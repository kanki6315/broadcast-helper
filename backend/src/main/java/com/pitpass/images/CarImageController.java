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

    private final PublicImageStorage storage;
    private final CarImageUrls urls;

    public CarImageController(JdbcClient db, PublicImageStorage storage, CarImageUrls urls) {
        this.db = db;
        this.storage = storage;
        this.urls = urls;
    }

    // ----------------------------------------------------------------- images

    public record ImageSummary(long id, String carNumber, String sourceFilename, OffsetDateTime uploadedAt, String imageUrl, String originalUrl, boolean publicStorage) {
    }

    public record MissingCar(String carNumber, String className, String teamName) {
    }

    public record ImageOverview(List<ImageSummary> images, List<MissingCar> missing) {
    }

    @GetMapping("/car-images")
    public ImageOverview list(@RequestParam long seasonId) {
        List<ImageSummary> images = db.sql("""
                        SELECT id, car_number, source_filename, uploaded_at, original_object_key, sheet_object_key
                        FROM car_image WHERE season_id = :seasonId ORDER BY car_number
                        """)
                .param("seasonId", seasonId)
                .query((rs, i) -> new ImageSummary(rs.getLong("id"), rs.getString("car_number"),
                        rs.getString("source_filename"), rs.getObject("uploaded_at", OffsetDateTime.class),
                        urls.sheet(rs.getLong("id"), rs.getObject("uploaded_at", OffsetDateTime.class).toInstant().toEpochMilli(), rs.getString("sheet_object_key")),
                        urls.original(rs.getLong("id"), rs.getObject("uploaded_at", OffsetDateTime.class).toInstant().toEpochMilli(), rs.getString("original_object_key")),
                        rs.getString("original_object_key") != null))
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
        throw new ResponseStatusException(HttpStatus.CONFLICT, "Upload directly to public storage. Reload the app before uploading.");
    }

    @PostMapping("/car-images")
    public ImageSummary uploadOne(@RequestParam long seasonId,
                                  @RequestParam String carNumber,
                                  @RequestParam("file") MultipartFile file) {
        throw new ResponseStatusException(HttpStatus.CONFLICT, "Upload directly to public storage. Reload the app before uploading.");
    }

    @GetMapping("/car-images/{id}/data")
    public ResponseEntity<byte[]> imageData(@PathVariable long id,
                                            @RequestParam(required = false) String variant,
                                            @RequestParam(required = false) String v) {
        return serve(id, variant, v != null);
    }

    /** The effective livery image for an entry: (its event's season, its car number). */
    @GetMapping("/entries/{entryId}/image")
    public ResponseEntity<byte[]> entryImage(@PathVariable long entryId,
                                             @RequestParam(required = false) String variant,
                                             @RequestParam(required = false) String v) {
        long imageId = db.sql("""
                        SELECT ci.id FROM entry en
                        JOIN event e ON e.id = en.event_id
                        JOIN car_image ci ON ci.season_id = e.season_id AND ci.car_number = en.car_number
                        WHERE en.id = :entryId
                        """).param("entryId", entryId).query(Long.class).optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No image for this entry"));
        return serve(imageId, variant, v != null);
    }

    private record ImageLocation(String contentType, String originalKey, String sheetKey) {}

    private ResponseEntity<byte[]> serve(long id, String variant, boolean versioned) {
        String key = db.sql("SELECT original_object_key, sheet_object_key FROM car_image WHERE id = :id")
                .param("id", id).query((rs, i) -> rs.getString("sheet".equals(variant) ? "sheet_object_key" : "original_object_key"))
                .optional().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such image"));
        return ResponseEntity.status(HttpStatus.FOUND).location(storage.publicUrl(key))
                .header("Cache-Control", HttpCaching.cacheControl(versioned)).build();
    }

    @DeleteMapping("/car-images/{id}")
    public void delete(@PathVariable long id) {
        int deleted = db.sql("DELETE FROM car_image WHERE id = :id").param("id", id).update();
        if (deleted == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such image");
        }
    }

    // ---------------------------------------------------------------- helpers

    /** Digit runs in the filename that exactly match a known car number (string match). */
    static List<String> numberCandidates(String filename, Set<String> known) {
        List<String> matches = new ArrayList<>();
        for (String run : digitRuns(filename)) {
            if (known.contains(run) && !matches.contains(run)) {
                matches.add(run);
            }
        }
        return matches;
    }

    static List<String> digitRuns(String filename) {
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
