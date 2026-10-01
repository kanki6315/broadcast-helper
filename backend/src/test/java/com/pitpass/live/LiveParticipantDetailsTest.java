package com.pitpass.live;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** participantDetails as the spec shows it (1.0.36, pp. 22–25 and 71). */
class LiveParticipantDetailsTest {

    private final ObjectMapper mapper = new ObjectMapper();

    /** #5 is on lap 5, in sector 3: sectors 1–2 are this lap's, 3 is still lap 4's. */
    private static final String DETAILS = """
            {"5": {"currentLap": 5, "currentSector": 3, "status": "TRACK", "pitStops": 1,
                   "lastSectors": {"1": {"isValid": false, "number": 1, "time": 27115, "trackLimits": true},
                                   "2": {"isValid": true, "number": 2, "time": 7796},
                                   "3": {"isValid": true, "number": 3, "time": 18062}},
                   "bestSectors": {"1": {"number": 1, "time": 26900}, "2": {"number": 2, "time": 7750},
                                   "3": {"number": 3, "time": 18000}}},
             "7": {"currentSector": 1, "status": "BOX",
                   "lastSectors": {"1": {"isValid": true, "number": 1, "time": 26800}},
                   "bestSectors": {"1": {"number": 1, "time": 26800}, "2": {"number": 2, "time": 7800}}}}
            """;

    @Test
    void sectorsAsTheyAreRun() throws Exception {
        JsonNode details = mapper.readTree(DETAILS);
        assertEquals(3, LiveParticipantDetails.sectorCount(details));
        var five = LiveParticipantDetails.car(details.get("5"), 3);
        assertEquals("TRACK", five.trackStatus());
        assertEquals(3, five.currentSector());
        assertEquals(new LiveParticipantDetails.SectorTime(27115, false, true), five.sectors().get(0), "invalid, this lap");
        assertTrue(five.sectors().get(1).currentLap());
        assertFalse(five.sectors().get(2).currentLap(), "sector 3 is the one being run: its time is the last lap's");
        assertEquals(List.of(26900, 7750, 18000), five.bestSectorMs());
        assertEquals(52_650L, five.idealMs());
        assertFalse(five.inBox());

        var seven = LiveParticipantDetails.car(details.get("7"), 3);
        assertTrue(seven.inBox());
        assertNull(seven.sectors().get(1), "no time yet in sector 2");
        assertNull(seven.idealMs(), "no best in sector 3 yet");
        assertNull(seven.pitStops());
    }

    @Test
    void classBestsAndTheIdealLap() throws Exception {
        JsonNode details = mapper.readTree(DETAILS);
        Map<String, LiveParticipantDetails.Car> cars = Map.of(
                "5", LiveParticipantDetails.car(details.get("5"), 3),
                "7", LiveParticipantDetails.car(details.get("7"), 3));
        var bests = LiveParticipantDetails.classBests(List.of("5", "7"), cars::get, 3);
        assertEquals(List.of(new LiveParticipantDetails.ClassSector(26800, "7"),
                new LiveParticipantDetails.ClassSector(7750, "5"),
                new LiveParticipantDetails.ClassSector(18000, "5")), bests);
        assertEquals(52_550L, LiveParticipantDetails.idealLap(bests));
        assertNull(LiveParticipantDetails.idealLap(List.of(new LiveParticipantDetails.ClassSector(null, null))));
    }

    @Test
    void theFlagJoinsTheChannel() {
        var off = props(false);
        var on = props(true);
        assertFalse(off.joinedChannels().contains(AlKamelV2Properties.PARTICIPANT_DETAILS_CHANNEL));
        assertEquals(List.of("timing.session.info", AlKamelV2Properties.PARTICIPANT_DETAILS_CHANNEL), on.joinedChannels());
    }

    private static AlKamelV2Properties props(boolean details) {
        return new AlKamelV2Properties("h", 1, "u", "p", false, false, "t", List.of("timing.session.info"), 1, 1, 1,
                null, null, new AlKamelV2Properties.Analysis(false, 0), new AlKamelV2Properties.ParticipantDetails(details), null);
    }

    @Test
    void noDetailsNoSectors() {
        assertEquals(0, LiveParticipantDetails.sectorCount(null));
        assertNull(LiveParticipantDetails.car(null, 3));
    }
}
