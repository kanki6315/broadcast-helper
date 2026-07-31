package com.pitpass.season;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@Transactional
class CarNumberAliasControllerTest {

    @Autowired JdbcClient db;
    @Autowired CarNumberAliasController controller;

    private long seasonId() {
        long seriesId = db.sql("INSERT INTO series (name) VALUES (:n) RETURNING id")
                .param("n", "Alias series " + UUID.randomUUID()).query(Long.class).single();
        return db.sql("INSERT INTO season (series_id, year) VALUES (:s, 2099) RETURNING id")
                .param("s", seriesId).query(Long.class).single();
    }

    @Test
    void createListDelete() {
        long season = seasonId();
        CarNumberAliasController.CarNumberAlias created = controller.create(season,
                new CarNumberAliasController.CreateRequest("GTP", "85", "5", "Ran Daytona as #85"));

        var listed = controller.list(season);
        assertEquals(1, listed.size());
        assertEquals("85", listed.get(0).carNumber());
        assertEquals("5", listed.get(0).canonicalNumber());
        assertEquals("Ran Daytona as #85", listed.get(0).note());

        controller.delete(season, created.id());
        assertTrue(controller.list(season).isEmpty());
    }

    @Test
    void aNumberHasOneMeaningPerSeasonAndClass() {
        long season = seasonId();
        controller.create(season, new CarNumberAliasController.CreateRequest("GTD", "19", "068", null));

        // The same number in ANOTHER class is a different car entirely.
        controller.create(season, new CarNumberAliasController.CreateRequest("GTDPRO", "19", "68", null));
        assertEquals(2, controller.list(season).size());

        // "019" is the same number as "19" — the second mapping must be refused,
        // whatever it points at. (Last: the unique violation aborts the test's
        // transaction, so nothing can run after it.)
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> controller.create(season,
                        new CarNumberAliasController.CreateRequest("GTD", "019", "99", null)));
        assertEquals(HttpStatus.CONFLICT, e.getStatusCode());
    }

    @Test
    void seriesListingSpansSeasonsNewestFirstAndOffersClassesInUse() {
        long seriesId = db.sql("INSERT INTO series (name) VALUES (:n) RETURNING id")
                .param("n", "Alias series " + UUID.randomUUID()).query(Long.class).single();
        long s2098 = db.sql("INSERT INTO season (series_id, year) VALUES (:s, 2098) RETURNING id")
                .param("s", seriesId).query(Long.class).single();
        long s2099 = db.sql("INSERT INTO season (series_id, year) VALUES (:s, 2099) RETURNING id")
                .param("s", seriesId).query(Long.class).single();
        long eventId = db.sql("INSERT INTO event (season_id, name) VALUES (:s, 'Round') RETURNING id")
                .param("s", s2099).query(Long.class).single();
        db.sql("INSERT INTO entry (event_id, car_number, class_name, team_name) VALUES (:e, '5', 'GTP', 'T')")
                .param("e", eventId).update();

        controller.create(s2098, new CarNumberAliasController.CreateRequest("GTD", "19", "068", null));
        controller.create(s2099, new CarNumberAliasController.CreateRequest("GTP", "85", "5", null));

        CarNumberAliasController.SeriesCarNumberAliases listed = controller.listForSeries(seriesId);
        assertEquals(2, listed.aliases().size());
        assertEquals(2099, listed.aliases().get(0).year());
        assertEquals("85", listed.aliases().get(0).carNumber());
        assertEquals(s2099, listed.aliases().get(0).seasonId());
        assertEquals(2098, listed.aliases().get(1).year());
        assertEquals(java.util.List.of("GTP"), listed.classesInUse());
    }

    @Test
    void selfMappingIsRejected() {
        long season = seasonId();
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> controller.create(season,
                        new CarNumberAliasController.CreateRequest("GTD", "068", "68", null)));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, e.getStatusCode());
    }

    @Test
    void unknownSeasonAndUnknownAliasAre404() {
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> controller.list(-1));
        assertEquals(HttpStatus.NOT_FOUND, e.getStatusCode());

        long season = seasonId();
        ResponseStatusException del = assertThrows(ResponseStatusException.class,
                () -> controller.delete(season, 123456789));
        assertEquals(HttpStatus.NOT_FOUND, del.getStatusCode());
    }
}
