package com.pitpass.live;

import com.pitpass.live.LiveEventMatch.FeedCar;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

/**
 * Files recorded sessions under Pit Pass events by series weekend: Al Kamel's
 * feed event (one championship at one weekend, live_feed_event) is bound to
 * an event, and every session of it follows. A session's own override wins
 * over its weekend (live_session.event_override).
 *
 * An unbound feed event is tried, in order:
 * <ol>
 *   <li>its championship's other feed event at the same track and week, if
 *       that one is bound — in case Al Kamel's event id is not stable;</li>
 *   <li>the championship by name ({@code champName} = series.name, else a
 *       series_alias) → that series' one event dated −1 to +6 days from the
 *       first session (Pit Pass stores the race day; practice runs days
 *       before);</li>
 *   <li>the event the connection is bound to, while live.</li>
 * </ol>
 * 2 and 3 must also pass {@link LiveEventMatch} — the championship decides,
 * the entry list confirms. Nothing automatic replaces a binding once made, or
 * touches an admin's. Unbound, the sessions stay recorded and viewable.
 * See docs/LIVE_TIMING_ALL_SERIES_PLAN.md, slice 3.
 */
@Component
public class LiveFiling {

    static final int DAYS_BEFORE_EVENT = 6;
    static final int DAYS_AFTER_EVENT = 1;
    static final Duration INHERIT_WITHIN = Duration.ofDays(7);
    static final Duration SWEEP_LOOKBACK = Duration.ofDays(14);

    private static final Logger log = LoggerFactory.getLogger(LiveFiling.class);

    /** A feed event and what its sessions say about it. */
    record FeedEvent(long id, Long eventId, String boundBy, Long champDbId, String champName, String shortName,
                     Long firstSessionMs) {

        /** Bound for good: nothing automatic looks at it again. */
        boolean settled() {
            return "ADMIN".equals(boundBy) || "ADMIN_NONE".equals(boundBy) || ("AUTO".equals(boundBy) && eventId != null);
        }
    }

    private final JdbcClient db;
    private final LiveEntryMatcher matcher;
    private final LiveDriverResolver drivers;

    public LiveFiling(JdbcClient db, LiveEntryMatcher matcher, LiveDriverResolver drivers) {
        this.db = db;
        this.matcher = matcher;
        this.drivers = drivers;
    }

    /**
     * The session on track: its feed event, the connection's event as a hint,
     * and its cars from the live tree. Binds the feed event if it can, then
     * brings its sessions into line. Returns whether the feed event is bound
     * (to an event or, by an admin, to none) — false means "try again later".
     */
    public boolean fileLive(long feedEventDbId, Long hintEventId, List<FeedCar> cars) {
        Optional<FeedEvent> found = load(feedEventDbId);
        if (found.isEmpty()) {
            return false; // the writer has not recorded it yet
        }
        boolean settled = found.get().settled() || resolve(found.get(), hintEventId, cars) != null;
        apply(feedEventDbId);
        return settled;
    }

    /**
     * Unbound feed events seen in the last two weeks, tried again from their
     * stored cars — entries imported, an event created or an alias added
     * since. Also brings every recent feed event's sessions into line.
     * Returns how many feed events it bound.
     */
    public int sweep() {
        List<Long> recent = db.sql("""
                SELECT DISTINCT s.feed_event_db_id FROM live_session s
                WHERE s.feed_event_db_id IS NOT NULL AND s.first_seen_at > :since
                """)
                .param("since", java.sql.Timestamp.from(Instant.now().minus(SWEEP_LOOKBACK)))
                .query(Long.class).list();
        int bound = 0;
        for (long id : recent) {
            bound += retry(id);
        }
        return bound;
    }

    /** One championship's unbound feed events, tried again (an alias was just added). */
    public int sweepChampionship(String champName) {
        List<Long> ids = db.sql("""
                SELECT DISTINCT feed_event_db_id FROM live_session
                WHERE feed_event_db_id IS NOT NULL AND lower(champ_name) = lower(:n)
                """)
                .param("n", champName).query(Long.class).list();
        int bound = 0;
        for (long id : ids) {
            bound += retry(id);
        }
        return bound;
    }

    private int retry(long feedEventDbId) {
        int bound = 0;
        try {
            Optional<FeedEvent> f = load(feedEventDbId);
            if (f.isPresent() && !f.get().settled() && resolve(f.get(), null, storedCars(feedEventDbId)) != null) {
                bound = 1;
            }
            apply(feedEventDbId);
        } catch (RuntimeException e) {
            log.warn("Live filing: feed event {} failed: {}", feedEventDbId, e.toString());
        }
        return bound;
    }

