package com.broadcasthelper.formats;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

/**
 * Assigns each RACE session a per-series race format (Sprint/Main, rallycross
 * Heat/Consolation/Feature, plain Race) so stats can tally wins and top-fives
 * per kind of race rather than per raw session name.
 *
 * <p>Classification is shape-aware, not name-only: a PESC sprint weekend and a
 * PESC rallycross weekend both contain a session named "Heat 1", but the first
 * is a full-field sprint and the second a six-car heat. The event's whole set
 * of races decides which vocabulary applies. Assignments recompute on every
 * import commit; sessions a reviewer pinned by hand ({@code format_source =
 * 'MANUAL'}) are never overwritten.
 */
@Service
public class RaceFormatService {

    // Stable machine codes: the heuristic's identity for a format within a
    // series. The display name is seeded from these defaults and freely
    // renamable in Manage without disturbing classification.
    public static final String RACE = "RACE";
    public static final String SPRINT = "SPRINT";
    public static final String MAIN = "MAIN";
    public static final String RX_HEAT = "RX_HEAT";
    public static final String RX_CONSOLATION = "RX_CONSOLATION";
    public static final String RX_FEATURE = "RX_FEATURE";

    private final JdbcClient db;

    public RaceFormatService(JdbcClient db) {
        this.db = db;
    }

    private record RaceRow(long id, String name, int ordinal, String source) {
    }

    /** Recompute format assignments for every AUTO race session of one event. */
    public void autoAssignEvent(long eventId) {
        List<RaceRow> races = db.sql("""
                        SELECT id, name, ordinal, format_source
                        FROM race_session
                        WHERE event_id = :eventId AND session_type = 'RACE'
                        ORDER BY ordinal
                        """)
                .param("eventId", eventId)
                .query((rs, i) -> new RaceRow(rs.getLong("id"), rs.getString("name"),
                        rs.getInt("ordinal"), rs.getString("format_source")))
                .list();
        if (races.isEmpty()) {
            return;
        }
        long seriesId = db.sql("""
                        SELECT s.series_id FROM event ev JOIN season s ON s.id = ev.season_id
                        WHERE ev.id = :eventId
                        """)
                .param("eventId", eventId)
                .query(Long.class)
                .single();

        // A rallycross-style meeting: many small races, or anything with a
        // consolation / last-chance race. Otherwise: one race is just a race,
        // same-named races are one repeated format (Mustang's "Race 1"/"Race 2"),
        // and differently named races are a sprint-then-main weekend.
        boolean rallycross = races.size() >= 4
                             || races.stream().anyMatch(r -> {
                                 String b = base(r.name());
                                 return b.contains("consol") || b.contains("last chance");
                             });
        boolean allSameBase = races.stream().map(r -> base(r.name())).distinct().count() == 1;
        int lastOrdinal = races.get(races.size() - 1).ordinal();

        for (RaceRow r : races) {
            if (!"AUTO".equals(r.source())) {
                continue;
            }
            String code = classify(r, rallycross, races.size(), allSameBase, lastOrdinal);
            long formatId = findOrCreateFormat(seriesId, code);
            db.sql("UPDATE race_session SET format_id = :formatId WHERE id = :id AND format_source = 'AUTO'")
                    .param("formatId", formatId)
                    .param("id", r.id())
                    .update();
        }
    }

    private static String classify(RaceRow r, boolean rallycross, int raceCount,
                                   boolean allSameBase, int lastOrdinal) {
        String b = base(r.name());
        if (rallycross) {
            if (b.contains("consol") || b.contains("last chance")) {
                return RX_CONSOLATION;
            }
            if (b.contains("feature") || b.contains("final") || b.contains("main")) {
                return RX_FEATURE;
            }
            if (b.contains("heat")) {
                return RX_HEAT;
            }
            return r.ordinal() == lastOrdinal ? RX_FEATURE : RX_HEAT;
        }
        if (raceCount == 1 || allSameBase) {
            return RACE;
        }
        return r.ordinal() == lastOrdinal ? MAIN : SPRINT;
    }

    /** "Heat 1" -> "heat": the name minus any trailing number, lowercased. */
    private static String base(String name) {
        return name == null ? "" : name.trim()
                .replaceAll("\\s*\\d+$", "")
                .toLowerCase(Locale.ROOT);
    }

    private long findOrCreateFormat(long seriesId, String code) {
        db.sql("""
                        INSERT INTO race_format (series_id, code, name, ordinal)
                        VALUES (:seriesId, :code, :name, :ordinal)
                        ON CONFLICT (series_id, code) DO NOTHING
                        """)
                .param("seriesId", seriesId)
                .param("code", code)
                .param("name", defaultName(code))
                .param("ordinal", defaultOrdinal(code))
                .update();
        return db.sql("SELECT id FROM race_format WHERE series_id = :seriesId AND code = :code")
                .param("seriesId", seriesId)
                .param("code", code)
                .query(Long.class)
                .single();
    }

    static String defaultName(String code) {
        return switch (code) {
            case SPRINT -> "Sprint";
            case MAIN -> "Main";
            case RX_HEAT -> "Heat";
            case RX_CONSOLATION -> "Consolation";
            case RX_FEATURE -> "Feature";
            default -> "Race";
        };
    }

    static int defaultOrdinal(String code) {
        return switch (code) {
            case SPRINT -> 1;
            case MAIN -> 2;
            case RX_HEAT -> 3;
            case RX_CONSOLATION -> 4;
            case RX_FEATURE -> 5;
            default -> 1;
        };
    }
}
