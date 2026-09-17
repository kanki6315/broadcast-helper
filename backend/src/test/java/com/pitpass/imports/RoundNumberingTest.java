package com.pitpass.imports;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Round numbers go to the events marked as rounds, by date. The Roar Before
 * the 24 is imported as its own event (its qualifying sets Daytona's grid) but
 * it is not round 1 — the recap matches championship round N to the event
 * numbered N, and with the Roar counted every column shifted by one — so it
 * is flagged {@code is_round = false} and skipped. The shape of an event's
 * sessions does not matter: a round that has only qualified so far (the
 * Saturday of a race weekend) keeps its number, and so does a weekend with no
 * sessions yet. Runs against the local dev Postgres like the other
 * @SpringBootTest classes; every write rolls back.
 */
@SpringBootTest
@Transactional
class RoundNumberingTest {

    @Autowired JdbcClient db;
    @Autowired ImportService service;

    @Test
    void onlyEventsMarkedAsRoundsTakeANumber() {
        long seriesId = db.sql("INSERT INTO series (name) VALUES (:n) RETURNING id")
                .param("n", "Round numbering " + UUID.randomUUID()).query(Long.class).single();
        long seasonId = db.sql("INSERT INTO season (series_id, year) VALUES (:s, 2023) RETURNING id")
                .param("s", seriesId).query(Long.class).single();
        long roar = event(seasonId, "ROAR Before the 24", "2023-01-20", false);
        long daytona = event(seasonId, "Rolex 24 at Daytona", "2023-01-28", true);
        long sebring = event(seasonId, "Twelve Hours of Sebring", "2023-03-18", true);
        long future = event(seasonId, "Long Beach", "2023-04-15", true);
        session(roar, "QUALIFYING", "Qualifying");
        session(roar, "RACE", "Qualifying Race");
        session(daytona, "QUALIFYING", "Qualifying");
        session(daytona, "RACE", "Race");
        // Sebring has only qualified so far: still round 2.
        session(sebring, "QUALIFYING", "Qualifying");

        service.renumberSeasonRounds(seasonId);

        assertEquals(List.of(daytona, sebring, future), numbered(seasonId));
        assertEquals(null, ordinal(roar));
        assertEquals(1, ordinal(daytona));
        assertEquals(2, ordinal(sebring));
        assertEquals(3, ordinal(future));

        // Un-marking an event drops its number and closes the gap; marking the
        // Roar hands it one. Both through the admin's override, which renumbers.
        service.setEventRound(sebring, false);
        assertEquals(List.of(daytona, future), numbered(seasonId));
        assertEquals(null, ordinal(sebring));
        assertEquals(2, ordinal(future));
        service.setEventRound(roar, true);
        assertEquals(List.of(roar, daytona, future), numbered(seasonId));
        assertEquals(1, ordinal(roar));
        assertEquals(3, ordinal(future));
    }

    private long event(long seasonId, String name, String date, boolean isRound) {
        return db.sql("INSERT INTO event (season_id, name, event_date, is_round) VALUES (:s, :n, :d::date, :r) RETURNING id")
                .param("s", seasonId).param("n", name).param("d", date).param("r", isRound)
                .query(Long.class).single();
    }

    private void session(long eventId, String type, String name) {
        int ordinal = db.sql("SELECT coalesce(max(ordinal), 0) + 1 FROM race_session WHERE event_id = :e AND session_type = :t")
                .param("e", eventId).param("t", type).query(Integer.class).single();
        db.sql("INSERT INTO race_session (event_id, session_type, name, ordinal) VALUES (:e, :t, :n, :o)")
                .param("e", eventId).param("t", type).param("n", name).param("o", ordinal).update();
    }

    private List<Long> numbered(long seasonId) {
        return db.sql("SELECT id FROM event WHERE season_id = :s AND round_ordinal IS NOT NULL ORDER BY round_ordinal")
                .param("s", seasonId).query(Long.class).list();
    }

    private Integer ordinal(long eventId) {
        return (Integer) db.sql("SELECT round_ordinal FROM event WHERE id = :e").param("e", eventId)
                .query().singleRow().get("round_ordinal");
    }
}
