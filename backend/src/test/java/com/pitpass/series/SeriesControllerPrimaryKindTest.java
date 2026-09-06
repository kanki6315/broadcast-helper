package com.pitpass.series;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest
@Transactional
class SeriesControllerPrimaryKindTest {

    @Autowired JdbcClient db;
    @Autowired SeriesController controller;

    @Test
    void setsValidatesAndClearsTheHeadlineKind() {
        long id = db.sql("INSERT INTO series (name) VALUES (:name) RETURNING id")
                .param("name", "Headline test " + UUID.randomUUID())
                .query(Long.class).single();
        assertNull(primaryKind(id), "a new series keeps the Teams-first default");

        controller.setPrimaryKind(id, new SeriesController.PrimaryKindRequest(" drivers "));
        assertEquals("DRIVERS", primaryKind(id));
        assertEquals("DRIVERS", controller.list().stream()
                .filter(s -> s.id() == id).findFirst().orElseThrow().primaryKind(), "the list reports it");

        ResponseStatusException bad = assertThrows(ResponseStatusException.class,
                () -> controller.setPrimaryKind(id, new SeriesController.PrimaryKindRequest("ENTRANTS")));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, bad.getStatusCode());
        assertEquals("DRIVERS", primaryKind(id), "a rejected kind leaves the setting alone");

        controller.setPrimaryKind(id, new SeriesController.PrimaryKindRequest(""));
        assertNull(primaryKind(id));

        assertThrows(ResponseStatusException.class,
                () -> controller.setPrimaryKind(-1, new SeriesController.PrimaryKindRequest("TEAMS")));
    }

    private String primaryKind(long seriesId) {
        return db.sql("SELECT primary_kind FROM series WHERE id = :id")
                .param("id", seriesId).query(String.class).optional().orElse(null);
    }
}
