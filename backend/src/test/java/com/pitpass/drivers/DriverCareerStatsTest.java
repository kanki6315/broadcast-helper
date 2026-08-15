package com.pitpass.drivers;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@Transactional
class DriverCareerStatsTest {

    @Autowired JdbcClient db;
    @Autowired DriverController controller;

    /** Same rule as the team profile: a qualifying stage keeps its badged
     *  season line but never reaches the career headline or the series
     *  all-time rollup. */
    @Test
    void qualifierSeasonsStayOutOfCareerAndSeriesRollups() {
        String suffix = UUID.randomUUID().toString();
        long driverId = db.sql("""
                        INSERT INTO driver (first_name, surname) VALUES ('Quali', :sn) RETURNING id
                        """)
                .param("sn", "Stage " + suffix).query(Long.class).single();
        long seriesId = db.sql("INSERT INTO series (name) VALUES (:n) RETURNING id")
                .param("n", "Driver qualifier series " + suffix).query(Long.class).single();
        long main = db.sql("INSERT INTO season (series_id, year) VALUES (:s, 2099) RETURNING id")
                .param("s", seriesId).query(Long.class).single();
        long qualifier = db.sql("""
                        INSERT INTO season (series_id, year, kind, label)
                        VALUES (:s, 2099, 'QUALIFIER', 'Regional — Test') RETURNING id
                        """)
                .param("s", seriesId).query(Long.class).single();

        raceWeekend(main, driverId, 1, 1);
        raceWeekend(qualifier, driverId, 1, 1);

        DriverController.DriverStats stats = controller.stats(driverId);

        assertEquals(1, stats.career().starts());
        assertEquals(1, stats.career().wins());
        assertEquals(1, stats.career().poles());

        assertEquals(1, stats.bySeries().size());
        assertEquals(1, stats.bySeries().get(0).byFormat().stream().mapToInt(l -> l.starts()).sum());
        assertEquals(1, stats.bySeries().get(0).quali().poles());

        assertEquals(2, stats.seasons().size());
        var qualifierLine = stats.seasons().stream()
                .filter(s -> s.seasonId() == qualifier).findFirst().orElseThrow();
        assertTrue(qualifierLine.qualifier());
        assertEquals("Regional — Test", qualifierLine.seasonLabel());
        assertFalse(stats.seasons().stream()
                .filter(s -> s.seasonId() == main).findFirst().orElseThrow().qualifier());
    }

    private void raceWeekend(long seasonId, long driverId, int racePos, int qualiPos) {
        long eventId = db.sql("INSERT INTO event (season_id, name) VALUES (:s, 'Round') RETURNING id")
                .param("s", seasonId).query(Long.class).single();
        long entryId = db.sql("""
                        INSERT INTO entry (event_id, car_number, class_name, team_name)
                        VALUES (:event, '7', 'P', 'Quali stage team') RETURNING id
                        """)
                .param("event", eventId).query(Long.class).single();
        // Sole crew member: quali results attribute to them without a grid row.
        db.sql("INSERT INTO driver_assignment (entry_id, driver_id, seat_order) VALUES (:e, :d, 1)")
                .param("e", entryId).param("d", driverId).update();
        for (String type : new String[] {"RACE", "QUALIFYING"}) {
            long sessionId = db.sql("""
                            INSERT INTO race_session (event_id, session_type, name)
                            VALUES (:event, :type, :type) RETURNING id
                            """)
                    .param("event", eventId).param("type", type).query(Long.class).single();
            db.sql("""
                            INSERT INTO result (session_id, entry_id, position_overall, position_in_class)
                            VALUES (:session, :entry, :pos, :pos)
                            """)
                    .param("session", sessionId).param("entry", entryId)
                    .param("pos", type.equals("RACE") ? racePos : qualiPos)
                    .update();
        }
    }
}
