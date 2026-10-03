package com.pitpass.live;

import com.pitpass.live.TelemetryDecoder.CarReading;
import com.pitpass.live.TelemetryDecoder.SessionClock;
import com.pitpass.sheets.SheetController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What IMSA telemetry leaves in memory: the latest reading per car. The ~1 Hz
 * stream itself is never kept — only one reading per car per lap goes to the
 * database, where {@link LiveEnergy} works out use per lap.
 *
 * A lap crossing is the car's completed-lap count going up; the first reading
 * at the new count is the energy at the line after that lap. The count is Al
 * Kamel's for the car when the feed has one: it moves within half a second of
 * the official line, while the car's own logger {@code lap_number} ticks about
 * 6.6 s after it (Petit Le Mans practice, 2026-10-01), so a logger-timed
 * sample is ~0.2% late and blurs the lap's edges for pit and flag
 * classification. The logger's count stands in when the feed has none. If
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

    private final Map<String, Reading> latest = new ConcurrentHashMap<>();
    private volatile SessionClock clock;

    /** Takes one decoded message, with no Al Kamel lap counts; returns the lap samples it completed (logger counts). */
    List<LapSample> accept(TelemetryDecoder.Decoded decoded, long nowMs) {
        return accept(decoded, nowMs, Map.of());
    }

    /**
     * Takes one decoded message; returns the lap samples it completed.
     * feedLaps is the Al Kamel feed's completed-lap count by car number; it
     * wins over the logger's own count, which stands in when the feed has none.
     */
    List<LapSample> accept(TelemetryDecoder.Decoded decoded, long nowMs, Map<String, Integer> feedLaps) {
        if (decoded.session() != null) {
            clock = decoded.session();
        }
        List<LapSample> completed = new ArrayList<>();
        for (CarReading car : decoded.cars()) {
            Integer fromFeed = feedLaps(feedLaps, car.number());
            Integer laps = fromFeed != null ? fromFeed : car.lapsCompleted();
            Reading previous = latest.put(car.number(), new Reading(car, laps, nowMs));
            if (previous == null || previous.laps() == null || laps == null || laps <= previous.laps()) {
                continue;
            }
            Double energy = car.energyPct() != null ? car.energyPct() : previous.car().energyPct();
            if (energy == null) {
                continue;
            }
            completed.add(new LapSample(car.number(), laps, energy, car.pitLane(), car.className()));
        }
        return completed;
    }

    /** The feed's count for a telemetry number: exactly first (#04 is not #4), then unambiguous without leading zeros. */
    private static Integer feedLaps(Map<String, Integer> feedLaps, String number) {
        Integer exact = feedLaps.get(number);
        if (exact != null || feedLaps.isEmpty()) {
            return exact;
        }
        String normalized = SheetController.normalizeCarNumber(number);
        List<Integer> loose = feedLaps.entrySet().stream()
                .filter(e -> SheetController.normalizeCarNumber(e.getKey()).equals(normalized))
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
        clock = null;
    }

    /**
     * The Al Kamel car's telemetry: its number exactly first (#04 is not #4),
     * then without leading zeros only when that is unambiguous — and only when
     * the reading's class agrees with the car's class in the Al Kamel feed, so
     * another series' car sharing the number never lends it its energy. Null
     * when unseen or the last reading is older than staleMs.
     */
    Double energyNow(String alKamelNumber, String feedClass, long nowMs, long staleMs) {
        String key = resolve(alKamelNumber);
        if (key == null) {
            return null;
        }
        Reading r = latest.get(key);
        if (r != null && !classAgrees(r.car().className(), feedClass)) {
            return null;
        }
        return r != null && nowMs - r.receivedAtMs() <= staleMs ? r.car().energyPct() : null;
    }

    /** Either side unknown agrees: the guard can only refuse what it can see differ. */
    static boolean classAgrees(String telemetryClass, String feedClass) {
        return telemetryClass == null || telemetryClass.isBlank() || feedClass == null || feedClass.isBlank()
                || LiveEventMatch.sameClass(telemetryClass, feedClass);
    }

    private String resolve(String number) {
        if (number == null) {
            return null;
        }
        if (latest.containsKey(number.trim())) {
            return number.trim();
        }
        String normalized = SheetController.normalizeCarNumber(number);
        List<String> loose = latest.keySet().stream()
                .filter(k -> SheetController.normalizeCarNumber(k).equals(normalized)).toList();
        return loose.size() == 1 ? loose.getFirst() : null;
    }
}
