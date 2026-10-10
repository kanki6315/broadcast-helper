package com.pitpass.imports;

import com.pitpass.imports.ImportService.ImportTarget;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Carrera Cup Asia timing PDFs (Time Service Asia) end to end: sidecar ->
 * stage -> commit through the shared F1 mapper. Runs against the local dev
 * Postgres like the other @SpringBootTest classes; every write rolls back.
 */
@SpringBootTest
@Transactional
class PccaPdfImportTest {

    private static final Path SAMPLES = Path.of("../parser/samples");

    @Autowired JdbcClient db;
    @Autowired ImportService service;

    private record Seeded(long seasonId, long eventId) {
    }

    private Seeded seed(String eventName) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        long seriesId = db.sql("INSERT INTO series (name) VALUES (:n) RETURNING id")
                .param("n", "PCCA PDF series " + suffix).query(Long.class).single();
        long seasonId = db.sql("INSERT INTO season (series_id, year) VALUES (:s, 2099) RETURNING id")
                .param("s", seriesId).query(Long.class).single();
        return new Seeded(seasonId, event(seasonId, eventName));
    }

    private long event(long seasonId, String name) {
        return db.sql("INSERT INTO event (season_id, name) VALUES (:s, :n) RETURNING id")
                .param("s", seasonId).param("n", name).query(Long.class).single();
    }

    private long stage(String sample) throws Exception {
        Path pdf = SAMPLES.resolve(sample);
        Assumptions.assumeTrue(Files.exists(pdf), "sample PDF not present");
        List<ImportService.BatchSummary> staged;
        try {
            staged = service.stage(sample, Files.readAllBytes(pdf), ImportFormat.PCCA_PDF);
        } catch (ResponseStatusException e) {
            // No python3/pdfplumber on this machine: the sidecar can't run.
            Assumptions.assumeTrue(!e.getReason().contains("Could not run"), e.getReason());
            throw e;
        }
        assertEquals(1, staged.size());
        return staged.get(0).id();
    }

    private static ImportTarget target(long eventId, String sessionType, int ordinal, String gridBasis) {
        return new ImportTarget(null, null, eventId, null, null, null, null, null, null,
                sessionType, ordinal, null, gridBasis, true, null, null, null);
    }

    @Test
    void sepangWeekendCommitsResultsCombinedQualifyingAndTwoUpGrid() throws Exception {
        Seeded s = seed("Sepang");
        long raceId = stage("2026_PCCA_Sepang_Results_R9.pdf");
        long combinedId = stage("2026_PCCA_Sepang_Qualifying_Combined.pdf");
        long gridId = stage("2026_PCCA_Sepang_Grid_R11.pdf");
        assertTrue(service.get(raceId).summary().startsWith("Sepang 2026 — Race 1 (Round 9) final classification"),
                service.get(raceId).summary());

        // Results and qualifying first: the grid prints no class.
        service.commit(raceId, target(s.eventId(), "RACE", 1, null));
        service.commit(combinedId, target(s.eventId(), "QUALIFYING", 1, null));
        service.commit(gridId, target(s.eventId(), "RACE", 3, "Combined qualifying (Q1 + Q2 times)"));

        Map<String, Object> winner = db.sql("""
                        SELECT d.first_name, d.surname, e.team_name, e.class_name, r.laps, r.elapsed_time, r.fastest_lap_time
                        FROM entry e
                        JOIN driver_assignment da ON da.entry_id = e.id
                        JOIN driver d ON d.id = da.driver_id
                        JOIN race_session rs ON rs.event_id = e.event_id AND rs.session_type = 'RACE' AND rs.ordinal = 1
                        JOIN result r ON r.session_id = rs.id AND r.entry_id = e.id
                        WHERE e.event_id = :event AND r.position_overall = 1
                        """)
                .param("event", s.eventId()).query().singleRow();
        assertEquals("GILTRAP", ((String) winner.get("surname")).toUpperCase());
        assertEquals("Team Porsche New Zealand", winner.get("team_name"));
        assertEquals(13, winner.get("laps"));
        assertEquals("2:09.190", winner.get("fastest_lap_time"));

        Map<String, Object> retired = db.sql("""
                        SELECT r.position_overall, r.status, r.not_finished, r.laps
                        FROM result r JOIN entry e ON e.id = r.entry_id
                        JOIN race_session rs ON rs.id = r.session_id AND rs.session_type = 'RACE' AND rs.ordinal = 1
                        WHERE e.event_id = :event AND e.car_number = '89'
                        """)
                .param("event", s.eventId()).query().singleRow();
        assertNull(retired.get("position_overall"));
        assertEquals(true, retired.get("not_finished"));
        assertEquals(4, retired.get("laps"));

        // The summed qualifying is its own session, beside (not over) Qualifying 1.
        Map<String, Object> combined = db.sql("""
                        SELECT rs.name, (SELECT count(*) FROM result r WHERE r.session_id = rs.id) AS results
                        FROM race_session rs WHERE rs.event_id = :event AND rs.session_type = 'QUALIFYING'
                        """)
                .param("event", s.eventId()).query().singleRow();
        assertEquals("Qualifying Combined", combined.get("name"));
        assertEquals(29L, combined.get("results"));

        Map<String, Object> grid = db.sql("""
                        SELECT rs.grid_basis, count(g.*) AS slots,
                               bool_and(g.starting_driver_id IS NOT NULL) AS attributed
                        FROM race_session rs JOIN grid_position g ON g.session_id = rs.id
                        WHERE rs.event_id = :event AND rs.session_type = 'RACE' AND rs.ordinal = 3
                        GROUP BY rs.grid_basis
                        """)
                .param("event", s.eventId()).query().singleRow();
        assertEquals("Combined qualifying (Q1 + Q2 times)", grid.get("grid_basis"));
        assertEquals(29L, grid.get("slots"));
        assertEquals(true, grid.get("attributed"));
    }

    @Test
    void fujiExcelExportCommitsResultsThenGrid() throws Exception {
        Seeded s = seed("Fuji");
        // Qualifying first: #4 is on the grid but never started, so only
        // qualifying names its class.
        service.commit(stage("2026_PCCA_Fuji Rounds 5&6_Qualifying_1.pdf"), target(s.eventId(), "QUALIFYING", 1, null));
        service.commit(stage("2026_PCCA_Fuji Rounds 5&6_Results_R5.pdf"), target(s.eventId(), "RACE", 1, null));
        service.commit(stage("2026_PCCA_Fuji Rounds 5&6_Grid_R5.pdf"), target(s.eventId(), "RACE", 1, null));

        Map<String, Object> race = db.sql("""
                        SELECT count(*) FILTER (WHERE r.position_overall IS NOT NULL) AS classified,
                               count(*) FILTER (WHERE r.status = 'Disqualified') AS disqualified,
                               (SELECT count(*) FROM grid_position g WHERE g.session_id = rs.id) AS grid
                        FROM race_session rs JOIN result r ON r.session_id = rs.id
                        WHERE rs.event_id = :event AND rs.session_type = 'RACE'
                        GROUP BY rs.id
                        """)
                .param("event", s.eventId()).query().singleRow();
        assertEquals(25L, race.get("classified"));
        assertEquals(1L, race.get("disqualified"));
        assertEquals(30L, race.get("grid"));

        Map<String, String> teams = db.sql("SELECT car_number, team_name FROM entry WHERE event_id = :e")
                .param("e", s.eventId())
                .query((rs, i) -> Map.entry(rs.getString(1), rs.getString(2))).list()
                .stream().collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        assertEquals("Porsche Vietnam", teams.get("12"));
        assertEquals("Wetrade X Phantom Global", teams.get("8"));
    }

    @Test
    void gridWithoutEntrantsKeepsTheTeamsTheSeasonAlreadyKnows() throws Exception {
        Seeded s = seed("Bangsaen");
        // Bangsaen's grids print no entrant. Round 7's results name every car
        // but #8, which withdrew from it; #8 ran earlier in the season.
        service.commit(stage("2026_PCCA_Bangsaen Rounds 7&8_Results_R7.pdf"), target(s.eventId(), "RACE", 1, null));
        String pro = db.sql("SELECT class_name FROM entry WHERE event_id = :e AND car_number = '3'")
                .param("e", s.eventId()).query(String.class).single();
        long earlier = event(s.seasonId(), "Fuji");
        db.sql("""
                        INSERT INTO entry (event_id, car_number, class_name, team_name)
                        VALUES (:e, '8', :cls, 'Wetrade X Phantom Global')
                        """)
                .param("e", earlier).param("cls", pro).update();
        service.commit(stage("2026_PCCA_Bangsaen Rounds 7&8_Grid_R8.pdf"), target(s.eventId(), "RACE", 2, null));

        Map<String, String> teams = db.sql("SELECT car_number, team_name FROM entry WHERE event_id = :e")
                .param("e", s.eventId())
                .query((rs, i) -> Map.entry(rs.getString(1), rs.getString(2))).list()
                .stream().collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        assertEquals("BWT Shanghai Yonda", teams.get("3"));
        assertEquals("Wetrade X Phantom Global", teams.get("8"));
    }

    @Test
    void aGridBeforeItsResultsWaitsThenCommitsItself() throws Exception {
        // A brand-new season: nothing classifies any car on the grid yet.
        Seeded s = seed("Sepang");
        long gridId = stage("2026_PCCA_Sepang_Grid_R11.pdf");
        ImportService.BatchSummary parked = service.commit(gridId,
                target(s.eventId(), "RACE", 3, "Combined qualifying (Q1 + Q2 times)"));
        assertEquals("WAITING", parked.status());
        assertTrue(parked.waitingFor().startsWith("#66 (class), #3 (class)"), parked.waitingFor());
        assertEquals(0L, gridSlots(s.eventId()));

        // The weekend's results name every car: the grid lands on its own.
        service.commit(stage("2026_PCCA_Sepang_Results_R9.pdf"), target(s.eventId(), "RACE", 1, null));
        ImportService.BatchSummary done = service.get(gridId);
        assertEquals("COMMITTED", done.status());
        assertNull(done.waitingFor());
        assertEquals(29L, gridSlots(s.eventId()));
        assertEquals("Combined qualifying (Q1 + Q2 times)", db.sql("""
                        SELECT grid_basis FROM race_session
                        WHERE event_id = :e AND session_type = 'RACE' AND ordinal = 3
                        """)
                .param("e", s.eventId()).query(String.class).single());
    }

    @Test
    void aWaitingGridCanBeDiscarded() throws Exception {
        Seeded s = seed("Bangsaen");
        long gridId = stage("2026_PCCA_Bangsaen Rounds 7&8_Grid_R8.pdf");
        assertEquals("WAITING", service.commit(gridId, target(s.eventId(), "RACE", 2, null)).status());
        service.discard(gridId);
        assertEquals("DISCARDED", service.get(gridId).status());
        // A later results commit doesn't bring it back.
        service.commit(stage("2026_PCCA_Bangsaen Rounds 7&8_Results_R7.pdf"), target(s.eventId(), "RACE", 1, null));
        assertEquals("DISCARDED", service.get(gridId).status());
    }

    @Test
    void aNumberedSessionMovesANamedOneAsideInsteadOfOverwritingIt() throws Exception {
        // Sepang as committed in production: Q1, then the combined sheet (which
        // took the next free ordinal, 2), then Q2 — which used to land on it.
        Seeded s = seed("Sepang");
        service.commit(stage("2026_PCCA_Sepang_Qualifying_1.pdf"), target(s.eventId(), "QUALIFYING", 1, null));
        service.commit(stage("2026_PCCA_Sepang_Qualifying_Combined.pdf"), target(s.eventId(), "QUALIFYING", 1, null));
        // The Q1 sample standing in for Q2: a numbered session claiming ordinal 2.
        service.commit(stage("2026_PCCA_Sepang_Qualifying_1.pdf"), target(s.eventId(), "QUALIFYING", 2, null));

        Map<String, Integer> byName = db.sql("""
                        SELECT name, ordinal FROM race_session
                        WHERE event_id = :e AND session_type = 'QUALIFYING'
                        """)
                .param("e", s.eventId())
                .query((rs, i) -> Map.entry(rs.getString(1), rs.getInt(2))).list()
                .stream().collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        assertEquals(Map.of("Qualifying", 1, "Qualifying 2", 2, "Qualifying Combined", 3), byName);
        assertEquals(29L, db.sql("""
                        SELECT count(*) FROM result r JOIN race_session rs ON rs.id = r.session_id
                        WHERE rs.event_id = :e AND rs.name = 'Qualifying Combined'
                        """)
                .param("e", s.eventId()).query(Long.class).single());
    }

    @Test
    void aClasslessGridNumbersCarsWithinTheirEntriesClasses() throws Exception {
        Seeded s = seed("Sepang");
        // Grid first: every class is a placeholder until the results land.
        long gridId = stage("2026_PCCA_Sepang_Grid_R11.pdf");
        service.commit(gridId, target(s.eventId(), "RACE", 3, null));
        service.commit(stage("2026_PCCA_Sepang_Results_R9.pdf"), target(s.eventId(), "RACE", 1, null));
        assertEquals("COMMITTED", service.get(gridId).status());

        Map<String, Integer> inClass = db.sql("""
                        SELECT e.car_number, g.position_in_class
                        FROM grid_position g JOIN entry e ON e.id = g.entry_id
                        JOIN race_session rs ON rs.id = g.session_id
                        WHERE rs.event_id = :e AND rs.ordinal = 3
                        """)
                .param("e", s.eventId())
                .query((rs, i) -> Map.entry(rs.getString(1), rs.getInt(2))).list()
                .stream().collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        assertEquals(1, inClass.get("66"));  // pole, Pro
        assertEquals(1, inClass.get("7"));   // 8th overall, first Pro-Am
        assertEquals(1, inClass.get("610")); // 11th overall, first Am
        assertEquals(3, inClass.get("25"));  // 14th overall, behind #610 and #91 in Am
        assertEquals(2, inClass.get("79"));  // 10th overall, second Pro-Am
    }

    private long gridSlots(long eventId) {
        return db.sql("""
                        SELECT count(*) FROM grid_position g JOIN race_session rs ON rs.id = g.session_id
                        WHERE rs.event_id = :e
                        """)
                .param("e", eventId).query(Long.class).single();
    }
}
