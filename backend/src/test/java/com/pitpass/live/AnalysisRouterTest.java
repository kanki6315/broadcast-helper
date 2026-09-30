package com.pitpass.live;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pitpass.live.AnalysisRows.EntriesChanged;
import com.pitpass.live.AnalysisRows.LapDeleted;
import com.pitpass.live.AnalysisRows.LapPatch;
import com.pitpass.live.AnalysisRows.Op;
import com.pitpass.live.AnalysisRows.SessionSeen;
import com.pitpass.live.AnalysisRows.StintDeleted;
import com.pitpass.live.AnalysisRows.StintPatch;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static com.pitpass.live.AnalysisFixtures.SESSION;
import static com.pitpass.live.AnalysisFixtures.info;
import static com.pitpass.live.AnalysisFixtures.lap;
import static com.pitpass.live.AnalysisFixtures.lapsDiff;
import static com.pitpass.live.AnalysisFixtures.stint;
import static com.pitpass.live.AnalysisFixtures.stintsDiff;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The router over synthetic frames: what becomes a patch, what reaches the tree, and what never does. */
class AnalysisRouterTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final AksStateTree tree = new AksStateTree();
    private final List<Op> ops = new ArrayList<>();
    private final LiveCarSummaries summaries = new LiveCarSummaries();
    private int capacity = Integer.MAX_VALUE;
    private final AnalysisRouter router = new AnalysisRouter(mapper, tree,
            op -> ops.size() < capacity && ops.add(op), summaries);

    private void feed(String json) throws Exception {
        feed("", json);
    }

    private void feed(String channel, String json) throws Exception {
        router.frame("", channel, new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
    }

    private <T extends Op> List<T> ops(Class<T> type) {
        return ops.stream().filter(type::isInstance).map(type::cast).toList();
    }

    @Test
    void aSnapshotBecomesLapPatchesAndNeverEntersTheTree() throws Exception {
        feed(info(SESSION, "Race"));
        feed(lapsDiff("7", "\"1\":" + lap(1, 1, 98_765, 20) + ",\"2\":" + lap(2, 1, 97_000, 20)));

        List<LapPatch> laps = ops(LapPatch.class);
        assertEquals(2, laps.size());
        LapPatch first = laps.get(0);
        assertEquals(SESSION, first.sessionDbId);
        assertEquals("7", first.car);
        assertEquals(1, first.lap);
        assertEquals(1, first.driverOrder);
        assertEquals(98_765, first.lapTimeMs);
        assertEquals(298.7, first.topSpeed);
        assertEquals(Boolean.TRUE, first.valid);
        assertEquals(0, first.trackLimits);
        assertEquals(3, first.sectors.size(), "loopSectors and sections are not sectors");
        assertEquals(32_921, first.sectors.get(1).timeMs);
        assertEquals("YELLOW", first.sectors.get(3).flag);

        assertNull(tree.copyOf("timing.analysis"), "analysis never reaches the state tree");
        assertEquals("Race", tree.copyOf("timing.session.info").path("name").asText());

        SessionSeen seen = ops(SessionSeen.class).getFirst();
        assertEquals(SESSION, seen.session().sessionDbId());
        assertEquals("RACE", seen.session().type());
        assertEquals(2, router.laps());
    }

    @Test
    void aPartialDiffCarriesOnlyWhatItNames() throws Exception {
        feed(info(SESSION, "Race"));
        feed(lapsDiff("7", "\"3\":{\"time\":96500,\"isValid\":null,\"sectors\":{\"2\":{\"time\":32100},\"3\":null}}"));

        LapPatch patch = ops(LapPatch.class).getFirst();
        assertTrue(patch.has(LapPatch.TIME));
        assertTrue(patch.has(LapPatch.VALID), "an explicit null is a value: it clears the column");
        assertNull(patch.valid);
        assertFalse(patch.has(LapPatch.DRIVER));
        assertFalse(patch.has(LapPatch.START));
        assertTrue(patch.sectors.get(2).hasTime);
        assertFalse(patch.sectors.get(2).hasFlag);
        assertTrue(patch.sectors.containsKey(3));
        assertNull(patch.sectors.get(3), "a null sector clears that sector");
    }

    @Test
    void aNullLapOrStintDeletesItButANullCarOrChannelDeletesNothing() throws Exception {
        feed(info(SESSION, "Race"));
        feed(lapsDiff("7", "\"4\":null"));
        feed(stintsDiff("7", "\"" + (AnalysisFixtures.RACE_START + 5) + "\":null"));
        feed("{\"timing\":{\"analysis\":{\"laps\":{\"31\":null}}}}");
        feed("{\"timing\":{\"analysis\":{\"stints\":null}}}");

        assertEquals(List.of(new LapDeleted(SESSION, "7", 4)), ops(LapDeleted.class));
        assertEquals(List.of(new StintDeleted(SESSION, "7", AnalysisFixtures.RACE_START + 5)), ops(StintDeleted.class));
        assertEquals(1 + 1 + 1 + 1, ops.size(), "session, entry resolution, one lap delete, one stint delete");
    }

    @Test
    void stintsKeepTheirAccumulators() throws Exception {
        feed(info(SESSION, "Race"));
        long start = AnalysisFixtures.RACE_START + 1_000;
        feed(stintsDiff("04", "\"" + start + "\":" + stint(start, "TRACK", 2, 1, 30, start + 3_000_000L, 2_900_000L)));

        StintPatch s = ops(StintPatch.class).getFirst();
        assertEquals("04", s.car, "the number exactly as the feed writes it");
        assertEquals(start, s.startTimeMs);
        assertEquals("TRACK", s.type);
        assertEquals(2, s.driverOrder);
        assertEquals(30, s.closeLap);
        assertEquals(2_900_000L, s.accumSessionTrackMs);
        assertEquals(2_960_000L, s.accumSessionMs);
        assertFalse(s.has(StintPatch.PIT_TYPE));
    }

    @Test
    void analysisBeforeAnySessionIsSkippedAndCounted() throws Exception {
        feed(lapsDiff("7", "\"1\":" + lap(1, 1, 98_000, 2)));
        assertTrue(ops.isEmpty());
        assertEquals(1, router.withoutSession());
    }

    @Test
    void aSessionInTheSameFrameAheadOfTheLapsIsUsed() throws Exception {
        feed("{\"timing\":{\"session\":{\"info\":{\"sessionDbId\":99,\"name\":\"Practice 1\"}},"
                + "\"analysis\":{\"laps\":{\"7\":{\"laps\":{\"1\":{\"time\":100000}}}}}}}");
        assertEquals(99, ops(LapPatch.class).getFirst().sessionDbId);
    }

    @Test
    void aNewSessionStartsFreshSummariesAndResolvesDriversAgain() throws Exception {
        feed(info(SESSION, "Race"));
        feed(lapsDiff("7", "\"1\":" + lap(1, 1, 98_000, 0)));
        assertEquals(1, summaries.snapshot().size());

        feed("{\"timing\":{\"session\":{\"entry\":{\"7\":{\"currentDriver\":2}}}}}");
        assertEquals(2, ops(EntriesChanged.class).size(), "once for the new session, once for the entry diff");

        feed(info(SESSION + 1, "Race"));
        assertTrue(summaries.snapshot().isEmpty());
        assertEquals(SESSION + 1, ops(SessionSeen.class).getLast().session().sessionDbId());

        feed(info(SESSION + 1, "Race")); // the same info again is not news
        assertEquals(2, ops(SessionSeen.class).size());
    }

    @Test
    void numbersSentAsStringsAndFlagsAsDigitsAreRead() throws Exception {
        feed(info(SESSION, "Race"));
        feed(lapsDiff("4", "\"12\":{\"time\":\"95123\",\"isValid\":0,\"driver\":\"3\",\"topSpeed\":\"301.2\","
                + "\"pitIn\":{\"driver\":3,\"time\":1769000123000},\"trackLimits\":true,\"unknownThing\":{\"a\":[1,2]}}"));
        LapPatch patch = ops(LapPatch.class).getFirst();
        assertEquals(95_123, patch.lapTimeMs);
        assertEquals(Boolean.FALSE, patch.valid);
        assertEquals(3, patch.driverOrder);
        assertEquals(301.2, patch.topSpeed);
        assertEquals(1_769_000_123_000L, patch.pitInMs);
        assertEquals(1, patch.trackLimits);
    }

    @Test
    void aSnapshotRootedAtItsChannelIsReadThere() throws Exception {
        feed(info(SESSION, "Race"));
        feed("timing.analysis.laps", "{\"7\":{\"laps\":{\"1\":{\"time\":100000}}}}");
        feed("timing.session.status", "{\"currentFlag\":\"GREEN\"}");
        assertEquals(1, ops(LapPatch.class).size());
        assertEquals("GREEN", tree.copyOf("timing.session.status").path("currentFlag").asText());
    }

    @Test
    void aFullQueueDropsAndCountsRatherThanWaiting() throws Exception {
        feed(info(SESSION, "Race"));
        capacity = ops.size() + 1;
        feed(lapsDiff("7", "\"1\":" + lap(1, 1, 98_000, 0) + ",\"2\":" + lap(2, 1, 98_000, 0)));
        assertEquals(1, ops(LapPatch.class).size());
        assertEquals(1, router.dropped());
    }

    @Test
    void summariesTrackLastBestAndTheOpenStint() throws Exception {
        feed(info(SESSION, "Race"));
        long start = AnalysisFixtures.RACE_START;
        feed(stintsDiff("7", "\"" + start + "\":" + stint(start, "TRACK", 1, 1, null, null, 0)));
        feed(lapsDiff("7", "\"1\":" + lap(1, 1, 99_000, 0) + ",\"2\":" + lap(2, 1, 97_500, 0)
                + ",\"3\":" + lap(3, 1, 98_200, 0)));

        LiveCarSummaries.CarSummary car = summaries.snapshot().getFirst();
        assertEquals(3, car.lastLap());
        assertEquals(98_200, car.lastLapMs());
        assertEquals(2, car.bestLap());
        assertEquals(97_500, car.bestLapMs());
        assertEquals(start, car.stintStartMs());
        assertEquals(3, car.lapsInStint());

        feed(lapsDiff("7", "\"2\":{\"isValid\":false}"));
        assertTrue(summaries.snapshot().getFirst().bestStale(), "invalidated after the fact: re-read from live_lap");

        feed(stintsDiff("7", "\"" + start + "\":{\"finishTime\":" + (start + 300_000) + "}"));
        assertNull(summaries.snapshot().getFirst().stintStartMs());
    }

    @Test
    void sessionFramesStillMergeAsBefore() throws Exception {
        feed(info(SESSION, "Race"));
        feed("{\"timing\":{\"session\":{\"info\":{\"name\":\"Race 2\"}}}}");
        assertEquals("Race 2", tree.copyOf("timing.session.info").path("name").asText());
        assertEquals(SESSION, tree.copyOf("timing.session.info").path("sessionDbId").asLong());
        feed("{\"timing\":null}");
        assertNull(tree.copyOf("timing"));
        assertNotNull(ops.getFirst());
        assertInstanceOf(SessionSeen.class, ops.getFirst());
    }
}
