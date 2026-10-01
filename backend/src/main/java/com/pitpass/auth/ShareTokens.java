package com.pitpass.auth;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The shareable timing links' secrets (table {@code live_share_token}, V62,
 * one per person since V63): no expiry, each named for whoever holds it and
 * revoked on its own by an admin. Like device tokens, 32 random bytes handed
 * out once with only the SHA-256 stored.
 *
 * <p>Every timing poll from a shared page checks its header here, so the
 * working hashes are cached and re-read every {@link #RELOAD}; issuing or
 * revoking on this process drops the cache at once. (During a redeploy's
 * overlap the other process may honour a revoked link for up to that long.)
 */
@Component
public class ShareTokens {

    static final Duration RELOAD = Duration.ofSeconds(30);
    static final Duration TOUCH_INTERVAL = Duration.ofMinutes(5);

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();

    /** A working link, for Manage. Never carries the secret or its hash. label is null for a V62 link. */
    public record Link(long id, String label, String createdBy, OffsetDateTime createdAt, OffsetDateTime lastUsedAt) {
    }

    /** A freshly issued link: the secret is shown this once. */
    public record Issued(String token, Link link) {
    }

    /** Hash → link id of every working link, and when it was read. */
    private record Active(Map<String, Long> byHash, Instant readAt) {
    }

    private final JdbcClient db;
    private final Clock clock;
    private volatile Active active;
    private final Map<Long, Instant> lastTouch = new ConcurrentHashMap<>();

    @Autowired
    public ShareTokens(JdbcClient db) {
        this(db, Clock.systemUTC());
    }

    ShareTokens(JdbcClient db, Clock clock) {
        this.db = db;
        this.clock = clock;
    }

    /** The working links, oldest first. */
    public List<Link> current() {
        return db.sql("""
                SELECT id, label, created_by, created_at, last_used_at FROM live_share_token
                WHERE revoked_at IS NULL ORDER BY created_at, id
                """)
                .query((rs, i) -> new Link(rs.getLong("id"), rs.getString("label"), rs.getString("created_by"),
                        rs.getObject("created_at", OffsetDateTime.class), rs.getObject("last_used_at", OffsetDateTime.class)))
                .list();
    }

    /** A new link named for whoever will hold it. The other links keep working. */
    public synchronized Issued issue(String label, String createdBy) {
        String token = secret();
        Link link = db.sql("""
                INSERT INTO live_share_token (token_hash, label, created_by) VALUES (:hash, :label, :by)
                RETURNING id, label, created_by, created_at, last_used_at
                """)
                .param("hash", DeviceTokens.hash(token)).param("label", label).param("by", createdBy)
                .query((rs, i) -> new Link(rs.getLong("id"), rs.getString("label"), rs.getString("created_by"),
                        rs.getObject("created_at", OffsetDateTime.class), null))
                .single();
        active = null;
        return new Issued(token, link);
    }

    /** Stops one link. False when it was not working. */
    public synchronized boolean revoke(long id) {
        int revoked = db.sql("UPDATE live_share_token SET revoked_at = clock_timestamp() WHERE id = :id AND revoked_at IS NULL")
                .param("id", id).update();
        active = null;
        lastTouch.remove(id);
        return revoked > 0;
    }

    /** Tests that change the table behind this process's back. */
    void revokeCacheForTests() {
        active = null;
    }

    /** The working link a secret opens, if any. Constant-time against each stored hash. */
    public OptionalLong match(String token) {
        if (token == null || token.isBlank()) {
            return OptionalLong.empty();
        }
        byte[] given = DeviceTokens.hash(token.trim()).getBytes(StandardCharsets.US_ASCII);
        Long found = null;
        // Compare against every hash, not just until a hit, so timing says nothing about which one matched.
        for (Map.Entry<String, Long> e : active().byHash().entrySet()) {
            if (MessageDigest.isEqual(e.getKey().getBytes(StandardCharsets.US_ASCII), given)) {
                found = e.getValue();
            }
        }
        if (found == null) {
            return OptionalLong.empty();
        }
        touch(found);
        return OptionalLong.of(found);
    }

    private Active active() {
        Active a = active;
        Instant now = clock.instant();
        if (a == null || a.readAt().plus(RELOAD).isBefore(now)) {
            Map<String, Long> byHash = new ConcurrentHashMap<>();
            db.sql("SELECT id, token_hash FROM live_share_token WHERE revoked_at IS NULL")
                    .query((rs, i) -> byHash.put(rs.getString("token_hash"), rs.getLong("id"))).list();
            a = new Active(Map.copyOf(byHash), now);
            active = a;
        }
        return a;
    }

    // last_used_at is "roughly when" for Manage, so at most one write per link per interval.
    private void touch(long id) {
        Instant now = clock.instant();
        Instant last = lastTouch.get(id);
        if (last != null && last.plus(TOUCH_INTERVAL).isAfter(now)) {
            return;
        }
        lastTouch.put(id, now);
        db.sql("UPDATE live_share_token SET last_used_at = clock_timestamp() WHERE id = :id").param("id", id).update();
    }

    private static String secret() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return B64.encodeToString(bytes);
    }
}
