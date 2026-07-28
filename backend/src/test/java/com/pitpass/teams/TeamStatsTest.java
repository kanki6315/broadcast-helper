package com.pitpass.teams;

import com.pitpass.browse.SeasonStatsController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@Transactional
class TeamStatsTest {

    @Autowired JdbcClient db;
    @Autowired TeamResolver resolver;
    @Autowired SeasonStatsController stats;

    /** Two cars under two raw spellings of one team: one row, per-car-entry
     *  counting, a pole with no grid attribution, and no row for an entry
     *  without a global team (the privateer case). */
    @Test
    void teamRowsGroupByEntityCountPerCarAndSkipTeamlessEntries() {
        String suffix = UUID.randomUUID().toString();
        String original = "JDC-Miller MotorSports " + suffix;
        String variant = "JDC Miller MotorSports " + suffix;
        long teamId = resolver.resolveOrCreate(original);
        db.sql("INSERT INTO team_alias (team_id, alias) VALUES (:id, :alias)")
                .param("id", teamId).param("alias", variant).update();

        long seriesId = db.sql("INSERT INTO series (name) VALUES (:n) RETURNING id")
                .param("n", "Team stats series " + suffix).query(Long.class).single();
        long seasonId = db.sql("INSERT INTO season (series_id, year) VALUES (:s, 2099) RETURNING id")
                .param("s", seriesId).query(Long.class).single();
        long eventId = db.sql("INSERT INTO event (season_id, name) VALUES (:s, 'Round') RETURNING id")
                .param("s", seasonId).query(Long.class).single();

        long car85 = entry(eventId, "85", original, teamId);
        long car5 = entry(eventId, "5", variant, teamId);
        long teamless = entry(eventId, "99", "Some Privateer", null);

        long race = session(eventId, "RACE");
        result(race, car85, 1);
        result(race, car5, 2);
        result(race, teamless, 3);

        long quali = session(eventId, "QUALIFYING");
        result(quali, car85, 1);

        SeasonStatsController.TeamStatsTable table = stats.seasonTeamStats(seasonId);

        assertEquals(1, table.rows().size());
        SeasonStatsController.TeamStatsRow row = table.rows().get(0);
        assertEquals(teamId, row.teamId());
        assertEquals(original, row.teamName());
        assertEquals("5 85", row.carNumbers());

        assertEquals(1, row.byFormat().size());
        SeasonStatsController.FormatLine line = row.byFormat().get(0);
        assertEquals(2, line.starts());
        assertEquals(1, line.wins());
        assertEquals(2, line.podiums());
        assertEquals(2, line.top5s());
        assertEquals(0, line.dnfs());

        assertEquals(1, row.quali().sessions());
        assertEquals(1, row.quali().poles());
        assertEquals(1, row.quali().top5s());

        // The series-wide all-time scope sees the same single row.
        SeasonStatsController.TeamStatsTable allTime = stats.seriesTeamStats(seriesId);
        assertEquals(1, allTime.rows().size());
        assertTrue(allTime.rows().stream().allMatch(r -> r.teamId() == teamId));
    }

    private long entry(long eventId, String number, String teamName, Long teamId) {
        return db.sql("""
                        INSERT INTO entry (event_id, car_number, class_name, team_name, team_id)
                        VALUES (:event, :number, 'P', :team, :teamId) RETURNING id
                        """)
                .param("event", eventId).param("number", number)
                .param("team", teamName).param("teamId", teamId)
                .query(Long.class).single();
    }

    private long session(long eventId, String type) {
        return db.sql("""
                        INSERT INTO race_session (event_id, session_type, name)
                        VALUES (:event, :type, :type) RETURNING id
                        """)
                .param("event", eventId).param("type", type).query(Long.class).single();
    }

    private void result(long sessionId, long entryId, int positionInClass) {
        db.sql("""
                        INSERT INTO result (session_id, entry_id, position_overall, position_in_class)
                        VALUES (:session, :entry, :pos, :pos)
                        """)
                .param("session", sessionId).param("entry", entryId).param("pos", positionInClass)
                .update();
    }
}
