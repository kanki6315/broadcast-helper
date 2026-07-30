package com.pitpass.imports;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pure-logic check of the session-type precedence for metadata-less results
 * files: the payload's own type (a CSV knows race from qualifying by its
 * header) must beat the reviewer's dropdown, which only covers payloads that
 * carry none. The DB-touching commit path is covered by the manual browser
 * pass, since the suite has no database.
 */
class CsvSessionTypeTest {

    @Test
    void payloadTypeWinsOverTarget() {
        assertEquals("QUALIFYING", ImportService.resolveCsvSessionType("QUALIFYING", "RACE"));
        assertEquals("RACE", ImportService.resolveCsvSessionType("RACE", "QUALIFYING"));
    }

    @Test
    void targetTypeCoversPayloadsWithoutOne() {
        assertEquals("QUALIFYING", ImportService.resolveCsvSessionType(null, "QUALIFYING"));
        assertEquals("PRACTICE", ImportService.resolveCsvSessionType(null, "PRACTICE"));
    }

    @Test
    void nothingChosenDefaultsToRace() {
        assertEquals("RACE", ImportService.resolveCsvSessionType(null, null));
    }
}
