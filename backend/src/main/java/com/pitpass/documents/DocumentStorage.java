package com.pitpass.documents;

import com.pitpass.images.PublicImageStorage;
import com.pitpass.web.HttpCaching;
import org.springframework.http.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.*;
import java.time.OffsetDateTime;
import java.util.*;

@Service
public class DocumentStorage {
    private final JdbcClient db;
    private final PublicImageStorage storage;
    public DocumentStorage(JdbcClient db, PublicImageStorage storage) { this.db = db; this.storage = storage; }

    public static final class Upload implements AutoCloseable {
        private final Path path;
        private final PublicImageStorage storage;
        private String key;
        Upload(Path path, PublicImageStorage storage) { this.path = path; this.storage = storage; }
        public Path path() { return path; }
        public String key() { return key; }
        /** Publish only after parsing succeeds. No PDF-sized Java array when R2 is enabled. */
        public void persist() {
            key = "documents/" + UUID.randomUUID() + "/document.pdf";
            storage.uploadPdf(key, path);
        }
        public void close() {
            try { Files.deleteIfExists(path); }
            catch (IOException e) { throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not remove temporary PDF", e); }
        }
    }

    public Upload receive(MultipartFile file) {
        if (!storage.enabled()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Public storage is not configured");
        Path path = null;
        try {
            if (file.isEmpty() || file.getSize() > 25 * 1024 * 1024)
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Choose a PDF up to 25 MB");
            path = Files.createTempFile("pitpass-document-", ".pdf");
            file.transferTo(path);
            try (var input = Files.newInputStream(path)) {
                if (!Arrays.equals(input.readNBytes(4), new byte[]{'%', 'P', 'D', 'F'}))
                    throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Not a PDF");
            }
            return new Upload(path, storage);
        } catch (IOException | RuntimeException e) {
            if (path != null) try { Files.deleteIfExists(path); } catch (IOException cleanup) { e.addSuppressed(cleanup); }
            if (e instanceof RuntimeException runtime) throw runtime;
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Could not read PDF upload", e);
        }
    }

    public String url(long eventId, String kind) {
        return db.sql("SELECT object_key, uploaded_at FROM event_document WHERE event_id = :id AND kind = :kind")
                .param("id", eventId).param("kind", kind).query((rs, i) -> {
                    String key = rs.getString("object_key");
                    return storage.publicUrl(key).toString();
                }).optional().orElse(null);
    }

    public ResponseEntity<byte[]> data(long eventId, String kind, boolean versioned) {
        String url = url(eventId, kind);
        if (url == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such document");
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(java.net.URI.create(url)).header("Cache-Control", HttpCaching.cacheControl(versioned)).build();
    }
}
