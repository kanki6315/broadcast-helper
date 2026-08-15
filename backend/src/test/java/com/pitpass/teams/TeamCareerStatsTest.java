package com.pitpass.teams;

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
class TeamCareerStatsTest {

    @Autowired JdbcClient db;
    @Autowired TeamResolver resolver;
    @Autowired TeamController controller;

    @Test
    void careerTotalsRollUpAcrossSeasonsAndSeriesLinesMatch() {
        String suffix = UUID.randomUUID().toString();
        long teamId = resolver.resolveOrCreate("Career team " + suffix);

        long seriesId = db.sql("INSERT INTO series (name) VALUES (:n) RETURNING id")
                .param("n", "Career series " + suffix).query(Long.class).single();
        long season1 = season(seriesId, 2098);
        long season2 = season(seriesId, 2099);

        // 2098: one car, a win and a quali pole.
        raceWeekend(season1, teamId, 1, 1);
        // 2099: one car, P2 in race, P3 in quali.
        raceWeekend(season2, teamId, 2, 3);

        TeamController.TeamStats stats = controller.stats(teamId);

        assertEquals(teamId, stats.teamId());
        assertEquals(2, stats.career().starts());
        assertEquals(1, stats.career().wins());
        assertEquals(2, stats.career().podiums());
        assertEquals(2, stats.career().top5s());
        assertEquals(1, stats.career().poles());
        assertEquals(2, stats.career().qualiTop5s());
        assertEquals(0, stats.career().dnfs());

        assertEquals(2, stats.seasons().size());
        assertEquals(1, stats.bySeries().size());
        var seriesLine = stats.bySeries().get(0);
        assertEquals(2, seriesLine.byFormat().stream().mapToInt(l -> l.starts()).sum());
        assertEquals(1, seriesLine.byFormat().stream().mapToInt(l -> l.wins()).sum());
        assertEquals(1, seriesLine.quali().poles());
    }

    /** A regional qualifying stage keeps its own season line but never leaks
     *  into the career headline or the series all-time rollup. */
    @Test
    void qualifierSeasonsStayOutOfCareerAndSeriesRollups() {
        String suffix = UUID.randomUUID().toString();
        long teamId = resolver.resolveOrCreate("Qualifier team " + suffix);

        long seriesId = db.sql("INSERT INTO series (name) VALUES (:n) RETURNING id")
                .param("n", "Qualifier series " + suffix).query(Long.class).single();
        long main = season(seriesId, 2099);
        long qualifier = db.sql("""
                        INSERT INTO season (series_id, year, kind, label)
                        VALUES (:s, 2099, 'QUALIFIER', 'Regional — Test') RETURNING id
                        """)
                .param("s", seriesId).query(Long.class).single();

        // A win-and-pole weekend in each; only the main season's may count.
        raceWeekend(main, teamId, 1, 1);
        raceWeekend(qualifier, teamId, 1, 1);

        TeamController.TeamStats stats = controller.stats(teamId);

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
        var mainLine = stats.seasons().stream()
                .filter(s -> s.seasonId() == main).findFirst().orElseThrow();
        assertFalse(mainLine.qualifier());
    }

    private long season(long seriesId, int year) {
        return db.sql("INSERT INTO season (series_id, year) VALUES (:s, :y) RETURNING id")
                .param("s", seriesId).param("y", year).query(Long.class).single();
    }

    private void raceWeekend(long seasonId, long teamId, int racePos, int qualiPos) {
        long eventId = db.sql("INSERT INTO event (season_id, name) VALUES (:s, 'Round') RETURNING id")
                .param("s", seasonId).query(Long.class).single();
        long entryId = db.sql("""
                        INSERT INTO entry (event_id, car_number, class_name, team_name, team_id)
                        VALUES (:event, '7', 'P', 'Career team', :teamId) RETURNING id
                        """)
                .param("event", eventId).param("teamId", teamId).query(Long.class).single();
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
