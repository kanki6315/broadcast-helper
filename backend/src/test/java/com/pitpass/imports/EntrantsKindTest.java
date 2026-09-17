package com.pitpass.imports;

import com.pitpass.imports.ImportService.ImportReview;
import com.pitpass.imports.ImportService.ImportTarget;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * "Entrants" is not a kind of championship, it is what some series call the
 * TEAMS kind — Mustang Challenge and Carrera Cup North America both rank
 * "Entrants". A sheet titled that way reviews as TEAMS with no class, commits
 * without the reviewer re-typing the kind, and the new group takes the
 * sheet's wording so the season pages say "Entrants" like the series does.
 * Runs against the local dev Postgres like the other @SpringBootTest classes;
 * every write rolls back.
 */
@SpringBootTest
@Transactional
class EntrantsKindTest {

    @Autowired JdbcClient db;
    @Autowired ImportService service;
    @Autowired com.fasterxml.jackson.databind.ObjectMapper json;

    @Test
    void anEntrantsSheetIsTheTeamsKindWordedEntrants() throws Exception {
        String seriesName = "Entrants series " + UUID.randomUUID();
        long seriesId = db.sql("INSERT INTO series (name) VALUES (:n) RETURNING id")
                .param("n", seriesName).query(Long.class).single();
        StandingsImport sheet = new StandingsImport(seriesName + " Entrants", seriesName + " Entrants", "", "2025",
                List.of(new StandingsImport.SessionRef(1, "Sebring", "Round 1")),
                List.of(new StandingsImport.Row(1, "Topp Racing", "", 42, null, null, null,
                        List.of(new StandingsImport.SessionPoints(1, 42, 42, 0, 0, 0, 0, "")))));
        long batchId = db.sql("""
                        INSERT INTO import_batch (kind, format, filename, payload, summary)
                        VALUES ('STANDINGS', 'IMSA_POINTS_PDF', 'entrants-test.pdf', :payload::jsonb, 'test')
                        RETURNING id
                        """)
                .param("payload", json.writeValueAsString(sheet)).query(Long.class).single();

        ImportReview review = service.reviewTarget(batchId, null, null, null);
        assertEquals("TEAMS", review.guess().kind());
        assertNull(review.guess().classCode(), "an entrants table spans the classes");
        assertEquals(2025, review.guess().seasonYear());

        service.commit(batchId, new ImportTarget(seriesId, null, null, null, null, "TEAMS", false, null, 2025,
                null, null, null, null, null, null, null, null));

        Map<String, Object> group = db.sql("""
                        SELECT g.kind, g.kind_label, g.label FROM championship_group g
                        JOIN season s ON s.id = g.season_id WHERE s.series_id = :s AND s.year = 2025
                        """).param("s", seriesId).query().singleRow();
        assertEquals("TEAMS", group.get("kind"));
        assertEquals("Entrants", group.get("kind_label"));
        assertEquals(seriesName + " — Entrants", group.get("label"));
    }

    @Test
    void aSeriesOnlyTitleTakesClassAndKindFromTheSubtitle() throws Exception {
        // Carrera Cup NA's points JSON: main_title is the series alone, the
        // championship lives in the subtitle.
        String seriesName = "Subtitle series " + UUID.randomUUID();
        db.sql("INSERT INTO series (name) VALUES (:n)").param("n", seriesName).update();
        for (String[] c : new String[][] {
                {"Pro Drivers - Championship Points Standings", "Pro", "DRIVERS"},
                {"Pro-Am Drivers - Championship Points Standings", "Pro-Am", "DRIVERS"},
                {"Entrants - Championship Points Standings", null, "TEAMS"}}) {
            StandingsImport sheet = new StandingsImport("PCCNA x", seriesName, c[0], "2024", List.of(), List.of());
            long batchId = db.sql("""
                            INSERT INTO import_batch (kind, format, filename, payload, summary)
                            VALUES ('STANDINGS', 'IMSA_JSON', 'subtitle-test.json', :payload::jsonb, 'test')
                            RETURNING id
                            """)
                    .param("payload", json.writeValueAsString(sheet)).query(Long.class).single();
            ImportReview review = service.reviewTarget(batchId, null, null, null);
            assertEquals(c[1], review.guess().classCode(), c[0]);
            assertEquals(c[2], review.guess().kind(), c[0]);
        }
    }

    @Test
    void kindWordsAreReadAsTheClosedSet() {
        assertEquals("TEAMS", ImportService.canonicalKind("Entrants"));
        assertEquals("TEAMS", ImportService.canonicalKind("Teams"));
        assertEquals("DRIVERS", ImportService.canonicalKind("Drivers"));
        assertEquals("MANUFACTURERS", ImportService.canonicalKind("Manufacturers"));
        assertEquals("ROOKIES", ImportService.canonicalKind("Rookies"), "an unknown word is left for the reviewer");
        assertEquals("Entrants", ImportService.sheetKindWording("Mustang Challenge DH Entrants", "TEAMS"));
        assertNull(ImportService.sheetKindWording("IMSA WeatherTech SportsCar Championship GTP Teams", "TEAMS"));
        assertNull(ImportService.sheetKindWording("Some Cup Drivers", "TEAMS"), "the word must be that kind's");
    }
}
