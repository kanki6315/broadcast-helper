package com.pitpass.imports;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Standings titles from the 2021 sheets: Lamborghini Super Trofeo names no
 * series at all ("PRO Driver Championship"), so a fetched sheet takes the
 * folder's series as a prefix; and several series hang a word after the kind
 * ("Driver Championship", "Bronze Drivers Cup") that must not be read as the
 * kind itself.
 */
class StandingsTitleTest {

    @Test
    void aTitleNamingNoSeriesTakesTheFolderSeriesAsPrefix() {
        assertEquals("Lamborghini Super Trofeo PRO Driver Championship",
                ImportService.withSeriesPrefix("PRO Driver Championship", "Lamborghini Super Trofeo"));
        assertEquals("Lamborghini Super Trofeo Team Championship",
                ImportService.withSeriesPrefix("Team Championship", "Lamborghini Super Trofeo"));
    }

    @Test
    void aTitleNamingItsSeriesIsLeftAlone() {
        // Exactly the recorded name.
        assertEquals("IMSA WeatherTech SportsCar Championship DPi Drivers",
                ImportService.withSeriesPrefix("IMSA WeatherTech SportsCar Championship DPi Drivers",
                        "IMSA WeatherTech SportsCar Championship"));
        // The series' distinctive word, in other wording.
        assertEquals("IMSA WeatherTech SportsCar Championship DPi Drivers",
                ImportService.withSeriesPrefix("IMSA WeatherTech SportsCar Championship DPi Drivers",
                        "WeatherTech SportsCar Championship"));
        assertEquals("Mazda MX-5 Cup Presented By BFGoodrich Drivers",
                ImportService.withSeriesPrefix("Mazda MX-5 Cup Presented By BFGoodrich Drivers", "Mazda MX-5 Cup"));
        // Generic words in the series name do not count as naming it.
        assertEquals("IMSA Michelin Pilot Challenge Grand Sport Drivers",
                ImportService.withSeriesPrefix("IMSA Michelin Pilot Challenge Grand Sport Drivers",
                        "Michelin Pilot Challenge"));
        assertEquals("PRO Driver Championship", ImportService.withSeriesPrefix("PRO Driver Championship", null));
    }

    @Test
    void trailingDecorationIsDroppedSoTheKindWordIsLast() {
        assertEquals("PRO Driver", ImportService.stripTitleDecoration("PRO Driver Championship"));
        assertEquals("P3-1 Bronze Drivers", ImportService.stripTitleDecoration("P3-1 Bronze Drivers Cup"));
        assertEquals("Team", ImportService.stripTitleDecoration("Team Championship"));
        assertEquals("GTP Drivers", ImportService.stripTitleDecoration("GTP Drivers"));
        // A lone word is never stripped, whatever it is.
        assertEquals("Championship", ImportService.stripTitleDecoration("Championship"));
    }

    @Test
    void cupTablesAreRecognisedWithTheirFamilyAndClass() {
        ImportService.CupGuess imec = ImportService.cupOf("IMSA Michelin Endurance Cup GT Daytona PRO Drivers", "Daytona");
        assertEquals("Michelin Endurance Cup", imec.family());
        assertEquals("GTDPRO", imec.className());
        assertEquals("GTD", ImportService.cupOf("IMSA Michelin Endurance Cup GT Daytona Teams", null).className());
        assertEquals("LMP2", ImportService.cupOf("IMEC LMP2 DRIVERS OVERALL", null).className());
        ImportService.CupGuess bronze = ImportService.cupOf("IMSA Michelin Pilot Challenge Grand Sport BRONZE Drivers",
                "Grand Sport BRONZE");
        assertEquals("Bronze Cup", bronze.family());
        assertEquals("Grand Sport", bronze.className());
        ImportService.CupGuess rookie = ImportService.cupOf("Porsche Carrera Cup North America Rookie Drivers", "Rookie");
        assertEquals("Rookie Cup", rookie.family());
        assertNull(rookie.className());
        assertNull(ImportService.cupOf("IMSA WeatherTech SportsCar Championship GTP Drivers", "GTP"));
        assertNull(ImportService.cupOf("Mustang Challenge DH Drivers", "DH"));
    }
}
