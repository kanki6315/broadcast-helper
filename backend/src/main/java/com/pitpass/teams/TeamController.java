package com.pitpass.teams;

import com.pitpass.browse.SeasonViewController;
import com.pitpass.browse.SeasonViewController.Recap;
import com.pitpass.browse.SeasonViewController.RecapRace;
import com.pitpass.browse.SeasonViewController.RecapRound;
import com.pitpass.drivers.DriverController.CareerTotals;
import com.pitpass.drivers.DriverController.NamedFormatLine;
import com.pitpass.drivers.DriverController.QualiLine;
import com.pitpass.drivers.DriverController.SeasonStatLine;
import com.pitpass.drivers.DriverController.SeriesStatLine;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
import java.util.Set;
import java.util.stream.Collectors;

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

    public record TeamRef(long id, String name) {
    }

    public record Lineage(TeamRef predecessor, List<TeamRef> successors) {
    }

    public record TeamProfile(Long teamId, String name, String notes, Lineage lineage,
                              List<RosterSeason> roster, List<TeamChampMatrix> championships) {
    }

    /** By id (team entity), or by name — an alias resolves to its entity, and a
     *  spelling with entries but no alias row (pre-V35 edge) still gets the
     *  legacy name-keyed profile so search hits never 404. */
    @GetMapping("/teams/profile")
    public TeamProfile profile(@RequestParam(required = false) Long id,
                               @RequestParam(required = false) String name) {
        record TeamRow(long id, String name, Long predecessorId) {
        }
        if (id != null) {
            TeamRow team = db.sql("SELECT id, name, predecessor_id FROM team WHERE id = :id")
                    .param("id", id)
                    .query((rs, i) -> new TeamRow(rs.getLong("id"), rs.getString("name"),
                            rs.getObject("predecessor_id", Long.class)))
                    .optional()
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such team"));
            return entityProfile(team.id(), team.name(), team.predecessorId());
        }
        String key = normalize(name);
        if (key.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Team id or name is required");
        }
        TeamRow resolved = db.sql("""
                        SELECT t.id, t.name, t.predecessor_id
                        FROM team_alias ta JOIN team t ON t.id = ta.team_id
                        WHERE lower(trim(ta.alias)) = :key
                        """)
                .param("key", key)
                .query((rs, i) -> new TeamRow(rs.getLong("id"), rs.getString("name"),
                        rs.getObject("predecessor_id", Long.class)))
                .optional()
                .orElse(null);
        if (resolved != null) {
            return entityProfile(resolved.id(), resolved.name(), resolved.predecessorId());
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

        return new TeamProfile(null, displayName, notes(key), null,
                roster(null, key), championships(Set.of(key)));
    }

    private TeamProfile entityProfile(long teamId, String name, Long predecessorId) {
        Set<String> aliasKeys = db.sql("SELECT alias FROM team_alias WHERE team_id = :id")
                .param("id", teamId)
                .query(String.class)
                .list()
                .stream()
                .map(TeamController::normalize)
                .collect(Collectors.toSet());

        TeamRef predecessor = predecessorId == null ? null
                : db.sql("SELECT id, name FROM team WHERE id = :id")
                        .param("id", predecessorId)
                        .query((rs, i) -> new TeamRef(rs.getLong("id"), rs.getString("name")))
                        .optional()
                        .orElse(null);
        List<TeamRef> successors = db.sql("""
                        SELECT id, name FROM team WHERE predecessor_id = :id ORDER BY lower(name)
                        """)
                .param("id", teamId)
                .query((rs, i) -> new TeamRef(rs.getLong("id"), rs.getString("name")))
                .list();

        return new TeamProfile(teamId, name, notes(normalize(name)),
                new Lineage(predecessor, successors),
                roster(teamId, null), championships(aliasKeys));
    }

    private String notes(String key) {
        return db.sql("SELECT notes FROM team_note WHERE name = :key")
                .param("key", key)
                .query(String.class)
                .optional()
                .orElse(null);
    }

    /** Current programs: one block per season of the team's newest year (a
     *  dual-program team like Winward runs two series in the same year), cars
     *  and crew as of the team's latest event with an entry list there. Keyed
     *  by team_id when the entity exists — that's what folds sponsor-variant
     *  spellings into one roster — else by the legacy normalized name. */
    private List<RosterSeason> roster(Long teamId, String key) {
        String entryMatch = teamId != null ? "en.team_id = :ident" : "lower(trim(en.team_name)) = :ident";
        String entry2Match = teamId != null ? "en2.team_id = :ident" : "lower(trim(en2.team_name)) = :ident";
        Object ident = teamId != null ? teamId : key;
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
                        WHERE %s
                          AND st.privateer_driver_id IS NULL
                          AND s.year = (SELECT max(s2.year)
                                        FROM entry en2
                                                 JOIN event ev2 ON ev2.id = en2.event_id
                                                 JOIN season s2 ON s2.id = ev2.season_id
                                        WHERE %s)
                        ORDER BY s.id, ev.event_date DESC NULLS LAST, ev.id DESC
                        """.formatted(entryMatch, entry2Match))
                .param("ident", ident)
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
                            WHERE en.event_id = :eventId AND %s
                            ORDER BY en.class_name, en.car_number
                            """.formatted(entryMatch))
                    .param("seasonId", season.seasonId())
                    .param("eventId", season.eventId())
                    .param("ident", ident)
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

    /** keys are the team's alias spellings, normalized — any of them may be
     *  what a standings file called the team. */
    private List<TeamChampMatrix> championships(Set<String> keys) {
        // GROUP BY, not DISTINCT: a two-car team has two standings rows in the
        // same championship and must yield the championship once.
        List<Long> champIds = db.sql("""
                        SELECT c.id
                        FROM standings_row srw
                                 JOIN championship c ON c.id = srw.championship_id
                                 JOIN championship_group g ON g.id = c.group_id
                                 JOIN season s ON s.id = c.season_id
                        WHERE g.kind = 'TEAMS' AND lower(trim(srw.competitor_name)) IN (:keys)
                        GROUP BY c.id, s.year
                        ORDER BY s.year DESC, c.id
                        """)
                .param("keys", List.copyOf(keys))
                .query(Long.class)
                .list();

        List<TeamChampMatrix> championships = new ArrayList<>();
        for (long champId : champIds) {
            Recap recap = seasonView.recap(champId);
            List<TeamChampEntry> entries = recap.rows().stream()
                    .filter(r -> keys.contains(normalize(r.competitorName())))
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
    /* Stats                                                                */
    /* ------------------------------------------------------------------ */

    public record TeamStats(long teamId, CareerTotals career, List<SeriesStatLine> bySeries,
                            List<SeasonStatLine> seasons) {
    }

    /**
     * Career tallies for the team modal, mirroring the driver stats grains
     * (career / per-series all-time / per-season) so both modals read the
     * same. Counting is per car-entry — a two-car team scores two starts a
     * race — and a quali claim needs no driver attribution: whoever set the
     * time, the entry's team owns the result. Reuses the driver stat record
     * shapes so the payloads serialize identically.
     */
    @GetMapping("/teams/{id}/stats")
    public TeamStats stats(@PathVariable long id) {
        db.sql("SELECT 1 FROM team WHERE id = :id").param("id", id).query(Integer.class)
                .optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such team"));

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
                        WHERE en.team_id = :id
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
        List<QualiAgg> qualiAggs = db.sql("""
                        SELECT s.id AS season_id, s.series_id, s.kind = 'QUALIFIER' AS qualifier, en.class_name,
                               count(*) FILTER (WHERE r.position_in_class IS NOT NULL) AS sessions,
                               count(*) FILTER (WHERE r.position_in_class = 1)  AS poles,
                               count(*) FILTER (WHERE r.position_in_class <= 5) AS top5s
                        FROM result r
                                 JOIN race_session rs ON rs.id = r.session_id AND rs.session_type = 'QUALIFYING'
                                 JOIN event ev ON ev.id = rs.event_id
                                 JOIN season s ON s.id = ev.season_id
                                 JOIN entry en ON en.id = r.entry_id
                        WHERE en.team_id = :id
                        GROUP BY s.id, s.series_id, en.class_name
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
        // up — same rule as the driver profile.
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
        return new TeamStats(id, career, bySeries, seasons);
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
