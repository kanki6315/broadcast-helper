package com.pitpass.documents;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pitpass.sheets.SheetController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@Transactional
class PitAssignmentControllerTest {

    @Autowired JdbcClient db;
    @Autowired PitAssignmentController controller;
    @Autowired SheetController sheetController;
    @Autowired ObjectMapper json;

    private long eventId() {
        long seriesId = db.sql("INSERT INTO series (name) VALUES (:n) RETURNING id")
                .param("n", "Pit series " + UUID.randomUUID()).query(Long.class).single();
        long seasonId = db.sql("INSERT INTO season (series_id, year) VALUES (:s, 2099) RETURNING id")
                .param("s", seriesId).query(Long.class).single();
        return db.sql("INSERT INTO event (season_id, name) VALUES (:s, 'Round') RETURNING id")
                .param("s", seasonId).query(Long.class).single();
    }

    private long entry(long eventId, String carNumber, String className, String teamName) {
        return db.sql("""
                        INSERT INTO entry (event_id, car_number, class_name, team_name)
                        VALUES (:e, :n, :c, :t) RETURNING id
                        """)
                .param("e", eventId).param("n", carNumber).param("c", className).param("t", teamName)
                .query(Long.class).single();
    }

    /** The stored PDF that upload would have written; PUT requires it. */
    private void document(long eventId, String note) {
        db.sql("""
                        INSERT INTO event_document (event_id, kind, source_filename, content_type, data, note)
                        VALUES (:e, 'PIT_ASSIGNMENTS', 'assignments.pdf', 'application/pdf', :d, :note)
                        """)
                .param("e", eventId).param("d", new byte[]{'%', 'P', 'D', 'F'}).param("note", note)
                .update();
    }

    private PitAssignmentController.Proposal propose(long eventId, String parsedJson) throws Exception {
        return controller.propose(eventId, json.readTree(parsedJson), "V3");
    }

    // ------------------------------------------------------------- proposals

    @Test
    void proposePicksTheColumnMatchingTheEventsEntries() throws Exception {
        long event = eventId();
        long cadillac = entry(event, "31", "GTP", "Cadillac Whelen");
        entry(event, "13", "LMP2", "13 Autosport");

        var proposal = propose(event, """
                {
                  "series": ["IWSC", "IMPC"],
                  "boxes": [
                    {"box": 1, "cars": {"IWSC": {"car_number": "31", "team": "Cadillac Whelen"},
                                        "IMPC": {"car_number": "98", "team": "Bryan Herta Autosport"}}},
                    {"box": 2, "cars": {"IMPC": {"car_number": "44", "team": "Ibiza Farm Motorsport"}}},
                    {"box": 3, "cars": {"IWSC": {"car_number": "13", "team": "13 Autosport"}}},
                    {"box": 4, "cars": {"IWSC": {"car_number": "99", "team": "Unknown Racing"}}}
                  ],
                  "landmarks": [{"after_box": 0, "label": "PENALTY BOX | MICHELIN"}]
                }
                """);

        assertEquals("IWSC", proposal.seriesColumn());
        assertEquals(Map.of("IWSC", 2, "IMPC", 0), proposal.matchCounts());
        // Only the winning column's rows survive, in box order.
        assertEquals(List.of(1, 3, 4), proposal.rows().stream().map(r -> r.boxNumber()).toList());
        assertEquals(cadillac, proposal.rows().getFirst().entryId());
        assertEquals("GTP", proposal.rows().getFirst().className());
        // Car 99 is nobody in this event: proposed but unmatched.
        assertNull(proposal.rows().getLast().entryId());
        assertEquals("V3", proposal.versionNote());
        assertEquals(List.of(new PitAssignmentController.Landmark(0, "PENALTY BOX | MICHELIN")),
                proposal.landmarks());
    }

    @Test
    void proposeBreaksNormalizedNumberCollisionsOnTeamName() throws Exception {
        long event = eventId();
        entry(event, "04", "GTD", "Crowdstrike Racing by APR");
        long plainFour = entry(event, "4", "GTP", "Corvette Racing by Pratt Miller Motorsports");

        var proposal = propose(event, """
                {
                  "series": ["IWSC"],
                  "boxes": [{"box": 5, "cars": {"IWSC": {"car_number": "4",
                                                          "team": "Corvette Racing by Pratt Miller Motorsports"}}}],
                  "landmarks": []
                }
                """);
        assertEquals(plainFour, proposal.rows().getFirst().entryId());
    }

