package com.pitpass.live;

import com.pitpass.live.LiveEventMatch.FeedCar;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Filing by series weekend over the real schema (slice 3 of
 * docs/LIVE_TIMING_ALL_SERIES_PLAN.md). Sessions, their cars and drivers are
 * seeded as the analysis writer would leave them; no feed is involved.
 * Championship names are unique per test so a real series never answers.
 */
@SpringBootTest
@Transactional
class LiveFilingTest {

    private static final LocalDate RACE_DAY = LocalDate.of(2099, 10, 3);

    @Autowired JdbcClient db;
    @Autowired LiveFiling filing;
    @Autowired LiveFeedChampionshipController championships;

    private String u;

    @BeforeEach
    void suffix() {
        u = UUID.randomUUID().toString().substring(0, 8);
    }

    // ---- seeding -----------------------------------------------------------------------

    private long series(String name) {
        return db.sql("INSERT INTO series (name) VALUES (:n) RETURNING id").param("n", name).query(Long.class).single();
    }

    private long event(long seriesId, String name, LocalDate date) {
        long season = db.sql("INSERT INTO season (series_id, year) VALUES (:s, 2099) RETURNING id")
                .param("s", seriesId).query(Long.class).single();
        return db.sql("INSERT INTO event (season_id, name, round_ordinal, event_date) VALUES (:s, :n, 1, :d) RETURNING id")
                .param("s", season).param("n", name).param("d", date).query(Long.class).single();
    }

    private long entry(long eventId, String number, String className) {
        return db.sql("INSERT INTO entry (event_id, car_number, class_name, team_name) VALUES (:e, :n, :c, 'Team') RETURNING id")
                .param("e", eventId).param("n", number).param("c", className).query(Long.class).single();
    }

    private long crew(long entryId, String first, String surname) {
        long driver = db.sql("INSERT INTO driver (first_name, surname) VALUES (:f, :s) RETURNING id")
                .param("f", first).param("s", surname).query(Long.class).single();
        db.sql("INSERT INTO driver_assignment (entry_id, driver_id, seat_order, rating) VALUES (:e, :d, 1, 'S')")
                .param("e", entryId).param("d", driver).update();
        return driver;
    }

    private static long id() {
        return 800_000_000L + ThreadLocalRandom.current().nextLong(100_000_000L);
    }

    /** A recorded session, as the writer leaves it: labels, cars (number:class) and the feed's drivers. */
    private long session(long feedEvent, long champDbId, String champName, LocalDate day, String... cars) {
        long session = id();
        db.sql("INSERT INTO live_feed_event (feed_event_db_id) VALUES (:f) ON CONFLICT DO NOTHING")
                .param("f", feedEvent).update();
        db.sql("""
                INSERT INTO live_session (session_db_id, feed_event_db_id, name, type, session_date_ms,
                                          champ_db_id, champ_name, feed_event_short_name)
                VALUES (:s, :f, 'Practice', 'FREE_PRACTICE', :d, :c, :n, 'Road Atlanta')
                """)
                .param("s", session).param("f", feedEvent)
                .param("d", day.atTime(16, 25).toInstant(ZoneOffset.UTC).toEpochMilli())
                .param("c", champDbId).param("n", champName).update();
        for (String car : cars) {
            String[] numberClass = car.split(":");
            db.sql("INSERT INTO live_car (session_db_id, car_number, feed_class) VALUES (:s, :n, :c)")
                    .param("s", session).param("n", numberClass[0]).param("c", numberClass[1]).update();
            db.sql("""
                    INSERT INTO live_driver (session_db_id, car_number, driver_order, first_name, last_name, license)
                    VALUES (:s, :n, 1, 'Ann', :last, 'Silver')
                    """)
                    .param("s", session).param("n", numberClass[0]).param("last", "Car" + numberClass[0] + u).update();
        }
        return session;
    }

    private Long filedUnder(long session) {
        return db.sql("SELECT event_id FROM live_session WHERE session_db_id = :s").param("s", session)
                .query((rs, i) -> rs.getObject("event_id", Long.class)).list().getFirst();
    }

    private String boundBy(long feedEvent) {
        return db.sql("SELECT bound_by FROM live_feed_event WHERE feed_event_db_id = :f").param("f", feedEvent)
                .query((rs, i) -> rs.getString("bound_by")).list().getFirst();
    }

