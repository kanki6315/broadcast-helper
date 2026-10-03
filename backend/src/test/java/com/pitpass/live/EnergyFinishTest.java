package com.pitpass.live;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnergyFinishTest {

    private static final long T0 = 1_790_000_000_000L;
    private static final long HOUR = 3_600_000L;

    /** Green 1:50 at 3.0%, caution 2:40 at 1.0%, just across the line. */
    private static EnergyFinish.Car car(double startPct) {
        return new EnergyFinish.Car(startPct, 3.0, 110_000, 1.0, 160_000.0, T0, 0);
    }

    @Test
    void aTimedRaceNeedsCautionLapsBothForTheEnergyTheySaveAndTheClockTheyUse() {
        // An hour to the flag: 33 green laps (32.7, and the car must finish the one it is on) need 99%.
        var r = EnergyFinish.timed(car(75), T0, T0 + HOUR, 0);
        assertEquals(33, r.lapsToFlag(), 1e-9);
        assertEquals(99, r.needPct(), 1e-9);
        assertEquals(-24, r.marginPct(), 1e-9, "24% short at green pace");
        // 7 caution laps: 18:40 of the clock, 23 green laps after = 76%. 8: 22 green laps = 74%.
        assertEquals(8, r.cautionLaps());
        assertTrue(r.makesIt());
    }

    @Test
    void enoughEnergyNeedsNoCaution() {
        var r = EnergyFinish.timed(car(100), T0, T0 + HOUR, 0);
        assertEquals(0, r.cautionLaps());
        assertEquals(1, r.marginPct(), 1e-9);
        var reserved = EnergyFinish.timed(car(100), T0, T0 + HOUR, 2);
        assertEquals(-1, reserved.marginPct(), 1e-9, "a 2% reserve turns a 1% surplus into a 1% shortfall");
        assertEquals(1, reserved.cautionLaps());
    }

    @Test
    void thePartRunLapIsAlreadyPartUsed() {
        // Half a lap since the line: 32.5 laps of use still to come from this reading.
        var r = EnergyFinish.timed(car(75), T0 + 55_000, T0 + HOUR, 0);
        assertEquals(32.5, r.lapsToFlag(), 1e-9);
        assertEquals(97.5, r.needPct(), 1e-9);
    }

    @Test
    void noCautionFigureMeansNoCautionAnswer() {
        var noCaution = new EnergyFinish.Car(75, 3.0, 110_000, null, null, T0, 0);
        var r = EnergyFinish.timed(noCaution, T0, T0 + HOUR, 0);
        assertNull(r.cautionLaps());
        assertTrue(r.makesIt(), "not known not to");
    }

    @Test
    void tooLittleEnergyMakesItWithNoAmountOfCaution() {
        var r = EnergyFinish.timed(car(5), T0, T0 + HOUR, 0);
        assertNull(r.cautionLaps());
        assertFalse(r.makesIt());
    }

    @Test
    void theLeadersFlagIsItsFirstCrossingAfterTheClock() {
        assertEquals(T0 + 330_000, EnergyFinish.leaderFlagMs(T0 + 300_000, T0, 110_000));
        assertEquals(T0 + 400_000, EnergyFinish.leaderFlagMs(T0 + 300_000, T0 + 400_000, 110_000), "already past it");
    }

    @Test
    void aLapRaceCountsTheLeadersLapsLeftLessTheLapsDown() {
        var lapped = new EnergyFinish.Car(60, 3.0, 110_000, 1.0, 160_000.0, T0, 2);
        var r = EnergyFinish.laps(lapped, T0, 25, 0);
        assertEquals(23, r.lapsToFlag(), 1e-9);
        assertEquals(69, r.needPct(), 1e-9);
        // Each caution lap saves 2%: 9% short is 5 caution laps.
        assertEquals(5, r.cautionLaps());
        var hopeless = EnergyFinish.laps(new EnergyFinish.Car(10, 3.0, 110_000, 1.0, 160_000.0, T0, 0), T0, 25, 0);
        assertFalse(hopeless.makesIt(), "even 25 caution laps use 25%");
    }

    // ---- assembled from a session: LiveAnalysisService.finish / scenario ----------------------

    private static final java.util.List<String> GREEN3 = java.util.List.of("GREEN", "GREEN", "GREEN");

    private static EnergyModel.Lap lap(int n, float energy) {
        return new EnergyModel.Lap(n, GREEN3, null, null, 110_000, 1, energy, false);
    }

    /** #7: three green laps at 3%. #31: three green laps, a refill stop to 97.5%, then on: the leader, 6 laps. */
    private static java.util.Map<String, EnergyModel.Car> models() {
        var seven = EnergyModel.car(java.util.List.of(lap(0, 100), lap(1, 97), lap(2, 94), lap(3, 91)));
        var thirtyOne = EnergyModel.car(java.util.List.of(lap(0, 50), lap(1, 47), lap(2, 44), lap(3, 41),
                new EnergyModel.Lap(4, GREEN3, 1L, null, 140_000, 1, 97.5f, false),
                new EnergyModel.Lap(5, GREEN3, null, 1L, 130_000, 2, 94.5f, false), lap(6, 91.5f)));
        return java.util.Map.of("7", seven, "31", thirtyOne);
    }

    private static LiveTimingService.Session race(LiveTimingService.Clock clock) {
        return new LiveTimingService.Session("IMSA", "PLM", "Race", "RACE", "GREEN", true, false, clock, null);
    }

    private static final LiveAnalysisService.Crossings CROSSED = new LiveAnalysisService.Crossings(
            java.util.Map.of("7", T0, "31", T0 + 10_000), java.util.Map.of("7", 3, "31", 6), T0 + 10_000);

    private static LiveAnalysisService.EnergyCar row(String car, EnergyModel.Car m, Double now) {
        return LiveAnalysisService.energyCar(car, m, java.util.List.copyOf(models().values()), now);
    }

    @Test
    void aTimedRaceRunsToTheLeadersFlagFromTheNewestFeedTime() {
        var clock = new LiveTimingService.Clock("BY_TIME", T0 - HOUR, 2 * HOUR, null, null, null, 0, null);
        var inputs = new LiveAnalysisService.EnergyInputs(0, false, null, null);
        var finish = LiveAnalysisService.finish(race(clock), CROSSED, models(), inputs);
        assertEquals("TIME", finish.type());
        assertEquals("31", finish.leader(), "the most laps");
        assertEquals(HOUR - 10_000, finish.clockLeftMs());
        assertEquals(33 * 110_000L, finish.flagInMs(), "the leader's 33rd crossing from its last is the first after the clock");
        assertEquals(97.5, finish.refillPct(), 1e-6);
        assertEquals("OBSERVED", finish.refillSource());

        var seven = row("7", models().get("7"), 91.0);
        var s = LiveAnalysisService.scenario(seven, models().get("7"), finish, CROSSED);
        assertEquals(91.0, s.startPct());
        assertTrue(s.result().marginPct() < 0, "short on green: 34 crossings from its last");
        assertNull(s.result().cautionLaps(), "no caution laps anywhere, and no manual figure");
        assertNull(s.cautionSource());

        var manual = new LiveAnalysisService.EnergyInputs(2, true, 1.0, 160_000.0);
        var withManual = LiveAnalysisService.finish(race(clock), CROSSED, models(), manual);
        var m = LiveAnalysisService.scenario(seven, models().get("7"), withManual, CROSSED);
        assertEquals(97.5, m.startPct(), 1e-6, "from a stop now: the session's refill level");
        assertEquals("MANUAL", m.cautionSource());
        assertTrue(m.result().cautionLaps() != null && m.result().cautionLaps() > 0);
    }

    @Test
    void aLapRaceRunsToTheLeadersLapsLeft() {
        var clock = new LiveTimingService.Clock("BY_LAPS", T0 - HOUR, null, 20, null, null, 0, null);
        var finish = LiveAnalysisService.finish(race(clock), CROSSED, models(), LiveAnalysisService.EnergyInputs.NONE);
        assertEquals("LAPS", finish.type());
        assertEquals(14, finish.leaderLapsLeft());
        var s = LiveAnalysisService.scenario(row("7", models().get("7"), 91.0), models().get("7"), finish, CROSSED);
        assertEquals(11 - 10_000 / 110_000.0, s.result().lapsToFlag(), 1e-6, "three laps down: 11 to run, the one it is on part-run");
    }

    @Test
    void noFinishOutsideARaceOrUnderARedFlag() {
        var clock = new LiveTimingService.Clock("BY_TIME", T0 - HOUR, 2 * HOUR, null, null, null, 0, null);
        var practice = new LiveTimingService.Session("IMSA", "PLM", "Practice 1", "FREE_PRACTICE", "GREEN", true, false, clock, null);
        assertNull(LiveAnalysisService.finish(practice, CROSSED, models(), LiveAnalysisService.EnergyInputs.NONE));
        var red = new LiveTimingService.Clock("BY_TIME", T0 - HOUR, 2 * HOUR, null, null, T0, 0, null);
        assertNull(LiveAnalysisService.finish(race(red), CROSSED, models(), LiveAnalysisService.EnergyInputs.NONE));
    }
}
