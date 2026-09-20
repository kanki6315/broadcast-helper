package com.pitpass.live;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pitpass.live.AksReplayServer.Recorded;
import com.pitpass.live.LiveTimingService.Pacing;
import com.pitpass.live.LiveTimingService.State;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The service end to end over a real socket, against {@link AksReplayServer}
 * — the same stand-in local dev replays recordings through. No database: the
 * store is a subclass holding the row in memory, shared between two services
 * where a test needs two processes contending for the one login.
 */
class LiveTimingServiceTest {

    private static final String PASSWORD = "s3cret-feed-password";

    private static final List<Recorded> FEED = List.of(
            new Recorded(1_000, "JSON:1::{\"timing\":{\"session\":{\"info\":{\"champName\":\"IMSA WeatherTech SportsCar Championship\","
                    + "\"eventName\":\"Petit Le Mans\",\"name\":\"Race\",\"type\":\"RACE\"}}}}"),
            new Recorded(1_001, "JSON:2::{\"timing\":{\"session\":{\"standings\":{\"byClass\":{\"active\":{\"GTP\":{\"class\":\"GTP\","
                    + "\"standings\":{\"1\":{\"participant\":\"7\",\"position\":1},\"2\":{\"participant\":\"31\",\"position\":2}}}}}}}}}"),
            new Recorded(1_002, "JSON:3::{\"timing\":{\"session\":{\"status\":{\"currentFlag\":\"GREEN\",\"isSessionRunning\":true}}}}"),
            // the diff that matters: the lead changes hands
            new Recorded(1_003, "JSON:4::{\"timing\":{\"session\":{\"standings\":{\"byClass\":{\"active\":{\"GTP\":{"
                    + "\"standings\":{\"1\":{\"participant\":\"31\"},\"2\":{\"participant\":\"7\"}}}}}}}}}"));

    private static final Pacing FAST = new Pacing(Duration.ofMillis(20), Duration.ofSeconds(2),
            List.of(Duration.ofMillis(50)), Duration.ofSeconds(5), Duration.ofSeconds(10));

    @TempDir
    Path recordings;

    private final List<AutoCloseable> cleanup = new ArrayList<>();
    private final BlockingQueue<Path> segments = new LinkedBlockingQueue<>();

    @AfterEach
    void tearDown() throws Exception {
        for (AutoCloseable c : cleanup.reversed()) {
            c.close();
        }
    }

    @Test
    void logsInJoinsAndMergesSnapshotsAndDiffs() throws Exception {
        AksReplayServer server = server(20);
        LiveTimingService service = service(server, new MemoryStore());

        service.request(true, 42L, "admin@example.test");
        await(() -> service.state("timing.session.status") != null);

        var status = service.status();
        assertEquals(State.LIVE, status.state());
        assertTrue(status.heldHere());
        assertEquals("Pit Pass replay", status.server().name());
        assertEquals("Petit Le Mans", status.session().event());
        assertEquals("GREEN", status.session().flag());
        assertEquals(42L, status.eventId());
        assertEquals("31", service.state("timing.session.standings.byClass.active.GTP.standings.1")
                .path("participant").asText());
        // untouched by the diff, so still from the snapshot
        assertEquals(1, service.state("timing.session.standings.byClass.active.GTP.standings.1")
                .path("position").asInt());
        assertEquals(1, server.logins());
    }

    @Test
    void pingsAtTheRateTheServerAsksFor() throws Exception {
        AksReplayServer server = server(1);
        LiveTimingService service = service(server, new MemoryStore());
        service.request(true, 1L, "t");
        await(() -> server.pings() >= 1);
        assertEquals(State.LIVE, service.status().state());
    }

    @Test
    void reconnectsAfterTheLinkDrops() throws Exception {
        AksReplayServer server = server(20);
        LiveTimingService service = service(server, new MemoryStore());
        service.request(true, 1L, "t");
        await(() -> service.status().state() == State.LIVE);

        server.dropClient();

        await(() -> server.logins() == 2 && service.status().state() == State.LIVE);
        assertEquals(1, service.status().drops());
        // LIVE is declared at login; the JOIN snapshots land a moment later.
        await(() -> service.state("timing.session.info") != null);
    }

    @Test
    void aRefusedLoginIsReportedAndNotHammered() throws Exception {
        AksReplayServer server = server(20);
        server.rejectLogins("User limit reached");
        LiveTimingService service = service(server, new MemoryStore());

        service.request(true, 1L, "t");
        await(() -> service.status().lastError() != null);
        Thread.sleep(300); // many ticks and many 50ms backoff steps — but not the refused-login wait

        var status = service.status();
        assertEquals(State.BACKING_OFF, status.state());
        assertTrue(status.lastError().contains("User limit reached"), status.lastError());
        assertEquals(1, status.attempts());
        assertNotNull(status.nextAttemptAt());
    }

