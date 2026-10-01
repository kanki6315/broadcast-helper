package com.pitpass.live;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * The timing page's analysis over one class's recorded laps and stints:
 * gap to the class leader lap by lap, best sectors, and pit stops. Pure —
 * {@link LiveAnalysisService} loads the rows — and always recomputed from
 * live_lap / live_stint rather than stored, so a lap the feed corrects after
 * the fact corrects every gap and best that depends on it.
 *
 * Ported from Gantry's race history (gap visualizer, best sectors, pit
 * cycles), which captures iRacing's gap as it goes; Al Kamel's feed keeps
 * every car's line-crossing time, so here the gap is arithmetic on those.
 */
public final class LiveAnalysis {

    private LiveAnalysis() {
    }

    /** One recorded lap, as much of it as the analysis reads. */
    public record Lap(String car, int lap, Integer driverOrder, Long startTimeMs, Integer lapTimeMs,
                      List<Integer> sectorMs, Boolean valid, boolean pitIn) {

        /** When the car crossed the line to finish this lap: its start plus its time. Null until timed. */
        Long crossingMs() {
            return startTimeMs == null || lapTimeMs == null || lapTimeMs <= 0 ? null : startTimeMs + lapTimeMs;
        }
    }

    /** One Al Kamel stint (type TRACK or PIT). */
    public record Stint(String car, long startTimeMs, String type, String pitType, Integer driverOrder,
                        Integer openLap, Integer closeLap, Long finishTimeMs) {

        boolean pit() {
            return "PIT".equalsIgnoreCase(type);
        }

        boolean open() {
            return finishTimeMs == null || finishTimeMs <= 0;
        }
    }

    // ---- gap to the class leader ------------------------------------------------------

    /**
     * One car's gap to its class leader at the end of each lap, laps
     * firstLap.. in order. gapMs is null where the car was a lap or more
     * down (lapsDown > 0) or the lap was not timed; lapsDown is null only
     * where the lap was not timed. pitLaps are the laps it pitted at the end of.
     */
    public record GapCar(String carNumber, int firstLap, List<Long> gapMs, List<Integer> lapsDown,
                         List<Integer> pitLaps) {
    }

    /**
     * Gap to the class leader after every lap. The leader at lap L is the
     * first car in the class to finish lap L, so the gap is how much later
     * this car crossed the line to finish the same lap. A car that finished
     * lap L after the leader had already finished lap L + n is n laps down,
     * and has no gap in seconds on that lap — a chart breaks the line there
     * rather than plot a lap as a time.
     *
     * Cars come back in running order: most laps, then earliest to finish the last.
     */
    public static List<GapCar> gaps(Collection<Lap> laps) {
        Map<String, NavigableMap<Integer, Long>> crossings = new HashMap<>();
        Map<String, List<Integer>> pits = new HashMap<>();
        NavigableMap<Integer, Long> leader = new TreeMap<>();
        for (Lap l : laps) {
            Long at = l.crossingMs();
            if (l.pitIn()) {
                pits.computeIfAbsent(l.car(), k -> new ArrayList<>()).add(l.lap());
            }
            crossings.computeIfAbsent(l.car(), k -> new TreeMap<>());
            if (at == null) {
                continue;
            }
            crossings.get(l.car()).put(l.lap(), at);
            leader.merge(l.lap(), at, Math::min);
        }
        // The leader's crossings, as the time each lap count was first reached.
        // Made non-decreasing so "laps the leader had done by time t" is one lookup.
        TreeMap<Long, Integer> lapsDoneBy = new TreeMap<>();
        long latest = Long.MIN_VALUE;
        for (var e : leader.entrySet()) {
            latest = Math.max(latest, e.getValue());
            lapsDoneBy.merge(latest, e.getKey(), Math::max);
        }

        List<GapCar> out = new ArrayList<>();
        for (var e : crossings.entrySet()) {
            NavigableMap<Integer, Long> own = e.getValue();
            List<Integer> pitLaps = pits.getOrDefault(e.getKey(), List.of()).stream().sorted().distinct().toList();
            if (own.isEmpty()) {
                out.add(new GapCar(e.getKey(), 1, List.of(), List.of(), pitLaps));
                continue;
            }
            int first = own.firstKey();
            int last = own.lastKey();
            List<Long> gap = new ArrayList<>();
            List<Integer> down = new ArrayList<>();
            for (int lap = first; lap <= last; lap++) {
                Long at = own.get(lap);
                if (at == null) {
                    gap.add(null);
                    down.add(null);
                    continue;
                }
                var done = lapsDoneBy.floorEntry(at);
                int lapsDown = done == null ? 0 : Math.max(0, done.getValue() - lap);
                down.add(lapsDown);
                gap.add(lapsDown > 0 ? null : at - leader.get(lap));
            }
            out.add(new GapCar(e.getKey(), first, gap, down, pitLaps));
        }
        out.sort(runningOrder(crossings));
        return out;
    }

