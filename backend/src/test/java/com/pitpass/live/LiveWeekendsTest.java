package com.pitpass.live;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Recorded sessions by weekend and series, as the timing page browses them (slice 4). */
@SpringBootTest
@Transactional
class LiveWeekendsTest {

    private static final long DAY = Duration.ofDays(1).toMillis();
    private static final long T0 = LocalDate.of(2099, 9, 30).atTime(16, 25).toInstant(ZoneOffset.UTC).toEpochMilli();

    @Autowired JdbcClient db;
    @Autowired LiveWeekends weekends;
    @Autowired LiveTimingPageService page;

    private static LiveWeekends.Championship champ(long id, String track, long firstMs) {
        return new LiveWeekends.Championship(id, null, "C" + id, null, track, null, null, null, null, firstMs, firstMs,
                List.of(), List.of());
    }

    @Test
    void seriesAtOneTrackWithinDaysAreOneWeekend() {
        var grouped = LiveWeekends.weekends(List.of(
                champ(611, "Road Atlanta", T0),
                champ(612, "Road Atlanta", T0 + 2 * DAY),
                champ(700, "Road Atlanta", T0 + 30 * DAY), // the next visit is another weekend
                champ(800, "Indianapolis", T0 + DAY)));
        assertEquals(List.of("Road Atlanta", "Indianapolis", "Road Atlanta"),
                grouped.stream().map(LiveWeekends.Weekend::track).toList(), "newest first");
        assertEquals(List.of(611L, 612L), grouped.get(2).championships().stream()
                .map(LiveWeekends.Championship::feedEventDbId).toList());
        assertEquals(T0, grouped.get(2).fromMs());
        assertEquals(T0 + 2 * DAY, grouped.get(2).toMs());
    }

    @Test
    void aSeriesWeekendCarriesItsSessionsFilingAndCandidateEvents() {
        String u = UUID.randomUUID().toString().substring(0, 8);
        String champName = "Weekend champ " + u;
        long series = db.sql("INSERT INTO series (name) VALUES (:n) RETURNING id").param("n", champName).query(Long.class).single();
        long season = db.sql("INSERT INTO season (series_id, year) VALUES (:s, 2099) RETURNING id").param("s", series).query(Long.class).single();
        long event = db.sql("INSERT INTO event (season_id, name, round_ordinal, event_date) VALUES (:s, :n, 1, :d) RETURNING id")
                .param("s", season).param("n", "Race " + u).param("d", LocalDate.of(2099, 10, 3)).query(Long.class).single();
        long feedEvent = 900_000_000L + ThreadLocalRandom.current().nextLong(1_000_000L);
        long qualifying = feedEvent + 1; // lower id, later session
        long practice = feedEvent + 2;
        db.sql("INSERT INTO live_feed_event (feed_event_db_id) VALUES (:f)").param("f", feedEvent).update();
        // Qualifying has the lower id but ran later: listed newest first by date, never by id.
        db.sql("""
                INSERT INTO live_session (session_db_id, feed_event_db_id, name, type, session_date_ms, champ_name,
                                          feed_event_name, feed_event_short_name)
                VALUES (:q, :f, 'Qualifying', 'QUALIFYING_BEST_LAP', :t1, :c, 'Petit', 'Road Atlanta'),
                       (:p, :f, 'Practice 2', 'FREE_PRACTICE', :t0, :c, 'Petit', 'Road Atlanta')
                """)
                .param("q", qualifying).param("p", practice).param("f", feedEvent)
                .param("t0", T0).param("t1", T0 + 3 * 3_600_000L).param("c", champName).update();

        var one = weekends.one(feedEvent).orElseThrow();
        assertEquals(champName, one.champName());
        assertNull(one.boundBy());
        assertEquals(List.of("Qualifying", "Practice 2"), one.sessions().stream().map(s -> s.name()).toList());
        assertEquals(event, one.candidates().getFirst().id(), "the championship's own series first");

        assertTrue(weekends.recent(30).stream().flatMap(w -> w.championships().stream())
                .anyMatch(c -> c.feedEventDbId() == feedEvent), "first seen just now");
        assertEquals(2, page.sessionsOfFeedEvent(feedEvent).size());
    }
}
