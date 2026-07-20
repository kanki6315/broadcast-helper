package com.pitpass.teams;

import com.pitpass.browse.SeasonViewController;
import com.pitpass.browse.SeasonViewController.Recap;
import com.pitpass.browse.SeasonViewController.RecapRace;
import com.pitpass.browse.SeasonViewController.RecapRound;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Team profile endpoints backing the ⌘K search and the team info modal. Teams
 * are entry.team_name strings, not rows, so everything keys on the normalized
 * name (lower/trim — the manufacturer_logo convention). The championship
 * matrices reuse the recap computation, filtered to the team's rows; a
 * two-car team gets one matrix entry per car.
 */
@RestController
@RequestMapping("/api")
public class TeamController {

    private final JdbcClient db;
    private final SeasonViewController seasonView;

    public TeamController(JdbcClient db, SeasonViewController seasonView) {
        this.db = db;
        this.seasonView = seasonView;
    }

    static String normalize(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    }

    /* ------------------------------------------------------------------ */
    /* Search (composed into /api/search by SearchController)               */
    /* ------------------------------------------------------------------ */

    public record TeamHit(String teamName, String carNumbers, String classNames,
                          Integer year, String seriesName) {
    }

    /** One hit per team name; context (cars, classes, series) comes from the
     *  team's most recent event so the row reads as "what they run now". */
    public List<TeamHit> search(String q, int limit) {
        String needle = normalize(q);
        if (needle.isEmpty()) {
            return List.of();
        }
        return db.sql("""
                        WITH latest AS (
                            SELECT DISTINCT ON (lower(trim(en.team_name)))
                                   lower(trim(en.team_name)) AS key, en.team_name,
                                   ev.id AS event_id, s.year, sr.name AS series_name
                            FROM entry en
                                     JOIN event ev ON ev.id = en.event_id
                                     JOIN season s ON s.id = ev.season_id
                                     JOIN series sr ON sr.id = s.series_id
                                     LEFT JOIN season_team st ON st.id = en.season_team_id
                            WHERE st.privateer_driver_id IS NULL
                            ORDER BY lower(trim(en.team_name)), ev.event_date DESC NULLS LAST, ev.id DESC
                        )
                        SELECT l.team_name, l.year, l.series_name,
                               (SELECT string_agg(DISTINCT e2.car_number, ' ' ORDER BY e2.car_number)
                                FROM entry e2
                                WHERE e2.event_id = l.event_id
                                  AND lower(trim(e2.team_name)) = l.key) AS car_numbers,
                               (SELECT string_agg(DISTINCT e2.class_name, ' · ')
                                FROM entry e2
                                WHERE e2.event_id = l.event_id
                                  AND lower(trim(e2.team_name)) = l.key) AS class_names
                        FROM latest l
                        WHERE l.key LIKE :contains
                        ORDER BY CASE WHEN l.key LIKE :prefix THEN 0 ELSE 1 END, l.team_name
                        LIMIT :limit
                        """)
                .param("contains", "%" + needle + "%")
                .param("prefix", needle + "%")
                .param("limit", Math.min(Math.max(limit, 1), 50))
                .query((rs, i) -> new TeamHit(rs.getString("team_name"), rs.getString("car_numbers"),
                        rs.getString("class_names"), rs.getObject("year", Integer.class),
                        rs.getString("series_name")))
                .list();
    }

    /* ------------------------------------------------------------------ */
    /* Profile                                                              */
    /* ------------------------------------------------------------------ */

    public record RosterDriver(Long driverId, String name, String rating, boolean isTbd) {
    }

    public record RosterCar(long entryId, String carNumber, String className, String classColor,
                            String vehicle, Long imageVersion, List<RosterDriver> drivers) {
    }

    public record RosterSeason(long seasonId, int year, String seriesName, String eventName,
                               List<RosterCar> cars) {
    }

    public record TeamChampEntry(String carNumber, int position, double totalPoints,
                                 Map<Integer, List<RecapRace>> cells,
                                 Map<Integer, Double> pointsByRound) {
    }