    /** Most laps finished, then the earliest to finish the last of them; untimed cars last, by number. */
    private static Comparator<GapCar> runningOrder(Map<String, NavigableMap<Integer, Long>> crossings) {
        Comparator<GapCar> byLaps = Comparator.comparingInt(
                c -> -(crossings.get(c.carNumber()).isEmpty() ? 0 : crossings.get(c.carNumber()).lastKey()));
        return byLaps
                .thenComparingLong(c -> crossings.get(c.carNumber()).isEmpty()
                        ? Long.MAX_VALUE : crossings.get(c.carNumber()).lastEntry().getValue())
                .thenComparing(GapCar::carNumber);
    }

    // ---- best sectors -------------------------------------------------------------------

    /**
     * A car's best time in each sector over its valid laps, the lap each came
     * on, its best valid lap, and the theoretical best — the best sectors
     * added up, only when it has a best in every sector the class ran.
     * Lists are by sector (index 0 = S1); null where it has no valid time.
     */
    public record SectorCar(String carNumber, List<Integer> bestSectorMs, List<Integer> bestSectorLap,
                            Integer bestLap, Integer bestLapMs, Long theoreticalMs) {
    }

    /** A class's best sectors: each car's, and the fastest in the class per sector. */
    public record SectorBests(int sectors, List<Integer> classBestSectorMs, List<SectorCar> cars) {
    }

