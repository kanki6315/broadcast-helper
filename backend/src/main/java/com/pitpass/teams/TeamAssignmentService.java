package com.pitpass.teams;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Resolves a driver's season assignment at an event round and materialises it
 * onto entry. The snapshot keeps the rest of the application on its established
 * entry.team_name read model; season_team_id preserves identity so privateers
 * can all display as "Privateer" without becoming one fictional team.
 */
@Service
public class TeamAssignmentService {

    private final JdbcClient db;

    public TeamAssignmentService(JdbcClient db) {
        this.db = db;
    }

    /** Re-resolve every entry after an admin save or an import renumbers rounds. */
    public int applySeason(long seasonId) {
        ensureNewDriversDefaultToPrivateer(seasonId);
        return db.sql("""
                        WITH driver_resolutions AS (
                            SELECT en.id AS entry_id, da.driver_id, picked.team_id, picked.team_name
                            FROM entry en
                                     JOIN event ev ON ev.id = en.event_id
                                     JOIN driver_assignment da ON da.entry_id = en.id AND da.driver_id IS NOT NULL
                                     LEFT JOIN LATERAL (
                                         SELECT a.team_id, st.name AS team_name
                                         FROM season_driver_team_assignment a
                                                  JOIN season_team st ON st.id = a.team_id
                                         WHERE a.season_id = ev.season_id
                                           AND a.driver_id = da.driver_id
                                           AND a.effective_from_round <= COALESCE(ev.round_ordinal, 1)
                                         ORDER BY a.effective_from_round DESC
                                         LIMIT 1
                                     ) picked ON true
                            WHERE ev.season_id = :seasonId
                        ), resolved_entries AS (
                            SELECT entry_id, min(team_id) AS team_id, min(team_name) AS team_name
                            FROM driver_resolutions
                            GROUP BY entry_id
                            HAVING count(*) = count(team_id) AND count(DISTINCT team_id) = 1
                        )
                        UPDATE entry en
                        SET season_team_id = r.team_id, team_name = r.team_name
                        FROM resolved_entries r
                        WHERE en.id = r.entry_id
                        """)
                .param("seasonId", seasonId)
                .update();
    }

    /**
     * Once a season has been configured, a driver first seen by a later import
     * must not fall back to the provider's driver-name-as-team placeholder.
     * Give that driver a distinct privateer identity from round one.
     */
    private void ensureNewDriversDefaultToPrivateer(long seasonId) {
        Integer configured = db.sql("""
                        SELECT count(*) FROM season_driver_team_assignment WHERE season_id = :seasonId
                        """)
                .param("seasonId", seasonId)
                .query(Integer.class)
                .single();
        if (configured == 0) {
            return;
        }

        var missing = db.sql("""
                        SELECT DISTINCT da.driver_id
                        FROM driver_assignment da
                                 JOIN entry en ON en.id = da.entry_id
                                 JOIN event ev ON ev.id = en.event_id
                        WHERE ev.season_id = :seasonId AND da.driver_id IS NOT NULL
                          AND NOT EXISTS (
                              SELECT 1 FROM season_driver_team_assignment a
                              WHERE a.season_id = :seasonId AND a.driver_id = da.driver_id
                          )
                        """)
                .param("seasonId", seasonId)
                .query(Long.class)
                .list();

        for (long driverId : missing) {
            long teamId = db.sql("""
                            INSERT INTO season_team (season_id, name, privateer_driver_id)
                            VALUES (:seasonId, 'Privateer', :driverId)
                            ON CONFLICT (season_id, privateer_driver_id)
                                WHERE privateer_driver_id IS NOT NULL
                            DO UPDATE SET name = EXCLUDED.name
                            RETURNING id
                            """)
                    .param("seasonId", seasonId)
                    .param("driverId", driverId)
                    .query(Long.class)
                    .single();
            db.sql("""
                            INSERT INTO season_driver_team_assignment
                                (season_id, driver_id, team_id, effective_from_round)
                            VALUES (:seasonId, :driverId, :teamId, 1)
                            ON CONFLICT (season_id, driver_id, effective_from_round) DO NOTHING
                            """)
                    .param("seasonId", seasonId)
                    .param("driverId", driverId)
                    .param("teamId", teamId)
                    .update();
        }
    }
}
