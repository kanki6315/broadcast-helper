package com.broadcasthelper.formats;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Locale;

/**
 * Manage surface for per-series race formats: list with usage counts, rename,
 * merge, create custom formats, pin or reset a single session's assignment, and
 * a series-wide auto-assign backfill (the classification heuristic lives in
 * {@link RaceFormatService} and cannot run inside a Flyway migration).
 */
@RestController
@RequestMapping("/api")
public class RaceFormatController {

    private final JdbcClient db;
    private final RaceFormatService formats;

    public RaceFormatController(JdbcClient db, RaceFormatService formats) {
        this.db = db;
        this.formats = formats;
    }

    public record FormatRow(long id, String code, String name, int ordinal, int sessionCount) {
    }

    @GetMapping("/series/{seriesId}/race-formats")
    public List<FormatRow> list(@PathVariable long seriesId) {
        requireSeries(seriesId);
        return db.sql("""
                        SELECT rf.id, rf.code, rf.name, rf.ordinal,
                               (SELECT count(*) FROM race_session rs WHERE rs.format_id = rf.id) AS session_count
                        FROM race_format rf
                        WHERE rf.series_id = :seriesId
                        ORDER BY rf.ordinal, rf.name
                        """)
                .param("seriesId", seriesId)
                .query((rs, i) -> new FormatRow(rs.getLong("id"), rs.getString("code"),
                        rs.getString("name"), rs.getInt("ordinal"), rs.getInt("session_count")))
                .list();
    }

    public record CreateRequest(@NotBlank String name) {
    }

    @PostMapping("/series/{seriesId}/race-formats")
    public FormatRow create(@PathVariable long seriesId, @Valid @RequestBody CreateRequest request) {
        requireSeries(seriesId);
        String name = request.name().trim();
        // Custom formats get a slug code so the heuristic's reserved codes stay
        // distinct; a name collision on the slug is a 409, not a silent reuse.
        String code = "CUSTOM_" + name.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
        int inserted = db.sql("""
                        INSERT INTO race_format (series_id, code, name, ordinal)
                        VALUES (:seriesId, :code, :name, 10)
                        ON CONFLICT (series_id, code) DO NOTHING
                        """)
                .param("seriesId", seriesId)
                .param("code", code)
                .param("name", name)
                .update();
        if (inserted == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A format with that name already exists");
        }
        return db.sql("""
                        SELECT id, code, name, ordinal, 0 AS session_count
                        FROM race_format WHERE series_id = :seriesId AND code = :code
                        """)
                .param("seriesId", seriesId)
                .param("code", code)
                .query((rs, i) -> new FormatRow(rs.getLong("id"), rs.getString("code"),
                        rs.getString("name"), rs.getInt("ordinal"), 0))
                .single();
    }

    public record PatchRequest(String name, Integer ordinal) {
    }

