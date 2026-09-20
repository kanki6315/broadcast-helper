package com.pitpass.live;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * The single live_timing row (V54): what an admin asked for, and which
 * process currently holds the lease to act on it. Its own class so
 * {@link LiveTimingService} can be driven in tests by a subclass with no
 * database behind it.
 */
@Component
public class LiveTimingStore {

    public record Row(boolean desiredConnected, Long eventId, String requestedBy, Instant requestedAt,
                      String holder, Instant leaseExpiresAt) {
    }

    private final JdbcClient db;

    public LiveTimingStore(JdbcClient db) {
        this.db = db;
    }

    public Row read() {
        return db.sql("""
                SELECT desired_connected, event_id, requested_by, requested_at, holder, lease_expires_at
                FROM live_timing WHERE id = 1
                """)
                .query((rs, i) -> new Row(
                        rs.getBoolean("desired_connected"),
                        rs.getObject("event_id", Long.class),
                        rs.getString("requested_by"),
                        instant(rs.getTimestamp("requested_at")),
                        rs.getString("holder"),
                        instant(rs.getTimestamp("lease_expires_at"))))
                .single();
    }

    /** Connecting binds the event; disconnecting leaves the last binding visible. */
    public void request(boolean connected, Long eventId, String requestedBy) {
        db.sql("""
                UPDATE live_timing
                SET desired_connected = :connected,
                    event_id = COALESCE(CAST(:eventId AS bigint), event_id),
                    requested_by = :by,
                    requested_at = clock_timestamp()
                WHERE id = 1
                """)
                .param("connected", connected)
                .param("eventId", eventId, java.sql.Types.BIGINT)
                .param("by", requestedBy)
                .update();
    }

    /**
     * Takes the lease if it is free, lapsed or already ours, and extends it.
     * One atomic UPDATE, so two processes racing here cannot both win.
     * clock_timestamp(), not now(): the lease must advance in real time even
     * inside a caller's transaction.
     */
    public boolean acquireOrRenew(String instance, Duration lease) {
        return db.sql("""
                UPDATE live_timing
                SET holder = :me,
                    lease_expires_at = clock_timestamp() + make_interval(secs => :seconds)
                WHERE id = 1
                  AND (holder IS NULL OR holder = :me OR lease_expires_at < clock_timestamp())
                """)
                .param("me", instance)
                .param("seconds", lease.toMillis() / 1000.0)
                .update() == 1;
    }

    /** Hands the lease back at once, so a redeploy's new process need not wait it out. */
    public void release(String instance) {
        db.sql("UPDATE live_timing SET holder = NULL, lease_expires_at = NULL WHERE id = 1 AND holder = :me")
                .param("me", instance)
                .update();
    }

    public Optional<String> eventName(long eventId) {
        return db.sql("SELECT name FROM event WHERE id = :id").param("id", eventId)
                .query(String.class).optional();
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}
