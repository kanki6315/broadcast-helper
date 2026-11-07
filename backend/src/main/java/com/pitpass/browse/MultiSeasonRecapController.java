package com.pitpass.browse;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

/** Read-only series history. Initially exposed in the UI for FIA F4's single
 * driver championship. Never combine cups, qualifiers or multiple classes. */
@RestController
@RequestMapping("/api/series")
public class MultiSeasonRecapController {
    private final JdbcClient db;
    private final SeasonViewController recaps;
    private final SeasonStatsController stats;

    public MultiSeasonRecapController(JdbcClient db, SeasonViewController recaps, SeasonStatsController stats) {
        this.db = db;
        this.recaps = recaps;
        this.stats = stats;
    }

    public record History(long seriesId, String seriesName, List<Season> seasons) {}
    public record Season(long id, int year, SeasonViewController.Recap recap,
                         SeasonStatsController.StatsTable stats, Map<String, Long> driverIds) {}
    private record SeasonRef(long id, int year) {}

    @GetMapping("/{id}/recap")
    @Transactional(readOnly = true)
    public History history(@PathVariable long id) {
        String name = db.sql("SELECT name FROM series WHERE id = :id").param("id", id)
                .query(String.class).optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such series"));
        List<SeasonRef> seasons = db.sql("SELECT id, year FROM season WHERE series_id = :id AND kind = 'MAIN' ORDER BY year")
                .param("id", id).query((rs, i) -> new SeasonRef(rs.getLong("id"), rs.getInt("year"))).list();
        // Restrict candidates to this series. Aliases survive driver merges; an
        // ambiguous spelling remains unresolved instead of crediting the wrong person.
        Map<String, Set<Long>> identities = new HashMap<>();
        db.sql("""
                WITH participants AS (
                    SELECT DISTINCT da.driver_id FROM driver_assignment da
                    JOIN entry en ON en.id = da.entry_id JOIN event e ON e.id = en.event_id
                    JOIN season s ON s.id = e.season_id WHERE s.series_id = :id AND s.kind = 'MAIN'
                )
                SELECT d.id, d.first_name || ' ' || d.surname AS name FROM driver d JOIN participants p ON p.driver_id = d.id
                UNION ALL
                SELECT a.driver_id, a.alias FROM driver_alias a JOIN participants p ON p.driver_id = a.driver_id
                """).param("id", id).query((rs, i) -> {
                    identities.computeIfAbsent(normalize(rs.getString("name")), k -> new HashSet<>()).add(rs.getLong("id"));
                    return 0;
                }).list();
        List<Season> result = new ArrayList<>();
        for (SeasonRef season : seasons) {
            List<Long> championships = db.sql("""
                    SELECT c.id FROM championship c JOIN championship_group g ON g.id = c.group_id
                    WHERE c.season_id = :id AND g.kind = 'DRIVERS' AND NOT g.is_cup ORDER BY c.id
                    """).param("id", season.id()).query(Long.class).list();
            if (championships.size() > 1) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Multi-season recap currently requires one main driver championship per season");
            }
            var recap = championships.isEmpty() ? null : recaps.recap(championships.getFirst());
            Map<String, Long> driverIds = new LinkedHashMap<>();
            if (recap != null) for (var row : recap.rows()) {
                Long driverId = resolve(identities, row.competitorName() == null ? row.competitorKey() : row.competitorName());
                if (driverId != null) driverIds.put(row.competitorKey(), driverId);
            }
            result.add(new Season(season.id(), season.year(), recap, stats.seasonStats(season.id()), driverIds));
        }
        return new History(id, name, result);
    }

    static Long resolve(Map<String, Set<Long>> identities, String name) {
        var candidates = identities.get(normalize(name));
        return candidates != null && candidates.size() == 1 ? candidates.iterator().next() : null;
    }

    static String normalize(String name) {
        return name == null ? "" : name.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
}
