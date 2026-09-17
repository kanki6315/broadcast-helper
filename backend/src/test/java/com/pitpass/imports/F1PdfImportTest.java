package com.pitpass.imports;

import com.pitpass.imports.ImportService.ImportTarget;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F1 support-race PDFs end to end (sidecar -> stage -> commit) plus the
 * initialled-name resolution those sheets need. Runs against the local dev
 * Postgres like the other @SpringBootTest classes; every write rolls back.
 */
@SpringBootTest
@Transactional
class F1PdfImportTest {

    private static final Path SAMPLES = Path.of("../parser/samples");

    @Autowired JdbcClient db;
    @Autowired ImportService service;

    private record Seeded(long seasonId, long eventId, String surname) {
    }

    private Seeded seed() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        long seriesId = db.sql("INSERT INTO series (name) VALUES (:n) RETURNING id")
                .param("n", "F1 PDF series " + suffix).query(Long.class).single();
        long seasonId = db.sql("INSERT INTO season (series_id, year) VALUES (:s, 2099) RETURNING id")
                .param("s", seriesId).query(Long.class).single();
        long eventId = db.sql("INSERT INTO event (season_id, name) VALUES (:s, 'Miami') RETURNING id")
                .param("s", seasonId).query(Long.class).single();
        return new Seeded(seasonId, eventId, "Zzlastochkin" + suffix);
    }

    private long entry(long eventId, String number) {
        return db.sql("""
                        INSERT INTO entry (event_id, car_number, class_name, team_name)
                        VALUES (:event, :number, 'Pro', 'Team')
                        RETURNING id
                        """)
                .param("event", eventId).param("number", number).query(Long.class).single();
    }

    private void assign(long entryId, long driverId) {
        db.sql("INSERT INTO driver_assignment (entry_id, driver_id, seat_order) VALUES (:e, :d, 1)")
                .param("e", entryId).param("d", driverId).update();
    }

    private static RaceResultsImport.DriverRow initialled(String initials, String surname) {
        return new RaceResultsImport.DriverRow(1, initials, surname, null, null, null);
    }

    // ------------------------------------------------------------ initials

    @Test
    void initialResolvesToTheDriverOfThatCar() {
        Seeded s = seed();
        long nikita = service.findOrCreateDriver("Nikita", s.surname(), null, null);
        long nora = service.findOrCreateDriver("Nora", s.surname(), null, null);
        long otherEvent = db.sql("INSERT INTO event (season_id, name) VALUES (:s, 'Sebring') RETURNING id")
                .param("s", s.seasonId()).query(Long.class).single();
        assign(entry(otherEvent, "03"), nikita);  // same car (leading zero ignored)
        assign(entry(otherEvent, "44"), nora);    // same season, other car
        long miamiEntry = entry(s.eventId(), "3");

        assertEquals(nikita, service.resolveDriver(initialled("N.", s.surname().toUpperCase()), miamiEntry));
    }

    @Test
    void initialResolvesToTheOnlyKnownMatch() {
        Seeded s = seed();
        long nikita = service.findOrCreateDriver("Nikita", s.surname(), null, null);
        long entry = entry(s.eventId(), "3");

        assertEquals(nikita, service.resolveDriver(initialled("N.", s.surname()), entry));
        // Multi-initial spellings match on the first initial.
        assertEquals(nikita, service.resolveDriver(initialled("N. A.", s.surname()), entry));
    }

    @Test
    void ambiguousInitialIsNeverGuessed() {
        Seeded s = seed();
        long nikita = service.findOrCreateDriver("Nikita", s.surname(), null, null);
        long nick = service.findOrCreateDriver("Nick", s.surname(), null, null);
        long entry = entry(s.eventId(), "3");

        long resolved = service.resolveDriver(initialled("N.", s.surname()), entry);
        assertNotEquals(nikita, resolved);
        assertNotEquals(nick, resolved);
        assertEquals("N.", db.sql("SELECT first_name FROM driver WHERE id = :id")
                .param("id", resolved).query(String.class).single());
    }

    @Test
    void fullNamesAreUnaffected() {
        Seeded s = seed();
        long nikita = service.findOrCreateDriver("Nikita", s.surname(), null, null);
        long entry = entry(s.eventId(), "3");
        long nate = service.resolveDriver(
                new RaceResultsImport.DriverRow(1, "Nate", s.surname(), null, null, null), entry);
        assertNotEquals(nikita, nate);
    }

    // ------------------------------------------------------------ end to end

    private long stage(String sample) throws Exception {
        Path pdf = SAMPLES.resolve(sample);
        Assumptions.assumeTrue(Files.exists(pdf), "sample PDF not present");
        List<ImportService.BatchSummary> staged;
        try {
            staged = service.stage(sample, Files.readAllBytes(pdf), ImportFormat.F1_PDF);
        } catch (org.springframework.web.server.ResponseStatusException e) {
            // No python3/pdfplumber on this machine: the sidecar can't run.
            Assumptions.assumeTrue(!e.getReason().contains("Could not run"), e.getReason());
            throw e;
        }
        assertEquals(1, staged.size());
        return staged.get(0).id();
    }

    private static ImportTarget target(long eventId, String sessionType, int ordinal, Map<String, String> classes) {
        return new ImportTarget(null, null, eventId, null, null, null, null, null, null,
                sessionType, ordinal, classes, null, true, null, null, null);
    }

    @Test
    void miamiWeekendCommitsWithAttributedGridAndResolvedInitials() throws Exception {
        Seeded s = seed();
        long gridId = stage("2026_PCCNA_Miami_Grid_R1.pdf");
        long raceId = stage("2026_PCCNA_Miami_Results_R1.pdf");
        long qualiId = stage("2026_PCCNA_Miami_Qualifying.pdf");
        assertTrue(service.get(raceId).summary().startsWith("Miami Gardens 2026 — Race 1 official classification"));

        // Review pre-fills the session from the sheet's title.
        ImportService.ImportReview review = service.reviewTarget(qualiId, s.eventId(), null);
        assertEquals("QUALIFYING", review.sessionTypeHint());
        assertEquals(1, review.sessionOrdinalHint());

        // Grid first: its full names seed the lineups the results' initials
        // then resolve against.
        service.commit(gridId, target(s.eventId(), "RACE", 1, null));
        service.commit(raceId, target(s.eventId(), "RACE", 1, null));
        service.commit(qualiId, target(s.eventId(), "QUALIFYING", 1, null));

        Map<String, Object> lastochkin = db.sql("""
                        SELECT d.first_name, d.surname, r.position_overall, g.position_overall AS grid_pos,
                               g.starting_driver_id = d.id AS attributed
                        FROM entry e
                        JOIN driver_assignment da ON da.entry_id = e.id
                        JOIN driver d ON d.id = da.driver_id
                        JOIN race_session rs ON rs.event_id = e.event_id AND rs.session_type = 'RACE'
                        JOIN result r ON r.session_id = rs.id AND r.entry_id = e.id
                        JOIN grid_position g ON g.session_id = rs.id AND g.entry_id = e.id
                        WHERE e.event_id = :event AND e.car_number = '3'
                        """)
                .param("event", s.eventId()).query().singleRow();
        // "N. LASTOCHKIN" in the results and qualifying resolved to the grid's
        // "Nikita Lastochkin" instead of minting a second driver.
        assertEquals("Nikita", lastochkin.get("first_name"));
        assertEquals(5, lastochkin.get("position_overall"));
        assertEquals(11, lastochkin.get("grid_pos"));
        assertEquals(true, lastochkin.get("attributed"));

        long initialDrivers = db.sql("""
                        SELECT count(*) FROM driver_assignment da
                        JOIN entry e ON e.id = da.entry_id
                        JOIN driver d ON d.id = da.driver_id
                        WHERE e.event_id = :event AND d.first_name LIKE '_.'
                        """)
                .param("event", s.eventId()).query(Long.class).single();
        assertEquals(0, initialDrivers);

        Map<String, Object> race = db.sql("""
                        SELECT report_mark, report_message,
                               (SELECT count(*) FROM result r WHERE r.session_id = rs.id) AS results
                        FROM race_session rs WHERE rs.event_id = :event AND rs.session_type = 'RACE'
                        """)
                .param("event", s.eventId()).query().singleRow();
        assertEquals("Official", race.get("report_mark"));
        assertEquals(19L, race.get("results"));

        Map<String, Object> notClassified = db.sql("""
                        SELECT r.position_overall, r.status, r.not_finished
                        FROM result r JOIN entry e ON e.id = r.entry_id
                        JOIN race_session rs ON rs.id = r.session_id AND rs.session_type = 'RACE'
                        WHERE e.event_id = :event AND e.car_number = '78'
                        """)
                .param("event", s.eventId()).query().singleRow();
        assertNull(notClassified.get("position_overall"));
        assertEquals("Not classified", notClassified.get("status"));
        assertEquals(true, notClassified.get("not_finished"));

        String poleTime = db.sql("""
                        SELECT r.fastest_lap_time FROM result r
                        JOIN entry e ON e.id = r.entry_id
                        JOIN race_session rs ON rs.id = r.session_id AND rs.session_type = 'QUALIFYING'
                        WHERE e.event_id = :event AND r.position_overall = 1
                        """)
                .param("event", s.eventId()).query(String.class).single();
        assertEquals("1:55.721", poleTime);
    }
}
