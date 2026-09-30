package com.pitpass.live;

import com.pitpass.live.DriveTime.Driver;
import com.pitpass.live.DriveTime.Result;
import com.pitpass.live.DriveTime.Status;
import com.pitpass.live.DriveTime.Stint;
import com.pitpass.live.DriveTimeRuleController.Rule;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DriveTimeTest {

    private static final long H = 3_600_000L;
    private static final long T0 = 1_769_000_000_000L;

    @Test
    void theLatestAccumulatorIsTheDriversTotal() {
        List<Stint> stints = List.of(
                new Stint("7", T0, "TRACK", 1, T0 + H, H),
                new Stint("7", T0 + H, "PIT", 1, T0 + H + 60_000, H),
                new Stint("7", T0 + H + 60_000, "TRACK", 2, T0 + 3 * H, 2 * H - 60_000),
                new Stint("7", T0 + 3 * H, "TRACK", 1, T0 + 4 * H, 2 * H));
        assertEquals(2 * H, DriveTime.driveMs(stints.stream().filter(s -> s.driverOrder() == 1).toList(), T0 + 5 * H));
        assertEquals(2 * H - 60_000, DriveTime.driveMs(stints.stream().filter(s -> s.driverOrder() == 2).toList(), T0 + 5 * H));
    }

    @Test
    void anOpenStintWithoutItsOwnAccumulatorAddsItsElapsedTime() {
        List<Stint> own = List.of(
                new Stint("7", T0, "TRACK", 1, T0 + H, H),
                new Stint("7", T0 + 2 * H, "TRACK", 1, null, null));
        assertEquals(H + 30 * 60_000, DriveTime.driveMs(own, T0 + 2 * H + 30 * 60_000));
    }

    @Test
    void anOpenStintThatCarriesAnAccumulatorIsTakenAsLive() {
        List<Stint> own = List.of(new Stint("7", T0, "TRACK", 1, null, 20 * 60_000L));
        assertEquals(20 * 60_000, DriveTime.driveMs(own, T0 + H));
    }

    @Test
    void anOpenPitStintAddsNothing() {
        List<Stint> own = List.of(
                new Stint("7", T0, "TRACK", 1, T0 + H, H),
                new Stint("7", T0 + H, "PIT", 1, null, null));
        assertEquals(H, DriveTime.driveMs(own, T0 + 2 * H));
    }

    @Test
    void statusesAgainstTheRuleForTheDriversRating() {
        List<Rule> rules = List.of(
                new Rule("GTD", null, null, 4 * H, "max 4h"),
                new Rule("GTD", "B", 2 * H, 4 * H, "bronze min 2h"));
        List<Driver> drivers = List.of(
                new Driver("7", 1, "Bronze Driver", "B", 11L, "GTD"),
                new Driver("7", 2, "Gold Driver", "G", 12L, "GTD"),
                new Driver("8", 1, "Proto Driver", "P", 13L, "GTP"));
        List<Stint> stints = List.of(
                new Stint("7", T0, "TRACK", 1, T0 + H, H),
                new Stint("7", T0 + H, "TRACK", 2, T0 + 6 * H, 5 * H),
                new Stint("7", T0 + 6 * H, "TRACK", 1, null, null),
                new Stint("8", T0, "TRACK", 1, null, 3 * H),
                new Stint("8", T0 + H, "TRACK", 9, T0 + 2 * H, H));

        List<Result> results = DriveTime.compute(stints, drivers, rules, T0 + 6 * H + 30 * 60_000);
        Result bronze = results.get(0);
        assertEquals(Status.UNDER_MIN, bronze.status());
        assertEquals(H + 30 * 60_000, bronze.driveMs());
        assertEquals(30 * 60_000L, bronze.owedMs());
        assertTrue(bronze.inCar());
        assertEquals(2 * H, bronze.minMs(), "the bronze rule, not the class-wide one");

        Result gold = results.get(1);
        assertEquals(Status.OVER_MAX, gold.status());
        assertEquals(H, gold.overMs());
        assertEquals(-H, gold.remainingMs());
        assertFalse(gold.inCar());

        Result proto = results.get(2);
        assertEquals(Status.NO_RULE, proto.status());
        assertNull(proto.minMs());

        Result unnamed = results.get(3);
        assertEquals("8", unnamed.car());
        assertEquals(9, unnamed.driverOrder());
        assertNull(unnamed.name(), "a driver order the entry data never named is still counted");
        assertEquals("GTP", unnamed.className());
        assertEquals(H, unnamed.driveMs());
    }

    @Test
    void aRatingRuleTakesItsMissingBoundFromTheClassRule() {
        List<Rule> rules = List.of(new Rule("GTD", null, null, 4 * H, "everyone"), new Rule("GTD", "B", 2 * H, null, null));
        assertEquals(new Rule("GTD", "B", 2 * H, 4 * H, "everyone"), DriveTime.rule(rules, "GTD", "Bronze"));
        assertEquals(rules.get(0), DriveTime.rule(rules, "GTD", "S"));
        assertNull(DriveTime.rule(rules, "GTP", "B"));
    }

    @Test
    void classNamesMatchIgnoringCaseAndSpaces() {
        Rule r = new Rule("GTD PRO", null, null, H, null);
        assertEquals(r, DriveTime.rule(List.of(r), "gtdpro", "Silver"));
    }
}
