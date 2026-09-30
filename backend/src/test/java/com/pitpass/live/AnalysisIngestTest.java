package com.pitpass.live;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pitpass.live.AksReplayServer.Recorded;
import com.pitpass.live.LiveTimingService.Pacing;
import com.pitpass.live.LiveTimingService.State;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static com.pitpass.live.AnalysisFixtures.RACE_START;
import static com.pitpass.live.AnalysisFixtures.info;
import static com.pitpass.live.AnalysisFixtures.lap;
import static com.pitpass.live.AnalysisFixtures.stint;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * timing.analysis end to end: the replay server over a real socket, the
 * production client streaming each frame through the router, the writer
 * batching into the real schema — and the recording still byte-identical to
 * what was sent. Not @Transactional: the writer commits on its own thread,
 * so the test's session is deleted afterwards (the rows cascade).
 */
@SpringBootTest
class AnalysisIngestTest {

    @Autowired NamedParameterJdbcTemplate jdbc;
    @Autowired JdbcClient db;
    @Autowired ObjectMapper mapper;
    @Autowired LiveDriverResolver resolver;

    @TempDir
    Path recordings;

    private final long session = 900_000_000L + ThreadLocalRandom.current().nextInt(1_000_000);
    private final List<AutoCloseable> cleanup = new ArrayList<>();
    private final BlockingQueue<Path> segments = new LinkedBlockingQueue<>();

    @AfterEach
    void tearDown() throws Exception {
        for (AutoCloseable c : cleanup.reversed()) {
            c.close();
        }
        db.sql("DELETE FROM live_session WHERE session_db_id = :s").param("s", session).update();
        db.sql("DELETE FROM live_feed_event WHERE feed_event_db_id = 812").update();
    }

