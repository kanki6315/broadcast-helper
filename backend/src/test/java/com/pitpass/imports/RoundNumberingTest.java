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
 * Round numbers go to the weekends that race. The Roar Before the 24 is
 * imported as its own event (its qualifying sets Daytona's grid) but it is not
 * round 1 — the recap matches championship round N to the event numbered N,
 * and with the Roar counted every column shifted by one. A weekend with no
 * sessions yet is a round to come and keeps its place. Runs against the local
 * dev Postgres like the other @SpringBootTest classes; every write rolls back.
 */
@SpringBootTest
@Transactional
class RoundNumberingTest {

    @Autowired JdbcClient db;
    @Autowired ImportService service;

    @Test
    void qualifyingOnlyWeekendsTakeNoRoundNumber() {
        long seriesId = db.sql("INSERT INTO series (name) VALUES (:n) RETURNING id")
                .param("n", "Round numbering " + UUID.randomUUID()).query(Long.class).single();
        long seasonId = db.sql("INSERT INTO season (series_id, year) VALUES (:s, 2023) RETURNING id")
                .param("s", seriesId).query(Long.class).single();
        long roar = event(seasonId, "ROAR Before the 24", "2023-01-20");
        long daytona = event(seasonId, "Rolex 24 at Daytona", "2023-01-28");
        long sebring = event(seasonId, "Twelve Hours of Sebring", "2023-03-18");
        long future = event(seasonId, "Long Beach", "2023-04-15");
        session(roar, "QUALIFYING", "Qualifying");
        session(daytona, "QUALIFYING", "Qualifying");
        session(daytona, "RACE", "Race");
        session(sebring, "RACE", "Race");

        service.renumberSeasonRounds(seasonId);

        assertEquals(List.of(daytona, sebring, future), numbered(seasonId));
        assertEquals(null, ordinal(roar));
        assertEquals(1, ordinal(daytona));
        assertEquals(2, ordinal(sebring));
        assertEquals(3, ordinal(future));

        // A numbered weekend that loses its race loses its number; the Roar
        // gaining one changes nothing (see below).
        session(roar, "RACE", "Race");
        db.sql("DELETE FROM race_session WHERE event_id = :e").param("e", sebring).update();
        session(sebring, "PRACTICE", "Practice 1");
        service.renumberSeasonRounds(seasonId);
        // The Roar is the exception: 2021–22 it ran a qualifying race that set
        // Daytona's grid, a RACE session in every respect except being a round.
        assertEquals(List.of(daytona, future), numbered(seasonId));
        assertEquals(null, ordinal(roar));
        assertEquals(null, ordinal(sebring));
    }

    private long event(long seasonId, String name, String date) {
        return db.sql("INSERT INTO event (season_id, name, event_date) VALUES (:s, :n, :d::date) RETURNING id")
                .param("s", seasonId).param("n", name).param("d", date).query(Long.class).single();
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
