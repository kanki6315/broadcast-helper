package com.pitpass.documents;

import com.pitpass.sheets.SheetController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest
@Transactional
class StorylineControllerTest {

    @Autowired JdbcClient db;
    @Autowired StorylineController controller;
    @Autowired SheetController sheetController;

    private long eventId() {
        long seriesId = db.sql("INSERT INTO series (name) VALUES (:n) RETURNING id")
                .param("n", "Storyline series " + UUID.randomUUID()).query(Long.class).single();
        long seasonId = db.sql("INSERT INTO season (series_id, year) VALUES (:s, 2099) RETURNING id")
                .param("s", seriesId).query(Long.class).single();
        return db.sql("INSERT INTO event (season_id, name) VALUES (:s, 'Round') RETURNING id")
                .param("s", seasonId).query(Long.class).single();
    }

    private static MockMultipartFile pdf(String filename, byte[] body) {
        return new MockMultipartFile("file", filename, "application/pdf", body);
    }

    @Test
    void uploadRoundtripsAndLightsUpTheSheet() {
        long event = eventId();
        assertNull(sheetController.sheet(event).storylinesVersion());

        var uploaded = controller.upload(event, pdf("storylines.pdf", new byte[]{'%', 'P', 'D', 'F', '-'}));
        assertEquals("storylines.pdf", uploaded.filename());
        assertNotNull(uploaded.uploadedAt());

        assertEquals(uploaded, controller.get(event));
        assertArrayEquals(new byte[]{'%', 'P', 'D', 'F', '-'}, controller.data(event, null).getBody());
        assertEquals(uploaded.version(), sheetController.sheet(event).storylinesVersion());
    }

    @Test
    void reuploadReplacesInPlace() {
        long event = eventId();
        controller.upload(event, pdf("v1.pdf", new byte[]{'%', 'P', 'D', 'F', '1'}));
        controller.upload(event, pdf("v2.pdf", new byte[]{'%', 'P', 'D', 'F', '2'}));

        assertEquals("v2.pdf", controller.get(event).filename());
        assertArrayEquals(new byte[]{'%', 'P', 'D', 'F', '2'}, controller.data(event, null).getBody());
        assertEquals(1, db.sql("SELECT count(*) FROM event_document WHERE event_id = :e AND kind = 'STORYLINES'")
                .param("e", event).query(Long.class).single());
    }

    @Test
    void uploadKeepsTeamSheetsSeparate() {
        long event = eventId();
        db.sql("""
                        INSERT INTO event_document (event_id, kind, source_filename, content_type, data)
                        VALUES (:e, 'TEAM_SHEETS', 'teams.pdf', 'application/pdf', :d)
                        """)
                .param("e", event).param("d", new byte[]{'%', 'P', 'D', 'F'}).update();

        controller.upload(event, pdf("storylines.pdf", new byte[]{'%', 'P', 'D', 'F', '-'}));
        controller.delete(event);

        assertEquals(1, db.sql("SELECT count(*) FROM event_document WHERE event_id = :e AND kind = 'TEAM_SHEETS'")
                .param("e", event).query(Long.class).single());
    }

    @Test
    void uploadRejectsNonPdf() {
        long event = eventId();
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> controller.upload(event, pdf("notes.docx", "not a pdf".getBytes())));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, e.getStatusCode());
    }

    @Test
    void uploadToUnknownEventIs404() {
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> controller.upload(-1, pdf("storylines.pdf", new byte[]{'%', 'P', 'D', 'F', '-'})));
        assertEquals(HttpStatus.NOT_FOUND, e.getStatusCode());
    }

    @Test
    void deleteRemovesAndFurtherReadsAre404() {
        long event = eventId();
        controller.upload(event, pdf("storylines.pdf", new byte[]{'%', 'P', 'D', 'F', '-'}));
        controller.delete(event);

        assertNull(sheetController.sheet(event).storylinesVersion());
        assertEquals(HttpStatus.NOT_FOUND,
                assertThrows(ResponseStatusException.class, () -> controller.get(event)).getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND,
                assertThrows(ResponseStatusException.class, () -> controller.data(event, null)).getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND,
                assertThrows(ResponseStatusException.class, () -> controller.delete(event)).getStatusCode());
    }
}
