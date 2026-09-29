package com.pitpass.live;

import com.fasterxml.jackson.databind.JsonNode;
import com.pitpass.live.LiveCarSummaries.CarSummary;
import com.pitpass.live.LiveTimingService.State;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * What the timing page polls: the tower, one car's laps and stints, drive
 * time, and the sessions recorded for an event. The tower's order and gaps
 * come from the feed's standings (via {@link LiveClassificationService});
 * laps and stints from the in-memory summaries and live_lap / live_stint.
 *
 * The tower carries no timestamps or counters, so it changes only when the
 * timing does and the API's ETag answers 304 in between. Stint time is sent
 * as the stint's start, for the client to count up from.
 */
@Component
public class LiveTimingPageService {

    /**
     * feedClockMs is the newest time the feed itself has reported (the last
     * lap's end): what a stint's running time counts up to when the session is
     * a replay or over. It moves only when a lap completes, as the lap fields
     * do, so it costs the ETag nothing.
     */
    public record Tower(State state, Long eventId, String eventName, LiveTimingService.Session session,
                        Long sessionDbId, Long feedClockMs, List<TowerClass> classes, int matched, int total) {
    }

    /** color is the series' class_style colour (#rrggbb), or null when the class has none. */
    public record TowerClass(String className, String feedClass, String color, List<TowerCar> cars) {
    }

    /** energyPct stays null until the IMSA telemetry adapter (slice 4). */
    public record TowerCar(int position, String carNumber, Long entryId, String teamName, String vehicle,
                           String manufacturer, String status, Integer laps,
                           Long gapToLeaderMs, Integer gapToLeaderLaps, Long intervalMs, Integer intervalLaps,
                           Integer driverOrder, String driverName, String driverShortName, String driverRating,
                           Integer lastLap, Integer lastLapMs, Integer bestLap, Integer bestLapMs,
                           boolean inPit, Long stintStartMs, Integer stintLaps, Double energyPct) {
    }

    public record SessionSummary(long sessionDbId, Long eventId, String name, String type, Long dateMs,
                                 int laps, int cars, boolean current) {
    }

    public record LapRow(int lap, Integer driverOrder, Integer driverLap, Integer position, Long startTimeMs,
                         Integer lapTimeMs, List<Integer> sectorMs, List<String> sectorFlags, Boolean valid,
                         Boolean longLap, Boolean shortLap, Integer trackLimits, Float topSpeed,
                         Long pitInMs, Long pitOutMs) {
    }

    public record StintRow(long startTimeMs, String type, String pitType, Integer driverOrder, Integer openLap,
                           Integer closeLap, Long finishTimeMs, Long driverAccumSessionTrackMs,
                           Long driverAccumSessionMs, Long driverAccumTrackMs, Long driverAccumMs) {
    }

    public record DriverRow(int driverOrder, String firstName, String lastName, String shortName, String license,
                            String rating, Long driverId) {
    }

    public record CarDetail(long sessionDbId, String carNumber, List<DriverRow> drivers, List<LapRow> laps,
                            List<StintRow> stints) {
    }

    public record DriveTimeResponse(long sessionDbId, Long eventId, List<DriveTimeRuleController.Rule> rules,
                                    List<DriveTime.Result> drivers) {
    }

    /** Past this, the newest feed time is not "now": the session is a replay or over. */
    private static final long LIVE_WINDOW_MS = 10 * 60_000;

    private final JdbcClient db;
    private final LiveTimingService live;
    private final LiveClassificationService classification;

    public LiveTimingPageService(JdbcClient db, LiveTimingService live, LiveClassificationService classification) {
        this.db = db;
        this.live = live;
        this.classification = classification;
    }

    // ---- tower -----------------------------------------------------------------------

