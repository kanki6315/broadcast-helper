package com.pitpass.images;

import com.pitpass.web.HttpCaching;
import org.springframework.http.HttpStatus;
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

    private final PublicImageStorage storage;
    private final LogoAssets assets;
    public SeriesLogoController(JdbcClient db, PublicImageStorage storage, LogoAssets assets) {
        this.db = db; this.storage = storage; this.assets = assets;
    }

    /** Epoch-millis version stamp of the current logo, for cache-busting URLs. */
    public record LogoVersion(Long logoVersion, String logoUrl) {
    }

    @PostMapping("/{id}/logo")
    public LogoVersion upload(@PathVariable long id, @RequestParam("file") MultipartFile file) {
        throw new ResponseStatusException(HttpStatus.CONFLICT, "Upload directly to public storage. Reload the app before uploading.");
    }

    @GetMapping("/{id}/logo/data")
    public ResponseEntity<byte[]> data(@PathVariable long id,
                                       @RequestParam(required = false) String v) {
        return assets.data(LogoAssets.Kind.SERIES, Long.toString(id), v != null);
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

}
