package com.pitpass.imports;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Session names as Al Kamel actually published them (2019–2026 coverage crawl). */
class SessionNamesTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', nullValues = "NULL", value = {
            // Plain names keep the trailing-ordinal rule.
            "QUALIFYING | Qualifying                              | NULL",
            "QUALIFYING | Qualifying 2                            | NULL",
            "RACE       | Race                                    | NULL",
            "RACE       | Race 2                                  | NULL",
            "RACE       | RACE                                    | NULL",
            "QUALIFYING | QUALIFY                                 | NULL",
            // Names without their type word are ordinal-keyed race sessions.
            "RACE       | Heat 1                                  | NULL",
            "RACE       | Feature                                 | NULL",
            "RACE       | Hour 6                                  | NULL",
            // 2021 WeatherTech split qualifying, every spelling seen.
            "QUALIFYING | Qualifying - GTD Position               | GTD Position",
            "QUALIFYING | Qualifying - GTD Points GTLM             | GTD Points GTLM",
            "QUALIFYING | Qualifying - GTD Points-GTLM             | GTD Points-GTLM",
            "QUALIFYING | Qualifying - GTD Points - GTLM           | GTD Points - GTLM",
            "QUALIFYING | Qualifying - LMP3 Position - Points      | LMP3 Position - Points",
            "QUALIFYING | Qualifying - LMP2 DPi Position - Points  | LMP2 DPi Position - Points",
            "QUALIFYING | Qualifying - LMP2-DPi                    | LMP2-DPi",
            "QUALIFYING | Qualifying- GTD - Points-GTLM            | GTD - Points-GTLM",
            "QUALIFYING | Qualifying - DPi                         | DPi",
            // Roar class splits and a qualifying race.
            "QUALIFYING | Qualifying GTD                          | GTD",
            "QUALIFYING | Qualifying DPi - LMP2 - GTLM            | DPi - LMP2 - GTLM",
            "QUALIFYING | R24H Qualifying Race                    | R24H Race",
            // A postponed race run at a later event.
            "RACE       | Miami Make-Up - Race 2                  | Miami Make-Up",
    })
    void splitLabel(String type, String name, String expected) {
        assertEquals(expected, SessionNames.splitLabel(type, name));
    }

    @Test
    void splitLabelNeedsATypeAndName() {
        assertNull(SessionNames.splitLabel("QUALIFYING", null));
        assertNull(SessionNames.splitLabel(null, "Qualifying - GTD"));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', nullValues = "NULL", value = {
            "03_Results_Qualifying - GTD Position.CSV               | Qualifying - GTD Position",
            "03_Results_Race 1_Official.json                        | Race 1",
            "03_Results_Race_Official.CSV                           | Race",
            "03_Results_Qualifying (1).json                         | Qualifying",
            "00_Grid_Race 2_Official_Amended 2.CSV                  | Race 2",
            "01_Grid_Race 1_Provisional REVISED.pdf                 | Race 1",
            "01_Starting Grid JSON_Race 1_Official.json             | Race 1",
            "04_Results by 2nd Fastest Lap_Qualifying.CSV           | Qualifying",
            "03_Results_Miami Make-Up - Race 2_Official.CSV         | Miami Make-Up - Race 2",
            "/tmp/x/03_Results_Qualifying - DPi.CSV                 | Qualifying - DPi",
            "results-race-official.csv                              | NULL",
            "2021_PCCNA_Sebring_Grid_R1.pdf                         | NULL",
    })
    void sessionNameFromFilename(String filename, String expected) {
        assertEquals(expected, SessionNames.sessionNameFromFilename(filename));
    }

    @Test
    void pointsOnlyClasses() {
        assertEquals(Set.of("GTD"), SessionNames.pointsOnlyClasses("GTD Points GTLM", List.of("GTD", "GTLM")));
        assertEquals(Set.of("GTD"), SessionNames.pointsOnlyClasses("GTD Points-GTLM", List.of("GTLM", "GTD")));
        assertEquals(Set.of("GTD"), SessionNames.pointsOnlyClasses("GTD - Points-GTLM", List.of("GTD", "GTLM")));
        assertEquals(Set.of("GTD"), SessionNames.pointsOnlyClasses("GTD Points", List.of("GTD")));
        assertEquals(Set.of(), SessionNames.pointsOnlyClasses("GTD Position", List.of("GTD")));
        assertEquals(Set.of(), SessionNames.pointsOnlyClasses("LMP3 Position - Points", List.of("LMP3")));
        assertEquals(Set.of(), SessionNames.pointsOnlyClasses("LMP2 DPi Position - Points", List.of("LMP2", "DPi")));
        assertEquals(Set.of(), SessionNames.pointsOnlyClasses("LMP2-DPi", List.of("LMP2", "DPi")));
        // "DPi" must not match inside another word, nor a class the label never names.
        assertEquals(Set.of(), SessionNames.pointsOnlyClasses("GTD Points", List.of("LMP2")));
        assertEquals(Set.of(), SessionNames.pointsOnlyClasses(null, List.of("GTD")));
    }

    @Test
    void normalizeIgnoresCaseAndSeparators() {
        assertEquals(SessionNames.normalize("Qualifying - GTD Points-GTLM"),
                SessionNames.normalize("QUALIFYING GTD POINTS GTLM"));
    }

    @Test
    void ordinalNeedsASeparateNumber() {
        assertEquals(2, ImportParser.sessionOrdinal("Race 2"));
        assertEquals(1, ImportParser.sessionOrdinal("Qualifying - LMP2"));
        assertEquals(1, ImportParser.sessionOrdinal("Qualifying"));
        assertEquals(12, ImportParser.sessionOrdinal("Hour 12"));
    }
}
