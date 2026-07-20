package com.pitpass.series;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * The series' championship groups and their {@code is_cup} flag, for the series
 * settings modal.
 *
 * <p>A group is one award set within a season (WeatherTech Teams, Endurance Cup
 * Teams, …); {@code is_cup} marks the ones contested over a subset of the
 * rounds and published under their own title, which sort after the primary
 * groups and are excluded from the sheet's championship column
 * ({@code SheetController}). The reviewer sets it at import, but
 * {@code ImportService.findOrCreateChampionshipGroup} never updates an existing
 * group — so a group mis-flagged at import (Carrera Cup Asia's Dealer Trophy
 * shares the series' own title yet arrived as a cup) could only be corrected by
 * hand-written SQL. This endpoint is that correction.
 */
@RestController
@RequestMapping("/api/series/{seriesId}/groups")
public class SeriesGroupController {

    private final JdbcClient db;

    public SeriesGroupController(JdbcClient db) {
        this.db = db;
    }

    public record SeriesGroup(long id, long seasonId, int year, String family, String kind,
                              String label, boolean isCup, long championshipCount) {
    }

    public record SeriesGroupsResponse(List<SeriesGroup> groups) {
    }

    public record CupRequest(@NotNull Boolean isCup) {
    }

    @GetMapping
    public SeriesGroupsResponse list(@PathVariable long seriesId) {
        requireSeries(seriesId);
        List<SeriesGroup> groups = db.sql("""
                        SELECT g.id, g.season_id, s.year, g.family, g.kind, g.label, g.is_cup,
                               (SELECT count(*) FROM championship c WHERE c.group_id = g.id) AS championship_count
                        FROM championship_group g
                                 JOIN season s ON s.id = g.season_id
                        WHERE s.series_id = :seriesId
                        ORDER BY s.year DESC, g.ordinal
                        """)
                .param("seriesId", seriesId)
                .query((rs, i) -> new SeriesGroup(rs.getLong("id"), rs.getLong("season_id"),
                        rs.getInt("year"), rs.getString("family"), rs.getString("kind"),
                        rs.getString("label"), rs.getBoolean("is_cup"), rs.getLong("championship_count")))
                .list();
        return new SeriesGroupsResponse(groups);
    }

    @PutMapping("/{groupId}/cup")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void setCup(@PathVariable long seriesId, @PathVariable long groupId,
                       @Valid @RequestBody CupRequest request) {
        requireSeries(seriesId);
        // Scoped to the series in the UPDATE, so a group id from another series
        // is a 404 rather than a silent cross-series write.
        int updated = db.sql("""
                        UPDATE championship_group SET is_cup = :isCup
                        WHERE id = :id
                          AND season_id IN (SELECT id FROM season WHERE series_id = :seriesId)
                        """)
                .param("isCup", request.isCup())
                .param("id", groupId)
                .param("seriesId", seriesId)
                .update();
        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such group in this series");
        }
    }

    private void requireSeries(long seriesId) {
        Integer found = db.sql("SELECT 1 FROM series WHERE id = :id")
                .param("id", seriesId)
                .query(Integer.class)
                .optional()
                .orElse(null);
        if (found == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such series");
        }
    }
}
