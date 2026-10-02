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
             "standings": {"overall": {
                "active": {"1": {"participant": "4", "position": 1, "lapNumber": 4},
                           "2": {"participant": "04", "position": 2, "lapNumber": 3, "gapFirstLaps": -1, "gapPreviousLaps": -1}},
                "participantDetails": {
                "04": {"currentSector": 2, "status": "TRACK",
                       "lastSectors": {"1": {"number": 1, "time": 30500, "isValid": true}},
                       "bestSectors": {"1": {"number": 1, "time": 30000}, "2": {"number": 2, "time": 31000}}},
                "4": {"currentSector": 1, "status": "BOX", "pitStops": 3, "hasSeenCheckered": true,
                      "bestSectors": {"1": {"number": 1, "time": 29000}, "2": {"number": 2, "time": 33000}}}}},
                           "byClass": {"active": {"GTD": {"class": "GTD", "standings": {
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
                                          is_valid, sector_ms, sector_flags, top_speed)
                    VALUES (:s, '04', :lap, 1, :start, :time, :valid, '{30000,31000,32000}', '{GREEN,GREEN,YELLOW}', :speed)
                    """)
                    .param("speed", 250f + lap)
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
        return pages(current).page();
    }

    private LiveAnalysisService analysis(boolean current) throws Exception {
        return pages(current).analysis();
    }

    private record Pages(LiveTimingPageService page, LiveAnalysisService analysis) {
    }

    private Pages pages(boolean current) throws Exception {
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
                return "timing.session".equals(path) ? tree : "timing.session.entry".equals(path) ? tree.get("entry")
                        : AlKamelV2Properties.PARTICIPANT_DETAILS_CHANNEL.equals(path) ? tree.at("/standings/overall/participantDetails")
                        : AlKamelV2Properties.OVERALL_STANDINGS_CHANNEL.equals(path) ? tree.at("/standings/overall/active")
                        : null;
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
            public LiveTelemetry.CarEnergy energy(String carNumber, String feedClass, Integer stintOpenLap) {
                return "04".equals(carNumber) ? new LiveTelemetry.CarEnergy(42.0, 3.5, 12.0) : null;
            }
        };
        LiveEntryMatcher matcher = new LiveEntryMatcher(db);
        LiveClassificationService classification = new LiveClassificationService(db, live, matcher);
        LiveTimingPageService page = new LiveTimingPageService(db, live, classification);
        return new Pages(page, new LiveAnalysisService(db, live, classification, page));
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
        assertEquals(3, first.laps(), "laps completed, from analysis");
        assertEquals(2, first.bestLap(), "lap 3 was invalidated after the fact, so the best comes from live_lap");
        assertEquals(99_998, first.bestLapMs());
        assertEquals(42.0, first.energyPct(), "IMSA telemetry, matched to the Al Kamel car");
        assertEquals(12.0, first.energyLapsLeft());
        assertNull(cars.get(1).energyPct(), "no telemetry for #4");

        var second = cars.get(1);
        assertEquals(4_200L, second.intervalMs(), "the feed's gapPreviousTime");
        assertEquals("Cy Unresolved", second.driverName(), "no live_driver row: the feed's own name");
        assertEquals("S", second.driverRating());
        assertNull(second.laps(), "no laps recorded and not known to be a race: the standings' lapNumber may be its best lap's");

        assertEquals(2, first.currentSector());
        assertEquals(30_500, first.sectors().get(0).ms());
        assertTrue(first.sectors().get(0).currentLap());
        assertNull(first.sectors().get(1));
        assertEquals(61_000L, first.idealMs());
        assertTrue(second.inPit(), "BOX marks the car in the pit with no PIT stint");
        assertEquals("BOX", second.trackStatus());
        var gtd = tower.classes().getFirst();
        assertEquals(List.of(new LiveParticipantDetails.ClassSector(29_000, "4", null),
                        new LiveParticipantDetails.ClassSector(31_000, "04", "One")),
                gtd.bestSectors(), "#04's sector 2 best was first run on lap 1, by Ann One; #4 has no laps to say");
        assertEquals("Ann One", first.bestLapDriver(), "the best lap (lap 2) was Ann's, though Bea is in the car");
        assertNull(second.bestLapDriver());
        assertEquals(2, first.overallPosition(), "#4 leads overall, from the overall standings");
        assertEquals(1, first.overallGapLaps());
        assertEquals(1, second.overallPosition());
        assertFalse(first.checkered());
        assertTrue(second.checkered(), "hasSeenCheckered");
        assertEquals(60_000L, gtd.idealMs());
        assertNull(first.startPosition(), "not a race: no places gained");
        assertEquals(253.0, first.topSpeed(), "the best trap counts on an invalid lap too");
        assertNull(second.topSpeed());
    }

    @Test
    void withoutTheFeedsWordTheFlagFallsAtTheLeadersLastCrossingOrTheClockEnd() {
        var overall = java.util.Map.of("7", new LiveTimingPageService.Overall(1, null, null, null, null),
                "9", new LiveTimingPageService.Overall(2, 5_000L, null, 5_000L, null));
        var crossings = java.util.Map.of("7", 1_000L, "9", 1_005L, "3", 990L);
        assertEquals(java.util.Set.of("7", "9"), LiveTimingPageService.pastTheFlag(true, overall, crossings, null),
                "#3 has not crossed since the leader took the flag");
        var clock = new LiveTimingService.Clock("BY_TIME", 0L, 995L, null, null, null, 0, null);
        assertEquals(java.util.Set.of("7", "9"), LiveTimingPageService.pastTheFlag(false, java.util.Map.of(), crossings, clock),
                "practice: the flag falls when the clock runs out");
        assertTrue(LiveTimingPageService.pastTheFlag(true, java.util.Map.of(), crossings, clock).isEmpty(),
                "a race with no overall leader: nothing to go by");
    }

    @Test
    void theClassStartRanksTheOverallGridWithinTheClass() throws Exception {
        var grid = LiveTimingPageService.gridPositions(mapper.readTree("""
                {"positions": {"1": {"participant": "7", "position": 1}, "2": {"participant": "04", "position": 2},
                               "3": {"participant": "31", "position": 3}, "4": {"participant": "4", "position": 4}}}
                """));
        assertEquals(java.util.Map.of("7", 1, "04", 2, "31", 3, "4", 4), grid);
        assertEquals(java.util.Map.of("04", 1, "4", 2), LiveTimingPageService.classStart(List.of("4", "04", "23"), grid),
                "#04 started 2nd overall, 1st in its class; #23 was not on the grid");
        assertTrue(LiveTimingPageService.gridPositions(null).isEmpty());
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
    void aCarNeverShowsAnotherCarsEnergyThroughALeadingZero() throws Exception {
        // Telemetry sent only "4", and #4 is a car of its own here: #04 has no energy, #4 keeps it.
        seedFour();
        db.sql("""
                INSERT INTO live_energy_lap (session_db_id, car_number, lap_number, energy_pct)
                VALUES (:s, '4', 1, 97), (:s, '4', 2, 93.5)
                """).param("s", session).update();
        assertNull(page(false).car("04", session).laps().get(1).energyPct());
        assertEquals(93.5f, page(false).car("4", session).laps().get(1).energyPct());
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

    /** #4 runs 101 s laps from T0 beside #04's 99.999, 99.998, 99.997; it pits at the end of lap 2. */
    private void seedFour() {
        for (int lap = 1; lap <= 3; lap++) {
            db.sql("""
                    INSERT INTO live_lap (session_db_id, car_number, lap_number, start_time_ms, lap_time_ms, is_valid,
                                          sector_ms, pit_in_time_ms)
                    VALUES (:s, '4', :lap, :start, 101000, true, '{29000,33000,39000}', :pit)
                    """)
                    .param("s", session).param("lap", lap).param("start", T0 + (lap - 1) * 101_000L)
                    .param("pit", lap == 2 ? T0 : null, java.sql.Types.BIGINT).update();
        }
    }

    @Test
    void gapsByClassFromTheRecordedLaps() throws Exception {
        seedFour();
        var gaps = analysis(true).gaps(session);
        assertEquals(1, gaps.classes().size(), "the session being fed: #4 is in GTD on the tower");
        var gtd = gaps.classes().getFirst();
        assertEquals("GTD", gtd.className());
        assertEquals(List.of("4", "04"), gtd.cars().stream().map(LiveAnalysisService.CarInfo::carNumber).toList());
        var four = gtd.gaps().get(1);
        assertEquals("4", four.carNumber());
        assertEquals(java.util.Arrays.asList(1_001L, 2_002L, 3_003L), four.gapMs(), "#04 finishes lap n at n × 100 s − n ms");
        assertEquals(List.of(2), four.pitLaps());

        var past = analysis(false).gaps(session);
        assertEquals(List.of("GTD", LiveAnalysisService.NOT_ENTERED),
                past.classes().stream().map(LiveAnalysisService.GapClass::className).toList(),
                "an old session: #4's drivers were never matched to an entry");
        assertEquals(java.util.Arrays.asList(0L, 0L, 0L), past.classes().getFirst().gaps().getFirst().gapMs());
    }

    @Test
    void sectorBestsByClass() throws Exception {
        seedFour();
        var gtd = analysis(true).sectors(session).classes().getFirst();
        assertEquals(java.util.Arrays.asList(29_000, 31_000, 32_000), gtd.bests().classBestSectorMs(),
                "#04's lap 3 was invalid but laps 1-2 carry the same sectors");
        var zero4 = gtd.bests().cars().getFirst();
        assertEquals("04", zero4.carNumber());
        assertEquals(93_000L, zero4.theoreticalMs());
    }

    @Test
    void pitStopsWithTheDriversEitherSide() throws Exception {
        db.sql("""
                INSERT INTO live_stint (session_db_id, car_number, start_time_ms, type, driver_order, open_lap_number,
                                        close_lap_number, finish_time_ms)
                VALUES (:s, '04', :t, 'PIT', 1, 2, 3, :f)
                """)
                .param("s", session).param("t", T0 + H).param("f", T0 + H + 70_000).update();
        var response = analysis(false).pits(session);
        var gtd = response.classes().getFirst();
        var zero4 = gtd.pits().getFirst();
        assertEquals(1, zero4.stops().size());
        var stop = zero4.stops().getFirst();
        assertEquals(70_000L, stop.durationMs());
        assertEquals(1, stop.driverIn());
        assertEquals(2, stop.driverOut());
        assertTrue(stop.driverChange());
        assertTrue(mapper.valueToTree(stop).path("driverChange").asBoolean(), "sent to the page, not only a method");
        assertEquals(1, zero4.lapsSinceStop(), "3 laps done, stopped on lap 2");
        assertEquals("Two", response.drivers().get("04").get(2));

        var tower = page(true).tower().classes().getFirst().cars();
        assertEquals(1, tower.get(0).pitStops(), "the tower counts stops as the Pits view does");
        assertEquals(70_000L, tower.get(0).lastPitMs());
        assertEquals(0, tower.get(1).pitStops(), "#4 has no stints: none yet, not unknown");
        assertNull(tower.get(1).lastPitMs());
    }

    @Test
    void theTowerCountsAnOpenStopButTimesOnlyFinishedOnes() throws Exception {
        db.sql("""
                INSERT INTO live_stint (session_db_id, car_number, start_time_ms, type, driver_order, open_lap_number,
                                        finish_time_ms)
                VALUES (:s, '04', :a, 'PIT', 1, 1, :af), (:s, '04', :b, 'PIT', 2, 3, NULL)
                """)
                .param("s", session).param("a", T0 + H).param("af", T0 + H + 65_000).param("b", T0 + 3 * H).update();
        var car = page(true).tower().classes().getFirst().cars().getFirst();
        assertEquals(2, car.pitStops());
        assertEquals(65_000L, car.lastPitMs(), "the stop in progress has no time yet");
    }
}
