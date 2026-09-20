package com.pitpass.live;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pitpass.images.PublicImageStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * Keeps the one Al Kamel live timing connection in the state an admin asked
 * for. The account allows a single concurrent login, which shapes everything:
 *
 * - What was asked for lives in Postgres ({@link LiveTimingStore}), not in
 *   memory, so the process a redeploy starts carries on where the old one was.
 * - Only the process holding the lease dials out. During a redeploy the old
 *   and new processes overlap; the new one stands by until the old one, on
 *   SIGTERM, closes its socket and releases the lease.
 * - A refused login backs off for a long time rather than hammering a server
 *   that may be telling us the login is in use elsewhere.
 *
 * This is the backend's only long-lived background work. Unconfigured (no
 * ALKAMELV2_HOST, no replay file) it starts no thread at all.
 */
@Component
@EnableConfigurationProperties(AlKamelV2Properties.class)
public class LiveTimingService implements SmartLifecycle {

    public enum State { NOT_CONFIGURED, OFF, STANDBY, CONNECTING, LIVE, BACKING_OFF }

    /** Pacing, separated out so tests run in milliseconds. */
    record Pacing(Duration tick, Duration lease, List<Duration> backoff,
                  Duration loginRejectedBackoff, Duration stableAfter) {
        static final Pacing PRODUCTION = new Pacing(
                Duration.ofSeconds(3), Duration.ofSeconds(30),
                List.of(Duration.ofSeconds(2), Duration.ofSeconds(5), Duration.ofSeconds(10),
                        Duration.ofSeconds(20), Duration.ofSeconds(30), Duration.ofSeconds(60)),
                Duration.ofSeconds(60), Duration.ofSeconds(60));
    }

    public record Server(String name, String version, int pingRateSeconds, int timeoutSeconds) {
    }

    /** What the feed says is running — shown so the admin can see it matches the bound event. */
    public record Session(String championship, String event, String name, String type,
                          String flag, boolean running, boolean finished) {
    }

    public record LiveStatus(State state, boolean configured, boolean replaying,
                             boolean desiredConnected, Long eventId, String eventName,
                             String requestedBy, Instant requestedAt,
                             String holder, boolean heldHere,
                             Instant connectedSince, Instant lastMessageAt, long messages, long bytes,
                             int attempts, int drops, String lastError, String lastWarning,
                             Instant nextAttemptAt, Server server, Session session, List<String> channels) {
    }

    private static final Logger log = LoggerFactory.getLogger(LiveTimingService.class);

    private final AlKamelV2Properties props;
    private final LiveTimingStore store;
    private final ObjectMapper mapper;
    private final PublicImageStorage storage;
    private final LiveRecorder.Sink sink;
    private final Pacing pacing;
    private final String instanceId = instanceName();
    private final AksStateTree tree = new AksStateTree();
    private final Semaphore wake = new Semaphore(0);
    private final AtomicLong messages = new AtomicLong();
    private final AtomicLong bytes = new AtomicLong();

    private volatile boolean running;
    private volatile State state;
    private volatile boolean leaseHeld;
    private volatile Instant leaseGoodUntil = Instant.MIN;
    private volatile Instant connectedSince;
    private volatile Instant lastMessageAt;
    private volatile Instant nextAttemptAt = Instant.MIN;
    private volatile String lastError;
    private volatile String lastWarning;
    private volatile Server server;
    private volatile int attempts;
    private volatile int drops;
    private volatile int backoffStep;
    private volatile boolean stopping;

    private Thread supervisor;
    private Thread connectionThread;
    private AksConnection connection;
    private AksReplayServer replayServer;

    @Autowired
    public LiveTimingService(AlKamelV2Properties props, LiveTimingStore store, ObjectMapper mapper,
                             PublicImageStorage storage) {
        this(props, store, mapper, storage, null, Pacing.PRODUCTION);
    }

    /** Tests pass their own sink and pacing; a null sink means the real one. */
    LiveTimingService(AlKamelV2Properties props, LiveTimingStore store, ObjectMapper mapper,
                      PublicImageStorage storage, LiveRecorder.Sink sink, Pacing pacing) {
        this.props = props;
        this.store = store;
        this.mapper = mapper;
        this.storage = storage;
        this.sink = sink != null ? sink : this::storeSegment;
        this.pacing = pacing;
        this.state = props.configured() ? State.OFF : State.NOT_CONFIGURED;
    }

    // ---- what the controller calls -------------------------------------------------

    public boolean configured() {
        return props.configured();
    }

    public void request(boolean connected, Long eventId, String requestedBy) {
        store.request(connected, eventId, requestedBy);
        wake.release(); // act now if this process is the one that should
    }