    // ---- tests -------------------------------------------------------------------------

    @Test
    void aWeekendIsFiledByChampionshipAndEverySessionFollows() {
        String champ = "Champ " + u;
        long event = event(series(champ), "Race " + u, RACE_DAY);
        long zero4 = entry(event, "04", "GTD");
        entry(event, "7", "GTD");
        long ann = crew(zero4, "Ann", "Car04" + u);
        long feedEvent = id();
        long practice = session(feedEvent, 612, champ, RACE_DAY.minusDays(3), "04:GTD", "7:GTD");
        long qualifying = session(feedEvent, 612, champ, RACE_DAY.minusDays(2));

        assertEquals(1, filing.sweepChampionship(champ));
        assertEquals("AUTO", boundBy(feedEvent));
        assertEquals(event, filedUnder(practice));
        assertEquals(event, filedUnder(qualifying), "the weekend is bound, so every session of it follows");
        assertEquals(ann, db.sql("""
                SELECT driver_id FROM live_driver WHERE session_db_id = :s AND car_number = '04'
                """).param("s", practice).query(Long.class).single(), "drivers matched against the filed event's crew");

        // A session that arrives later is filed without being matched again.
        long race = session(feedEvent, 612, champ, RACE_DAY, "99:TCR");
        assertTrue(filing.fileLive(feedEvent, null, List.of(new FeedCar("99", "TCR"))));
        assertEquals(event, filedUnder(race));
    }

    @Test
    void theDateWindowRunsSixDaysBeforeTheRaceDay() {
        String champ = "Window " + u;
        long event = event(series(champ), "Race " + u, RACE_DAY);
        entry(event, "04", "GTD");
        long tooEarly = id();
        long early = session(tooEarly, 700, champ, RACE_DAY.minusDays(8), "04:GTD");
        long inTime = id();
        long practice = session(inTime, 700, "Window other " + u, RACE_DAY.minusDays(4), "04:GTD");
        db.sql("UPDATE live_session SET champ_name = :n, feed_event_short_name = 'Elsewhere' WHERE session_db_id = :s")
                .param("n", champ).param("s", practice).update(); // not inheritable from the early one

        filing.sweepChampionship(champ);
        assertNull(filedUnder(early), "eight days before the race day is another weekend");
        assertEquals(event, filedUnder(practice), "four days before files");
    }

    /** 2026-09-30: VP Racing ran while IMPC was bound; its numbers overlap IMPC's, its classes do not. */
    @Test
    void aSeriesWithNoEventStaysUnfiledEvenWithTheConnectionBoundElsewhere() {
        String impc = "IMPC " + u;
        String vp = "VP " + u;
        long impcEvent = event(series(impc), "Fox Factory 120 " + u, RACE_DAY);
        entry(impcEvent, "2", "GS");
        entry(impcEvent, "5", "TCR");
        series(vp); // in Pit Pass, but no event this weekend
        long feedEvent = id();
        long vpPractice = session(feedEvent, 613, vp, RACE_DAY.minusDays(3), "2:LMP3", "5:GSX", "11:LMP3");

        assertFalse(filing.fileLive(feedEvent, impcEvent, List.of(
                new FeedCar("2", "LMP3"), new FeedCar("5", "GSX"), new FeedCar("11", "LMP3"))));
        assertNull(filedUnder(vpPractice));
        assertNull(boundBy(feedEvent), "left unbound, to be tried again");
    }

    @Test
    void theConnectionsEventFilesAChampionshipPitPassDoesNotName() {
        long event = event(series("Unnamed " + u), "Race " + u, RACE_DAY);
        entry(event, "04", "GTD");
        long feedEvent = id();
        long session = session(feedEvent, 900, "Some Feed Name " + u, RACE_DAY.minusDays(1), "04:GTD");

        assertTrue(filing.fileLive(feedEvent, event, List.of(new FeedCar("04", "GTD"))));
        assertEquals(event, filedUnder(session));
    }

