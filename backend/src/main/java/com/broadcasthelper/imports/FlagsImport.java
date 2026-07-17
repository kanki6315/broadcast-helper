package com.broadcasthelper.imports;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Normalized form of a timing-provider FlagsAnalysisWithRCMessages JSON (one
 * session of one event): the session header shared with results files, plus the
 * chronological stream of flag periods (GF / FCY / FF) and race-control
 * messages. The header also carries report_mark/report_message — often a more
 * current copy than the results file's, since the flags report is generated
 * later; committing one refreshes the session's notes.
 */
public record FlagsImport(
        String championshipName,
        String eventName,
        String sessionName,
        String sessionType,
        int sessionOrdinal, // 1-based within the type (Race 1 / Race 2), from the session name
        String reportMark,
        String reportMessage,
        LocalDateTime sessionStart,
        String circuitName,
        Double circuitLengthM,
        String circuitCountry,
        List<FlagRow> rows
) {

    public record FlagRow(
            String wallTime,   // "14:05:35.677" verbatim
            String elapsed,    // session elapsed at the record; "" -> null, "-" kept verbatim
            String recType,    // GF | FCY | FF | RCMessage | future values verbatim
            String flag,       // "GREEN FLAG" etc. on flag records
            String message,    // race-control message text, verbatim
            String flagTime,   // this period's duration
            String accumTime,  // accumulated duration of this flag state
            Integer lap
    ) {
    }
}
