package com.pitpass.auth;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Native-app sign-in state: the short-lived one-time codes minted at the end
 * of a Google login started from the app, and the long-lived bearer tokens
 * they are exchanged for (table {@code device_token}, V45).
 *
 * <p>Codes live in memory. They are single-use, expire in {@link #CODE_TTL},
 * and only bridge the few seconds between the browser redirecting to
 * {@code pitpass://auth?code=…} and the app calling exchange — a restart in
 * that window just means signing in again. One container runs in production,
 * so there is no second instance to share them with.
 *
 * <p>Tokens: 32 random bytes, base64url, handed out exactly once. Only the
 * SHA-256 hex is stored, so a database read cannot impersonate a device.
 * Non-final with a package-private clock constructor so tests can subclass
 * and time-travel (house pattern: no Mockito).
 */
@Component
public class DeviceTokens {

    static final Duration CODE_TTL = Duration.ofMinutes(2);
    /** last_used_at is a "roughly when" for the admin page, not an audit log —
     *  one UPDATE per request would be the most expensive thing on the hot
     *  path, so it only moves after this much silence. */
    static final Duration TOUCH_INTERVAL = Duration.ofMinutes(5);

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();

    private final JdbcClient db;
    private final Clock clock;
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();

    private record Pending(String email, String deviceName, Instant expiresAt) {
    }

    /** What exchange hands the app: the secret (shown once) and who it is for. */
    public record Issued(String token, String email, String deviceName) {
    }

    /** An active device for Manage → Sessions. Never exposes the hash. */
    public record Device(long id, String email, String deviceName, OffsetDateTime createdAt,
                         OffsetDateTime lastUsedAt) {
    }

    @Autowired
    public DeviceTokens(JdbcClient db) {
        this(db, Clock.systemUTC());
    }

    DeviceTokens(JdbcClient db, Clock clock) {
        this.db = db;
        this.clock = clock;
    }

    /** Mint a one-time code for a just-completed login. */
    public String issueCode(String email, String deviceName) {
        sweep();
        String code = secret();
        pending.put(code, new Pending(email, deviceName, clock.instant().plus(CODE_TTL)));
        return code;
    }

    /**
     * Redeem a code for a fresh token. Null when the code is unknown, already
     * used, or expired — the caller turns that into a 400. The code is
     * consumed before the token is minted, so a racing second exchange loses.
     */
    public Issued exchange(String code) {
        if (code == null) {
            return null;
        }
        Pending p = pending.remove(code);
        if (p == null || p.expiresAt().isBefore(clock.instant())) {
            return null;
        }
        String token = secret();
        db.sql("""
                        INSERT INTO device_token (token_hash, owner_email, device_name)
                        VALUES (:hash, :email, :name)
                        """)
                .param("hash", hash(token))
                .param("email", p.email())
                .param("name", p.deviceName())
                .update();
        return new Issued(token, p.email(), p.deviceName());
    }

    /** The principal a bearer secret stands for, or null if unknown/revoked. */
    public DeviceUser resolve(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        DeviceUser user = db.sql("""
                        SELECT id, owner_email, device_name
                          FROM device_token
                         WHERE token_hash = :hash AND revoked_at IS NULL
                        """)
                .param("hash", hash(token))
                .query((rs, i) -> new DeviceUser(rs.getLong("id"), rs.getString("owner_email"),
                        rs.getString("device_name")))
                .optional()
                .orElse(null);
        if (user != null) {
            touch(user.tokenId());
        }
        return user;
    }

    private void touch(long id) {
        db.sql("""
                        UPDATE device_token
                           SET last_used_at = now()
                         WHERE id = :id
                           AND (last_used_at IS NULL OR last_used_at < now() - CAST(:interval AS interval))
                        """)
                .param("id", id)
                .param("interval", TOUCH_INTERVAL.toSeconds() + " seconds")
                .update();
    }

    /** Active devices, grouped by owner, most recently used first. */
    public List<Device> list() {
        return db.sql("""
                        SELECT id, owner_email, device_name, created_at, last_used_at
                          FROM device_token
                         WHERE revoked_at IS NULL
                         ORDER BY lower(owner_email), last_used_at DESC NULLS LAST, created_at DESC
                        """)
                .query((rs, i) -> new Device(rs.getLong("id"), rs.getString("owner_email"),
                        rs.getString("device_name"),
                        rs.getObject("created_at", OffsetDateTime.class),
                        rs.getObject("last_used_at", OffsetDateTime.class)))
                .list();
    }

    /** Revoke one device; false if it was unknown or already revoked. */
    public boolean revoke(long id) {
        return db.sql("UPDATE device_token SET revoked_at = now() WHERE id = :id AND revoked_at IS NULL")
                .param("id", id)
                .update() == 1;
    }

    /** Cut every device of one email off; returns how many were active. */
    public int revokeAllForEmail(String email) {
        String normalized = email == null ? "" : email.trim().toLowerCase();
        return db.sql("""
                        UPDATE device_token SET revoked_at = now()
                         WHERE lower(owner_email) = :email AND revoked_at IS NULL
                        """)
                .param("email", normalized)
                .update();
    }

    private void sweep() {
        Instant now = clock.instant();
        pending.values().removeIf(p -> p.expiresAt().isBefore(now));
    }

    private static String secret() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return B64.encodeToString(bytes);
    }

    static String hash(String secret) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(sha.digest(secret.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 missing from the JRE", e);
        }
    }
}
