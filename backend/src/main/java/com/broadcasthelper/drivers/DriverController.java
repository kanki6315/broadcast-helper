package com.broadcasthelper.drivers;

import com.broadcasthelper.browse.SeasonViewController;
import com.broadcasthelper.browse.SeasonViewController.Recap;
import com.broadcasthelper.browse.SeasonViewController.RecapRace;
import com.broadcasthelper.browse.SeasonViewController.RecapRound;
import com.broadcasthelper.browse.SeasonViewController.RecapRow;
import com.broadcasthelper.web.HttpCaching;
import com.sksamuel.scrimage.ImmutableImage;
import com.sksamuel.scrimage.webp.WebpWriter;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Driver profile endpoints backing the ⌘K search and the driver info modal:
 * name search with latest car/team context, a profile combining broadcaster-
 * entered bio fields with the driver's championship result matrix (reusing the
 * recap computation so the modal always agrees with the grid), plus bio/notes
 * writes and a headshot. Imports never touch the bio fields (they only upsert
 * country/hometown), so everything written here survives re-imports.
 */
@RestController
@RequestMapping("/api")
public class DriverController {

    private final JdbcClient db;
    private final SeasonViewController seasonView;

    public DriverController(JdbcClient db, SeasonViewController seasonView) {
        this.db = db;
        this.seasonView = seasonView;
    }

    /* ------------------------------------------------------------------ */
    /* Search                                                               */
    /* ------------------------------------------------------------------ */

    public record SearchHit(long id, String name, String country, String rating, String carNumber,
                            String teamName, String className, Integer year, String seriesName) {
    }

    /** Name / team / car-number search for the command palette. Context columns
     *  come from the driver's most recent entry, so a hit reads as "who they
     *  drive for now". Ranked: full-name prefix, surname prefix, name contains,
     *  then team/car matches. */
    @GetMapping("/drivers/search")
    public List<SearchHit> search(@RequestParam String q, @RequestParam(defaultValue = "12") int limit) {
        String needle = q.trim().toLowerCase(Locale.ROOT);
        if (needle.isEmpty()) {
            return List.of();
        }
        return db.sql("""
                        WITH ctx AS (
                            SELECT DISTINCT ON (d.id) d.id AS driver_id, en.car_number, en.team_name,
                                   en.class_name, da.rating, s.year, sr.name AS series_name
                            FROM driver d
                                     JOIN driver_assignment da ON da.driver_id = d.id
                                     JOIN entry en ON en.id = da.entry_id
                                     JOIN event ev ON ev.id = en.event_id
                                     JOIN season s ON s.id = ev.season_id
                                     JOIN series sr ON sr.id = s.series_id
                            ORDER BY d.id, ev.event_date DESC NULLS LAST, ev.id DESC
                        )
                        SELECT d.id, d.first_name || ' ' || d.surname AS name, d.country,
                               c.car_number, c.team_name, c.class_name, c.rating, c.year, c.series_name
                        FROM driver d
                                 LEFT JOIN ctx c ON c.driver_id = d.id
                        WHERE lower(d.first_name || ' ' || d.surname) LIKE :contains
                           OR lower(coalesce(c.team_name, '')) LIKE :contains
                           OR lower(coalesce(c.car_number, '')) = :exact
                        ORDER BY CASE
                                     WHEN lower(d.first_name || ' ' || d.surname) LIKE :prefix THEN 0
                                     WHEN lower(d.surname) LIKE :prefix THEN 1
                                     WHEN lower(d.first_name || ' ' || d.surname) LIKE :contains THEN 2
                                     ELSE 3
                                 END,
                                 d.surname, d.first_name
                        LIMIT :limit
                        """)
                .param("contains", "%" + needle + "%")
                .param("prefix", needle + "%")
                .param("exact", needle)
                .param("limit", Math.min(Math.max(limit, 1), 50))
                .query((rs, i) -> new SearchHit(rs.getLong("id"), rs.getString("name"),
                        rs.getString("country"), rs.getString("rating"), rs.getString("car_number"),
                        rs.getString("team_name"), rs.getString("class_name"),
                        rs.getObject("year", Integer.class), rs.getString("series_name")))
                .list();
    }

    /* ------------------------------------------------------------------ */
    /* Profile                                                              */
    /* ------------------------------------------------------------------ */

    public record ChampMatrix(long championshipId, String title, String className, String seriesName,
                              int year, long seasonId, int position, double totalPoints,
                              String carNumber, String teamName, List<RecapRound> rounds,
                              Map<Integer, List<RecapRace>> cells, Map<Integer, Double> pointsByRound) {
    }

    public record Profile(long id, String name, String country, String hometown, LocalDate dateOfBirth,
                          String placeOfBirth, String pronunciation, String notes, Long photoVersion,
                          String rating, String carNumber, String teamName, String className,
                          Integer year, String seriesName, List<ChampMatrix> championships) {
    }

