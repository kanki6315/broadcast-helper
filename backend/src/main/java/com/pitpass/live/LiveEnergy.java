package com.pitpass.live;

import com.pitpass.sheets.SheetController;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.sql.Array;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Loads a session's laps and lap-crossing energy and runs them through
 * {@link EnergyModel}, per Al Kamel car. The tower asks on every poll, so a
 * session's result is kept for {@value #FRESH_MS} ms: a car completes a lap
 * every minute or so, and the reading shown as "now" comes from memory, not
 * from here.
 *
 * live_energy_lap is keyed by IMSA's number as sent and live_lap by Al
 * Kamel's: the same number exactly first (#04 is not #4), then without
 * leading zeros only when that names one car.
 */
@Component
class LiveEnergy {

    static final long FRESH_MS = 2_000;

    /** Caution use and where it came from; null figures when neither the car nor its class has enough. */
    record Caution(EnergyModel.Average average, EnergyModel.Source source) {
    }

    private record Cached(long atMs, Map<String, EnergyModel.Car> cars) {
    }

    private final JdbcClient db;
    /** By session: the tower polls the current one while a car panel may be open on an earlier one. */
    private final Map<Long, Cached> cached = new java.util.concurrent.ConcurrentHashMap<>();

    LiveEnergy(JdbcClient db) {
        this.db = db;
    }

    /** Every car of the session with laps recorded, by Al Kamel number. */
    Map<String, EnergyModel.Car> session(long session) {
        long now = System.currentTimeMillis();
        Cached c = cached.get(session);
        if (c != null && now - c.atMs() < FRESH_MS) {
            return c.cars();
        }
        Map<String, EnergyModel.Car> cars = load(session);
        cached.values().removeIf(old -> now - old.atMs() >= FRESH_MS);
        cached.put(session, new Cached(now, cars));
        return cars;
    }

    /** One car, or null when it has no laps recorded. */
    EnergyModel.Car car(long session, String carNumber) {
        return session(session).get(carNumber);
    }

    /** The car's own caution use when it has enough, else its class's pooled over classCars. */
    static Caution caution(EnergyModel.Car car, List<EnergyModel.Car> classCars) {
        if (car != null && car.caution() != null) {
            return new Caution(car.caution(), EnergyModel.Source.CAR);
        }
        EnergyModel.Average pooled = EnergyModel.pooled(classCars);
        return pooled == null ? new Caution(null, null) : new Caution(pooled, EnergyModel.Source.CLASS);
    }

    private record Reading(float pct, Boolean pitLane) {
    }

    private Map<String, EnergyModel.Car> load(long session) {
        Map<String, Map<Integer, Reading>> energy = new HashMap<>();
        db.sql("SELECT car_number, lap_number, energy_pct, pit_lane FROM live_energy_lap WHERE session_db_id = :s")
                .param("s", session)
                .query((rs, i) -> energy.computeIfAbsent(rs.getString("car_number"), k -> new HashMap<>())
                        .put(rs.getInt("lap_number"), new Reading(rs.getFloat("energy_pct"),
                                rs.getObject("pit_lane", Boolean.class))))
                .list();
        if (energy.isEmpty()) {
            return Map.of();
        }
        Map<String, Map<Integer, EnergyModel.Lap>> laps = new HashMap<>();
        db.sql("""
                SELECT car_number, lap_number, sector_flags, pit_in_time_ms, pit_out_time_ms, lap_time_ms, driver_order
                FROM live_lap WHERE session_db_id = :s
                """)
                .param("s", session)
                .query((rs, i) -> laps.computeIfAbsent(rs.getString("car_number"), k -> new TreeMap<>())
                        .put(rs.getInt("lap_number"), new EnergyModel.Lap(rs.getInt("lap_number"),
                                texts(rs.getArray("sector_flags")), rs.getObject("pit_in_time_ms", Long.class),
                                rs.getObject("pit_out_time_ms", Long.class), rs.getObject("lap_time_ms", Integer.class),
                                rs.getObject("driver_order", Integer.class), null, null)))
                .list();
        // Every Al Kamel number in the session: a loose match never takes another car's readings.
        java.util.Set<String> alKamelCars = new java.util.HashSet<>(laps.keySet());
        alKamelCars.addAll(db.sql("SELECT car_number FROM live_car WHERE session_db_id = :s")
                .param("s", session).query(String.class).list());
        Map<String, EnergyModel.Car> out = new HashMap<>();
        for (var e : laps.entrySet()) {
            Map<Integer, Reading> readings = readingsFor(e.getKey(), energy, alKamelCars);
            if (readings == null) {
                continue;
            }
            Map<Integer, EnergyModel.Lap> merged = new TreeMap<>(e.getValue());
            // A reading with no lap recorded (the lap before the first, or one the feed never sent) still starts the next lap.
            for (var r : readings.entrySet()) {
                merged.putIfAbsent(r.getKey(), new EnergyModel.Lap(r.getKey(), null, null, null, null, null, null, null));
            }
            List<EnergyModel.Lap> withEnergy = merged.values().stream().map(l -> {
                Reading r = readings.get(l.lap());
                return r == null ? l : new EnergyModel.Lap(l.lap(), l.sectorFlags(), l.pitInMs(), l.pitOutMs(),
                        l.lapTimeMs(), l.driverOrder(), r.pct(), r.pitLane());
            }).toList();
            out.put(e.getKey(), EnergyModel.car(withEnergy));
        }
        return out;
    }

    /** Exactly first; loosely only to a telemetry number no other Al Kamel car has exactly. */
    private static Map<Integer, Reading> readingsFor(String alKamelNumber, Map<String, Map<Integer, Reading>> energy,
                                                     java.util.Set<String> alKamelCars) {
        Map<Integer, Reading> exact = energy.get(alKamelNumber);
        if (exact != null) {
            return exact;
        }
        String normalized = SheetController.normalizeCarNumber(alKamelNumber);
        List<String> loose = energy.keySet().stream()
                .filter(k -> !alKamelCars.contains(k) && SheetController.normalizeCarNumber(k).equals(normalized)).toList();
        return loose.size() == 1 ? energy.get(loose.getFirst()) : null;
    }

    private static List<String> texts(Array array) throws SQLException {
        return array == null ? null : Arrays.asList((String[]) array.getArray());
    }
}
