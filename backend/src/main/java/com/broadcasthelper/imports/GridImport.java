package com.broadcasthelper.imports;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Normalized form of a timing-provider starting-grid JSON (the grid for one race
 * session). Published as its own file — the grid reflects the fastest-two-laps
 * carry-over plus any penalties, so it is the only true starting order and is
 * always imported, never computed. Committing attaches a start position per entry
 * to the race session it belongs to (matched by (event, session_type, ordinal)).
 */
public record GridImport(
        String championshipName,
        String eventName,
        String sessionName,
        String sessionType,
        int sessionOrdinal, // the race this grid is for (Race 1 / Race 2), from the session name
        LocalDateTime sessionStart,
        String circuitName,
        Double circuitLengthM,
        String circuitCountry,
        List<Row> rows
) {

    public record Row(
            int positionOverall,
            Integer positionInClass, // 1-based within class, derived from overall grid order
            String number,
            String className,
            String group,
            String team,
            String vehicle,
            String manufacturer,
            String time, // qualifying time behind the slot (grid CSVs only; JSON grids carry none)
            Integer startingDriverSeat,   // 1-based seat taking the start; null when the source names none
            Integer qualifyingDriverSeat, // 1-based seat that set the time; null when the source names none
            // The grid's own per-car roster (JSON grids only; empty for CSV and
            // iRacing grids). Null — not empty — when the batch was staged before
            // these fields existed and Jackson filled them in as absent.
            List<RaceResultsImport.DriverRow> drivers
    ) {
    }
}
