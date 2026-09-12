package com.pitpass.auth;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Code → token → principal, against the real device_token table (rolled back).
 * Time-sensitive cases drive a hand-rolled mutable clock (house pattern: no
 * Mockito).
 */
@SpringBootTest
@Transactional
class DeviceTokensTest {

    /** A clock the test can push forward. */
    static class MutableClock extends Clock {
        Instant now = Instant.parse("2026-09-12T10:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @Autowired
    private JdbcClient db;

    @Autowired
    private DeviceTokens tokens;

    @Test
    void codeExchangesOnceForATokenThatResolvesToItsOwner() {
        String code = tokens.issueCode("pad@example.test", "Arjuna's iPad");
        DeviceTokens.Issued issued = tokens.exchange(code);
        assertNotNull(issued);
        assertEquals("pad@example.test", issued.email());
        assertEquals("Arjuna's iPad", issued.deviceName());
        assertNotEquals(code, issued.token(), "the token is a fresh secret, not the code");

        DeviceUser user = tokens.resolve(issued.token());
        assertNotNull(user);
        assertEquals("pad@example.test", user.email());
        assertEquals("Arjuna's iPad", user.deviceName());

        assertNull(tokens.exchange(code), "a code is single-use");
    }

    @Test
    void onlyTheHashIsStored() {
        DeviceTokens.Issued issued = tokens.exchange(tokens.issueCode("hash@example.test", "iPad"));
        String stored = db.sql("SELECT token_hash FROM device_token WHERE lower(owner_email) = 'hash@example.test'")
                .query(String.class).single();
        assertEquals(DeviceTokens.hash(issued.token()), stored);
        assertNotEquals(issued.token(), stored);
    }

    @Test
    void unknownGarbageAndBlankTokensResolveToNobody() {
        assertNull(tokens.resolve("not-a-token"));
        assertNull(tokens.resolve(""));
        assertNull(tokens.resolve(null));
        assertNull(tokens.exchange("no-such-code"));
        assertNull(tokens.exchange(null));
    }

    @Test
    void expiredCodeIsRefused() {
        MutableClock clock = new MutableClock();
        DeviceTokens timed = new DeviceTokens(db, clock);
        String code = timed.issueCode("late@example.test", "iPad");
        clock.now = clock.now.plus(DeviceTokens.CODE_TTL).plusSeconds(1);
        assertNull(timed.exchange(code));
    }

    @Test
    void revokeCutsResolutionAndListingButKeepsTheRow() {
        DeviceTokens.Issued issued = tokens.exchange(tokens.issueCode("rev@example.test", "Old iPad"));
        long id = tokens.resolve(issued.token()).tokenId();
        assertTrue(tokens.list().stream().anyMatch(d -> d.id() == id));

        assertTrue(tokens.revoke(id));
        assertFalse(tokens.revoke(id), "second revoke finds nothing active");
        assertNull(tokens.resolve(issued.token()));
        assertFalse(tokens.list().stream().anyMatch(d -> d.id() == id));
        Integer rows = db.sql("SELECT count(*) FROM device_token WHERE id = :id").param("id", id)
                .query(Integer.class).single();
        assertEquals(1, rows, "revoked rows stay for the record");
    }

    @Test
    void revokeAllForEmailIsCaseInsensitiveAndCounts() {
        tokens.exchange(tokens.issueCode("multi@example.test", "iPad A"));
        tokens.exchange(tokens.issueCode("Multi@Example.test", "iPad B"));
        DeviceTokens.Issued other = tokens.exchange(tokens.issueCode("other@example.test", "iPad"));

        assertEquals(2, tokens.revokeAllForEmail("  MULTI@example.TEST "));
        assertEquals(0, tokens.revokeAllForEmail("multi@example.test"));
        assertNotNull(tokens.resolve(other.token()), "other user's device untouched");
    }

    @Test
    void resolveStampsLastUsedOnce() {
        DeviceTokens.Issued issued = tokens.exchange(tokens.issueCode("touch@example.test", "iPad"));
        assertNull(tokens.list().stream().filter(d -> d.email().equals("touch@example.test"))
                .findFirst().orElseThrow().lastUsedAt(), "unused until the first bearer call");
        tokens.resolve(issued.token());
        assertNotNull(tokens.list().stream().filter(d -> d.email().equals("touch@example.test"))
                .findFirst().orElseThrow().lastUsedAt());
    }
}
