package com.pitpass.live;

import com.pitpass.live.LiveAnalysis.GapCar;
import com.pitpass.live.LiveAnalysis.Lap;
import com.pitpass.live.LiveAnalysis.PitCar;
import com.pitpass.live.LiveAnalysis.SectorBests;
import com.pitpass.live.LiveAnalysis.Stint;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.sql.Array;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Loads a session's laps and stints for {@link LiveAnalysis}, class by class.
 *
 * A car's class is the one it is running in on the tower while the session
 * is the one being fed; otherwise the class of the entry its drivers were
 * matched to. A car matched to no entry is listed under "Not entered"
 * rather than dropped. Classes run in the series' sheet order.
 */
@Component
public class LiveAnalysisService {

    static final String NOT_ENTERED = "Not entered";

    public record CarInfo(String carNumber, String teamName, String className) {
    }

    public record GapClass(String className, String color, List<CarInfo> cars, List<GapCar> gaps) {
    }

    public record GapsResponse(long sessionDbId, List<GapClass> classes) {
    }

    public record SectorClass(String className, String color, List<CarInfo> cars, SectorBests bests) {
    }

    public record SectorsResponse(long sessionDbId, List<SectorClass> classes) {
    }

    public record PitClass(String className, String color, List<CarInfo> cars, List<PitCar> pits) {
    }

    /** drivers: each car's driver orders to surnames, for who got in and out. */
    public record PitsResponse(long sessionDbId, Map<String, Map<Integer, String>> drivers, List<PitClass> classes) {
    }

    /**
     * One car's energy over the session. energyPct is now (the session being
     * fed only, null when stale). green is the last 10 green laps' average,
     * greenShort the last 5; greenLapsLeft energyPct over green. caution is
     * the car's own caution laps when it has 3, else its class's pooled
     * (cautionSource CAR or CLASS). lapsSinceGreen counts the car's laps after
     * the newest green lap, so a long caution or a run of pit laps shows how
     * old the green figure is. laps counts each EnergyModel.Kind.
     */
    public record EnergyCar(String carNumber, Double energyPct, Double greenLapsLeft, EnergyModel.Average green,
                            EnergyModel.Average greenShort, EnergyModel.Average caution,
                            EnergyModel.Source cautionSource, Integer lastLap, Integer lapsSinceGreen,
                            Map<EnergyModel.Kind, Integer> laps, Scenario finish) {

        EnergyCar withFinish(Scenario s) {
            return new EnergyCar(carNumber, energyPct, greenLapsLeft, green, greenShort, caution, cautionSource, lastLap,
                    lapsSinceGreen, laps, s);
        }
    }

    /**
     * What the Energy view's finish scenarios are asked for: an energy reserve
     * to keep, whether to start from the energy now or from a stop now (the
     * session's observed refill level), and a caution figure for cars whose
     * own and class's are not known yet.
     */
    public record EnergyInputs(double reservePct, boolean fromStop, Double cautionUsePct, Double cautionLapMs) {
        public static final EnergyInputs NONE = new EnergyInputs(0, false, null, null);
    }

    /**
     * One car against the flag (EnergyFinish.Result), with the energy it
     * started from and whose caution figures it used: CAR, CLASS or MANUAL.
     */
    public record Scenario(double startPct, EnergyFinish.Result result, String cautionSource) {
    }

    /**
     * The flag the scenarios run to, in a race being fed only. type TIME: the
     * clock runs out in clockLeftMs and the overall leader (leader) is
     * projected to take the flag in flagInMs. type LAPS: the leader has
     * leaderLapsLeft. refillPct is where refills landed this session
     * (OBSERVED, the median) or 100 (ASSUMED) when none has been seen.
     */
    public record Finish(String type, Long clockLeftMs, Long flagInMs, Integer leaderLapsLeft, String leader,
                         EnergyInputs inputs, double refillPct, String refillSource) {
    }

    /** caution: the class's caution laps pooled, every car's. */
    public record EnergyClass(String className, String color, List<CarInfo> cars, EnergyModel.Average caution,
                              List<EnergyCar> energy) {
    }

    /** live: the session is the one being fed, so energyPct is now. */
    public record EnergyResponse(long sessionDbId, boolean live, Finish finish, List<EnergyClass> classes) {
    }

