package com.pitpass.auth;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.Optional;

/**
 * The shareable timing link's secret (table {@code live_share_token}, V62):
 * one at a time, no expiry, revoked or replaced by an admin. Like device
 * tokens, 32 random bytes handed out once with only the SHA-256 stored.
 *
 * <p>Every timing poll from a shared page checks its header here, so the
 * active hash is cached and re-read every {@link #RELOAD}; issuing or revoking
 * on this process drops the cache at once. (During a redeploy's overlap the
 * other process may honour a revoked link for up to that long.)
 */
@Component
public class ShareTokens {

    static final Duration RELOAD = Duration.ofSeconds(30);
    static final Duration TOUCH_INTERVAL = Duration.ofMinutes(5);

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();

    /** The working link, for Manage. Never carries the secret or its hash. */
    public record Link(long id, String createdBy, OffsetDateTime createdAt, OffsetDateTime lastUsedAt) {
    }

    /** A freshly issued link: the secret is shown this once. */
    public record Issued(String token, Link link) {
    }

    private record Active(Long id, String hash, Instant readAt) {
    }

    private final JdbcClient db;
    private final TransactionTemplate tx;
    private final Clock clock;
    private volatile Active active;
    private volatile Instant lastTouch = Instant.MIN;

    @Autowired
    public ShareTokens(JdbcClient db, TransactionTemplate tx) {
        this(db, tx, Clock.systemUTC());
    }

    ShareTokens(JdbcClient db, TransactionTemplate tx, Clock clock) {
        this.db = db;
        this.tx = tx;
        this.clock = clock;
    }

    public Optional<Link> current() {
        return db.sql("""
                SELECT id, created_by, created_at, last_used_at FROM live_share_token WHERE revoked_at IS NULL
                """)
                .query((rs, i) -> new Link(rs.getLong("id"), rs.getString("created_by"),
                        rs.getObject("created_at", OffsetDateTime.class), rs.getObject("last_used_at", OffsetDateTime.class)))
                .optional();
    }

    /** Replaces the working link (if any) with a new one; the old one stops working. */
    public synchronized Issued issue(String createdBy) {
        String token = secret();
        Link link = tx.execute(status -> {
            db.sql("UPDATE live_share_token SET revoked_at = clock_timestamp() WHERE revoked_at IS NULL").update();
            return db.sql("""
                    INSERT INTO live_share_token (token_hash, created_by) VALUES (:hash, :by)
                    RETURNING id, created_by, created_at, last_used_at
                    """)
                    .param("hash", DeviceTokens.hash(token)).param("by", createdBy)
                    .query((rs, i) -> new Link(rs.getLong("id"), rs.getString("created_by"),
                            rs.getObject("created_at", OffsetDateTime.class), null))
                    .single();
        });
        active = null;
        return new Issued(token, link);
    }

    /** Stops the working link. False when there was none. */
    public synchronized boolean revoke() {
        int revoked = db.sql("UPDATE live_share_token SET revoked_at = clock_timestamp() WHERE revoked_at IS NULL").update();
        active = null;
        return revoked > 0;
    }

    /** Tests that change the table behind this process's back. */
    void revokeCacheForTests() {
        active = null;
    }

    /** Whether a secret is the working link's. Constant-time against the stored hash. */
    public boolean matches(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        Active a = active();
        if (a.hash() == null || !MessageDigest.isEqual(
                a.hash().getBytes(StandardCharsets.US_ASCII),
                DeviceTokens.hash(token.trim()).getBytes(StandardCharsets.US_ASCII))) {
            return false;
        }
        touch(a.id());
        return true;
    }

    private Active active() {
        Active a = active;
        Instant now = clock.instant();
        if (a == null || a.readAt().plus(RELOAD).isBefore(now)) {
            record Row(long id, String hash) {
            }
            Optional<Row> row = db.sql("SELECT id, token_hash FROM live_share_token WHERE revoked_at IS NULL")
                    .query((rs, i) -> new Row(rs.getLong("id"), rs.getString("token_hash"))).optional();
            a = new Active(row.map(Row::id).orElse(null), row.map(Row::hash).orElse(null), now);
            active = a;
        }
        return a;
    }

    // last_used_at is "roughly when" for Manage, so at most one write per interval.
    private void touch(Long id) {
        Instant now = clock.instant();
        if (id == null || lastTouch.plus(TOUCH_INTERVAL).isAfter(now)) {
            return;
        }
        lastTouch = now;
        db.sql("UPDATE live_share_token SET last_used_at = clock_timestamp() WHERE id = :id").param("id", id).update();
    }

    private static String secret() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return B64.encodeToString(bytes);
    }
}