    public LiveStatus status() {
        LiveTimingStore.Row row = store.read();
        boolean heldHere = instanceId.equals(row.holder());
        State shown = state;
        // Asked for, but the supervisor has not ticked yet: say what is about to
        // be true. Held elsewhere → this process stands by; held by nobody →
        // this process is the one about to dial.
        if (props.configured() && !heldHere && row.desiredConnected() && shown == State.OFF) {
            shown = row.holder() == null ? State.CONNECTING : State.STANDBY;
        }
        // The same the other way: a disconnect just asked for is OFF to the
        // caller, even in the instant before the supervisor closes the socket.
        if (props.configured() && !row.desiredConnected()) {
            shown = State.OFF;
        }
        return new LiveStatus(shown, props.configured(), props.replaying(),
                row.desiredConnected(), row.eventId(),
                row.eventId() == null ? null : store.eventName(row.eventId()).orElse(null),
                row.requestedBy(), row.requestedAt(),
                row.holder(), heldHere,
                connectedSince, lastMessageAt, messages.get(), bytes.get(),
                attempts, drops, lastError, lastWarning,
                state == State.BACKING_OFF ? nextAttemptAt : null,
                server, session(), props.configured() ? props.channels() : List.of());
    }

    /** A copy of the merged feed at a dotted path, or null. The raw material for every live feature. */
    public JsonNode state(String dottedPath) {
        return tree.copyOf(dottedPath);
    }

    private Session session() {
        JsonNode info = tree.copyOf("timing.session.info");
        if (info == null) {
            return null;
        }
        JsonNode status = tree.copyOf("timing.session.status");
        JsonNode s = status == null ? mapper.createObjectNode() : status;
        return new Session(
                info.path("champName").asText(null), info.path("eventName").asText(null),
                info.path("name").asText(null), info.path("type").asText(null),
                s.path("currentFlag").asText(null),
                s.path("isSessionRunning").asBoolean(false), s.path("isFinished").asBoolean(false));
    }

    // ---- lifecycle -----------------------------------------------------------------

    @Override
    public void start() {
        if (!props.configured() || running) {
            return;
        }
        if (props.replaying()) {
            try {
                Path file = Path.of(props.replay().file());
                replayServer = new AksReplayServer(AksReplayServer.load(file), props.replay().speed(), 20).start();
                log.info("Live timing replays {} on loopback port {}", file, replayServer.port());
            } catch (IOException e) {
                throw new IllegalStateException("Cannot read ALKAMELV2_REPLAY_FILE: " + e.getMessage(), e);
            }
        }
        running = true;
        supervisor = Thread.ofVirtual().name("aks-supervisor").start(this::supervise);
    }

