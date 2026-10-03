package com.pitpass.live;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.pitpass.live.EnergyModel.Kind.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnergyModelTest {

    private static final List<String> GREEN3 = List.of("GREEN", "GREEN", "GREEN");
    private static final List<String> FCY3 = List.of("FULL_YELLOW", "FULL_YELLOW", "FULL_YELLOW");

    private static EnergyModel.Lap lap(int n, List<String> flags, Float energy) {
        return new EnergyModel.Lap(n, flags, null, null, 75_000, 1, energy, false);
    }

    private static List<EnergyModel.Kind> kinds(EnergyModel.Car car) {
        return car.laps().stream().map(EnergyModel.Classified::kind).toList();
    }

    @Test
    void eachLapIsGreenCautionOrLeftOutWithAReason() {
        List<EnergyModel.Lap> laps = List.of(
                new EnergyModel.Lap(0, null, null, null, null, null, 100f, false),       // the line before lap 1
                lap(1, GREEN3, 97.8f),
                new EnergyModel.Lap(2, GREEN3, 1L, null, 90_000, 1, 95.5f, false),         // pits
                new EnergyModel.Lap(3, GREEN3, null, null, 80_000, 2, 97.5f, false),       // out of the pits
                lap(4, GREEN3, 95.3f),
                lap(5, List.of("GREEN", "FULL_YELLOW", "FULL_YELLOW"), 94f),               // the caution came out
                lap(6, FCY3, 93.2f),
                lap(7, List.of("RED", "RED", "RED"), 93f),
                lap(8, List.of("GREEN", "GREEN", "SAFETY_CAR"), 91f),                     // a value never seen
                lap(9, null, 89f),
                lap(11, GREEN3, 85f));                                                       // lap 10 never read
        assertEquals(List.of(GREEN, PIT, OUT_LAP, GREEN, FLAG_CHANGE, CAUTION, RED, FLAG_UNKNOWN,
                FLAG_UNKNOWN, NO_READING), kinds(EnergyModel.car(laps)), "lap 0 is the reading lap 1 starts from, not a lap");
    }

    @Test
    void thePitLaneAtEitherLineMakesItAPitLap() {
        var car = EnergyModel.car(List.of(
                new EnergyModel.Lap(1, GREEN3, null, null, 75_000, 1, 80f, true),
                lap(2, GREEN3, 78f),
                lap(3, GREEN3, 76f)));
        assertEquals(List.of(NO_READING, PIT, GREEN), kinds(car));
    }

    @Test
    void aRiseWithNoPitMarkIsARefillNotNegativeUse() {
        // Refills land at ~96-98%, and a red flag's pit-lane laps can arrive without pit marks.
        var car = EnergyModel.car(List.of(lap(1, GREEN3, 60f), lap(2, GREEN3, 97.8f), lap(3, GREEN3, 95.6f)));
        assertEquals(List.of(NO_READING, REFILL, GREEN), kinds(car));
        assertNull(car.laps().get(1).usedPct(), "a rise is not negative use");
    }

    @Test
    void aLapOnAnEmptyMeterSaysNothing() {
        // Energy floors at 0% and the car keeps lapping at full pace (Petit Le Mans practice).
        var car = EnergyModel.car(List.of(lap(1, GREEN3, 2f), lap(2, GREEN3, 0f), lap(3, GREEN3, 0f)));
        assertEquals(List.of(NO_READING, EMPTY, EMPTY), kinds(car));
    }

    @Test
    void greenUseIsTheLastTenGreenLapsAcrossPitStops() {
        List<EnergyModel.Lap> laps = new ArrayList<>();
        float e = 100;
        laps.add(lap(0, null, e));
        for (int n = 1; n <= 8; n++) {                      // stint one: 3% a lap
            laps.add(lap(n, GREEN3, e -= 3));
        }
        laps.add(new EnergyModel.Lap(9, GREEN3, 1L, null, 90_000, 1, e = 96f, false));
        laps.add(new EnergyModel.Lap(10, GREEN3, null, 1L, 90_000, 2, e -= 3, false));
        for (int n = 11; n <= 14; n++) {                    // stint two, a new driver: 2% a lap
            laps.add(new EnergyModel.Lap(n, GREEN3, null, null, 74_000, 2, e -= 2, false));
        }
        var car = EnergyModel.car(laps);
        assertEquals(10, car.green().laps());
        assertEquals((6 * 3 + 4 * 2) / 10.0, car.green().perLapPct(), 1e-4, "laps 3-8 and 11-14");
        assertTrue(car.green().driverChange());
        assertEquals(14, car.green().lastLap());
        assertEquals((6 * 75_000 + 4 * 74_000) / 10.0, car.green().lapTimeMs(), 1e-6);
        assertEquals(2.2, car.greenShort().perLapPct(), 1e-4, "the last five: 8 and 11-14");
        assertNull(car.caution());
        assertEquals(78 / 2.6, EnergyModel.greenLapsLeft(78.0, car.green()), 1e-6);
        assertNull(EnergyModel.greenLapsLeft(null, car.green()), "stale telemetry projects nothing");
    }

    @Test
    void fewerThanThreeLapsIsNoFigure() {
        var two = EnergyModel.car(List.of(lap(0, null, 100f), lap(1, GREEN3, 98f), lap(2, GREEN3, 96f)));
        assertNull(two.green());
        assertNull(EnergyModel.greenLapsLeft(96.0, two.green()));
        var three = EnergyModel.car(List.of(lap(0, null, 100f), lap(1, GREEN3, 98f), lap(2, GREEN3, 96f),
                lap(3, GREEN3, 94f)));
        assertEquals(3, three.green().laps());
        assertFalse(three.green().driverChange());
    }

    @Test
    void cautionUseFallsBackToTheClassPool() {
        var thin = EnergyModel.car(List.of(lap(0, null, 50f), lap(1, FCY3, 49f), lap(2, FCY3, 48f)));
        var other = EnergyModel.car(List.of(lap(0, null, 60f), lap(1, FCY3, 59.5f), lap(2, FCY3, 59f),
                lap(3, FCY3, 58.5f)));
        assertNull(thin.caution(), "two caution laps of its own are not enough");
        assertEquals(0.5, other.caution().perLapPct(), 1e-6);

        var forThin = LiveEnergy.caution(thin, List.of(thin, other));
        assertEquals(EnergyModel.Source.CLASS, forThin.source());
        assertEquals((1 + 1 + 0.5 * 3) / 5.0, forThin.average().perLapPct(), 1e-6, "all five of the class's caution laps");
        assertEquals(EnergyModel.Source.CAR, LiveEnergy.caution(other, List.of(thin, other)).source());
        assertNull(LiveEnergy.caution(thin, List.of(thin)).average());
    }

    @Test
    void aStintAveragesItsOwnGreenLaps() {
        var car = EnergyModel.car(List.of(lap(0, null, 100f), lap(1, GREEN3, 97f), lap(2, GREEN3, 94f),
                lap(3, GREEN3, 91f), lap(4, FCY3, 90f), lap(5, GREEN3, 88f)));
        assertEquals(3.0, EnergyModel.stint(car.laps(), 1, 3).perLapPct(), 1e-6);
        assertEquals((3 * 3 + 2) / 4.0, EnergyModel.stint(car.laps(), 1, null).perLapPct(), 1e-6, "open: to the newest lap");
        assertNull(EnergyModel.stint(car.laps(), 4, null), "one green lap");
    }
}
