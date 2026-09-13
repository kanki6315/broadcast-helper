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
@RequestMapping("/api/car-images/uploads")
public class CarImageUploadController {
    private static final Set<String> TYPES = Set.of("image/jpeg", "image/png", "image/webp", "image/gif");
    private final JdbcClient db;
    private final PublicImageStorage storage;

    public CarImageUploadController(JdbcClient db, PublicImageStorage storage) {
        this.db = db;
        this.storage = storage;
    }

    public record Configuration(boolean directUpload) {}
    @GetMapping("/config")
    public Configuration configuration() { return new Configuration(storage.enabled()); }

    public record MatchRequest(@Positive long seasonId, @NotEmpty @Size(max = 500) List<@NotBlank @Size(max = 255) String> filenames) {}
    public record Match(String filename, String carNumber, String status, List<String> candidates) {}

    @PostMapping("/match")
    public List<Match> match(@Valid @RequestBody MatchRequest request) {
        Set<String> known = Set.copyOf(db.sql("""
                SELECT DISTINCT en.car_number FROM entry en JOIN event e ON e.id = en.event_id
                WHERE e.season_id = :season
                """).param("season", request.seasonId()).query(String.class).list());
        return request.filenames().stream().map(filename -> {
            List<String> candidates = CarImageController.numberCandidates(filename, known);
            return new Match(filename, candidates.size() == 1 ? candidates.getFirst() : null,
                    candidates.size() == 1 ? "MATCHED" : candidates.isEmpty() ? "UNMATCHED" : "AMBIGUOUS",
                    candidates.isEmpty() ? CarImageController.digitRuns(filename) : candidates);
        }).toList();
    }

    public record PrepareRequest(@Positive long seasonId, @NotBlank @Size(max = 32) String carNumber,
            @NotBlank @Size(max = 255) String filename, @NotBlank String contentType,
            @Min(1) @Max(26214400) long originalSize, @Min(1) @Max(1048576) long sheetSize, Long migrationImageId, OffsetDateTime sourceUploadedAt) {}
    public record UploadPlan(UUID id, String originalUrl, String sheetUrl) {}

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public UploadPlan prepare(@Valid @RequestBody PrepareRequest request) {
        if (!storage.enabled()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Image storage is not configured");
        if (!TYPES.contains(request.contentType()))
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Choose a JPEG, PNG, WebP, or GIF image");
        if (!db.sql("SELECT EXISTS (SELECT 1 FROM season WHERE id = :id)").param("id", request.seasonId()).query(Boolean.class).single())
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such season");
        if (request.migrationImageId() != null || request.sourceUploadedAt() != null)
            throw new ResponseStatusException(HttpStatus.GONE, "Database migration has been retired");
        UUID id = UUID.randomUUID();
        String original = storage.uploadUrl(staging(id, "original"), request.contentType(), request.originalSize());
        String sheet = storage.uploadUrl(staging(id, "sheet"), "image/webp", request.sheetSize());
        db.sql("""
                INSERT INTO car_image_upload (id, season_id, car_number, filename, content_type, original_size, sheet_size, expires_at)
                VALUES (:id, :season, :number, :filename, :type, :original, :sheet, now() + interval '30 minutes')
                """).param("id", id).param("season", request.seasonId()).param("number", request.carNumber().trim())
                .param("filename", request.filename()).param("type", request.contentType())
                .param("original", request.originalSize()).param("sheet", request.sheetSize()).update();
        return new UploadPlan(id, original, sheet);
    }

    private record Ticket(long season, String number, String filename, String type, long originalSize,
                          long sheetSize, OffsetDateTime expires, boolean completed, Long imageId) {}
    public record Completed(long id, boolean replaced) {}

    /** A retry of a completed ticket never overwrites a newer image. */
    @PostMapping("/{id}/complete")
    @Transactional
    public Completed complete(@PathVariable UUID id) {
        Ticket ticket = db.sql("SELECT * FROM car_image_upload WHERE id = :id FOR UPDATE").param("id", id)
                .query((rs, i) -> new Ticket(rs.getLong("season_id"), rs.getString("car_number"), rs.getString("filename"),
                        rs.getString("content_type"), rs.getLong("original_size"), rs.getLong("sheet_size"),
                        rs.getObject("expires_at", OffsetDateTime.class), rs.getBoolean("completed"),
                        rs.getObject("image_id", Long.class)))
                .optional().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such upload"));
        if (ticket.completed()) {
            if (ticket.imageId() == null) throw new ResponseStatusException(HttpStatus.GONE, "This image was deleted");
            return new Completed(ticket.imageId(), false);
        }
        if (ticket.expires().isBefore(OffsetDateTime.now()))
            throw new ResponseStatusException(HttpStatus.GONE, "Upload expired; select the file again");
        String originalKey = published(id, "original");
        String sheetKey = published(id, "sheet");
        storage.publish(staging(id, "original"), originalKey, ticket.type(), ticket.originalSize());
        storage.publish(staging(id, "sheet"), sheetKey, "image/webp", ticket.sheetSize());
        boolean replaced = db.sql("SELECT EXISTS (SELECT 1 FROM car_image WHERE season_id = :s AND car_number = :n)")
                .param("s", ticket.season()).param("n", ticket.number()).query(Boolean.class).single();
        long imageId = db.sql("""
                INSERT INTO car_image (season_id, car_number, source_filename, content_type, original_object_key, sheet_object_key)
                VALUES (:season, :number, :filename, :type, :original, :sheet)
                ON CONFLICT (season_id, car_number) DO UPDATE SET
                  source_filename = EXCLUDED.source_filename, content_type = EXCLUDED.content_type,
                  original_object_key = EXCLUDED.original_object_key, sheet_object_key = EXCLUDED.sheet_object_key,
                  uploaded_at = clock_timestamp()
                RETURNING id
                """).param("season", ticket.season()).param("number", ticket.number()).param("filename", ticket.filename())
                .param("type", ticket.type()).param("original", originalKey).param("sheet", sheetKey).query(Long.class).single();
        db.sql("UPDATE car_image_upload SET completed = true, image_id = :image WHERE id = :id")
                .param("image", imageId).param("id", id).update();
        return new Completed(imageId, replaced);
    }

    static String staging(UUID id, String variant) { return "staging/car-images/" + id + "/" + variant; }
    static String published(UUID id, String variant) { return "car-images/" + id + "/" + variant; }
}
