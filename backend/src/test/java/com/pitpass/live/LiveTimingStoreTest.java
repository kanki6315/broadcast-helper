package com.pitpass.live;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The lease SQL against the real Postgres — the one guarantee that two
 * processes never both use the account's single login. Rolled back, so the
 * live_timing row is left as the migration seeded it.
 */
@SpringBootTest
@Transactional
class LiveTimingStoreTest {

    @Autowired
    private LiveTimingStore store;

    @Autowired
    private LiveTimingController controller;

    @Test
    void theRowStartsOffAndUnheld() {
        var row = store.read();
        assertFalse(row.desiredConnected());
        assertNull(row.holder());
    }

    @Test
    void aHeldLeaseShutsOthersOutUntilReleased() {
        assertTrue(store.acquireOrRenew("old-process", Duration.ofSeconds(30)));
        assertFalse(store.acquireOrRenew("new-process", Duration.ofSeconds(30)));
        assertTrue(store.acquireOrRenew("old-process", Duration.ofSeconds(30)), "the holder renews freely");

        store.release("new-process"); // not the holder: a no-op
        assertEquals("old-process", store.read().holder());

        store.release("old-process");
        assertTrue(store.acquireOrRenew("new-process", Duration.ofSeconds(30)));
    }

    @Test
    void aLapsedLeaseCanBeTakenOver() {
        // A process that died without releasing: its lease is simply in the past.
        assertTrue(store.acquireOrRenew("crashed-process", Duration.ofSeconds(-1)));
        assertTrue(store.acquireOrRenew("new-process", Duration.ofSeconds(30)));
        assertEquals("new-process", store.read().holder());
    }

    @Test
    void disconnectingKeepsTheLastEventBinding() {
        store.request(true, null, "admin@example.test");
        assertTrue(store.read().desiredConnected());
        assertEquals("admin@example.test", store.read().requestedBy());

        store.request(false, null, "someone-else@example.test");
        assertFalse(store.read().desiredConnected());
        assertEquals("someone-else@example.test", store.read().requestedBy());
    }

    @Test
    void connectingIsRefusedWhereTheFeedIsNotConfigured() {
        // The test context has no ALKAMELV2_HOST — the state of local dev and CI.
        assertEquals("NOT_CONFIGURED", controller.status().state().name());
        var refused = assertThrows(ResponseStatusException.class,
                () -> controller.connect(new LiveTimingController.ConnectRequest(1L), null));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, refused.getStatusCode());
        assertFalse(store.read().desiredConnected());
    }
}
