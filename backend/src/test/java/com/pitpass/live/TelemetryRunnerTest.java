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
import java.util.Map;
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
        return new ImsaTelemetryProperties(false, "https://example.invalid/", List.of("IMSA WeatherTech SportsCar Championship"),
                List.of("telemetry/message"), 15,
                true, replayFile, 0);
    }

    private TelemetryRunner runner(ImsaTelemetryProperties props, TelemetryRunner.SourceFactory sources,
                                   AtomicReference<Long> session) {
        return runner(props, sources, session, car -> null);
    }

    private TelemetryRunner runner(ImsaTelemetryProperties props, TelemetryRunner.SourceFactory sources,
                                   AtomicReference<Long> session, java.util.function.Function<String, String> feedClassOf) {
        return runner(props, sources, session, feedClassOf, Map::of);
    }

    private TelemetryRunner runner(ImsaTelemetryProperties props, TelemetryRunner.SourceFactory sources,
                                   AtomicReference<Long> session, java.util.function.Function<String, String> feedClassOf,
                                   java.util.function.Supplier<Map<String, Integer>> feedLaps) {
        TelemetryRunner r = new TelemetryRunner(props, mapper, sources,
                () -> new LiveRecorder(tmp.resolve("rec"), Duration.ofHours(1), (seg, key) -> segments.add(seg), "imsa-telemetry/"),
                session::get, op -> written.add(op), feedClassOf, feedLaps,
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
                new AnalysisRows.EnergyLap(3150, "7", 11, 88.2, false),
                new AnalysisRows.EnergyLap(3150, "04", 10, 67.5, true)), written);
        var status = r.status();
        assertEquals(TelemetryRunner.State.LIVE, status.state());
        assertEquals(frames.size(), status.messages());
        assertEquals(2, status.cars());
        assertEquals(87.0, r.telemetry().energyNow("7", null, System.currentTimeMillis(), 15_000));

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
    void aGtdLoggerWithNoLapCountIsSampledAtAlKamelsCrossings() throws Exception {
        Path file = tmp.resolve("gtd.imsa");
        Files.writeString(file, "1\t" + TelemetryFixtures.data("x", cars(car("023", 60, 0, false, "GTD"))) + "\n"
                + "2\t" + TelemetryFixtures.data("x", cars(car("023", 58, 0, false, "GTD"))) + "\n");
        AtomicInteger alKamelLaps = new AtomicInteger(20);
        TelemetryRunner r = runner(props(file.toString()), () -> new ReplayTelemetrySource(file, 0, mapper),
                new AtomicReference<>(3150L), car -> "GTD", () -> Map.of("023", alKamelLaps.getAndIncrement()));
        r.ensure(true);
        await(() -> written.size() == 1);
        assertEquals(List.of(new AnalysisRows.EnergyLap(3150, "023", 21, 58.0, false)), written);
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
        assertEquals(88.0, r.telemetry().energyNow("7", null, System.currentTimeMillis(), 15_000));
    }

    @Test
    void aCarOfAnotherSeriesSharingTheNumberStoresNoEnergy() throws Exception {
        // IMSA still streams WeatherTech's #7 (GTP) while Al Kamel times Pilot Challenge, whose #7 is GS.
        Path file = tmp.resolve("t2.imsa");
        Files.writeString(file, "1\t" + TelemetryFixtures.data("x", cars(car("7", 90, 1, false), car("04", 70, 1, false))) + "\n"
                + "2\t" + TelemetryFixtures.data("x", cars(car("7", 88, 2, false), car("04", 68, 2, false))) + "\n");
        TelemetryRunner r = runner(props(file.toString()), () -> new ReplayTelemetrySource(file, 0, mapper),
                new AtomicReference<>(3150L), car -> car.equals("7") ? "GS" : "GTP");
        r.ensure(true);
        await(() -> r.status().messages() == 2);
        assertEquals(List.of(new AnalysisRows.EnergyLap(3150, "04", 2, 68.0, false)), written, "only the car whose class agrees");
        assertEquals(1, r.status().classRejected());
        assertNull(r.telemetry().energyNow("7", "GS", System.currentTimeMillis(), 15_000),
                "nor is it shown on the GS car");
        assertEquals(88.0, r.telemetry().energyNow("7", "GTP", System.currentTimeMillis(), 15_000));
    }

    @Test
    void offWithAReasonWhenTheSeriesSendsNoEnergy() {
        TelemetryRunner r = runner(props("x"), () -> { throw new AssertionError("must not connect"); }, new AtomicReference<>(1L));
        r.ensure(false, "The bound event's series sends no energy telemetry");
        assertEquals(TelemetryRunner.State.OFF, r.status().state());
        assertEquals("The bound event's series sends no energy telemetry", r.status().idleReason());
        assertTrue(new ImsaTelemetryProperties(true, "", List.of("IMSA WeatherTech SportsCar Championship", "IWSC"), List.of(), 15,
                false, "", 1).coversSeries("imsa weathertech sportscar championship", null));
        assertTrue(new ImsaTelemetryProperties(true, "", List.of("IWSC"), List.of(), 15, false, "", 1).coversSeries("Renamed", "iwsc"));
        assertTrue(!new ImsaTelemetryProperties(true, "", List.of("IWSC"), List.of(), 15, false, "", 1)
                .coversSeries("IMSA Michelin Pilot Challenge", "IMPC"));
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
