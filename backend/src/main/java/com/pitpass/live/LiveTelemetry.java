package com.pitpass.live;

import com.pitpass.live.TelemetryDecoder.CarReading;
import com.pitpass.live.TelemetryDecoder.SessionClock;
import com.pitpass.sheets.SheetController;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * What IMSA telemetry leaves in memory: the latest reading per car, and each
 * car's last few lap-crossing energy samples. The ~1 Hz stream itself is never
 * kept — only one reading per car per lap goes to the database.
 *
 * A lap crossing is the car's completed-lap count going up; the first reading
 * at the new count is the energy at the line after that lap (up to a second
 * late). The count is the car's own logger's {@code lap_number} when it sends
 * one — it matched Al Kamel's count exactly on a live weekend — else the Al
 * Kamel feed's lap count for the car, since most GTD loggers send none. If
 * messages were missed and the count jumps by several laps, only the lap just
 * completed is sampled — the energy at the earlier crossings was never seen.
 */
final class LiveTelemetry {

    /** A reading as received; laps is the completed-lap count it was taken at, null when unknown. */
    record Reading(CarReading car, Integer laps, long receivedAtMs) {
    }

    /** Energy at the line after {@code lap}: the lap-crossing sample that is stored. className is IMSA's. */
    record LapSample(String car, int lap, double energyPct, Boolean pitLane, String className) {
    }

    /** For the tower: energy now and, from this stint's laps, the average use per lap. */
    record CarEnergy(Double energyPct, Double avgPerLapPct, Double lapsLeft) {
    }

    private static final int KEPT_SAMPLES = 30;

    private final Map<String, Reading> latest = new ConcurrentHashMap<>();
    private final Map<String, Deque<LapSample>> samples = new ConcurrentHashMap<>();
    private volatile SessionClock clock;

    /** Takes one decoded message, with no Al Kamel lap counts; returns the lap samples it completed. */
    List<LapSample> accept(TelemetryDecoder.Decoded decoded, long nowMs) {
        return accept(decoded, nowMs, Map.of());
    }

    /**
     * Takes one decoded message; returns the lap samples it completed.
     * feedLaps is the Al Kamel feed's completed-lap count by car number, for
     * cars whose logger sends no lap count.
     */
    List<LapSample> accept(TelemetryDecoder.Decoded decoded, long nowMs, Map<String, Integer> feedLaps) {
        if (decoded.session() != null) {
            clock = decoded.session();
        }
        List<LapSample> completed = new ArrayList<>();
        java.util.Set<String> sent = new java.util.HashSet<>(latest.keySet());
        decoded.cars().forEach(c -> sent.add(c.number()));
        for (CarReading car : decoded.cars()) {
            Integer laps = car.lapsCompleted() != null ? car.lapsCompleted() : feedLaps(feedLaps, car.number(), sent::contains);
            Reading previous = latest.put(car.number(), new Reading(car, laps, nowMs));
            if (previous == null || previous.laps() == null || laps == null || laps <= previous.laps()) {
                continue;
            }
            Double energy = car.energyPct() != null ? car.energyPct() : previous.car().energyPct();
            if (energy == null) {
                continue;
            }
            LapSample sample = new LapSample(car.number(), laps, energy, car.pitLane(), car.className());
            completed.add(sample);
            Deque<LapSample> kept = samples.computeIfAbsent(car.number(), k -> new ArrayDeque<>());
            synchronized (kept) {
                kept.addLast(sample);
                while (kept.size() > KEPT_SAMPLES) {
                    kept.removeFirst();
                }
            }
        }
        return completed;
    }

