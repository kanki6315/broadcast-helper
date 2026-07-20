package com.pitpass.auth;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Session read/revoke over seeded spring_session rows — no real Google login
 * needed. @Transactional so the seeds roll back; JdbcClient is tx-aware, so the
 * component reads the seeds within the same transaction.
 */
@SpringBootTest
@Transactional
class UserSessionsTest {

    @Autowired
    private UserSessions userSessions;

    @Autowired
    private JdbcClient db;

    /** Seed a session row; returns its primary_id. */
    private String seedSession(String primaryId, String sessionId, String principal,
                               long lastAccess, long expiry) {
        db.sql("""
                        INSERT INTO spring_session
                            (primary_id, session_id, creation_time, last_access_time,
                             max_inactive_interval, expiry_time, principal_name)
                        VALUES (:pid, :sid, :created, :access, :maxint, :expiry, :principal)
                        """)
                .param("pid", primaryId)
                .param("sid", sessionId)
                .param("created", lastAccess - 1000)
                .param("access", lastAccess)
                .param("maxint", 259200)
                .param("expiry", expiry)
                .param("principal", principal)
                .update();
        return primaryId;
    }

    private static long future() {
        return System.currentTimeMillis() + 3_600_000;
    }

    @Test
    void listsLiveSessionsMarksCurrentAndExcludesNoise() {
        long now = System.currentTimeMillis();
        seedSession("p-live-1", "s-live-1", "driver@example.test", now, future());
        seedSession("p-live-2", "s-live-2", "driver@example.test", now - 5000, future());
        seedSession("p-expired", "s-expired", "gone@example.test", now, now - 1000);
        seedSession("p-prelogin", "s-prelogin", null, now, future());

        List<UserSessions.UserSession> live = userSessions.list("s-live-2").stream()
                .filter(s -> s.email() != null && s.email().endsWith("@example.test"))
                .toList();

        // Only the two live rows for a real principal; expired + null-principal excluded.
        assertEquals(List.of("p-live-1", "p-live-2"), live.stream().map(UserSessions.UserSession::id).toList());
        assertEquals("driver@example.test", live.get(0).email());
        // Newest-active first, and the caller's session (s-live-2) flagged current.
        assertFalse(live.get(0).current());
        assertTrue(live.get(1).current());
    }

    @Test
    void revokeDeletesOneAndCascadesAttributes() {
        seedSession("p-rev", "s-rev", "kick@example.test", System.currentTimeMillis(), future());
        db.sql("""
                        INSERT INTO spring_session_attributes (session_primary_id, attribute_name, attribute_bytes)
                        VALUES ('p-rev', 'X', decode('00', 'hex'))
                        """).update();

        assertTrue(userSessions.revoke("p-rev"));
        assertFalse(userSessions.revoke("p-rev"), "second revoke finds nothing");

        Integer attrs = db.sql("SELECT count(*) FROM spring_session_attributes WHERE session_primary_id = 'p-rev'")
                .query(Integer.class).single();
        assertEquals(0, attrs, "attributes should cascade-delete with the session");
    }

    @Test
    void revokeAllForEmailIsCaseInsensitiveAndCounts() {
        long f = future();
        seedSession("p-a", "s-a", "multi@example.test", System.currentTimeMillis(), f);
        seedSession("p-b", "s-b", "multi@example.test", System.currentTimeMillis(), f);
        seedSession("p-c", "s-c", "other@example.test", System.currentTimeMillis(), f);

        assertEquals(2, userSessions.revokeAllForEmail("  MULTI@Example.TEST "));
        assertEquals(0, userSessions.revokeAllForEmail("multi@example.test"));
        assertTrue(userSessions.list(null).stream().anyMatch(s -> "p-c".equals(s.id())),
                "other user's session untouched");
    }
}
