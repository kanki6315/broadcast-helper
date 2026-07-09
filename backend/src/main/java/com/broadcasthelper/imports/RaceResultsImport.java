package com.broadcasthelper.imports;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Normalized form of a timing-provider results JSON (one session of one event).
 * This is what gets staged for review; committing maps it onto the domain tables.
 */
public record RaceResultsImport(
        String championshipName,
        String eventName,
        String sessionName,
        String sessionType,
        String reportMark,
        String reportMessage,
        LocalDateTime sessionStart,
        String circuitName,
        Double circuitLengthM,
        String circuitCountry,
        List<Row> rows
) {

    public record Row(
            int positionOverall,
            int positionInClass,
            String number,
            String className,
            String group,
            String team,
            String vehicle,
            String manufacturer,
            String status,
            boolean notFinished,
            String notFinishedCause,
            Integer laps,
            String elapsedTime,
            String gapFirst,
            String gapPrevious,
            String fastestLapTime,
            Integer fastestLapNumber,
            Double fastestLapKph,
            Integer fastestLapDriverSeat,
            Integer pitStops,
            List<DriverRow> drivers
    ) {
    }

    public record DriverRow(
            int seatOrder,
            String firstName,
            String surname,
            String rating,
            String hometown,
            String country
    ) {
    }
}
