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
     * do, so it costs the ETag nothing. speedUnit is "mph" or "km/h", from the
     * feed's unitOfMeasure (US or METRIC), for topSpeed. eventId is the
     * bound event; filedEventId the event the session on track is filed
     * under, which is where teams, class colours and driver names come from.
     * Filed nowhere, the tower shows the feed's own. raceControl is race
     * control's screen now and its newest message, for the strip above the
     * tower; null when the feed has sent no race control channel.
     */
    public record Tower(State state, Long eventId, String eventName, LiveTimingService.Session session,
                        Long sessionDbId, Long feedClockMs, List<TowerClass> classes, int matched, int total,
                        String speedUnit, Long filedEventId, String filedEventName,
                        LiveRaceControl.Now raceControl) {
    }

    /** A session's race control log, newest first. */
    public record RaceControlLog(long sessionDbId, List<LiveRaceControl.Message> messages) {
    }

    /**
     * color is the series' class_style colour (#rrggbb), or null when the
     * class has none. bestSectors is the class's fastest time in each sector
     * and its holder, idealMs their sum (empty / null without participant
     * details).
     */
    public record TowerClass(String className, String feedClass, String color, List<TowerCar> cars,
                             List<LiveParticipantDetails.ClassSector> bestSectors, Long idealMs) {
    }

    /**
     * energyPct is IMSA telemetry's energy remaining (null when off, unseen or
     * older than the stale limit); energyLapsLeft projects it over this
     * stint's average use per lap (null until the stint has two lap samples).
     * laps is laps completed: the last lap from analysis, else the standings'
     * lapNumber in a race only — in practice and qualifying that is the number
     * of the car's best lap (Road Atlanta practice, 2026-09-30).
     * pitStops counts Al Kamel's PIT stints, as the Pits view does (null with
     * no session recorded); lastPitMs is the pit-lane time of the newest
     * finished one; with no session recorded, participant details' own count.
     * From participant details (null without them): trackStatus BOX /
     * OUT_LAP / TRACK / STOPPED, which also marks a car in the pit when no
     * PIT stint says so (a red flag's pit-lane stints arrive only once
     * closed); currentSector; sectors as they are run; the car's best sectors
     * and their sum, idealMs.
     * topSpeed is the car's best speed trap of the session, from the laps
     * recorded (in the tower's speedUnit).
     * startPosition is the car's place in its class on the starting grid, in
     * a race only (the feed's grid is overall; ranked here within the class
     * as the tower has it), for places gained. Null off the grid.
     * overall* are the car's place and gaps across every class, from the
     * feed's overall standings (null until that channel has arrived).
     * checkered: the car has taken the chequered flag — participant details'
     * hasSeenCheckered, else, once the session is finished, a crossing of the
     * line at or after the flag (see {@link #pastTheFlag}).
     * bestLapDriver is the full name of the driver who set bestLapMs.
     */
    public record TowerCar(int position, String carNumber, Long entryId, String teamName, String vehicle,
                           String manufacturer, String status, Integer laps,
                           Long gapToLeaderMs, Integer gapToLeaderLaps, Long intervalMs, Integer intervalLaps,
                           Integer driverOrder, String driverName, String driverShortName, String driverRating,
                           Integer lastLap, Integer lastLapMs, Integer bestLap, Integer bestLapMs,
                           boolean inPit, Long stintStartMs, Integer stintLaps, Double energyPct,
                           Double energyLapsLeft, Integer pitStops, Long lastPitMs,
                           String trackStatus, Integer currentSector, List<LiveParticipantDetails.SectorTime> sectors,
                           List<Integer> bestSectorMs, Long idealMs, Integer startPosition, Double topSpeed,
                           Integer overallPosition, Long overallGapMs, Integer overallGapLaps,
                           Long overallIntervalMs, Integer overallIntervalLaps, boolean checkered,
                           String bestLapDriver) {
    }

    /** A car's row in the feed's overall standings. Gaps are one or the other: laps when lapped. */
    record Overall(int position, Long gapMs, Integer gapLaps, Long intervalMs, Integer intervalLaps) {
    }

    /** A driver as the tower names them: ours when resolved, else the feed's. */
    private record Named(String name, String shortName, String lastName, String rating) {
    }

    public record SessionSummary(long sessionDbId, Long eventId, String name, String type, Long dateMs,
                                 int laps, int cars, boolean current) {
    }

    public record LapRow(int lap, Integer driverOrder, Integer driverLap, Integer position, Long startTimeMs,
                         Integer lapTimeMs, List<Integer> sectorMs, List<String> sectorFlags, Boolean valid,
                         Boolean longLap, Boolean shortLap, Integer trackLimits, Float topSpeed,
                         Long pitInMs, Long pitOutMs, Float energyPct, Float energyUsedPct) {

        LapRow withEnergy(Float pct, Float used) {
            return new LapRow(lap, driverOrder, driverLap, position, startTimeMs, lapTimeMs, sectorMs, sectorFlags,
                    valid, longLap, shortLap, trackLimits, topSpeed, pitInMs, pitOutMs, pct, used);
        }
    }

    public record StintRow(long startTimeMs, String type, String pitType, Integer driverOrder, Integer openLap,
                           Integer closeLap, Long finishTimeMs, Long driverAccumSessionTrackMs,
                           Long driverAccumSessionMs, Long driverAccumTrackMs, Long driverAccumMs,
                           Float avgEnergyPerLapPct) {
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
    /**
     * Which driver set a lap or a sector time, once found in live_lap: a
     * recorded lap's driver does not change, and the tower asks every poll.
     * Keyed by session, so a new session never reads an old one's.
     */
    private final Map<String, Integer> setBy = new java.util.concurrent.ConcurrentHashMap<>();

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
        Map<String, String> colors = order.filedEventId() == null ? Map.of() : classColors(order.filedEventId());
        JsonNode details = live.state(AlKamelV2Properties.PARTICIPANT_DETAILS_CHANNEL);
        Map<String, Integer> grid = gridPositions(live.state("timing.session.startingGrid"));
        Map<String, Double> topSpeeds = session == null ? Map.of() : topSpeeds(session);
        int sectorCount = LiveParticipantDetails.sectorCount(details);
        boolean race = order.session() != null && "RACE".equalsIgnoreCase(order.session().type());
        Map<String, Overall> overall = overallOrder(live.state(AlKamelV2Properties.OVERALL_STANDINGS_CHANNEL));
        Map<String, Long> crossings = session != null && order.session() != null && order.session().finished()
                ? lastCrossings(session) : Map.of();
        java.util.Set<String> pastFlag = pastTheFlag(race, overall, crossings,
                order.session() == null ? null : order.session().clock());
        if (setBy.size() > 20_000) {
            setBy.clear();
        }
        Map<String, LiveAnalysis.PitCar> pits = session == null ? Map.of()
                : LiveAnalysis.pitStops(stints(session), Map.of()).stream()
                        .collect(Collectors.toMap(LiveAnalysis.PitCar::carNumber, Function.identity()));

        List<TowerClass> classes = new ArrayList<>();
        for (var cls : order.classification().classes()) {
            List<TowerCar> cars = new ArrayList<>();
            Map<String, LiveParticipantDetails.Car> detailByCar = new HashMap<>();
            Map<String, Integer> driving = new HashMap<>();
            Map<String, Integer> classStart = race ? classStart(cls.cars().stream().map(c -> c.carNumber()).toList(), grid) : Map.of();
            for (var car : cls.cars()) {
                CarSummary s = summaries.get(car.carNumber());
                LiveParticipantDetails.Car d = details == null ? null
                        : LiveParticipantDetails.car(details.get(car.carNumber()), sectorCount);
                if (d != null) {
                    detailByCar.put(car.carNumber(), d);
                }
                JsonNode feedCar = feedEntries == null ? null : feedEntries.get(car.carNumber());
                Integer order2 = feedCar != null && feedCar.hasNonNull("currentDriver")
                        ? feedCar.path("currentDriver").asInt() : s == null ? null : s.stintDriverOrder();
                if (order2 != null) {
                    driving.put(car.carNumber(), order2);
                }
                Named now = named(car.carNumber(), order2, resolved, feedCar);
                int[] best = bestFromDb.get(car.carNumber());
                Integer bestLap = best != null ? Integer.valueOf(best[0]) : s == null ? null : s.bestLap();
                // The lap's own driver, so it names whoever set the time shown; participant details' when not recorded.
                Integer bestBy = session == null || bestLap == null ? null : lapDriver(session, car.carNumber(), bestLap);
                if (bestBy == null && d != null) {
                    bestBy = d.bestLapDriver();
                }
                Named bestDriver = named(car.carNumber(), bestBy, resolved, feedCar);
                Overall o = overall.get(car.carNumber());
                boolean checkered = d != null && d.checkered() != null ? d.checkered() : pastFlag.contains(car.carNumber());
                LiveTelemetry.CarEnergy energy = live.energy(car.carNumber(), cls.feedClass(), s == null ? null : s.stintOpenLap());
                LiveAnalysis.PitCar pit = pits.get(car.carNumber());
                Long lastPitMs = pit == null ? null : pit.stops().stream().map(LiveAnalysis.PitStop::durationMs)
                        .filter(java.util.Objects::nonNull).reduce((a, b) -> b).orElse(null);
                cars.add(new TowerCar(car.position(), car.carNumber(), car.entryId(), car.teamName(), car.vehicle(),
                        car.manufacturer(), car.status(),
                        s != null && s.lastLap() != null ? Integer.valueOf(Math.max(s.lastLap(), race && car.laps() != null ? car.laps() : 0))
                                : race ? car.laps() : null,
                        car.gapToLeaderMs(), car.gapToLeaderLaps(), car.intervalMs(), car.intervalLaps(),
                        order2, now.name(), now.shortName(), now.rating(),
                        s == null ? null : s.lastLap(), s == null ? null : s.lastLapMs(),
                        bestLap,
                        best != null ? Integer.valueOf(best[1]) : s == null ? null : s.bestLapMs(),
                        (s != null && "PIT".equalsIgnoreCase(s.stintType())) || (d != null && d.inBox()),
                        s == null ? null : s.stintStartMs(), s == null ? null : s.lapsInStint(),
                        energy == null ? null : energy.energyPct(), energy == null ? null : energy.lapsLeft(),
                        session == null ? (d == null ? null : d.pitStops()) : pit == null ? 0 : pit.stops().size(), lastPitMs,
                        d == null ? null : d.trackStatus(), d == null ? null : d.currentSector(),
                        d == null ? null : d.sectors(), d == null ? null : d.bestSectorMs(),
                        d == null ? null : d.idealMs(), classStart.get(car.carNumber()), topSpeeds.get(car.carNumber()),
                        o == null ? null : o.position(), o == null ? null : o.gapMs(), o == null ? null : o.gapLaps(),
                        o == null ? null : o.intervalMs(), o == null ? null : o.intervalLaps(), checkered,
                        bestDriver.name()));
            }
            List<LiveParticipantDetails.ClassSector> bests = detailByCar.isEmpty() ? List.of()
                    : LiveParticipantDetails.classBests(cars.stream().map(TowerCar::carNumber).toList(), detailByCar::get, sectorCount)
                            .stream().map(b -> b.withDriver(sectorDriver(session, b, detailByCar, driving, resolved, feedEntries)))
                            .toList();
            classes.add(new TowerClass(cls.className(), cls.feedClass(),
                    colors.get(cls.className().trim().toLowerCase()), cars, bests, LiveParticipantDetails.idealLap(bests)));
        }
        JsonNode info = live.state("timing.session.info");
        String unit = info == null || !info.hasNonNull("unitOfMeasure") ? null
                : "US".equalsIgnoreCase(info.path("unitOfMeasure").asText()) ? "mph" : "km/h";
        return new Tower(order.state(), order.eventId(), order.eventName(), order.session(), session,
                session == null ? null : latestFeedTime(session), classes,
                order.classification().matched(), order.classification().total(), unit,
                order.filedEventId(), order.filedEventName(),
                LiveRaceControl.now(live.state("raceControl.currentMessages"), live.state("raceControl.messages")));
    }

    // ---- race control ------------------------------------------------------------------

    public RaceControlLog raceControl(Long sessionParam) {
        long session = session(sessionParam);
        List<LiveRaceControl.Message> messages = new ArrayList<>(db.sql("""
                SELECT message_key, day_time_ms, text, group_text, line, foreground_color, background_color,
                       blink, is_null
                FROM live_race_control WHERE session_db_id = :s
                """)
                .param("s", session)
                .query((rs, i) -> java.util.Optional.ofNullable(LiveRaceControl.row(rs.getString("message_key"),
                        rs.getObject("day_time_ms", Long.class), rs.getString("text"), rs.getString("group_text"),
                        integer(rs, "line"), rs.getString("foreground_color"), rs.getString("background_color"),
                        rs.getObject("blink", Boolean.class), rs.getObject("is_null", Boolean.class))))
                .list().stream().flatMap(java.util.Optional::stream).toList());
        messages.sort(LiveRaceControl.NEWEST_FIRST);
        return new RaceControlLog(session, messages);
    }

    /** Each car's best speed trap of the session. A speed is a speed on an invalid lap too. */
    private Map<String, Double> topSpeeds(long session) {
        Map<String, Double> out = new HashMap<>();
        db.sql("""
                SELECT car_number, max(top_speed) AS best FROM live_lap
                WHERE session_db_id = :s AND top_speed > 0 GROUP BY car_number
                """)
                .param("s", session)
                .query((rs, i) -> out.put(rs.getString("car_number"), rs.getDouble("best")))
                .list();
        return out;
    }

    /** The feed's overall standings: car number → its place and gaps across every class. */
    static Map<String, Overall> overallOrder(JsonNode standings) {
        Map<String, Overall> out = new HashMap<>();
        if (standings == null) {
            return out;
        }
        for (JsonNode row : standings) {
            String car = row.path("participant").asText("");
            int position = row.path("position").asInt(0);
            if (car.isBlank() || position <= 0) {
                continue;
            }
            out.put(car, new Overall(position,
                    LiveClassification.gap(row, "gapFirstTime"), laps(row, "gapFirstLaps"),
                    LiveClassification.gap(row, "gapPreviousTime"), laps(row, "gapPreviousLaps")));
        }
        return out;
    }

    // The spec's example sends laps behind as a negative number.
    private static Integer laps(JsonNode row, String field) {
        int n = row.path(field).asInt(0);
        return n == 0 ? null : Math.abs(n);
    }

    /**
     * The cars that have crossed the line under the chequered flag, for when
     * the feed does not say (no participant details). Only called with
     * crossings once the session is finished, which the spec defines as the
     * flag being shown. In a race the flag is first shown to the overall
     * leader, so it fell at the leader's last crossing; otherwise it falls
     * when the clock runs out. Every car that crossed at or after it has seen
     * it.
     */
    static java.util.Set<String> pastTheFlag(boolean race, Map<String, Overall> overall, Map<String, Long> crossings,
                                             LiveTimingService.Clock clock) {
        if (crossings.isEmpty()) {
            return java.util.Set.of();
        }
        Long flag = null;
        if (race) {
            String leader = overall.entrySet().stream().filter(e -> e.getValue().position() == 1)
                    .map(Map.Entry::getKey).findFirst().orElse(null);
            flag = leader == null ? null : crossings.get(leader);
        } else if (clock != null && clock.startMs() != null && clock.finalMs() != null) {
            flag = clock.startMs() + clock.finalMs() + clock.stoppedMs();
        }
        if (flag == null) {
            return java.util.Set.of();
        }
        long at = flag;
        return crossings.entrySet().stream().filter(e -> e.getValue() >= at).map(Map.Entry::getKey)
                .collect(Collectors.toSet());
    }

    /** Each car's newest crossing of the line: its last recorded lap's end. */
    private Map<String, Long> lastCrossings(long session) {
        Map<String, Long> out = new HashMap<>();
        db.sql("""
                SELECT car_number, max(start_time_ms + lap_time_ms) AS crossed FROM live_lap
                WHERE session_db_id = :s AND lap_time_ms > 0 AND start_time_ms > 0 GROUP BY car_number
                """)
                .param("s", session)
                .query((rs, i) -> out.put(rs.getString("car_number"), rs.getLong("crossed")))
                .list();
        return out;
    }

    /** The driver order of a recorded lap, or null while it is not recorded. */
    private Integer lapDriver(long session, String car, int lap) {
        String key = session + "|" + car + "|L" + lap;
        Integer known = setBy.get(key);
        if (known != null) {
            return known;
        }
        Integer order = db.sql("""
                SELECT driver_order FROM live_lap WHERE session_db_id = :s AND car_number = :car AND lap_number = :lap
                """)
                .param("s", session).param("car", car).param("lap", lap)
                .query((rs, i) -> integer(rs, "driver_order")).optional().orElse(null);
        if (order != null) {
            setBy.put(key, order);
        }
        return order;
    }

    /**
     * The surname of the driver who set a class's best time in a sector: the
     * first recorded lap of the holder with that time there. A lap is
     * recorded only once complete, so a best set on the lap the car is on now
     * is not there yet — that one is the current driver's, while the car's
     * newest time in that sector is still the best.
     */
    private String sectorDriver(Long session, LiveParticipantDetails.ClassSector best,
                                Map<String, LiveParticipantDetails.Car> details, Map<String, Integer> driving,
                                Map<String, DriverRow> resolved, JsonNode feedEntries) {
        if (best.ms() == null || best.car() == null) {
            return null;
        }
        LiveParticipantDetails.Car car = details.get(best.car());
        int index = car == null ? -1 : car.bestSectorMs().indexOf(best.ms());
        if (index < 0) {
            return null;
        }
        Integer order = null;
        if (session != null) {
            String key = session + "|" + best.car() + "|S" + (index + 1) + "|" + best.ms();
            order = setBy.get(key);
            if (order == null) {
                order = db.sql("""
                        SELECT driver_order FROM live_lap
                        WHERE session_db_id = :s AND car_number = :car AND sector_ms[:n] = :ms AND driver_order IS NOT NULL
                        ORDER BY lap_number LIMIT 1
                        """)
                        .param("s", session).param("car", best.car()).param("n", index + 1).param("ms", best.ms())
                        .query((rs, i) -> integer(rs, "driver_order")).optional().orElse(null);
                if (order != null) {
                    setBy.put(key, order);
                }
            }
        }
        if (order == null) {
            LiveParticipantDetails.SectorTime newest = car.sectors().get(index);
            if (newest != null && best.ms().equals(newest.ms())) {
                order = driving.get(best.car());
            }
        }
        JsonNode feedCar = feedEntries == null ? null : feedEntries.get(best.car());
        return order == null ? null : named(best.car(), order, resolved, feedCar).lastName();
    }

    /** A car's driver by order: our resolved row first, else the feed's entry. All null for no order. */
    private static Named named(String car, Integer order, Map<String, DriverRow> resolved, JsonNode feedCar) {
        if (order == null) {
            return new Named(null, null, null, null);
        }
        DriverRow driver = resolved.get(car + "#" + order);
        if (driver != null) {
            return new Named(join(driver.firstName(), driver.lastName()), driver.shortName(),
                    driver.lastName() != null ? driver.lastName() : driver.shortName(), driver.rating());
        }
        JsonNode feedDriver = feedCar == null ? null : feedCar.path("drivers").get(String.valueOf(order));
        if (feedDriver == null) {
            return new Named(null, null, null, null);
        }
        String last = text(feedDriver, "lastName");
        return new Named(join(text(feedDriver, "firstName"), last), text(feedDriver, "shortName"),
                last != null ? last : text(feedDriver, "shortName"), initial(text(feedDriver, "license")));
    }

    /** The feed's starting grid: car number → overall grid position. */
    static Map<String, Integer> gridPositions(JsonNode grid) {
        Map<String, Integer> out = new HashMap<>();
        if (grid == null) {
            return out;
        }
        for (JsonNode p : grid.path("positions")) {
            int position = p.path("position").asInt(0);
            if (p.hasNonNull("participant") && position > 0) {
                out.put(p.path("participant").asText(), position);
            }
        }
        return out;
    }

    /** Each car's place in its class at the start: its overall grid slot ranked among the class's cars on the grid. */
    static Map<String, Integer> classStart(List<String> classCars, Map<String, Integer> grid) {
        List<String> onGrid = classCars.stream().filter(grid::containsKey)
                .sorted(java.util.Comparator.comparing(grid::get)).toList();
        Map<String, Integer> out = new HashMap<>();
        for (int i = 0; i < onGrid.size(); i++) {
            out.put(onGrid.get(i), i + 1);
        }
        return out;
    }

    /** Every stint of a session, for pit stops. */
    List<LiveAnalysis.Stint> stints(long session) {
        return db.sql("""
                SELECT car_number, start_time_ms, type, pit_type, driver_order, open_lap_number, close_lap_number,
                       finish_time_ms
                FROM live_stint WHERE session_db_id = :s
                """)
                .param("s", session)
                .query((rs, i) -> new LiveAnalysis.Stint(rs.getString("car_number"), rs.getLong("start_time_ms"),
                        rs.getString("type"), rs.getString("pit_type"), integer(rs, "driver_order"),
                        integer(rs, "open_lap_number"), integer(rs, "close_lap_number"),
                        rs.getObject("finish_time_ms", Long.class)))
                .list();
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
        return sessions("s.event_id = :e", eventId);
    }

    /** Every session of one series weekend (Al Kamel's feed event), filed or not. */
    public List<SessionSummary> sessionsOfFeedEvent(long feedEventDbId) {
        return sessions("s.feed_event_db_id = :e", feedEventDbId);
    }

    // Newest first by date: Al Kamel's session ids are not in time order.
    private List<SessionSummary> sessions(String where, long key) {
        Long current = live.analysisSessionDbId();
        return db.sql("""
                SELECT s.session_db_id, s.event_id, s.name, s.type, s.session_date_ms,
                       (SELECT count(*) FROM live_lap l WHERE l.session_db_id = s.session_db_id) AS laps,
                       (SELECT count(DISTINCT car_number) FROM live_lap l WHERE l.session_db_id = s.session_db_id) AS cars
                FROM live_session s WHERE %s
                ORDER BY s.session_date_ms DESC NULLS LAST, s.first_seen_at DESC
                """.formatted(where))
                .param("e", key)
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
                        rs.getObject("pit_in_time_ms", Long.class), rs.getObject("pit_out_time_ms", Long.class),
                        null, null))
                .list();
        Map<Integer, Float> energy = energyByLap(session, carNumber);
        if (!energy.isEmpty()) {
            laps = laps.stream().map(l -> l.withEnergy(energy.get(l.lap()), used(energy, l.lap()))).toList();
        }
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
                        rs.getObject("driver_accum_ms", Long.class),
                        averageUse(energy, integer(rs, "open_lap_number"), integer(rs, "close_lap_number"))))
                .list();
        List<DriverRow> drivers = drivers(session, carNumber).stream().map(Map.Entry::getValue).toList();
        if (laps.isEmpty() && stints.isEmpty() && drivers.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No laps for car " + carNumber + " in this session");
        }
        return new CarDetail(session, carNumber, drivers, laps, stints);
    }

    /**
     * IMSA telemetry's energy at the line, by lap, for an Al Kamel car. The
     * telemetry's car number exactly first (#04 is not #4), then without
     * leading zeros only when that names one car.
     */
    private Map<Integer, Float> energyByLap(long session, String carNumber) {
        Map<String, Map<Integer, Float>> byCar = new HashMap<>();
        db.sql("""
                SELECT car_number, lap_number, energy_pct FROM live_energy_lap
                WHERE session_db_id = :s AND ltrim(car_number, '0') = ltrim(:car, '0')
                """)
                .param("s", session).param("car", carNumber)
                .query((rs, i) -> byCar.computeIfAbsent(rs.getString("car_number"), k -> new HashMap<>())
                        .put(rs.getInt("lap_number"), rs.getFloat("energy_pct")))
                .list();
        if (byCar.containsKey(carNumber)) {
            return byCar.get(carNumber);
        }
        return byCar.size() == 1 ? byCar.values().iterator().next() : Map.of();
    }

    /** Energy used on a lap: the drop from the previous lap's reading. A rise is a refill, not use. */
    private static Float used(Map<Integer, Float> energy, int lap) {
        Float before = energy.get(lap - 1);
        Float after = energy.get(lap);
        return before == null || after == null || after > before ? null : before - after;
    }

    /** Average use per lap over a stint's laps, where both ends of a lap were seen. */
    private static Float averageUse(Map<Integer, Float> energy, Integer openLap, Integer closeLap) {
        if (energy.isEmpty() || openLap == null) {
            return null;
        }
        int last = closeLap != null ? closeLap : energy.keySet().stream().max(Integer::compare).orElse(openLap);
        float total = 0;
        int laps = 0;
        // A stint's first lap counts: a refill before it shows as a rise and is left out anyway.
        for (int lap = openLap; lap <= last; lap++) {
            Float u = used(energy, lap);
            if (u != null) {
                total += u;
                laps++;
            }
        }
        return laps == 0 ? null : total / laps;
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
    long session(Long asked) {
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