    private List<Recorded> feed() {
        long stintStart = RACE_START + 60_000;
        List<String> lines = List.of(
                "JSON:1::" + info(session, "Practice 1"),
                "JSON:2::{\"timing\":{\"session\":{\"entry\":{\"04\":{\"number\":\"04\",\"class\":\"GTD\",\"drivers\":{"
                        + "\"1\":{\"number\":1,\"firstName\":\"Ann\",\"lastName\":\"Driver\",\"shortName\":\"Dri\",\"license\":\"Silver\"}}}}}}}",
                // the JOIN snapshot: two cars that differ only by a leading zero
                "JSON:3::{\"timing\":{\"analysis\":{\"laps\":{"
                        + "\"04\":{\"laps\":{\"1\":" + lap(1, 1, 99_000, 20) + ",\"2\":" + lap(2, 1, 98_000, 20)
                        + ",\"3\":" + lap(3, 1, 97_500, 20) + "}},"
                        + "\"4\":{\"laps\":{\"1\":" + lap(1, 1, 101_000, 20) + "}}}}}}",
                "JSON:4::{\"timing\":{\"analysis\":{\"stints\":{\"04\":{\"stints\":{\"" + stintStart + "\":"
                        + stint(stintStart, "TRACK", 1, 1, null, null, 290_000) + "}}}}}}",
                "JSON:5::{\"broken\":",
                // diffs: lap 3 re-timed with one sector's flag changed, lap 2 deleted, lap 4 begun
                "JSON:6::{\"timing\":{\"analysis\":{\"laps\":{\"04\":{\"laps\":{"
                        + "\"3\":{\"time\":97400,\"sectors\":{\"2\":{\"flag\":\"RED\"}}},\"2\":null,"
                        + "\"4\":{\"driver\":1,\"startTime\":" + (RACE_START + 400_000) + "}}}}}}}",
                "JSON:7::{\"timing\":{\"session\":{\"status\":{\"currentFlag\":\"GREEN\"}}}}");
        List<Recorded> out = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            out.add(new Recorded(1_000 + i, lines.get(i)));
        }
        return out;
    }

    @Test
    void streamsLapsAndStintsIntoPostgresAndRecordsEveryByte() throws Exception {
        List<Recorded> feed = feed();
        AksReplayServer server = new AksReplayServer(feed, 0, 20).start();
        cleanup.add(server);
        AnalysisWriter writer = new AnalysisWriter(jdbc, mapper, 1_000, 500, Duration.ofMillis(50));
        cleanup.add(writer::stop);
        AlKamelV2Properties props = new AlKamelV2Properties("127.0.0.1", server.port(), "feed-user", "pw",
                false, false, "Pit Pass test",
                List.of("timing.session.info", "timing.session.entry", "timing.session.status"),
                1 << 10, 2, 1,
                new AlKamelV2Properties.Recording(true, recordings.toString(), "", 10, 64),
                new AlKamelV2Properties.Replay("", 1.0),
                new AlKamelV2Properties.Analysis(true, 1 << 20));
        LiveTimingService service = new LiveTimingService(props, new LiveTimingServiceTest.MemoryStore(), mapper,
                null, (segment, key) -> segments.add(segment),
                new Pacing(Duration.ofMillis(20), Duration.ofSeconds(2), List.of(Duration.ofMillis(50)),
                        Duration.ofSeconds(5), Duration.ofSeconds(10)),
                writer, resolver, null, null);
        service.start();
        cleanup.add(service::stop);

        service.request(true, null, "t");
        await(() -> "GREEN".equals(text(service, "timing.session.status", "currentFlag")));
        await(() -> count("live_lap WHERE session_db_id = :s AND car_number = '04' AND lap_number = 4") == 1
                && writer.stats().queued() == 0);
        Thread.sleep(150); // the last batch's commit

        var status = service.status();
        assertEquals(State.LIVE, status.state());
        assertTrue(status.channels().contains("timing.analysis.laps"), "analysis channels are joined when on");
        assertTrue(status.lastWarning().contains("Unreadable JSON frame"), status.lastWarning());
        assertEquals(0, status.analysis().dropped());
        assertEquals(0, status.analysis().failed());
        assertNull(service.state("timing.analysis"), "never in the tree");

        assertEquals("Practice 1", db.sql("SELECT name FROM live_session WHERE session_db_id = :s")
                .param("s", session).query(String.class).single());
        assertEquals("Fixture Championship (not a series)|38|Showcase 120|Road America|812", db.sql("""
                SELECT concat_ws('|', champ_name, champ_db_id, feed_event_name, feed_event_short_name, feed_event_db_id)
                FROM live_session WHERE session_db_id = :s
                """).param("s", session).query(String.class).single(), "the feed's own labels are kept");
        await(() -> count("live_car WHERE session_db_id = :s AND car_number = '04' AND feed_class = 'GTD'") == 1);
        assertEquals(1, count("live_feed_event WHERE feed_event_db_id = 812"), "its series weekend, for filing");
        assertEquals(List.of("04:1", "04:3", "04:4", "4:1"), db.sql("""
                SELECT car_number || ':' || lap_number FROM live_lap WHERE session_db_id = :s
                ORDER BY car_number, lap_number
                """).param("s", session).query(String.class).list(), "#04 and #4 stay apart; lap 2 deleted");

        Map<String, Object> lap3 = db.sql("""
                SELECT lap_time_ms, driver_order, is_valid, array_to_string(sector_ms, ',', '-') AS ms,
                       array_to_string(sector_flags, ',', '-') AS flags
                FROM live_lap WHERE session_db_id = :s AND car_number = '04' AND lap_number = 3
                """).param("s", session).query().singleRow();
        assertEquals(97_400, lap3.get("lap_time_ms"), "re-timed by the diff");
        assertEquals(1, lap3.get("driver_order"), "untouched by the diff, still from the snapshot");
        assertEquals(true, lap3.get("is_valid"));
        assertEquals("32500,32500,32500", lap3.get("ms"), "the diff named only a flag");
        assertEquals("GREEN,RED,YELLOW", lap3.get("flags"));

        Map<String, Object> lap4 = db.sql("""
                SELECT lap_time_ms, start_time_ms, sector_ms FROM live_lap
                WHERE session_db_id = :s AND car_number = '04' AND lap_number = 4
                """).param("s", session).query().singleRow();
        assertNull(lap4.get("lap_time_ms"), "a lap in progress");
        assertEquals(RACE_START + 400_000, lap4.get("start_time_ms"));

        assertEquals(290_000L, db.sql("""
                SELECT driver_accum_session_track_ms FROM live_stint WHERE session_db_id = :s AND car_number = '04'
                """).param("s", session).query(Long.class).single());

        assertEquals("Driver|Silver|S", db.sql("""
                SELECT last_name || '|' || license || '|' || rating FROM live_driver
                WHERE session_db_id = :s AND car_number = '04' AND driver_order = 1 AND driver_id IS NULL
                """).param("s", session).query(String.class).single(), "no event bound: listed, unmatched");

        var car = service.carSummaries().stream().filter(c -> c.car().equals("04")).findFirst().orElseThrow();
        assertEquals(3, car.lastLap());
        assertEquals(97_400, car.lastLapMs());

        // The recording is exactly what was sent, the unparseable line included.
        service.request(false, null, "t");
        Path segment = segments.poll(5, TimeUnit.SECONDS);
        assertNotNull(segment);
        assertEquals(feed.stream().map(Recorded::line).toList(),
                AksReplayServer.load(segment).stream().map(Recorded::line).toList());
    }

    @Test
    void aReconnectSnapshotRewritesTheSameRows() throws Exception {
        List<Recorded> feed = feed();
        AksReplayServer server = new AksReplayServer(feed, 0, 20).start();
        cleanup.add(server);
        AnalysisWriter writer = new AnalysisWriter(jdbc, mapper, 1_000, 500, Duration.ofMillis(50));
        cleanup.add(writer::stop);
        AlKamelV2Properties props = new AlKamelV2Properties("127.0.0.1", server.port(), "u", "p", false, false,
                "Pit Pass test", List.of("timing.session.info"), 1 << 10, 2, 1,
                new AlKamelV2Properties.Recording(false, recordings.toString(), "", 10, 64),
                new AlKamelV2Properties.Replay("", 1.0), new AlKamelV2Properties.Analysis(true, 1 << 20));
        LiveTimingService service = new LiveTimingService(props, new LiveTimingServiceTest.MemoryStore(), mapper,
                null, (segment, key) -> { },
                new Pacing(Duration.ofMillis(20), Duration.ofSeconds(2), List.of(Duration.ofMillis(50)),
                        Duration.ofSeconds(5), Duration.ofSeconds(10)),
                writer, null, null, null);
        service.start();
        cleanup.add(service::stop);

        service.request(true, null, "t");
        await(() -> count("live_lap WHERE session_db_id = :s AND car_number = '04' AND lap_number = 4") == 1);
        server.dropClient();
        await(() -> server.logins() == 2);
        await(() -> service.status().analysis().laps() >= 6 && writer.stats().queued() == 0);
        Thread.sleep(150);

        assertEquals(4, count("live_lap WHERE session_db_id = :s"), "the same rows, not twice as many");
        assertEquals(0, writer.stats().failed());
    }

    @Test
    void energyLapsUpsert() throws Exception {
        AnalysisWriter writer = new AnalysisWriter(jdbc, mapper, 100, 500, Duration.ofMillis(20));
        cleanup.add(writer::stop);
        writer.offer(new AnalysisRows.SessionSeen(new AnalysisRows.SessionInfo(session, null, null, "Race", "RACE", null,
                null, null, null, null, false)));
        writer.offer(new AnalysisRows.EnergyLap(session, "04", 7, 81.5, false));
        writer.offer(new AnalysisRows.EnergyLap(session, "04", 7, 80.5, true)); // the same lap again: the later reading wins
        await(() -> count("live_energy_lap WHERE session_db_id = :s AND energy_pct < 81") == 1);
        assertEquals(0, writer.stats().failed());
    }

    /**
     * The weekend case: two series on one feed, sharing car numbers. The
     * session is filed only under the event whose entries match the cars on
     * track by number and class; binding the other series files nothing and
     * moves nothing; drivers follow the filed event; and IMSA energy runs
     * only while the bound event's series sends it.
     */
    @Test
    void aSessionIsFiledUnderTheEventItsCarsMatchAndEnergyFollowsTheSeries() throws Exception {
        String u = java.util.UUID.randomUUID().toString().substring(0, 8);
        String wtName = "Filing WT " + u;
        long wtSeries = id("INSERT INTO series (name) VALUES (:n) RETURNING id", wtName);
        long pcSeries = id("INSERT INTO series (name) VALUES (:n) RETURNING id", "Filing PC " + u);
        long wtEvent = event(wtSeries, "WT " + u);
        long pcEvent = event(pcSeries, "PC " + u);
        long wt04 = db.sql("INSERT INTO entry (event_id, car_number, class_name, team_name) VALUES (:e, '04', 'GTD', 'WT Team') RETURNING id")
                .param("e", wtEvent).query(Long.class).single();
        db.sql("INSERT INTO entry (event_id, car_number, class_name, team_name) VALUES (:e, '04', 'TCR', 'PC Team')")
                .param("e", pcEvent).update();
        long ann = db.sql("INSERT INTO driver (first_name, surname) VALUES (:f, 'Driver') RETURNING id")
                .param("f", "Ann" + u).query(Long.class).single();
        db.sql("INSERT INTO driver_assignment (entry_id, driver_id, seat_order, rating) VALUES (:e, :d, 1, 'S')")
                .param("e", wt04).param("d", ann).update();
        cleanup.add(() -> {
            db.sql("DELETE FROM live_session WHERE session_db_id = :s").param("s", session).update();
            db.sql("DELETE FROM live_feed_event WHERE feed_event_db_id = 812").update();
            db.sql("DELETE FROM driver_assignment WHERE driver_id = :d").param("d", ann).update();
            db.sql("DELETE FROM event WHERE id IN (:a, :b)").param("a", wtEvent).param("b", pcEvent).update();
            db.sql("DELETE FROM season WHERE series_id IN (:a, :b)").param("a", wtSeries).param("b", pcSeries).update();
            db.sql("DELETE FROM series WHERE id IN (:a, :b)").param("a", wtSeries).param("b", pcSeries).update();
            db.sql("DELETE FROM driver WHERE id = :d").param("d", ann).update();
        });

        // IMSA telemetry for #04 in GTD, lap 1 then lap 2: one lap-crossing sample.
        Path telemetry = recordings.resolve("t.imsa");
        java.nio.file.Files.writeString(telemetry,
                "1\t" + TelemetryFixtures.data("x", TelemetryFixtures.cars(TelemetryFixtures.car("04", 80, 1, false, "GTD"))) + "\n"
                + "2\t" + TelemetryFixtures.data("x", TelemetryFixtures.cars(TelemetryFixtures.car("04", 77, 2, false, "GTD"))) + "\n");

        AksReplayServer server = new AksReplayServer(feed(), 0, 20).start();
        cleanup.add(server);
        AnalysisWriter writer = new AnalysisWriter(jdbc, mapper, 1_000, 500, Duration.ofMillis(50));
        cleanup.add(writer::stop);
        LiveTimingStore real = new LiveTimingStore(db);
        LiveTimingServiceTest.MemoryStore store = new LiveTimingServiceTest.MemoryStore() {
            @Override
            public java.util.Optional<Series> seriesOf(long eventId) {
                return real.seriesOf(eventId);
            }

            @Override
            public java.util.Optional<Long> filedEvent(long sessionDbId) {
                return real.filedEvent(sessionDbId);
            }
        };
        AlKamelV2Properties props = new AlKamelV2Properties("127.0.0.1", server.port(), "u", "p", false, false,
                "Pit Pass test", List.of("timing.session.info", "timing.session.entry"), 1 << 10, 2, 1,
                new AlKamelV2Properties.Recording(false, recordings.toString(), "", 10, 64),
                new AlKamelV2Properties.Replay("", 1.0), new AlKamelV2Properties.Analysis(true, 1 << 20));
        ImsaTelemetryProperties telemetryProps = new ImsaTelemetryProperties(false, "https://example.invalid/",
                List.of(wtName), List.of("telemetry/message"), 15, false, telemetry.toString(), 0);
        LiveTimingService service = new LiveTimingService(props, store, mapper, null, (segment, key) -> { },
                new Pacing(Duration.ofMillis(20), Duration.ofSeconds(2), List.of(Duration.ofMillis(50)),
                        Duration.ofSeconds(5), Duration.ofSeconds(10)),
                writer, resolver, telemetryProps, new LiveFiling(db, new LiveEntryMatcher(db), resolver));
        service.start();
        cleanup.add(service::stop);

        // Bound to the Pilot Challenge event while WeatherTech's cars run: recorded, filed nowhere, no energy.
        service.request(true, pcEvent, "t");
        await(() -> count("live_lap WHERE session_db_id = :s") >= 4 && writer.stats().queued() == 0);
        Thread.sleep(200);
        assertNull(filedUnder(), "PC's #04 is TCR; the car on track is GTD");
        assertEquals(pcEvent, service.status().eventId(), "still bound to Pilot Challenge");
        assertNull(service.status().filedEventId(), "but Pit Pass rows come only from the filed event: none");
        assertEquals(TelemetryRunner.State.OFF, service.status().telemetry().state());
        assertEquals("The series on track sends no energy telemetry", service.status().telemetry().idleReason());

        // Rebind to the WeatherTech event: it matches, so the session is filed there at once,
        // its drivers are matched to that crew, and energy starts.
        service.request(true, wtEvent, "t");
        await(() -> Long.valueOf(wtEvent).equals(filedUnder()));
        assertEquals(wtEvent, service.status().filedEventId());
        await(() -> Long.valueOf(ann).equals(db.sql("""
                SELECT driver_id FROM live_driver WHERE session_db_id = :s AND car_number = '04' AND driver_order = 1
                """).param("s", session).query((rs, i) -> rs.getObject("driver_id", Long.class)).optional().orElse(null)));
        await(() -> count("live_energy_lap WHERE session_db_id = :s AND car_number = '04' AND lap_number = 1") == 1);

        // Binding the other series again changes nothing about what this session is —
        // and energy follows the series on track, not the binding.
        service.request(true, pcEvent, "t");
        Thread.sleep(300);
        assertEquals(wtEvent, filedUnder());
        assertNotEquals(TelemetryRunner.State.OFF, service.status().telemetry().state());
        assertEquals(wtEvent, service.status().filedEventId(), "the binding moved; what the session is did not");
    }

    private Long filedUnder() {
        return db.sql("SELECT event_id FROM live_session WHERE session_db_id = :s").param("s", session)
                .query((rs, i) -> rs.getObject("event_id", Long.class)).optional().orElse(null);
    }

    private long id(String sql, String name) {
        return db.sql(sql).param("n", name).query(Long.class).single();
    }

    private long event(long seriesId, String name) {
        long season = db.sql("INSERT INTO season (series_id, year) VALUES (:s, 2099) RETURNING id")
                .param("s", seriesId).query(Long.class).single();
        return db.sql("INSERT INTO event (season_id, name, round_ordinal) VALUES (:s, :n, 1) RETURNING id")
                .param("s", season).param("n", name).query(Long.class).single();
    }

    private long count(String fromWhere) {
        return db.sql("SELECT count(*) FROM " + fromWhere).param("s", session).query(Long.class).single();
    }

    private static String text(LiveTimingService service, String path, String field) {
        var node = service.state(path);
        return node == null ? null : node.path(field).asText(null);
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        Instant deadline = Instant.now().plusSeconds(10);
        while (!condition.getAsBoolean()) {
            assertTrue(Instant.now().isBefore(deadline), "timed out waiting");
            Thread.sleep(20);
        }
    }
}
