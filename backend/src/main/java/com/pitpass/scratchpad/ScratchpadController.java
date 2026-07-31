package com.pitpass.scratchpad;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;

/**
 * The drawing scratchpad (see {@code event_scratchpad}, V39): one freehand
 * pad per event per signed-in user, stored as an opaque JSONB array of
 * strokes the SPA draws and replays itself. The owner is always the caller —
 * the email never appears in the path or body, so a viewer can only ever
 * touch their own pad, which is why PUT gets a member() carve-out in
 * {@link com.pitpass.auth.SecurityConfig} instead of the admin fail-close.
 * Saves are whole-document swaps guarded by an optimistic revision: a stale
 * tab's PUT answers 409 and the SPA offers a reload, never a silent
 * overwrite.
 */
@RestController
@RequestMapping("/api")
public class ScratchpadController {

    /** ~2000+ strokes — unreachable in normal use; the SPA surfaces "pad full". */
    private static final int MAX_STROKES_BYTES = 2_000_000;
    private static final int DEFAULT_PAGE_HEIGHT = 2000;
    /** Local dev runs the permit-all chain with no principal; a fixed
     *  sentinel gives dev exactly one pad per event. */
    static final String DEV_OWNER = "dev@local";

    private final JdbcClient db;
    private final ObjectMapper json;

    public ScratchpadController(JdbcClient db, ObjectMapper json) {
        this.db = db;
        this.json = json;
    }

    public record Pad(long eventId, long revision, int pageHeight, JsonNode strokes) {
    }

    public record SaveRequest(long baseRevision, int pageHeight, JsonNode strokes) {
    }

    public record SaveResponse(long revision, OffsetDateTime updatedAt) {
    }

    @GetMapping("/events/{eventId}/scratchpad")
    public Pad get(@PathVariable long eventId, @AuthenticationPrincipal OidcUser caller) {
        requireEvent(eventId);
        record Row(long revision, int pageHeight, String strokes) {
        }
        return db.sql("""
                        SELECT revision, page_height, strokes::text AS strokes
                        FROM event_scratchpad
                        WHERE event_id = :eventId AND lower(owner_email) = lower(:owner)
                        """)
                .param("eventId", eventId)
                .param("owner", owner(caller))
                .query((rs, i) -> new Row(rs.getLong("revision"), rs.getInt("page_height"),
                        rs.getString("strokes")))
                .optional()
                .map(r -> new Pad(eventId, r.revision(), r.pageHeight(), parseStrokes(r.strokes())))
                // No row yet: revision 0 tells the SPA to send baseRevision 0
                // on its first save, which takes the insert path below.
                .orElseGet(() -> new Pad(eventId, 0, DEFAULT_PAGE_HEIGHT, json.createArrayNode()));
    }

    @PutMapping("/events/{eventId}/scratchpad")
    public SaveResponse save(@PathVariable long eventId, @AuthenticationPrincipal OidcUser caller,
                             @RequestBody SaveRequest request) {
        requireEvent(eventId);
        if (request.strokes() == null || !request.strokes().isArray()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "strokes must be an array");
        }
        if (request.pageHeight() < 500 || request.pageHeight() > 50000) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "pageHeight out of range");
        }
        String strokes = request.strokes().toString();
        if (strokes.length() > MAX_STROKES_BYTES) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                    "Scratchpad is full — erase some strokes to keep drawing");
        }
        String owner = owner(caller);
        record Saved(long revision, OffsetDateTime updatedAt) {
        }
        Saved saved;
        if (request.baseRevision() == 0) {
            // First save: insert. A concurrent first save from another tab
            // conflicts on the unique key and yields zero rows — same stale
            // outcome as a lost update.
            saved = db.sql("""
                            INSERT INTO event_scratchpad (event_id, owner_email, strokes, page_height)
                            VALUES (:eventId, :owner, CAST(:strokes AS jsonb), :pageHeight)
                            ON CONFLICT (event_id, lower(owner_email)) DO NOTHING
                            RETURNING revision, updated_at
                            """)
                    .param("eventId", eventId)
                    .param("owner", owner)
                    .param("strokes", strokes)
                    .param("pageHeight", request.pageHeight())
                    .query((rs, i) -> new Saved(rs.getLong("revision"),
                            rs.getObject("updated_at", OffsetDateTime.class)))
                    .optional()
                    .orElse(null);
        } else {
            saved = db.sql("""
                            UPDATE event_scratchpad
                            SET strokes = CAST(:strokes AS jsonb), page_height = :pageHeight,
                                revision = revision + 1, updated_at = now()
                            WHERE event_id = :eventId AND lower(owner_email) = lower(:owner)
                              AND revision = :baseRevision
                            RETURNING revision, updated_at
                            """)
                    .param("eventId", eventId)
                    .param("owner", owner)
                    .param("strokes", strokes)
                    .param("pageHeight", request.pageHeight())
                    .param("baseRevision", request.baseRevision())
                    .query((rs, i) -> new Saved(rs.getLong("revision"),
                            rs.getObject("updated_at", OffsetDateTime.class)))
                    .optional()
                    .orElse(null);
        }
        if (saved == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Scratchpad changed elsewhere — reload it before saving");
        }
        return new SaveResponse(saved.revision(), saved.updatedAt());
    }

    private String owner(OidcUser caller) {
        return caller != null ? caller.getEmail() : DEV_OWNER;
    }

    private JsonNode parseStrokes(String stored) {
        try {
            return json.readTree(stored);
        } catch (JsonProcessingException e) {
            // Unreachable: the column is jsonb, so stored text is valid JSON.
            throw new IllegalStateException("Corrupt scratchpad JSON", e);
        }
    }

    private void requireEvent(long eventId) {
        Integer found = db.sql("SELECT 1 FROM event WHERE id = :id")
                .param("id", eventId)
                .query(Integer.class)
                .optional()
                .orElse(null);
        if (found == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such event");
        }
    }
}
