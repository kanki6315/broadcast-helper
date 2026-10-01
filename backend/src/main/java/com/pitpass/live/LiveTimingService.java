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
@EnableConfigurationProperties({AlKamelV2Properties.class, ImsaTelemetryProperties.class})
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
    /** feedEventDbId is Al Kamel's event id: the series weekend the session is filed by (LiveFiling). */
    public record Session(String championship, String event, String name, String type,
                          String flag, boolean running, boolean finished, Long feedEventDbId) {
    }

    public record LiveStatus(State state, boolean configured, boolean replaying,
                             boolean desiredConnected, Long eventId, String eventName,
                             String requestedBy, Instant requestedAt,
                             String holder, boolean heldHere,
                             Instant connectedSince, Instant lastMessageAt, long messages, long bytes,
                             int attempts, int drops, String lastError, String lastWarning,
                             Instant nextAttemptAt, Server server, Session session, List<String> channels,
                             Analysis analysis, TelemetryRunner.Status telemetry,
                             Long filedEventId, String filedEventName) {
    }

    /**
     * timing.analysis ingest, when enabled. laps/stints count patches read on
     * this connection; dropped were turned away by a full queue;
     * withoutSession arrived before timing.session.info named the session.
     */
    public record Analysis(boolean enabled, long laps, long stints, long dropped, long withoutSession,
                           int queued, long written, long failed) {
    }

    private static final Logger log = LoggerFactory.getLogger(LiveTimingService.class);

    private final AlKamelV2Properties props;
    private final LiveTimingStore store;
    private final ObjectMapper mapper;
    private final PublicImageStorage storage;
    private final LiveRecorder.Sink sink;
    private final Pacing pacing;
    private final AnalysisWriter analysisWriter;
    private final LiveCarSummaries summaries = new LiveCarSummaries();
    private final ImsaTelemetryProperties telemetryProps;
    private final LiveFiling filing;
    /** When an unbound feed event may be tried again, and the next sweep of recent ones. */
    private volatile String lastFilingTry;
    private volatile Instant nextFilingTry = Instant.MIN;
    private volatile Instant nextSweep = Instant.MIN;
    /** eventId → whether its series sends IMSA energy; series do not change mid-weekend. */
    private final java.util.Map<Long, Boolean> telemetrySeries = new java.util.concurrent.ConcurrentHashMap<>();
    private final TelemetryRunner telemetry;
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
    private volatile Long boundEventId;
    private volatile AnalysisRouter router;

    private Thread supervisor;
    private Thread connectionThread;
    private AksConnection connection;
    private AksReplayServer replayServer;

    @Autowired
    public LiveTimingService(AlKamelV2Properties props, LiveTimingStore store, ObjectMapper mapper,
                             PublicImageStorage storage, AnalysisWriter analysisWriter,
                             LiveDriverResolver driverResolver, ImsaTelemetryProperties telemetryProps,
                             LiveFiling filing) {
        this(props, store, mapper, storage, null, Pacing.PRODUCTION, analysisWriter, driverResolver, telemetryProps,
                filing);
    }

    /** Tests pass their own sink and pacing; a null sink means the real one. */
    LiveTimingService(AlKamelV2Properties props, LiveTimingStore store, ObjectMapper mapper,
                      PublicImageStorage storage, LiveRecorder.Sink sink, Pacing pacing) {
        this(props, store, mapper, storage, sink, pacing, null, null, null, null);
    }

    /** A null writer leaves timing.analysis off whatever the configuration says. */
    LiveTimingService(AlKamelV2Properties props, LiveTimingStore store, ObjectMapper mapper,
                      PublicImageStorage storage, LiveRecorder.Sink sink, Pacing pacing,
                      AnalysisWriter analysisWriter, AnalysisWriter.DriverResolver driverResolver,
                      ImsaTelemetryProperties telemetryProps, LiveFiling filing) {
        this.props = props;
        this.store = store;
        this.mapper = mapper;
        this.storage = storage;
        this.sink = sink != null ? sink : this::storeSegment;
        this.pacing = pacing;
        this.filing = filing;
        this.analysisWriter = props.analysisEnabled() ? analysisWriter : null;
        if (this.analysisWriter != null) {
            this.analysisWriter.drivers(driverResolver, () -> tree.copyOf("timing.session.entry"), this::filedEventId);
        }
        this.telemetryProps = telemetryProps;
        this.telemetry = telemetryProps == null || !telemetryProps.configured() ? null : new TelemetryRunner(
                telemetryProps, mapper,
                () -> telemetryProps.replaying()
                        ? new ReplayTelemetrySource(Path.of(telemetryProps.replayFile()), telemetryProps.replaySpeed(), mapper)
                        : new AppSyncTelemetrySource(telemetryProps, mapper),
                () -> telemetryProps.recordingEnabled() && !telemetryProps.replaying() && props.recording() != null
                        ? new LiveRecorder(recordingDirectory().resolve("imsa-telemetry"),
                                Duration.ofMinutes(Math.max(1, props.recording().segmentMinutes())), this.sink, "imsa-telemetry/")
                        : null,
                summaries::sessionDbId, this.analysisWriter, this::feedClassOf,
                List.of(Duration.ofSeconds(5), Duration.ofSeconds(15), Duration.ofSeconds(30),
                        Duration.ofSeconds(60), Duration.ofMinutes(5)),
                Duration.ofMinutes(1));
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

    /**
     * Connects with no event: every series on track is recorded and filed by
     * championship (LiveFiling). Clears any earlier binding, so a stale one
     * cannot be tried against the next series' cars.
     */
    public void connectWithoutEvent(String requestedBy) {
        store.connectWithoutEvent(requestedBy);
        wake.release();
    }

    /**
     * eventId is the event an admin bound the connection to: a hint for
     * filing, and where the connect control stands. filedEventId is the event
     * the session on track is filed under ({@link #overlayEventId}) — the one
     * whose Pit Pass rows belong with it. They differ when the binding has
     * outlived its series' session.
     */
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
        String bound = row.eventId() == null ? null : store.eventName(row.eventId()).orElse(null);
        Long filed = overlayEventId(row.eventId());
        return new LiveStatus(shown, props.configured(), props.replaying(),
                row.desiredConnected(), row.eventId(), bound,
                row.requestedBy(), row.requestedAt(),
                row.holder(), heldHere,
                connectedSince, lastMessageAt, messages.get(), bytes.get(),
                attempts, drops, lastError, lastWarning,
                state == State.BACKING_OFF ? nextAttemptAt : null,
                server, session(), props.configured() ? props.joinedChannels() : List.of(), analysis(),
                telemetry == null ? null : telemetry.status(), filed,
                filed == null ? null : filed.equals(row.eventId()) ? bound : store.eventName(filed).orElse(null));
    }

    /**
     * The event whose Pit Pass rows — entries, class colours, driver links,
     * championships — belong with the session on track: the one it is filed
     * under, read from live_session so a restart does not forget it. Never
     * just the binding: an IMPC binding left up while VP Racing runs would
     * put IMPC teams on VP cars that share their numbers. Without analysis
     * nothing is recorded or filed, so the binding stands in, as it always has.
     */
    Long overlayEventId(Long boundEventId) {
        if (analysisWriter == null) {
            return boundEventId;
        }
        Long session = analysisSessionDbId();
        return session == null ? null : store.filedEvent(session).orElse(null);
    }

    /**
     * IMSA telemetry energy for an Al Kamel car number, or null when telemetry
     * is off or has never seen the car. stintOpenLap scopes the per-lap average.
     */
    public LiveTelemetry.CarEnergy energy(String carNumber, String feedClass, Integer stintOpenLap) {
        if (telemetry == null) {
            return null;
        }
        return telemetry.telemetry().energy(carNumber, feedClass, stintOpenLap, System.currentTimeMillis(),
                Math.max(1, telemetryProps.staleSeconds()) * 1000L);
    }

    /** The event the current session is filed under (live_session), or null. Drivers are matched against it. */
    Long filedEventId() {
        Long session = summaries.sessionDbId();
        return session == null ? null : store.filedEvent(session).orElse(null);
    }

    /** A car's class as the Al Kamel feed has it, for the telemetry class guard. */
    private String feedClassOf(String carNumber) {
        JsonNode entry = tree.copyOf("timing.session.entry." + carNumber);
        String cls = entry == null ? null : entry.path("class").asText(null);
        return cls == null || cls.isBlank() ? null : cls;
    }

    private Analysis analysis() {
        if (analysisWriter == null) {
            return new Analysis(false, 0, 0, 0, 0, 0, 0, 0);
        }
        AnalysisRouter r = router;
        AnalysisWriter.Stats w = analysisWriter.stats();
        return r == null
                ? new Analysis(true, 0, 0, 0, 0, w.queued(), w.written(), w.failed())
                : new Analysis(true, r.laps(), r.stints(), r.dropped(), r.withoutSession(),
                        w.queued(), w.written(), w.failed());
    }

    /** Per-car last lap, best lap and open stint of the current session. Empty unless analysis is on. */
    public List<LiveCarSummaries.CarSummary> carSummaries() {
        return summaries.snapshot();
    }

    /** The feed session the summaries belong to, or null. */
    public Long analysisSessionDbId() {
        return summaries.sessionDbId();
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
                s.path("isSessionRunning").asBoolean(false), s.path("isFinished").asBoolean(false),
                info.hasNonNull("eventDbId") ? info.path("eventDbId").asLong() : null);
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
            sweepIfDue();
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
        ensureTelemetry(false);
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
        boundEventId = row.eventId();
        if (!row.desiredConnected()) {
            stopConnection();
            if (leaseHeld) {
                store.release(instanceId);
                leaseHeld = false;
            }
            ensureTelemetry(false);
            // Off means off: a stale running order must not outlive the connection.
            // (While BACKING_OFF the last-known tree is kept on purpose.)
            tree.clear();
            summaries.reset(null);
            state = State.OFF;
            backoffStep = 0;
            attempts = 0;
            drops = 0;
            nextAttemptAt = Instant.MIN;
            return;
        }
        if (!store.acquireOrRenew(instanceId, pacing.lease())) {
            ensureTelemetry(false);
            stopConnection();
            tree.clear();
            summaries.reset(null);
            leaseHeld = false;
            state = State.STANDBY;
            return;
        }
        leaseHeld = true;
        leaseGoodUntil = Instant.now().plus(pacing.lease());
        ensureTelemetry(true);
        fileSession();
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
        summaries.reset(null);
        AnalysisRouter analysis = analysisWriter == null ? null
                : new AnalysisRouter(mapper, tree, analysisWriter, summaries);
        router = analysis;
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
            public AksLineReader.Tee line(long epochMs) {
                messages.incrementAndGet();
                lastMessageAt = Instant.ofEpochMilli(epochMs);
                AksLineReader.Tee recording = recorder != null ? recorder.line(epochMs) : null;
                return new AksLineReader.Tee() {
                    @Override
                    public void write(byte[] line, int offset, int length) {
                        bytes.addAndGet(length);
                        if (recording != null) {
                            recording.write(line, offset, length);
                        }
                    }

                    @Override
                    public void end() {
                        bytes.addAndGet(2); // the CRLF
                        if (recording != null) {
                            recording.end();
                        }
                    }
                };
            }

            @Override
            public void warned(String message) {
                lastWarning = message;
                log.warn("Live timing: {}", message);
            }
        }, analysis);
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

    /** Telemetry never takes the supervisor down with it: it fails soft by design. */
    private void ensureTelemetry(boolean run) {
        if (telemetry == null) {
            return;
        }
        try {
            if (!run) {
                telemetry.ensure(false);
                return;
            }
            // Only a series that sends energy (WeatherTech) is worth the connection — the
            // series on track: its filed event's, else the feed's championship, else the binding's.
            Long filed = analysisWriter == null ? null : filedEventId();
            JsonNode info = tree.copyOf("timing.session.info");
            String champ = info == null ? null : info.path("champName").asText(null);
            boolean covered = filed != null ? seriesCovered(filed)
                    : champ != null && !champ.isBlank() ? telemetryProps.coversSeries(champ, null)
                    : boundEventId != null && seriesCovered(boundEventId);
            telemetry.ensure(covered, covered ? null : "The series on track sends no energy telemetry");
        } catch (RuntimeException e) {
            log.warn("IMSA telemetry: {}", e.toString());
        }
    }

    private boolean seriesCovered(long eventId) {
        return telemetrySeries.computeIfAbsent(eventId, id -> store.seriesOf(id)
                .map(s -> telemetryProps.coversSeries(s.name(), s.abbreviation())).orElse(false));
    }

    /**
     * Files the session on track by its series weekend (LiveFiling): the feed
     * event it belongs to is bound by championship, an earlier binding, or the
     * connection's event — each confirmed by the entry list — and the session
     * follows. An unbound feed event is tried again every 10 s as the running
     * order fills in; a bound one only brings new sessions into line.
     * Supervisor thread; never fatal.
     */
    private void fileSession() {
        if (analysisWriter == null || filing == null) {
            return;
        }
        JsonNode info = tree.copyOf("timing.session.info");
        if (info == null || !info.hasNonNull("eventDbId")) {
            return;
        }
        long feedEvent = info.path("eventDbId").asLong();
        String attempt = feedEvent + ">" + boundEventId;
        if (attempt.equals(lastFilingTry) && Instant.now().isBefore(nextFilingTry)) {
            return;
        }
        lastFilingTry = attempt;
        nextFilingTry = Instant.MIN;
        try {
            if (!filing.fileLive(feedEvent, boundEventId, LiveEventMatch.cars(tree.copyOf("timing.session")))) {
                nextFilingTry = Instant.now().plusSeconds(10);
            }
        } catch (RuntimeException e) {
            log.warn("Live timing: filing feed event {} failed: {}", feedEvent, e.toString());
        }
    }

    /**
     * Once a minute, whether connected or not: recent feed events that are
     * still unbound are tried again from their stored cars — entries are often
     * imported after Wednesday practice, and events or aliases get added later.
     */
    private void sweepIfDue() {
        if (analysisWriter == null || filing == null || Instant.now().isBefore(nextSweep)) {
            return;
        }
        nextSweep = Instant.now().plus(Duration.ofMinutes(1));
        try {
            filing.sweep();
        } catch (RuntimeException e) {
            log.warn("Live timing: filing sweep failed: {}", e.toString());
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