    /** SIGTERM lands here: free the account's one login before the process goes. */
    @Override
    public void stop() {
        running = false;
        wake.release();
        if (supervisor != null) {
            try {
                supervisor.join(Duration.ofSeconds(25)); // inside Spring's 30s shutdown phase
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (replayServer != null) {
            replayServer.close();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    // ---- supervisor ----------------------------------------------------------------

    private void supervise() {
        while (running) {
            try {
                tick();
            } catch (RuntimeException e) {
                // Usually the database. If the lease can no longer be proven
                // ours, another process may already be dialling — let go.
                log.warn("Live timing supervisor: {}", e.toString());
                if (leaseHeld && Instant.now().isAfter(leaseGoodUntil)) {
                    stopConnection();
                    leaseHeld = false;
                    state = State.STANDBY;
                }
            }
            try {
                wake.tryAcquire(pacing.tick().toMillis(), TimeUnit.MILLISECONDS);
                wake.drainPermits();
            } catch (InterruptedException e) {
                break;
            }
        }
        // Order matters for a redeploy: the socket closes first (the login is
        // free), the lease goes next (the new process may dial at once), and
        // only then do we wait on the last recording segment's upload.
        closeConnection();
        if (leaseHeld) {
            try {
                store.release(instanceId);
            } catch (RuntimeException e) {
                log.warn("Could not release the live timing lease; it lapses in {}s", pacing.lease().toSeconds());
            }
            leaseHeld = false;
        }
        awaitConnection(Duration.ofSeconds(20));
    }

    private void tick() {
        LiveTimingStore.Row row = store.read();
        if (!row.desiredConnected()) {
            stopConnection();
            if (leaseHeld) {
                store.release(instanceId);
                leaseHeld = false;
            }
            // Off means off: a stale running order must not outlive the connection.
            // (While BACKING_OFF the last-known tree is kept on purpose.)
            tree.clear();
            state = State.OFF;
            backoffStep = 0;
            attempts = 0;
            drops = 0;
            nextAttemptAt = Instant.MIN;
            return;
        }
        if (!store.acquireOrRenew(instanceId, pacing.lease())) {
            stopConnection();
            tree.clear();
            leaseHeld = false;
            state = State.STANDBY;
            return;
        }
        leaseHeld = true;
        leaseGoodUntil = Instant.now().plus(pacing.lease());
        if (connectionThread != null && connectionThread.isAlive()) {
            return;
        }
        if (Instant.now().isBefore(nextAttemptAt)) {
            state = State.BACKING_OFF;
            return;
        }
        startConnection();
    }

    private void startConnection() {
        tree.clear(); // the new login's JOIN snapshots are the whole truth
        server = null;
        messages.set(0);
        bytes.set(0);
        stopping = false;
        attempts++;
        state = State.CONNECTING;
        LiveRecorder recorder = props.recording().enabled()
                ? new LiveRecorder(recordingDirectory(), Duration.ofMinutes(Math.max(1, props.recording().segmentMinutes())), sink)
                : null;
        String host = replayServer != null ? "127.0.0.1" : props.host();
        int port = replayServer != null ? replayServer.port() : props.port();
        boolean tls = replayServer == null && props.tlsEnabled();
        AksConnection conn = new AksConnection(props, host, port, tls, tree, mapper, new AksConnection.Listener() {
            @Override
            public void loggedIn(AksConnection.ServerInfo info) {
                server = new Server(info.name(), info.version(), info.pingRateSeconds(), info.timeoutSeconds());
                connectedSince = Instant.now();
                lastError = null;
                state = State.LIVE;
                log.info("Live timing connected to {} ({})", info.name(), info.version());
            }

            @Override
            public void received(long epochMs, byte[] line) {
                messages.incrementAndGet();
                bytes.addAndGet(line.length + 2L);
                lastMessageAt = Instant.ofEpochMilli(epochMs);
                if (recorder != null) {
                    recorder.write(epochMs, line);
                }
            }

            @Override
            public void warned(String message) {
                lastWarning = message;
                log.warn("Live timing: {}", message);
            }
        });
        connection = conn;
        connectionThread = Thread.ofVirtual().name("aks-connection").start(() -> runConnection(conn, recorder));
    }

    private void runConnection(AksConnection conn, LiveRecorder recorder) {
        boolean rejected = false;
        try {
            conn.run();
        } catch (AksConnection.LoginRejected e) {
            rejected = true;
            lastError = e.getMessage();
        } catch (IOException | RuntimeException e) {
            if (!stopping) {
                lastError = e.getMessage() == null ? e.toString() : e.getMessage();
            }
        } finally {
            // Bookkeeping before the recorder: closing it uploads the last
            // segment, and by then a successor connection may own these fields.
            Instant liveSince = connectedSince;
            connectedSince = null;
            if (!stopping) {
                if (liveSince != null) {
                    drops++;
                    // A connection that held for a while was healthy: start the ladder over.
                    if (Duration.between(liveSince, Instant.now()).compareTo(pacing.stableAfter()) >= 0) {
                        backoffStep = 0;
                    }
                }
                Duration wait = pacing.backoff().get(Math.min(backoffStep, pacing.backoff().size() - 1));
                if (rejected && wait.compareTo(pacing.loginRejectedBackoff()) < 0) {
                    wait = pacing.loginRejectedBackoff();
                }
                backoffStep++;
                nextAttemptAt = Instant.now().plus(wait);
                state = State.BACKING_OFF;
                log.warn("Live timing connection ended ({}); next attempt in {}s", lastError, wait.toSeconds());
            }
            if (recorder != null) {
                recorder.close();
            }
        }
    }

    // Supervisor thread only.
    private void stopConnection() {
        closeConnection();
        awaitConnection(Duration.ofSeconds(5));
    }

    private void closeConnection() {
        if (connectionThread != null) {
            stopping = true;
            if (connection != null) {
                connection.close(); // synchronous: the login is free when this returns
            }
        }
    }

    private void awaitConnection(Duration patience) {
        Thread t = connectionThread;
        if (t == null) {
            return;
        }
        try {
            t.join(patience);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        connectionThread = null;
        connection = null;
    }

    // ---- recordings ----------------------------------------------------------------

    private Path recordingDirectory() {
        String configured = props.recording().directory();
        return configured == null || configured.isBlank()
                ? Path.of(System.getProperty("java.io.tmpdir"), "pit-pass-aks")
                : Path.of(configured);
    }

    private void storeSegment(Path segment, String key) {
        String bucket = props.recording().bucket();
        if (bucket != null && !bucket.isBlank() && storage != null && storage.enabled()) {
            try {
                storage.uploadPrivate(bucket, key, segment, "application/gzip");
                Files.deleteIfExists(segment);
                return;
            } catch (IOException | RuntimeException e) {
                log.warn("Live timing segment {} stays on local disk: {}", segment.getFileName(), e.toString());
            }
        }
        pruneLocal(segment.getParent(), props.recording().maxLocalMegabytes() * 1024L * 1024L);
    }

    /** Oldest first, until what remains fits. File names sort by time. */
    private static void pruneLocal(Path directory, long budgetBytes) {
        try (Stream<Path> files = Files.list(directory)) {
            List<Path> newestFirst = files.filter(p -> p.getFileName().toString().endsWith(".aks.gz"))
                    .sorted(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed())
                    .toList();
            long kept = 0;
            for (Path file : newestFirst) {
                kept += Files.size(file);
                if (kept > budgetBytes) {
                    Files.deleteIfExists(file);
                }
            }
        } catch (IOException e) {
            log.warn("Could not prune live timing recordings: {}", e.toString());
        }
    }

    private static String instanceName() {
        String name = System.getenv("RAILWAY_REPLICA_ID");
        if (name == null || name.isBlank()) {
            try {
                name = InetAddress.getLocalHost().getHostName();
            } catch (IOException e) {
                name = "pit-pass";
            }
        }
        return name + "-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
