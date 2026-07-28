package com.pitpass.teams;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Maps a raw team-name spelling onto its global team via team_alias. Unknown
 * spellings auto-create a team with itself as the first alias — the catalogue
 * maintains itself on import and admins merge variants afterwards. The raw
 * spelling is never rewritten anywhere; this only supplies entry.team_id.
 */
@Service
public class TeamResolver {

    private final JdbcClient db;

    public TeamResolver(JdbcClient db) {
        this.db = db;
    }

    /** Alias lookup (lower/trim); unknown names create team + self-alias. Blank &rarr; null. */
    public Long resolveOrCreate(String rawName) {
        if (rawName == null || rawName.isBlank()) {
            return null;
        }
        String trimmed = rawName.trim();
        Long existing = lookup(trimmed);
        if (existing != null) {
            return existing;
        }
        long teamId = db.sql("INSERT INTO team (name) VALUES (:name) RETURNING id")
                .param("name", trimmed)
                .query(Long.class)
                .single();
        int inserted = db.sql("""
                        INSERT INTO team_alias (team_id, alias)
                        VALUES (:teamId, :alias)
                        ON CONFLICT DO NOTHING
                        """)
                .param("teamId", teamId)
                .param("alias", trimmed)
                .update();
        if (inserted == 0) {
            // Lost a race to a concurrent import: the alias now belongs to
            // another team row. Use that one and drop the orphan just created.
            db.sql("DELETE FROM team WHERE id = :id").param("id", teamId).update();
            return lookup(trimmed);
        }
        return teamId;
    }

    private Long lookup(String trimmed) {
        return db.sql("SELECT team_id FROM team_alias WHERE lower(trim(alias)) = lower(:key)")
                .param("key", trimmed)
                .query(Long.class)
                .optional()
                .orElse(null);
    }
}
