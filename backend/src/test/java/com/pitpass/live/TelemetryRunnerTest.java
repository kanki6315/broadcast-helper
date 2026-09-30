package com.pitpass.live;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.zip.GZIPInputStream;

import static com.pitpass.live.TelemetryFixtures.car;
import static com.pitpass.live.TelemetryFixtures.cars;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The runner over a replayed recording (the production decoding path) and
 * over a source that keeps failing: samples reach the writer keyed to the Al
 * Kamel session, raw frames are recorded, and a broken endpoint only ever
 * costs a retry.
 */
class TelemetryRunnerTest {

    @TempDir
    Path tmp;

    private final ObjectMapper mapper = new ObjectMapper();
    private final List<AnalysisRows.Op> written = new CopyOnWriteArrayList<>();
    private final BlockingQueue<Path> segments = new LinkedBlockingQueue<>();
    private final List<TelemetryRunner> runners = new ArrayList<>();

    @AfterEach
    void stop() {
        runners.forEach(TelemetryRunner::stop);
    }

    private ImsaTelemetryProperties props(String replayFile) {
        return new ImsaTelemetryProperties(false, "https://example.invalid/", List.of("telemetry/message"), 15,
                true, replayFile, 0);
    }

    private TelemetryRunner runner(ImsaTelemetryProperties props, TelemetryRunner.SourceFactory sources,
                                   AtomicReference<Long> session) {
        TelemetryRunner r = new TelemetryRunner(props, mapper, sources,
                () -> new LiveRecorder(tmp.resolve("rec"), Duration.ofHours(1), (seg, key) -> segments.add(seg), "imsa-telemetry/"),
                session::get, op -> written.add(op),
                List.of(Duration.ofMillis(50)), Duration.ofMinutes(1));
        runners.add(r);
        return r;
    }

    @Test
    void aReplayedWeekendStoresOneEnergySamplePerCarPerLap() throws Exception {
        List<String> frames = List.of(
                "{\"type\":\"connection_ack\",\"connectionTimeoutMs\":300000}",
                "{\"type\":\"subscribe_success\",\"id\":\"old-id\"}",
                TelemetryFixtures.data("old-id", cars(car("7", 90.0, 10, false), car("04", 70.0, 9, false))),
                "{\"type\":\"ka\"}",
                TelemetryFixtures.data("old-id", cars(car("7", 88.2, 11, false), car("04", 69.0, 9, false))),
                TelemetryFixtures.data("old-id", cars(car("7", 87.0, 11, false), car("04", 67.5, 10, true))));
        Path file = tmp.resolve("telemetry.imsa");
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < frames.size(); i++) {
            text.append(1_000 + i).append('\t').append(frames.get(i)).append('\n');
        }
        Files.writeString(file, text);

        // A replay is never recorded again; this one records to prove the live path does.
        ImsaTelemetryProperties props = props(file.toString());
        AtomicReference<Long> session = new AtomicReference<>(3150L);
        TelemetryRunner r = runner(props, () -> new ReplayTelemetrySource(file, 0, mapper), session);
        r.ensure(true);
        await(() -> written.size() == 2);

        assertEquals(List.of(
                new AnalysisRows.EnergyLap(3150, "7", 10, 88.2, false),
                new AnalysisRows.EnergyLap(3150, "04", 9, 67.5, true)), written);
        var status = r.status();
        assertEquals(TelemetryRunner.State.LIVE, status.state());
        assertEquals(frames.size(), status.messages());
        assertEquals(2, status.cars());
        assertEquals(87.0, r.telemetry().energy("7", null, System.currentTimeMillis(), 15_000).energyPct());

        r.ensure(false);
        assertEquals(TelemetryRunner.State.OFF, r.status().state());
        Path segment = segments.poll(5, TimeUnit.SECONDS);
        assertNotNull(segment, "stopping finishes the recording segment");
        try (InputStream in = new GZIPInputStream(Files.newInputStream(segment))) {
            String recorded = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(recorded.contains("\t" + frames.get(2) + "\n"), "raw frames, byte for byte");
        }
    }

    @Test
    void withNoAlKamelSessionEnergyIsShownButNotStored() throws Exception {
        Path file = tmp.resolve("t.imsa");
        Files.writeString(file, "1\t" + TelemetryFixtures.data("x", cars(car("7", 90, 1, false))) + "\n"
                + "2\t" + TelemetryFixtures.data("x", cars(car("7", 88, 2, false))) + "\n");
        TelemetryRunner r = runner(props(file.toString()), () -> new ReplayTelemetrySource(file, 0, mapper),
                new AtomicReference<>(null));
        r.ensure(true);
        await(() -> r.status().messages() == 2);
        assertTrue(written.isEmpty());
        assertEquals(88.0, r.telemetry().energy("7", null, System.currentTimeMillis(), 15_000).energyPct());
    }

    @Test
    void aBrokenEndpointBacksOffAndNeverThrows() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        TelemetryRunner r = runner(props("x"), () -> new TelemetrySource() {
            @Override
            public void run(Listener listener) throws Exception {
                attempts.incrementAndGet();
                throw new IOException("The telemetry app's bundle no longer names an AppSync endpoint and key");
            }

            @Override
            public void close() {
            }
        }, new AtomicReference<>(1L));
        r.ensure(true);
        await(() -> attempts.get() >= 3);
        var status = r.status();
        assertTrue(status.lastError().contains("no longer names an AppSync endpoint"), status.lastError());
        assertTrue(status.state() == TelemetryRunner.State.BACKING_OFF || status.state() == TelemetryRunner.State.CONNECTING);
        r.ensure(false);
        assertEquals(TelemetryRunner.State.OFF, r.status().state());
        assertNull(r.status().nextAttemptAt());
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        Instant deadline = Instant.now().plusSeconds(5);
        while (!condition.getAsBoolean()) {
            assertTrue(Instant.now().isBefore(deadline), "timed out waiting");
            Thread.sleep(10);
        }
    }
}