    /**
     * Best sectors over valid laps only: a lap the feed marked invalid (track
     * limits) keeps none of its sectors, as Gantry counts only valid sectors.
     * A lap whose validity is not known yet counts. Cars are fastest
     * theoretical best first, then best lap, then those with neither.
     */
    public static SectorBests sectorBests(Collection<Lap> laps) {
        int sectors = 0;
        for (Lap l : laps) {
            sectors = Math.max(sectors, l.sectorMs() == null ? 0 : l.sectorMs().size());
        }
        Map<String, Integer[]> best = new HashMap<>();
        Map<String, Integer[]> bestLap = new HashMap<>();
        Map<String, int[]> bestWhole = new HashMap<>();
        Integer[] classBest = new Integer[sectors];
        for (Lap l : laps) {
            best.computeIfAbsent(l.car(), k -> new Integer[0]);
            if (Boolean.FALSE.equals(l.valid())) {
                continue;
            }
            if (l.lapTimeMs() != null && l.lapTimeMs() > 0) {
                int[] whole = bestWhole.get(l.car());
                if (whole == null || l.lapTimeMs() < whole[1] || (l.lapTimeMs() == whole[1] && l.lap() < whole[0])) {
                    bestWhole.put(l.car(), new int[] {l.lap(), l.lapTimeMs()});
                }
            }
            if (l.sectorMs() == null) {
                continue;
            }
            Integer[] own = best.get(l.car());
            Integer[] ownLap = bestLap.computeIfAbsent(l.car(), k -> new Integer[0]);
            if (own.length < sectors) {
                own = Arrays.copyOf(own, sectors);
                ownLap = Arrays.copyOf(ownLap, sectors);
                best.put(l.car(), own);
                bestLap.put(l.car(), ownLap);
            }
            for (int i = 0; i < l.sectorMs().size(); i++) {
                Integer ms = l.sectorMs().get(i);
                if (ms == null || ms <= 0) {
                    continue;
                }
                if (own[i] == null || ms < own[i] || (ms.equals(own[i]) && l.lap() < ownLap[i])) {
                    own[i] = ms;
                    ownLap[i] = l.lap();
                }
                if (classBest[i] == null || ms < classBest[i]) {
                    classBest[i] = ms;
                }
            }
        }
        List<SectorCar> cars = new ArrayList<>();
        for (var e : best.entrySet()) {
            Integer[] own = Arrays.copyOf(e.getValue(), sectors);
            Integer[] ownLap = Arrays.copyOf(bestLap.getOrDefault(e.getKey(), new Integer[0]), sectors);
            Long theoretical = null;
            if (sectors > 0) {
                long sum = 0;
                for (Integer ms : own) {
                    if (ms == null) {
                        sum = -1;
                        break;
                    }
                    sum += ms;
                }
                theoretical = sum < 0 ? null : sum;
            }
            int[] whole = bestWhole.get(e.getKey());
            cars.add(new SectorCar(e.getKey(), Arrays.asList(own), Arrays.asList(ownLap),
                    whole == null ? null : whole[0], whole == null ? null : whole[1], theoretical));
        }
        cars.sort(Comparator.comparing(SectorCar::theoreticalMs, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(SectorCar::bestLapMs, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(SectorCar::carNumber));
        return new SectorBests(sectors, Arrays.asList(classBest), cars);
    }

    // ---- pit stops ----------------------------------------------------------------------

    /**
     * One stop: Al Kamel's PIT stint. durationMs is its pit-lane time, null
     * while the car is still in; lap is the lap it came in on. driverIn and
     * driverOut are the driver orders of the track stints either side of it
     * (driverOut null until the car is out); driverChange whether they differ.
     */
    public record PitStop(int number, long startTimeMs, Long durationMs, Integer lap, String pitType,
                          Integer driverIn, Integer driverOut, boolean driverChange) {
    }

    /**
     * A car's stops. totalMs and averageMs count finished stops only;
     * lapsSinceStop is laps completed since the last stop's lap (null before
     * the first stop, and while in the pit).
     */
    public record PitCar(String carNumber, List<PitStop> stops, long totalMs, Long averageMs, boolean inPit,
                         Integer lapsSinceStop) {
    }

    /**
     * Each car's pit stops, from its stints, counted as Al Kamel counts them
     * (checked against its own tower, Road Atlanta practice 2026-09-30):
     * - a car's opening PIT stint, from the garage at the start, is no stop;
     * - PIT stints back to back are one stop. A red flag closes every stint
     *   and opens a fresh one at the restart, so a car in the pit lane
     *   across it has two.
     * A stop's time sums its stints' pit-lane time (the red flag's gap is in
     * none of them). lastLap is how many laps each car has completed, for
     * laps since its last stop. Cars come back most stops first, then by
     * number, so the cars off-sequence stand out.
     */
    public static List<PitCar> pitStops(Collection<Stint> stints, Map<String, Integer> lastLap) {
        Map<String, List<Stint>> byCar = new HashMap<>();
        for (Stint s : stints) {
            byCar.computeIfAbsent(s.car(), k -> new ArrayList<>()).add(s);
        }
        List<PitCar> out = new ArrayList<>();
        for (var e : byCar.entrySet()) {
            List<Stint> own = e.getValue().stream().sorted(Comparator.comparingLong(Stint::startTimeMs)).toList();
            List<List<Integer>> groups = new ArrayList<>();
            for (int i = 0; i < own.size(); i++) {
                Stint s = own.get(i);
                if (!s.pit() || (i == 0 && (s.openLap() == null || s.openLap() <= 1))) {
                    continue;
                }
                if (!groups.isEmpty() && groups.getLast().getLast() == i - 1) {
                    groups.getLast().add(i);
                } else {
                    groups.add(new ArrayList<>(List.of(i)));
                }
            }
            List<PitStop> stops = new ArrayList<>();
            long total = 0;
            int finished = 0;
            for (List<Integer> group : groups) {
                Stint first = own.get(group.getFirst());
                Integer before = null;
                for (int j = group.getFirst() - 1; j >= 0 && before == null; j--) {
                    if (!own.get(j).pit()) {
                        before = own.get(j).driverOrder();
                    }
                }
                Integer after = null;
                for (int j = group.getLast() + 1; j < own.size() && after == null; j++) {
                    if (!own.get(j).pit()) {
                        after = own.get(j).driverOrder();
                    }
                }
                Long length = 0L;
                String pitType = null;
                for (int i : group) {
                    Stint s = own.get(i);
                    length = length == null || s.open() ? null : length + s.finishTimeMs() - s.startTimeMs();
                    pitType = pitType != null ? pitType : s.pitType();
                }
                if (length != null) {
                    total += length;
                    finished++;
                }
                stops.add(new PitStop(stops.size() + 1, first.startTimeMs(), length, first.openLap(), pitType,
                        before, after, before != null && after != null && !before.equals(after)));
            }
            boolean inPit = !own.isEmpty() && own.getLast().pit() && own.getLast().open();
            Integer since = null;
            if (!stops.isEmpty() && !inPit && stops.getLast().lap() != null && lastLap.get(e.getKey()) != null) {
                since = Math.max(0, lastLap.get(e.getKey()) - stops.getLast().lap());
            }
            out.add(new PitCar(e.getKey(), stops, total, finished == 0 ? null : total / finished, inPit, since));
        }
        out.sort(Comparator.comparingInt((PitCar c) -> -c.stops().size())
                .thenComparing(PitCar::carNumber, LiveAnalysis::byNumber));
        return out;
    }

    /** Car numbers by value, then as written: #4 before #04, #7 before #10. */
    static int byNumber(String a, String b) {
        try {
            int c = Integer.compare(Integer.parseInt(a), Integer.parseInt(b));
            return c != 0 ? c : Integer.compare(a.length(), b.length());
        } catch (NumberFormatException e) {
            return a.compareTo(b);
        }
    }
}
