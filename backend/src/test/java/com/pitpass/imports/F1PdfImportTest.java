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
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    // ------------------------------------------------------------ family name first

    // Carrera Cup Asia's entry list and points print "LING Kang"; the F1-paddock
    // sheet at Shanghai prints "Kang LING" and "J. BAO". The stored split follows
    // the entry list (first_name LING, surname Kang).

    private static RaceResultsImport.DriverRow named(String first, String surname) {
        return new RaceResultsImport.DriverRow(1, first, surname, null, null, null);
    }

    private void standings(long seasonId, String... keys) {
        long groupId = db.sql("""
                        INSERT INTO championship_group (season_id, family, kind, label, ordinal)
                        VALUES (:s, 'Cup', 'DRIVERS', 'Cup — Drivers', 1) RETURNING id
                        """)
                .param("s", seasonId).query(Long.class).single();
        long champId = db.sql("""
                        INSERT INTO championship (season_id, group_id, name) VALUES (:s, :g, 'Overall') RETURNING id
                        """)
                .param("s", seasonId).param("g", groupId).query(Long.class).single();
        for (int i = 0; i < keys.length; i++) {
            db.sql("""
                            INSERT INTO standings_row (championship_id, position, competitor_key, total_points)
                            VALUES (:c, :p, :k, 0)
                            """)
                    .param("c", champId).param("p", i + 1).param("k", keys[i]).update();
        }
    }

    private String nameOf(long driverId) {
        return db.sql("SELECT first_name || '|' || surname FROM driver WHERE id = :id")
                .param("id", driverId).query(String.class).single();
    }

    @Test
    void givenNameFirstResolvesToTheFamilyNameFirstDriverOfThatCar() {
        Seeded s = seed();
        String family = s.surname().toUpperCase();
        long ling = service.findOrCreateDriver(family, "Kang", null, null);
        long otherEvent = db.sql("INSERT INTO event (season_id, name) VALUES (:s, 'Bangsaen') RETURNING id")
                .param("s", s.seasonId()).query(Long.class).single();
        assign(entry(otherEvent, "87"), ling);

        assertEquals(ling, service.resolveDriver(named("Kang", family), entry(s.eventId(), "87")));
    }

    @Test
    void theCarNumberAloneNeverNamesTheDriver() {
        // #4 is LU Wei at Shanghai but LI Kerong at Bangsaen: same car, other person.
        Seeded s = seed();
        long liKerong = service.findOrCreateDriver("LI", "Kerong" + s.surname(), null, null);
        long otherEvent = db.sql("INSERT INTO event (season_id, name) VALUES (:s, 'Bangsaen') RETURNING id")
                .param("s", s.seasonId()).query(Long.class).single();
        assign(entry(otherEvent, "4"), liKerong);

        long luWei = service.resolveDriver(named("Wei", "LU" + s.surname()), entry(s.eventId(), "4"));
        assertNotEquals(liKerong, luWei);
    }

    @Test
    void initialMatchesTheGivenNameOnEitherSide() {
        Seeded s = seed();
        String family = s.surname().toUpperCase();
        long bao = service.findOrCreateDriver(family, "Jinlong", null, null);
        long otherEvent = db.sql("INSERT INTO event (season_id, name) VALUES (:s, 'Bangsaen') RETURNING id")
                .param("s", s.seasonId()).query(Long.class).single();
        assign(entry(otherEvent, "7"), bao);

        assertEquals(bao, service.resolveDriver(initialled("J.", family), entry(s.eventId(), "7")));
    }

    @Test
    void standingsNameADriverWithNoRecordInTheSeriesWordOrder() {
        // BAO skipped Bangsaen, so the points sheet is the only full spelling.
        Seeded s = seed();
        String family = s.surname().toUpperCase();
        standings(s.seasonId(), family + " Jinlong", "WANG Zhongwei");

        long car = entry(s.eventId(), "7");
        long fromInitial = service.resolveDriver(initialled("J.", family), car);
        assertEquals(family + "|Jinlong", nameOf(fromInitial));
        long fromFullName = service.resolveDriver(named("Jinlong", family), car);
        assertEquals(fromInitial, fromFullName);
    }

    @Test
    void reorderedNameMatchesADriverFromAnotherSeason() {
        Seeded s = seed();
        String family = s.surname().toUpperCase();
        long sawer = service.findOrCreateDriver(family, "Hoang Dat", null, null);

        assertEquals(sawer, service.resolveDriver(named("Hoang Dat", family), entry(s.eventId(), "12")));
    }

    @Test
    void anExactSpellingStillWinsOverAReorderedOne() {
        Seeded s = seed();
        String family = s.surname().toUpperCase();
        long reordered = service.findOrCreateDriver(family, "Kang", null, null);
        long exact = service.findOrCreateDriver("Kang", family, null, null);

        assertEquals(exact, service.resolveDriver(named("Kang", family), entry(s.eventId(), "87")));
        assertNotEquals(reordered, exact);
    }

    @Test
    void aShortenedNameMatchesOnlyTheSameCar() {
        // Fuji prints "Roy TANG" for #25 Tien Foo Roy TANG.
        Seeded s = seed();
        String family = s.surname().toUpperCase();
        long tang = service.findOrCreateDriver("Tien", "Foo Roy " + family, null, null);
        long otherEvent = db.sql("INSERT INTO event (season_id, name) VALUES (:s, 'Zhuhai') RETURNING id")
                .param("s", s.seasonId()).query(Long.class).single();
        assign(entry(otherEvent, "25"), tang);

        assertEquals(tang, service.resolveDriver(named("Roy", family), entry(s.eventId(), "25")));
        assertNotEquals(tang, service.resolveDriver(named("Roy", family), entry(s.eventId(), "26")));
    }

    @Test
    void aFullNameAbsorbsTheInitialMintedBeforeIt() {
        // Qualifying before the grid: "M. MAASSEN" has no known driver, so it is
        // minted as written; the grid's "Montego MAASSEN" then takes its place.
        Seeded s = seed();
        String family = s.surname().toUpperCase();
        long car = entry(s.eventId(), "89");
        long minted = service.resolveDriver(initialled("M.", family), car);
        assign(car, minted);
        assertEquals("M.", nameOf(minted).split("\\|")[0]);

        long full = service.resolveDriver(named("Montego", family), car);
        assertNotEquals(minted, full);
        assertEquals(0L, db.sql("SELECT count(*) FROM driver WHERE id = :id")
                .param("id", minted).query(Long.class).single());
        // The seat moved to the full-named driver; the initial is not kept as an alias.
        assertEquals(full, db.sql("SELECT driver_id FROM driver_assignment WHERE entry_id = :e")
                .param("e", car).query(Long.class).single());
        assertEquals(0L, db.sql("SELECT count(*) FROM driver_alias WHERE driver_id = :id")
                .param("id", full).query(Long.class).single());
    }

    @Test
    void anInitialOnAnotherCarIsLeftAlone() {
        Seeded s = seed();
        String family = s.surname().toUpperCase();
        long other = entry(s.eventId(), "88");
        long minted = service.resolveDriver(initialled("M.", family), other);
        assign(other, minted);

        service.resolveDriver(named("Montego", family), entry(s.eventId(), "89"));
        assertEquals(1L, db.sql("SELECT count(*) FROM driver WHERE id = :id")
                .param("id", minted).query(Long.class).single());
    }

    @Test
    void namesAgreeRules() {
        assertTrue(ImportService.namesAgree("Kang", "LING", "LING Kang"));
        assertTrue(ImportService.namesAgree("Tien Foo Roy", "TANG", "Tien Foo Roy TANG"));
        assertTrue(ImportService.namesAgree("J.", "BAO", "BAO Jinlong"));
        assertTrue(ImportService.namesAgree("R.", "DIAS ALMEIDA", "Rodrigo DIAS ALMEIDA"));
        assertTrue(ImportService.namesAgree("H.", "SAWER", "SAWER Hoang Dat"));
        assertFalse(ImportService.namesAgree("Wei", "LU", "LI Kerong"));
        assertFalse(ImportService.namesAgree("J.", "BAO", "BAO Kang"));
        assertFalse(ImportService.namesAgree("J.", "BAO", "J. BAO"));
        assertFalse(ImportService.namesAgree("Kang", "LING", "LING Kang Jr"));
        assertTrue(ImportService.shortenedName("Roy", "TANG", "Tien Foo Roy TANG"));
        assertFalse(ImportService.shortenedName("Roy", "TAN", "Tien Foo Roy TANG"));
        assertFalse(ImportService.shortenedName("Wei", "LYU", "LU Wei"));
        assertTrue(ImportService.namesAgreeButSuffix("Javier", "Ripoll jr", "Javier Ripoll"));
        assertTrue(ImportService.namesAgreeButSuffix("Javier", "RIPOLL", "Javier Ripoll Jr."));
        assertFalse(ImportService.namesAgreeButSuffix("Javier", "Ripoll", "Javier Ripoll"));
        assertFalse(ImportService.namesAgreeButSuffix("Jose", "Ripoll jr", "Javier Ripoll"));
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
