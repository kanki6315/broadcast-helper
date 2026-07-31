package com.pitpass.images;

import com.pitpass.web.HttpCaching;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;

/**
 * Manufacturer logos, uploaded once and reused on every sheet. Matched to
 * entries by the normalized manufacturer name. The management view lists every
 * manufacturer seen across entries so it doubles as a coverage checklist.
 */
@RestController
@RequestMapping("/api")
public class ManufacturerLogoController {

    private final JdbcClient db;

    public ManufacturerLogoController(JdbcClient db) {
        this.db = db;
    }

    public record ManufacturerRow(String name, long entryCount, Long logoVersion, Boolean invertOnDark) {
    }

    /** Every manufacturer seen on an entry, with whether a logo is uploaded. */
    @GetMapping("/manufacturers")
    public List<ManufacturerRow> manufacturers() {
        return db.sql("""
                        SELECT en.manufacturer AS name, count(*) AS entry_count, ml.uploaded_at, ml.invert_on_dark
                        FROM entry en
                                 LEFT JOIN manufacturer_logo ml ON ml.name = lower(trim(en.manufacturer))
                        WHERE en.manufacturer IS NOT NULL AND en.manufacturer <> ''
                        GROUP BY en.manufacturer, ml.uploaded_at, ml.invert_on_dark
                        ORDER BY en.manufacturer
                        """)
                .query((rs, i) -> {
                    OffsetDateTime uploaded = rs.getObject("uploaded_at", OffsetDateTime.class);
                    return new ManufacturerRow(rs.getString("name"), rs.getLong("entry_count"),
                            uploaded != null ? uploaded.toInstant().toEpochMilli() : null,
                            rs.getObject("invert_on_dark", Boolean.class));
                })
                .list();
    }

    @PostMapping("/manufacturer-logos")
    public ManufacturerRow upload(@RequestParam String name, @RequestParam("file") MultipartFile file) {
        String display = name.trim();
        if (display.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Manufacturer name is required");
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
        db.sql("""
                        INSERT INTO manufacturer_logo (name, display_name, content_type, data)
                        VALUES (:name, :display, :contentType, :data)
                        ON CONFLICT (name) DO UPDATE
                            SET display_name = EXCLUDED.display_name,
                                content_type = EXCLUDED.content_type,
                                data = EXCLUDED.data,
                                uploaded_at = now()
                        """)
                .param("name", display.toLowerCase(Locale.ROOT))
                .param("display", display)
                .param("contentType", contentType(file))
                .param("data", data)
                .update();
        return db.sql("""
                        SELECT en.manufacturer AS name, count(*) AS entry_count, ml.uploaded_at, ml.invert_on_dark
                        FROM entry en JOIN manufacturer_logo ml ON ml.name = lower(trim(en.manufacturer))
                        WHERE lower(trim(en.manufacturer)) = :name
                        GROUP BY en.manufacturer, ml.uploaded_at, ml.invert_on_dark
                        """)
                .param("name", display.toLowerCase(Locale.ROOT))
                .query((rs, i) -> new ManufacturerRow(rs.getString("name"), rs.getLong("entry_count"),
                        rs.getObject("uploaded_at", OffsetDateTime.class).toInstant().toEpochMilli(),
                        rs.getBoolean("invert_on_dark")))
                .optional()
                .orElse(new ManufacturerRow(display, 0, System.currentTimeMillis(), false));
    }

    public record InvertRequest(boolean invertOnDark) {
    }

    /**
     * Dark-theme treatment for one logo: recolour it white (monochrome
     * wordmarks) instead of the default white pill (multi-colour badges).
     * Survives a re-upload — the flag describes the mark, not the file.
     */
    @PutMapping("/manufacturer-logos/{name}/invert")
    public void setInvert(@org.springframework.web.bind.annotation.PathVariable String name,
                          @RequestBody InvertRequest request) {
        int updated = db.sql("UPDATE manufacturer_logo SET invert_on_dark = :invert WHERE name = :name")
                .param("invert", request.invertOnDark())
                .param("name", name.toLowerCase(Locale.ROOT))
                .update();
        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such logo");
        }
    }

    @GetMapping("/manufacturer-logos/{name}/data")
    public ResponseEntity<byte[]> data(@org.springframework.web.bind.annotation.PathVariable String name,
                                       @RequestParam(required = false) String v) {
        return db.sql("SELECT content_type, data FROM manufacturer_logo WHERE name = :name")
                .param("name", name.toLowerCase(Locale.ROOT))
                .query((rs, i) -> ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType(rs.getString("content_type")))
                        .header("Cache-Control", HttpCaching.cacheControl(v != null))
                        .body(rs.getBytes("data")))
                .optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No logo for that manufacturer"));
    }

    @DeleteMapping("/manufacturer-logos/{name}")
    public void delete(@org.springframework.web.bind.annotation.PathVariable String name) {
        int deleted = db.sql("DELETE FROM manufacturer_logo WHERE name = :name")
                .param("name", name.toLowerCase(Locale.ROOT))
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
