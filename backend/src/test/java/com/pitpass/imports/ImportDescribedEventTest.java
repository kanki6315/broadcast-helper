package com.pitpass.imports;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pitpass.imports.ImportService.ImportTarget;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A results/grid CSV carries no event metadata, so its review used to demand an
 * existing event — a dead end when the file arrives before anything else for
 * that weekend. Now the reviewer can describe the event (name, date, series;
 * circuit optional) and commit creates it. Runs against the local dev Postgres
 * like the other @SpringBootTest classes; every write rolls back with the test tx.
 */
@SpringBootTest
@Transactional
class ImportDescribedEventTest {

    @Autowired JdbcClient db;
    @Autowired ImportService service;
    @Autowired ObjectMapper json;

    @Test
    void resultsCsvCommitsIntoADescribedNewEvent() throws Exception {
        long seriesId = series();
        long batchId = stage("RACE_RESULTS", json.writeValueAsString(resultsCsv(List.of("5", "85"))));

        service.commit(batchId, describe(seriesId, null, "Grand Prix of Long Beach",
                LocalDate.of(2099, 4, 12), "Long Beach", "RACE", 1));

        assertEquals("COMMITTED", batchStatus(batchId));
        long seasonId = db.sql("SELECT id FROM season WHERE series_id = :s AND year = 2099 AND kind = 'MAIN'")
                .param("s", seriesId).query(Long.class).single();
        Map<String, Object> event = db.sql("""
                        SELECT name, circuit_name, event_date, round_ordinal FROM event WHERE season_id = :s
                        """).param("s", seasonId).query().singleRow();
        assertEquals("Grand Prix of Long Beach", event.get("name"));
        assertEquals("Long Beach", event.get("circuit_name"));
        assertEquals(LocalDate.of(2099, 4, 12), ((java.sql.Date) event.get("event_date")).toLocalDate());
        assertEquals(1, event.get("round_ordinal"));
        int results = db.sql("""
                        SELECT count(*) FROM result r JOIN race_session s ON s.id = r.session_id
                        JOIN event e ON e.id = s.event_id WHERE e.season_id = :s
                        """).param("s", seasonId).query(Integer.class).single();
        assertEquals(2, results);
    }

    @Test
    void gridCsvCommitsIntoADescribedNewEventInANewSeries() throws Exception {
        String seriesName = "Described series " + UUID.randomUUID();
        long batchId = stage("GRID", json.writeValueAsString(gridCsv(List.of("5"))));

        service.commit(batchId, describe(null, seriesName, "Round One",
                LocalDate.of(2099, 5, 1), null, "RACE", 2));

        assertEquals("COMMITTED", batchStatus(batchId));
        int events = db.sql("""
                        SELECT count(*) FROM event e JOIN season sn ON sn.id = e.season_id
                        JOIN series sr ON sr.id = sn.series_id WHERE sr.name = :n AND e.name = 'Round One'
                        """).param("n", seriesName).query(Integer.class).single();
        assertEquals(1, events);
    }

    @Test
    void describedEventNeedsNameDateAndSeries() throws Exception {
        long seriesId = series();
        long batchId = stage("RACE_RESULTS", json.writeValueAsString(resultsCsv(List.of("5"))));

        ResponseStatusException noName = assertThrows(ResponseStatusException.class, () ->
                service.commit(batchId, describe(seriesId, null, null, LocalDate.of(2099, 4, 12), null, "RACE", 1)));
        assertTrue(noName.getReason().contains("choose an existing event"), noName.getReason());
        ResponseStatusException noDate = assertThrows(ResponseStatusException.class, () ->
                service.commit(batchId, describe(seriesId, null, "Round", null, null, "RACE", 1)));
        assertTrue(noDate.getReason().contains("date"), noDate.getReason());
        ResponseStatusException noSeries = assertThrows(ResponseStatusException.class, () ->
                service.commit(batchId, describe(null, null, "Round", LocalDate.of(2099, 4, 12), null, "RACE", 1)));
        assertTrue(noSeries.getReason().contains("series"), noSeries.getReason());
        assertEquals("STAGED", batchStatus(batchId));
    }

    @Test
    void reviewChecksClassesAgainstTheDescribedSeason() throws Exception {
        long seriesId = series();
        long seasonId = db.sql("INSERT INTO season (series_id, year) VALUES (:s, 2099) RETURNING id")
                .param("s", seriesId).query(Long.class).single();
        long eventId = db.sql("INSERT INTO event (season_id, name) VALUES (:s, 'Earlier round') RETURNING id")
                .param("s", seasonId).query(Long.class).single();
        db.sql("INSERT INTO entry (event_id, car_number, class_name, team_name) VALUES (:e, '1', 'GTD', 'T')")
                .param("e", eventId).update();
        long batchId = stage("RACE_RESULTS", json.writeValueAsString(resultsCsv(List.of("5"))));

        // Without a season to check against, nothing is unknown…
        assertEquals(List.of(), service.reviewTarget(batchId, null, null, null).classReview().unknownClasses());
        // …but against the series+year the new event lands in, 'P' is.
        ImportService.ClassReview cr = service.reviewTarget(batchId, null, seriesId, 2099).classReview();
        assertEquals(List.of("GTD"), cr.knownClasses());
        assertEquals(List.of("P"), cr.unknownClasses());
    }

    // ----------------------------------------------------------------- helpers

    private long series() {
        return db.sql("INSERT INTO series (name) VALUES (:n) RETURNING id")
                .param("n", "Described event series " + UUID.randomUUID()).query(Long.class).single();
    }

    private long stage(String kind, String payload) {
        return db.sql("""
                        INSERT INTO import_batch (kind, format, filename, payload, summary)
                        VALUES (:kind, 'IMSA_CSV', 'described-event-test', :payload::jsonb, 'test batch')
                        RETURNING id
                        """)
                .param("kind", kind).param("payload", payload)
                .query(Long.class).single();
    }

    /** The CSV shape: no sessionStart, so every event fact comes from the target. */
    private static RaceResultsImport resultsCsv(List<String> carNumbers) {
        List<RaceResultsImport.Row> rows = new java.util.ArrayList<>();
        for (int i = 0; i < carNumbers.size(); i++) {
            rows.add(new RaceResultsImport.Row(i + 1, i + 1, carNumbers.get(i), "P", null,
                    "Team " + carNumbers.get(i), null, null, "Classified", false, null, 10, null, null, null,
                    null, null, null, null, null, List.of()));
        }
        return new RaceResultsImport(null, null, null, "RACE", 1, null, null, null, null, null, null, rows);
    }

    private static GridImport gridCsv(List<String> carNumbers) {
        List<GridImport.Row> rows = new java.util.ArrayList<>();
        for (int i = 0; i < carNumbers.size(); i++) {
            rows.add(new GridImport.Row(i + 1, i + 1, carNumbers.get(i), "P", null,
                    "Team " + carNumbers.get(i), null, null, null, null, null, null));
        }
        return new GridImport(null, null, null, null, 1, null, null, null, null, rows);
    }

    private static ImportTarget describe(Long seriesId, String newSeriesName, String eventName,
                                         LocalDate eventDate, String circuit,
                                         String sessionType, int ordinal) {
        return new ImportTarget(seriesId, newSeriesName, null, eventName, null, null, null, null, null,
                sessionType, ordinal, null, null, true, null, eventDate, circuit);
    }

    private String batchStatus(long batchId) {
        return db.sql("SELECT status FROM import_batch WHERE id = :id")
                .param("id", batchId).query(String.class).single();
    }
}
