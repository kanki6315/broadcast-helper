package com.pitpass.live;

import com.pitpass.live.LiveAnalysis.Lap;
import com.pitpass.live.LiveAnalysis.Stint;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiveAnalysisTest {

    private static final long T0 = 1_769_000_000_000L;

    /** A car's laps, all starting at T0, with the given lap times. */
    private static List<Lap> run(String car, int... times) {
        List<Lap> laps = new ArrayList<>();
        long start = T0;
        for (int i = 0; i < times.length; i++) {
            laps.add(new Lap(car, i + 1, 1, start, times[i], null, null, false));
            start += times[i];
        }
        return laps;
    }

    @SafeVarargs
    private static List<Lap> all(List<Lap>... cars) {
        return Arrays.stream(cars).flatMap(List::stream).toList();
    }

    // ---- gaps ---------------------------------------------------------------------------

    @Test
    void theGapIsHowMuchLaterACarFinishedTheSameLap() {
        var gaps = LiveAnalysis.gaps(all(run("7", 100_000, 100_000, 100_000), run("04", 101_000, 100_500, 99_000)));
        assertEquals("7", gaps.get(0).carNumber(), "the leader first");
        assertEquals(Arrays.asList(0L, 0L, 0L), gaps.get(0).gapMs());
        assertEquals(Arrays.asList(1_000L, 1_500L, 500L), gaps.get(1).gapMs());
        assertEquals(List.of(0, 0, 0), gaps.get(1).lapsDown());
    }

    @Test
    void theLeaderIsWhoeverFinishedEachLapFirst() {
        // #04 leads lap 1 by a second, #7 is ahead by the end of lap 2.
        var gaps = LiveAnalysis.gaps(all(run("7", 101_000, 98_000), run("04", 100_000, 100_000)));
        var seven = gaps.stream().filter(g -> g.carNumber().equals("7")).findFirst().orElseThrow();
        var zero4 = gaps.stream().filter(g -> g.carNumber().equals("04")).findFirst().orElseThrow();
        assertEquals(Arrays.asList(1_000L, 0L), seven.gapMs());
        assertEquals(Arrays.asList(0L, 1_000L), zero4.gapMs());
        assertEquals("7", gaps.get(0).carNumber(), "running order: the earlier to finish lap 2");
    }

    @Test
    void aLappedCarHasLapsDownAndNoGapInSeconds() {
        // #9 finishes lap 1 at 150 s, when the leader has done one lap; lap 2 at 310 s, when it has done three.
        var gaps = LiveAnalysis.gaps(all(run("1", 100_000, 100_000, 100_000, 100_000), run("9", 150_000, 160_000)));
        var nine = gaps.get(1);
        assertEquals("9", nine.carNumber());
        assertEquals(Arrays.asList(50_000L, null), nine.gapMs());
        assertEquals(List.of(0, 1), nine.lapsDown());
    }

    @Test
    void anUntimedLapLeavesAHoleAndAPitLapIsMarked() {
        List<Lap> laps = new ArrayList<>(run("1", 100_000, 100_000, 100_000));
        laps.add(new Lap("2", 1, 1, T0, 100_500, null, null, false));
        laps.add(new Lap("2", 2, 1, T0 + 100_500, null, null, null, true));
        laps.add(new Lap("2", 3, 1, T0 + 230_000, 71_000, null, null, false));
        var two = LiveAnalysis.gaps(laps).get(1);
        assertEquals(Arrays.asList(500L, null, 1_000L), two.gapMs());
        assertEquals(Arrays.asList(0, null, 0), two.lapsDown());
        assertEquals(List.of(2), two.pitLaps());
    }

    // ---- sectors ------------------------------------------------------------------------

    private static Lap sectors(String car, int lap, Boolean valid, Integer... ms) {
        int time = Arrays.stream(ms).mapToInt(m -> m == null ? 0 : m).sum();
        return new Lap(car, lap, 1, T0, time, Arrays.asList(ms), valid, false);
    }

    @Test
    void bestSectorsComeFromValidLapsAndAddUpToTheTheoreticalBest() {
        var bests = LiveAnalysis.sectorBests(List.of(
                sectors("7", 1, true, 30_000, 31_000, 32_000),
                sectors("7", 2, true, 29_500, 31_500, 31_800),
                sectors("7", 3, false, 28_000, 28_000, 28_000),
                sectors("04", 1, null, 29_800, 30_900, 32_500)));
        assertEquals(3, bests.sectors());
        assertEquals(Arrays.asList(29_500, 30_900, 31_800), bests.classBestSectorMs(), "the invalid lap 3 counts for nothing");
        var seven = bests.cars().stream().filter(c -> c.carNumber().equals("7")).findFirst().orElseThrow();
        assertEquals(Arrays.asList(29_500, 31_000, 31_800), seven.bestSectorMs());
        assertEquals(Arrays.asList(2, 1, 2), seven.bestSectorLap());
        assertEquals(92_300L, seven.theoreticalMs());
        assertEquals(2, seven.bestLap(), "92.8 s on lap 2 beats 93.0 on lap 1; lap 3's 84.0 was invalid");
        assertEquals(92_800, seven.bestLapMs());
    }

    @Test
    void noTheoreticalBestWithoutABestInEverySector() {
        var bests = LiveAnalysis.sectorBests(List.of(
                sectors("7", 1, true, 30_000, 31_000, 32_000),
                sectors("9", 1, true, 30_000, null, null)));
        var nine = bests.cars().get(1);
        assertEquals("9", nine.carNumber(), "a car with no theoretical best sorts after one with");
        assertNull(nine.theoreticalMs());
        assertEquals(Arrays.asList(30_000, null, null), nine.bestSectorMs());
    }

    // ---- pits ---------------------------------------------------------------------------

    @Test
    void stopsCarryPitLaneTimeLapAndTheDriverChange() {
        var cars = LiveAnalysis.pitStops(List.of(
                new Stint("7", T0, "TRACK", null, 1, 1, 30, T0 + 3_000_000),
                new Stint("7", T0 + 3_000_000, "PIT", null, 1, 30, 31, T0 + 3_065_000),
                new Stint("7", T0 + 3_065_000, "TRACK", null, 2, 31, 60, T0 + 6_000_000),
                new Stint("7", T0 + 6_000_000, "PIT", "PENALTY", 2, 60, 61, T0 + 6_035_000),
                new Stint("7", T0 + 6_035_000, "TRACK", null, 2, 61, null, null),
                new Stint("04", T0, "TRACK", null, 1, 1, 40, T0 + 4_000_000),
                new Stint("04", T0 + 4_000_000, "PIT", null, 1, 40, null, null)),
                Map.of("7", 72, "04", 40));
        var seven = cars.get(0);
        assertEquals("7", seven.carNumber(), "most stops first");
        assertEquals(2, seven.stops().size());
        var first = seven.stops().get(0);
        assertEquals(65_000L, first.durationMs());
        assertEquals(30, first.lap());
        assertTrue(first.driverChange(), "driver 1 in, driver 2 out");
        var second = seven.stops().get(1);
        assertEquals("PENALTY", second.pitType());
        assertFalse(second.driverChange());
        assertEquals(100_000L, seven.totalMs());
        assertEquals(50_000L, seven.averageMs());
        assertEquals(12, seven.lapsSinceStop());
        assertFalse(seven.inPit());

        var zero4 = cars.get(1);
        assertTrue(zero4.inPit());
        assertNull(zero4.stops().getFirst().durationMs(), "still in the pit lane");
        assertNull(zero4.stops().getFirst().driverOut());
        assertNull(zero4.averageMs(), "no finished stop yet");
        assertNull(zero4.lapsSinceStop());
    }

    @Test
    void carNumbersSortByValueThenAsWritten() {
        List<String> numbers = new ArrayList<>(List.of("10", "04", "4", "7", "A1"));
        numbers.sort(LiveAnalysis::byNumber);
        assertEquals(List.of("4", "04", "7", "10", "A1"), numbers);
    }
}
