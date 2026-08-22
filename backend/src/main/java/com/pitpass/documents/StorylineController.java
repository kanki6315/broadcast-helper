package com.pitpass.documents;

import com.pitpass.web.HttpCaching;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.time.OffsetDateTime;

/**
 * The event's storylines PDF — the series' pre-race narrative notes, read in
 * the booth as one document. Unlike team sheets there is nothing to extract:
 * upload stores the bytes as-is and the sheet page renders the whole PDF. One
 * document per event: re-uploading replaces it in place.
 */
@RestController
@RequestMapping("/api")
public class StorylineController {

    private static final String KIND = "STORYLINES";

    private final JdbcClient db;

    public StorylineController(JdbcClient db) {
        this.db = db;
    }

    public record Storylines(String filename, OffsetDateTime uploadedAt, long version) {
    }

    @GetMapping("/events/{eventId}/storylines")
    public Storylines get(@PathVariable long eventId) {
        return metadata(eventId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No storylines for this event"));
    }

    @PostMapping("/events/{eventId}/storylines")
    public Storylines upload(@PathVariable long eventId, @RequestParam("file") MultipartFile file) {
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
        db.sql("""
                        INSERT INTO event_document (event_id, kind, source_filename, content_type, data)
                        VALUES (:eventId, :kind, :filename, 'application/pdf', :data)
                        ON CONFLICT (event_id, kind) DO UPDATE
                            SET source_filename = EXCLUDED.source_filename,
                                data = EXCLUDED.data,
                                uploaded_at = now()
                        """)
                .param("eventId", eventId)
                .param("kind", KIND)
                .param("filename", file.getOriginalFilename())
                .param("data", data)
                .update();
        return get(eventId);
    }

    @GetMapping("/events/{eventId}/storylines/data")
    public ResponseEntity<byte[]> data(@PathVariable long eventId,
                                       @RequestParam(required = false) String v) {
        byte[] pdf = db.sql("SELECT data FROM event_document WHERE event_id = :eventId AND kind = :kind")
                .param("eventId", eventId).param("kind", KIND)
                .query(byte[].class)
                .optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No storylines for this event"));
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header("Cache-Control", HttpCaching.cacheControl(v != null))
                .body(pdf);
    }

    @DeleteMapping("/events/{eventId}/storylines")
    public void delete(@PathVariable long eventId) {
        int deleted = db.sql("DELETE FROM event_document WHERE event_id = :eventId AND kind = :kind")
                .param("eventId", eventId).param("kind", KIND)
                .update();
        if (deleted == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No storylines for this event");
        }
    }

    // ---------------------------------------------------------------- helpers

    private java.util.Optional<Storylines> metadata(long eventId) {
        return db.sql("""
                        SELECT source_filename, uploaded_at
                        FROM event_document WHERE event_id = :eventId AND kind = :kind
                        """)
                .param("eventId", eventId).param("kind", KIND)
                .query((rs, i) -> {
                    OffsetDateTime uploadedAt = rs.getObject("uploaded_at", OffsetDateTime.class);
                    return new Storylines(rs.getString("source_filename"), uploadedAt,
                            uploadedAt.toInstant().toEpochMilli());
                })
                .optional();
    }

    private void requireEvent(long eventId) {
        long count = db.sql("SELECT count(*) FROM event WHERE id = :id").param("id", eventId)
                .query(Long.class).single();
        if (count == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such event");
        }
    }
}