    @GetMapping("/drivers/{id}/profile")
    public Profile profile(@PathVariable long id) {
        record Bio(String name, String country, String hometown, LocalDate dateOfBirth,
                   String placeOfBirth, String pronunciation, String notes) {
        }
        Bio bio = db.sql("""
                        SELECT first_name || ' ' || surname AS name, country, hometown,
                               date_of_birth, place_of_birth, pronunciation, notes
                        FROM driver WHERE id = :id
                        """)
                .param("id", id)
                .query((rs, i) -> new Bio(rs.getString("name"), rs.getString("country"),
                        rs.getString("hometown"), rs.getObject("date_of_birth", LocalDate.class),
                        rs.getString("place_of_birth"), rs.getString("pronunciation"),
                        rs.getString("notes")))
                .optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such driver"));

        Long photoVersion = db.sql("""
                        SELECT (extract(epoch FROM uploaded_at) * 1000)::bigint
                        FROM driver_photo WHERE driver_id = :id
                        """)
                .param("id", id)
                .query(Long.class)
                .optional()
                .orElse(null);

        // Latest seat: the header's "who they drive for now" line.
        record Seat(String rating, String carNumber, String teamName, String className,
                    Integer year, String seriesName) {
        }
        Seat seat = db.sql("""
                        SELECT da.rating, en.car_number, en.team_name, en.class_name,
                               s.year, sr.name AS series_name
                        FROM driver_assignment da
                                 JOIN entry en ON en.id = da.entry_id
                                 JOIN event ev ON ev.id = en.event_id
                                 JOIN season s ON s.id = ev.season_id
                                 JOIN series sr ON sr.id = s.series_id
                        WHERE da.driver_id = :id
                        ORDER BY ev.event_date DESC NULLS LAST, ev.id DESC
                        LIMIT 1
                        """)
                .param("id", id)
                .query((rs, i) -> new Seat(rs.getString("rating"), rs.getString("car_number"),
                        rs.getString("team_name"), rs.getString("class_name"),
                        rs.getObject("year", Integer.class), rs.getString("series_name")))
                .optional()
                .orElse(new Seat(null, null, null, null, null, null));

        // DRIVERS championships whose standings carry this driver, newest season
        // first. The matrix comes from the same recap computation the grids use,
        // filtered to this driver's row — the modal can never disagree with the
        // recap page about a start/finish.
        List<Long> champIds = db.sql("""
                        SELECT c.id
                        FROM standings_row srw
                                 JOIN championship c ON c.id = srw.championship_id
                                 JOIN championship_group g ON g.id = c.group_id
                                 JOIN season s ON s.id = c.season_id
                        WHERE g.kind = 'DRIVERS'
                          AND (lower(trim(srw.competitor_name)) = lower(:name)
                               OR lower(trim(srw.competitor_key)) = lower(:name))
                        ORDER BY s.year DESC, c.id
                        """)
                .param("name", bio.name())
                .query(Long.class)
                .list();

        List<ChampMatrix> championships = new ArrayList<>();
        for (long champId : champIds) {
            Recap recap = seasonView.recap(champId);
            recap.rows().stream()
                    .filter(r -> matchesName(r, bio.name()))
                    .findFirst()
                    .ifPresent(row -> championships.add(new ChampMatrix(recap.championship().id(),
                            recap.championship().title(), recap.championship().className(),
                            recap.championship().seriesName(), recap.championship().year(),
                            recap.championship().seasonId(), row.position(), row.totalPoints(),
                            row.carNumber(), row.teamName(), recap.rounds(), row.cells(),
                            row.pointsByRound())));
        }

        return new Profile(id, bio.name(), bio.country(), bio.hometown(), bio.dateOfBirth(),
                bio.placeOfBirth(), bio.pronunciation(), bio.notes(), photoVersion,
                seat.rating(), seat.carNumber(), seat.teamName(), seat.className(),
                seat.year(), seat.seriesName(), championships);
    }

    private static boolean matchesName(RecapRow row, String name) {
        String needle = name.trim().toLowerCase(Locale.ROOT);
        return needle.equals(Objects.toString(row.competitorName(), "").trim().toLowerCase(Locale.ROOT))
                || needle.equals(Objects.toString(row.competitorKey(), "").trim().toLowerCase(Locale.ROOT));
    }

    /* ------------------------------------------------------------------ */
    /* Bio & notes writes                                                   */
    /* ------------------------------------------------------------------ */

    public record BioUpdate(LocalDate dateOfBirth, String hometown, String placeOfBirth, String pronunciation) {
    }

