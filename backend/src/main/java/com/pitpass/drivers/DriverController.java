package com.pitpass.drivers;

import com.pitpass.browse.SeasonViewController;
import com.pitpass.browse.SeasonViewController.Recap;
import com.pitpass.browse.SeasonViewController.RecapRace;
import com.pitpass.browse.SeasonViewController.RecapRound;
import com.pitpass.browse.SeasonViewController.RecapRow;
import com.pitpass.web.HttpCaching;
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
import java.util.LinkedHashMap;
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
    /* Stats                                                                */
    /* ------------------------------------------------------------------ */

    public record NamedFormatLine(Long formatId, String formatName, int starts, int wins,
                                  int podiums, int top5s, int dnfs) {
    }

    public record QualiLine(int sessions, int poles, int top5s) {
    }

    public record SeasonStatLine(long seasonId, int year, String seriesName, String className,
                                 boolean qualifier, String seasonLabel,
                                 List<NamedFormatLine> byFormat, QualiLine quali) {
    }

    public record SeriesStatLine(long seriesId, String seriesName,
                                 List<NamedFormatLine> byFormat, QualiLine quali) {
    }

    public record CareerTotals(int starts, int wins, int podiums, int top5s, int poles,
                               int qualiTop5s, int dnfs) {
    }

    public record DriverStats(long driverId, CareerTotals career, List<SeriesStatLine> bySeries,
                              List<SeasonStatLine> seasons) {
    }

    /**
     * Career tallies for the driver modal, at three grains: format-agnostic
     * career totals (formats don't merge across series), per-series all-time
     * with the format split, and per-season lines. A win is in-class P1; poles
     * come only from QUALIFYING session results (a reversed grid's front row is
     * not a pole). Race counts credit the driver for every crewed entry; quali
     * counts credit only the qualifying driver of record.
     */
    @GetMapping("/drivers/{id}/stats")
    public DriverStats stats(@PathVariable long id) {
        db.sql("SELECT 1 FROM driver WHERE id = :id").param("id", id).query(Integer.class)
                .optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such driver"));

        record RaceAgg(long seasonId, int year, long seriesId, String seriesName, String className,
                       boolean qualifier, String seasonLabel,
                       Long formatId, String formatName, int formatOrdinal,
                       int starts, int wins, int podiums, int top5s, int dnfs) {
        }
        List<RaceAgg> raceAggs = db.sql("""
                        SELECT s.id AS season_id, s.year, sr.id AS series_id, sr.name AS series_name,
                               s.kind = 'QUALIFIER' AS qualifier, s.label AS season_label,
                               en.class_name, rs.format_id,
                               COALESCE(rf.name, 'Unassigned') AS format_name,
                               COALESCE(rf.ordinal, 99) AS format_ordinal,
                               count(*) FILTER (WHERE r.position_in_class IS NOT NULL) AS starts,
                               count(*) FILTER (WHERE r.position_in_class = 1)  AS wins,
                               count(*) FILTER (WHERE r.position_in_class <= 3) AS podiums,
                               count(*) FILTER (WHERE r.position_in_class <= 5) AS top5s,
                               count(*) FILTER (WHERE r.not_finished)           AS dnfs
                        FROM result r
                                 JOIN race_session rs ON rs.id = r.session_id AND rs.session_type = 'RACE'
                                 LEFT JOIN race_format rf ON rf.id = rs.format_id
                                 JOIN event ev ON ev.id = rs.event_id
                                 JOIN season s ON s.id = ev.season_id
                                 JOIN series sr ON sr.id = s.series_id
                                 JOIN entry en ON en.id = r.entry_id
                                 JOIN driver_assignment da ON da.entry_id = en.id
                        WHERE da.driver_id = :id
                        GROUP BY s.id, s.year, sr.id, sr.name, en.class_name,
                                 rs.format_id, rf.name, rf.ordinal
                        ORDER BY s.year DESC, sr.name, en.class_name, format_ordinal
                        """)
                .param("id", id)
                .query((rs, i) -> new RaceAgg(rs.getLong("season_id"), rs.getInt("year"),
                        rs.getLong("series_id"), rs.getString("series_name"), rs.getString("class_name"),
                        rs.getBoolean("qualifier"), rs.getString("season_label"),
                        rs.getObject("format_id", Long.class), rs.getString("format_name"),
                        rs.getInt("format_ordinal"), rs.getInt("starts"), rs.getInt("wins"),
                        rs.getInt("podiums"), rs.getInt("top5s"), rs.getInt("dnfs")))
                .list();

        record QualiAgg(long seasonId, long seriesId, String className, boolean qualifier,
                        int sessions, int poles, int top5s) {
        }
        // Quali claims go to the qualifying driver of record (grid attribution,
        // else a solo entry's sole crew member) — same rule as the season stats,
        // so this profile can never disagree with the leaderboards.
        List<QualiAgg> qualiAggs = db.sql("""
                        SELECT q.season_id, q.series_id, q.class_name, q.qualifier,
                               count(*) FILTER (WHERE q.position_in_class IS NOT NULL) AS sessions,
                               count(*) FILTER (WHERE q.position_in_class = 1)  AS poles,
                               count(*) FILTER (WHERE q.position_in_class <= 5) AS top5s
                        FROM (
                            SELECT s.id AS season_id, s.series_id, s.kind = 'QUALIFIER' AS qualifier,
                                   en.class_name, r.position_in_class,
                                   COALESCE(
                                       (SELECT gp.qualifying_driver_id
                                        FROM grid_position gp
                                                 JOIN race_session grs ON grs.id = gp.session_id
                                                      AND grs.event_id = ev.id AND grs.session_type = 'RACE'
                                        WHERE gp.entry_id = en.id AND gp.qualifying_driver_id IS NOT NULL
                                        ORDER BY grs.ordinal LIMIT 1),
                                       (SELECT min(da.driver_id) FROM driver_assignment da
                                        WHERE da.entry_id = en.id HAVING count(*) = 1)
                                   ) AS driver_id
                            FROM result r
                                     JOIN race_session rs ON rs.id = r.session_id AND rs.session_type = 'QUALIFYING'
                                     JOIN event ev ON ev.id = rs.event_id
                                     JOIN season s ON s.id = ev.season_id
                                     JOIN entry en ON en.id = r.entry_id
                        ) q
                        WHERE q.driver_id = :id
                        GROUP BY q.season_id, q.series_id, q.class_name, q.qualifier
                        """)
                .param("id", id)
                .query((rs, i) -> new QualiAgg(rs.getLong("season_id"), rs.getLong("series_id"),
                        rs.getString("class_name"), rs.getBoolean("qualifier"), rs.getInt("sessions"),
                        rs.getInt("poles"), rs.getInt("top5s")))
                .list();

        // Per-season lines: one per season × class, formats in ordinal order.
        record SeasonKey(long seasonId, String className) {
        }
        Map<SeasonKey, List<RaceAgg>> bySeasonClass = new LinkedHashMap<>();
        for (RaceAgg a : raceAggs) {
            bySeasonClass.computeIfAbsent(new SeasonKey(a.seasonId(), a.className()), k -> new ArrayList<>())
                    .add(a);
        }
        List<SeasonStatLine> seasons = new ArrayList<>();
        for (Map.Entry<SeasonKey, List<RaceAgg>> e : bySeasonClass.entrySet()) {
            List<RaceAgg> aggs = e.getValue();
            List<NamedFormatLine> lines = aggs.stream()
                    .map(a -> new NamedFormatLine(a.formatId(), a.formatName(), a.starts(), a.wins(),
                            a.podiums(), a.top5s(), a.dnfs()))
                    .toList();
            QualiLine quali = qualiAggs.stream()
                    .filter(q -> q.seasonId() == e.getKey().seasonId()
                                 && q.className().equals(e.getKey().className()))
                    .findFirst()
                    .map(q -> new QualiLine(q.sessions(), q.poles(), q.top5s()))
                    .orElse(new QualiLine(0, 0, 0));
            seasons.add(new SeasonStatLine(e.getKey().seasonId(), aggs.get(0).year(),
                    aggs.get(0).seriesName(), e.getKey().className(),
                    aggs.get(0).qualifier(), aggs.get(0).seasonLabel(), lines, quali));
        }

        // All-time per series: the same buckets rolled up across its seasons
        // (formats are per-series, so they merge cleanly), classes combined.
        // Qualifying stages keep their per-season lines above but never roll
        // up — a regional qualifier is not a start in the series proper.
        record FormatKey(long seriesId, Long formatId) {
        }
        Map<FormatKey, int[]> seriesFormatSums = new LinkedHashMap<>();
        Map<FormatKey, String> seriesFormatNames = new LinkedHashMap<>();
        Map<Long, String> seriesNames = new LinkedHashMap<>();
        for (RaceAgg a : raceAggs) {
            if (a.qualifier()) {
                continue;
            }
            FormatKey k = new FormatKey(a.seriesId(), a.formatId());
            int[] sums = seriesFormatSums.computeIfAbsent(k, x -> new int[5]);
            sums[0] += a.starts();
            sums[1] += a.wins();
            sums[2] += a.podiums();
            sums[3] += a.top5s();
            sums[4] += a.dnfs();
            seriesFormatNames.putIfAbsent(k, a.formatName());
            seriesNames.putIfAbsent(a.seriesId(), a.seriesName());
        }
        List<SeriesStatLine> bySeries = new ArrayList<>();
        for (Map.Entry<Long, String> se : seriesNames.entrySet()) {
            List<NamedFormatLine> lines = seriesFormatSums.entrySet().stream()
                    .filter(e -> e.getKey().seriesId() == se.getKey())
                    .map(e -> new NamedFormatLine(e.getKey().formatId(), seriesFormatNames.get(e.getKey()),
                            e.getValue()[0], e.getValue()[1], e.getValue()[2], e.getValue()[3],
                            e.getValue()[4]))
                    .toList();
            int qs = 0;
            int qp = 0;
            int qt = 0;
            for (QualiAgg q : qualiAggs) {
                if (q.seriesId() == se.getKey() && !q.qualifier()) {
                    qs += q.sessions();
                    qp += q.poles();
                    qt += q.top5s();
                }
            }
            bySeries.add(new SeriesStatLine(se.getKey(), se.getValue(), lines, new QualiLine(qs, qp, qt)));
        }

        // Career headline counts qualifying stages out too: on a broadcast,
        // "career starts" means real series starts.
        List<RaceAgg> mainRace = raceAggs.stream().filter(a -> !a.qualifier()).toList();
        List<QualiAgg> mainQuali = qualiAggs.stream().filter(q -> !q.qualifier()).toList();
        CareerTotals career = new CareerTotals(
                mainRace.stream().mapToInt(RaceAgg::starts).sum(),
                mainRace.stream().mapToInt(RaceAgg::wins).sum(),
                mainRace.stream().mapToInt(RaceAgg::podiums).sum(),
                mainRace.stream().mapToInt(RaceAgg::top5s).sum(),
                mainQuali.stream().mapToInt(QualiAgg::poles).sum(),
                mainQuali.stream().mapToInt(QualiAgg::top5s).sum(),
                mainRace.stream().mapToInt(RaceAgg::dnfs).sum());
        return new DriverStats(id, career, bySeries, seasons);
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
