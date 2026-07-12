package com.broadcasthelper.documents;

import com.broadcasthelper.web.HttpCaching;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Per-event reference documents — currently the series' team-sheets PDF (one
 * team/driver-bio section per car). Upload extracts the car-number -> page map
 * via the Python sidecar (parser/extract_team_sheet_pages.py) so the sheet
 * page can deep-link each entry row to its team's section; mappings are
 * manually correctable afterwards. One document per event: re-uploading an
 * updated PDF replaces bytes and map in place.
 */
@RestController
@RequestMapping("/api")
public class EventDocumentController {

    private static final String KIND = "TEAM_SHEETS";

    private final JdbcClient db;
    private final ObjectMapper json;
    private final String parserPython;
    private final String parserScript;

    public EventDocumentController(JdbcClient db, ObjectMapper json,
                                   @Value("${broadcast-helper.entry-list-parser.python:python3}") String parserPython,
                                   @Value("${broadcast-helper.team-sheet-parser.script:../parser/extract_team_sheet_pages.py}") String parserScript) {
        this.db = db;
        this.json = json;
        this.parserPython = parserPython;
        this.parserScript = parserScript;
    }

    public record PageMapping(String carNumber, int page, String teamName) {
    }

    public record TeamSheets(String filename, OffsetDateTime uploadedAt, long version, Integer pageCount,
                             List<PageMapping> pages) {
    }

