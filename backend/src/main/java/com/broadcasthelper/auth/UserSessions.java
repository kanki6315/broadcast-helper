package com.broadcasthelper.auth;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Read/revoke access over the Spring Session JDBC tables for the Manage →
 * Sessions page. Sessions are keyed to the admin UI by {@code primary_id} (the
 * internal PK), never {@code session_id} — the latter is base64-encoded into the
 * cookie and is effectively the auth token, so it must not leave the server.
 * Deleting a {@code spring_session} row cascades to its attributes via the
 * schema's FK.
 */
@Component
public class UserSessions {

    private final JdbcClient db;

    public UserSessions(JdbcClient db) {
        this.db = db;
    }

    /** {@code id} is the primary_id; {@code current} flags the caller's own session. */
    public record UserSession(String id, String email, OffsetDateTime createdAt,
                              OffsetDateTime lastActiveAt, boolean current) {
    }

    /**
     * Live, signed-in sessions newest-active first. Excludes pre-login sessions
     * (an OAuth authorization request with no principal yet) and expired rows the
     * cleanup job hasn't reaped. {@code currentSessionId} may be null.
     */
    public List<UserSession> list(String currentSessionId) {
        return db.sql("""
                        SELECT trim(primary_id)                        AS primary_id,
                               principal_name,
                               to_timestamp(creation_time / 1000.0)    AS created_at,
                               to_timestamp(last_access_time / 1000.0) AS last_active_at,
                               (session_id = :current)                 AS current
                          FROM spring_session
                         WHERE principal_name IS NOT NULL
                           AND expiry_time > :now
                         ORDER BY principal_name, last_access_time DESC
                        """)
                .param("current", currentSessionId)
                .param("now", System.currentTimeMillis())
                .query((rs, i) -> new UserSession(
                        rs.getString("primary_id"),
                        rs.getString("principal_name"),
                        rs.getObject("created_at", OffsetDateTime.class),
                        rs.getObject("last_active_at", OffsetDateTime.class),
                        rs.getBoolean("current")))
                .list();
    }

    /** Revoke one session by primary_id; false if it was already gone. */
    public boolean revoke(String primaryId) {
        return db.sql("DELETE FROM spring_session WHERE primary_id = :id")
                .param("id", primaryId)
                .update() == 1;
    }

    /** Sign a user out of every device; returns how many sessions were killed. */
    public int revokeAllForEmail(String email) {
        String normalized = email == null ? "" : email.trim().toLowerCase();
        return db.sql("DELETE FROM spring_session WHERE lower(principal_name) = :email")
                .param("email", normalized)
                .update();
    }
}
