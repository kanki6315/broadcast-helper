package com.pitpass.imports;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
    void parsesTeamEntriesWithTheirCrewNotTheTeamName() throws IOException {
        JsonNode root;
        try (InputStream in = getClass()
                .getResourceAsStream("/fixtures/iracing/subsession-longbeach-team-2025.json")) {
            assertNotNull(in, "missing team-race fixture");
            root = mapper.readTree(in);
        }
        RaceResultsImport race = IRacingParser.parseSessions(root).stream()
                .filter(s -> "Race".equals(s.sessionType()))
                .findFirst().orElseThrow();

        // A team entry: the row's team is the team name; its drivers are the crew
        // from driver_results, NOT the team name split into first/surname.
        RaceResultsImport.Row winner = race.rows().get(0);
        assertEquals("91", winner.number());
        assertEquals("Porsche Coanda $91", winner.team());
        assertEquals(2, winner.drivers().size());

        List<String> crew = winner.drivers().stream()
                .map(d -> (d.firstName() + " " + d.surname()).trim())
                .toList();
        assertEquals(List.of("Charlie Collins", "Elvis Rankin"), crew);
        assertEquals("GB", winner.drivers().get(0).country());
        assertEquals(1, winner.drivers().get(0).seatOrder());
        assertEquals(2, winner.drivers().get(1).seatOrder());
        // The bug this guards: the team name must never become a "driver".
        assertNotEquals("$91", winner.drivers().get(0).surname());
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

        // The steward's correction is kept as its own figure rather than folded
        // into a session's penalty — it belongs to the season, not a round. It
        // is also what explains a row whose columns won't sum to its total.
        StandingsImport.Adjustments adj = leader.adjustments();
        assertEquals(389.0, adj.basePoints());
        assertEquals(0.0, adj.positive());
        assertEquals(-10.0, adj.negative());
        assertEquals(leader.totalPoints(), adj.basePoints() + adj.positive() + adj.negative(),
                "total must reconcile as base plus adjustments");

        // Rows come out in championship order.
        for (int i = 1; i < st.rows().size(); i++) {
            assertTrue(st.rows().get(i).position() >= st.rows().get(i - 1).position());
        }
    }

    @Test
    void splitsALeagueRoundIntoItsScoringSessions() throws IOException {
        // A round is its scoring sim-sessions, not one lump: this league pays
        // qualifying as well as both races, and practice/warmup pay nothing so
        // they are not sessions at all.
        List<IRacingParser.RoundSession> scored = IRacingParser.parseRoundLeagueSessions(fixture());

        assertEquals(List.of("Qualifying", "Heat 1", "Feature"),
                scored.stream().map(IRacingParser.RoundSession::sessionName).toList());

        // Cooper Webster: pole for 8, then 20 in the heat and 50 in the feature.
        // The 8 used to be dropped — only race sim-sessions were read — leaving
        // his round two-thirds of a session short of what iRacing scored him.
        assertEquals(8.0, scored.get(0).pointsByCust().get(161668L));
        assertEquals(20.0, scored.get(1).pointsByCust().get(161668L));
        assertEquals(50.0, scored.get(2).pointsByCust().get(161668L));

        double webster = scored.stream()
                .mapToDouble(s -> s.pointsByCust().getOrDefault(161668L, 0.0)).sum();
        assertEquals(78.0, webster, "the round total is what league_agg_points reports");
        assertEquals(30, scored.get(2).pointsByCust().size());
    }

    @Test
    void doesNotMakeASessionOfOneNobodyScoredIn() throws IOException {
        // A league that doesn't score qualifying (this team race pays only the
        // race) must not gain a column of zeros for it.
        JsonNode teamRace;
        try (InputStream in = getClass()
                .getResourceAsStream("/fixtures/iracing/subsession-longbeach-team-2025.json")) {
            assertNotNull(in, "missing team-race fixture");
            teamRace = mapper.readTree(in);
        }
        assertTrue(IRacingParser.parseRoundLeagueSessions(teamRace).isEmpty(),
                "this round scored nobody anywhere — it contributes no sessions");
    }

    @Test
    void namesTheRoundWithTheSameVenueTheResultsImportUses() throws IOException {
        // The recap labels a column with this name but matches the round to its
        // event by ordinal, so this is a label, not a key. Keeping it identical to
        // the round-results import's event name stops a column reading unlike the
        // event beneath it.
        String venue = IRacingParser.roundVenueName(fixture());

        assertTrue(venue.startsWith("Daytona"), venue);
        assertEquals(IRacingParser.parseSessions(fixture()).get(0).eventName(), venue);
    }

    @Test
    void assemblesPerRoundPointsOntoStandingsRows() throws IOException {
        JsonNode standings;
        try (InputStream in = getClass()
                .getResourceAsStream("/fixtures/iracing/league-season-standings-6004-114713.json")) {
            assertNotNull(in, "missing standings fixture");
            standings = mapper.readTree(in);
        }
        // One scored round (Daytona) plus a future calendar slot nobody has raced:
        // the assembly fills the first and leaves the second blank, not zero.
        Map<Long, Map<Integer, Double>> byCust = new HashMap<>();
        IRacingParser.parseRoundLeagueSessions(fixture()).get(2).pointsByCust()
                .forEach((cust, p) -> byCust.computeIfAbsent(cust, k -> new HashMap<>()).put(1, p));
        List<StandingsImport.SessionRef> sessions = List.of(
                new StandingsImport.SessionRef(1, "Daytona International Speedway", "Feature"),
                new StandingsImport.SessionRef(2, "Sebring International Raceway", "Feature"));

        StandingsImport st = IRacingParser.assembleSeasonStandings(
                standings, "2025 Porsche Esports Supercup", "2025", sessions, byCust);

        assertEquals(2, st.sessions().size());
        StandingsImport.Row leader = st.rows().get(0);
        assertEquals("161668", leader.key());
        assertEquals(379.0, leader.totalPoints()); // season total is unchanged (base + adjustments)

        // Only the scored round appears; the empty future round stays blank.
        assertEquals(1, leader.pointsBySession().size());
        StandingsImport.SessionPoints round1 = leader.pointsBySession().get(0);
        assertEquals(1, round1.sessionIndex());
        assertEquals(50.0, round1.totalPoints()); // the feature session alone
        assertEquals(50.0, round1.racePoints());
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

        // iRacing names no per-seat attribution — a solo entry's qualifier is
        // resolved at read time as the sole crew member, never stored here.
        assertTrue(feature.rows().stream().allMatch(r ->
                r.startingDriverSeat() == null
                && r.qualifyingDriverSeat() == null
                && r.drivers().isEmpty()));
    }

    // ------------------------------------------------- official-series seasons
    // Fixtures are trimmed real payloads from the 2020 Porsche TAG Heuer Esports
    // Supercup (series 409, season 2812) — the season whose first Le Mans race
    // was voided (official_session false) and re-run months later, which is the
    // case the official-round filtering exists for.

    private JsonNode officialFixture(String name) throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/fixtures/iracing/" + name)) {
            assertNotNull(in, "missing fixture " + name);
            return mapper.readTree(in);
        }
    }

    @Test
    void findsAnOfficialSeasonWithItsYearAndCarClasses() throws IOException {
        JsonNode root = officialFixture("series-past-seasons-409.json");

        IRacingParser.OfficialSeason season =
                IRacingParser.parseOfficialSeason(root, 2812).orElseThrow();
        assertEquals("Porsche TAG Heuer Esports Supercup - 2020 Season", season.seasonName());
        // The year is the payload's own field — the name carries it as a suffix,
        // where the league flow's leading-year heuristic would find nothing.
        assertEquals(2020, season.seasonYear());
        assertEquals(1, season.carClasses().size());
        assertEquals(95, season.carClasses().get(0).carClassId());

        // A season the series never ran is a mismatched id pair, not a match.
        assertTrue(IRacingParser.parseOfficialSeason(root, 999999).isEmpty());
    }

    @Test
    void listsOnlyOfficialRoundsOldestFirstIgnoringWeekNumbers() throws IOException {
        // The fixture's rows are deliberately shuffled and include the voided
        // June Le Mans race (week 3, official_session false).
        List<IRacingParser.LeagueRound> rounds =
                IRacingParser.parseOfficialSeasonRounds(officialFixture("season-results-2812.json"));

        // 11 sessions in the payload; the voided one is not a round.
        assertEquals(10, rounds.size());
        assertTrue(rounds.stream().noneMatch(r -> r.subsessionId() == 33053142L),
                "the voided Le Mans race must not appear as a round");

        // Chronological despite the shuffled payload: the season opened at
        // Zandvoort and closed at Monza. Week numbers play no part — the
        // calendar has raceless and voided weeks.
        assertEquals(32168491L, rounds.get(0).subsessionId());
        assertEquals("Circuit Park Zandvoort Grand Prix - 2009", rounds.get(0).trackName());
        assertEquals("Autodromo Nazionale Monza Grand Prix", rounds.get(9).trackName());
        for (int i = 1; i < rounds.size(); i++) {
            assertTrue(!rounds.get(i).launchAt().isBefore(rounds.get(i - 1).launchAt()),
                    "rounds must be oldest-first");
        }

        // The re-run Le Mans (September, week 10) is a real round.
        assertTrue(rounds.stream().anyMatch(r -> r.subsessionId() == 34799811L));

        // This payload lists only completed sessions and names no winner.
        assertTrue(rounds.stream().allMatch(IRacingParser.LeagueRound::hasResults));
        assertEquals(40, rounds.get(0).entryCount());
    }

    @Test
    void splitsAnOfficialRoundIntoQualifyingAndEachRace() throws IOException {
        // Official series pay qualifying (10/8/6…), a heat, and a feature; each
        // is its own scoring session, so the recap can show what a driver took
        // from qualifying rather than only the weekend's lump.
        List<IRacingParser.RoundSession> scored = IRacingParser.parseRoundChampSessions(
                officialFixture("subsession-official-pesc-2020.json"));

        assertEquals(List.of("Qualifying", "Heat 1", "Feature"),
                scored.stream().map(IRacingParser.RoundSession::sessionName).toList());

        // Job's Zandvoort round: 6 + 16 + 50 = the 72 iRacing scored him.
        assertEquals(6.0, scored.get(0).pointsByCust().get(119101L));
        assertEquals(16.0, scored.get(1).pointsByCust().get(119101L));
        assertEquals(50.0, scored.get(2).pointsByCust().get(119101L));

        // Rogers took pole and appears in qualifying only in this fixture; his
        // 10 still arrives, where reading only the races would have lost it.
        assertEquals(10.0, scored.get(0).pointsByCust().get(169237L));
        assertFalse(scored.get(2).pointsByCust().containsKey(169237L));
    }

    @Test
    void scoresAVoidedRoundAsNothing() throws IOException {
        // A voided race keeps its rows but zeroes every points field — belt and
        // braces under the official_session filter: even if one slipped through,
        // it would contribute no sessions at all, so no phantom column.
        JsonNode voided = mapper.readTree("""
                {"session_results":[{"simsession_type":6,"simsession_name":"FEATURE","results":[
                  {"cust_id":119101,"champ_points":0,"aggregate_champ_points":0},
                  {"cust_id":120570,"champ_points":0,"aggregate_champ_points":0}]}]}
                """);

        assertTrue(IRacingParser.parseRoundChampSessions(voided).isEmpty());
    }

    @Test
    void assemblesOfficialStandingsFromFlatChunkRows() throws IOException {
        // Chunk rows are flat (rank/cust_id/display_name/points at the top
        // level), unlike the league shape nested under driver.*; two files
        // stand in for a chunked download already concatenated by the client.
        var rows = mapper.createArrayNode();
        rows.addAll((com.fasterxml.jackson.databind.node.ArrayNode)
                officialFixture("season-standings-chunk-2812-1.json"));
        rows.addAll((com.fasterxml.jackson.databind.node.ArrayNode)
                officialFixture("season-standings-chunk-2812-2.json"));

        Map<Long, Map<Integer, Double>> byCust = new HashMap<>();
        byCust.put(119101L, Map.of(1, 72.0));
        List<StandingsImport.SessionRef> sessions = List.of(
                new StandingsImport.SessionRef(1, "Circuit Park Zandvoort Grand Prix - 2009",
                        "Circuit Park Zandvoort"),
                new StandingsImport.SessionRef(2, "Circuit de Barcelona Catalunya Grand Prix",
                        "Circuit de Barcelona Catalunya"));

        StandingsImport st = IRacingParser.assembleOfficialStandings(
                rows, "Porsche TAG Heuer Esports Supercup - 2020 Season", "2020",
                sessions, byCust);

        assertEquals("Porsche TAG Heuer Esports Supercup - 2020 Season", st.name());
        assertEquals("2020", st.year());
        assertEquals(2, st.sessions().size());
        assertEquals(6, st.rows().size());

        StandingsImport.Row champion = st.rows().get(0);
        assertEquals(1, champion.position());
        assertEquals("119101", champion.key()); // cust_id, stable across a rename
        assertEquals("Sebastian Job", champion.team());
        assertEquals(659.0, champion.totalPoints()); // the endpoint's authoritative figure
        // An official series has no steward adjustments to report, which is why
        // its totals reconcile to the rounds exactly.
        assertNull(champion.adjustments());

        // Only the scored round appears; the round without points stays blank.
        assertEquals(1, champion.pointsBySession().size());
        assertEquals(72.0, champion.pointsBySession().get(0).totalPoints());

        for (int i = 1; i < st.rows().size(); i++) {
            assertTrue(st.rows().get(i).position() >= st.rows().get(i - 1).position());
        }
    }
}
