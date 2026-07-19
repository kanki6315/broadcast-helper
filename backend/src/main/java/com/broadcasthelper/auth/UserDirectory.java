package com.broadcasthelper.auth;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The app_user roster, cached as an immutable in-memory snapshot so the
 * per-request checks in {@link LiveAuthorization} cost a set lookup, not a
 * query. Reloaded at startup and after every Users-page mutation — deliberately
 * NOT on a schedule, so a direct psql edit to app_user requires a restart.
 * Non-final so tests can stub it by subclassing (house pattern).
 */
@Component
public class UserDirectory {

    private static final Logger log = LoggerFactory.getLogger(UserDirectory.class);

    private final JdbcClient db;

    /** Empty only before the first successful load — empty means nobody signs in. */
    private volatile Snapshot current = new Snapshot(Set.of(), Set.of());

    record Snapshot(Set<String> members, Set<String> admins) {
    }

    public UserDirectory(JdbcClient db) {
        this.db = db;
    }

    @PostConstruct
    void loadAtStartup() {
        reload();
    }

    /**
     * One SELECT, one atomic snapshot swap. Never throws: a failed load keeps
     * the previous snapshot — installing an empty one on a transient DB error
     * would lock every user out of a healthy deployment.
     */
    public void reload() {
        record Row(String email, String role) {
        }
        try {
            List<Row> rows = db.sql("SELECT email, role FROM app_user")
                    .query((rs, i) -> new Row(rs.getString("email"), rs.getString("role")))
                    .list();
            Set<String> members = rows.stream()
                    .map(r -> r.email().trim().toLowerCase())
                    .collect(Collectors.toUnmodifiableSet());
            Set<String> admins = rows.stream()
                    .filter(r -> "ADMIN".equals(r.role()))
                    .map(r -> r.email().trim().toLowerCase())
                    .collect(Collectors.toUnmodifiableSet());
            current = new Snapshot(members, admins);
        } catch (DataAccessException e) {
            log.warn("app_user reload failed; keeping the previous snapshot", e);
        }
    }

    /** Any listed email — may sign in and read. */
    public boolean allows(String email) {
        String e = norm(email);
        return e != null && current.members().contains(e);
    }

    /** Role ADMIN — may write. */
    public boolean isAdmin(String email) {
        String e = norm(email);
        return e != null && current.admins().contains(e);
    }

    private static String norm(String email) {
        if (email == null) return null;
        String e = email.trim().toLowerCase();
        return e.isBlank() ? null : e;
    }
}