    public Tower tower() {
        LiveClassificationService.Response order = classification.current();
        Long session = live.analysisSessionDbId();
        Map<String, CarSummary> summaries = live.carSummaries().stream()
                .collect(Collectors.toMap(CarSummary::car, Function.identity()));
        Map<String, int[]> bestFromDb = session == null ? Map.of() : staleBests(session, summaries);
        Map<String, DriverRow> resolved = session == null ? Map.of() : resolvedDrivers(session);
        JsonNode feedEntries = live.state("timing.session.entry");
        Map<String, String> colors = order.eventId() == null ? Map.of() : classColors(order.eventId());

        List<TowerClass> classes = new ArrayList<>();
        for (var cls : order.classification().classes()) {
            List<TowerCar> cars = new ArrayList<>();
            for (var car : cls.cars()) {
                CarSummary s = summaries.get(car.carNumber());
                JsonNode feedCar = feedEntries == null ? null : feedEntries.get(car.carNumber());
                Integer order2 = feedCar != null && feedCar.hasNonNull("currentDriver")
                        ? feedCar.path("currentDriver").asInt() : s == null ? null : s.stintDriverOrder();
                DriverRow driver = order2 == null ? null : resolved.get(car.carNumber() + "#" + order2);
                JsonNode feedDriver = order2 == null || feedCar == null ? null
                        : feedCar.path("drivers").get(String.valueOf(order2));
                String name = driver != null ? join(driver.firstName(), driver.lastName())
                        : feedDriver == null ? null : join(text(feedDriver, "firstName"), text(feedDriver, "lastName"));
                String shortName = driver != null ? driver.shortName() : feedDriver == null ? null : text(feedDriver, "shortName");
                String rating = driver != null ? driver.rating() : feedDriver == null ? null : initial(text(feedDriver, "license"));
                int[] best = bestFromDb.get(car.carNumber());
                cars.add(new TowerCar(car.position(), car.carNumber(), car.entryId(), car.teamName(), car.vehicle(),
                        car.manufacturer(), car.status(), car.laps(),
                        car.gapToLeaderMs(), car.gapToLeaderLaps(), car.intervalMs(), car.intervalLaps(),
                        order2, name, shortName, rating,
                        s == null ? null : s.lastLap(), s == null ? null : s.lastLapMs(),
                        best != null ? Integer.valueOf(best[0]) : s == null ? null : s.bestLap(),
                        best != null ? Integer.valueOf(best[1]) : s == null ? null : s.bestLapMs(),
                        s != null && "PIT".equalsIgnoreCase(s.stintType()),
                        s == null ? null : s.stintStartMs(), s == null ? null : s.lapsInStint(), null));
            }
            classes.add(new TowerClass(cls.className(), cls.feedClass(),
                    colors.get(cls.className().trim().toLowerCase()), cars));
        }
        return new Tower(order.state(), order.eventId(), order.eventName(), order.session(), session,
                session == null ? null : latestFeedTime(session), classes,
                order.classification().matched(), order.classification().total());
    }

    /** The event's series' class colours, keyed by lower-cased class code. */
    private Map<String, String> classColors(long eventId) {
        Map<String, String> out = new HashMap<>();
        db.sql("""
                SELECT cs.class_code, cs.color FROM class_style cs
                JOIN season se ON se.series_id = cs.series_id JOIN event ev ON ev.season_id = se.id
                WHERE ev.id = :e
                """)
                .param("e", eventId)
                .query((rs, i) -> out.put(rs.getString("class_code").trim().toLowerCase(), rs.getString("color")))
                .list();
        return out;
    }

    /** The best lap of each car whose in-memory best was invalidated after the fact. */
    private Map<String, int[]> staleBests(long session, Map<String, CarSummary> summaries) {
        List<String> stale = summaries.values().stream().filter(CarSummary::bestStale).map(CarSummary::car).toList();
        if (stale.isEmpty()) {
            return Map.of();
        }
        Map<String, int[]> out = new HashMap<>();
        db.sql("""
                SELECT DISTINCT ON (car_number) car_number, lap_number, lap_time_ms FROM live_lap
                WHERE session_db_id = :s AND car_number IN (:cars) AND lap_time_ms > 0 AND is_valid IS NOT FALSE
                ORDER BY car_number, lap_time_ms, lap_number
                """)
                .param("s", session).param("cars", stale)
                .query((rs, i) -> out.put(rs.getString("car_number"),
                        new int[] {rs.getInt("lap_number"), rs.getInt("lap_time_ms")}))
                .list();
        return out;
    }

    private Map<String, DriverRow> resolvedDrivers(long session) {
        Map<String, DriverRow> out = new HashMap<>();
        drivers(session, null).forEach(e -> out.put(e.getKey() + "#" + e.getValue().driverOrder(), e.getValue()));
        return out;
    }

