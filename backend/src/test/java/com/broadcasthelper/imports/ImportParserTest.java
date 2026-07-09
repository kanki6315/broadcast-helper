package com.broadcasthelper.imports;

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
        try (InputStream in = getClass().getResourceAsStream("/fixtures/imsa/" + name)) {
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
        assertTrue(sebring.rows().stream().anyMatch(r -> "Not Started".equals(r.status())));
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
}