    /**
     * The feed's count for a telemetry number: exactly first (#04 is not #4),
     * then unambiguous without leading zeros among feed numbers that are not
     * themselves a telemetry car (telemetryHasCar) — telemetry's 4 never takes
     * #04's count while telemetry also sends 04.
     */
    private static Integer feedLaps(Map<String, Integer> feedLaps, String number, Predicate<String> telemetryHasCar) {
        Integer exact = feedLaps.get(number);
        if (exact != null || feedLaps.isEmpty()) {
            return exact;
        }
        String normalized = SheetController.normalizeCarNumber(number);
        List<Integer> loose = feedLaps.entrySet().stream()
                .filter(e -> SheetController.normalizeCarNumber(e.getKey()).equals(normalized)
                        && !telemetryHasCar.test(e.getKey()))
                .map(Map.Entry::getValue).toList();
        return loose.size() == 1 ? loose.getFirst() : null;
    }

    SessionClock clock() {
        return clock;
    }

    int cars() {
        return latest.size();
    }

    /** A new connection or session starts empty. */
    void clear() {
        latest.clear();
        samples.clear();
        clock = null;
    }

    /** As below, knowing no other Al Kamel car numbers. */
    CarEnergy energy(String alKamelNumber, String feedClass, Integer stintOpenLap, long nowMs, long staleMs) {
        return energy(alKamelNumber, feedClass, stintOpenLap, nowMs, staleMs, n -> false);
    }

    /**
     * The Al Kamel car's telemetry: its number exactly first (#04 is not #4),
     * then without leading zeros only when that is unambiguous and the
     * telemetry number is not itself another Al Kamel car (feedHasCar) — #04
     * never takes #4's energy when #4 is on the grid — and only when the
     * reading's class agrees with the car's class in the Al Kamel feed, so
     * another series' car sharing the number never lends it its energy. Null
     * energy when the last reading is older than staleMs.
     */
    CarEnergy energy(String alKamelNumber, String feedClass, Integer stintOpenLap, long nowMs, long staleMs,
                     Predicate<String> feedHasCar) {
        String key = resolve(alKamelNumber, feedHasCar);
        if (key == null) {
            return null;
        }
        Reading r = latest.get(key);
        if (r != null && !classAgrees(r.car().className(), feedClass)) {
            return null;
        }
        Double now = r != null && nowMs - r.receivedAtMs() <= staleMs ? r.car().energyPct() : null;
        Double avg = averageUse(key, stintOpenLap);
        Double left = now != null && avg != null && avg > 0 ? now / avg : null;
        return new CarEnergy(now, avg, left);
    }

    /** Either side unknown agrees: the guard can only refuse what it can see differ. */
    static boolean classAgrees(String telemetryClass, String feedClass) {
        return telemetryClass == null || telemetryClass.isBlank() || feedClass == null || feedClass.isBlank()
                || LiveEventMatch.sameClass(telemetryClass, feedClass);
    }

    private String resolve(String number, Predicate<String> feedHasCar) {
        if (number == null) {
            return null;
        }
        if (latest.containsKey(number.trim())) {
            return number.trim();
        }
        String normalized = SheetController.normalizeCarNumber(number);
        List<String> loose = latest.keySet().stream()
                .filter(k -> SheetController.normalizeCarNumber(k).equals(normalized) && !feedHasCar.test(k)).toList();
        return loose.size() == 1 ? loose.getFirst() : null;
    }

    /**
     * Average energy used per lap over this stint's consecutive samples. A
     * rise between samples is a refill or a recharge, not use, and is left
     * out; so is anything before the stint's first lap. Needs two laps.
     */
    private Double averageUse(String key, Integer stintOpenLap) {
        Deque<LapSample> kept = samples.get(key);
        if (kept == null) {
            return null;
        }
        List<LapSample> copy;
        synchronized (kept) {
            copy = new ArrayList<>(kept);
        }
        double used = 0;
        int laps = 0;
        for (int i = 1; i < copy.size(); i++) {
            LapSample a = copy.get(i - 1);
            LapSample b = copy.get(i);
            // The pair is lap b's use; a stint's first lap counts (a refill before it is a rise, left out below).
            if (stintOpenLap != null && b.lap() < stintOpenLap) {
                continue;
            }
            if (b.lap() != a.lap() + 1 || b.energyPct() > a.energyPct()) {
                continue;
            }
            used += a.energyPct() - b.energyPct();
            laps++;
        }
        return laps == 0 ? null : used / laps;
    }
}
