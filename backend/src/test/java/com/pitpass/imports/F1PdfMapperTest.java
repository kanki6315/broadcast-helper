package com.pitpass.imports;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Fixtures are parse_f1_pdf.py's output for real Carrera Cup NA Miami sheets
 *  (parser/samples/*_PCCNA_Miami_*.pdf). */
class F1PdfMapperTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode fixture(String name) throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/fixtures/f1/" + name)) {
            return mapper.readTree(in);
        }
    }

    private static RaceResultsImport.Row row(RaceResultsImport imp, String number) {
        return imp.rows().stream().filter(r -> r.number().equals(number)).findFirst().orElseThrow();
    }

    @Test
    void raceSheetMapsToAMetadataLessResultsBatch() throws IOException {
        RaceResultsImport imp = F1PdfMapper.mapResults(fixture("race-miami-2023-r1.json"));

        // No date anywhere in the sheet: the reviewer picks the event, the
        // title only pre-fills the session.
        assertNull(imp.sessionStart());
        assertEquals("RACE", imp.sessionType());
        assertEquals(1, imp.sessionOrdinal());
        assertEquals("Race 1", imp.sessionName());
        assertEquals("Official", imp.reportMark());
        // The penalties page becomes the session's stewards' notes.
        assertTrue(imp.reportMessage().startsWith("Car 9 - 10 second time penalty"));
        assertEquals(40, imp.rows().size());

        RaceResultsImport.Row winner = imp.rows().get(0);
        assertEquals(1, winner.positionOverall());
        assertEquals(1, winner.positionInClass());
        assertEquals("53", winner.number());
        assertEquals("P", winner.className());
        assertEquals("Kellymoss", winner.team());
        assertEquals("Classified", winner.status());
        assertFalse(winner.notFinished());
        assertEquals(16, winner.laps());
        assertEquals("40:34.588", winner.elapsedTime());
        assertNull(winner.gapFirst());
        assertEquals("1:58.527", winner.fastestLapTime());
        assertEquals(16, winner.fastestLapNumber());
        // KM/H on this sheet is the race average, not the best lap's speed.
        assertNull(winner.fastestLapKph());
        assertEquals(1, winner.fastestLapDriverSeat());
        assertEquals(1, winner.drivers().size());
        assertEquals("Riley", winner.drivers().get(0).firstName());
        assertEquals("Dickinson", winner.drivers().get(0).surname());
    }

    @Test
    void retirementsFollowTheTimingJsonConvention() throws IOException {
        RaceResultsImport imp = F1PdfMapper.mapResults(fixture("race-miami-2023-r1.json"));

        // Classified despite retiring: status Classified, not_finished set.
        RaceResultsImport.Row dnf = row(imp, "28");
        assertEquals(30, dnf.positionOverall());
        assertEquals("Classified", dnf.status());
        assertTrue(dnf.notFinished());
        assertNull(dnf.gapFirst());

        RaceResultsImport.Row lapped = row(imp, "95");
        assertEquals("1 Lap", lapped.gapFirst());
        assertEquals("+127.031", lapped.gapPrevious());
        assertFalse(lapped.notFinished());
    }

    @Test
    void notClassifiedCarKeepsItsRowWithoutPositions() throws IOException {
        RaceResultsImport imp = F1PdfMapper.mapResults(fixture("race-miami-2026-r1.json"));
        RaceResultsImport.Row kleck = row(imp, "78");
        assertNull(kleck.positionOverall());
        assertNull(kleck.positionInClass());
        assertEquals("Not classified", kleck.status());
        assertTrue(kleck.notFinished());
    }

    @Test
    void inClassPositionsFollowOverallOrder() throws IOException {
        RaceResultsImport imp = F1PdfMapper.mapResults(fixture("race-miami-2023-r1.json"));
        // #65 is the first PA car home (8th overall).
        assertEquals(1, row(imp, "65").positionInClass());
        assertEquals(2, row(imp, "82").positionInClass());
    }

    @Test
    void surnamesAreCasedForNewDrivers() throws IOException {
        RaceResultsImport imp = F1PdfMapper.mapResults(fixture("race-miami-2023-r1.json"));
        assertEquals("De La Torre", row(imp, "4").drivers().get(0).surname());
        assertEquals("McCann", row(imp, "8").drivers().get(0).surname());
    }

    @Test
    void qualifyingBestLapLandsInFastestLap() throws IOException {
        RaceResultsImport imp = F1PdfMapper.mapResults(fixture("qualifying-miami-2026.json"));
        assertEquals("QUALIFYING", imp.sessionType());
        assertEquals(1, imp.sessionOrdinal());

        RaceResultsImport.Row pole = imp.rows().get(0);
        assertEquals("40", pole.number());
        assertEquals("1:55.721", pole.fastestLapTime());
        assertNull(pole.fastestLapNumber());
        assertNull(pole.elapsedTime());
        assertNull(pole.gapFirst());
        assertEquals(14, pole.laps());
        assertEquals("Classified", pole.status());

        // The initial passes through; the commit resolves it to a known driver.
        RaceResultsImport.DriverRow initialled = row(imp, "3").drivers().get(0);
        assertEquals("N.", initialled.firstName());
        assertEquals("Lastochkin", initialled.surname());
    }

    @Test
    void gridNamesItsDriverForAttribution() throws IOException {
        GridImport grid = F1PdfMapper.mapGrid(fixture("grid-miami-2026-r1.json"));
        assertNull(grid.sessionStart());
        assertEquals("RACE", grid.sessionType());
        assertEquals(1, grid.sessionOrdinal());
        assertEquals(19, grid.rows().size());

        GridImport.Row pole = grid.rows().get(0);
        assertEquals(1, pole.positionOverall());
        assertEquals(1, pole.positionInClass());
        assertEquals("40", pole.number());
        assertEquals("PRO", pole.className());
        assertEquals("ACI Motorsports", pole.team());
        assertEquals("1:55.721", pole.time());
        assertEquals(1, pole.startingDriverSeat());
        assertEquals(1, pole.qualifyingDriverSeat());

        // The grid spells out the given name the results shorten.
        GridImport.Row lastochkin = grid.rows().stream().filter(r -> r.number().equals("3")).findFirst().orElseThrow();
        assertEquals("Nikita", lastochkin.drivers().get(0).firstName());
        assertEquals("Lastochkin", lastochkin.drivers().get(0).surname());
    }

    @Test
    void recognisesGridDocuments() throws IOException {
        assertTrue(F1PdfMapper.isGrid(fixture("grid-miami-2026-r1.json")));
        assertFalse(F1PdfMapper.isGrid(fixture("qualifying-miami-2026.json")));
    }

    @ParameterizedTest
    @CsvSource({
            "DICKINSON, Dickinson",
            "DE LA TORRE, De La Torre",
            "MCCANN, McCann",
            "O'CONNELL, O'Connell",
            "RIPOLL JR, Ripoll Jr",
            "SMITH-JONES, Smith-Jones",
            "van Berlo, van Berlo",
            "MC, Mc",
    })
    void nameCase(String printed, String expected) {
        assertEquals(expected, F1PdfMapper.nameCase(printed));
    }
}
