package com.pitpass.browse;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every case is a verbatim race-control message from the imported
 * FlagsAnalysisWithRCMessages files — the extractor must survive the real
 * stream, typos and all, and must never invent a car from a number that
 * belongs to a turn, a lap, a regulation article, or a restart-gap rule.
 */
class RcCarsTest {

    @Test
    void singleCarPrefix() {
        assertEquals(List.of("11"), RcCars.extract("Car 11 Penalty - Track Limits - Warning"));
        assertEquals(List.of("27"),
                RcCars.extract("Car 27: Penalty - Too many crew over wall, considered working on car - Drive Through"));
    }

    @Test
    void uppercaseIncidentReports() {
        assertEquals(List.of("8"), RcCars.extract("CAR 8 SPUN AT TURN 3"));
        assertEquals(List.of("2"), RcCars.extract("CAR 2 OFF COURSE AT TURN 8 & CONTINUED"));
        // The provider's own glitch: a turn number that never arrived.
        assertEquals(List.of("66"), RcCars.extract("CAR 66 OFF COURSE AT TURN  & CONTINUED"));
    }

    @Test
    void multiCarPrefix() {
        assertEquals(List.of("34", "120"),
                RcCars.extract("Car 34 and 120: Penalty - Causing Red Flag during Practice - Loss of fastest Qualifying lap"));
    }

    @Test
    void impoundLists() {
        assertEquals(List.of("43", "04", "99", "52"), RcCars.extract("IMPOUND CARS: 43, 04, 99, 52"));
        assertEquals(List.of("27", "36", "12"), RcCars.extract("IMPOUND CARS: 27, 36 and 12"));
        // Oxford comma variant.
        assertEquals(List.of("43", "52", "99"), RcCars.extract("IMPOUND CARS: 43, 52, and 99"));
    }

    @Test
    void midSentenceCarsList() {
        assertEquals(List.of("13", "27", "66"),
                RcCars.extract("INCIDENT INVOLVING CARS 13, 27 & 66 UNDER REVIEW"));
    }

    @Test
    void numbersInTextAreNotCars() {
        // Turn and lap numbers after the car run has ended.
        assertEquals(List.of("11"),
                RcCars.extract("Car 11: Penalty - Track Limits - Turn 2 - Lap time invalidated - Lap 5  - Warning"));
        // Regulation article and hold duration.
        assertEquals(List.of("27"),
                RcCars.extract("Car 27 Penalty-Failed to perform torque sensor offset test SR 22.3.10-5 Minute Hold"));
        // "10 car lengths": the number precedes "car", so it is not a car.
        assertEquals(List.of("59"),
                RcCars.extract("Car 59: Penalty -Leaving more the 10 car lengths on the restartt-Warning"));
        // Lap range in the note text.
        assertEquals(List.of("34"),
                RcCars.extract("Car 34: Penalty - Failure to adhere to the Controlled Powertrain Parameters -Invalidate Lap 4-7"));
    }

    @Test
    void bareCrossReferencesAreDeliberatelySkipped() {
        // "with 13 & 66" has no car keyword — linking it would be a guess. The
        // matching "INCIDENT INVOLVING CARS 13, 27 & 66" message links them.
        assertEquals(List.of("27"),
                RcCars.extract("Car 27: Penalty - Incident Responsibility with 13 & 66 - Warning"));
    }

    @Test
    void carWithoutNumberYieldsNothing() {
        assertTrue(RcCars.extract("PITS OPEN ALL CARS").isEmpty());
        assertTrue(RcCars.extract("THE CATEGORY & CLASS SPLITS FOR EACH CAR WILL CONCLUDE T7").isEmpty());
        assertTrue(RcCars.extract("Car 2: Penalty - Working on the car from behind the wall - Drive Through")
                .contains("2"));
    }

    @Test
    void plainMessagesYieldNothing() {
        assertTrue(RcCars.extract("GREEN FLAG").isEmpty());
        assertTrue(RcCars.extract("UNDER 5 MINUTES").isEmpty());
        assertTrue(RcCars.extract("PREPARE FOR THE CLASS SPLITS").isEmpty());
        assertTrue(RcCars.extract(null).isEmpty());
        assertTrue(RcCars.extract("").isEmpty());
    }
}
