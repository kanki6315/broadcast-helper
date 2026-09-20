package com.pitpass.live;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pitpass.live.LiveClassification.Entry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The feed's per-class order against an event's entries. The feed side uses
 * the field names of the AKS V2 spec (byClass standings for a race); the
 * numbers and spellings are the awkward ones real IMSA data has.
 */
class LiveClassificationTest {

    private static final List<Entry> ENTRIES = List.of(
            new Entry(1, "7", "GTP", "Porsche Penske Motorsport", "Porsche 963", "Porsche", false),
            new Entry(2, "31", "GTP", "Cadillac Whelen", "Cadillac V-Series.R", "Cadillac", false),
            new Entry(3, "85", "GTP", "JDC-Miller MotorSports", "Porsche 963", "Porsche", false),
            new Entry(4, "04", "LMP2", "CrowdStrike Racing by APR", "ORECA LMP2 07", "ORECA", false),
            new Entry(5, "3", "GTD PRO", "Corvette Racing by Pratt Miller", "Corvette Z06 GT3.R", "Chevrolet", false),
            new Entry(6, "77", "GTD PRO", "AO Racing", "Porsche 911 GT3 R", "Porsche", true),
            new Entry(7, "57", "GTD", "Winward Racing", "Mercedes-AMG GT3", "Mercedes-AMG", false));

    private static JsonNode session(String json) throws Exception {
        return new ObjectMapper().readTree(json);
    }

    private static final String RACE = """
            {
              "classes": {"1": {"name": "GTP", "order": 1}, "2": {"name": "LMP2", "order": 2},
                          "3": {"name": "GTDPRO", "order": 3}},
              "entry": {"99": {"team": "Late Entry Racing", "vehicle": "Acura ARX-06", "class": "GTP"}},
              "standings": {"byClass": {"active": {
                "GTDPRO": {"class": "GTDPRO", "standings": {
                  "1": {"participant": "77", "position": 1, "status": "CLASSIFIED", "lapNumber": 61},
                  "2": {"participant": "3", "position": 2, "status": "CLASSIFIED", "lapNumber": 61, "gapFirstTime": 4210}}},
                "LMP2": {"class": "LMP2", "standings": {
                  "1": {"participant": "4", "position": 1, "status": "CLASSIFIED", "lapNumber": 68}}},
                "GTP": {"class": "GTP", "standings": {
                  "2": {"participant": "7", "position": 2, "status": "CLASSIFIED", "lapNumber": 72, "gapFirstTime": 1830},
                  "1": {"participant": "31", "position": 1, "status": "CLASSIFIED", "lapNumber": 72},
                  "3": {"participant": "99", "position": 3, "status": "CLASSIFIED", "lapNumber": 72, "gapFirstTime": 9000},
                  "4": {"participant": "85", "position": 4, "status": "RETIRED", "lapNumber": 40, "gapFirstLaps": -32}}}
              }}}
            }
            """;

    @Test
    void ordersEachClassByPositionAndClassesAsTheFeedDoes() throws Exception {
        var result = LiveClassification.build(session(RACE), ENTRIES, Map.of(), Map.of());

        assertEquals(List.of("GTP", "LMP2", "GTD PRO"), result.classes().stream().map(c -> c.className()).toList());
        var gtp = result.classes().get(0);
        assertEquals(List.of("31", "7", "99", "85"), gtp.cars().stream().map(c -> c.carNumber()).toList());
        assertEquals("Cadillac Whelen", gtp.cars().get(0).teamName());
        assertNull(gtp.cars().get(0).gapToLeaderMs());
        assertEquals(1830L, gtp.cars().get(1).gapToLeaderMs());
        assertEquals("RETIRED", gtp.cars().get(3).status());
        assertEquals(32, gtp.cars().get(3).gapToLeaderLaps());
        assertNull(gtp.cars().get(3).gapToLeaderMs());
    }

    @Test
    void aFeedClassTakesTheNameItsCarsAreEnteredUnder() throws Exception {
        var result = LiveClassification.build(session(RACE), ENTRIES, Map.of(), Map.of());
        var pro = result.classes().get(2);
        assertEquals("GTD PRO", pro.className());
        assertEquals("GTDPRO", pro.feedClass());
        assertTrue(pro.cars().get(0).guest(), "guest status rides along for the calculator to use or ignore");
        assertTrue(result.classMismatches().isEmpty(), "a spelling difference is not a mismatch");
    }

    @Test
    void leadingZerosDoNotSplitACar() throws Exception {
        var result = LiveClassification.build(session(RACE), ENTRIES, Map.of(), Map.of());
        var lmp2 = result.classes().get(1).cars().get(0);
        assertEquals("4", lmp2.carNumber(), "shown as the feed shows it");
        assertEquals(4L, lmp2.entryId());
        assertEquals("4", lmp2.competitorKey());
    }

