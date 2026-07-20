package com.pitpass.sheets;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SheetControllerTest {

    @Test
    void teamSimilarityAcceptsSponsorAndCasingChanges() {
        // Same operation, cosmetic changes -> auto-pass.
        assertTrue(SheetController.similarTeams("Vasser Sullivan Racing",
                "Vasser Sullivan Racing w/Dreyer & Reinbold"));
        assertTrue(SheetController.similarTeams("Winward Racing", "WINWARD RACING"));
        assertTrue(SheetController.similarTeams("TDS RACING", "TDS Racing"));
        assertTrue(SheetController.similarTeams("Tower Motorsports", "Tower Motorsports"));
    }

    @Test
    void teamSimilarityRejectsSignificantChanges() {
        // Renames and takeovers -> leave the cell to the broadcaster.
        assertFalse(SheetController.similarTeams("AWA", "13 Autosport"));
        assertFalse(SheetController.similarTeams("PR1 Mathiasen Motorsports",
                "Bryan Herta Autosport with PR1/Mathiasen"));
        assertFalse(SheetController.similarTeams("Ford Multimatic Motorsports", "Ford Racing"));
        assertFalse(SheetController.similarTeams(null, "Anything"));
    }

    @Test
    void venueAbbreviations() {
        assertEquals("CTMP", SheetController.venueAbbrev("Chevrolet Grand Prix", "Canadian Tire Motorsport Park"));
        assertEquals("WGI", SheetController.venueAbbrev("Sahlen's Six Hours of The Glen", "Watkins Glen International"));
        assertEquals("DAY", SheetController.venueAbbrev("Rolex 24 at Daytona", "Daytona International Speedway"));
        assertEquals("LAG", SheetController.venueAbbrev("StubHub Monterey SportsCar Championship",
                "Weathertech Raceway Laguna Seca"));
    }

    @Test
    void ordinals() {
        assertEquals("1st", SheetController.ordinal(1));
        assertEquals("2nd", SheetController.ordinal(2));
        assertEquals("3rd", SheetController.ordinal(3));
        assertEquals("11th", SheetController.ordinal(11));
        assertEquals("13th", SheetController.ordinal(13));
        assertEquals("21st", SheetController.ordinal(21));
    }
}
