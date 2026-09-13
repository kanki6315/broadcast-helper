package com.pitpass.images;

import com.pitpass.web.HttpCaching;
import org.springframework.http.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Component
public class LogoAssets {
    public enum Kind {
        MANUFACTURER("manufacturer_logo", "name", "manufacturer-logos"),
        SERIES("series_logo", "series_id", "series-logos"),
        DRIVER("driver_photo", "driver_id", "driver-photos");
        final String table, column, prefix;
        Kind(String table, String column, String prefix) { this.table = table; this.column = column; this.prefix = prefix; }
        Object key(String target) { return this == MANUFACTURER ? target : Long.valueOf(target); }
        public String normalize(String target) {
            if (this == MANUFACTURER) return target.trim().toLowerCase(Locale.ROOT);
            try {
                long id = Long.parseLong(target.trim());
                if (id <= 0) throw new NumberFormatException();
                return Long.toString(id);
            } catch (NumberFormatException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A positive ID is required");
            }
        }
    }
    public record Asset(String target, String contentType, OffsetDateTime uploadedAt,
                        String objectKey, String logoUrl, boolean publicStorage) {}
    private final JdbcClient db;
    private final PublicImageStorage storage;
    public LogoAssets(JdbcClient db, PublicImageStorage storage) { this.db = db; this.storage = storage; }

    public String url(Kind kind, String target, Long version, String key) {
        if (key != null) return storage.publicUrl(key).toString();
        if (version == null) return null;
        String encoded = UriUtils.encodePathSegment(kind.normalize(target), StandardCharsets.UTF_8);
        if (kind == Kind.DRIVER) return "/api/drivers/" + encoded + "/photo?v=" + version;
        return kind == Kind.SERIES ? "/api/series/" + encoded + "/logo/data?v=" + version
                : "/api/manufacturer-logos/" + encoded + "/data?v=" + version;
    }

    public List<Asset> list(Kind kind) {
        return query(kind, null, false);
    }
    public Optional<Asset> find(Kind kind, String target, boolean lock) {
        return query(kind, kind.normalize(target), lock).stream().findFirst();
    }
    private List<Asset> query(Kind kind, String target, boolean lock) {
        // Identifiers come only from the closed enum, never request strings.
        var query = db.sql("SELECT " + kind.column + " AS target, content_type, uploaded_at, object_key FROM " + kind.table
                + (target == null ? " ORDER BY " + kind.column : " WHERE " + kind.column + " = :target")
                + (lock ? " FOR UPDATE" : ""));
        if (target != null) query = query.param("target", kind.key(target));
        return query.query((rs, i) -> {
            OffsetDateTime date = rs.getObject("uploaded_at", OffsetDateTime.class);
            String key = rs.getString("object_key");
            String name = rs.getString("target");
            return new Asset(name, rs.getString("content_type"), date, key,
                    url(kind, name, date.toInstant().toEpochMilli(), key), key != null);
        }).list();
    }

    public ResponseEntity<byte[]> data(Kind kind, String target, boolean versioned) {
        Asset asset = find(kind, target, false).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such logo"));
        if (asset.publicStorage()) return ResponseEntity.status(HttpStatus.FOUND)
                .location(storage.publicUrl(asset.objectKey())).header("Cache-Control", HttpCaching.cacheControl(versioned)).build();
        byte[] data = db.sql("SELECT data FROM " + kind.table + " WHERE " + kind.column + " = :target")
                .param("target", kind.key(asset.target())).query(byte[].class).single();
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(asset.contentType()))
                .header("Cache-Control", HttpCaching.cacheControl(versioned)).body(data);
    }
}
