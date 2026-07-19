package com.broadcasthelper.auth;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DelegatingDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Loads committed app_user rows through private UserDirectory instances (the
 * app's singleton bean is never touched), covering normalization, the initial
 * deny-everything snapshot, and the failed-reload-keeps-snapshot guard — a
 * transient DB error must not swap in an empty roster and lock everyone out.
 * Deliberately not @Transactional: reload() must see committed rows.
 */
@SpringBootTest
class UserDirectoryTest {

    @Autowired
    private JdbcClient db;

    @Autowired
    private DataSource dataSource;

    @AfterEach
    void cleanUp() {
        // trim+lower because one fixture is deliberately stored with
        // whitespace and mixed case to prove normalization.
        db.sql("DELETE FROM app_user WHERE lower(trim(email)) LIKE 'dir-%@example.test'").update();
    }

    /** Delegates until {@code broken}; then every connection request fails. */
    private static final class BreakableDataSource extends DelegatingDataSource {
        volatile boolean broken = false;

        BreakableDataSource(DataSource target) {
            super(target);
        }

        @Override
        public Connection getConnection() throws SQLException {
            if (broken) throw new SQLException("simulated outage");
            return super.getConnection();
        }
    }

    @Test
    void loadsAndNormalizesRoster() {
        db.sql("INSERT INTO app_user (email, role) VALUES ('  Dir-Admin@Example.TEST ', 'ADMIN')").update();
        db.sql("INSERT INTO app_user (email, role) VALUES ('dir-viewer@example.test', 'VIEWER')").update();

        UserDirectory directory = new UserDirectory(db);
        directory.reload();

        assertTrue(directory.allows("dir-viewer@example.test"));
        assertTrue(directory.allows("  DIR-ADMIN@example.test  "));
        assertTrue(directory.isAdmin("dir-admin@EXAMPLE.TEST"));
        assertFalse(directory.isAdmin("dir-viewer@example.test"));
        assertFalse(directory.allows("dir-nobody@example.test"));
        assertFalse(directory.allows(null));
        assertFalse(directory.allows("   "));
    }

    @Test
    void failedReloadKeepsPreviousSnapshot() {
        db.sql("INSERT INTO app_user (email, role) VALUES ('dir-keep@example.test', 'ADMIN')").update();

        BreakableDataSource breakable = new BreakableDataSource(dataSource);
        UserDirectory directory = new UserDirectory(JdbcClient.create(breakable));
        directory.reload();
        assertTrue(directory.isAdmin("dir-keep@example.test"));

        breakable.broken = true;
        assertDoesNotThrow(directory::reload);
        assertTrue(directory.isAdmin("dir-keep@example.test"));
    }

    @Test
    void freshInstanceDeniesEverything() {
        UserDirectory directory = new UserDirectory(db);
        assertFalse(directory.allows("dir-anyone@example.test"));
        assertFalse(directory.isAdmin("dir-anyone@example.test"));
    }
}
