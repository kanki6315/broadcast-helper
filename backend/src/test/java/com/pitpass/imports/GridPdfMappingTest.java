package com.pitpass.imports;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The grid-PDF sidecar's crew rows (2021 WeatherTech / Pilot Challenge sheets)
 *  map onto a roster with the marked seats; a single-driver sheet stays roster-less. */
class GridPdfMappingTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void aCrewSheetRowCarriesItsRosterAndMarkedSeats() throws Exception {
        GridImport g = ImportService.mapGridPdfJson(json.readTree("""
                {"session": "Race", "race": null, "revised": false, "rows": [
                  {"position": 1, "class": "DPi", "number": "31", "driver": "F. Nasr / M. Conway / P. Derani",
                   "team": "Whelen Engineering Racing", "car": "Cadillac DPi", "time": null,
                   "drivers": [{"name": "F. Nasr"}, {"name": "M. Conway"}, {"name": "P. Derani"}],
                   "starting_driver_seat": 1, "qualifying_driver_seat": 3}
                ]}"""));
        assertEquals(1, g.sessionOrdinal()); // numberless title: the weekend's one race
        GridImport.Row row = g.rows().get(0);
        assertEquals(3, row.drivers().size());
        assertEquals("F.", row.drivers().get(0).firstName());
        assertEquals("Nasr", row.drivers().get(0).surname());
        assertEquals(3, row.drivers().get(2).seatOrder());
        assertEquals(1, row.startingDriverSeat());
        assertEquals(3, row.qualifyingDriverSeat());
    }

    @Test
    void aSingleDriverSheetRowStaysRosterless() throws Exception {
        GridImport g = ImportService.mapGridPdfJson(json.readTree("""
                {"session": "Race 2", "race": 2, "revised": true, "rows": [
                  {"position": 1, "class": "Pro", "number": "15", "driver": "Seb Priaulx(J)",
                   "team": "Kelly-Moss Road and Race", "car": "Porsche 911 GT3 Cup", "time": "2:07.001"}
                ]}"""));
        assertEquals(2, g.sessionOrdinal());
        GridImport.Row row = g.rows().get(0);
        assertEquals(0, row.drivers().size());
        assertNull(row.startingDriverSeat());
        assertNull(row.qualifyingDriverSeat());
    }
}