    @GetMapping("/events/{eventId}/team-sheets")
    public TeamSheets get(@PathVariable long eventId) {
        return metadata(eventId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No team sheets for this event"));
    }

    @PostMapping("/events/{eventId}/team-sheets")
    @Transactional
    public TeamSheets upload(@PathVariable long eventId, @RequestParam("file") MultipartFile file) {
        requireEvent(eventId);
        byte[] data;
        try {
            data = file.getBytes();
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Could not read upload: " + e.getMessage());
        }
        if (data.length < 5 || data[0] != '%' || data[1] != 'P' || data[2] != 'D' || data[3] != 'F') {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Not a PDF: " + file.getOriginalFilename());
        }

        JsonNode map = runPageMapParser(file.getOriginalFilename(), data);
        Integer pageCount = map.path("page_count").isNumber() ? map.get("page_count").asInt() : null;

        long documentId = db.sql("""
                        INSERT INTO event_document (event_id, kind, source_filename, content_type, data, page_count)
                        VALUES (:eventId, :kind, :filename, 'application/pdf', :data, :pageCount)
                        ON CONFLICT (event_id, kind) DO UPDATE
                            SET source_filename = EXCLUDED.source_filename,
                                data = EXCLUDED.data,
                                page_count = EXCLUDED.page_count,
                                uploaded_at = now()
                        RETURNING id
                        """)
                .param("eventId", eventId)
                .param("kind", KIND)
                .param("filename", file.getOriginalFilename())
                .param("data", data)
                .param("pageCount", pageCount)
                .query(Long.class)
                .single();

        // The extracted map fully replaces the previous one, including any manual
        // overrides — an updated PDF renumbers pages, so stale overrides would
        // point at the wrong team.
        db.sql("DELETE FROM event_document_page WHERE document_id = :id").param("id", documentId).update();
        for (JsonNode car : map.path("cars")) {
            db.sql("""
                            INSERT INTO event_document_page (document_id, car_number, page, team_name)
                            VALUES (:id, :number, :page, :team)
                            """)
                    .param("id", documentId)
                    .param("number", car.path("car_number").asText())
                    .param("page", car.path("page").asInt())
                    .param("team", car.path("team").isTextual() ? car.get("team").asText() : null)
                    .update();
        }
        return get(eventId);
    }

    @GetMapping("/events/{eventId}/team-sheets/data")
    public ResponseEntity<byte[]> data(@PathVariable long eventId,
                                       @RequestParam(required = false) String v) {
        byte[] pdf = db.sql("SELECT data FROM event_document WHERE event_id = :eventId AND kind = :kind")
                .param("eventId", eventId).param("kind", KIND)
                .query(byte[].class)
                .optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No team sheets for this event"));
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header("Cache-Control", HttpCaching.cacheControl(v != null))
                .body(pdf);
    }

    public record PageUpdate(String carNumber, Integer page) {
    }

    /** Manual mapping fix: set a car's page, or clear it with a null page. */
    @PatchMapping("/events/{eventId}/team-sheets/pages")
    public TeamSheets updatePage(@PathVariable long eventId, @RequestBody PageUpdate update) {
        if (update.carNumber() == null || update.carNumber().isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Car number is required");
        }
        record Doc(long id, Integer pageCount) {
        }
        Doc doc = db.sql("SELECT id, page_count FROM event_document WHERE event_id = :eventId AND kind = :kind")
                .param("eventId", eventId).param("kind", KIND)
                .query((rs, i) -> new Doc(rs.getLong("id"), rs.getObject("page_count", Integer.class)))
                .optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No team sheets for this event"));
        String number = update.carNumber().trim();
        if (update.page() == null) {
            db.sql("DELETE FROM event_document_page WHERE document_id = :id AND car_number = :number")
                    .param("id", doc.id()).param("number", number).update();
        } else {
            if (update.page() < 1 || (doc.pageCount() != null && update.page() > doc.pageCount())) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "Page must be between 1 and " + (doc.pageCount() != null ? doc.pageCount() : "the last page"));
            }
            db.sql("""
                            INSERT INTO event_document_page (document_id, car_number, page)
                            VALUES (:id, :number, :page)
                            ON CONFLICT (document_id, car_number) DO UPDATE SET page = EXCLUDED.page
                            """)
                    .param("id", doc.id()).param("number", number).param("page", update.page())
                    .update();
        }
        return get(eventId);
    }

    @DeleteMapping("/events/{eventId}/team-sheets")
    public void delete(@PathVariable long eventId) {
        int deleted = db.sql("DELETE FROM event_document WHERE event_id = :eventId AND kind = :kind")
                .param("eventId", eventId).param("kind", KIND)
                .update();
        if (deleted == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No team sheets for this event");
        }
    }

    // ---------------------------------------------------------------- helpers

    private java.util.Optional<TeamSheets> metadata(long eventId) {
        record Doc(long id, String filename, OffsetDateTime uploadedAt, Integer pageCount) {
        }
        return db.sql("""
                        SELECT id, source_filename, uploaded_at, page_count
                        FROM event_document WHERE event_id = :eventId AND kind = :kind
                        """)
                .param("eventId", eventId).param("kind", KIND)
                .query((rs, i) -> new Doc(rs.getLong("id"), rs.getString("source_filename"),
                        rs.getObject("uploaded_at", OffsetDateTime.class), rs.getObject("page_count", Integer.class)))
                .optional()
                .map(doc -> {
                    List<PageMapping> pages = db.sql("""
                                    SELECT car_number, page, team_name FROM event_document_page
                                    WHERE document_id = :id ORDER BY page, car_number
                                    """)
                            .param("id", doc.id())
                            .query((rs, i) -> new PageMapping(rs.getString("car_number"), rs.getInt("page"),
                                    rs.getString("team_name")))
                            .list();
                    return new TeamSheets(doc.filename(), doc.uploadedAt(),
                            doc.uploadedAt().toInstant().toEpochMilli(), doc.pageCount(), pages);
                });
    }

    private void requireEvent(long eventId) {
        long count = db.sql("SELECT count(*) FROM event WHERE id = :id").param("id", eventId)
                .query(Long.class).single();
        if (count == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such event");
        }
    }

    /** Runs the sidecar (parser/extract_team_sheet_pages.py): PDF in, page-map JSON out. */
    private JsonNode runPageMapParser(String filename, byte[] pdf) {
        try {
            java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("team-sheets-");
            String safeName = java.nio.file.Path.of(filename == null ? "team-sheets.pdf" : filename)
                    .getFileName().toString();
            java.nio.file.Path tmp = dir.resolve(safeName);
            try {
                java.nio.file.Files.write(tmp, pdf);
                Process process = new ProcessBuilder(parserPython, parserScript, tmp.toString())
                        .redirectErrorStream(false)
                        .start();
                byte[] out = process.getInputStream().readAllBytes();
                String err = new String(process.getErrorStream().readAllBytes());
                if (!process.waitFor(120, java.util.concurrent.TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                            "Team-sheets parser timed out on " + filename);
                }
                if (process.exitValue() != 0) {
                    throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                            "Team-sheets parser failed on " + filename + ": " + err.trim());
                }
                return json.readTree(out);
            } finally {
                java.nio.file.Files.deleteIfExists(tmp);
                java.nio.file.Files.deleteIfExists(dir);
            }
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Could not run team-sheets parser (" + parserPython + " " + parserScript + "): " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Team-sheets parser interrupted");
        }
    }
}
