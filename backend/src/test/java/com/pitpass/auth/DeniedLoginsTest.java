package com.pitpass.auth;

import com.pitpass.auth.DeniedLogins.DeniedLogin;
import org.junit.jupiter.api.BeforeEach;
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
 * Dedupe, cap, and cleanup semantics of the denied-login recorder. Safe to run
 * {@code @Transactional} (nothing here is snapshot-cached); each test clears
 * the table first so the cap and counts are deterministic. The
 * last-attempt-advances assertion works because record() uses
 * clock_timestamp(), not the transaction-scoped now().
 */
@SpringBootTest
@Transactional
class DeniedLoginsTest {

    @Autowired
    private DeniedLogins deniedLogins;

    @Autowired
    private JdbcClient db;

    @BeforeEach
    void clearTable() {
        db.sql("DELETE FROM denied_login").update();
    }

    @Test
    void repeatAttemptsDedupeAndCount() {
        deniedLogins.record("  Stranger@Example.TEST ");
        deniedLogins.record("stranger@example.test");

        List<DeniedLogin> rows = deniedLogins.list();
        assertEquals(1, rows.size());
        DeniedLogin row = rows.get(0);
        assertEquals("stranger@example.test", row.email());
        assertEquals(2, row.attemptCount());
        assertTrue(row.lastAttemptAt().isAfter(row.firstAttemptAt()));
    }

    @Test
    void capBlocksNewEmailsButKeepsCounting() {
        db.sql("INSERT INTO denied_login (email) SELECT 'seed' || g || '@example.test' FROM generate_series(1, 200) g")
                .update();

        deniedLogins.record("late@example.test");
        assertEquals(200, deniedLogins.list().size());

        deniedLogins.record("seed7@example.test");
        DeniedLogin seed7 = deniedLogins.list().stream()
                .filter(r -> r.email().equals("seed7@example.test"))
                .findFirst().orElseThrow();
        assertEquals(2, seed7.attemptCount());
    }

    @Test
    void junkInputIsSkipped() {
        deniedLogins.record(null);
        deniedLogins.record("   ");
        deniedLogins.record("x".repeat(400) + "@example.test");
        assertTrue(deniedLogins.list().isEmpty());
    }

    @Test
    void clearForIsCaseInsensitive() {
        deniedLogins.record("gone@example.test");
        deniedLogins.clearFor("  GONE@Example.TEST ");
        assertTrue(deniedLogins.list().isEmpty());
    }

    @Test
    void dismissReportsWhetherARowExisted() {
        deniedLogins.record("dismiss@example.test");
        long id = deniedLogins.list().get(0).id();
        assertTrue(deniedLogins.dismiss(id));
        assertFalse(deniedLogins.dismiss(id));
    }
}
