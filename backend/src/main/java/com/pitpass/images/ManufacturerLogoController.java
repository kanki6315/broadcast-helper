package com.pitpass.images;

import org.springframework.http.HttpStatus;
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

    private final PublicImageStorage storage;
    private final LogoAssets assets;
    public ManufacturerLogoController(JdbcClient db, PublicImageStorage storage, LogoAssets assets) {
        this.db = db; this.storage = storage; this.assets = assets;
    }

    public record ManufacturerRow(String name, long entryCount, Long logoVersion, Boolean invertOnDark, String logoUrl, boolean publicStorage) {
    }

    /** Every manufacturer seen on an entry, with whether a logo is uploaded. */
    @GetMapping("/manufacturers")
    public List<ManufacturerRow> manufacturers() {
        return db.sql("""
                        SELECT en.manufacturer AS name, count(*) AS entry_count, ml.uploaded_at, ml.invert_on_dark, ml.object_key
                        FROM entry en
                                 LEFT JOIN manufacturer_logo ml ON ml.name = lower(trim(en.manufacturer))
                        WHERE en.manufacturer IS NOT NULL AND en.manufacturer <> ''
                        GROUP BY en.manufacturer, ml.uploaded_at, ml.invert_on_dark, ml.object_key
                        ORDER BY en.manufacturer
                        """)
                .query((rs, i) -> {
                    OffsetDateTime uploaded = rs.getObject("uploaded_at", OffsetDateTime.class);
                    return new ManufacturerRow(rs.getString("name"), rs.getLong("entry_count"),
                            uploaded != null ? uploaded.toInstant().toEpochMilli() : null,
                            rs.getObject("invert_on_dark", Boolean.class),
                            assets.url(LogoAssets.Kind.MANUFACTURER, rs.getString("name"), uploaded != null ? uploaded.toInstant().toEpochMilli() : null, rs.getString("object_key")),
                            rs.getString("object_key") != null);
                })
                .list();
    }

    @PostMapping("/manufacturer-logos")
    public ManufacturerRow upload(@RequestParam String name, @RequestParam("file") MultipartFile file) {
        throw new ResponseStatusException(HttpStatus.CONFLICT, "Upload directly to public storage. Reload the app before uploading.");
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
        return assets.data(LogoAssets.Kind.MANUFACTURER, name, v != null);
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

}