    // ---- sessions and cars -------------------------------------------------------------

    public List<SessionSummary> sessions(long eventId) {
        Long current = live.analysisSessionDbId();
        return db.sql("""
                SELECT s.session_db_id, s.event_id, s.name, s.type, s.session_date_ms,
                       (SELECT count(*) FROM live_lap l WHERE l.session_db_id = s.session_db_id) AS laps,
                       (SELECT count(DISTINCT car_number) FROM live_lap l WHERE l.session_db_id = s.session_db_id) AS cars
                FROM live_session s WHERE s.event_id = :e
                ORDER BY s.session_date_ms DESC NULLS LAST, s.session_db_id DESC
                """)
                .param("e", eventId)
                .query((rs, i) -> new SessionSummary(rs.getLong("session_db_id"), rs.getObject("event_id", Long.class),
                        rs.getString("name"), rs.getString("type"), rs.getObject("session_date_ms", Long.class),
                        rs.getInt("laps"), rs.getInt("cars"),
                        current != null && current == rs.getLong("session_db_id")))
                .list();
    }

    public CarDetail car(String carNumber, Long sessionParam) {
        long session = session(sessionParam);
        List<LapRow> laps = db.sql("""
                SELECT * FROM live_lap WHERE session_db_id = :s AND car_number = :car ORDER BY lap_number
                """)
                .param("s", session).param("car", carNumber)
                .query((rs, i) -> new LapRow(rs.getInt("lap_number"), integer(rs, "driver_order"),
                        integer(rs, "driver_lap_number"), integer(rs, "position"), rs.getObject("start_time_ms", Long.class),
                        integer(rs, "lap_time_ms"), ints(rs.getArray("sector_ms")), texts(rs.getArray("sector_flags")),
                        rs.getObject("is_valid", Boolean.class), rs.getObject("is_long_lap", Boolean.class),
                        rs.getObject("is_short_lap", Boolean.class), integer(rs, "track_limits"),
                        rs.getObject("top_speed", Float.class),
                        rs.getObject("pit_in_time_ms", Long.class), rs.getObject("pit_out_time_ms", Long.class)))
                .list();
        List<StintRow> stints = db.sql("""
                SELECT * FROM live_stint WHERE session_db_id = :s AND car_number = :car ORDER BY start_time_ms
                """)
                .param("s", session).param("car", carNumber)
                .query((rs, i) -> new StintRow(rs.getLong("start_time_ms"), rs.getString("type"), rs.getString("pit_type"),
                        integer(rs, "driver_order"), integer(rs, "open_lap_number"), integer(rs, "close_lap_number"),
                        rs.getObject("finish_time_ms", Long.class),
                        rs.getObject("driver_accum_session_track_ms", Long.class),
                        rs.getObject("driver_accum_session_ms", Long.class),
                        rs.getObject("driver_accum_track_ms", Long.class),
                        rs.getObject("driver_accum_ms", Long.class)))
                .list();
        List<DriverRow> drivers = drivers(session, carNumber).stream().map(Map.Entry::getValue).toList();
        if (laps.isEmpty() && stints.isEmpty() && drivers.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No laps for car " + carNumber + " in this session");
        }
        return new CarDetail(session, carNumber, drivers, laps, stints);
    }

    private List<Map.Entry<String, DriverRow>> drivers(long session, String car) {
        return db.sql("""
                SELECT car_number, driver_order, first_name, last_name, short_name, license, rating, driver_id
                FROM live_driver WHERE session_db_id = :s AND (CAST(:car AS text) IS NULL OR car_number = :car)
                ORDER BY car_number, driver_order
                """)
                .param("s", session).param("car", car, java.sql.Types.VARCHAR)
                .query((rs, i) -> Map.entry(rs.getString("car_number"), new DriverRow(rs.getInt("driver_order"),
                        rs.getString("first_name"), rs.getString("last_name"), rs.getString("short_name"),
                        rs.getString("license"), rs.getString("rating"), rs.getObject("driver_id", Long.class))))
                .list();
    }

    // ---- drive time --------------------------------------------------------------------

