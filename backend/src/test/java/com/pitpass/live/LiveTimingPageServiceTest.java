package com.pitpass.live;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The timing page's reads over the real schema. The feed side is a
 * LiveTimingService subclass holding a fixed tree and fixed summaries, as in
 * LiveClassificationServiceTest; the analysis rows are seeded directly.
 */
@SpringBootTest
@Transactional
class LiveTimingPageServiceTest {

    private static final long H = 3_600_000L;
    private static final long T0 = 1_769_000_000_000L;

    @Autowired JdbcClient db;
    @Autowired LiveTimingStore store;
    @Autowired AlKamelV2Properties props;
    @Autowired ObjectMapper mapper;

    private long event;
    private long session;
    private long zero4;

    private static final String FEED = """
            {"entry": {"04": {"number": "04", "currentDriver": 2, "drivers": {
                          "1": {"firstName": "Ann", "lastName": "One", "shortName": "One", "license": "Bronze"},
                          "2": {"firstName": "Bea", "lastName": "Two", "shortName": "Two", "license": "Gold"}}},
                       "4": {"number": "4", "currentDriver": 1, "drivers": {
                          "1": {"firstName": "Cy", "lastName": "Unresolved", "shortName": "Unr", "license": "Silver"}}}},
             "standings": {"byClass": {"active": {"GTD": {"class": "GTD", "standings": {
                "1": {"participant": "04", "position": 1, "lapNumber": 3},
                "2": {"participant": "4", "position": 2, "lapNumber": 3, "gapFirstTime": 4200, "gapPreviousTime": 4200}}}}}}}
            """;

    @BeforeEach
    void seed() {
        String u = UUID.randomUUID().toString();
        long series = db.sql("INSERT INTO series (name) VALUES (:n) RETURNING id").param("n", "Page " + u).query(Long.class).single();
        long season = db.sql("INSERT INTO season (series_id, year) VALUES (:s, 2099) RETURNING id").param("s", series).query(Long.class).single();
        event = db.sql("INSERT INTO event (season_id, name, round_ordinal) VALUES (:s, :n, 1) RETURNING id")
                .param("s", season).param("n", "Page " + u).query(Long.class).single();
        zero4 = db.sql("INSERT INTO entry (event_id, car_number, class_name, team_name) VALUES (:e, '04', 'GTD', 'Zero Four') RETURNING id")
                .param("e", event).query(Long.class).single();
        db.sql("INSERT INTO entry (event_id, car_number, class_name, team_name) VALUES (:e, '4', 'GTD', 'Four')")
                .param("e", event).update();
        session = 700_000_000L + (System.nanoTime() % 1_000_000);
        db.sql("INSERT INTO live_session (session_db_id, event_id, name, type, session_date_ms) VALUES (:s, :e, 'Race', 'RACE', :d)")
                .param("s", session).param("e", event).param("d", T0).update();
        for (int lap = 1; lap <= 3; lap++) {
            db.sql("""
                    INSERT INTO live_lap (session_db_id, car_number, lap_number, driver_order, start_time_ms, lap_time_ms,
                                          is_valid, sector_ms, sector_flags)
                    VALUES (:s, '04', :lap, 1, :start, :time, :valid, '{30000,31000,32000}', '{GREEN,GREEN,YELLOW}')
                    """)
                    .param("s", session).param("lap", lap).param("start", T0 + (lap - 1) * 100_000L)
                    .param("time", 100_000 - lap).param("valid", lap != 3).update();
        }
        db.sql("""
                INSERT INTO live_stint (session_db_id, car_number, start_time_ms, type, driver_order, open_lap_number,
                                        close_lap_number, finish_time_ms, driver_accum_session_track_ms)
                VALUES (:s, '04', :t0, 'TRACK', 1, 1, 2, :t1, :acc),
                       (:s, '04', :t1, 'TRACK', 2, 3, NULL, NULL, NULL)
                """)
                .param("s", session).param("t0", T0).param("t1", T0 + 2 * H).param("acc", 2 * H).update();
        db.sql("""
                INSERT INTO live_driver (session_db_id, car_number, driver_order, first_name, last_name, short_name,
                                         license, entry_id, rating)
                VALUES (:s, '04', 1, 'Ann', 'One', 'One', 'Bronze', :e, 'B'),
                       (:s, '04', 2, 'Bea', 'Two', 'Two', 'Gold', :e, 'G')
                """)
                .param("s", session).param("e", zero4).update();
        db.sql("""
                INSERT INTO drive_time_rule (event_id, class_name, rating, min_ms, max_ms)
                VALUES (:e, 'GTD', NULL, NULL, :max), (:e, 'GTD', 'B', :min, :max)
                """)
                .param("e", event).param("min", 3 * H).param("max", 4 * H).update();
        store.request(true, event, "admin@example.test");
    }

    private LiveTimingPageService page(boolean current) throws Exception {
        JsonNode tree = mapper.readTree(FEED);
        LiveCarSummaries summaries = new LiveCarSummaries();
        summaries.reset(current ? session : null);
        AnalysisRows.LapPatch lap = new AnalysisRows.LapPatch(session, "04", 3);
        lap.present = AnalysisRows.LapPatch.TIME;
        lap.lapTimeMs = 99_000;
        summaries.lap(lap); // a best the DB knows was invalid
        AnalysisRows.LapPatch invalid = new AnalysisRows.LapPatch(session, "04", 3);
        invalid.present = AnalysisRows.LapPatch.VALID;
        invalid.valid = false;
        summaries.lap(invalid);
        LiveTimingService live = new LiveTimingService(props, store, mapper, null, null, LiveTimingService.Pacing.PRODUCTION) {
            @Override
            public JsonNode state(String path) {
                return "timing.session".equals(path) ? tree : "timing.session.entry".equals(path) ? tree.get("entry") : null;
            }

            @Override
            public List<LiveCarSummaries.CarSummary> carSummaries() {
                return summaries.snapshot();
            }

            @Override
            public Long analysisSessionDbId() {
                return summaries.sessionDbId();
            }

            @Override
            public LiveTelemetry.CarEnergy energy(String carNumber, Integer stintOpenLap) {
                return "04".equals(carNumber) ? new LiveTelemetry.CarEnergy(42.0, 3.5, 12.0) : null;
            }
        };
        LiveEntryMatcher matcher = new LiveEntryMatcher(db);
        return new LiveTimingPageService(db, live, new LiveClassificationService(db, live, matcher));
    }

