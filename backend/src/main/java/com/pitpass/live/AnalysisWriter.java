package com.pitpass.live;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pitpass.live.AnalysisRows.EnergyLap;
import com.pitpass.live.AnalysisRows.EntriesChanged;
import com.pitpass.live.AnalysisRows.LapDeleted;
import com.pitpass.live.AnalysisRows.LapPatch;
import com.pitpass.live.AnalysisRows.Op;
import com.pitpass.live.AnalysisRows.Sector;
import com.pitpass.live.AnalysisRows.SessionSeen;
import com.pitpass.live.AnalysisRows.StintDeleted;
import com.pitpass.live.AnalysisRows.StintPatch;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Component;

import java.sql.Types;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Takes the router's patches off the socket thread and upserts them in
 * batches: every second, or every 500 rows, whichever comes first. One
 * virtual thread and one pooled connection at a time — the pool is 5, and the
 * socket thread never waits on it. The queue is bounded; the router drops
 * what does not fit rather than block (a reconnect's snapshot rewrites it).
 *
 * Every write is an idempotent upsert, so a reconnect snapshot simply
 * rewrites the same rows. A batch the database refuses is logged, counted
 * and let go: nothing here may stop the feed.
 *
 * Batches go through NamedParameterJdbcTemplate because JdbcClient has no
 * batch call; the SQL is written the same way.
 */
@Component
public class AnalysisWriter implements AnalysisRouter.Sink {

    /** Re-resolves live_driver for a session from the feed's entry data. */
    interface DriverResolver {
        void resolve(long sessionDbId, JsonNode entries, Long eventId);
    }

    /** Counters for /api/live/status. */
    public record Stats(int queued, long written, long failed) {
    }

    private static final Logger log = LoggerFactory.getLogger(AnalysisWriter.class);
    static final int DEFAULT_CAPACITY = 100_000;

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final BlockingQueue<Op> queue;
    private final int batchRows;
    private final Duration interval;
    private final AtomicLong written = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();

    private volatile DriverResolver resolver;
    private volatile Supplier<JsonNode> entrySource = () -> null;
    private volatile Supplier<Long> filedEventId = () -> null;
    private volatile boolean stopping;
    private Thread thread;

    @Autowired
    public AnalysisWriter(NamedParameterJdbcTemplate jdbc, ObjectMapper mapper) {
        this(jdbc, mapper, DEFAULT_CAPACITY, 500, Duration.ofSeconds(1));
    }

    AnalysisWriter(NamedParameterJdbcTemplate jdbc, ObjectMapper mapper, int capacity, int batchRows,
                   Duration interval) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.queue = new ArrayBlockingQueue<>(capacity);
        this.batchRows = batchRows;
        this.interval = interval;
    }

    /**
     * Where driver resolution reads the feed's entries from, and the event the
     * current session is filed under (not merely bound: see LiveEventMatch).
     */
    void drivers(DriverResolver resolver, Supplier<JsonNode> entrySource, Supplier<Long> filedEventId) {
        this.resolver = resolver;
        this.entrySource = entrySource;
        this.filedEventId = filedEventId;
    }

    @Override
    public boolean offer(Op op) {
        start();
        return queue.offer(op);
    }

    public Stats stats() {
        return new Stats(queue.size(), written.get(), failed.get());
    }

    /** Test hook: true once everything offered so far has been written or given up on. */
    boolean idle() {
        return queue.isEmpty() && !busy;
    }

    private volatile boolean busy;

    private synchronized void start() {
        if (thread == null && !stopping) {
            thread = Thread.ofVirtual().name("live-analysis-writer").start(this::run);
        }
    }

    /** At shutdown, after the connection has closed: write what is queued, briefly. */
    @PreDestroy
    public void stop() {
        Thread t;
        synchronized (this) {
            stopping = true;
            t = thread;
        }
        if (t != null) {
            t.interrupt();
            try {
                t.join(Duration.ofSeconds(10));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void run() {
        List<Op> batch = new ArrayList<>(batchRows);
        while (!stopping || !queue.isEmpty()) {
            try {
                Op first = stopping ? queue.poll() : queue.poll(interval.toMillis(), TimeUnit.MILLISECONDS);
                if (first == null) {
                    continue;
                }
                busy = true;
                batch.add(first);
                queue.drainTo(batch, batchRows - batch.size());
                long deadline = System.nanoTime() + interval.toNanos();
                while (!stopping && batch.size() < batchRows) {
                    long wait = deadline - System.nanoTime();
                    Op next = wait > 0 ? queue.poll(wait, TimeUnit.NANOSECONDS) : null;
                    if (next == null) {
                        break;
                    }
                    batch.add(next);
                    queue.drainTo(batch, batchRows - batch.size());
                }
            } catch (InterruptedException e) {
                // stop() asked: write what we hold and drain the rest without waiting
                stopping = true;
            }
            write(batch);
            batch.clear();
            busy = false;
        }
    }

    /** Runs of the same kind go as one JDBC batch, in arrival order. */
    private void write(List<Op> batch) {
        Long resolveSession = null;
        int i = 0;
        while (i < batch.size()) {
            Class<?> kind = batch.get(i).getClass();
            int j = i;
            while (j < batch.size() && batch.get(j).getClass() == kind) {
                j++;
            }
            List<Op> run = batch.subList(i, j);
            if (kind == EntriesChanged.class) {
                resolveSession = ((EntriesChanged) run.getLast()).sessionDbId();
            } else {
                execute(kind, run);
            }
            i = j;
        }
        if (resolveSession != null && resolver != null) {
            try {
                resolver.resolve(resolveSession, entrySource.get(), filedEventId.get());
            } catch (RuntimeException e) {
                log.warn("Live analysis: resolving drivers failed: {}", e.toString());
            }
        }
    }

    private void execute(Class<?> kind, List<Op> run) {
        try {
            if (kind == SessionSeen.class) {
                batch(SESSION_UPSERT, run.stream().map(op -> session((SessionSeen) op)).toList());
                // Its series weekend, for filing (LiveFiling); a binding already there is kept.
                List<SqlParameterSource> feedEvents = run.stream()
                        .map(op -> ((SessionSeen) op).session().feedEventDbId()).filter(Objects::nonNull).distinct()
                        .map(id -> (SqlParameterSource) new MapSqlParameterSource("f", id)).toList();
                if (!feedEvents.isEmpty()) {
                    batch("INSERT INTO live_feed_event (feed_event_db_id) VALUES (:f) ON CONFLICT DO NOTHING",
                            feedEvents);
                }
            } else if (kind == LapPatch.class) {
                batch(LAP_UPSERT, run.stream().map(op -> lap((LapPatch) op)).toList());
            } else if (kind == StintPatch.class) {
                batch(STINT_UPSERT, run.stream().map(op -> stint((StintPatch) op)).toList());
            } else if (kind == LapDeleted.class) {
                batch("DELETE FROM live_lap WHERE session_db_id = :s AND car_number = :car AND lap_number = :lap",
                        run.stream().map(op -> {
                            LapDeleted d = (LapDeleted) op;
                            return (SqlParameterSource) new MapSqlParameterSource("s", d.sessionDbId())
                                    .addValue("car", d.car()).addValue("lap", d.lap());
                        }).toList());
            } else if (kind == EnergyLap.class) {
                batch("""
                        INSERT INTO live_energy_lap (session_db_id, car_number, lap_number, energy_pct, pit_lane)
                        VALUES (:s, :car, :lap, :energy, :pit)
                        ON CONFLICT (session_db_id, car_number, lap_number) DO UPDATE SET
                            energy_pct = EXCLUDED.energy_pct, pit_lane = EXCLUDED.pit_lane,
                            recorded_at = clock_timestamp()
                        """,
                        run.stream().map(op -> {
                            EnergyLap e = (EnergyLap) op;
                            return (SqlParameterSource) new MapSqlParameterSource("s", e.sessionDbId())
                                    .addValue("car", e.car()).addValue("lap", e.lap())
                                    .addValue("energy", (float) e.energyPct(), Types.REAL)
                                    .addValue("pit", e.pitLane(), Types.BOOLEAN);
                        }).toList());
            } else if (kind == StintDeleted.class) {
                batch("DELETE FROM live_stint WHERE session_db_id = :s AND car_number = :car AND start_time_ms = :start",
                        run.stream().map(op -> {
                            StintDeleted d = (StintDeleted) op;
                            return (SqlParameterSource) new MapSqlParameterSource("s", d.sessionDbId())
                                    .addValue("car", d.car()).addValue("start", d.startTimeMs());
                        }).toList());
            }
            written.addAndGet(run.size());
        } catch (DataAccessException | IllegalStateException e) {
            if (failed.getAndAdd(run.size()) == 0 || log.isDebugEnabled()) {
                log.warn("Live analysis: {} {} write(s) refused: {}", run.size(), kind.getSimpleName(), e.toString());
            }
        }
    }

    private void batch(String sql, List<SqlParameterSource> params) {
        jdbc.batchUpdate(sql, params.toArray(SqlParameterSource[]::new));
    }

    // ---- SQL -------------------------------------------------------------------------

    // event_id is never written here: filing is LiveFiling's, by series weekend.
    private static final String SESSION_UPSERT = """
            INSERT INTO live_session (session_db_id, session_mongo_id, feed_event_db_id, name, type, session_date_ms,
                                      champ_db_id, champ_name, feed_event_name, feed_event_short_name, closed)
            VALUES (:s, :mongo, :feedEvent, :name, :type, :date,
                    :champ, :champName, :feedEventName, :feedEventShortName, :closed)
            ON CONFLICT (session_db_id) DO UPDATE SET
                session_mongo_id = EXCLUDED.session_mongo_id,
                feed_event_db_id = EXCLUDED.feed_event_db_id,
                name = EXCLUDED.name,
                type = EXCLUDED.type,
                session_date_ms = EXCLUDED.session_date_ms,
                champ_db_id = EXCLUDED.champ_db_id,
                champ_name = EXCLUDED.champ_name,
                feed_event_name = EXCLUDED.feed_event_name,
                feed_event_short_name = EXCLUDED.feed_event_short_name,
                closed = EXCLUDED.closed,
                updated_at = clock_timestamp()
            """;

    private static String keep(String column) {
        return column + " = CASE WHEN :has_" + column + " THEN EXCLUDED." + column + " ELSE t." + column + " END";
    }

    private static final List<String> LAP_COLUMNS = List.of("driver_order", "driver_lap_number", "position",
            "start_time_ms", "lap_time_ms", "top_speed", "is_valid", "is_long_lap", "is_short_lap", "track_limits",
            "pit_in_time_ms", "pit_out_time_ms");

    private static final String LAP_UPSERT = """
            INSERT INTO live_lap AS t (session_db_id, car_number, lap_number, %s, sector_ms, sector_flags)
            VALUES (:s, :car, :lap, %s,
                    live_patch_int(NULL, CAST(:sectors AS jsonb), 'ms'),
                    live_patch_text(NULL, CAST(:sectors AS jsonb), 'flag'))
            ON CONFLICT (session_db_id, car_number, lap_number) DO UPDATE SET
                %s,
                sector_ms = live_patch_int(t.sector_ms, CAST(:sectors AS jsonb), 'ms'),
                sector_flags = live_patch_text(t.sector_flags, CAST(:sectors AS jsonb), 'flag')
            """.formatted(String.join(", ", LAP_COLUMNS),
            String.join(", ", LAP_COLUMNS.stream().map(c -> ":" + c).toList()),
            String.join(",\n    ", LAP_COLUMNS.stream().map(AnalysisWriter::keep).toList()));

    private static final List<String> STINT_COLUMNS = List.of("type", "pit_type", "driver_order", "open_lap_number",
            "close_lap_number", "finish_time_ms", "driver_accum_session_track_ms", "driver_accum_session_ms",
            "driver_accum_track_ms", "driver_accum_ms");

    private static final String STINT_UPSERT = """
            INSERT INTO live_stint AS t (session_db_id, car_number, start_time_ms, %s)
            VALUES (:s, :car, :start, %s)
            ON CONFLICT (session_db_id, car_number, start_time_ms) DO UPDATE SET
                %s
            """.formatted(String.join(", ", STINT_COLUMNS),
            String.join(", ", STINT_COLUMNS.stream().map(c -> ":" + c).toList()),
            String.join(",\n    ", STINT_COLUMNS.stream().map(AnalysisWriter::keep).toList()));

    private static SqlParameterSource session(SessionSeen seen) {
        var s = seen.session();
        return new MapSqlParameterSource("s", s.sessionDbId())
                .addValue("mongo", s.mongoId(), Types.VARCHAR)
                .addValue("feedEvent", s.feedEventDbId(), Types.BIGINT)
                .addValue("name", s.name(), Types.VARCHAR)
                .addValue("type", s.type(), Types.VARCHAR)
                .addValue("date", s.dateMs(), Types.BIGINT)
                .addValue("champ", s.champDbId(), Types.BIGINT)
                .addValue("champName", s.champName(), Types.VARCHAR)
                .addValue("feedEventName", s.feedEventName(), Types.VARCHAR)
                .addValue("feedEventShortName", s.feedEventShortName(), Types.VARCHAR)
                .addValue("closed", s.closed());
    }

    private SqlParameterSource lap(LapPatch l) {
        MapSqlParameterSource p = new MapSqlParameterSource("s", l.sessionDbId)
                .addValue("car", l.car).addValue("lap", l.lap);
        field(p, "driver_order", l.has(LapPatch.DRIVER), l.driverOrder, Types.INTEGER);
        field(p, "driver_lap_number", l.has(LapPatch.DRIVER_LAP), l.driverLapNumber, Types.INTEGER);
        field(p, "position", l.has(LapPatch.POSITION), l.position, Types.INTEGER);
        field(p, "start_time_ms", l.has(LapPatch.START), l.startTimeMs, Types.BIGINT);
        field(p, "lap_time_ms", l.has(LapPatch.TIME), l.lapTimeMs, Types.INTEGER);
        field(p, "top_speed", l.has(LapPatch.TOP_SPEED), l.topSpeed == null ? null : l.topSpeed.floatValue(), Types.REAL);
        field(p, "is_valid", l.has(LapPatch.VALID), l.valid, Types.BOOLEAN);
        field(p, "is_long_lap", l.has(LapPatch.LONG_LAP), l.longLap, Types.BOOLEAN);
        field(p, "is_short_lap", l.has(LapPatch.SHORT_LAP), l.shortLap, Types.BOOLEAN);
        field(p, "track_limits", l.has(LapPatch.TRACK_LIMITS), l.trackLimits, Types.INTEGER);
        field(p, "pit_in_time_ms", l.has(LapPatch.PIT_IN), l.pitInMs, Types.BIGINT);
        field(p, "pit_out_time_ms", l.has(LapPatch.PIT_OUT), l.pitOutMs, Types.BIGINT);
        p.addValue("sectors", sectorPatch(l), Types.VARCHAR);
        return p;
    }

    /** The sector part of a diff as the JSON live_patch_int/_text apply; null = untouched. */
    private String sectorPatch(LapPatch l) {
        if (l.sectorsCleared) {
            return "null";
        }
        if (l.sectors == null || l.sectors.isEmpty()) {
            return null;
        }
        ObjectNode patch = mapper.createObjectNode();
        for (Map.Entry<Integer, Sector> e : l.sectors.entrySet()) {
            Sector s = e.getValue();
            if (s == null) {
                patch.putNull(e.getKey().toString());
                continue;
            }
            ObjectNode one = patch.putObject(e.getKey().toString());
            if (s.hasTime) {
                one.put("ms", s.timeMs);
            }
            if (s.hasFlag) {
                one.put("flag", s.flag);
            }
        }
        return patch.toString();
    }

    private static SqlParameterSource stint(StintPatch s) {
        MapSqlParameterSource p = new MapSqlParameterSource("s", s.sessionDbId)
                .addValue("car", s.car).addValue("start", s.startTimeMs);
        field(p, "type", s.has(StintPatch.TYPE), s.type, Types.VARCHAR);
        field(p, "pit_type", s.has(StintPatch.PIT_TYPE), s.pitType, Types.VARCHAR);
        field(p, "driver_order", s.has(StintPatch.DRIVER), s.driverOrder, Types.INTEGER);
        field(p, "open_lap_number", s.has(StintPatch.OPEN_LAP), s.openLap, Types.INTEGER);
        field(p, "close_lap_number", s.has(StintPatch.CLOSE_LAP), s.closeLap, Types.INTEGER);
        field(p, "finish_time_ms", s.has(StintPatch.FINISH), s.finishTimeMs, Types.BIGINT);
        field(p, "driver_accum_session_track_ms", s.has(StintPatch.ACCUM_SESSION_TRACK), s.accumSessionTrackMs, Types.BIGINT);
        field(p, "driver_accum_session_ms", s.has(StintPatch.ACCUM_SESSION), s.accumSessionMs, Types.BIGINT);
        field(p, "driver_accum_track_ms", s.has(StintPatch.ACCUM_TRACK), s.accumTrackMs, Types.BIGINT);
        field(p, "driver_accum_ms", s.has(StintPatch.ACCUM), s.accumMs, Types.BIGINT);
        return p;
    }

    private static void field(MapSqlParameterSource p, String column, boolean present, Object value, int type) {
        p.addValue(column, present ? value : null, type);
        p.addValue("has_" + column, present, Types.BOOLEAN);
    }
}