    /** The series a feed championship name stands for: series.name, else a series_alias. Null when none or several. */
    public Long seriesFor(String champName) {
        if (champName == null || champName.isBlank()) {
            return null;
        }
        List<Long> ids = db.sql("""
                SELECT id FROM series WHERE lower(name) = lower(:n)
                UNION
                SELECT series_id FROM series_alias WHERE lower(alias) = lower(:n)
                """)
                .param("n", champName.trim()).query(Long.class).list();
        return ids.size() == 1 ? ids.getFirst() : null;
    }

    // ---- an admin's say -----------------------------------------------------------------

    /** What an admin can say about a series weekend. */
    public enum Binding { EVENT, NONE, AUTO }

    /**
     * EVENT files the weekend under eventId; NONE says it is not in Pit Pass;
     * AUTO hands it back to automatic filing, which tries at once. Either of
     * the first two is final for everything automatic. Returns false when the
     * feed event is unknown.
     */
    public boolean bindByAdmin(long feedEventDbId, Binding binding, Long eventId, String email) {
        int updated = db.sql("""
                UPDATE live_feed_event
                SET event_id = :e, bound_by = :by, bound_by_email = :email,
                    bound_at = CASE WHEN :by IS NULL THEN NULL ELSE clock_timestamp() END
                WHERE feed_event_db_id = :f
                """)
                .param("f", feedEventDbId)
                .param("e", binding == Binding.EVENT ? eventId : null, java.sql.Types.BIGINT)
                .param("by", switch (binding) {
                    case EVENT -> "ADMIN";
                    case NONE -> "ADMIN_NONE";
                    case AUTO -> null;
                }, java.sql.Types.VARCHAR)
                .param("email", binding == Binding.AUTO ? null : email, java.sql.Types.VARCHAR)
                .update();
        if (updated == 0) {
            return false;
        }
        log.info("Live filing: feed event {} set to {} {} by {}", feedEventDbId, binding,
                eventId == null ? "" : eventId, email);
        if (binding == Binding.AUTO) {
            retry(feedEventDbId);
        } else {
            apply(feedEventDbId);
        }
        return true;
    }

    /**
     * One session filed somewhere other than its weekend (eventId), or back
     * with its weekend (null). Returns false when the session is unknown.
     */
    public boolean overrideSession(long sessionDbId, Long eventId) {
        record Row(Long feedEvent) {
        }
        List<Row> rows = db.sql("""
                UPDATE live_session SET event_override = :e WHERE session_db_id = :s RETURNING feed_event_db_id
                """)
                .param("s", sessionDbId).param("e", eventId, java.sql.Types.BIGINT)
                .query((rs, i) -> new Row(rs.getObject("feed_event_db_id", Long.class))).list();
        if (rows.isEmpty()) {
            return false;
        }
        Long feedEvent = rows.getFirst().feedEvent();
        if (feedEvent != null) {
            apply(feedEvent);
        } else if (db.sql("""
                UPDATE live_session SET event_id = event_override, updated_at = clock_timestamp()
                WHERE session_db_id = :s AND event_id IS DISTINCT FROM event_override
                """).param("s", sessionDbId).update() == 1) {
            drivers.rematch(sessionDbId, eventId);
        }
        return true;
    }

    // ---- resolving ---------------------------------------------------------------------

    /** Binds an unbound feed event (AUTO) if one of the three ways finds its event. Returns the event, or null. */
    Long resolve(FeedEvent f, Long hintEventId, List<FeedCar> cars) {
        Long inherited = inherited(f);
        if (inherited != null) {
            return bind(f, inherited, "inherited from the same championship's weekend");
        }
        Long byName = byChampionship(f);
        if (byName != null && confirmed(byName, cars)) {
            return bind(f, byName, "by championship " + f.champName());
        }
        if (hintEventId != null && !hintEventId.equals(byName) && confirmed(hintEventId, cars)) {
            return bind(f, hintEventId, "by the connection's event");
        }
        return null;
    }

    private Long inherited(FeedEvent f) {
        if (f.champDbId() == null || f.shortName() == null || f.firstSessionMs() == null) {
            return null;
        }
        List<Long> events = db.sql("""
                SELECT DISTINCT fe.event_id
                FROM live_feed_event fe JOIN live_session s ON s.feed_event_db_id = fe.feed_event_db_id
                WHERE fe.feed_event_db_id <> :f AND fe.event_id IS NOT NULL AND fe.bound_by IN ('AUTO', 'ADMIN')
                  AND s.champ_db_id = :champ AND lower(s.feed_event_short_name) = lower(:short)
                  AND abs(s.session_date_ms - :first) <= :within
                """)
                .param("f", f.id()).param("champ", f.champDbId()).param("short", f.shortName())
                .param("first", f.firstSessionMs()).param("within", INHERIT_WITHIN.toMillis())
                .query(Long.class).list();
        return events.size() == 1 ? events.getFirst() : null;
    }

