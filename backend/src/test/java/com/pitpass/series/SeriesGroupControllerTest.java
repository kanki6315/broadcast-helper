package com.pitpass.series;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@Transactional
class SeriesGroupControllerTest {

    @Autowired JdbcClient db;
    @Autowired SeriesGroupController controller;

    @Test
    void listsThisSeriesGroupsAndTogglesTheCupFlag() {
        long seriesId = series("Cup toggle test");
        long seasonId = season(seriesId, 2099);
        long primary = group(seasonId, "DRIVERS", 1, false);
        long cup = group(seasonId, "TEAMS", 2, true);
        championship(seasonId, cup, "Dealer Trophy");

        long otherSeasonId = season(series("Other series"), 2099);
        long otherGroup = group(otherSeasonId, "DRIVERS", 1, false);

        var listed = controller.list(seriesId).groups();
        assertEquals(2, listed.size());
        assertTrue(listed.stream().noneMatch(g -> g.id() == otherGroup), "other series' groups excluded");
        assertEquals(1, listed.stream().filter(g -> g.id() == cup).findFirst().orElseThrow().championshipCount());

        controller.setCup(seriesId, cup, new SeriesGroupController.CupRequest(false));
        assertFalse(isCup(cup));
        assertFalse(isCup(primary), "the sibling group is untouched");

        controller.setCup(seriesId, cup, new SeriesGroupController.CupRequest(true));
        assertTrue(isCup(cup));
    }

    @Test
    void refusesAGroupBelongingToAnotherSeries() {
        long seriesId = series("Asking series");
        long otherSeasonId = season(series("Owning series"), 2099);
        long otherGroup = group(otherSeasonId, "TEAMS", 1, true);

        assertThrows(ResponseStatusException.class,
                () -> controller.setCup(seriesId, otherGroup, new SeriesGroupController.CupRequest(false)));
        assertTrue(isCup(otherGroup), "a cross-series write must not land");

        assertThrows(ResponseStatusException.class, () -> controller.list(-1));
    }

    private long series(String name) {
        return db.sql("INSERT INTO series (name) VALUES (:name) RETURNING id")
                .param("name", name + " " + UUID.randomUUID())
                .query(Long.class).single();
    }

    private long season(long seriesId, int year) {
        return db.sql("INSERT INTO season (series_id, year) VALUES (:series, :year) RETURNING id")
                .param("series", seriesId).param("year", year)
                .query(Long.class).single();
    }

    private long group(long seasonId, String kind, int ordinal, boolean isCup) {
        return db.sql("""
                        INSERT INTO championship_group (season_id, family, kind, label, ordinal, is_cup)
                        VALUES (:season, :family, :kind, :label, :ordinal, :isCup) RETURNING id
                        """)
                .param("season", seasonId).param("family", "Family " + UUID.randomUUID())
                .param("kind", kind).param("label", "Label").param("ordinal", ordinal).param("isCup", isCup)
                .query(Long.class).single();
    }

    private void championship(long seasonId, long groupId, String title) {
        db.sql("""
                        INSERT INTO championship (season_id, group_id, name, title)
                        VALUES (:season, :group, :name, :title)
                        """)
                .param("season", seasonId).param("group", groupId)
                .param("name", title + " " + UUID.randomUUID()).param("title", title)
                .update();
    }

    private boolean isCup(long groupId) {
        return db.sql("SELECT is_cup FROM championship_group WHERE id = :id")
                .param("id", groupId).query(Boolean.class).single();
    }
}
