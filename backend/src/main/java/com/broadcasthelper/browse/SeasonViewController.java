package com.broadcasthelper.browse;

import com.broadcasthelper.sheets.SheetController;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Read-only endpoints backing the season hub rebuild: the championship recap
 * (standings enriched with per-round points and start→finish cells), the
 * season driver-lineup matrix, and per-event session results. Queries follow
 * BrowseController's conventions; the importers own all writes.
 */
@RestController
@RequestMapping("/api")
public class SeasonViewController {

    private final JdbcClient db;

    public SeasonViewController(JdbcClient db) {
        this.db = db;
    }

    /* ------------------------------------------------------------------ */
    /* Championship recap                                                   */
    /* ------------------------------------------------------------------ */

    public record ChampInfo(long id, String title, String className, String kind, String family,
                            boolean isCup, long seasonId, int year, String seriesName) {
    }

    /** One column: a round of THIS championship's published calendar. eventId is
     *  the season event it maps to (by venue), null when that event isn't
     *  imported yet — the column still renders, its cells blank. */
    public record RecapRound(int round, String venue, Long eventId, int raceCount) {
    }

    public record RecapRace(int race, Integer start, Integer finish, String status, boolean notFinished) {
    }

    public record RecapRow(int position, String competitorKey, String competitorName, String carNumber,
                           String teamName, double totalPoints,
                           Map<Integer, Double> pointsByRound,
                           Map<Integer, List<RecapRace>> cells) {
    }

    public record Recap(ChampInfo championship, List<RecapRound> rounds, List<RecapRow> rows) {
    }

