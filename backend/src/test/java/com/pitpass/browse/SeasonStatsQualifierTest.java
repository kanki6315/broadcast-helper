package com.pitpass.browse;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
@Transactional
class SeasonStatsQualifierTest {

    @Autowired JdbcClient db;
    @Autowired SeasonStatsController stats;

    /** The series All-time tab reads only MAIN seasons; a qualifying stage's
     *  own season page still answers for itself. */
    @Test
    void seriesAllTimeExcludesQualifierSeasons() {
        String suffix = UUID.randomUUID().toString();
        long driverId = db.sql("INSERT INTO driver (first_name, surname) VALUES ('Alltime', :sn) RETURNING id")
                .param("sn", "Scope " + suffix).query(Long.class).single();
        long seriesId = db.sql("INSERT INTO series (name) VALUES (:n) RETURNING id")
                .param("n", "All-time scope series " + suffix).query(Long.class).single();
        long main = db.sql("INSERT INTO season (series_id, year) VALUES (:s, 2099) RETURNING id")
                .param("s", seriesId).query(Long.class).single();
        long qualifier = db.sql("""
                        INSERT INTO season (series_id, year, kind, label)
                        VALUES (:s, 2099, 'QUALIFIER', 'Regional — Test') RETURNING id
                        """)
                .param("s", seriesId).query(Long.class).single();

        raceStart(main, driverId);
        raceStart(qualifier, driverId);

        SeasonStatsController.StatsTable allTime = stats.seriesStats(seriesId);
        assertEquals(1, allTime.rows().size());
        assertEquals(1, allTime.rows().get(0).byFormat().stream().mapToInt(l -> l.starts()).sum());

        SeasonStatsController.StatsTable qualifierSeason = stats.seasonStats(qualifier);
        assertEquals(1, qualifierSeason.rows().size());
    }

    private void raceStart(long seasonId, long driverId) {
        long eventId = db.sql("INSERT INTO event (season_id, name) VALUES (:s, 'Round') RETURNING id")
                .param("s", seasonId).query(Long.class).single();
        long entryId = db.sql("""
                        INSERT INTO entry (event_id, car_number, class_name, team_name)
                        VALUES (:event, '7', 'P', 'Scope team') RETURNING id
                        """)
                .param("event", eventId).query(Long.class).single();
        db.sql("INSERT INTO driver_assignment (entry_id, driver_id, seat_order) VALUES (:e, :d, 1)")
                .param("e", entryId).param("d", driverId).update();
        long sessionId = db.sql("""
                        INSERT INTO race_session (event_id, session_type, name)
                        VALUES (:event, 'RACE', 'Race') RETURNING id
                        """)
                .param("event", eventId).query(Long.class).single();
        db.sql("""
                        INSERT INTO result (session_id, entry_id, position_overall, position_in_class)
                        VALUES (:session, :entry, 1, 1)
                        """)
                .param("session", sessionId).param("entry", entryId).update();
    }
}
