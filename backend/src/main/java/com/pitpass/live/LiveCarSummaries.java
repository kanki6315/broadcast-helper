package com.pitpass.live;

import com.pitpass.live.AnalysisRows.LapPatch;
import com.pitpass.live.AnalysisRows.StintPatch;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The only analysis state held in memory: per car of the current session,
 * the last lap, the best lap and the open stint. A few dozen bytes a car,
 * updated from each diff as it streams past; everything else is in Postgres.
 *
 * Built from diffs alone, so two things are approximate and flagged rather
 * than guessed: a lap invalidated after the fact may have been the best
 * ({@code bestStale} — read the best lap from live_lap instead), and a stint
 * whose type is never sent is treated as on track.
 */
final class LiveCarSummaries {

    /** One car. lapsInStint counts the open stint's laps up to the last completed one. */
    record CarSummary(String car, Integer lastLap, Integer lastLapMs, Integer bestLap, Integer bestLapMs,
                      boolean bestStale, Long stintStartMs, String stintType, Integer stintDriverOrder,
                      Integer stintOpenLap, Integer lapsInStint) {
    }

    private static final class Car {
        Integer lastLap;
        Integer lastLapMs;
        Integer bestLap;
        Integer bestLapMs;
        boolean bestStale;
        Long stintStart;
        String stintType;
        Integer stintDriver;
        Integer stintOpenLap;
    }

    private final Map<String, Car> cars = new ConcurrentHashMap<>();
    private volatile Long sessionDbId;

    /** A new session (or connection) starts empty. */
    void reset(Long sessionDbId) {
        cars.clear();
        this.sessionDbId = sessionDbId;
    }

    Long sessionDbId() {
        return sessionDbId;
    }

    void lap(LapPatch p) {
        Car c = cars.computeIfAbsent(p.car, k -> new Car());
        synchronized (c) {
            if (p.has(LapPatch.TIME) && p.lapTimeMs != null && p.lapTimeMs > 0) {
                if (c.lastLap == null || p.lap >= c.lastLap) {
                    c.lastLap = p.lap;
                    c.lastLapMs = p.lapTimeMs;
                }
                boolean invalid = p.has(LapPatch.VALID) && Boolean.FALSE.equals(p.valid);
                if (!invalid && (c.bestLapMs == null || p.lapTimeMs < c.bestLapMs)) {
                    c.bestLap = p.lap;
                    c.bestLapMs = p.lapTimeMs;
                }
            }
            if (p.has(LapPatch.VALID) && Boolean.FALSE.equals(p.valid) && c.bestLap != null && c.bestLap == p.lap
                    && !p.has(LapPatch.TIME)) {
                c.bestStale = true;
            }
        }
    }

    void stint(StintPatch p) {
        Car c = cars.computeIfAbsent(p.car, k -> new Car());
        synchronized (c) {
            boolean closed = p.has(StintPatch.FINISH) && p.finishTimeMs != null && p.finishTimeMs > 0;
            if (closed) {
                if (c.stintStart != null && c.stintStart == p.startTimeMs) {
                    c.stintStart = null;
                    c.stintType = null;
                    c.stintDriver = null;
                    c.stintOpenLap = null;
                }
                return;
            }
            if (c.stintStart == null || p.startTimeMs >= c.stintStart) {
                if (c.stintStart == null || p.startTimeMs != c.stintStart) {
                    c.stintType = null;
                    c.stintDriver = null;
                    c.stintOpenLap = null;
                }
                c.stintStart = p.startTimeMs;
                if (p.has(StintPatch.TYPE)) {
                    c.stintType = p.type;
                }
                if (p.has(StintPatch.DRIVER)) {
                    c.stintDriver = p.driverOrder;
                }
                if (p.has(StintPatch.OPEN_LAP)) {
                    c.stintOpenLap = p.openLap;
                }
            }
        }
    }

    List<CarSummary> snapshot() {
        return cars.entrySet().stream()
                .map(e -> {
                    Car c = e.getValue();
                    synchronized (c) {
                        Integer inStint = c.stintOpenLap != null && c.lastLap != null
                                ? Math.max(0, c.lastLap - c.stintOpenLap + 1) : null;
                        return new CarSummary(e.getKey(), c.lastLap, c.lastLapMs, c.bestLap, c.bestLapMs,
                                c.bestStale, c.stintStart, c.stintType == null && c.stintStart != null ? "TRACK" : c.stintType,
                                c.stintDriver, c.stintOpenLap, inStint);
                    }
                })
                .sorted(Comparator.comparing(CarSummary::car))
                .toList();
    }
}