    @Test
    void onlyTheLeaseHolderDialsAndAStoppedHolderHandsOver() throws Exception {
        AksReplayServer server = server(20);
        MemoryStore shared = new MemoryStore();
        LiveTimingService old = service(server, shared);
        LiveTimingService fresh = service(server, shared);

        old.request(true, 1L, "t");
        await(() -> old.status().state() == State.LIVE || fresh.status().state() == State.LIVE);
        LiveTimingService holder = old.status().state() == State.LIVE ? old : fresh;
        LiveTimingService waiting = holder == old ? fresh : old;
        await(() -> waiting.status().state() == State.STANDBY);
        assertEquals(1, server.logins());

        holder.stop(); // what SIGTERM does on a redeploy

        await(() -> waiting.status().state() == State.LIVE);
        assertEquals(2, server.logins());
        assertTrue(waiting.status().heldHere());
    }

    @Test
    void disconnectingFreesTheLoginAndTheLease() throws Exception {
        AksReplayServer server = server(20);
        MemoryStore store = new MemoryStore();
        LiveTimingService service = service(server, store);
        service.request(true, 7L, "t");
        await(() -> service.status().state() == State.LIVE);

        service.request(false, null, "t");

        await(() -> service.status().state() == State.OFF && store.read().holder() == null);
        assertNull(service.status().lastError(), "a disconnect we asked for is not an error");
        assertEquals(7L, service.status().eventId(), "the last binding stays visible");
        assertNull(service.state("timing"), "a stale running order must not outlive the connection");
        // the server's single slot is free again
        LiveTimingService next = service(server, new MemoryStore());
        next.request(true, 7L, "t");
        await(() -> next.status().state() == State.LIVE);
    }

    @Test
    void recordsWhatArrivedAndNeverThePassword() throws Exception {
        AksReplayServer server = server(20);
        LiveTimingService service = service(server, new MemoryStore());
        service.request(true, 1L, "t");
        await(() -> service.state("timing.session.status") != null);
        service.request(false, null, "t");

        Path segment = segments.poll(5, TimeUnit.SECONDS);
        assertNotNull(segment, "closing the connection finishes the segment");
        String text = gunzip(segment);
        assertTrue(text.contains("\tLOGIN:1+::"), text);
        assertTrue(text.contains("\tJSON:4::"), text);
        assertFalse(text.contains(PASSWORD));

        // and a recording is exactly what the replay server reads back
        List<Recorded> replayable = AksReplayServer.load(segment);
        assertEquals(FEED.size(), replayable.size());
        assertEquals(FEED.get(3).line(), replayable.get(3).line());
    }

    // ---- fixtures ------------------------------------------------------------------

    private AksReplayServer server(int pingRateSeconds) throws Exception {
        AksReplayServer server = new AksReplayServer(FEED, 0, pingRateSeconds).start();
        cleanup.add(server);
        return server;
    }

    private LiveTimingService service(AksReplayServer server, LiveTimingStore store) {
        AlKamelV2Properties props = new AlKamelV2Properties("127.0.0.1", server.port(), "feed-user", PASSWORD,
                false, false, "Pit Pass test",
                List.of("timing.session.info", "timing.session.status", "timing.session.standings.byClass.active"),
                1 << 20, 2,
                new AlKamelV2Properties.Recording(true, recordings.toString(), "", 10, 64),
                new AlKamelV2Properties.Replay("", 1.0));
        LiveTimingService service = new LiveTimingService(props, store, new ObjectMapper(), null,
                (segment, key) -> segments.add(segment), FAST);
        service.start();
        cleanup.add(service::stop);
        return service;
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        Instant deadline = Instant.now().plusSeconds(5);
        while (!condition.getAsBoolean()) {
            assertTrue(Instant.now().isBefore(deadline), "timed out waiting");
            Thread.sleep(10);
        }
    }

    private static String gunzip(Path file) throws Exception {
        try (InputStream in = new GZIPInputStream(Files.newInputStream(file))) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            in.transferTo(out);
            return out.toString(StandardCharsets.UTF_8);
        }
    }

    /** The live_timing row without Postgres; synchronized like the single UPDATE it stands in for. */
    static final class MemoryStore extends LiveTimingStore {
        private boolean desired;
        private Long eventId;
        private String by;
        private Instant at;
        private String holder;
        private Instant leaseExpires;

        MemoryStore() {
            super(null);
        }

        @Override
        public synchronized Row read() {
            return new Row(desired, eventId, by, at, holder, leaseExpires);
        }

        @Override
        public synchronized void request(boolean connected, Long event, String requestedBy) {
            desired = connected;
            eventId = event != null ? event : eventId;
            by = requestedBy;
            at = Instant.now();
        }

        @Override
        public synchronized boolean acquireOrRenew(String instance, Duration lease) {
            if (holder == null || holder.equals(instance) || leaseExpires.isBefore(Instant.now())) {
                holder = instance;
                leaseExpires = Instant.now().plus(lease);
                return true;
            }
            return false;
        }

        @Override
        public synchronized void release(String instance) {
            if (instance.equals(holder)) {
                holder = null;
                leaseExpires = null;
            }
        }

        @Override
        public Optional<String> eventName(long id) {
            return Optional.of("Event " + id);
        }
    }
}