    @GetMapping("/championships/{id}/recap")
    public Recap recap(@PathVariable long id) {
        ChampInfo champ = db.sql("""
                        SELECT c.id, c.title, c.class_name, g.kind, g.family, g.is_cup,
                               s.id AS season_id, s.year, sr.name AS series_name
                        FROM championship c
                                 JOIN championship_group g ON g.id = c.group_id
                                 JOIN season s ON s.id = c.season_id
                                 JOIN series sr ON sr.id = s.series_id
                        WHERE c.id = :id
                        """)
                .param("id", id)
                .query((rs, i) -> new ChampInfo(rs.getLong("id"), rs.getString("title"),
                        rs.getString("class_name"), rs.getString("kind"), rs.getString("family"),
                        rs.getBoolean("is_cup"), rs.getLong("season_id"), rs.getInt("year"),
                        rs.getString("series_name")))
                .optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such championship"));

        // The championship's own calendar: sessions grouped into rounds by event
        // name (a cup's calendar is a subset of the season's, so columns come
        // from here, not from the season's events).
        record ChampRound(int round, String eventName) {
        }
        List<ChampRound> champRounds = db.sql("""
                        SELECT DISTINCT dense_rank() OVER (ORDER BY first_idx) AS round_no, event_name
                        FROM (
                            SELECT event_name,
                                   min(session_index) OVER (PARTITION BY event_name) AS first_idx
                            FROM championship_session
                            WHERE championship_id = :id
                        ) t
                        ORDER BY round_no
                        """)
                .param("id", id)
                .query((rs, i) -> new ChampRound(rs.getInt("round_no"), rs.getString("event_name")))
                .list();

        // Season events with at least one race, keyed by venue for round matching.
        record SeasonEvent(long id, String venue, int raceCount) {
        }
        Map<String, SeasonEvent> eventsByVenue = new LinkedHashMap<>();
        db.sql("""
                        SELECT ev.id, ev.name, ev.circuit_name,
                               (SELECT count(*) FROM race_session rs
                                WHERE rs.event_id = ev.id AND rs.session_type = 'RACE') AS race_count
                        FROM event ev
                        WHERE ev.season_id = :seasonId AND ev.round_ordinal IS NOT NULL
                        ORDER BY ev.round_ordinal
                        """)
                .param("seasonId", champ.seasonId())
                .query((rs, i) -> new SeasonEvent(rs.getLong("id"),
                        SheetController.venueAbbrev(rs.getString("name"), rs.getString("circuit_name")),
                        rs.getInt("race_count")))
                .list()
                .forEach(e -> eventsByVenue.putIfAbsent(e.venue(), e));

        List<RecapRound> rounds = new ArrayList<>();
        Map<Long, Integer> roundByEventId = new HashMap<>();
        for (ChampRound cr : champRounds) {
            String venue = SheetController.venueAbbrev(cr.eventName(), null);
            SeasonEvent match = eventsByVenue.get(venue);
            rounds.add(new RecapRound(cr.round(), venue,
                    match != null ? match.id() : null,
                    match != null ? Math.max(match.raceCount(), 1) : 1));
            if (match != null) {
                roundByEventId.put(match.id(), cr.round());
            }
        }

        // Standings rows with per-round points. A round where every session was
        // did_not_race is omitted from the row's points map (blank, not 0).
        record RowHeader(long rowId, int position, String key, String name, double totalPoints) {
        }
        List<RowHeader> headers = db.sql("""
                        SELECT id, position, competitor_key, competitor_name, total_points
                        FROM standings_row
                        WHERE championship_id = :id
                        ORDER BY position, competitor_key
                        """)
                .param("id", id)
                .query((rs, i) -> new RowHeader(rs.getLong("id"), rs.getInt("position"),
                        rs.getString("competitor_key"), rs.getString("competitor_name"),
                        rs.getDouble("total_points")))
                .list();

        record SessionPoints(long rowId, int round, double points, String status) {
        }
        Map<Long, Map<Integer, Double>> pointsByRow = new HashMap<>();
        Map<Long, Map<Integer, Boolean>> contestedByRow = new HashMap<>();
        db.sql("""
                        SELECT sr.id AS row_id, rnd.round_no, ssp.total_points, ssp.status
                        FROM standings_row sr
                                 JOIN standings_session_points ssp ON ssp.standings_row_id = sr.id
                                 JOIN (
                                     SELECT session_index,
                                            dense_rank() OVER (ORDER BY first_idx) AS round_no
                                     FROM (
                                         SELECT session_index,
                                                min(session_index) OVER (PARTITION BY event_name) AS first_idx
                                         FROM championship_session
                                         WHERE championship_id = :id
                                     ) t
                                 ) rnd ON rnd.session_index = ssp.session_index
                        WHERE sr.championship_id = :id
                        """)
                .param("id", id)
                .query((rs, i) -> new SessionPoints(rs.getLong("row_id"), rs.getInt("round_no"),
                        rs.getDouble("total_points"), rs.getString("status")))
                .list()
                .forEach(sp -> {
                    pointsByRow.computeIfAbsent(sp.rowId(), k -> new HashMap<>())
                            .merge(sp.round(), sp.points(), Double::sum);
                    boolean contested = !"did_not_race".equalsIgnoreCase(Objects.toString(sp.status(), ""));
                    contestedByRow.computeIfAbsent(sp.rowId(), k -> new HashMap<>())
                            .merge(sp.round(), contested, Boolean::logicalOr);
                });

        // Start→finish cells for this class across the matched events, plus the
        // driver names per entry (for DRIVERS championships, rows match entries
        // by crew member name; TEAMS match by car number).
        record Cell(long entryId, String carNumber, String team, long eventId, int raceOrdinal,
                    Integer start, Integer finish, String status, boolean notFinished) {
        }
        List<Cell> cells = roundByEventId.isEmpty() ? List.of() : db.sql("""
                        SELECT en.id AS entry_id, en.car_number, en.team_name, ev.id AS event_id,
                               rs.ordinal AS race_ordinal,
                               g.position_in_class AS start_pos, r.position_in_class AS finish_pos, r.status,
                               COALESCE(r.not_finished, false) AS not_finished
                        FROM entry en
                                 JOIN event ev ON ev.id = en.event_id
                                 JOIN race_session rs ON rs.event_id = ev.id AND rs.session_type = 'RACE'
                                 LEFT JOIN result r ON r.session_id = rs.id AND r.entry_id = en.id
                                 LEFT JOIN grid_position g ON g.session_id = rs.id AND g.entry_id = en.id
                        WHERE ev.id IN (:eventIds) AND en.class_name = :className
                        ORDER BY ev.id, rs.ordinal
                        """)
                .param("eventIds", roundByEventId.keySet())
                .param("className", champ.className())
                .query((rs, i) -> new Cell(rs.getLong("entry_id"), rs.getString("car_number"),
                        rs.getString("team_name"), rs.getLong("event_id"), rs.getInt("race_ordinal"),
                        rs.getObject("start_pos", Integer.class), rs.getObject("finish_pos", Integer.class),
                        rs.getString("status"), rs.getBoolean("not_finished")))
                .list()
                .stream()
                .filter(c -> c.start() != null || c.finish() != null || c.status() != null)
                .toList();

        boolean driversKind = "DRIVERS".equals(champ.kind());
        Map<Long, List<String>> driverNamesByEntry = new HashMap<>();
        if (driversKind && !roundByEventId.isEmpty()) {
            db.sql("""
                            SELECT da.entry_id, COALESCE(d.first_name || ' ' || d.surname, '') AS name
                            FROM driver_assignment da
                                     LEFT JOIN driver d ON d.id = da.driver_id
                            WHERE da.entry_id IN (SELECT id FROM entry
                                                  WHERE event_id IN (:eventIds) AND class_name = :className)
                            """)
                    .param("eventIds", roundByEventId.keySet())
                    .param("className", champ.className())
                    .query((rs, i) -> driverNamesByEntry
                            .computeIfAbsent(rs.getLong("entry_id"), k -> new ArrayList<>())
                            .add(rs.getString("name")))
                    .list();
        }

        // A round nobody has scored in yet is a future round: blank, not a
        // column of zeros. (A completed round always pays someone.)
        java.util.Set<Integer> scoredRounds = new java.util.HashSet<>();
        pointsByRow.values().forEach(m -> m.forEach((round, pts) -> {
            if (pts != 0) {
                scoredRounds.add(round);
            }
        }));

        List<RecapRow> rows = new ArrayList<>();
        for (RowHeader h : headers) {
            Map<Integer, List<RecapRace>> byRound = new LinkedHashMap<>();
            String carNumber = driversKind ? null : h.key();
            String teamName = driversKind ? null : h.name();
            int latestRound = -1;
            for (Cell c : cells) {
                boolean mine = driversKind
                        ? matchesDriver(driverNamesByEntry.get(c.entryId()), h.key(), h.name())
                        : sameCarNumber(c.carNumber(), h.key());
                if (!mine) {
                    continue;
                }
                int round = roundByEventId.get(c.eventId());
                byRound.computeIfAbsent(round, k -> new ArrayList<>())
                        .add(new RecapRace(c.raceOrdinal(), c.start(), c.finish(), c.status(), c.notFinished()));
                if (driversKind && round > latestRound) {
                    latestRound = round;
                    carNumber = c.carNumber();
                    teamName = c.team();
                }
            }

            Map<Integer, Double> points = new LinkedHashMap<>();
            Map<Integer, Double> rowPoints = pointsByRow.getOrDefault(h.rowId(), Map.of());
            Map<Integer, Boolean> contested = contestedByRow.getOrDefault(h.rowId(), Map.of());
            rowPoints.forEach((round, pts) -> {
                if (scoredRounds.contains(round) && contested.getOrDefault(round, false)) {
                    points.put(round, pts);
                }
            });

            rows.add(new RecapRow(h.position(), h.key(), h.name(), carNumber, teamName,
                    h.totalPoints(), points, byRound));
        }

        return new Recap(champ, rounds, rows);
    }

