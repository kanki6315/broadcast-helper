package com.pitpass.images;

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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.Locale;

/**
 * Series logos — one per series, reused on the landing directory and season
 * surfaces. Uploaded on the Manage tab. Serves at a version-stamped URL so the
 * browser caches the bytes immutably (see {@link HttpCaching}). Mirrors
 * {@link ManufacturerLogoController}; kept separate because the key is a
 * series id, not a normalized name.
 */
@RestController
@RequestMapping("/api/series")
public class SeriesLogoController {

    private final JdbcClient db;

    public SeriesLogoController(JdbcClient db) {
        this.db = db;
    }

    /** Epoch-millis version stamp of the current logo, for cache-busting URLs. */
    public record LogoVersion(Long logoVersion) {
    }

    @PostMapping("/{id}/logo")
    public LogoVersion upload(@PathVariable long id, @RequestParam("file") MultipartFile file) {
        Boolean exists = db.sql("SELECT true FROM series WHERE id = :id")
                .param("id", id)
                .query(Boolean.class)
                .optional()
                .orElse(false);
        if (!exists) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such series");
        }
        byte[] data;
        try {
            data = file.getBytes();
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Could not read upload: " + e.getMessage());
        }
        if (data.length == 0) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Empty file");
        }
        OffsetDateTime uploaded = db.sql("""
                        INSERT INTO series_logo (series_id, content_type, data)
                        VALUES (:id, :contentType, :data)
                        ON CONFLICT (series_id) DO UPDATE
                            SET content_type = EXCLUDED.content_type,
                                data = EXCLUDED.data,
                                uploaded_at = now()
                        RETURNING uploaded_at
                        """)
                .param("id", id)
                .param("contentType", contentType(file))
                .param("data", data)
                .query(OffsetDateTime.class)
                .single();
        return new LogoVersion(uploaded.toInstant().toEpochMilli());
    }

    @GetMapping("/{id}/logo/data")
    public ResponseEntity<byte[]> data(@PathVariable long id,
                                       @RequestParam(required = false) String v) {
        return db.sql("SELECT content_type, data FROM series_logo WHERE series_id = :id")
                .param("id", id)
                .query((rs, i) -> ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType(rs.getString("content_type")))
                        .header("Cache-Control", HttpCaching.cacheControl(v != null))
                        .body(rs.getBytes("data")))
                .optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No logo for that series"));
    }

    @DeleteMapping("/{id}/logo")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id) {
        int deleted = db.sql("DELETE FROM series_logo WHERE series_id = :id")
                .param("id", id)
                .update();
        if (deleted == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such logo");
        }
    }

    private static String contentType(MultipartFile file) {
        String declared = file.getContentType();
        if (declared != null && declared.toLowerCase(Locale.ROOT).startsWith("image/")) {
            return declared;
        }
        String fn = file.getOriginalFilename() != null ? file.getOriginalFilename().toLowerCase(Locale.ROOT) : "";
        if (fn.endsWith(".svg")) return "image/svg+xml";
        if (fn.endsWith(".png")) return "image/png";
        if (fn.endsWith(".jpg") || fn.endsWith(".jpeg")) return "image/jpeg";
        if (fn.endsWith(".webp")) return "image/webp";
        throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                "Not a recognized image type: " + file.getOriginalFilename());
    }
}
