package com.pitpass.images;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.*;
import java.time.OffsetDateTime;
import java.util.List;

/** Metadata-only inventory; each file is migrated in its own existing upload transaction. */
@RestController
@RequestMapping("/api/public-storage/migration")
public class PublicStorageMigrationController {
    private final JdbcClient db;
    private final PublicImageStorage storage;
    private final CarImageUrls urls;
    private final LogoAssets logos;
    public PublicStorageMigrationController(JdbcClient db, PublicImageStorage storage, CarImageUrls urls, LogoAssets logos) {
        this.db = db; this.storage = storage; this.urls = urls; this.logos = logos;
    }
    public record Photo(long id, long seasonId, String carNumber, String filename, String contentType,
                        OffsetDateTime uploadedAt, String originalUrl) {}
    public record Logo(LogoAssets.Kind kind, LogoAssets.Asset asset) {}
    public record Document(long id, String filename) {}
    public record Inventory(boolean enabled, List<Photo> photos, List<Logo> logos, List<Document> documents) {}

    // POST uses the existing admin-only security rule, even though inventory is read-only.
    @PostMapping
    public Inventory inventory() {
        if (!storage.enabled()) return new Inventory(false, List.of(), List.of(), List.of());
        var photos = db.sql("SELECT id, season_id, car_number, source_filename, content_type, uploaded_at FROM car_image WHERE original_object_key IS NULL ORDER BY season_id, id")
                .query((rs, i) -> {
                    var date = rs.getObject("uploaded_at", OffsetDateTime.class);
                    return new Photo(rs.getLong("id"), rs.getLong("season_id"), rs.getString("car_number"),
                            rs.getString("source_filename"), rs.getString("content_type"), date,
                            urls.original(rs.getLong("id"), date.toInstant().toEpochMilli(), null));
                }).list();
        var marks = java.util.Arrays.stream(LogoAssets.Kind.values()).flatMap(kind -> logos.list(kind).stream()
                .filter(asset -> !asset.publicStorage()).map(asset -> new Logo(kind, asset))).toList();
        var documents = db.sql("SELECT id, source_filename FROM event_document WHERE object_key IS NULL ORDER BY id")
                .query((rs, i) -> new Document(rs.getLong("id"), rs.getString("source_filename"))).list();
        return new Inventory(true, photos, marks, documents);
    }
}
