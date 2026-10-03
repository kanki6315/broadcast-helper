package com.pitpass.live;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Keeps the IMSA telemetry connection up while {@link LiveTimingService} says
 * it should be: only in the process holding the Al Kamel lease, and only
 * while the feed is asked for. Its own thread and its own backoff, and it
 * fails soft — nothing here can touch the Al Kamel connection, and a broken
 * or vanished endpoint costs a log line and a retry, never an exception
 * upward.
 *
 * Lap-crossing energy samples (see LiveTelemetry) are written through the
 * analysis writer, keyed to the Al Kamel session being fed; with no such session (analysis off) the
 * energy is shown live but not stored.
 */
final class TelemetryRunner {

    public enum State { OFF, CONNECTING, LIVE, BACKING_OFF }

    /**
     * idleReason says why it is OFF while the feed is connected (the bound
     * event's series sends no energy). classRejected counts lap samples not
     * stored because IMSA's class for the car disagreed with the Al Kamel feed's.
     */
    public record Status(boolean configured, boolean replaying, State state, String source, String lastError,
                         String idleReason, Instant lastMessageAt, long messages, int cars, long lapsStored,
                         long classRejected, Instant nextAttemptAt) {
    }

    /** Builds a fresh source per attempt. */
    interface SourceFactory {
        TelemetrySource create();
    }

    /** A fresh recorder per connection, or null when recording is off. */
    interface RecorderFactory {
        LiveRecorder create();
    }

    private static final Logger log = LoggerFactory.getLogger(TelemetryRunner.class);

    private final ImsaTelemetryProperties props;
    private final TelemetryDecoder decoder;
    private final SourceFactory sources;
    private final RecorderFactory recorders;
    private final Supplier<Long> sessionDbId;
    private final AnalysisRouter.Sink sink;
    private final Function<String, String> feedClassOf;
    private final Supplier<Map<String, Integer>> feedLaps;
    private final List<Duration> backoff;
    private final Duration stableAfter;
    private final LiveTelemetry telemetry = new LiveTelemetry();
    private final AtomicLong messages = new AtomicLong();
    private final AtomicLong lapsStored = new AtomicLong();
    private final AtomicLong classRejected = new AtomicLong();

    private volatile boolean running;
    private volatile State state = State.OFF;
    private volatile String source;
    private volatile String lastError;
    private volatile String idleReason;
    private volatile Instant lastMessageAt;
    private volatile Instant nextAttemptAt;
    private volatile TelemetrySource current;
    private volatile Long lastSession;
    private Thread thread;

    TelemetryRunner(ImsaTelemetryProperties props, ObjectMapper mapper, SourceFactory sources,
                    RecorderFactory recorders, Supplier<Long> sessionDbId, AnalysisRouter.Sink sink,
                    Function<String, String> feedClassOf, Supplier<Map<String, Integer>> feedLaps,
                    List<Duration> backoff, Duration stableAfter) {
        this.props = props;
        this.decoder = new TelemetryDecoder(mapper);
        this.sources = sources;
        this.recorders = recorders;
        this.sessionDbId = sessionDbId;
        this.sink = sink;
        this.feedClassOf = feedClassOf;
        this.feedLaps = feedLaps;
        this.backoff = backoff;
        this.stableAfter = stableAfter;
    }

    LiveTelemetry telemetry() {
        return telemetry;
    }

    Status status() {
        return new Status(props.configured(), props.replaying(), state, source, lastError,
                running ? null : idleReason, lastMessageAt, messages.get(), telemetry.cars(), lapsStored.get(),
                classRejected.get(), state == State.BACKING_OFF ? nextAttemptAt : null);
    }

    /** Called on every supervisor tick: start or stop to match. Idempotent. */
    synchronized void ensure(boolean shouldRun) {
        ensure(shouldRun, null);
    }

    /** whyNot is shown in the status while it stays off (a series that sends no energy). */
    synchronized void ensure(boolean shouldRun, String whyNot) {
        idleReason = shouldRun ? null : whyNot;
        if (shouldRun && props.configured() && !running) {
            running = true;
            lastError = null;
            thread = Thread.ofVirtual().name("imsa-telemetry").start(this::loop);
        } else if (!shouldRun && running) {
            stop();
        }
    }

    synchronized void stop() {
        running = false;
        TelemetrySource s = current;
        if (s != null) {
            s.close();
        }
        if (thread != null) {
            thread.interrupt();
            try {
                thread.join(Duration.ofSeconds(5));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            thread = null;
        }
        telemetry.clear();
        state = State.OFF;
    }

    private void loop() {
        int step = 0;
        while (running) {
            state = State.CONNECTING;
            Instant started = Instant.now();
            LiveRecorder recorder = null;
            try {
                recorder = recorders == null ? null : recorders.create();
                TelemetrySource s = sources.create();
                current = s;
                s.run(listener(recorder));
            } catch (InterruptedException e) {
                // stopping
            } catch (Exception | LinkageError e) {
                if (running) {
                    lastError = e.getMessage() == null ? e.toString() : e.getMessage();
                }
            } finally {
                current = null;
                if (recorder != null) {
                    recorder.close();
                }
            }
            if (!running) {
                break;
            }
            if (Duration.between(started, Instant.now()).compareTo(stableAfter) >= 0) {
                step = 0;
            }
            Duration wait = backoff.get(Math.min(step++, backoff.size() - 1));
            nextAttemptAt = Instant.now().plus(wait);
            state = State.BACKING_OFF;
            log.warn("IMSA telemetry connection ended ({}); next attempt in {}s", lastError, wait.toSeconds());
            try {
                Thread.sleep(wait);
            } catch (InterruptedException e) {
                // stopping
            }
        }
        state = State.OFF;
    }

    private TelemetrySource.Listener listener(LiveRecorder recorder) {
        return new TelemetrySource.Listener() {
            @Override
            public void connected(String description) {
                source = description;
                lastError = null;
                state = State.LIVE;
                log.info("IMSA telemetry connected: {}", description);
            }

            @Override
            public void raw(long epochMs, String text) {
                messages.incrementAndGet();
                lastMessageAt = Instant.ofEpochMilli(epochMs);
                if (recorder != null) {
                    recorder.write(epochMs, text.getBytes(StandardCharsets.UTF_8));
                }
            }

            @Override
            public void event(String channel, String payload) {
                // A new Al Kamel session restarts lap counts; the old crossings are not this session's.
                Long session = sessionDbId.get();
                if (!Objects.equals(session, lastSession)) {
                    telemetry.clear();
                    lastSession = session;
                }
                TelemetryDecoder.Decoded decoded = decoder.decode(payload);
                // Al Kamel's lap counts time the crossings; a car's logger count stands in where the feed has none.
                Map<String, Integer> alKamelLaps = decoded.cars().isEmpty() || feedLaps == null ? Map.of() : feedLaps.get();
                List<LiveTelemetry.LapSample> laps = telemetry.accept(decoded, System.currentTimeMillis(), alKamelLaps);
                if (session == null || sink == null) {
                    return;
                }
                for (LiveTelemetry.LapSample lap : laps) {
                    // Another series' car sharing the number must not file energy under this session.
                    String feedClass = feedClassOf == null ? null : feedClassOf.apply(lap.car());
                    if (!LiveTelemetry.classAgrees(lap.className(), feedClass)) {
                        classRejected.incrementAndGet();
                        continue;
                    }
                    if (sink.offer(new AnalysisRows.EnergyLap(session, lap.car(), lap.lap(), lap.energyPct(), lap.pitLane()))) {
                        lapsStored.incrementAndGet();
                    }
                }
            }
        };
    }
}
