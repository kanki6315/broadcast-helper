package com.pitpass.imports;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** A new season's group inherits the series' wording for its kind, so "Entrants"
 *  set once on Mustang Challenge 2025 is what 2026 arrives as. */
@SpringBootTest
@Transactional
class ImportServiceGroupWordingTest {

    @Autowired JdbcClient db;
    @Autowired ImportService importService;

    @Test
    void inheritsTheMostRecentWordingOfTheSameKindPreferringTheSameFamily() {
        long seriesId = series("Wording inherit");
        long s2024 = season(seriesId, 2024);
        long s2025 = season(seriesId, 2025);
        long s2026 = season(seriesId, 2026);
        group(s2024, "Family A", "TEAMS", "Entrants (old)", false);
        group(s2025, "Family A", "TEAMS", "Entrants", false);
        group(s2025, "Some Cup", "TEAMS", "Crews", true);
        group(s2025, "Family A", "DRIVERS", null, false);

        assertEquals("Entrants", importService.inheritedKindLabel(s2026, "Family A", "TEAMS"),
                "the newest same-family wording wins");
        assertEquals("Crews", importService.inheritedKindLabel(s2026, "Some Cup", "TEAMS"));
        assertEquals("Entrants", importService.inheritedKindLabel(s2026, "Brand New Cup", "TEAMS"),
                "an unseen family borrows the primary's wording for the kind, not a cup's");
        assertNull(importService.inheritedKindLabel(s2026, "Family A", "DRIVERS"),
                "a kind with no wording anywhere stays default");
        assertNull(importService.inheritedKindLabel(s2026, "Family A", null));

        long otherSeason = season(series("Other"), 2026);
        assertNull(importService.inheritedKindLabel(otherSeason, "Family A", "TEAMS"),
                "wording never crosses series");
    }

    private long series(String name) {
        return db.sql("INSERT INTO series (name) VALUES (:name) RETURNING id")
                .param("name", name + " " + UUID.randomUUID()).query(Long.class).single();
    }

    private long season(long seriesId, int year) {
        return db.sql("INSERT INTO season (series_id, year) VALUES (:series, :year) RETURNING id")
                .param("series", seriesId).param("year", year).query(Long.class).single();
    }

    private void group(long seasonId, String family, String kind, String kindLabel, boolean isCup) {
        db.sql("""
                        INSERT INTO championship_group (season_id, family, kind, label, kind_label, ordinal, is_cup)
                        VALUES (:season, :family, :kind, :label, :kindLabel,
                                (SELECT COALESCE(max(ordinal), 0) + 1 FROM championship_group WHERE season_id = :season),
                                :isCup)
                        """)
                .param("season", seasonId).param("family", family).param("kind", kind)
                .param("label", family + " — x").param("kindLabel", kindLabel).param("isCup", isCup)
                .update();
    }
}
