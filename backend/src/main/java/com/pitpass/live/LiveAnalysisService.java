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
                            Map<EnergyModel.Kind, Integer> laps) {
    }

    /** caution: the class's caution laps pooled, every car's. */
    public record EnergyClass(String className, String color, List<CarInfo> cars, EnergyModel.Average caution,
                              List<EnergyCar> energy) {
    }

    /** live: the session is the one being fed, so energyPct is now. */
    public record EnergyResponse(long sessionDbId, boolean live, List<EnergyClass> classes) {
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
        return new EnergyResponse(session, isLive, out);
    }

    static EnergyCar energyCar(String car, EnergyModel.Car m, List<EnergyModel.Car> classModels, Double now) {
        LiveEnergy.Caution caution = LiveEnergy.caution(m, classModels);
        Integer lastLap = m.laps().isEmpty() ? null : m.laps().getLast().lap();
        Integer since = m.green() == null || lastLap == null ? null : lastLap - m.green().lastLap();
        Map<EnergyModel.Kind, Integer> counts = new java.util.EnumMap<>(EnergyModel.Kind.class);
        m.laps().forEach(l -> counts.merge(l.kind(), 1, Integer::sum));
        return new EnergyCar(car, now, EnergyModel.greenLapsLeft(now, m.green()), m.green(), m.greenShort(),
                caution.average(), caution.source(), lastLap, since, counts);
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
