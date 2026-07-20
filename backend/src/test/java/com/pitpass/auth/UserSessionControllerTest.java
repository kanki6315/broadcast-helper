package com.pitpass.auth;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The session endpoints called directly on the bean (house pattern), against
 * seeded spring_session rows. @Transactional rolls the seeds back.
 */
@SpringBootTest
@Transactional
class UserSessionControllerTest {

    @Autowired
    private UserSessionController controller;

    @Autowired
    private JdbcClient db;

    private void seed(String primaryId, String sessionId, String principal) {
        long now = System.currentTimeMillis();
        db.sql("""
                        INSERT INTO spring_session
                            (primary_id, session_id, creation_time, last_access_time,
                             max_inactive_interval, expiry_time, principal_name)
                        VALUES (:pid, :sid, :now, :now, 259200, :expiry, :principal)
                        """)
                .param("pid", primaryId).param("sid", sessionId)
                .param("now", now).param("expiry", now + 3_600_000)
                .param("principal", principal)
                .update();
    }

    @Test
    void listReturnsSeededSession() {
        seed("ctl-p1", "ctl-s1", "list@example.test");
        var rows = controller.list(new MockHttpServletRequest()).stream()
                .filter(s -> "ctl-p1".equals(s.id())).toList();
        assertEquals(1, rows.size());
        assertEquals("list@example.test", rows.get(0).email());
    }

    @Test
    void revokeUnknownIsNotFound() {
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> controller.revoke("does-not-exist"));
        assertEquals(HttpStatus.NOT_FOUND, e.getStatusCode());
    }

    @Test
    void revokeDeletesTheSession() {
        seed("ctl-p2", "ctl-s2", "kill@example.test");
        controller.revoke("ctl-p2");
        assertTrue(controller.list(new MockHttpServletRequest()).stream().noneMatch(s -> "ctl-p2".equals(s.id())));
    }

    @Test
    void revokeAllForEmailIgnoresZeroMatches() {
        controller.revokeAll("nobody@example.test"); // no throw, no 404
        seed("ctl-p3", "ctl-s3", "all@example.test");
        controller.revokeAll("all@example.test");
        assertTrue(controller.list(new MockHttpServletRequest()).stream().noneMatch(s -> "ctl-p3".equals(s.id())));
    }
}
