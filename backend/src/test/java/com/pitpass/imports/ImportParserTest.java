package com.pitpass.imports;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.time.Month;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Parser tests against the real 2026 IMSA sample files. These fixtures encode
 * the format quirks the importers must keep handling: BOMs, two date formats,
 * leading-zero car numbers, 2-4 driver crews, and penalty-reordered positions.
 */
class ImportParserTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode fixture(String name) throws IOException {
        return fixtureIn("imsa", name);
    }

    private JsonNode mustangFixture(String name) throws IOException {
        return fixtureIn("mustang", name);
    }

    private JsonNode fixtureIn(String series, String name) throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/fixtures/" + series + "/" + name)) {
            assertNotNull(in, "missing fixture " + name);
            return mapper.readTree(in);
        }
    }

    @Test
    void detectsFileKinds() throws IOException {
        assertTrue(ImportParser.looksLikeRaceResults(fixture("race-wgi-2026.json")));
        assertTrue(ImportParser.looksLikeStandings(fixture("standings-gtp-teams-2026.json")));
    }

    @Test
    void parsesWatkinsGlenRace() throws IOException {
        RaceResultsImport imp = ImportParser.parseRaceResults(fixture("race-wgi-2026.json"));
        assertEquals("IMSA WeatherTech SportsCar Championship", imp.championshipName());
        assertEquals("Sahlen's Six Hours of The Glen", imp.eventName());
        assertEquals(LocalDateTime.of(2026, Month.JUNE, 28, 12, 10), imp.sessionStart());
        assertEquals(54, imp.rows().size());

        RaceResultsImport.Row winner = imp.rows().get(0);
        assertEquals("31", winner.number());
        assertEquals(1, winner.positionOverall());
        assertEquals(1, winner.positionInClass());
        assertEquals("GTP", winner.className());
        assertEquals(3, winner.drivers().size());

        // Leading zeros survive: #04 (LMP2) and #068 (GTD) are distinct numbers.
        assertTrue(imp.rows().stream().anyMatch(r -> r.number().equals("04")));
        assertTrue(imp.rows().stream().anyMatch(r -> r.number().equals("068")));
    }

    @Test
    void derivesSessionOrdinalFromName() throws IOException {
        // Single-race weekend: "Race" -> ordinal 1.
        assertEquals(1, ImportParser.parseRaceResults(fixture("race-wgi-2026.json")).sessionOrdinal());
        // Multi-race weekend: "Race 1"/"Race 2" -> 1/2; "Qualifying" -> 1.
        assertEquals(1, ImportParser.parseRaceResults(mustangFixture("results-midohio-race1-2026.json")).sessionOrdinal());
        assertEquals(2, ImportParser.parseRaceResults(mustangFixture("results-midohio-race2-2026.json")).sessionOrdinal());
        assertEquals(1, ImportParser.parseRaceResults(mustangFixture("results-midohio-qualifying-2026.json")).sessionOrdinal());
    }

    @Test
    void parsesStartingGridWithInClassPositions() throws IOException {
        JsonNode root = fixture("grid-wgi-2026.json");
        assertTrue(ImportParser.looksLikeGrid(root));
        GridImport grid = ImportParser.parseGrid(root);
        assertEquals("Race", grid.sessionName());
        assertEquals(1, grid.sessionOrdinal());

        // Pole is #31, GTP P1. In-class positions count within each class over the
        // overall grid order; a blank grid slot (overall P12) is skipped, so the
        // first LMP2 (#43, overall P13) is LMP2 P1 — not thrown off by the gap.
        GridImport.Row pole = grid.rows().get(0);
        assertEquals("31", pole.number());
        assertEquals("GTP", pole.className());
        assertEquals(1, pole.positionInClass());
        assertEquals(1, pole.positionOverall());

        GridImport.Row firstLmp2 = grid.rows().stream()
                .filter(r -> r.className().equals("LMP2")).findFirst().orElseThrow();
        assertEquals("43", firstLmp2.number());
        assertEquals(1, firstLmp2.positionInClass());
        assertEquals(13, firstLmp2.positionOverall());

        // Leading-zero car numbers survive as strings.
        assertTrue(grid.rows().stream().anyMatch(r -> r.number().equals("04")));
        assertTrue(grid.rows().stream().anyMatch(r -> r.number().equals("033")));
        // The blank slot is dropped, never a phantom row.
        assertTrue(grid.rows().stream().allMatch(r -> r.number() != null && !r.number().isBlank()));
    }

    @Test
    void parsesGridDriverAttribution() throws IOException {
        GridImport grid = ImportParser.parseGrid(fixture("grid-wgi-2026.json"));

        // Pole car 31: seat 1 both qualified and starts; the roster carries the
        // same per-car seat numbering as a results file.
        GridImport.Row pole = grid.rows().get(0);
        assertEquals(1, pole.startingDriverSeat());
        assertEquals(1, pole.qualifyingDriverSeat());
        RaceResultsImport.DriverRow seat1 = pole.drivers().stream()
                .filter(d -> d.seatOrder() == 1).findFirst().orElseThrow();
        assertEquals("Jack", seat1.firstName());
        assertEquals("Aitken", seat1.surname());

        // Car 120 names a starter (seat 3) but no qualifier — the real
        // missing-seat case; the roster still lists all three drivers.
        GridImport.Row car120 = grid.rows().stream()
                .filter(r -> r.number().equals("120")).findFirst().orElseThrow();
        assertEquals(3, car120.startingDriverSeat());
        assertNull(car120.qualifyingDriverSeat());
        assertEquals(List.of("Sargent", "Ilott", "Adelson"),
                car120.drivers().stream().map(RaceResultsImport.DriverRow::surname).toList());
    }

    @Test
    void parsesMustangPerRaceGrids() throws IOException {
        GridImport race1 = ImportParser.parseGrid(mustangFixture("grid-midohio-race1-2026.json"));
        GridImport race2 = ImportParser.parseGrid(mustangFixture("grid-midohio-race2-2026.json"));
        assertEquals(1, race1.sessionOrdinal());
        assertEquals(2, race2.sessionOrdinal());
        // #42 is on DH pole for Race 1.
        GridImport.Row dhPole = race1.rows().stream()
                .filter(r -> r.className().equals("DH")).findFirst().orElseThrow();
        assertEquals("42", dhPole.number());
        assertEquals(1, dhPole.positionInClass());
    }

    @Test
    void handlesDetroitSlashDateFormat() throws IOException {
        RaceResultsImport imp = ImportParser.parseRaceResults(fixture("race-detroit-2026.json"));
        assertEquals(LocalDateTime.of(2026, Month.MAY, 30, 4, 10), imp.sessionStart());
    }

    @Test
    void computesInClassPositionsAcrossInterleavedClasses() throws IOException {
        RaceResultsImport imp = ImportParser.parseRaceResults(fixture("race-wgi-2026.json"));
        // First LMP2 home is #99 in P10 overall -> P1 in class.
        RaceResultsImport.Row firstLmp2 = imp.rows().stream()
                .filter(r -> r.className().equals("LMP2")).findFirst().orElseThrow();
        assertEquals("99", firstLmp2.number());
        assertEquals(10, firstLmp2.positionOverall());
        assertEquals(1, firstLmp2.positionInClass());

        // In-class positions are dense 1..n per class.
        List<Integer> gtdPro = imp.rows().stream()
                .filter(r -> r.className().equals("GTDPRO"))
                .map(RaceResultsImport.Row::positionInClass)
                .toList();
        assertEquals(12, gtdPro.size());
        for (int i = 0; i < gtdPro.size(); i++) {
            assertEquals(i + 1, gtdPro.get(i));
        }
    }

    @Test
    void parsesDaytonaWithFourDriverCrewsAndNotStartedStatus() throws IOException {
        RaceResultsImport daytona = ImportParser.parseRaceResults(fixture("race-daytona-2026.json"));
        assertEquals(60, daytona.rows().size());
        assertTrue(daytona.rows().stream().anyMatch(r -> r.drivers().size() == 4));

        RaceResultsImport sebring = ImportParser.parseRaceResults(fixture("race-sebring-2026.json"));
        // A car that did not start is kept, but carries no in-class position.
        RaceResultsImport.Row dns = sebring.rows().stream()
                .filter(r -> "Not Started".equals(r.status())).findFirst().orElseThrow();
        assertNull(dns.positionInClass());
    }

    @Test
    void detectsFlagsFile() throws IOException {
        JsonNode flags = fixture("flags-ctmp-race-2026.json");
        assertTrue(ImportParser.looksLikeFlags(flags));
        // A flags file must not be mistaken for results or a grid, nor vice versa.
        assertTrue(!ImportParser.looksLikeRaceResults(flags));
        assertTrue(!ImportParser.looksLikeGrid(flags));
        assertTrue(!ImportParser.looksLikeFlags(fixture("race-wgi-2026.json")));
    }

    @Test
    void parsesFlagsFile() throws IOException {
        FlagsImport imp = ImportParser.parseFlags(fixture("flags-ctmp-race-2026.json"));
        assertEquals("IMSA WeatherTech SportsCar Championship", imp.championshipName());
        assertEquals("Chevrolet Grand Prix", imp.eventName());
        assertEquals("Race", imp.sessionType());
        assertEquals(1, imp.sessionOrdinal());
        assertEquals(7, imp.rows().size());

        FlagsImport.FlagRow greenFlag = imp.rows().get(1);
        assertEquals("GF", greenFlag.recType());
        assertEquals("GREEN FLAG", greenFlag.flag());
        assertEquals(1, greenFlag.lap());
        assertEquals("14:10.647", greenFlag.flagTime());

        FlagsImport.FlagRow penalty = imp.rows().get(2);
        assertEquals("RCMessage", penalty.recType());
        assertEquals("Car 11 Penalty - Track Limits - Warning", penalty.message());
        assertNull(penalty.flag());

        FlagsImport.FlagRow chequered = imp.rows().get(5);
        assertEquals("FF", chequered.recType());
        assertEquals(127, chequered.lap());

        // Header notes ride along so a commit can refresh the session's.
        assertEquals("Car #11, #16, #66, #68 & #70 - Some lap times invalidated due to track limits",
                imp.reportMessage());
    }

    @Test
    void qualifyingBestLapReadsTimeAndLapFields() throws IOException {
        // A "Qualifying Practice by Best Lap" file spells the entry's best lap as
        // time / lap / kph, not the race's fastest_lap_* — the parser must read
        // both spellings or every qualifying lap stores null (it used to).
        RaceResultsImport quali =
                ImportParser.parseRaceResults(mustangFixture("results-midohio-qualifying-2026.json"));
        RaceResultsImport.Row pole = quali.rows().get(0);
        assertEquals("42", pole.number());
        assertEquals("1:47.054", pole.fastestLapTime());
        assertEquals(6, pole.fastestLapNumber());
        assertEquals(122.2, pole.fastestLapKph());

        // Races keep reading fastest_lap_*; the fallback never fires for them.
        RaceResultsImport race = ImportParser.parseRaceResults(fixture("race-wgi-2026.json"));
        assertTrue(race.rows().stream().anyMatch(r -> r.fastestLapTime() != null));
        assertTrue(race.rows().stream().anyMatch(r -> r.fastestLapNumber() != null));
    }

    @Test
    void parsesGtpTeamsStandings() throws IOException {
        StandingsImport imp = ImportParser.parseStandings(fixture("standings-gtp-teams-2026.json"));
        assertEquals("IMSA WeatherTech SportsCar Championship GTP Teams", imp.mainTitle());
        assertEquals("2026", imp.year());
        assertEquals(22, imp.sessions().size()); // 11 rounds x (Qualifying + Race)
        assertEquals(11, imp.rows().size());

        StandingsImport.Row leader = imp.rows().get(0);
        assertEquals(1, leader.position());
        assertEquals("31", leader.key());
        assertEquals("Cadillac Whelen", leader.team());
        assertEquals(2145.0, leader.totalPoints());
        assertEquals(22, leader.pointsBySession().size());
        // Daytona qualifying scored separately from the race: the points split is real data.
        assertEquals(20.0, leader.pointsBySession().get(0).totalPoints());
        assertEquals(320.0, leader.pointsBySession().get(1).totalPoints());
    }

    @Test
    void standingsKeepLeadingZeroCarNumbers() throws IOException {
        StandingsImport gtd = ImportParser.parseStandings(fixture("standings-gtd-teams-2026.json"));
        assertTrue(gtd.rows().stream().anyMatch(r -> r.key().equals("023")));
        assertTrue(gtd.rows().stream().anyMatch(r -> r.key().equals("068")));

        StandingsImport lmp2 = ImportParser.parseStandings(fixture("standings-lmp2-teams-2026.json"));
        assertTrue(lmp2.rows().stream().anyMatch(r -> r.key().equals("04")));
    }

    @Test
    void parsesDriversStandingsKeyedByName() throws IOException {
        StandingsImport imp = ImportParser.parseStandings(fixture("standings-gtp-drivers-2026.json"));
        assertEquals("IMSA WeatherTech SportsCar Championship GTP Drivers", imp.mainTitle());
        assertEquals(39, imp.rows().size());

        // Drivers championships key competitors by full name, with no team field.
        StandingsImport.Row leader = imp.rows().get(0);
        assertEquals("Jack Aitken", leader.key());
        assertNull(leader.team());

        // Full-season co-drivers share a position: the file has two P3s, two P4s, etc.
        List<String> p3 = imp.rows().stream()
                .filter(r -> r.position() == 3)
                .map(StandingsImport.Row::key)
                .toList();
        assertEquals(2, p3.size());
        assertTrue(p3.contains("Nick Yelloly") && p3.contains("Renger van der Zande"));
    }

    @Test
    void parsesMichelinEnduranceCupCheckpointStandings() throws IOException {
        StandingsImport imp = ImportParser.parseStandings(fixture("standings-imec-gtp-teams-2026.json"));
        assertEquals("IMSA Michelin Endurance Cup GTP Teams", imp.mainTitle());
        assertEquals("2026", imp.year());

        // Cup structure: 5 endurance rounds scored at in-race checkpoints, not quali/race.
        assertEquals(14, imp.sessions().size());
        List<String> daytonaCheckpoints = imp.sessions().stream()
                .filter(s -> s.eventName().equals("Daytona"))
                .map(StandingsImport.SessionRef::sessionName)
                .toList();
        assertEquals(List.of("Hour 6", "Hour 12", "Hour 18", "Finish"), daytonaCheckpoints);
        assertEquals(5, imp.sessions().stream().map(StandingsImport.SessionRef::eventName).distinct().count());

        StandingsImport.Row leader = imp.rows().get(0);
        assertEquals("7", leader.key());
        assertEquals("Porsche Penske Motorsport", leader.team());
        assertEquals(39.0, leader.totalPoints());
    }

    @Test
    void imecUsesLongFormClassNames() throws IOException {
        // IMEC titles spell classes long-form ("GT Daytona PRO") while race results
        // use short codes ("GTDPRO"); normalization is deferred to Phase 2.
        StandingsImport imp = ImportParser.parseStandings(fixture("standings-imec-gtdpro-teams-2026.json"));
        assertEquals("IMSA Michelin Endurance Cup GT Daytona PRO Teams", imp.mainTitle());
    }

    @Test
    void allFixturesParseWithoutError() throws IOException {
        for (String name : List.of("race-daytona-2026.json", "race-sebring-2026.json",
                "race-long-beach-2026.json", "race-laguna-2026.json", "race-detroit-2026.json",
                "race-wgi-2026.json")) {
            RaceResultsImport imp = ImportParser.parseRaceResults(fixture(name));
            assertTrue(imp.rows().size() > 20, name);
            assertNotNull(imp.sessionStart(), name);
        }
        for (String name : List.of("standings-gtp-teams-2026.json", "standings-lmp2-teams-2026.json",
                "standings-gtdpro-teams-2026.json", "standings-gtd-teams-2026.json")) {
            StandingsImport imp = ImportParser.parseStandings(fixture(name));
            assertTrue(imp.rows().size() >= 11, name);
            assertEquals(22, imp.sessions().size(), name);
        }
        for (String name : List.of("standings-imec-gtp-teams-2026.json", "standings-imec-lmp2-teams-2026.json",
                "standings-imec-gtdpro-teams-2026.json", "standings-imec-gtd-teams-2026.json")) {
            StandingsImport imp = ImportParser.parseStandings(fixture(name));
            assertTrue(imp.rows().size() >= 11, name);
            assertEquals(14, imp.sessions().size(), name);
        }
    }

    @Test
    void parsesEntryListContractFromPythonSidecar() throws IOException {
        JsonNode root = fixture("entry-list-ctmp-2026.json");
        assertTrue(ImportParser.looksLikeEntryList(root));

        EntryListImport imp = ImportParser.parseEntryList(root);
        assertEquals("Chevrolet Grand Prix", imp.event().name());
        assertEquals("Canadian Tire Motorsport Park", imp.event().circuit());
        assertEquals("IWSC", imp.event().series());
        assertEquals(java.time.LocalDate.of(2026, 7, 10), imp.event().startDate());
        assertEquals(33, imp.entries().size());
        assertEquals(33, imp.event().totalEntries());

        // TBD seats survive: #37 Intersport announced only Jon Field.
        EntryListImport.Entry car37 = imp.entries().stream()
                .filter(e -> e.carNumber().equals("37")).findFirst().orElseThrow();
        assertEquals(2, car37.drivers().size());
        assertTrue(car37.drivers().get(1).isTbd());
        assertNull(car37.drivers().get(1).rating());

        // Sponsor split from the italic-shear detection, ratings as letters.
        EntryListImport.Entry car52 = imp.entries().stream()
                .filter(e -> e.carNumber().equals("52")).findFirst().orElseThrow();
        assertEquals("Bryan Herta Autosport with PR1/Mathiasen", car52.team());
        assertEquals("In Power / MyPrize", car52.sponsor());
        assertEquals("B", car52.drivers().get(0).rating());
    }

    @Test
    void rejectsUnknownDateFormats() {
        assertNull(ImportParser.parseSessionDate(""));
        try {
            ImportParser.parseSessionDate("2026-06-28T12:10:00");
            throw new AssertionError("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // loud failure on a new format is the desired behavior
        }
    }

    // ------------------------------------------------------------ grid CSVs

    private byte[] csvFixture(String name) throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/fixtures/pilot/" + name)) {
            assertNotNull(in, "missing fixture " + name);
            return in.readAllBytes();
        }
    }

    @Test
    void parsesGridCsv() throws IOException {
        GridImport grid = ImportParser.parseGridCsv(csvFixture("grid-race-official.csv"));
        assertEquals(7, grid.rows().size());

        GridImport.Row pole = grid.rows().get(0);
        assertEquals(1, pole.positionOverall());
        assertEquals("26", pole.number());
        assertEquals("GS", pole.className());
        assertEquals("Heart of Racing Team", pole.team());
        assertEquals("Aston Martin Vantage AMR GT4 Evo", pole.vehicle());
        assertEquals("1:53.839", pole.time());

        // The file carries no session or event metadata; the reviewer supplies it.
        assertNull(grid.sessionStart());
        assertNull(grid.championshipName());
        assertNull(grid.sessionName());
        assertNull(grid.circuitName());
    }

    @Test
    void gridCsvDerivesInClassPositions() throws IOException {
        GridImport grid = ImportParser.parseGridCsv(csvFixture("grid-race-official.csv"));
        // GS counts 1..4 over the overall order; TCR restarts at 1.
        List<GridImport.Row> gs = grid.rows().stream().filter(r -> r.className().equals("GS")).toList();
        List<GridImport.Row> tcr = grid.rows().stream().filter(r -> r.className().equals("TCR")).toList();
        assertEquals(4, gs.size());
        assertEquals(3, tcr.size());
        assertEquals(1, gs.get(0).positionInClass());
        assertEquals(4, gs.get(3).positionInClass());
        assertEquals(1, tcr.get(0).positionInClass());
        assertEquals(5, tcr.get(0).positionOverall());
    }

    @Test
    void gridCsvResolvesAttributionNamesToSeats() throws IOException {
        GridImport grid = ImportParser.parseGridCsv(csvFixture("grid-race-official.csv"));

        // "Hannah Grisham" is DRIVER_1 -> seat 1 for both start and qualifying;
        // the CSV builds no roster (full names can't split into first/surname).
        GridImport.Row car26 = grid.rows().get(0);
        assertEquals(1, car26.startingDriverSeat());
        assertEquals(1, car26.qualifyingDriverSeat());
        assertTrue(car26.drivers().isEmpty());

        // "LP Montour" is DRIVER_2 on car 93 -> seat 2, not a default of 1.
        GridImport.Row car93 = grid.rows().stream()
                .filter(r -> r.number().equals("93")).findFirst().orElseThrow();
        assertEquals(2, car93.startingDriverSeat());
        assertEquals(2, car93.qualifyingDriverSeat());

        // Car 5 names a starter but its QUALIFYING_DRIVER cell is blank.
        GridImport.Row car5 = grid.rows().stream()
                .filter(r -> r.number().equals("5")).findFirst().orElseThrow();
        assertEquals(1, car5.startingDriverSeat());
        assertNull(car5.qualifyingDriverSeat());
    }

    @Test
    void gridCsvKeepsLeadingZeroNumbers() throws IOException {
        GridImport grid = ImportParser.parseGridCsv(csvFixture("grid-race-official.csv"));
        assertTrue(grid.rows().stream().anyMatch(r -> r.number().equals("08")));
    }

    @Test
    void gridCsvHandlesBlanksAndTrailingSemicolon() throws IOException {
        GridImport grid = ImportParser.parseGridCsv(csvFixture("grid-race-official.csv"));
        // The last row (no qualifying driver, no time) still imports, time null.
        GridImport.Row last = grid.rows().get(grid.rows().size() - 1);
        assertEquals("5", last.number());
        assertNull(last.time());
        // The trailing semicolon on every line never creates phantom data.
        assertTrue(grid.rows().stream().allMatch(r -> r.number() != null && !r.number().isBlank()));
    }

    @Test
    void gridCsvDetection() throws IOException {
        assertTrue(ImportParser.looksLikeGridCsv(csvFixture("grid-race-official.csv")));
        assertTrue(!ImportParser.looksLikeGridCsv("{\"session\": {}, \"grid\": []}".getBytes()));
    }

    @Test
    void rejectsUnrecognizedCsvHeader() {
        byte[] wrong = "POS;NO;DRIVER\r\n1;26;Hannah Grisham\r\n".getBytes();
        try {
            ImportParser.parseGridCsv(wrong);
            throw new AssertionError("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("POSITION"));
        }
    }

    // ------------------------------------------------------------ results CSVs

    @Test
    void resultsCsvDetection() throws IOException {
        // The three CSV kinds must sniff apart: the same family, three headers.
        assertTrue(ImportParser.looksLikeResultsCsv(csvFixture("results-race-official.csv")));
        assertTrue(!ImportParser.looksLikeResultsCsv(csvFixture("results-qualifying-official.csv")));
        assertTrue(!ImportParser.looksLikeResultsCsv(csvFixture("grid-race-official.csv")));
        assertTrue(ImportParser.looksLikeQualifyingCsv(csvFixture("results-qualifying-official.csv")));
        assertTrue(!ImportParser.looksLikeQualifyingCsv(csvFixture("results-race-official.csv")));
        assertTrue(!ImportParser.looksLikeQualifyingCsv(csvFixture("grid-race-official.csv")));
        assertTrue(!ImportParser.looksLikeGridCsv(csvFixture("results-race-official.csv")));
    }

    @Test
    void parsesResultsCsv() throws IOException {
        RaceResultsImport imp = ImportParser.parseResultsCsv(csvFixture("results-race-official.csv"));
        assertEquals(6, imp.rows().size());
        assertEquals("RACE", imp.sessionType());
        assertEquals(1, imp.sessionOrdinal());
        // No session or event metadata; the reviewer supplies both at commit.
        assertNull(imp.sessionStart());
        assertNull(imp.sessionName());
        assertNull(imp.championshipName());

        RaceResultsImport.Row winner = imp.rows().get(0);
        assertEquals(1, winner.positionOverall());
        assertEquals(1, winner.positionInClass());
        assertEquals("9", winner.number());
        assertEquals("Pro", winner.className());
        assertEquals("JDX Racing", winner.team());
        assertEquals("Porsche 992", winner.vehicle());
        assertEquals("Classified", winner.status());
        assertTrue(!winner.notFinished());
        assertEquals(17, winner.laps());
        assertEquals("46:32.222", winner.elapsedTime());
        assertNull(winner.gapFirst()); // published "-" on the leader's row
        assertEquals("2:16.351", winner.fastestLapTime());
        assertEquals(17, winner.fastestLapNumber());
        assertEquals(144.5, winner.fastestLapKph());
        assertEquals(1, winner.fastestLapDriverSeat());

        RaceResultsImport.Row second = imp.rows().get(1);
        assertEquals("+0.496", second.gapFirst());
        assertEquals("+0.496", second.gapPrevious());
    }

    @Test
    void resultsCsvBuildsSingleDriverRosterWithoutMarkers() throws IOException {
        RaceResultsImport imp = ImportParser.parseResultsCsv(csvFixture("results-race-official.csv"));
        RaceResultsImport.Row winner = imp.rows().get(0);
        assertEquals(1, winner.drivers().size());
        RaceResultsImport.DriverRow driver = winner.drivers().get(0);
        assertEquals(1, driver.seatOrder());
        assertEquals("Parker", driver.firstName());
        // The junior marker "(J)" is glued to the surname in the file; entry
        // lists split markers out, so it must not fork the identity key here.
        assertEquals("Thompson", driver.surname());
        assertEquals("Red Deer, AB", driver.hometown());
    }

    @Test
    void resultsCsvHandlesNotStartedRows() throws IOException {
        RaceResultsImport imp = ImportParser.parseResultsCsv(csvFixture("results-race-official.csv"));
        RaceResultsImport.Row dns = imp.rows().stream()
                .filter(r -> r.number().equals("72")).findFirst().orElseThrow();
        // Blank POSITION + "Not started": the row stays (entry and driver
        // matter) but both positions are null and nothing counts in class.
        assertNull(dns.positionOverall());
        assertNull(dns.positionInClass());
        assertEquals("Not started", dns.status());
        assertTrue(dns.notFinished());
        assertEquals("Not started", dns.notFinishedCause());
        assertEquals(0, dns.laps());
        assertNull(dns.elapsedTime());
        assertNull(dns.fastestLapTime());
        assertNull(dns.fastestLapDriverSeat());
        assertEquals("Phillip", dns.drivers().get(0).firstName());
    }

    @Test
    void resultsCsvDerivesInClassPositions() throws IOException {
        RaceResultsImport imp = ImportParser.parseResultsCsv(csvFixture("results-race-official.csv"));
        // Pro counts 1..3 over the overall order; the first PA991 and Pro-Am
        // cars each restart at 1; the DNS PA991 car advances no counter.
        List<RaceResultsImport.Row> pro = imp.rows().stream()
                .filter(r -> "Pro".equals(r.className())).toList();
        assertEquals(3, pro.size());
        assertEquals(3, pro.get(2).positionInClass());
        RaceResultsImport.Row firstPa991 = imp.rows().stream()
                .filter(r -> "PA991".equals(r.className())).findFirst().orElseThrow();
        assertEquals(13, firstPa991.positionOverall());
        assertEquals(1, firstPa991.positionInClass());
        RaceResultsImport.Row firstProAm = imp.rows().stream()
                .filter(r -> "Pro-Am".equals(r.className())).findFirst().orElseThrow();
        assertEquals(1, firstProAm.positionInClass());
    }

    @Test
    void parsesQualifyingCsv() throws IOException {
        RaceResultsImport imp = ImportParser.parseQualifyingCsv(csvFixture("results-qualifying-official.csv"));
        assertEquals(7, imp.rows().size());
        assertEquals("QUALIFYING", imp.sessionType());
        assertNull(imp.sessionStart());

        RaceResultsImport.Row pole = imp.rows().get(0);
        assertEquals(1, pole.positionOverall());
        assertEquals(1, pole.positionInClass());
        assertEquals("7", pole.number());
        // The classifying lap lands in the fastest_lap_* fields, the same
        // treatment the JSON "Qualifying Practice by Best Lap" spelling gets.
        assertEquals("1:47.446", pole.fastestLapTime());
        assertEquals(8, pole.fastestLapNumber());
        assertEquals(183.3, pole.fastestLapKph());
        assertEquals(1, pole.fastestLapDriverSeat());
        assertEquals(9, pole.laps());
        assertNull(pole.gapFirst());
        assertNull(pole.elapsedTime());
        assertEquals("Maxwell", pole.drivers().get(0).firstName());
        assertEquals("Root", pole.drivers().get(0).surname());
        assertEquals("Silver", pole.drivers().get(0).rating());
    }

    @Test
    void qualifyingCsvKeepsUnclassifiedRows() throws IOException {
        RaceResultsImport imp = ImportParser.parseQualifyingCsv(csvFixture("results-qualifying-official.csv"));
        // A revised classification can drop a car's position but keep its lap:
        // the row stays, unpositioned, and advances no class counter.
        RaceResultsImport.Row excluded = imp.rows().stream()
                .filter(r -> r.number().equals("8")).findFirst().orElseThrow();
        assertNull(excluded.positionOverall());
        assertNull(excluded.positionInClass());
        assertEquals("1:55.721", excluded.fastestLapTime());

        // Lap 0 with no time: the car never set a lap.
        RaceResultsImport.Row noLap = imp.rows().stream()
                .filter(r -> r.number().equals("69")).findFirst().orElseThrow();
        assertNull(noLap.positionOverall());
        assertNull(noLap.fastestLapTime());
        assertNull(noLap.fastestLapNumber());
        assertNull(noLap.fastestLapDriverSeat());

        // A positioned car with no time still classifies (and counts in class).
        RaceResultsImport.Row positionedNoTime = imp.rows().stream()
                .filter(r -> r.number().equals("64")).findFirst().orElseThrow();
        assertEquals(34, positionedNoTime.positionOverall());
        assertEquals(1, positionedNoTime.positionInClass());
        assertNull(positionedNoTime.fastestLapTime());
    }
}
