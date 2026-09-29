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

/**
 * What IMSA telemetry leaves in memory: the latest reading per car, and each
 * car's last few lap-crossing energy samples. The ~1 Hz stream itself is never
 * kept — only one reading per car per lap goes to the database.
 *
 * A lap crossing is {@code scoring.lapNumber} going up. The first reading on
 * the new lap is the energy at the line for the lap just completed (the
 * reading before it is up to a second older). If messages were missed and the
 * count jumps by several laps, only the lap just completed is sampled — the
 * energy at the earlier crossings was never seen. Whether IMSA's lap numbers
 * match Al Kamel's, or run one off, is unverified until a live weekend.
 */
final class LiveTelemetry {

    /** A reading as received. */
    record Reading(CarReading car, long receivedAtMs) {
    }

    /** Energy at the line after {@code lap}: the lap-crossing sample that is stored. */
    record LapSample(String car, int lap, double energyPct, Boolean pitLane) {
    }

    /** For the tower: energy now and, from this stint's laps, the average use per lap. */
    record CarEnergy(Double energyPct, Double avgPerLapPct, Double lapsLeft) {
    }

    private static final int KEPT_SAMPLES = 30;

    private final Map<String, Reading> latest = new ConcurrentHashMap<>();
    private final Map<String, Deque<LapSample>> samples = new ConcurrentHashMap<>();
    private volatile SessionClock clock;

    /** Takes one decoded message; returns the lap samples it completed. */
    List<LapSample> accept(TelemetryDecoder.Decoded decoded, long nowMs) {
        if (decoded.session() != null) {
            clock = decoded.session();
        }
        List<LapSample> completed = new ArrayList<>();
        for (CarReading car : decoded.cars()) {
            Reading previous = latest.put(car.number(), new Reading(car, nowMs));
            if (previous == null || previous.car().lapNumber() == null || car.lapNumber() == null
                    || car.lapNumber() <= previous.car().lapNumber()) {
                continue;
            }
            Double energy = car.energyPct() != null ? car.energyPct() : previous.car().energyPct();
            if (energy == null) {
                continue;
            }
            LapSample sample = new LapSample(car.number(), car.lapNumber() - 1, energy, car.pitLane());
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

    /**
     * The Al Kamel car's telemetry: its number exactly first (#04 is not #4),
     * then without leading zeros only when that is unambiguous. Null energy
     * when the last reading is older than staleMs.
     */
    CarEnergy energy(String alKamelNumber, Integer stintOpenLap, long nowMs, long staleMs) {
        String key = resolve(alKamelNumber);
        if (key == null) {
            return null;
        }
        Reading r = latest.get(key);
        Double now = r != null && nowMs - r.receivedAtMs() <= staleMs ? r.car().energyPct() : null;
        Double avg = averageUse(key, stintOpenLap);
        Double left = now != null && avg != null && avg > 0 ? now / avg : null;
        return new CarEnergy(now, avg, left);
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
