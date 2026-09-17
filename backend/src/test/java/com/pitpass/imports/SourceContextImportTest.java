package com.pitpass.imports;

import com.pitpass.imports.ImportService.BatchSummary;
import com.pitpass.imports.ImportService.GroupBatch;
import com.pitpass.imports.ImportService.GroupCommitRequest;
import com.pitpass.imports.ImportService.GroupCommitResult;
import com.pitpass.imports.ImportService.ImportReview;
import com.pitpass.imports.ImportService.ImportTarget;
import com.pitpass.imports.ImportService.ProposedEvent;
import com.pitpass.imports.ImportService.Staged;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A results CSV fetched from the Al Kamel site knows its series, event, date
 * and session from the folders it sat in ({@link SourceContext}). With that
 * context it reviews and group-commits like a timing JSON — no reviewer picking
 * a session — the event it creates is stamped with the folder key, and the next
 * file from the same folder finds that event by the stamp rather than by a
 * circuit the CSV never names. Runs against the local dev Postgres like the
 * other @SpringBootTest classes; every write rolls back.
 */
@SpringBootTest
@Transactional
class SourceContextImportTest {

    private static final String FOLDER = "21_2021/08_Mid-Ohio Sports Car Course";
    private static final String QUALI_FILE = "03_Results_Qualifying - GTD Position.CSV";

    @Autowired JdbcClient db;
    @Autowired ImportService service;

    @Test
    void aFetchedCsvPlacesItselfAndStampsItsEvent() throws IOException {
        String seriesName = "Context series " + UUID.randomUUID();
        long seriesId = db.sql("INSERT INTO series (name) VALUES (:n) RETURNING id")
                .param("n", seriesName).query(Long.class).single();
        SourceContext ctx = new SourceContext(
                "https://example.test/Results/21_2021/x/" + QUALI_FILE, "2021-05-15 18:39", FOLDER, 2021,
                seriesName, "Mid-Ohio Sports Car Course",
                LocalDateTime.of(2021, 5, 15, 12, 20), "Qualifying - GTD Position");

        List<BatchSummary> staged = service.stage(FOLDER + "/x/" + QUALI_FILE, fixture(QUALI_FILE),
                ImportFormat.IMSA_CSV, ctx);
        assertEquals(1, staged.size());
        BatchSummary batch = staged.get(0);
        assertEquals(FOLDER, batch.sourceEvent());
        assertEquals(ctx.sourceUrl(), batch.sourceUrl());

        // Review: the context made it a self-placing batch.
        ImportReview review = service.reviewTarget(batch.id(), null, null, null);
        assertFalse(review.needsSession(), "session comes from the folder, not the reviewer");
        assertEquals(seriesId, review.guess().seriesId());
        assertEquals("Mid-Ohio Sports Car Course", review.guess().eventName());
        assertEquals("2021-05-15", review.guess().eventDate());
        assertNull(review.guess().eventId(), "nothing to attach to yet");

        // The race from the same folder, staged before anything commits — the
        // qualifying lists one class (GTD), the race four; in one group the
        // empty season is seeded by the group as a whole, not by whichever
        // batch happens to commit first.
        SourceContext raceCtx = new SourceContext(
                "https://example.test/Results/21_2021/x/03_Results_Race_Official.CSV", "2021-05-19 17:35", FOLDER,
                2021, seriesName, "Mid-Ohio Sports Car Course", LocalDateTime.of(2021, 5, 16, 14, 40), "Race");
        BatchSummary race = service.stage(FOLDER + "/x/03_Results_Race_Official.CSV",
                fixture("03_Results_Race_Official.CSV"), ImportFormat.IMSA_CSV, raceCtx).get(0);

        // Group-commit into a new event, the way the confirm step does.
        ImportTarget target = new ImportTarget(seriesId, null, null, null, null, null, null, null, null,
                null, null, null, null, true, null, null, null);
        GroupCommitResult result = service.commitGroup(new GroupCommitRequest(
                List.of(new ProposedEvent("e1", null, "Mid-Ohio Sports Car Course", "2021-05-15")),
                List.of(new GroupBatch(batch.id(), "e1", target), new GroupBatch(race.id(), "e1", target))));
        assertEquals(2, result.committed(), result.results().toString());

        Map<String, Object> event = db.sql("""
                        SELECT e.id, e.event_date, e.source_ref, e.round_ordinal, e.circuit_name
                        FROM event e JOIN season s ON s.id = e.season_id
                        WHERE s.series_id = :series AND s.year = 2021 AND e.name = 'Mid-Ohio Sports Car Course'
                        """).param("series", seriesId).query().singleRow();
        assertEquals(LocalDate.of(2021, 5, 15), ((java.sql.Date) event.get("event_date")).toLocalDate());
        assertEquals(FOLDER, event.get("source_ref"));
        assertEquals(1, event.get("round_ordinal"));
        assertNull(event.get("circuit_name"), "a CSV names no circuit and the folder is not one");
        long eventId = (Long) event.get("id");

        Map<String, Object> session = db.sql("""
                        SELECT name, session_type, ordinal, session_start FROM race_session
                        WHERE event_id = :e AND session_type = 'QUALIFYING'
                        """)
                .param("e", eventId).query().singleRow();
        assertEquals("Qualifying - GTD Position", session.get("name"));
        assertNotNull(session.get("session_start"));
        int results = db.sql("SELECT count(*) FROM result r JOIN race_session s ON s.id = r.session_id WHERE s.event_id = :e")
                .param("e", eventId).query(Integer.class).single();
        assertEquals(13 + 25, results, "qualifying and race rows both landed");
        int classes = db.sql("SELECT count(DISTINCT class_name) FROM entry WHERE event_id = :e")
                .param("e", eventId).query(Integer.class).single();
        assertTrue(classes > 1, "the race's other classes were accepted, not rejected against GTD");

        // The next file from the same folder finds the event by its stamp.
        SourceContext laterCtx = new SourceContext(
                "https://example.test/Results/21_2021/x/03_Results_Qualifying - GTD Points.CSV", "2021-05-15 19:52",
                FOLDER, 2021, seriesName, "Mid-Ohio Sports Car Course", LocalDateTime.of(2021, 5, 15, 12, 45),
                "Qualifying - GTD Points");
        BatchSummary later = service.stage(FOLDER + "/x/03_Results_Qualifying - GTD Points.CSV",
                fixture("03_Results_Qualifying - GTD Points.CSV"), ImportFormat.IMSA_CSV, laterCtx).get(0);
        ImportReview laterReview = service.reviewTarget(later.id(), null, null, null);
        assertEquals(eventId, laterReview.guess().eventId());
        assertFalse(laterReview.needsSession());
    }