    private static boolean sameCarNumber(String a, String b) {
        return SheetController.normalizeCarNumber(a).equals(SheetController.normalizeCarNumber(b));
    }

    private static boolean matchesDriver(List<String> entryDrivers, String key, String name) {
        if (entryDrivers == null) {
            return false;
        }
        for (String d : entryDrivers) {
            String norm = d.trim().toLowerCase();
            if (!norm.isEmpty()
                    && (norm.equals(Objects.toString(key, "").trim().toLowerCase())
                        || norm.equals(Objects.toString(name, "").trim().toLowerCase()))) {
                return true;
            }
        }
        return false;
    }

    /* ------------------------------------------------------------------ */
    /* Season lineups (entries rotation)                                    */
    /* ------------------------------------------------------------------ */

    public record LineupRound(int ordinal, String venue, long eventId, String eventName, LocalDate eventDate) {
    }

    public record LineupDriver(String name, String rating, String country, boolean isTbd) {
    }

    public record LineupCar(String carNumber, String teamName, boolean isGuest,
                            Map<Integer, List<LineupDriver>> byRound) {
    }

    public record LineupClass(String className, String color, List<LineupCar> cars) {
    }

    public record Lineups(long seasonId, List<LineupRound> rounds, List<LineupClass> classes) {
    }

