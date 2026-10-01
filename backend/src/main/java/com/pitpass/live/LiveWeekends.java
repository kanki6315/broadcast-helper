package com.pitpass.live;

import com.pitpass.live.LiveTimingPageService.SessionSummary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Recorded sessions as the timing page browses them: by weekend, then by
 * series (Al Kamel's feed event), filed or not. A weekend is the feed events
 * at one track (eventShortName) whose first sessions fall within a few days
 * of each other — Al Kamel issues one feed event per series, so nothing in
 * the feed names the weekend itself. Each series carries the Pit Pass events
 * an admin might file it under.
 */
@Component
public class LiveWeekends {

    static final Duration SAME_WEEKEND = Duration.ofDays(5);
    static final int CANDIDATE_DAYS = 10;

    /** An event an admin could file a series weekend under. */
    public record EventOption(long id, String name, String seriesName, LocalDate date) {
    }

    /**
     * One series at one weekend. boundBy: null = not filed yet (automatic
     * filing keeps trying), AUTO, ADMIN, ADMIN_NONE ("not in Pit Pass").
     */
    public record Championship(long feedEventDbId, Long champDbId, String champName, String feedEventName,
                               String track, Long eventId, String eventName, String boundBy, String boundByEmail,
                               Long firstSessionMs, Long lastSessionMs, List<SessionSummary> sessions,
                               List<EventOption> candidates) {
    }

    public record Weekend(String track, Long fromMs, Long toMs, List<Championship> championships) {
    }

    private record Row(long id, Long champDbId, String champName, String feedEventName, String track, Long eventId,
                       String eventName, String boundBy, String boundByEmail, Long firstMs, Long lastMs) {
    }

    private final JdbcClient db;
    private final LiveTimingPageService page;
    private final LiveFiling filing;

    public LiveWeekends(JdbcClient db, LiveTimingPageService page, LiveFiling filing) {
        this.db = db;
        this.page = page;
        this.filing = filing;
    }

    /** Weekends with a session first seen in the last {@code days} days, newest first. */
    public List<Weekend> recent(int days) {
        List<Row> rows = rows("s.feed_event_db_id IN (SELECT feed_event_db_id FROM live_session WHERE first_seen_at > :since)",
                Timestamp.from(Instant.now().minus(Duration.ofDays(days))));
        List<Championship> all = rows.stream().map(this::championship).toList();
        return weekends(all);
    }

    public Optional<Championship> one(long feedEventDbId) {
        return rows("fe.feed_event_db_id = :since", feedEventDbId).stream().findFirst().map(this::championship);
    }

    /** Pure: championships grouped by track, a new weekend when first sessions are days apart. */
    static List<Weekend> weekends(List<Championship> championships) {
        List<Championship> sorted = championships.stream()
                .sorted(Comparator.comparing((Championship c) -> c.firstSessionMs() == null ? 0L : c.firstSessionMs()))
                .toList();
        List<List<Championship>> groups = new ArrayList<>();
        for (Championship c : sorted) {
            List<Championship> into = null;
            for (List<Championship> g : groups) {
                Championship first = g.getFirst();
                if (sameTrack(first.track(), c.track()) && first.firstSessionMs() != null && c.firstSessionMs() != null
                        && c.firstSessionMs() - first.firstSessionMs() <= SAME_WEEKEND.toMillis()) {
                    into = g;
                }
            }
            if (into == null) {
                into = new ArrayList<>();
                groups.add(into);
            }
            into.add(c);
        }
        return groups.stream()
                .map(g -> new Weekend(g.getFirst().track(),
                        g.stream().map(Championship::firstSessionMs).filter(java.util.Objects::nonNull).min(Long::compare).orElse(null),
                        g.stream().map(Championship::lastSessionMs).filter(java.util.Objects::nonNull).max(Long::compare).orElse(null),
                        List.copyOf(g)))
                .sorted(Comparator.comparing((Weekend w) -> w.fromMs() == null ? 0L : w.fromMs()).reversed())
                .toList();
    }

    private static boolean sameTrack(String a, String b) {
        return a == null ? b == null : b != null && a.trim().equalsIgnoreCase(b.trim());
    }

    private List<Row> rows(String where, Object key) {
        return db.sql("""
                SELECT fe.feed_event_db_id, fe.event_id, fe.bound_by, fe.bound_by_email, e.name AS event_name,
                       max(s.champ_db_id) AS champ_db_id, max(s.champ_name) AS champ_name,
                       max(s.feed_event_name) AS feed_event_name, max(s.feed_event_short_name) AS track,
                       min(s.session_date_ms) AS first_ms, max(s.session_date_ms) AS last_ms
                FROM live_feed_event fe
                         JOIN live_session s ON s.feed_event_db_id = fe.feed_event_db_id
                         LEFT JOIN event e ON e.id = fe.event_id
                WHERE %s
                GROUP BY fe.feed_event_db_id, fe.event_id, fe.bound_by, fe.bound_by_email, e.name
                """.formatted(where))
                .param("since", key)
                .query((rs, i) -> new Row(rs.getLong("feed_event_db_id"), rs.getObject("champ_db_id", Long.class),
                        rs.getString("champ_name"), rs.getString("feed_event_name"), rs.getString("track"),
                        rs.getObject("event_id", Long.class), rs.getString("event_name"), rs.getString("bound_by"),
                        rs.getString("bound_by_email"), rs.getObject("first_ms", Long.class),
                        rs.getObject("last_ms", Long.class)))
                .list();
    }

    private Championship championship(Row r) {
        return new Championship(r.id(), r.champDbId(), r.champName(), r.feedEventName(), r.track(), r.eventId(),
                r.eventName(), r.boundBy(), r.boundByEmail(), r.firstMs(), r.lastMs(),
                page.sessionsOfFeedEvent(r.id()), candidates(r));
    }

    /** Events from ten days before to ten days after the weekend; the championship's own series first. */
    private List<EventOption> candidates(Row r) {
        if (r.firstMs() == null) {
            return List.of();
        }
        LocalDate from = day(r.firstMs()).minusDays(CANDIDATE_DAYS);
        LocalDate to = day(r.lastMs() == null ? r.firstMs() : r.lastMs()).plusDays(CANDIDATE_DAYS);
        Long series = filing.seriesFor(r.champName());
        return db.sql("""
                SELECT e.id, e.name, sr.name AS series_name, e.event_date
                FROM event e JOIN season se ON se.id = e.season_id JOIN series sr ON sr.id = se.series_id
                WHERE e.event_date BETWEEN :from AND :to
                ORDER BY (sr.id = :series) DESC, e.event_date, sr.name
                """)
                .param("from", from).param("to", to).param("series", series == null ? -1L : series)
                .query((rs, i) -> new EventOption(rs.getLong("id"), rs.getString("name"), rs.getString("series_name"),
                        rs.getObject("event_date", LocalDate.class)))
                .list();
    }

    private static LocalDate day(long epochMs) {
        return Instant.ofEpochMilli(epochMs).atZone(ZoneOffset.UTC).toLocalDate();
    }
}