    /** Full replace of the broadcaster-editable bio fields; blank strings clear.
     *  (Hometown is also import-fed via COALESCE-upsert — a later results file
     *  that carries a hometown wins over a manual clear, which is fine: the
     *  import's value is source data, not a guess.) */
    @PatchMapping("/drivers/{id}/bio")
    public void updateBio(@PathVariable long id, @RequestBody BioUpdate body) {
        int updated = db.sql("""
                        UPDATE driver
                        SET date_of_birth = :dob, hometown = :hometown,
                            place_of_birth = :placeOfBirth, pronunciation = :pronunciation
                        WHERE id = :id
                        """)
                .param("id", id)
                .param("dob", body.dateOfBirth())
                .param("hometown", blankToNull(body.hometown()))
                .param("placeOfBirth", blankToNull(body.placeOfBirth()))
                .param("pronunciation", blankToNull(body.pronunciation()))
                .update();
        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such driver");
        }
    }

    public record NotesUpdate(String notes) {
    }

    @PatchMapping("/drivers/{id}/notes")
    public void updateNotes(@PathVariable long id, @RequestBody NotesUpdate body) {
        int updated = db.sql("UPDATE driver SET notes = :notes WHERE id = :id")
                .param("id", id)
                .param("notes", blankToNull(body.notes()))
                .update();
        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such driver");
        }
    }

    static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    /* ------------------------------------------------------------------ */
    /* Photo                                                                */
    /* ------------------------------------------------------------------ */

    /** Longest side stored, px. Headshots render at ~96px in the modal; 640
     *  keeps retina crisp without banking multi-MB originals in the DB. */
    private static final int PHOTO_MAX = 640;

    public record PhotoResult(long driverId, long photoVersion) {
    }

    @PostMapping("/drivers/{id}/photo")
    public PhotoResult uploadPhoto(@PathVariable long id, @RequestParam("file") MultipartFile file) {
        byte[] data;
        try {
            data = file.getBytes();
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Could not read upload: " + e.getMessage());
        }
        if (data.length == 0) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Empty file");
        }
        String contentType = contentType(file);
        // Downscale to a webp like the car-image sheet variant; if the bytes
        // don't decode, store the original — resizing is an optimization.
        try {
            ImmutableImage image = ImmutableImage.loader().fromBytes(data);
            if (image.width > PHOTO_MAX || image.height > PHOTO_MAX) {
                data = image.max(PHOTO_MAX, PHOTO_MAX).bytes(WebpWriter.DEFAULT);
                contentType = "image/webp";
            }
        } catch (IOException | RuntimeException e) {
            // keep original bytes
        }
        Long version = db.sql("""
                        INSERT INTO driver_photo (driver_id, content_type, source_filename, data)
                        VALUES (:id, :contentType, :filename, :data)
                        ON CONFLICT (driver_id) DO UPDATE
                            SET content_type = EXCLUDED.content_type,
                                source_filename = EXCLUDED.source_filename,
                                data = EXCLUDED.data,
                                uploaded_at = now()
                        RETURNING (extract(epoch FROM uploaded_at) * 1000)::bigint
                        """)
                .param("id", id)
                .param("contentType", contentType)
                .param("filename", file.getOriginalFilename())
                .param("data", data)
                .query(Long.class)
                .single();
        return new PhotoResult(id, version);
    }

    @GetMapping("/drivers/{id}/photo")
    public ResponseEntity<byte[]> photo(@PathVariable long id, @RequestParam(required = false) String v) {
        record Blob(String contentType, byte[] data) {
        }
        Blob blob = db.sql("SELECT content_type, data FROM driver_photo WHERE driver_id = :id")
                .param("id", id)
                .query((rs, i) -> new Blob(rs.getString("content_type"), rs.getBytes("data")))
                .optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No photo for this driver"));
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(blob.contentType()))
                .header("Cache-Control", HttpCaching.cacheControl(v != null))
                .body(blob.data());
    }

    @DeleteMapping("/drivers/{id}/photo")
    public void deletePhoto(@PathVariable long id) {
        int deleted = db.sql("DELETE FROM driver_photo WHERE driver_id = :id").param("id", id).update();
        if (deleted == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No photo for this driver");
        }
    }

    private static String contentType(MultipartFile file) {
        String declared = file.getContentType();
        if (declared != null && declared.toLowerCase(Locale.ROOT).startsWith("image/")) {
            return declared;
        }
        String name = file.getOriginalFilename() != null
                ? file.getOriginalFilename().toLowerCase(Locale.ROOT) : "";
        if (name.endsWith(".png")) return "image/png";
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) return "image/jpeg";
        if (name.endsWith(".webp")) return "image/webp";
        if (name.endsWith(".gif")) return "image/gif";
        throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                "Not a recognized image type: " + file.getOriginalFilename());
    }
}
