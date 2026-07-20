package com.pitpass.series;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@Transactional
class SeriesChampionshipControllerTest {

    @Autowired JdbcClient db;
    @Autowired SeriesChampionshipController controller;

    @Test
    void listsOnlyThisSeriesChampionshipsAndTogglesTheOverallFlag() {
        long seriesId = series("Overall toggle test");
        long seasonId = season(seriesId, 2099);
        long groupId = group(seasonId, "DRIVERS", 1);
        long umbrella = championship(seasonId, groupId, "Umbrella drivers", "DH");
        long classChamp = championship(seasonId, groupId, "Class drivers", "DHL");

        long otherSeasonId = season(series("Other series"), 2099);
        long otherChampionship = championship(otherSeasonId, group(otherSeasonId, "DRIVERS", 1),
                "Someone else's", "GT");

        List<SeriesChampionshipController.SeriesChampionship> listed =
                controller.list(seriesId).championships();
        // Ordered by class_name within the group, so "DH" precedes "DHL".
        assertEquals(List.of(umbrella, classChamp),
                listed.stream().map(SeriesChampionshipController.SeriesChampionship::id).toList());
        assertTrue(listed.stream().noneMatch(c -> c.id() == otherChampionship));
        assertTrue(listed.stream().noneMatch(SeriesChampionshipController.SeriesChampionship::isOverall),
                "importer leaves named-class champs unflagged");

        controller.setOverall(seriesId, umbrella, new SeriesChampionshipController.OverallRequest(true));
        assertTrue(isOverall(umbrella));
        assertFalse(isOverall(classChamp), "flagging one championship leaves its siblings alone");
        assertTrue(controller.list(seriesId).championships().stream()
                .filter(c -> c.id() == umbrella).findFirst().orElseThrow().isOverall());

        controller.setOverall(seriesId, umbrella, new SeriesChampionshipController.OverallRequest(false));
        assertFalse(isOverall(umbrella));
    }

    @Test
    void refusesAChampionshipBelongingToAnotherSeries() {
        long seriesId = series("Asking series");
        long otherSeries = series("Owning series");
        long otherSeasonId = season(otherSeries, 2099);
        long otherChampionship = championship(otherSeasonId, group(otherSeasonId, "DRIVERS", 1),
                "Not yours", "GT");

        assertThrows(ResponseStatusException.class, () -> controller.setOverall(seriesId, otherChampionship,
                new SeriesChampionshipController.OverallRequest(true)));
        assertFalse(isOverall(otherChampionship), "a cross-series write must not land");

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

    private long group(long seasonId, String kind, int ordinal) {
        return db.sql("""
                        INSERT INTO championship_group (season_id, family, kind, label, ordinal)
                        VALUES (:season, :family, :kind, :label, :ordinal) RETURNING id
                        """)
                .param("season", seasonId).param("family", "Family " + UUID.randomUUID())
                .param("kind", kind).param("label", "Label").param("ordinal", ordinal)
                .query(Long.class).single();
    }

    private long championship(long seasonId, long groupId, String title, String className) {
        return db.sql("""
                        INSERT INTO championship (season_id, group_id, name, title, class_name)
                        VALUES (:season, :group, :name, :title, :className) RETURNING id
                        """)
                .param("season", seasonId).param("group", groupId)
                .param("name", title + " " + UUID.randomUUID()).param("title", title)
                .param("className", className)
                .query(Long.class).single();
    }

    private boolean isOverall(long championshipId) {
        return db.sql("SELECT is_overall FROM championship WHERE id = :id")
                .param("id", championshipId).query(Boolean.class).single();
    }
}