    /** Rows = cars, columns = rounds, cells = that round's crew. Rounds appear
     *  as soon as they have an entry list (races not required), so next round's
     *  lineup shows before the weekend. A car absent from a round has no cell. */
    @GetMapping("/seasons/{id}/lineups")
    public Lineups lineups(@PathVariable long id) {
        Long seriesId = db.sql("SELECT series_id FROM season WHERE id = :id")
                .param("id", id)
                .query(Long.class)
                .optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such season"));

        List<LineupRound> rounds = db.sql("""
                        SELECT ev.id, ev.round_ordinal, ev.name, ev.circuit_name, ev.event_date
                        FROM event ev
                        WHERE ev.season_id = :id AND ev.round_ordinal IS NOT NULL
                          AND EXISTS (SELECT 1 FROM entry en WHERE en.event_id = ev.id)
                        ORDER BY ev.round_ordinal
                        """)
                .param("id", id)
                .query((rs, i) -> new LineupRound(rs.getInt("round_ordinal"),
                        SheetController.venueAbbrev(rs.getString("name"), rs.getString("circuit_name")),
                        rs.getLong("id"), rs.getString("name"), rs.getObject("event_date", LocalDate.class)))
                .list();

        record Row(String className, String carNumber, String team, boolean isGuest, int round,
                   String driverName, String rating, String country, boolean isTbd) {
        }
        List<Row> raw = db.sql("""
                        SELECT en.class_name, en.car_number, en.team_name, en.is_guest, ev.round_ordinal,
                               COALESCE(d.first_name || ' ' || d.surname, 'TBD') AS driver_name,
                               da.rating, d.country, da.is_tbd
                        FROM entry en
                                 JOIN event ev ON ev.id = en.event_id
                                 LEFT JOIN driver_assignment da ON da.entry_id = en.id
                                 LEFT JOIN driver d ON d.id = da.driver_id
                        WHERE ev.season_id = :id AND ev.round_ordinal IS NOT NULL
                        ORDER BY ev.round_ordinal, da.seat_order
                        """)
                .param("id", id)
                .query((rs, i) -> new Row(rs.getString("class_name"), rs.getString("car_number"),
                        rs.getString("team_name"), rs.getBoolean("is_guest"), rs.getInt("round_ordinal"),
                        rs.getString("driver_name"), rs.getString("rating"), rs.getString("country"),
                        rs.getObject("is_tbd") != null && rs.getBoolean("is_tbd")))
                .list();

        record ClassStyle(int ordinal, String color) {
        }
        Map<String, ClassStyle> classStyles = new HashMap<>();
        db.sql("SELECT class_code, ordinal, color FROM class_style WHERE series_id = :seriesId")
                .param("seriesId", seriesId)
                .query((rs, i) -> classStyles.put(rs.getString("class_code"),
                        new ClassStyle(rs.getInt("ordinal"), rs.getString("color"))))
                .list();

        record CarKey(String className, String carNumber) {
        }
        Map<CarKey, Map<Integer, List<LineupDriver>>> byCar = new LinkedHashMap<>();
        Map<CarKey, Integer> latestRound = new HashMap<>();
        Map<CarKey, String> latestTeam = new HashMap<>();
        Map<CarKey, Boolean> guest = new HashMap<>();
        for (Row r : raw) {
            CarKey key = new CarKey(r.className(), r.carNumber());
            Map<Integer, List<LineupDriver>> perRound =
                    byCar.computeIfAbsent(key, k -> new LinkedHashMap<>());
            List<LineupDriver> crew = perRound.computeIfAbsent(r.round(), k -> new ArrayList<>());
            if (r.driverName() != null && !"TBD".equals(r.driverName()) || r.isTbd()) {
                crew.add(new LineupDriver(r.driverName(), r.rating(), r.country(), r.isTbd()));
            }
            if (r.round() >= latestRound.getOrDefault(key, 0)) {
                latestRound.put(key, r.round());
                latestTeam.put(key, r.team());
                guest.put(key, r.isGuest());
            }
        }

        String defaultColor = "#1a1a1a";
        Map<String, List<LineupCar>> byClass = new LinkedHashMap<>();
        byCar.forEach((key, perRound) -> byClass.computeIfAbsent(key.className(), k -> new ArrayList<>())
                .add(new LineupCar(key.carNumber(), latestTeam.get(key),
                        guest.getOrDefault(key, false), perRound)));

        List<LineupClass> classes = byClass.entrySet().stream()
                .map(e -> {
                    List<LineupCar> sorted = e.getValue().stream()
                            .sorted(Comparator.comparingInt((LineupCar c) -> numericValue(c.carNumber()))
                                    .thenComparing(LineupCar::carNumber))
                            .toList();
                    ClassStyle st = classStyles.get(e.getKey());
                    return new LineupClass(e.getKey(), st != null ? st.color() : defaultColor, sorted);
                })
                .sorted(Comparator.comparingInt(c -> {
                    ClassStyle st = classStyles.get(c.className());
                    return st != null ? st.ordinal() : Integer.MAX_VALUE;
                }))
                .toList();

        return new Lineups(id, rounds, classes);
    }

    /* ------------------------------------------------------------------ */
    /* Event session results                                                */
    /* ------------------------------------------------------------------ */

    public record ResultRow(Integer posOverall, Integer posInClass, String carNumber, String className,
                            String teamName, String drivers, String vehicle, String status,
                            Integer laps, String elapsedTime, String gapFirst, String fastestLapTime,
                            Integer pitStops) {
    }

