package com.pitpass.live;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pitpass.live.LiveClassification.Entry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which event a session on track belongs to. Two series of one weekend share
 * car numbers but never classes, so number and class together decide.
 */
class LiveEventMatchTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private static Entry entry(long id, String number, String className) {
        return new Entry(id, number, className, "Team " + number, null, null, false);
    }

    // WeatherTech's entry list and Pilot Challenge's, both with a #7 and a #04.
    private final List<Entry> weatherTech = List.of(entry(1, "7", "GTP"), entry(2, "31", "GTP"),
            entry(3, "04", "LMP2"), entry(4, "64", "GTD PRO"), entry(5, "57", "GTD"));
    private final List<Entry> pilotChallenge = List.of(entry(11, "7", "GS"), entry(12, "31", "GS"),
            entry(13, "04", "TCR"), entry(14, "64", "GS"), entry(15, "57", "TCR"));

    private static final String WEATHERTECH_ON_TRACK = """
            {"standings": {"byClass": {"active": {
              "GTP": {"class": "GTP", "standings": {"1": {"participant": "7"}, "2": {"participant": "31"}}},
              "LMP2": {"class": "LMP2", "standings": {"1": {"participant": "04"}}},
              "GTDPRO": {"class": "GTDPRO", "standings": {"1": {"participant": "64"}}},
              "GTD": {"class": "GTD", "standings": {"1": {"participant": "57"}, "2": {"participant": "99"}}}}}}}
            """;

    @Test
    void theRightSeriesMatchesAndTheOtherDoesNotDespiteSharedNumbers() throws Exception {
        var session = mapper.readTree(WEATHERTECH_ON_TRACK);
        var right = LiveEventMatch.score(session, weatherTech, Map.of());
        assertEquals(6, right.total());
        assertEquals(5, right.agreeing(), "GTDPRO on the feed is GTD PRO entered; the late #99 is the one miss");
        assertTrue(right.matches());

        var wrong = LiveEventMatch.score(session, pilotChallenge, Map.of());
        assertEquals(0, wrong.agreeing(), "every number is there, in the wrong class");
        assertFalse(wrong.matches());
    }

    @Test
    void theSeriesClassAliasesCount() throws Exception {
        var session = mapper.readTree("""
                {"standings": {"byClass": {"active": {
                  "PRO": {"class": "PRO", "standings": {"1": {"participant": "64"}}}}}}}
                """);
        assertFalse(LiveEventMatch.score(session, weatherTech, Map.of()).matches());
        assertTrue(LiveEventMatch.score(session, weatherTech, Map.of("pro", "GTD PRO")).matches());
    }

    @Test
    void beforeTheRunningOrderTheEntryChannelDecides() throws Exception {
        var session = mapper.readTree("""
                {"entry": {"7": {"number": "7", "class": "GS"}, "04": {"number": "04", "class": "TCR"}}}
                """);
        assertTrue(LiveEventMatch.score(session, pilotChallenge, Map.of()).matches());
        assertFalse(LiveEventMatch.score(session, weatherTech, Map.of()).matches());
    }

    @Test
    void nothingOnTrackMatchesNothing() throws Exception {
        assertFalse(LiveEventMatch.score(mapper.readTree("{}"), weatherTech, Map.of()).matches());
        assertFalse(LiveEventMatch.score(null, weatherTech, Map.of()).matches());
        assertFalse(LiveEventMatch.score(mapper.readTree(WEATHERTECH_ON_TRACK), List.of(), Map.of()).matches());
    }

    @Test
    void classNamesCompareAsIMSAWritesThem() {
        assertTrue(LiveEventMatch.sameClass("GTD PRO", "GTDPRO"));
        assertTrue(LiveEventMatch.sameClass("gtd-pro", "GTD Pro"));
        assertFalse(LiveEventMatch.sameClass("GTD", "GTD PRO"));
        assertFalse(LiveEventMatch.sameClass("", ""));
    }
}