    @Test
    void entriesImportedAfterPracticeFileOnTheNextSweep() {
        String champ = "Late entries " + u;
        long event = event(series(champ), "Race " + u, RACE_DAY);
        long feedEvent = id();
        long practice = session(feedEvent, 701, champ, RACE_DAY.minusDays(3), "04:GTD", "7:GTD");

        assertEquals(0, filing.sweepChampionship(champ), "no entry list to confirm against yet");
        assertNull(filedUnder(practice));

        entry(event, "04", "GTD");
        entry(event, "7", "GTD");
        assertEquals(1, filing.sweepChampionship(champ));
        assertEquals(event, filedUnder(practice));
    }

    @Test
    void anAdminsNotInPitPassIsNeverOverridden() {
        String champ = "Declined " + u;
        long event = event(series(champ), "Race " + u, RACE_DAY);
        entry(event, "04", "GTD");
        long feedEvent = id();
        long practice = session(feedEvent, 702, champ, RACE_DAY.minusDays(3), "04:GTD");
        db.sql("UPDATE live_feed_event SET bound_by = 'ADMIN_NONE' WHERE feed_event_db_id = :f").param("f", feedEvent).update();

        assertEquals(0, filing.sweepChampionship(champ));
        assertTrue(filing.fileLive(feedEvent, event, List.of(new FeedCar("04", "GTD"))), "settled, by the admin");
        assertNull(filedUnder(practice));
        assertEquals("ADMIN_NONE", boundBy(feedEvent));
    }

    @Test
    void aNewFeedEventIdForTheSameWeekendInheritsItsBinding() {
        String champ = "Inherit " + u;
        long event = event(series(champ), "Race " + u, RACE_DAY);
        entry(event, "04", "GTD");
        long wednesday = id();
        session(wednesday, 703, champ, RACE_DAY.minusDays(3), "04:GTD");
        filing.sweepChampionship(champ);
        assertEquals("AUTO", boundBy(wednesday));

        // Saturday under a new id, before its running order (no cars): the same championship at the same track.
        long saturday = id();
        long race = session(saturday, 703, champ, RACE_DAY);
        assertTrue(filing.fileLive(saturday, null, List.of()));
        assertEquals(event, filedUnder(race));
    }

    @Test
    void aSessionsOwnOverrideWinsOverItsWeekend() {
        String champ = "Override " + u;
        long series = series(champ);
        long event = event(series, "Race " + u, RACE_DAY);
        long other = event(series(champ + " other"), "Other " + u, RACE_DAY);
        entry(event, "04", "GTD");
        long feedEvent = id();
        long practice = session(feedEvent, 704, champ, RACE_DAY.minusDays(3), "04:GTD");
        long combined = session(feedEvent, 704, champ, RACE_DAY.minusDays(2), "04:GTD");
        db.sql("UPDATE live_session SET event_override = :o WHERE session_db_id = :s")
                .param("o", other).param("s", combined).update();

        filing.sweepChampionship(champ);
        assertEquals(event, filedUnder(practice));
        assertEquals(other, filedUnder(combined));
    }

    @Test
    void mappingAChampionshipToASeriesFilesItsWeekends() {
        String champ = "IMSA Something " + u;
        long series = series("Pit Pass name " + u);
        long event = event(series, "Race " + u, RACE_DAY);
        entry(event, "04", "GTD");
        long feedEvent = id();
        long practice = session(feedEvent, 705, champ, RACE_DAY.minusDays(3), "04:GTD");

        var before = championships.list().stream().filter(c -> c.champName().equals(champ)).findFirst().orElseThrow();
        assertNull(before.seriesId());
        assertEquals(1, before.unfiledWeekends());

        var mapped = championships.map(new LiveFeedChampionshipController.MapRequest(champ, series));
        assertEquals(1, mapped.weekendsFiled());
        assertEquals(series, mapped.championship().seriesId());
        assertEquals(0, mapped.championship().unfiledWeekends());
        assertEquals(event, filedUnder(practice));

        assertEquals(409, assertThrows(ResponseStatusException.class,
                () -> championships.map(new LiveFeedChampionshipController.MapRequest(champ, series)))
                .getStatusCode().value(), "already stands for a series");
        assertEquals(404, assertThrows(ResponseStatusException.class,
                () -> championships.map(new LiveFeedChampionshipController.MapRequest("Never seen " + u, series)))
                .getStatusCode().value());
    }
}