    public DriveTimeResponse driveTime(Long sessionParam) {
        long session = session(sessionParam);
        Long eventId = db.sql("SELECT event_id FROM live_session WHERE session_db_id = :s").param("s", session)
                .query((rs, i) -> rs.getObject("event_id", Long.class)).optional().orElse(null);
        List<DriveTimeRuleController.Rule> rules = eventId == null ? List.of() : DriveTimeRuleController.rules(db, eventId);
        List<DriveTime.Stint> stints = db.sql("""
                SELECT car_number, start_time_ms, type, driver_order, finish_time_ms, driver_accum_session_track_ms
                FROM live_stint WHERE session_db_id = :s
                """)
                .param("s", session)
                .query((rs, i) -> new DriveTime.Stint(rs.getString("car_number"), rs.getLong("start_time_ms"),
                        rs.getString("type"), integer(rs, "driver_order"), rs.getObject("finish_time_ms", Long.class),
                        rs.getObject("driver_accum_session_track_ms", Long.class)))
                .list();
        List<DriveTime.Driver> drivers = db.sql("""
                SELECT d.car_number, d.driver_order, d.first_name, d.last_name, d.rating, d.driver_id, en.class_name
                FROM live_driver d LEFT JOIN entry en ON en.id = d.entry_id
                WHERE d.session_db_id = :s
                """)
                .param("s", session)
                .query((rs, i) -> new DriveTime.Driver(rs.getString("car_number"), rs.getInt("driver_order"),
                        join(rs.getString("first_name"), rs.getString("last_name")), rs.getString("rating"),
                        rs.getObject("driver_id", Long.class), rs.getString("class_name")))
                .list();
        return new DriveTimeResponse(session, eventId, rules, DriveTime.compute(stints, drivers, rules, clock(session)));
    }

    /**
     * "Now" for an open stint. The wall clock while this session is live;
     * otherwise — a replay, a finished session — the newest time the feed
     * itself reported, so an old session's open stints do not grow forever.
     */
    long clock(long session) {
        long latest = latestFeedTime(session);
        long wall = System.currentTimeMillis();
        Long current = live.analysisSessionDbId();
        LiveTimingService.Session feed = live.status().session();
        boolean running = current != null && current == session && (feed == null || !feed.finished());
        return running && wall - latest < LIVE_WINDOW_MS && wall >= latest ? wall : latest;
    }

    /** The newest time the feed reported for a session: the last lap's end or stint's start/finish. 0 = none. */
    private long latestFeedTime(long session) {
        return db.sql("""
                SELECT COALESCE(GREATEST(
                    (SELECT max(start_time_ms + COALESCE(lap_time_ms, 0)) FROM live_lap WHERE session_db_id = :s),
                    (SELECT max(GREATEST(start_time_ms, COALESCE(finish_time_ms, 0))) FROM live_stint WHERE session_db_id = :s)), 0)
                """)
                .param("s", session).query(Long.class).single();
    }

    /** The asked-for session, else the one being fed, else the bound event's latest. */
    private long session(Long asked) {
        if (asked != null) {
            if (db.sql("SELECT 1 FROM live_session WHERE session_db_id = :s").param("s", asked)
                    .query(Integer.class).optional().isEmpty()) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such live session");
            }
            return asked;
        }
        Long current = live.analysisSessionDbId();
        if (current != null) {
            return current;
        }
        Long eventId = live.status().eventId();
        return (eventId == null ? java.util.Optional.<Long>empty() : db.sql("""
                SELECT session_db_id FROM live_session WHERE event_id = :e
                ORDER BY session_date_ms DESC NULLS LAST, session_db_id DESC LIMIT 1
                """).param("e", eventId).query(Long.class).optional())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No live session recorded yet"));
    }

    // ---- helpers -----------------------------------------------------------------------

    private static Integer integer(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, Integer.class);
    }

    private static List<Integer> ints(Array array) throws SQLException {
        return array == null ? null : Arrays.asList((Integer[]) array.getArray());
    }

    private static List<String> texts(Array array) throws SQLException {
        return array == null ? null : Arrays.asList((String[]) array.getArray());
    }

    private static String join(String first, String last) {
        String name = ((first == null ? "" : first) + " " + (last == null ? "" : last)).trim();
        return name.isEmpty() ? null : name;
    }

    private static String initial(String license) {
        return license == null || license.isBlank() ? null : license.trim().substring(0, 1).toUpperCase();
    }

    private static String text(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.path(field).asText() : null;
    }
}