    public record GridRow(Integer posOverall, Integer posInClass, String carNumber, String className,
                          String teamName, String qualifyingTime) {
    }

    public record SessionResults(long sessionId, String sessionType, String name, List<ResultRow> results,
                                 List<GridRow> grid) {
    }

    public record EventResults(long eventId, String eventName, String circuitName, LocalDate eventDate,
                               Integer roundOrdinal, long seasonId, int year, String seriesName,
                               List<SessionResults> sessions) {
    }

    /** Qualifying and race classification per session, plus each race's starting
     *  grid (with qualifying time when the grid CSV carried it). Practice
     *  sessions are omitted — not broadcast reference material. */
    @GetMapping("/events/{id}/results")
    public EventResults eventResults(@PathVariable long id) {
        EventResults header = db.sql("""
                        SELECT e.id, e.name, e.circuit_name, e.event_date, e.round_ordinal,
                               s.id AS season_id, s.year, sr.name AS series_name
                        FROM event e
                                 JOIN season s ON s.id = e.season_id
                                 JOIN series sr ON sr.id = s.series_id
                        WHERE e.id = :id
                        """)
                .param("id", id)
                .query((rs, i) -> new EventResults(rs.getLong("id"), rs.getString("name"),
                        rs.getString("circuit_name"), rs.getObject("event_date", LocalDate.class),
                        rs.getObject("round_ordinal", Integer.class), rs.getLong("season_id"),
                        rs.getInt("year"), rs.getString("series_name"), new ArrayList<>()))
                .optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such event"));

        record Session(long id, String type, String name, int ordinal) {
        }
        List<Session> sessions = db.sql("""
                        SELECT id, session_type, name, ordinal
                        FROM race_session
                        WHERE event_id = :id AND session_type IN ('QUALIFYING', 'RACE')
                        ORDER BY CASE session_type WHEN 'QUALIFYING' THEN 0 ELSE 1 END, ordinal
                        """)
                .param("id", id)
                .query((rs, i) -> new Session(rs.getLong("id"), rs.getString("session_type"),
                        rs.getString("name"), rs.getInt("ordinal")))
                .list();

        for (Session s : sessions) {
            List<ResultRow> results = db.sql("""
                            SELECT r.position_overall, r.position_in_class, en.car_number, en.class_name,
                                   en.team_name, en.vehicle, r.status, r.laps, r.elapsed_time, r.gap_first,
                                   r.fastest_lap_time, r.pit_stops,
                                   (SELECT string_agg(COALESCE(d.first_name || ' ' || d.surname, 'TBD'),
                                                      ', ' ORDER BY da.seat_order)
                                    FROM driver_assignment da LEFT JOIN driver d ON d.id = da.driver_id
                                    WHERE da.entry_id = en.id) AS drivers
                            FROM result r
                                     JOIN entry en ON en.id = r.entry_id
                            WHERE r.session_id = :sessionId
                            ORDER BY r.position_overall NULLS LAST, en.car_number
                            """)
                    .param("sessionId", s.id())
                    .query((rs, i) -> new ResultRow(rs.getObject("position_overall", Integer.class),
                            rs.getObject("position_in_class", Integer.class), rs.getString("car_number"),
                            rs.getString("class_name"), rs.getString("team_name"), rs.getString("drivers"),
                            rs.getString("vehicle"), rs.getString("status"),
                            rs.getObject("laps", Integer.class), rs.getString("elapsed_time"),
                            rs.getString("gap_first"), rs.getString("fastest_lap_time"),
                            rs.getObject("pit_stops", Integer.class)))
                    .list();

            List<GridRow> grid = db.sql("""
                            SELECT g.position_overall, g.position_in_class, g.qualifying_time,
                                   en.car_number, en.class_name, en.team_name
                            FROM grid_position g
                                     JOIN entry en ON en.id = g.entry_id
                            WHERE g.session_id = :sessionId
                            ORDER BY g.position_overall NULLS LAST, en.car_number
                            """)
                    .param("sessionId", s.id())
                    .query((rs, i) -> new GridRow(rs.getObject("position_overall", Integer.class),
                            rs.getObject("position_in_class", Integer.class), rs.getString("car_number"),
                            rs.getString("class_name"), rs.getString("team_name"),
                            rs.getString("qualifying_time")))
                    .list();

            header.sessions().add(new SessionResults(s.id(), s.type(), s.name(), results, grid));
        }

        return header;
    }

    private static int numericValue(String carNumber) {
        try {
            return Integer.parseInt(carNumber);
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE;
        }
    }
}
