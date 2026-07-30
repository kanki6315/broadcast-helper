package com.pitpass.imports;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pitpass.imports.ImportService.ImportTarget;
import com.pitpass.imports.ImportService.RosterDiff;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The file-vs-event roster diff and the allowNewEntries commit guard: a file
 * naming cars the event never entered is the fingerprint of an import targeted
 * at the wrong event (the mistake that once replaced CTMP's grid with Watkins
 * Glen's), so review surfaces the diff and commit refuses to fabricate entries
 * until the reviewer acknowledges it. Runs against the local dev Postgres like
 * the other @SpringBootTest classes; every write rolls back with the test tx.
 */
@SpringBootTest
@Transactional
class ImportRosterGuardTest {

    @Autowired JdbcClient db;
    @Autowired ImportService service;
    @Autowired ObjectMapper json;

    // ------------------------------------------------------------- review diff

    @Test
    void reviewReportsNewAndMissingCarsAgainstChosenEvent() throws Exception {
        Seeded s = seedEvent();
        long batchId = stageGrid(gridCsv(List.of("5", "99")));

        RosterDiff diff = service.reviewTarget(batchId, s.eventId(), null).rosterDiff();

        assertNotNull(diff);
        assertEquals(List.of("99"), diff.newCars().stream().map(ImportService.CarRef::number).toList());
        assertEquals(List.of("85"), diff.missingCars().stream().map(ImportService.CarRef::number).toList());
        assertEquals(2, diff.eventEntryCount());
    }

    @Test
    void reviewDiffNullForEmptyEventAndNoEffectiveEvent() throws Exception {
        Seeded s = seedEvent();
        long emptyEventId = db.sql("INSERT INTO event (season_id, name) VALUES (:s, 'Empty round') RETURNING id")
                .param("s", s.seasonId()).query(Long.class).single();
        long batchId = stageGrid(gridCsv(List.of("5", "99")));

        // A first import has no roster to disagree with.
        assertNull(service.reviewTarget(batchId, emptyEventId, null).rosterDiff());
        // A grid CSV guesses no event, and none was chosen: nothing to diff.
        assertNull(service.reviewTarget(batchId, null, null).rosterDiff());
    }

    /** Raw comparison on purpose: entry uniqueness is the raw car_number
     *  ("04" and "4" are distinct cars per V2), so a leading-zero variant is
     *  truthfully reported as a car the commit would create. */
    @Test
    void reviewDiffUsesRawCarNumbers() throws Exception {
        Seeded s = seedEvent();
        entry(s.eventId(), "04", "Zero Pad Racing");
        long batchId = stageGrid(gridCsv(List.of("4", "5", "85")));

        RosterDiff diff = service.reviewTarget(batchId, s.eventId(), null).rosterDiff();

        assertNotNull(diff);
        assertEquals(List.of("4"), diff.newCars().stream().map(ImportService.CarRef::number).toList());
        assertEquals(List.of("04"), diff.missingCars().stream().map(ImportService.CarRef::number).toList());
    }

    // ------------------------------------------------------------ commit guard

    @Test
    void commitGridRejectsNewCarsWithoutAck() throws Exception {
        Seeded s = seedEvent();
        long batchId = stageGrid(gridCsv(List.of("5", "99")));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.commit(batchId, gridTarget(s.eventId(), null)));

        assertEquals(422, ex.getStatusCode().value());
        assertNotNull(ex.getReason());
        assertTrue(ex.getReason().contains("#99"), ex.getReason());
        assertEquals(0, entryCount(s.eventId(), "99"));
        assertEquals("STAGED", batchStatus(batchId));
    }

    @Test
    void commitGridAllowsNewCarsWithAck() throws Exception {
        Seeded s = seedEvent();
        long batchId = stageGrid(gridCsv(List.of("5", "99")));

        service.commit(batchId, gridTarget(s.eventId(), true));

        assertEquals(1, entryCount(s.eventId(), "99"));
        Integer gridRows = db.sql("""
                        SELECT count(*) FROM grid_position g
                                 JOIN race_session rs ON rs.id = g.session_id
                        WHERE rs.event_id = :eventId
                        """)
                .param("eventId", s.eventId()).query(Integer.class).single();
        assertEquals(2, gridRows);
        assertEquals("COMMITTED", batchStatus(batchId));
    }

    @Test
    void commitIntoEmptyEventNeedsNoAck() throws Exception {
        Seeded s = seedEvent();
        long emptyEventId = db.sql("INSERT INTO event (season_id, name) VALUES (:s, 'Empty round') RETURNING id")
                .param("s", s.seasonId()).query(Long.class).single();
        long batchId = stageGrid(gridCsv(List.of("5", "99")));

        service.commit(batchId, gridTarget(emptyEventId, null));

        assertEquals(1, entryCount(emptyEventId, "99"));
        assertEquals("COMMITTED", batchStatus(batchId));
    }

    @Test
    void commitMatchingOrShrunkenRosterNeedsNoAck() throws Exception {
        Seeded s = seedEvent();
        long batchId = stageGrid(gridCsv(List.of("5"))); // subset: #85 withdrew

        service.commit(batchId, gridTarget(s.eventId(), null));

        assertEquals("COMMITTED", batchStatus(batchId));
    }

    @Test
    void commitRaceResultsRejectsNewCarsWithoutAck() throws Exception {
        Seeded s = seedEvent();
        RaceResultsImport imp = new RaceResultsImport(null, null, null, "RACE", 1, null, null,
                null, null, null, null,
                List.of(resultRow("5", 1), resultRow("99", 2)));
        long batchId = stage("RACE_RESULTS", json.writeValueAsString(imp));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.commit(batchId, target(null, s.eventId(), "RACE", 1, null)));

        assertEquals(422, ex.getStatusCode().value());
        assertNotNull(ex.getReason());
        assertTrue(ex.getReason().contains("#99"), ex.getReason());
        assertEquals(0, entryCount(s.eventId(), "99"));
    }

    @Test
    void commitEntryListRejectsNewCarsWithoutAck() throws Exception {
        Seeded s = seedEvent();
        EntryListImport imp = new EntryListImport(
                new EntryListImport.Event("Round", "Test Circuit", null, null,
                        LocalDate.of(2099, 6, 1), null, null, null),
                List.of(entryListEntry("5"), entryListEntry("99")));
        long batchId = stage("ENTRY_LIST", json.writeValueAsString(imp));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.commit(batchId, target(s.seriesId(), s.eventId(), null, null, null)));

        assertEquals(422, ex.getStatusCode().value());
        assertNotNull(ex.getReason());
        assertTrue(ex.getReason().contains("entry list"), ex.getReason());
        assertTrue(ex.getReason().contains("#99"), ex.getReason());
        assertEquals(0, entryCount(s.eventId(), "99"));

        // The same list commits once acknowledged.
        service.commit(batchId, target(s.seriesId(), s.eventId(), null, null, true));
        assertEquals(1, entryCount(s.eventId(), "99"));
    }

    // ----------------------------------------------------------------- helpers

    private record Seeded(long seriesId, long seasonId, long eventId) {
    }

    /** A season with one event entered by cars #5 and #85, class 'P'. */
    private Seeded seedEvent() {
        String suffix = UUID.randomUUID().toString();
        long seriesId = db.sql("INSERT INTO series (name) VALUES (:n) RETURNING id")
                .param("n", "Roster guard series " + suffix).query(Long.class).single();
        long seasonId = db.sql("INSERT INTO season (series_id, year) VALUES (:s, 2099) RETURNING id")
                .param("s", seriesId).query(Long.class).single();
        long eventId = db.sql("INSERT INTO event (season_id, name) VALUES (:s, 'Round') RETURNING id")
                .param("s", seasonId).query(Long.class).single();
        entry(eventId, "5", "Team Five");
        entry(eventId, "85", "Team Eighty-Five");
        return new Seeded(seriesId, seasonId, eventId);
    }

    private void entry(long eventId, String number, String teamName) {
        db.sql("""
                        INSERT INTO entry (event_id, car_number, class_name, team_name)
                        VALUES (:event, :number, 'P', :team)
                        """)
                .param("event", eventId).param("number", number).param("team", teamName)
                .update();
    }

    private long stage(String kind, String payload) {
        return db.sql("""
                        INSERT INTO import_batch (kind, format, filename, payload, summary)
                        VALUES (:kind, 'IMSA_CSV', 'roster-guard-test', :payload::jsonb, 'test batch')
                        RETURNING id
                        """)
                .param("kind", kind).param("payload", payload)
                .query(Long.class).single();
    }

    private long stageGrid(GridImport imp) throws Exception {
        return stage("GRID", json.writeValueAsString(imp));
    }

    /** A metadata-less grid (the CSV shape): no sessionStart, so commit takes
     *  the event and session from the target — the mis-targetable path. */
    private static GridImport gridCsv(List<String> carNumbers) {
        List<GridImport.Row> rows = new java.util.ArrayList<>();
        for (int i = 0; i < carNumbers.size(); i++) {
            rows.add(new GridImport.Row(i + 1, i + 1, carNumbers.get(i), "P", null,
                    "Team " + carNumbers.get(i), null, null, null, null, null, null));
        }
        return new GridImport(null, null, null, null, 1, null, null, null, null, rows);
    }

    private static RaceResultsImport.Row resultRow(String number, int position) {
        return new RaceResultsImport.Row(position, position, number, "P", null,
                "Team " + number, null, null, "Classified", false, null, 10, null, null, null,
                null, null, null, null, null, List.of());
    }

    private static EntryListImport.Entry entryListEntry(String number) {
        return new EntryListImport.Entry("Prototype", "P", null, number, "Team " + number,
                null, null, false, false, null, null, null, null, List.of());
    }

    private static ImportTarget target(Long seriesId, Long eventId, String sessionType,
                                       Integer sessionOrdinal, Boolean allowNewEntries) {
        return new ImportTarget(seriesId, null, eventId, null, null, null, null, null, null,
                sessionType, sessionOrdinal, null, null, allowNewEntries);
    }

    private static ImportTarget gridTarget(long eventId, Boolean allowNewEntries) {
        return target(null, eventId, "RACE", 1, allowNewEntries);
    }

    private int entryCount(long eventId, String carNumber) {
        return db.sql("SELECT count(*) FROM entry WHERE event_id = :event AND car_number = :number")
                .param("event", eventId).param("number", carNumber)
                .query(Integer.class).single();
    }

    private String batchStatus(long batchId) {
        return db.sql("SELECT status FROM import_batch WHERE id = :id")
                .param("id", batchId).query(String.class).single();
    }
}