    @PatchMapping("/race-formats/{id}")
    public FormatRow patch(@PathVariable long id, @RequestBody PatchRequest request) {
        if (request.name() != null && request.name().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name must not be blank");
        }
        int updated = db.sql("""
                        UPDATE race_format
                        SET name = COALESCE(:name, name), ordinal = COALESCE(:ordinal, ordinal)
                        WHERE id = :id
                        """)
                .param("name", request.name() != null ? request.name().trim() : null)
                .param("ordinal", request.ordinal())
                .param("id", id)
                .update();
        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such race format");
        }
        return db.sql("""
                        SELECT rf.id, rf.code, rf.name, rf.ordinal,
                               (SELECT count(*) FROM race_session rs WHERE rs.format_id = rf.id) AS session_count
                        FROM race_format rf WHERE rf.id = :id
                        """)
                .param("id", id)
                .query((rs, i) -> new FormatRow(rs.getLong("id"), rs.getString("code"),
                        rs.getString("name"), rs.getInt("ordinal"), rs.getInt("session_count")))
                .single();
    }

    public record MergeRequest(long intoId) {
    }

    /** Move every session of {id} onto {intoId} and delete {id}. Sessions keep
     *  their AUTO/MANUAL source; a later auto-assign may re-split AUTO ones,
     *  which is the honest behavior — merge is a data cleanup, not a rule. */
    @PostMapping("/race-formats/{id}/merge")
    @Transactional
    public FormatRow merge(@PathVariable long id, @RequestBody MergeRequest request) {
        if (id == request.intoId()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot merge a format into itself");
        }
        Long fromSeries = seriesOf(id);
        Long intoSeries = seriesOf(request.intoId());
        if (fromSeries == null || intoSeries == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such race format");
        }
        if (!fromSeries.equals(intoSeries)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Formats belong to different series");
        }
        db.sql("UPDATE race_session SET format_id = :intoId WHERE format_id = :id")
                .param("intoId", request.intoId())
                .param("id", id)
                .update();
        db.sql("DELETE FROM race_format WHERE id = :id").param("id", id).update();
        return patch(request.intoId(), new PatchRequest(null, null));
    }

    @DeleteMapping("/race-formats/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id) {
        Integer inUse = db.sql("SELECT count(*) FROM race_session WHERE format_id = :id")
                .param("id", id)
                .query(Integer.class)
                .single();
        if (inUse > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Format is assigned to " + inUse + " session" + (inUse == 1 ? "" : "s")
                    + " — merge or reassign them first");
        }
        int deleted = db.sql("DELETE FROM race_format WHERE id = :id").param("id", id).update();
        if (deleted == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such race format");
        }
    }

    public record SessionFormatRequest(Long formatId) {
    }

    /** Pin one session to a format (MANUAL), or reset it to AUTO with a null
     *  formatId — which immediately re-runs the heuristic for its event. */
    @PatchMapping("/sessions/{sessionId}/format")
    @Transactional
    public void setSessionFormat(@PathVariable long sessionId, @RequestBody SessionFormatRequest request) {
        record SessionRef(long eventId, String sessionType) {
        }
        SessionRef ref = db.sql("SELECT event_id, session_type FROM race_session WHERE id = :id")
                .param("id", sessionId)
                .query((rs, i) -> new SessionRef(rs.getLong("event_id"), rs.getString("session_type")))
                .optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such session"));
        if (!"RACE".equals(ref.sessionType())) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Only race sessions carry a format");
        }
        if (request.formatId() == null) {
            db.sql("UPDATE race_session SET format_id = NULL, format_source = 'AUTO' WHERE id = :id")
                    .param("id", sessionId)
                    .update();
            formats.autoAssignEvent(ref.eventId());
            return;
        }
        Long formatSeries = seriesOf(request.formatId());
        if (formatSeries == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such race format");
        }
        Long sessionSeries = db.sql("""
                        SELECT s.series_id FROM race_session rs
                                 JOIN event ev ON ev.id = rs.event_id
                                 JOIN season s ON s.id = ev.season_id
                        WHERE rs.id = :id
                        """)
                .param("id", sessionId)
                .query(Long.class)
                .single();
        if (!formatSeries.equals(sessionSeries)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Format belongs to a different series");
        }
        db.sql("UPDATE race_session SET format_id = :formatId, format_source = 'MANUAL' WHERE id = :id")
                .param("formatId", request.formatId())
                .param("id", sessionId)
                .update();
    }

    public record AutoAssignResult(int eventsProcessed, int sessionsAssigned) {
    }

    /** Backfill: run the heuristic over every event of every season of the
     *  series. Idempotent; MANUAL assignments survive. */
    @PostMapping("/series/{seriesId}/race-formats/auto-assign")
    @Transactional
    public AutoAssignResult autoAssign(@PathVariable long seriesId) {
        requireSeries(seriesId);
        List<Long> eventIds = db.sql("""
                        SELECT ev.id FROM event ev JOIN season s ON s.id = ev.season_id
                        WHERE s.series_id = :seriesId
                        ORDER BY ev.id
                        """)
                .param("seriesId", seriesId)
                .query(Long.class)
                .list();
        for (long eventId : eventIds) {
            formats.autoAssignEvent(eventId);
        }
        Integer assigned = db.sql("""
                        SELECT count(*) FROM race_session rs
                                 JOIN event ev ON ev.id = rs.event_id
                                 JOIN season s ON s.id = ev.season_id
                        WHERE s.series_id = :seriesId AND rs.format_id IS NOT NULL
                        """)
                .param("seriesId", seriesId)
                .query(Integer.class)
                .single();
        return new AutoAssignResult(eventIds.size(), assigned);
    }

    private Long seriesOf(long formatId) {
        return db.sql("SELECT series_id FROM race_format WHERE id = :id")
                .param("id", formatId)
                .query(Long.class)
                .optional()
                .orElse(null);
    }

    private void requireSeries(long seriesId) {
        Integer found = db.sql("SELECT 1 FROM series WHERE id = :id")
                .param("id", seriesId)
                .query(Integer.class)
                .optional()
                .orElse(null);
        if (found == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such series");
        }
    }
}
