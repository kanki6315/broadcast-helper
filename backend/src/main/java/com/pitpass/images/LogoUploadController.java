package com.pitpass.images;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/logo-uploads")
public class LogoUploadController {
    private static final Set<String> TYPES = Set.of("image/svg+xml", "image/png", "image/jpeg", "image/webp", "image/gif");
    private final JdbcClient db;
    private final PublicImageStorage storage;
    private final LogoAssets assets;
    public LogoUploadController(JdbcClient db, PublicImageStorage storage, LogoAssets assets) {
        this.db = db; this.storage = storage; this.assets = assets;
    }
    public record Prepare(@NotNull LogoAssets.Kind kind, @NotBlank @Size(max = 255) String target,
                          @NotBlank String contentType, @Min(1) @Max(26214400) long size,
                          OffsetDateTime sourceUploadedAt) {}
    public record Plan(UUID id, String uploadUrl) {}
    private record Ticket(LogoAssets.Kind kind, String target, String contentType, long size,
                          OffsetDateTime expires, boolean completed) {}

    @GetMapping("/assets")
    public List<LogoAssets.Asset> list(@RequestParam LogoAssets.Kind kind) { return assets.list(kind); }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Plan prepare(@Valid @RequestBody Prepare request) {
        if (!storage.enabled()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Image storage is not configured");
        if (!TYPES.contains(request.contentType())) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Choose an SVG, PNG, JPEG, WebP, or GIF logo");
        String target = request.kind().normalize(request.target());
        if (request.kind() == LogoAssets.Kind.SERIES && !db.sql("SELECT EXISTS(SELECT 1 FROM series WHERE id = :id)")
                .param("id", Long.valueOf(target)).query(Boolean.class).single())
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such series");
        if (request.kind() == LogoAssets.Kind.DRIVER) {
            if (request.contentType().equals("image/svg+xml")) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Choose a raster photo");
            if (!db.sql("SELECT EXISTS(SELECT 1 FROM driver WHERE id = :id)").param("id", Long.valueOf(target)).query(Boolean.class).single())
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such driver");
        }
        if (request.sourceUploadedAt() != null)
            throw new ResponseStatusException(HttpStatus.GONE, "Database migration has been retired");
        UUID id = UUID.randomUUID();
        String url = storage.uploadUrl(staging(id), request.contentType(), request.size());
        db.sql("""
                INSERT INTO logo_upload(id, kind, target, content_type, size, expires_at)
                VALUES (:id, :kind, :target, :type, :size, now() + interval '30 minutes')
                """).param("id", id).param("kind", request.kind().name()).param("target", target)
                .param("type", request.contentType()).param("size", request.size()).update();
        return new Plan(id, url);
    }

    @PostMapping("/{id}/complete")
    @Transactional
    public LogoAssets.Asset complete(@PathVariable UUID id) {
        Ticket ticket = db.sql("SELECT * FROM logo_upload WHERE id = :id FOR UPDATE").param("id", id)
                .query((rs, i) -> new Ticket(LogoAssets.Kind.valueOf(rs.getString("kind")), rs.getString("target"),
                        rs.getString("content_type"), rs.getLong("size"), rs.getObject("expires_at", OffsetDateTime.class),
                        rs.getBoolean("completed")))
                .optional().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such upload"));
        if (ticket.completed()) return assets.find(ticket.kind(), ticket.target(), false)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.GONE, "This logo was deleted"));
        if (ticket.expires().isBefore(OffsetDateTime.now())) throw new ResponseStatusException(HttpStatus.GONE, "Upload expired; select the logo again");
        String key = ticket.kind().prefix + "/" + id + "/logo";
        storage.publish(staging(id), key, ticket.contentType(), ticket.size());
        String sql = ticket.kind() == LogoAssets.Kind.DRIVER ? """
                INSERT INTO driver_photo(driver_id, content_type, object_key) VALUES (:target, :type, :key)
                ON CONFLICT(driver_id) DO UPDATE SET content_type = EXCLUDED.content_type, object_key = EXCLUDED.object_key,
                  uploaded_at = clock_timestamp()
                """ : ticket.kind() == LogoAssets.Kind.SERIES ? """
                INSERT INTO series_logo(series_id, content_type, object_key) VALUES (:target, :type, :key)
                ON CONFLICT(series_id) DO UPDATE SET content_type = EXCLUDED.content_type, object_key = EXCLUDED.object_key,
                  uploaded_at = clock_timestamp()
                """ : """
                INSERT INTO manufacturer_logo(name, display_name, content_type, object_key) VALUES (:target, :target, :type, :key)
                ON CONFLICT(name) DO UPDATE SET content_type = EXCLUDED.content_type, object_key = EXCLUDED.object_key,
                  uploaded_at = clock_timestamp()
                """;
        db.sql(sql).param("target", ticket.kind().key(ticket.target())).param("type", ticket.contentType())
                .param("key", key).update();
        // Inversion and existing display names are deliberately not updated by uploads.
        db.sql("UPDATE logo_upload SET completed = true WHERE id = :id").param("id", id).update();
        return assets.find(ticket.kind(), ticket.target(), false).orElseThrow();
    }

    static String staging(UUID id) { return "staging/logos/" + id + "/logo"; }
}