    private Long byChampionship(FeedEvent f) {
        Long series = seriesFor(f.champName());
        if (series == null || f.firstSessionMs() == null) {
            return null;
        }
        LocalDate day = Instant.ofEpochMilli(f.firstSessionMs()).atZone(ZoneOffset.UTC).toLocalDate();
        List<Long> events = db.sql("""
                SELECT e.id FROM event e JOIN season se ON se.id = e.season_id
                WHERE se.series_id = :series AND e.event_date BETWEEN :from AND :to
                """)
                .param("series", series)
                .param("from", day.minusDays(DAYS_AFTER_EVENT)).param("to", day.plusDays(DAYS_BEFORE_EVENT))
                .query(Long.class).list();
        return events.size() == 1 ? events.getFirst() : null;
    }

    private boolean confirmed(long eventId, List<FeedCar> cars) {
        return LiveEventMatch.score(cars, matcher.entries(eventId), matcher.classAliases(eventId)).matches();
    }

    private Long bind(FeedEvent f, long eventId, String how) {
        int updated = db.sql("""
                UPDATE live_feed_event
                SET event_id = :e, bound_by = 'AUTO', bound_by_email = NULL, bound_at = clock_timestamp()
                WHERE feed_event_db_id = :f AND (bound_by IS NULL OR (bound_by = 'AUTO' AND event_id IS NULL))
                """)
                .param("e", eventId).param("f", f.id()).update();
        if (updated == 0) {
            return null; // bound meanwhile (an admin, another process)
        }
        log.info("Live filing: feed event {} ({}) bound to event {}, {}", f.id(), f.champName(), eventId, how);
        return eventId;
    }

    /**
     * Sets each of the feed event's sessions to its override, else the feed
     * event's event, and re-matches the drivers of every session that moved.
     * Returns the sessions that moved.
     */
    List<Long> apply(long feedEventDbId) {
        record Moved(long session, Long event) {
        }
        List<Moved> moved = db.sql("""
                UPDATE live_session s
                SET event_id = COALESCE(s.event_override, fe.event_id), updated_at = clock_timestamp()
                FROM live_feed_event fe
                WHERE fe.feed_event_db_id = s.feed_event_db_id AND s.feed_event_db_id = :f
                  AND s.event_id IS DISTINCT FROM COALESCE(s.event_override, fe.event_id)
                RETURNING s.session_db_id, s.event_id
                """)
                .param("f", feedEventDbId)
                .query((rs, i) -> new Moved(rs.getLong("session_db_id"), rs.getObject("event_id", Long.class)))
                .list();
        for (Moved m : moved) {
            drivers.rematch(m.session(), m.event());
            log.info("Live filing: session {} now filed under {}", m.session(), m.event() == null ? "nothing" : m.event());
        }
        return moved.stream().map(Moved::session).toList();
    }

    // ---- reading -----------------------------------------------------------------------

    Optional<FeedEvent> load(long feedEventDbId) {
        return db.sql("""
                SELECT fe.feed_event_db_id, fe.event_id, fe.bound_by,
                       max(s.champ_db_id) AS champ_db_id, max(s.champ_name) AS champ_name,
                       max(s.feed_event_short_name) AS short_name, min(s.session_date_ms) AS first_ms
                FROM live_feed_event fe LEFT JOIN live_session s ON s.feed_event_db_id = fe.feed_event_db_id
                WHERE fe.feed_event_db_id = :f
                GROUP BY fe.feed_event_db_id, fe.event_id, fe.bound_by
                """)
                .param("f", feedEventDbId)
                .query((rs, i) -> new FeedEvent(rs.getLong("feed_event_db_id"), rs.getObject("event_id", Long.class),
                        rs.getString("bound_by"), rs.getObject("champ_db_id", Long.class), rs.getString("champ_name"),
                        rs.getString("short_name"), rs.getObject("first_ms", Long.class)))
                .optional();
    }

    /** The cars of the feed event's latest session with any, as the feed classed them. */
    List<FeedCar> storedCars(long feedEventDbId) {
        return db.sql("""
                SELECT c.car_number, c.feed_class FROM live_car c
                WHERE c.session_db_id = (
                    SELECT s.session_db_id FROM live_session s
                    WHERE s.feed_event_db_id = :f AND EXISTS (SELECT 1 FROM live_car x WHERE x.session_db_id = s.session_db_id)
                    ORDER BY s.session_date_ms DESC NULLS LAST, s.first_seen_at DESC LIMIT 1)
                """)
                .param("f", feedEventDbId)
                .query((rs, i) -> new FeedCar(rs.getString("car_number"), rs.getString("feed_class")))
                .list();
    }
}
