package com.broadcasthelper.browse;

import com.broadcasthelper.browse.ReportNotes.SessionNote;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every case here is a verbatim line from a real report_message in the
 * database — the parser must survive the provider's actual habits, typos
 * included, not an idealized grammar.
 */
class ReportNotesTest {

    private static SessionNote only(String message) {
        List<SessionNote> notes = ReportNotes.parse(message);
        assertEquals(1, notes.size());
        return notes.get(0);
    }

    @Test
    void singleCar() {
        SessionNote n = only("Car #77 - Fastest lap time invalidated for causing a Red Flag during "
                + "Practice, as per Article 29.10.1 of the Sporting Regulations");
        assertEquals(List.of("77"), n.carNumbers());
        // Article 29.10.1 must not leak into the car list.
    }

    @Test
    void ampersandPair() {
        assertEquals(List.of("16", "18"), only("Cars #16 & #18 - Discontinued participation").carNumbers());
    }

    @Test
    void longCommaList() {
        SessionNote n = only("Cars #2, #3, #4, #13, #17, #21, #24, #26, #27, #30, #37, #38, #46, "
                + "#54, #64, #67, #76, #77, #91, #92, #95 - Some lap times invalidated due to track limits");
        assertEquals(21, n.carNumbers().size());
        assertEquals("2", n.carNumbers().get(0));
        assertEquals("95", n.carNumbers().get(20));
    }

    @Test
    void andForm() {
        assertEquals(List.of("2", "94"),
                only("Cars #2 and #94 - Lap 3 invalidated due to track limits").carNumbers());
    }

    @Test
    void singularCarWithTwoNumbers() {
        assertEquals(List.of("8", "40"),
                only("Car #8 & #40 - Some Lap times invalidated due to shortcut").carNumbers());
    }

    @Test
    void hashlessNumberInList() {
        // Real provider typo: the last car lost its "#".
        assertEquals(List.of("73", "1", "9", "94", "5"),
                only("Cars #73, #1, #9, #94 & 5 - Discontinue participation").carNumbers());
    }

    @Test
    void numbersInNoteTextAreNotCars() {
        SessionNote n = only("Car #33 - 88 second post race time penalty for unserved "
                + "drive-through for incident responsibility");
        assertEquals(List.of("33"), n.carNumbers());

        // A second " - " inside the text changes nothing: only the first head counts.
        SessionNote p = only("Car #27: Penalty - Incident Responsibility with 13 & 66 - Warning");
        assertEquals(List.of("27"), p.carNumbers());
    }

    @Test
    void lineWithoutCarPrefixIsSessionWide() {
        SessionNote n = only("Race interrupted by red flag - results counted back one lap");
        assertTrue(n.carNumbers().isEmpty());
        assertEquals("Race interrupted by red flag - results counted back one lap", n.text());
    }

    @Test
    void lineWithoutDashHasNoCars() {
        assertTrue(only("Provisional pending technical inspection").carNumbers().isEmpty());
    }

    @Test
    void multiLineWithCrlfAndVerbatimText() {
        List<SessionNote> notes = ReportNotes.parse(
                "Car #2 - 28 second time penalty for unserved drive-through for incident responsability\r\n"
                        + "Car #17 - 10 second time penalty for incident responsability");
        assertEquals(2, notes.size());
        assertEquals(List.of("2"), notes.get(0).carNumbers());
        assertEquals(List.of("17"), notes.get(1).carNumbers());
        // Source typo survives untouched.
        assertTrue(notes.get(0).text().endsWith("responsability"));
    }

    @Test
    void leadingZeroCarNumbersSurvive() {
        assertEquals(List.of("04"), only("Car #04 - Lap 3 invalidated due to track limits").carNumbers());
    }

    @Test
    void nullAndBlankAreEmpty() {
        assertTrue(ReportNotes.parse(null).isEmpty());
        assertTrue(ReportNotes.parse("").isEmpty());
        assertTrue(ReportNotes.parse("  \r\n \n").isEmpty());
    }
}