    private final JdbcClient db;
    private final LiveTimingService live;
    private final LiveClassificationService classification;
    private final LiveTimingPageService page;

    public LiveAnalysisService(JdbcClient db, LiveTimingService live, LiveClassificationService classification,
                               LiveTimingPageService page) {
        this.db = db;
        this.live = live;
        this.classification = classification;
        this.page = page;
    }

    public GapsResponse gaps(Long sessionParam) {
        long session = page.session(sessionParam);
        List<Lap> laps = laps(session, false);
        List<GapClass> out = new ArrayList<>();
        classes(session, laps.stream().map(Lap::car).toList()).forEach((cls, cars) ->
                out.add(new GapClass(cls.name, cls.color, cars, LiveAnalysis.gaps(of(laps, cars, Lap::car)))));
        return new GapsResponse(session, out);
    }

    public SectorsResponse sectors(Long sessionParam) {
        long session = page.session(sessionParam);
        List<Lap> laps = laps(session, true);
        List<SectorClass> out = new ArrayList<>();
        classes(session, laps.stream().map(Lap::car).toList()).forEach((cls, cars) ->
                out.add(new SectorClass(cls.name, cls.color, cars, LiveAnalysis.sectorBests(of(laps, cars, Lap::car)))));
        return new SectorsResponse(session, out);
    }

    public PitsResponse pits(Long sessionParam) {
        long session = page.session(sessionParam);
        List<Stint> stints = page.stints(session);
        Map<String, Integer> lastLap = new HashMap<>();
        db.sql("SELECT car_number, max(lap_number) AS last FROM live_lap WHERE session_db_id = :s AND lap_time_ms > 0 GROUP BY car_number")
                .param("s", session)
                .query((rs, i) -> lastLap.put(rs.getString("car_number"), rs.getInt("last")))
                .list();
        Map<String, Map<Integer, String>> drivers = new HashMap<>();
        db.sql("""
                SELECT car_number, driver_order, COALESCE(last_name, short_name) AS name
                FROM live_driver WHERE session_db_id = :s
                """)
                .param("s", session)
                .query((rs, i) -> drivers.computeIfAbsent(rs.getString("car_number"), k -> new HashMap<>())
                        .put(rs.getInt("driver_order"), rs.getString("name")))
                .list();
        List<String> seen = new ArrayList<>(stints.stream().map(Stint::car).toList());
        seen.addAll(lastLap.keySet());
        List<PitClass> out = new ArrayList<>();
        classes(session, seen).forEach((cls, cars) ->
                out.add(new PitClass(cls.name, cls.color, cars, LiveAnalysis.pitStops(of(stints, cars, Stint::car), lastLap))));
        return new PitsResponse(session, drivers, out);
    }

    /**
     * Every car's energy use, class by class, fewest green laps left first: who
     * has to stop soonest. Cars without telemetry are left out.
     */
    public EnergyResponse energy(Long sessionParam) {
        return energy(sessionParam, EnergyInputs.NONE);
    }

