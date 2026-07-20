package com.pitpass.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Rejected sign-in attempts, surfaced on Manage → Users so the admin can add
 * the attempted email in one click or dismiss it. Recording happens before any
 * authorization — any real Google account can trigger it — so writes are
 * deduped per email, the table is hard-capped at {@value #MAX_ROWS} distinct
 * emails, oversized input is dropped, and a failure to record never breaks the
 * login flow.
 */
@Component
public class DeniedLogins {

    private static final Logger log = LoggerFactory.getLogger(DeniedLogins.class);
    private static final int MAX_ROWS = 200;

    private final JdbcClient db;

    public DeniedLogins(JdbcClient db) {
        this.db = db;
    }

    public record DeniedLogin(long id, String email, int attemptCount,
                              OffsetDateTime firstAttemptAt, OffsetDateTime lastAttemptAt) {
    }

    public void record(String email) {
        String e = norm(email);
        if (e == null || e.length() > 320) {
            return;
        }
        try {
            // UPDATE first so the row cap below never stops counting emails
            // that are already recorded. clock_timestamp(), not now(): now()
            // is transaction-scoped and would freeze last_attempt_at.
            int updated = db.sql("""
                            UPDATE denied_login
                               SET attempt_count = attempt_count + 1,
                                   last_attempt_at = clock_timestamp()
                             WHERE lower(email) = :email
                            """)
                    .param("email", e)
                    .update();
            if (updated == 0) {
                // A concurrent first attempt for the same email just loses one
                // increment to ON CONFLICT DO NOTHING — acceptable.
                db.sql("""
                                INSERT INTO denied_login (email)
                                SELECT :email WHERE (SELECT count(*) FROM denied_login) < :cap
                                ON CONFLICT DO NOTHING
                                """)
                        .param("email", e)
                        .param("cap", MAX_ROWS)
                        .update();
            }
        } catch (DataAccessException ex) {
            log.warn("failed to record denied login for {}", e, ex);
        }
    }

    public List<DeniedLogin> list() {
        // id tiebreak: rows created in one transaction share a timestamp.
        return db.sql("""
                        SELECT id, email, attempt_count, first_attempt_at, last_attempt_at
                          FROM denied_login
                         ORDER BY last_attempt_at DESC, id DESC
                        """)
                .query((rs, i) -> new DeniedLogin(rs.getLong("id"), rs.getString("email"),
                        rs.getInt("attempt_count"),
                        rs.getObject("first_attempt_at", OffsetDateTime.class),
                        rs.getObject("last_attempt_at", OffsetDateTime.class)))
                .list();
    }

    public boolean dismiss(long id) {
        return db.sql("DELETE FROM denied_login WHERE id = :id").param("id", id).update() == 1;
    }

    /** Called when an email becomes a user — its denied record is obsolete. */
    public void clearFor(String email) {
        String e = norm(email);
        if (e == null) {
            return;
        }
        db.sql("DELETE FROM denied_login WHERE lower(email) = :email").param("email", e).update();
    }

    private static String norm(String email) {
        if (email == null) return null;
        String e = email.trim().toLowerCase();
        return e.isBlank() ? null : e;
    }
}