    @Test
    void theTowerJoinsTheRunningOrderToDriversLapsAndStints() throws Exception {
        var tower = page(true).tower();
        assertEquals(session, tower.sessionDbId());
        assertEquals(T0 + 2 * H, tower.feedClockMs(), "the newest feed time: the second stint's start, after lap 3's end");
        var cars = tower.classes().getFirst().cars();
        var first = cars.get(0);
        assertEquals("04", first.carNumber());
        assertEquals(zero4, first.entryId(), "#04 is the entry 04, not 4");
        assertEquals(2, first.driverOrder());
        assertEquals("Bea Two", first.driverName());
        assertEquals("G", first.driverRating(), "our rating, from live_driver");
        assertEquals(3, first.lastLap());
        assertEquals(2, first.bestLap(), "lap 3 was invalidated after the fact, so the best comes from live_lap");
        assertEquals(99_998, first.bestLapMs());
        assertEquals(42.0, first.energyPct(), "IMSA telemetry, matched to the Al Kamel car");
        assertEquals(12.0, first.energyLapsLeft());
        assertNull(cars.get(1).energyPct(), "no telemetry for #4");

        var second = cars.get(1);
        assertEquals(4_200L, second.intervalMs(), "the feed's gapPreviousTime");
        assertEquals("Cy Unresolved", second.driverName(), "no live_driver row: the feed's own name");
        assertEquals("S", second.driverRating());
    }

    @Test
    void aCarsLapsStintsAndDrivers() throws Exception {
        var car = page(false).car("04", session);
        assertEquals(3, car.laps().size());
        assertEquals(List.of(30_000, 31_000, 32_000), car.laps().getFirst().sectorMs());
        assertEquals("YELLOW", car.laps().getFirst().sectorFlags().get(2));
        assertEquals(Boolean.FALSE, car.laps().get(2).valid());
        assertEquals(2, car.stints().size());
        assertEquals(2, car.drivers().size());

        var service = page(false);
        assertEquals(404, assertThrows(ResponseStatusException.class, () -> service.car("04", -5L)).getStatusCode().value());
        assertEquals(404, assertThrows(ResponseStatusException.class, () -> service.car("99", session)).getStatusCode().value());
        assertEquals(3, service.car("04", null).laps().size(), "no session asked for: the bound event's latest");
    }

    @Test
    void driveTimeCountsAnOldSessionsOpenStintOnlyToItsLastFeedTime() throws Exception {
        // Bea's stint opened at T0+2h; the feed's newest time is the end of her lap 4, T0+2h+160s.
        db.sql("""
                INSERT INTO live_lap (session_db_id, car_number, lap_number, driver_order, start_time_ms, lap_time_ms)
                VALUES (:s, '04', 4, 2, :start, 100000)
                """).param("s", session).param("start", T0 + 2 * H + 60_000).update();

        var response = page(true).driveTime(session);
        assertEquals(event, response.eventId());
        assertEquals(2, response.rules().size());
        var ann = response.drivers().get(0);
        assertEquals(DriveTime.Status.UNDER_MIN, ann.status(), "bronze: 2h of a 3h minimum");
        assertEquals(H, ann.owedMs());
        assertFalse(ann.inCar());
        var bea = response.drivers().get(1);
        assertTrue(bea.inCar());
        assertEquals(160_000, bea.driveMs(), "a finished replay's clock stops at its last feed time, not the wall clock");
        assertEquals(DriveTime.Status.OK, bea.status());
        assertEquals(4 * H - 160_000, bea.remainingMs());
    }

    @Test
    void aCarsLapsCarryEnergyUsedAndItsStintsTheAverage() throws Exception {
        // Telemetry numbered the car "4"… no: exactly "04". Laps 1-3 at the line: 97, 93.5, 90.
        db.sql("""
                INSERT INTO live_energy_lap (session_db_id, car_number, lap_number, energy_pct)
                VALUES (:s, '04', 1, 97), (:s, '04', 2, 93.5), (:s, '04', 3, 90), (:s, '4', 2, 10)
                """).param("s", session).update();
        var car = page(false).car("04", session);
        assertEquals(97f, car.laps().get(0).energyPct());
        assertNull(car.laps().get(0).energyUsedPct(), "lap 1 has no reading before it");
        assertEquals(3.5f, car.laps().get(1).energyUsedPct());
        assertEquals(3.5f, car.laps().get(2).energyUsedPct());
        assertEquals(3.5f, car.stints().get(0).avgEnergyPerLapPct(), "laps 2 of stint 1-2");
        assertEquals(3.5f, car.stints().get(1).avgEnergyPerLapPct(), "the open stint runs to the newest lap");
    }

    @Test
    void sessionsOfAnEventNewestFirst() throws Exception {
        var sessions = page(true).sessions(event);
        assertEquals(1, sessions.size());
        assertEquals(3, sessions.getFirst().laps());
        assertEquals(1, sessions.getFirst().cars());
        assertTrue(sessions.getFirst().current());
        assertFalse(page(false).sessions(event).getFirst().current());
    }
}
