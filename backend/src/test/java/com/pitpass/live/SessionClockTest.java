package com.pitpass.live;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The session clock read from timing.session.status (spec 1.0.36, p. 39). */
class SessionClockTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private LiveTimingService.Clock clock(String info, String status) throws Exception {
        JsonNode i = mapper.readTree(info);
        JsonNode s = mapper.readTree(status);
        return LiveTimingService.clock(i, s);
    }

    @Test
    void theSpecsExample() throws Exception {
        var c = clock("{\"utcOffset\": -5}", """
                {"currentFlag": "GREEN", "elapsedLaps": 72, "finalTime": 9600, "finalType": "BY_TIME",
                 "isSessionRunning": true, "sessionStartTime": 1470600372111, "startTime": 1470600372,
                 "stopTime": 0, "stoppedSeconds": 0}
                """);
        assertEquals("BY_TIME", c.finalType());
        assertEquals(1_470_600_372_111L, c.startMs());
        assertEquals(9_600_000L, c.finalMs());
        assertNull(c.stopMs(), "running: no stop");
        assertEquals(0, c.stoppedMs());
        assertEquals(-5.0, c.utcOffsetHours());
    }

    @Test
    void aRedFlagStopsTheClockAtStopTime() throws Exception {
        var c = clock("{}", """
                {"finalTime": "2100", "finalType": "BY_TIME", "isSessionRunning": false,
                 "sessionStartTime": 1700000000000, "stopTime": 1700000600, "stoppedSeconds": 90,
                 "stoppedMilliSeconds": 90500}
                """);
        assertEquals(2_100_000L, c.finalMs(), "a number sent as a string still reads");
        assertEquals(1_700_000_600_000L, c.stopMs(), "stopTime is in seconds");
        assertEquals(90_500L, c.stoppedMs(), "the ms field wins over the seconds one");
        assertNull(c.utcOffsetHours());
    }

    @Test
    void beforeTheStartThereIsNoStartAndNoStop() throws Exception {
        var c = clock("{}", """
                {"finalLaps": 30, "finalType": "BY_LAPS", "isSessionRunning": false, "sessionStartTime": 0,
                 "stopTime": 0, "stoppedSeconds": 0}
                """);
        assertNull(c.startMs());
        assertNull(c.stopMs());
        assertNull(c.finalMs());
        assertEquals(30, c.finalLaps());
    }

    @Test
    void aStopFromBeforeThisSessionIsIgnored() throws Exception {
        var c = clock("{}", """
                {"isSessionRunning": false, "sessionStartTime": 1700000000000, "stopTime": 1690000000}
                """);
        assertNull(c.stopMs());
    }
}