    public record TeamChampMatrix(long championshipId, String title, String className,
                                  String seriesName, int year, long seasonId,
                                  List<RecapRound> rounds, List<TeamChampEntry> entries) {
    }

    public record TeamProfile(String name, String notes, List<RosterSeason> roster,
                              List<TeamChampMatrix> championships) {
    }

    @GetMapping("/teams/profile")
    public TeamProfile profile(@RequestParam String name) {
        String key = normalize(name);
        if (key.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Team name is required");
        }

        // Display casing from the most recent entry; also proves the team exists.
        String displayName = db.sql("""
                        SELECT en.team_name
                        FROM entry en JOIN event ev ON ev.id = en.event_id
                             LEFT JOIN season_team st ON st.id = en.season_team_id
                        WHERE lower(trim(en.team_name)) = :key AND st.privateer_driver_id IS NULL
                        ORDER BY ev.event_date DESC NULLS LAST, ev.id DESC
                        LIMIT 1
                        """)
                .param("key", key)
                .query(String.class)
                .optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such team"));

        String notes = db.sql("SELECT notes FROM team_note WHERE name = :key")
                .param("key", key)
                .query(String.class)
                .optional()
                .orElse(null);

        return new TeamProfile(displayName, notes, roster(key), championships(key));
    }

    /** Current programs: one block per season of the team's newest year (a
     *  dual-program team like Winward runs two series in the same year), cars
     *  and crew as of the team's latest event with an entry list there. */
    private List<RosterSeason> roster(String key) {
        record SeasonRef(long seasonId, int year, String seriesName, long seriesId,
                         long eventId, String eventName) {
        }
        List<SeasonRef> seasons = db.sql("""
                        SELECT DISTINCT ON (s.id) s.id AS season_id, s.year, sr.name AS series_name,
                               sr.id AS series_id, ev.id AS event_id, ev.name AS event_name
                        FROM entry en
                                 JOIN event ev ON ev.id = en.event_id
                                 JOIN season s ON s.id = ev.season_id
                                 JOIN series sr ON sr.id = s.series_id
                                 LEFT JOIN season_team st ON st.id = en.season_team_id
                        WHERE lower(trim(en.team_name)) = :key
                          AND st.privateer_driver_id IS NULL
                          AND s.year = (SELECT max(s2.year)
                                        FROM entry en2
                                                 JOIN event ev2 ON ev2.id = en2.event_id
                                                 JOIN season s2 ON s2.id = ev2.season_id
                                        WHERE lower(trim(en2.team_name)) = :key)
                        ORDER BY s.id, ev.event_date DESC NULLS LAST, ev.id DESC
                        """)
                .param("key", key)
                .query((rs, i) -> new SeasonRef(rs.getLong("season_id"), rs.getInt("year"),
                        rs.getString("series_name"), rs.getLong("series_id"),
                        rs.getLong("event_id"), rs.getString("event_name")))
                .list();

        List<RosterSeason> roster = new ArrayList<>();
        for (SeasonRef season : seasons) {
            Map<String, String> classColors = new HashMap<>();
            db.sql("SELECT class_code, color FROM class_style WHERE series_id = :seriesId")
                    .param("seriesId", season.seriesId())
                    .query((rs, i) -> classColors.put(rs.getString("class_code"), rs.getString("color")))
                    .list();

            record CarRow(long entryId, String carNumber, String className, String vehicle,
                          Long imageVersion) {
            }
            List<CarRow> cars = db.sql("""
                            SELECT en.id, en.car_number, en.class_name, en.vehicle,
                                   (SELECT (extract(epoch FROM ci.uploaded_at) * 1000)::bigint
                                    FROM car_image ci
                                    WHERE ci.season_id = :seasonId AND ci.car_number = en.car_number
                                   ) AS image_version
                            FROM entry en
                            WHERE en.event_id = :eventId AND lower(trim(en.team_name)) = :key
                            ORDER BY en.class_name, en.car_number
                            """)
                    .param("seasonId", season.seasonId())
                    .param("eventId", season.eventId())
                    .param("key", key)
                    .query((rs, i) -> new CarRow(rs.getLong("id"), rs.getString("car_number"),
                            rs.getString("class_name"), rs.getString("vehicle"),
                            rs.getObject("image_version", Long.class)))
                    .list();

            Map<Long, List<RosterDriver>> crews = new HashMap<>();
            if (!cars.isEmpty()) {
                db.sql("""
                                SELECT da.entry_id, da.driver_id, da.rating, da.is_tbd,
                                       COALESCE(d.first_name || ' ' || d.surname, 'TBD') AS name
                                FROM driver_assignment da
                                         LEFT JOIN driver d ON d.id = da.driver_id
                                WHERE da.entry_id IN (:entryIds)
                                ORDER BY da.seat_order
                                """)
                        .param("entryIds", cars.stream().map(CarRow::entryId).toList())
                        .query((rs, i) -> crews
                                .computeIfAbsent(rs.getLong("entry_id"), k -> new ArrayList<>())
                                .add(new RosterDriver(rs.getObject("driver_id", Long.class),
                                        rs.getString("name"), rs.getString("rating"),
                                        rs.getBoolean("is_tbd"))))
                        .list();
            }

            roster.add(new RosterSeason(season.seasonId(), season.year(), season.seriesName(),
                    season.eventName(),
                    cars.stream().map(c -> new RosterCar(c.entryId(), c.carNumber(), c.className(),
                            classColors.getOrDefault(c.className(), "#1a1a1a"), c.vehicle(),
                            c.imageVersion(), crews.getOrDefault(c.entryId(), List.of()))).toList()));
        }
        return roster;
    }