    @Test
    void numbersThatDifferOnlyByAZeroAreDifferentCarsWhenTheGridHasBoth() throws Exception {
        // Daytona really does have #04 (LMP2) and #4 (GTD PRO), #23 (GTP) and #023 (GTD).
        var grid = List.of(
                new Entry(1, "04", "LMP2", "CrowdStrike Racing by APR", null, null, false),
                new Entry(2, "4", "GTD PRO", "Corvette Racing by Pratt Miller", null, null, false),
                new Entry(3, "23", "GTP", "Aston Martin THOR Team", null, null, false),
                new Entry(4, "023", "GTD", "Triarsi Competizione", null, null, false));
        var feed = session("""
                {"standings": {"byClass": {"active": {
                  "LMP2": {"class": "LMP2", "standings": {"1": {"participant": "04", "position": 1}}},
                  "GTDPRO": {"class": "GTDPRO", "standings": {"1": {"participant": "4", "position": 1}}},
                  "GTP": {"class": "GTP", "standings": {"1": {"participant": "23", "position": 1},
                                                        "2": {"participant": "0023", "position": 2}}},
                  "GTD": {"class": "GTD", "standings": {"1": {"participant": "023", "position": 1}}}}}}}
                """);
        var result = LiveClassification.build(feed, grid, Map.of(), Map.of());

        assertTrue(result.classMismatches().isEmpty(), result.classMismatches().toString());
        assertEquals(4, result.matched());
        assertEquals("CrowdStrike Racing by APR", result.classes().get(0).cars().get(0).teamName());
        assertEquals("Corvette Racing by Pratt Miller", result.classes().get(1).cars().get(0).teamName());
        assertEquals("Triarsi Competizione", result.classes().get(3).cars().get(0).teamName());
        // "0023" is neither car exactly and could be either loosely: left unmatched, not guessed.
        assertEquals(List.of("0023"), result.unmatched().stream().map(u -> u.carNumber()).toList());
        assertTrue(result.missing().isEmpty());
    }

    @Test
    void theStandingsKeyFollowsTheSeasonsCarNumberAlias() throws Exception {
        // JDC-Miller's #5 ran Daytona as #85: the standings row is keyed 5.
        var result = LiveClassification.build(session(RACE), ENTRIES,
                Map.of(LiveClassification.aliasKey("GTP", "85"), "5"), Map.of());
        var jdc = result.classes().get(0).cars().get(3);
        assertEquals("85", jdc.carNumber());
        assertEquals("5", jdc.competitorKey());
    }

    @Test
    void whatDoesNotLineUpIsReportedNotDropped() throws Exception {
        var result = LiveClassification.build(session(RACE), ENTRIES, Map.of(), Map.of());

        assertEquals(7, result.total());
        assertEquals(6, result.matched());

        assertEquals(1, result.unmatched().size());
        var late = result.unmatched().get(0);
        assertEquals("99", late.carNumber());
        assertEquals("GTP", late.feedClass());
        assertEquals(3, late.position());
        assertEquals("Late Entry Racing", late.teamName(), "described from the feed's own entry list");
        // and it still holds its place in the order — the cars behind it are not promoted
        assertEquals(4, result.classes().get(0).cars().get(3).position());

        assertEquals(List.of("57"), result.missing().stream().map(m -> m.carNumber()).toList());
    }

    @Test
    void aCarRunningOutsideItsEnteredClassIsFlagged() throws Exception {
        var moved = session("""
                {"standings": {"byClass": {"active": {"GTD": {"class": "GTD", "standings": {
                  "1": {"participant": "57", "position": 1},
                  "2": {"participant": "3", "position": 2},
                  "3": {"participant": "77", "position": 3, "gapFirstTime": 1}}}}}}}
                """);
        var result = LiveClassification.build(moved, ENTRIES, Map.of(), Map.of());
        // two of the three are entered in GTD PRO, so that is what the class is called…
        assertEquals("GTD PRO", result.classes().get(0).className());
        // …and the odd one out is named.
        assertEquals(1, result.classMismatches().size());
        assertEquals("57", result.classMismatches().get(0).carNumber());
        assertEquals("GTD", result.classMismatches().get(0).enteredClass());
    }

    @Test
    void theWrongSeriesOnTrackReadsAsNothingMatchedNotEverythingMissing() throws Exception {
        var pilotChallenge = session("""
                {"standings": {"byClass": {"active": {"GS": {"class": "GS", "standings": {
                  "1": {"participant": "28", "position": 1}, "2": {"participant": "95", "position": 2}}}}}}}
                """);
        var result = LiveClassification.build(pilotChallenge, ENTRIES, Map.of(), Map.of("gs", "Grand Sport"));
        assertEquals(0, result.matched());
        assertEquals(2, result.total());
        assertEquals(2, result.unmatched().size());
        assertTrue(result.missing().isEmpty());
        assertEquals("Grand Sport", result.classes().get(0).className(), "with no car to go by, the series' class alias names it");
    }

    @Test
    void noFeedOrNoOrderYetIsEmptyNotAnError() throws Exception {
        assertTrue(LiveClassification.build(null, ENTRIES, Map.of(), Map.of()).classes().isEmpty());
        assertTrue(LiveClassification.build(session("{\"info\":{\"name\":\"Race\"}}"), ENTRIES, Map.of(), Map.of())
                .classes().isEmpty());
        assertFalse(LiveClassification.build(session(RACE), List.of(), Map.of(), Map.of()).classes().isEmpty(),
                "an event with no entries yet still shows the order");
    }
}
