package com.pitpass.season;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest
@Transactional
class SeasonKindControllerTest {

    @Autowired JdbcClient db;
    @Autowired SeasonController controller;

    @Test
    void flipsToQualifierWithTrimmedLabelAndBackToMainClearingIt() {
        long seasonId = season(series(), 2099, "MAIN", null);

        controller.setKind(seasonId, new SeasonController.KindUpdate("QUALIFIER", "  Regional — Europe  "));
        assertEquals(Map.of("kind", "QUALIFIER", "label", "Regional — Europe"), kindAndLabel(seasonId));

        // Flipping back is a return to the series proper; the stage name goes with it.
        controller.setKind(seasonId, new SeasonController.KindUpdate("MAIN", "Regional — Europe"));
        assertEquals("MAIN", kindAndLabel(seasonId).get("kind"));
        assertNull(kindAndLabel(seasonId).get("label"));
    }

    /** The fixture itself is half the point: a qualifying stage and the main
     *  season now coexist in one year, which V2's UNIQUE(series_id, year)
     *  used to forbid. */
    @Test
    void refusesASecondMainSeasonForTheYear() {
        long seriesId = series();
        season(seriesId, 2099, "MAIN", null);
        long regional = season(seriesId, 2099, "QUALIFIER", "Regional — Europe");

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> controller.setKind(regional, new SeasonController.KindUpdate("MAIN", null)));
        assertEquals(HttpStatus.CONFLICT, e.getStatusCode());
    }

    @Test
    void refusesTwoQualifierStagesSharingALabel() {
        long seriesId = series();
        season(seriesId, 2099, "QUALIFIER", "Regional — Europe");
        long second = season(seriesId, 2099, "MAIN", null);

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> controller.setKind(second, new SeasonController.KindUpdate("QUALIFIER", "Regional — Europe")));
        assertEquals(HttpStatus.CONFLICT, e.getStatusCode());
    }

    @Test
    void refusesUnknownKindsAndUnknownSeasons() {
        long seasonId = season(series(), 2099, "MAIN", null);
        ResponseStatusException invalid = assertThrows(ResponseStatusException.class,
                () -> controller.setKind(seasonId, new SeasonController.KindUpdate("PLAYOFF", null)));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, invalid.getStatusCode());

        ResponseStatusException missing = assertThrows(ResponseStatusException.class,
                () -> controller.setKind(-1, new SeasonController.KindUpdate("QUALIFIER", null)));
        assertEquals(HttpStatus.NOT_FOUND, missing.getStatusCode());
    }

    private long series() {
        return db.sql("INSERT INTO series (name) VALUES (:n) RETURNING id")
                .param("n", "Kind series " + UUID.randomUUID()).query(Long.class).single();
    }

    private long season(long seriesId, int year, String kind, String label) {
        return db.sql("INSERT INTO season (series_id, year, kind, label) VALUES (:s, :y, :k, :l) RETURNING id")
                .param("s", seriesId).param("y", year)
                .param("k", kind).param("l", label).query(Long.class).single();
    }

    private Map<String, Object> kindAndLabel(long seasonId) {
        return db.sql("SELECT kind, label FROM season WHERE id = :id").param("id", seasonId)
                .query().singleRow();
    }
}
