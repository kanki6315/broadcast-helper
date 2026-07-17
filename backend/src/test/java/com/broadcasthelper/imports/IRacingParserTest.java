package com.broadcasthelper.imports;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Parser tests against a real 2025 Porsche Esports Supercup subsession — a
 * heat-format league round at Daytona. The fixture encodes what this format
 * makes the importer handle: five sim-sessions of which two are dropped, a
 * reverse feature grid, ten-thousandth-second times, and two qualifiers who
 * set no lap.
 */
class IRacingParserTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode fixture() throws IOException {
        try (InputStream in = getClass()
                .getResourceAsStream("/fixtures/iracing/subsession-daytona-2025.json")) {
            assertNotNull(in, "missing iRacing fixture");
            return mapper.readTree(in);
        }
    }

    private RaceResultsImport session(String name) throws IOException {
        return IRacingParser.parseSessions(fixture()).stream()
                .filter(s -> name.equals(s.sessionName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no session named " + name));
    }

    private JsonNode seasonSessionsFixture() throws IOException {
        try (InputStream in = getClass()
                .getResourceAsStream("/fixtures/iracing/league-season-sessions-6004-114713.json")) {
            assertNotNull(in, "missing season-sessions fixture");
            return mapper.readTree(in);
        }
    }

    @Test
    void dropsTheNaConfigFromATrackWithNoLayout() {
        // A track with a real layout keeps it...
        assertEquals("Daytona International Speedway Road Course",
                IRacingParser.circuitName(track("Daytona International Speedway", "Road Course")));
        // ...but "N/A" (Sachsenring) is a sentinel for "no layout", not a name.
        assertEquals("Sachsenring", IRacingParser.circuitName(track("Sachsenring", "N/A")));
        assertEquals("Sachsenring", IRacingParser.circuitName(track("Sachsenring", "n/a")));
        assertEquals("Sachsenring", IRacingParser.circuitName(track("Sachsenring", null)));
    }

    private JsonNode track(String trackName, String configName) {
        var track = mapper.createObjectNode().put("track_name", trackName);
        if (configName != null) {
            track.put("config_name", configName);
        }
        return mapper.createObjectNode().set("track", track);
    }

    @Test
    void parsesSeasonDriverStandings() throws IOException {
        JsonNode root;
        try (InputStream in = getClass()
                .getResourceAsStream("/fixtures/iracing/league-season-standings-6004-114713.json")) {
            assertNotNull(in, "missing standings fixture");
            root = mapper.readTree(in);
        }
        StandingsImport st = IRacingParser.parseSeasonStandings(root, "2025 Porsche Esports Supercup", "2025");

        assertEquals("2025 Porsche Esports Supercup", st.name());
        assertEquals("2025", st.year());
        assertTrue(st.sessions().isEmpty(), "the endpoint names no rounds");
        assertEquals(30, st.rows().size());

        StandingsImport.Row leader = st.rows().get(0);
        assertEquals(1, leader.position());
        assertEquals("Cooper Webster", leader.team());
        assertEquals("161668", leader.key()); // cust_id, stable across a rename
        assertEquals(379.0, leader.totalPoints()); // total = base 389 + adjustment -10
        assertTrue(leader.pointsBySession().isEmpty(), "no per-round breakdown from this endpoint");

        // Rows come out in championship order.
        for (int i = 1; i < st.rows().size(); i++) {
            assertTrue(st.rows().get(i).position() >= st.rows().get(i - 1).position());
        }
    }

    @Test
    void parsesLeagueSeasonRoundsOldestFirst() throws IOException {
        List<IRacingParser.LeagueRound> rounds =
                IRacingParser.parseSeasonRounds(seasonSessionsFixture());

        assertEquals(7, rounds.size());

        // Oldest first: the 2025 Porsche Esports Supercup opened at Daytona — the
        // very subsession the fetch path is proven against.
        IRacingParser.LeagueRound opener = rounds.get(0);
        assertEquals(74553295L, opener.subsessionId());
        assertEquals("Daytona International Speedway", opener.trackName());
        assertTrue(opener.hasResults());
        assertEquals(2025, opener.launchAt().getYear());

        // Strictly ascending by launch time — the schedule order a broadcaster reads.
        for (int i = 1; i < rounds.size(); i++) {
            assertTrue(!rounds.get(i).launchAt().isBefore(rounds.get(i - 1).launchAt()),
                    "rounds must be oldest-first");
        }

        // Every round here has been run, so each subsession id is importable.
        assertTrue(rounds.stream().allMatch(IRacingParser.LeagueRound::hasResults));
    }

    @Test
    void detectsEventResult() throws IOException {
        assertTrue(IRacingParser.looksLikeEventResult(fixture()));
    }

    @Test
    void rejectsATimingProviderFile() throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/fixtures/imsa/race-wgi-2026.json")) {
            assertFalse(IRacingParser.looksLikeEventResult(mapper.readTree(in)));
        }
    }

    /**
     * The Data API returns the result object unwrapped — session_results at the
     * top level, no {"type":"event_result","data":{...}} envelope, which the
     * exported file adds. Unwrapping the fixture the same way must parse to the
     * exact same sessions, so the fetch and upload paths stay interchangeable.
     */
    @Test
    void parsesTheUnwrappedApiShapeIdenticallyToTheExportedFile() throws IOException {
        JsonNode wrapped = fixture();
        JsonNode bare = wrapped.get("data"); // what the signed-link payload looks like

        assertTrue(IRacingParser.looksLikeEventResult(bare));
        assertEquals(
                IRacingParser.parseSessions(wrapped).stream().map(RaceResultsImport::sessionName).toList(),
                IRacingParser.parseSessions(bare).stream().map(RaceResultsImport::sessionName).toList());
        assertEquals(IRacingParser.parseGrids(wrapped).size(), IRacingParser.parseGrids(bare).size());

        RaceResultsImport wrappedFeature = IRacingParser.parseSessions(wrapped).stream()
                .filter(s -> "Feature".equals(s.sessionName())).findFirst().orElseThrow();
        RaceResultsImport bareFeature = IRacingParser.parseSessions(bare).stream()
                .filter(s -> "Feature".equals(s.sessionName())).findFirst().orElseThrow();
        assertEquals(wrappedFeature.rows().get(0).team(), bareFeature.rows().get(0).team());
        assertEquals(wrappedFeature.circuitName(), bareFeature.circuitName());
    }

    @Test
    void keepsScoringSessionsAndDropsPracticeAndWarmup() throws IOException {
        List<RaceResultsImport> sessions = IRacingParser.parseSessions(fixture());
        assertEquals(List.of("Qualifying", "Heat 1", "Feature"),
                sessions.stream().map(RaceResultsImport::sessionName).toList());

        // The races keep their broadcast names while taking sequential RACE
        // ordinals — the key the domain matches on.
        assertEquals("Qualifying", sessions.get(0).sessionType());
        assertEquals(1, sessions.get(0).sessionOrdinal());
        assertEquals("Race", sessions.get(1).sessionType());
        assertEquals(1, sessions.get(1).sessionOrdinal());
        assertEquals("Race", sessions.get(2).sessionType());
        assertEquals(2, sessions.get(2).sessionOrdinal());
    }

    @Test
    void readsMeetingMetadata() throws IOException {
        RaceResultsImport feature = session("Feature");
        assertEquals("Porsche TAG Heuer Esports Supercup", feature.championshipName());
        assertEquals("Daytona International Speedway Road Course", feature.circuitName());
        assertEquals("Daytona International Speedway Road Course", feature.eventName());
        assertEquals(30, feature.rows().size());

        // Asserted as an instant rather than a wall clock: the parser shifts the
        // payload's UTC into the JVM zone so the instant survives the
        // timestamptz column, which leaves the wall clock machine-dependent.
        assertEquals(Instant.parse("2025-02-01T18:30:18Z"),
                feature.sessionStart().atZone(ZoneId.systemDefault()).toInstant());
    }

    @Test
    void parsesQualifyingPole() throws IOException {
        RaceResultsImport qualifying = session("Qualifying");
        RaceResultsImport.Row pole = qualifying.rows().get(0);
        assertEquals(1, pole.positionOverall());
        assertEquals(1, pole.positionInClass());
        assertEquals("2", pole.number());
        assertEquals("1:44.601", pole.fastestLapTime());
        assertEquals("-", pole.gapFirst());
        assertEquals("Classified", pole.status());
        assertFalse(pole.notFinished());

        RaceResultsImport.DriverRow driver = pole.drivers().get(0);
        assertEquals("Florian A", driver.firstName()); // middle initial stays with the forename
        assertEquals("Lebigre", driver.surname());
        assertEquals("FR", driver.country());
        // The licence class only. The safety rating iRacing shows beside it has
        // no home in the domain, which keeps a single letter per driver.
        assertEquals("A", driver.rating());

        // A thousandth covers the whole front row.
        assertEquals("+0.001", qualifying.rows().get(1).gapFirst());
    }

    @Test
    void classifiesAQualifierWhoSetNoLap() throws IOException {
        RaceResultsImport qualifying = session("Qualifying");
        RaceResultsImport.Row noTime = qualifying.rows().get(28);
        assertEquals("Sam Kuitert", noTime.team());
        assertEquals(29, noTime.positionOverall());
        // Ran out but never set a time: classified, not retired.
        assertEquals("No Time", noTime.status());
        assertFalse(noTime.notFinished());
        assertNull(noTime.notFinishedCause());
        assertNull(noTime.fastestLapTime());
        assertNull(noTime.gapFirst());
        assertNull(noTime.gapPrevious()); // no time and no lap between them
    }

    @Test
    void parsesFeatureResult() throws IOException {
        RaceResultsImport feature = session("Feature");
        RaceResultsImport.Row winner = feature.rows().get(0);
        assertEquals("37", winner.number());
        assertEquals("Cooper Webster", winner.team());
        assertEquals("[Legacy] Porsche 911 GT3 Cup (992.1)", winner.vehicle());
        assertEquals(16, winner.laps());
        assertEquals("1:44.735", winner.fastestLapTime());

        RaceResultsImport.Row second = feature.rows().get(1);
        assertEquals("+0.016", second.gapFirst());
        assertEquals("+0.016", second.gapPrevious());

        RaceResultsImport.Row third = feature.rows().get(2);
        assertEquals("+0.072", third.gapFirst());
        assertEquals("+0.056", third.gapPrevious()); // differenced from the leader gaps
    }

    @Test
    void reportsALappedCarInLapsNotSeconds() throws IOException {
        RaceResultsImport feature = session("Feature");
        RaceResultsImport.Row last = feature.rows().get(29);
        assertEquals("Chris Lulham", last.team());
        assertEquals(15, last.laps());
        assertEquals("1 Lap", last.gapFirst());
        assertEquals("1 Lap", last.gapPrevious());
        assertFalse(last.notFinished()); // still running, just a lap down
    }

    @Test
    void takesTheFeatureGridVerbatimBecauseItIsReversed() throws IOException {
        List<GridImport> grids = IRacingParser.parseGrids(fixture());
        assertEquals(List.of("Heat 1", "Feature"),
                grids.stream().map(GridImport::sessionName).toList());

        // The heat winner starts the feature eighth: a reverse-top-8 grid, which
        // neither the qualifying order nor the heat order reproduces.
        GridImport feature = grids.get(1);
        assertEquals(30, feature.rows().size());
        assertEquals("2", feature.rows().get(7).number()); // Lebigre, heat winner, P8
        assertEquals(8, feature.rows().get(7).positionOverall());

        // The heat grid is the qualifying order: pole sitter starts first.
        GridImport heat = grids.get(0);
        assertEquals("2", heat.rows().get(0).number());
    }
}
