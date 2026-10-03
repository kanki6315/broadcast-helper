package com.pitpass.live;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Which of a car's laps say anything about its energy use, and what they say.
 * Pure: {@link LiveEnergy} loads the laps, the tower and the car panel read
 * the result, so both show the same figures.
 *
 * A lap's use is the drop in IMSA telemetry's energy between the line before
 * it and the line after it (live_energy_lap at lap - 1 and lap). It counts
 * only when both readings were seen and the lap was run at one pace: no pit
 * lane at either end, not the lap out of the pits, no refill, not run on an
 * empty meter (it floors at 0% and the car keeps lapping), and every sector
 * under one flag. Al Kamel flags each sector GREEN, FULL_YELLOW or RED; a
 * local yellow reads GREEN, which is what is wanted. Any other value is
 * unknown and left out.
 *
 * Green use is the average of the car's last {@value #WINDOW} green laps of
 * the session, across pit stops, so a new stint is not blind. Below
 * {@value #MIN_LAPS} laps there is no figure. Caution use is the same over
 * caution laps; a car rarely has enough of its own, so the class's pooled
 * caution laps stand in (see {@link #pooled}).
 */
final class EnergyModel {

    static final int WINDOW = 10;
    static final int SHORT_WINDOW = 5;
    static final int MIN_LAPS = 3;

    /** Why a lap counts, or why it does not. */
    enum Kind { GREEN, CAUTION, NO_READING, PIT, OUT_LAP, REFILL, EMPTY, RED, FLAG_CHANGE, FLAG_UNKNOWN }

    /** Where caution figures came from: the car's own laps, or its class's. */
    enum Source { CAR, CLASS }

    /**
     * One lap as recorded: live_lap, plus the energy at the line after it
     * (live_energy_lap at this lap) and whether that reading was in the pit lane.
     */
    record Lap(int lap, List<String> sectorFlags, Long pitInMs, Long pitOutMs, Integer lapTimeMs,
               Integer driverOrder, Float energyEnd, Boolean pitLaneEnd) {
    }

    /** energyPct is the reading at the line after the lap; usedPct the drop over it, null when either reading is missing. */
    record Classified(int lap, Kind kind, Float energyPct, Float usedPct, Integer lapTimeMs, Integer driverOrder) {
    }

    /**
     * An average over the newest laps of one kind. laps is how many it rests
     * on (at most the window); driverChange says they were not all one
     * driver's. lastLap is the newest of them.
     */
    record Average(double perLapPct, Double lapTimeMs, int laps, boolean driverChange, int lastLap) {
    }

    /** A car's laps, classified, and what they average to. Averages are null below MIN_LAPS. */
    record Car(List<Classified> laps, Average green, Average greenShort, Average caution) {
    }

    private static final Set<String> FLAGS = Set.of("GREEN", "FULL_YELLOW", "RED");

    private EnergyModel() {
    }

    /** laps in any order; the energy at the line before each lap is the previous lap's energyEnd. */
    static Car car(List<Lap> laps) {
        List<Lap> sorted = laps.stream().sorted(java.util.Comparator.comparingInt(Lap::lap)).toList();
        Map<Integer, Lap> byLap = new java.util.HashMap<>();
        for (Lap l : sorted) {
            byLap.put(l.lap(), l);
        }
        List<Classified> out = new ArrayList<>();
        for (Lap l : sorted) {
            Lap before = byLap.get(l.lap() - 1);
            Float start = before == null ? null : before.energyEnd();
            Float used = start == null || l.energyEnd() == null ? null : start - l.energyEnd();
            out.add(new Classified(l.lap(), kind(l, before, start), l.energyEnd(), used, l.lapTimeMs(), l.driverOrder()));
        }
        return new Car(out, average(out, Kind.GREEN, WINDOW), average(out, Kind.GREEN, SHORT_WINDOW),
                average(out, Kind.CAUTION, WINDOW));
    }

    private static Kind kind(Lap l, Lap before, Float start) {
        if (start == null || l.energyEnd() == null) {
            return Kind.NO_READING;
        }
        if (l.pitInMs() != null || l.pitOutMs() != null
                || Boolean.TRUE.equals(l.pitLaneEnd()) || Boolean.TRUE.equals(before.pitLaneEnd())) {
            return Kind.PIT;
        }
        if (before.pitInMs() != null) {
            return Kind.OUT_LAP;
        }
        if (l.energyEnd() > start) {
            return Kind.REFILL;
        }
        if (l.energyEnd() <= 0) {
            return Kind.EMPTY;
        }
        List<String> flags = l.sectorFlags();
        if (flags == null || flags.isEmpty() || flags.stream().anyMatch(f -> f == null || !FLAGS.contains(f))) {
            return Kind.FLAG_UNKNOWN;
        }
        Set<String> seen = new HashSet<>(flags);
        if (seen.contains("RED")) {
            return Kind.RED;
        }
        if (seen.size() > 1) {
            return Kind.FLAG_CHANGE;
        }
        return seen.contains("GREEN") ? Kind.GREEN : Kind.CAUTION;
    }

    /** The newest {@code window} laps of a kind, or null below MIN_LAPS. */
    static Average average(List<Classified> laps, Kind kind, int window) {
        List<Classified> picked = new ArrayList<>();
        for (int i = laps.size() - 1; i >= 0 && picked.size() < window; i--) {
            if (laps.get(i).kind() == kind) {
                picked.add(laps.get(i));
            }
        }
        return over(picked);
    }

    /**
     * Caution use for a class: every caution lap of its cars this session.
     * Cautions are rare and short, so a window would throw most of them away.
     */
    static Average pooled(List<Car> cars) {
        List<Classified> all = new ArrayList<>();
        for (Car c : cars) {
            c.laps().stream().filter(l -> l.kind() == Kind.CAUTION).forEach(all::add);
        }
        Average a = over(all);
        // Different cars' drivers say nothing about a driver change.
        return a == null ? null : new Average(a.perLapPct(), a.lapTimeMs(), a.laps(), false, a.lastLap());
    }

    private static Average over(List<Classified> laps) {
        if (laps.size() < MIN_LAPS) {
            return null;
        }
        double used = 0;
        double time = 0;
        int timed = 0;
        for (Classified l : laps) {
            used += l.usedPct();
            if (l.lapTimeMs() != null && l.lapTimeMs() > 0) {
                time += l.lapTimeMs();
                timed++;
            }
        }
        boolean driverChange = laps.stream().map(Classified::driverOrder).filter(Objects::nonNull).distinct().count() > 1;
        int last = laps.stream().mapToInt(Classified::lap).max().orElse(0);
        return new Average(used / laps.size(), timed == 0 ? null : time / timed, laps.size(), driverChange, last);
    }

    /** Green laps left on this energy, or null without a green figure. */
    static Double greenLapsLeft(Double energyNowPct, Average green) {
        return energyNowPct == null || green == null || green.perLapPct() <= 0 ? null : energyNowPct / green.perLapPct();
    }

    /** Green use over one stint's laps (open to close, inclusive), for the car panel. Null below MIN_LAPS. */
    static Average stint(List<Classified> laps, Integer openLap, Integer closeLap) {
        if (openLap == null) {
            return null;
        }
        return over(laps.stream()
                .filter(l -> l.kind() == Kind.GREEN && l.lap() >= openLap && (closeLap == null || l.lap() <= closeLap))
                .toList());
    }
}