    private List<TeamChampMatrix> championships(String key) {
        // GROUP BY, not DISTINCT: a two-car team has two standings rows in the
        // same championship and must yield the championship once.
        List<Long> champIds = db.sql("""
                        SELECT c.id
                        FROM standings_row srw
                                 JOIN championship c ON c.id = srw.championship_id
                                 JOIN championship_group g ON g.id = c.group_id
                                 JOIN season s ON s.id = c.season_id
                        WHERE g.kind = 'TEAMS' AND lower(trim(srw.competitor_name)) = :key
                        GROUP BY c.id, s.year
                        ORDER BY s.year DESC, c.id
                        """)
                .param("key", key)
                .query(Long.class)
                .list();

        List<TeamChampMatrix> championships = new ArrayList<>();
        for (long champId : champIds) {
            Recap recap = seasonView.recap(champId);
            List<TeamChampEntry> entries = recap.rows().stream()
                    .filter(r -> key.equals(normalize(r.competitorName())))
                    .map(r -> new TeamChampEntry(
                            Objects.toString(r.carNumber(), r.competitorKey()), r.position(),
                            r.totalPoints(), r.cells(), r.pointsByRound()))
                    .toList();
            if (!entries.isEmpty()) {
                championships.add(new TeamChampMatrix(recap.championship().id(),
                        recap.championship().title(), recap.championship().className(),
                        recap.championship().seriesName(), recap.championship().year(),
                        recap.championship().seasonId(), recap.rounds(), entries));
            }
        }
        return championships;
    }

    /* ------------------------------------------------------------------ */
    /* Notes                                                                */
    /* ------------------------------------------------------------------ */

    public record NotesUpdate(String name, String notes) {
    }

    @PatchMapping("/teams/notes")
    public void updateNotes(@RequestBody NotesUpdate body) {
        String key = normalize(body.name());
        if (key.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Team name is required");
        }
        String notes = body.notes() == null || body.notes().isBlank() ? null : body.notes().trim();
        db.sql("""
                        INSERT INTO team_note (name, notes)
                        VALUES (:key, :notes)
                        ON CONFLICT (name) DO UPDATE SET notes = EXCLUDED.notes, updated_at = now()
                        """)
                .param("key", key)
                .param("notes", notes)
                .update();
    }
}
