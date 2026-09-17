package com.pitpass.imports;

import java.time.LocalDateTime;

/**
 * What the fetcher knows about a file from where it found it, for files that
 * say too little about themselves. A results CSV names no series, event, date
 * or venue; a grid PDF not even its session. Fetched from the Al Kamel index
 * the folder path carries all of that — the series folder, the event folder,
 * the session folder's stamp and label — so the staged payload is completed
 * from it and the batch places itself the way a timing JSON does.
 *
 * @param sourceUrl      the file's URL, recorded on the batch.
 * @param sourceModified the index's last-modified cell for the file, recorded
 *                       for the event-refresh comparison.
 * @param sourceEvent    the "YY_YYYY/NN_Event" folder key: groups a weekend's
 *                       batches in the confirm step and, stamped on the event at
 *                       commit, lets a later fetch find the same event.
 * @param year           the year folder's season.
 * @param seriesName     the series' name as stored in the {@code series} table
 *                       (resolved by the planner through the aliases), or null
 *                       when the folder matched no series.
 * @param eventName      the event folder's display name, the proposed event name.
 * @param sessionStart   the session folder's stamp (local wall-clock, no zone),
 *                       null for series-level files (standings, entry list).
 * @param sessionLabel   the session folder's label ("Qualifying - GTD Position",
 *                       "Race 2"), null for series-level files.
 */
public record SourceContext(
        String sourceUrl,
        String sourceModified,
        String sourceEvent,
        Integer year,
        String seriesName,
        String eventName,
        LocalDateTime sessionStart,
        String sessionLabel
) {
}
