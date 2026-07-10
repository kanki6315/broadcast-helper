package com.broadcasthelper.images;

import com.sksamuel.scrimage.ImmutableImage;
import com.sksamuel.scrimage.webp.WebpWriter;
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
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Car livery images, uploaded in bulk and matched to cars by the number in the
 * filename. Matching is strict-by-string against the season's known car numbers
 * (leading zeros significant: "023.png" never matches #23), and ambiguous
 * filenames are surfaced for manual assignment instead of guessed.
 */
@RestController
@RequestMapping("/api")
public class CarImageController {

    // Digit runs of 1-3 not embedded in longer runs: finds 31 in "2026_31_cadillac.png"
    // without ever considering "2026".
    private static final Pattern NUMBER_RUN = Pattern.compile("(?<!\\d)\\d{1,3}(?!\\d)");

    private final JdbcClient db;

    public CarImageController(JdbcClient db) {
        this.db = db;
    }

    // ----------------------------------------------------------------- images

    public record ImageSummary(long id, String carNumber, String sourceFilename, OffsetDateTime uploadedAt) {
    }

    public record MissingCar(String carNumber, String className, String teamName) {
    }

    public record ImageOverview(List<ImageSummary> images, List<MissingCar> missing) {
    }

    @GetMapping("/car-images")
    public ImageOverview list(@RequestParam long seasonId) {
        List<ImageSummary> images = db.sql("""
                        SELECT id, car_number, source_filename, uploaded_at
                        FROM car_image WHERE season_id = :seasonId ORDER BY car_number
                        """)
                .param("seasonId", seasonId)
                .query((rs, i) -> new ImageSummary(rs.getLong("id"), rs.getString("car_number"),
                        rs.getString("source_filename"), rs.getObject("uploaded_at", OffsetDateTime.class)))
                .list();
        // Cars entered this season with no image yet; latest event's team name wins.
        List<MissingCar> missing = db.sql("""
                        SELECT DISTINCT ON (en.car_number) en.car_number, en.class_name, en.team_name
                        FROM entry en JOIN event e ON e.id = en.event_id
                        WHERE e.season_id = :seasonId
                          AND NOT EXISTS (SELECT 1 FROM car_image ci
                                          WHERE ci.season_id = :seasonId AND ci.car_number = en.car_number)
                        ORDER BY en.car_number, e.event_date DESC
                        """)
                .param("seasonId", seasonId)
                .query((rs, i) -> new MissingCar(rs.getString("car_number"), rs.getString("class_name"),
                        rs.getString("team_name")))
                .list();
        return new ImageOverview(images, missing);
    }

    public record BulkResult(String filename, String status, String carNumber, List<String> candidates) {
    }

    @PostMapping("/car-images/bulk")
    public List<BulkResult> bulkUpload(@RequestParam long seasonId,
                                       @RequestParam("files") List<MultipartFile> files) {
        Set<String> known = new LinkedHashSet<>(db.sql("""
                        SELECT DISTINCT en.car_number FROM entry en
                                 JOIN event e ON e.id = en.event_id
                        WHERE e.season_id = :seasonId
                        """)
                .param("seasonId", seasonId)
                .query(String.class)
                .list());

        List<BulkResult> results = new ArrayList<>();
        for (MultipartFile file : files) {
            String filename = file.getOriginalFilename() != null ? file.getOriginalFilename() : "(unnamed)";
            List<String> candidates = numberCandidates(filename, known);
            if (candidates.size() == 1) {
                boolean replaced = save(seasonId, candidates.get(0), file);
                results.add(new BulkResult(filename, replaced ? "REPLACED" : "MATCHED", candidates.get(0), candidates));
            } else if (candidates.isEmpty()) {
                results.add(new BulkResult(filename, "UNMATCHED", null, digitRuns(filename)));
            } else {
                results.add(new BulkResult(filename, "AMBIGUOUS", null, candidates));
            }
        }
        return results;
    }

    @PostMapping("/car-images")
    public ImageSummary uploadOne(@RequestParam long seasonId,
                                  @RequestParam String carNumber,
                                  @RequestParam("file") MultipartFile file) {
        String number = carNumber.trim();
        if (number.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Car number is required");
        }
        save(seasonId, number, file);
        return db.sql("""
                        SELECT id, car_number, source_filename, uploaded_at
                        FROM car_image WHERE season_id = :seasonId AND car_number = :number
                        """)
                .param("seasonId", seasonId).param("number", number)
                .query((rs, i) -> new ImageSummary(rs.getLong("id"), rs.getString("car_number"),
                        rs.getString("source_filename"), rs.getObject("uploaded_at", OffsetDateTime.class)))
                .single();
    }

    @GetMapping("/car-images/{id}/data")
    public ResponseEntity<byte[]> imageData(@PathVariable long id,
                                            @RequestParam(required = false) String variant) {
        FullImage full = db.sql("SELECT id, content_type, data FROM car_image WHERE id = :id")
                .param("id", id)
                .query((rs, i) -> new FullImage(rs.getLong("id"), rs.getString("content_type"), rs.getBytes("data")))
                .optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such image"));
        return serve(full, variant);
    }

    /** The effective livery image for an entry: (its event's season, its car number). */
    @GetMapping("/entries/{entryId}/image")
    public ResponseEntity<byte[]> entryImage(@PathVariable long entryId,
                                             @RequestParam(required = false) String variant) {
        FullImage full = db.sql("""
                        SELECT ci.id, ci.content_type, ci.data
                        FROM entry en
                                 JOIN event e ON e.id = en.event_id
                                 JOIN car_image ci ON ci.season_id = e.season_id AND ci.car_number = en.car_number
                        WHERE en.id = :entryId
                        """)
                .param("entryId", entryId)
                .query((rs, i) -> new FullImage(rs.getLong("id"), rs.getString("content_type"), rs.getBytes("data")))
                .optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No image for this entry"));
        return serve(full, variant);
    }

    private record FullImage(long id, String contentType, byte[] data) {
    }

    private record VariantBlob(String contentType, Integer width, byte[] data) {
    }

    /**
     * Serve full-res, or a named downscaled variant (currently just "sheet"). An
     * existing variant is served straight; a missing "sheet" variant is generated
     * on the fly and stored (covers images uploaded before variants existed). If
     * a variant can't be produced, fall back to full-res — it's an optimization,
     * never a hard dependency.
     */
    private ResponseEntity<byte[]> serve(FullImage full, String variant) {
        if (variant == null || variant.isBlank()) {
            return body(full.contentType(), full.data());
        }
        Optional<VariantBlob> existing = db.sql("""
                        SELECT content_type, data FROM car_image_variant
                        WHERE image_id = :id AND variant = :variant
                        """)
                .param("id", full.id()).param("variant", variant)
                .query((rs, i) -> new VariantBlob(rs.getString("content_type"), null, rs.getBytes("data")))
                .optional();
        if (existing.isPresent()) {
            return body(existing.get().contentType(), existing.get().data());
        }
        if ("sheet".equals(variant)) {
            VariantBlob generated = ensureSheetVariant(full.id(), full.data());
            if (generated != null) {
                return body(generated.contentType(), generated.data());
            }
        }
        return body(full.contentType(), full.data());
    }

    private static ResponseEntity<byte[]> body(String contentType, byte[] data) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .header("Cache-Control", "max-age=300")
                .body(data);
    }

    @DeleteMapping("/car-images/{id}")
    public void delete(@PathVariable long id) {
        int deleted = db.sql("DELETE FROM car_image WHERE id = :id").param("id", id).update();
        if (deleted == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such image");
        }
    }

    // ---------------------------------------------------------------- helpers

    /** Saves (upsert); returns true if an existing image was replaced. */
    private boolean save(long seasonId, String carNumber, MultipartFile file) {
        byte[] data;
        try {
            data = file.getBytes();
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Could not read upload: " + e.getMessage());
        }
        if (data.length == 0) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Empty file");
        }
        boolean exists = db.sql("SELECT count(*) FROM car_image WHERE season_id = :s AND car_number = :n")
                .param("s", seasonId).param("n", carNumber).query(Long.class).single() > 0;
        long imageId = db.sql("""
                        INSERT INTO car_image (season_id, car_number, content_type, source_filename, data)
                        VALUES (:seasonId, :number, :contentType, :filename, :data)
                        ON CONFLICT (season_id, car_number) DO UPDATE
                            SET content_type = EXCLUDED.content_type,
                                source_filename = EXCLUDED.source_filename,
                                data = EXCLUDED.data,
                                uploaded_at = now()
                        RETURNING id
                        """)
                .param("seasonId", seasonId)
                .param("number", carNumber)
                .param("contentType", contentType(file))
                .param("filename", file.getOriginalFilename())
                .param("data", data)
                .query(Long.class)
                .single();
        ensureSheetVariant(imageId, data);
        return exists;
    }

    private static final int SHEET_MAX = 400; // longest side of the sheet variant, px

    /**
     * Generate (and store) the ~400px WebP "sheet" variant from the source bytes,
     * returning it, or null if the source is already small enough or can't be
     * decoded. Best-effort: variants are an optimization, so failures never break
     * upload or serving — the caller falls back to full-res.
     */
    private VariantBlob ensureSheetVariant(long imageId, byte[] source) {
        VariantBlob variant = makeSheetVariant(source);
        if (variant == null) {
            db.sql("DELETE FROM car_image_variant WHERE image_id = :id AND variant = 'sheet'")
                    .param("id", imageId).update();
            return null;
        }
        db.sql("""
                        INSERT INTO car_image_variant (image_id, variant, content_type, width, data)
                        VALUES (:id, 'sheet', :contentType, :width, :data)
                        ON CONFLICT (image_id, variant) DO UPDATE
                            SET content_type = EXCLUDED.content_type,
                                width = EXCLUDED.width,
                                data = EXCLUDED.data
                        """)
                .param("id", imageId)
                .param("contentType", variant.contentType())
                .param("width", variant.width())
                .param("data", variant.data())
                .update();
        return variant;
    }

    private static VariantBlob makeSheetVariant(byte[] source) {
        try {
            ImmutableImage image = ImmutableImage.loader().fromBytes(source);
            if (image.width <= SHEET_MAX && image.height <= SHEET_MAX) {
                return null; // already sheet-sized; full-res is fine
            }
            ImmutableImage scaled = image.max(SHEET_MAX, SHEET_MAX); // fit within, keep aspect + alpha
            return new VariantBlob("image/webp", scaled.width, scaled.bytes(WebpWriter.DEFAULT));
        } catch (IOException | RuntimeException e) {
            return null; // undecodable or encoder trouble -> serve full-res
        }
    }

    private static String contentType(MultipartFile file) {
        String declared = file.getContentType();
        if (declared != null && declared.toLowerCase(Locale.ROOT).startsWith("image/")) {
            return declared;
        }
        String name = file.getOriginalFilename() != null ? file.getOriginalFilename().toLowerCase(Locale.ROOT) : "";
        if (name.endsWith(".png")) return "image/png";
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) return "image/jpeg";
        if (name.endsWith(".webp")) return "image/webp";
        if (name.endsWith(".gif")) return "image/gif";
        throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                "Not a recognized image type: " + file.getOriginalFilename());
    }

    /** Digit runs in the filename that exactly match a known car number (string match). */
    private static List<String> numberCandidates(String filename, Set<String> known) {
        List<String> matches = new ArrayList<>();
        for (String run : digitRuns(filename)) {
            if (known.contains(run) && !matches.contains(run)) {
                matches.add(run);
            }
        }
        return matches;
    }

    private static List<String> digitRuns(String filename) {
        String base = filename.contains(".") ? filename.substring(0, filename.lastIndexOf('.')) : filename;
        Matcher m = NUMBER_RUN.matcher(base);
        List<String> runs = new ArrayList<>();
        while (m.find()) {
            if (!runs.contains(m.group())) {
                runs.add(m.group());
            }
        }
        return runs;
    }
}