    @Test
    void proposeWithNoMatchingColumnIsUnprocessable() {
        long event = eventId();
        entry(event, "31", "GTP", "Cadillac Whelen");
        ResponseStatusException e = assertThrows(ResponseStatusException.class, () -> propose(event, """
                {"series": ["LST"], "boxes": [{"box": 1, "cars": {"LST": {"car_number": "63", "team": "TR3"}}}],
                 "landmarks": []}
                """));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, e.getStatusCode());
    }

    // ------------------------------------------------------- save / get / delete

    @Test
    void saveRoundtripsRowsAndLandmarksAndLightsUpTheSheet() {
        long event = eventId();
        long cadillac = entry(event, "31", "GTP", "Cadillac Whelen");
        document(event, "7/28/26 · VERSION 3");

        assertNull(sheetController.sheet(event).pitAssignmentsVersion(),
                "an unconfirmed upload must not light the sheet button");

        controller.save(event, new PitAssignmentController.SaveRequest(
                List.of(new PitAssignmentController.SaveRow(1, "31", "Cadillac Whelen", cadillac),
                        new PitAssignmentController.SaveRow(4, "99", "Mystery Team", null)),
                List.of(new PitAssignmentController.Landmark(0, "PIT OUT"),
                        new PitAssignmentController.Landmark(0, "PENALTY BOX"),
                        new PitAssignmentController.Landmark(2, "S / F"))));

        var saved = controller.get(event);
        assertEquals("7/28/26 · VERSION 3", saved.versionNote());
        assertEquals("assignments.pdf", saved.filename());
        assertEquals(2, saved.rows().size());
        assertEquals("Cadillac Whelen", saved.rows().getFirst().entryTeam());
        assertEquals("GTP", saved.rows().getFirst().className());
        assertNull(saved.rows().getLast().entryId());
        // Landmarks keep PDF order, not label order, within the same after_box.
        assertEquals(List.of("PIT OUT", "PENALTY BOX", "S / F"),
                saved.landmarks().stream().map(PitAssignmentController.Landmark::label).toList());

        assertNotNull(sheetController.sheet(event).pitAssignmentsVersion());
    }

    @Test
    void saveReplacesWholesale() {
        long event = eventId();
        document(event, null);
        controller.save(event, new PitAssignmentController.SaveRequest(
                List.of(new PitAssignmentController.SaveRow(1, "31", null, null)),
                List.of(new PitAssignmentController.Landmark(0, "PIT OUT"))));
        controller.save(event, new PitAssignmentController.SaveRequest(
                List.of(new PitAssignmentController.SaveRow(2, "45", null, null)), List.of()));

        var saved = controller.get(event);
        assertEquals(1, saved.rows().size());
        assertEquals(2, saved.rows().getFirst().boxNumber());
        assertTrue(saved.landmarks().isEmpty());
    }

    @Test
    void saveValidatesRows() {
        long event = eventId();
        long other = eventId();
        long foreign = entry(other, "1", "GTP", "Elsewhere");
        document(event, null);

        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, assertThrows(ResponseStatusException.class,
                () -> controller.save(event, new PitAssignmentController.SaveRequest(
                        List.of(new PitAssignmentController.SaveRow(1, "31", null, null),
                                new PitAssignmentController.SaveRow(1, "45", null, null)),
                        List.of()))).getStatusCode());

        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, assertThrows(ResponseStatusException.class,
                () -> controller.save(event, new PitAssignmentController.SaveRequest(
                        List.of(new PitAssignmentController.SaveRow(1, " ", null, null)), List.of())))
                .getStatusCode());

        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, assertThrows(ResponseStatusException.class,
                () -> controller.save(event, new PitAssignmentController.SaveRequest(
                        List.of(new PitAssignmentController.SaveRow(1, "31", null, foreign)), List.of())))
                .getStatusCode());
    }

    @Test
    void saveWithoutAnUploadedPdfIs404() {
        long event = eventId();
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> controller.save(event, new PitAssignmentController.SaveRequest(List.of(), List.of())));
        assertEquals(HttpStatus.NOT_FOUND, e.getStatusCode());
    }

    @Test
    void deleteRemovesDocumentAndRows() {
        long event = eventId();
        document(event, null);
        controller.save(event, new PitAssignmentController.SaveRequest(
                List.of(new PitAssignmentController.SaveRow(1, "31", null, null)), List.of()));

        controller.delete(event);
        assertEquals(HttpStatus.NOT_FOUND,
                assertThrows(ResponseStatusException.class, () -> controller.get(event)).getStatusCode());
        assertEquals(0, db.sql("SELECT count(*) FROM pit_box_assignment WHERE event_id = :e")
                .param("e", event).query(Long.class).single());
        assertEquals(HttpStatus.NOT_FOUND,
                assertThrows(ResponseStatusException.class, () -> controller.delete(event)).getStatusCode());
    }

    @Test
    void unknownEventIs404() {
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> controller.save(-1, new PitAssignmentController.SaveRequest(List.of(), List.of())));
        assertEquals(HttpStatus.NOT_FOUND, e.getStatusCode());
    }
}