    public EnergyResponse energy(Long sessionParam, EnergyInputs inputs) {
        long session = page.session(sessionParam);
        Map<String, EnergyModel.Car> models = page.energyModels(session);
        Long current = live.analysisSessionDbId();
        boolean isLive = current != null && current == session;
        List<EnergyClass> out = new ArrayList<>();
        classes(session, List.copyOf(models.keySet())).forEach((cls, cars) -> {
            List<EnergyModel.Car> classModels = cars.stream().map(c -> models.get(c.carNumber()))
                    .filter(java.util.Objects::nonNull).toList();
            if (classModels.isEmpty()) {
                return;
            }
            List<EnergyCar> rows = new ArrayList<>();
            for (CarInfo car : cars) {
                EnergyModel.Car m = models.get(car.carNumber());
                if (m != null) {
                    rows.add(energyCar(car.carNumber(), m, classModels, isLive ? live.energyNow(car.carNumber(), null) : null));
                }
            }
            rows.sort(java.util.Comparator.comparing(EnergyCar::greenLapsLeft,
                    java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder())));
            out.add(new EnergyClass(cls.name, cls.color, cars, EnergyModel.pooled(classModels), rows));
        });
        Crossings crossed = isLive ? crossings(session) : null;
        Finish finish = crossed == null ? null : finish(classification.current().session(), crossed, models, inputs);
        if (finish != null) {
            for (int i = 0; i < out.size(); i++) {
                EnergyClass cls = out.get(i);
                List<EnergyCar> rows = cls.energy().stream()
                        .map(e -> e.withFinish(scenario(e, models.get(e.carNumber()), finish, crossed)))
                        .toList();
                out.set(i, new EnergyClass(cls.className(), cls.color(), cls.cars(), cls.caution(), rows));
            }
        }
        return new EnergyResponse(session, isLive, finish, out);
    }

    /** Each car's last crossing and laps completed, and the newest feed time: "now" for the finish. */
    record Crossings(Map<String, Long> at, Map<String, Integer> laps, long nowMs) {
    }

    private Crossings crossings(long session) {
        Map<String, Integer> laps = new HashMap<>();
        db.sql("SELECT car_number, max(lap_number) AS laps FROM live_lap WHERE session_db_id = :s AND lap_time_ms > 0 GROUP BY car_number")
                .param("s", session)
                .query((rs, i) -> laps.put(rs.getString("car_number"), rs.getInt("laps")))
                .list();
        return new Crossings(page.lastCrossings(session), laps, page.latestFeedTime(session));
    }

    /**
     * The flag to run to, in a race only: a timed race (BY_TIME) or a lap race
     * (BY_LAPS), while the clock runs. Null otherwise — practice, a red flag,
     * a finish type not modelled, or no lap times yet.
     */
    static Finish finish(LiveTimingService.Session now, Crossings c, Map<String, EnergyModel.Car> models,
                         EnergyInputs inputs) {
        if (now == null || !"RACE".equalsIgnoreCase(now.type()) || now.clock() == null || now.clock().stopMs() != null) {
            return null;
        }
        LiveTimingService.Clock clock = now.clock();
        Map<String, Integer> laps = c.laps();
        Map<String, Long> at = c.at();
        long feedNow = c.nowMs();
        // The overall leader: the most laps, and of those the first across the line.
        String leader = laps.entrySet().stream()
                .filter(e -> at.containsKey(e.getKey()))
                .max(java.util.Comparator.<Map.Entry<String, Integer>>comparingInt(Map.Entry::getValue)
                        .thenComparing(e -> -at.get(e.getKey())))
                .map(Map.Entry::getKey).orElse(null);
        if (leader == null) {
            return null;
        }
        double[] refill = refillLevel(models);
        String type = clock.finalType();
        if ("BY_TIME".equalsIgnoreCase(type) && clock.startMs() != null && clock.finalMs() != null) {
            Double leaderLap = greenLapMs(models.get(leader), models);
            if (leaderLap == null) {
                return null;
            }
            long clockEnd = clock.startMs() + clock.finalMs() + clock.stoppedMs();
            long flag = EnergyFinish.leaderFlagMs(clockEnd, at.get(leader), leaderLap);
            return new Finish("TIME", clockEnd - feedNow, flag - feedNow, null, leader, inputs, refill[0],
                    refill[1] > 0 ? "OBSERVED" : "ASSUMED");
        }
        if ("BY_LAPS".equalsIgnoreCase(type) && clock.finalLaps() != null) {
            return new Finish("LAPS", null, null, Math.max(0, clock.finalLaps() - laps.get(leader)), leader, inputs,
                    refill[0], refill[1] > 0 ? "OBSERVED" : "ASSUMED");
        }
        return null;
    }

    /** The car's green lap time; for a car without one, the median of every car's. */
    private static Double greenLapMs(EnergyModel.Car car, Map<String, EnergyModel.Car> models) {
        if (car != null && car.green() != null && car.green().lapTimeMs() != null) {
            return car.green().lapTimeMs();
        }
        List<Double> all = models.values().stream().filter(m -> m.green() != null && m.green().lapTimeMs() != null)
                .map(m -> m.green().lapTimeMs()).sorted().toList();
        return all.isEmpty() ? null : all.get(all.size() / 2);
    }

    /**
     * Where refills landed this session: the median energy after a rise of
     * more than 10 points between consecutive readings. {median, count}; 100
     * and 0 when none was seen.
     */
    static double[] refillLevel(Map<String, EnergyModel.Car> models) {
        List<Float> landed = new ArrayList<>();
        for (EnergyModel.Car m : models.values()) {
            Float before = null;
            for (EnergyModel.Classified l : m.laps()) {
                if (l.energyPct() != null) {
                    if (before != null && l.energyPct() - before > 10) {
                        landed.add(l.energyPct());
                    }
                    before = l.energyPct();
                }
            }
        }
        if (landed.isEmpty()) {
            return new double[]{100, 0};
        }
        landed.sort(null);
        return new double[]{landed.get(landed.size() / 2), landed.size()};
    }

    static Scenario scenario(EnergyCar e, EnergyModel.Car m, Finish finish, Crossings c) {
        Long last = c.at().get(e.carNumber());
        Double start = finish.inputs().fromStop() ? Double.valueOf(finish.refillPct()) : e.energyPct();
        if (m == null || m.green() == null || m.green().lapTimeMs() == null || last == null || start == null) {
            return null;
        }
        Double cautionUse = null;
        Double cautionLap = null;
        String source = null;
        if (e.caution() != null && e.caution().lapTimeMs() != null) {
            cautionUse = e.caution().perLapPct();
            cautionLap = e.caution().lapTimeMs();
            source = e.cautionSource() == null ? null : e.cautionSource().name();
        } else if (finish.inputs().cautionUsePct() != null && finish.inputs().cautionLapMs() != null) {
            cautionUse = finish.inputs().cautionUsePct();
            cautionLap = finish.inputs().cautionLapMs();
            source = "MANUAL";
        }
        Integer leaderLaps = c.laps().get(finish.leader());
        Integer carLaps = c.laps().get(e.carNumber());
        int behind = leaderLaps == null || carLaps == null ? 0 : Math.max(0, leaderLaps - carLaps);
        var car = new EnergyFinish.Car(start, m.green().perLapPct(), m.green().lapTimeMs(), cautionUse, cautionLap,
                last, behind);
        long feedNow = c.nowMs();
        EnergyFinish.Result r = "TIME".equals(finish.type())
                ? EnergyFinish.timed(car, feedNow, feedNow + finish.flagInMs(), finish.inputs().reservePct())
                : EnergyFinish.laps(car, feedNow, finish.leaderLapsLeft(), finish.inputs().reservePct());
        return new Scenario(start, r, source);
    }

    static EnergyCar energyCar(String car, EnergyModel.Car m, List<EnergyModel.Car> classModels, Double now) {
        LiveEnergy.Caution caution = LiveEnergy.caution(m, classModels);
        Integer lastLap = m.laps().isEmpty() ? null : m.laps().getLast().lap();
        Integer since = m.green() == null || lastLap == null ? null : lastLap - m.green().lastLap();
        Map<EnergyModel.Kind, Integer> counts = new java.util.EnumMap<>(EnergyModel.Kind.class);
        m.laps().forEach(l -> counts.merge(l.kind(), 1, Integer::sum));
        return new EnergyCar(car, now, EnergyModel.greenLapsLeft(now, m.green()), m.green(), m.greenShort(),
                caution.average(), caution.source(), lastLap, since, counts, null);
    }

    // ---- loading ------------------------------------------------------------------------

    private List<Lap> laps(long session, boolean withSectors) {
        return db.sql("""
                SELECT car_number, lap_number, driver_order, start_time_ms, lap_time_ms, is_valid,
                       pit_in_time_ms IS NOT NULL AS pit_in, %s AS sector_ms
                FROM live_lap WHERE session_db_id = :s
                """.formatted(withSectors ? "sector_ms" : "NULL::int[]"))
                .param("s", session)
                .query((rs, i) -> new Lap(rs.getString("car_number"), rs.getInt("lap_number"),
                        rs.getObject("driver_order", Integer.class), rs.getObject("start_time_ms", Long.class),
                        rs.getObject("lap_time_ms", Integer.class), ints(rs.getArray("sector_ms")),
                        rs.getObject("is_valid", Boolean.class), rs.getBoolean("pit_in")))
                .list();
    }

    private static <T> List<T> of(List<T> rows, List<CarInfo> cars, Function<T, String> car) {
        var numbers = cars.stream().map(CarInfo::carNumber).collect(java.util.stream.Collectors.toSet());
        return rows.stream().filter(r -> numbers.contains(car.apply(r))).toList();
    }

    private record ClassKey(String name, String color) {
    }

    /**
     * Every car seen in the session, grouped by class in display order. The
     * tower's class wins for the session being fed; else the matched entry's.
     */
    private Map<ClassKey, List<CarInfo>> classes(long session, List<String> seen) {
        Map<String, CarInfo> byCar = new LinkedHashMap<>();
        db.sql("""
                SELECT DISTINCT ON (d.car_number) d.car_number, en.team_name, en.class_name
                FROM live_driver d JOIN entry en ON en.id = d.entry_id
                WHERE d.session_db_id = :s
                ORDER BY d.car_number, d.driver_order
                """)
                .param("s", session)
                .query((rs, i) -> byCar.put(rs.getString("car_number"),
                        new CarInfo(rs.getString("car_number"), rs.getString("team_name"), rs.getString("class_name"))))
                .list();
        // Filed nowhere: no entries to name them, so the feed's own class and team (live_car).
        db.sql("""
                SELECT c.car_number, c.team, c.feed_class FROM live_car c JOIN live_session ls USING (session_db_id)
                WHERE c.session_db_id = :s AND ls.event_id IS NULL AND c.feed_class IS NOT NULL
                """)
                .param("s", session)
                .query((rs, i) -> byCar.putIfAbsent(rs.getString("car_number"),
                        new CarInfo(rs.getString("car_number"), rs.getString("team"), rs.getString("feed_class"))))
                .list();
        List<String> towerOrder = new ArrayList<>();
        Long current = live.analysisSessionDbId();
        if (current != null && current == session) {
            for (var cls : classification.current().classification().classes()) {
                towerOrder.add(cls.className());
                for (var car : cls.cars()) {
                    CarInfo known = byCar.get(car.carNumber());
                    byCar.put(car.carNumber(), new CarInfo(car.carNumber(),
                            car.teamName() != null ? car.teamName() : known == null ? null : known.teamName(),
                            cls.className()));
                }
            }
        }
        for (String car : seen) {
            byCar.putIfAbsent(car, new CarInfo(car, null, NOT_ENTERED));
        }

        Map<String, Integer> ordinal = new HashMap<>();
        Map<String, String> colors = new HashMap<>();
        db.sql("""
                SELECT cs.class_code, cs.ordinal, cs.color FROM class_style cs
                JOIN season se ON se.series_id = cs.series_id JOIN event ev ON ev.season_id = se.id
                JOIN live_session ls ON ls.event_id = ev.id
                WHERE ls.session_db_id = :s
                """)
                .param("s", session)
                .query((rs, i) -> {
                    String key = rs.getString("class_code").trim().toLowerCase();
                    ordinal.put(key, rs.getInt("ordinal"));
                    return colors.put(key, rs.getString("color"));
                })
                .list();

        List<String> names = byCar.values().stream().map(CarInfo::className).distinct()
                .sorted((a, b) -> {
                    int ta = towerOrder.indexOf(a), tb = towerOrder.indexOf(b);
                    if (ta >= 0 || tb >= 0) {
                        return Integer.compare(ta < 0 ? Integer.MAX_VALUE : ta, tb < 0 ? Integer.MAX_VALUE : tb);
                    }
                    if (a.equals(NOT_ENTERED) || b.equals(NOT_ENTERED)) {
                        return Boolean.compare(a.equals(NOT_ENTERED), b.equals(NOT_ENTERED));
                    }
                    int oa = ordinal.getOrDefault(a.trim().toLowerCase(), Integer.MAX_VALUE);
                    int ob = ordinal.getOrDefault(b.trim().toLowerCase(), Integer.MAX_VALUE);
                    return oa != ob ? Integer.compare(oa, ob) : a.compareTo(b);
                })
                .toList();
        Map<ClassKey, List<CarInfo>> out = new LinkedHashMap<>();
        for (String name : names) {
            List<CarInfo> cars = byCar.values().stream().filter(c -> c.className().equals(name))
                    .sorted((x, y) -> LiveAnalysis.byNumber(x.carNumber(), y.carNumber())).toList();
            out.put(new ClassKey(name, colors.get(name.trim().toLowerCase())), cars);
        }
        return out;
    }

    private static List<Integer> ints(Array array) throws SQLException {
        return array == null ? null : Arrays.asList((Integer[]) array.getArray());
    }
}