    @Test
    void uploadsRecordNoSource() throws IOException {
        BatchSummary batch = service.stage(QUALI_FILE, fixture(QUALI_FILE), ImportFormat.IMSA_CSV).get(0);
        assertNull(batch.sourceEvent());
        assertNull(batch.sourceUrl());
        assertEquals(true, service.reviewTarget(batch.id(), null, null, null).needsSession());
    }

    @Test
    void contextFillsOnlyWhatThePayloadLeavesBlank() {
        SourceContext ctx = new SourceContext("u", "m", FOLDER, 2017, "Series X", "Long Beach Street Circuit",
                LocalDateTime.of(2017, 4, 8, 13, 5), "Race 2");

        // A grid sheet names no session at all: label and its ordinal come from the folder.
        GridImport bareGrid = new GridImport(null, null, null, null, 1, null, null, null, null, List.of());
        GridImport grid = (GridImport) ImportService.applyContext(
                new Staged("GRID", bareGrid, ""), ImportFormat.IMSA_GRID_PDF, ctx).payload();
        assertEquals("Series X", grid.championshipName());
        assertEquals("Long Beach Street Circuit", grid.eventName());
        assertEquals("Race 2", grid.sessionName());
        assertEquals(2, grid.sessionOrdinal());
        assertEquals(LocalDateTime.of(2017, 4, 8, 13, 5), grid.sessionStart());

        // A CSV named by its file keeps that name (and ordinal) over the folder label.
        RaceResultsImport named = new RaceResultsImport(null, null, "Qualifying - GTD Points", "QUALIFYING", 1,
                null, null, null, null, null, null, List.of());
        RaceResultsImport results = (RaceResultsImport) ImportService.applyContext(
                new Staged("RACE_RESULTS", named, ""), ImportFormat.IMSA_CSV, ctx).payload();
        assertEquals("Qualifying - GTD Points", results.sessionName());
        assertEquals(1, results.sessionOrdinal());
        assertEquals("Series X", results.championshipName());

        // A timing JSON that names everything is untouched.
        RaceResultsImport full = new RaceResultsImport("Real series", "Real event", "Race", "RACE", 1, null, null,
                LocalDateTime.of(2017, 4, 8, 13, 0), "Circuit", 3.0, "USA", List.of());
        assertEquals(full, ImportService.applyContext(
                new Staged("RACE_RESULTS", full, ""), ImportFormat.IMSA_JSON, ctx).payload());

        // A points PDF takes the folder's year; a standings JSON keeps its own.
        StandingsImport st = new StandingsImport("n", "Title", "", "2026", List.of(), List.of());
        assertEquals("2017", ((StandingsImport) ImportService.applyContext(
                new Staged("STANDINGS", st, ""), ImportFormat.IMSA_POINTS_PDF, ctx).payload()).year());
        assertEquals("2026", ((StandingsImport) ImportService.applyContext(
                new Staged("STANDINGS", st, ""), ImportFormat.IMSA_JSON, ctx).payload()).year());
    }

    private static byte[] fixture(String name) throws IOException {
        try (InputStream in = SourceContextImportTest.class.getClassLoader()
                .getResourceAsStream("fixtures/imsa-2021/" + name)) {
            if (in == null) {
                throw new IOException("Missing fixture " + name);
            }
            return in.readAllBytes();
        }
    }
}
