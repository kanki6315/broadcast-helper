package com.broadcasthelper.series;

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
 * The series' championships and their {@code is_overall} flag, for the series
 * settings modal.
 *
 * <p>An <em>overall</em> championship scores the whole field rather than one
 * class, so its recap matches entries across every class and prints whole-field
 * start/finish positions ({@code SeasonViewController.recap}). The importer sets
 * the flag only where a championship arrives with no class of its own — an
 * umbrella whose class_name names a real entry class (Mustang Challenge's "DH"
 * over DHL, Carrera Cup Asia's "Overall" carrying class PRO) is
 * indistinguishable from a class championship at import time and can only be
 * identified by a human. This endpoint is how they say so, instead of hand-
 * written SQL. The flag survives standings re-imports (see
 * {@code ImportService.commitStandings}), so it stays set once chosen.
 */
@RestController
@RequestMapping("/api/series/{seriesId}/championships")
public class SeriesChampionshipController {

    private final JdbcClient db;

    public SeriesChampionshipController(JdbcClient db) {
        this.db = db;
    }

    /** {@code rowCount} is the standings rows imported for it — a championship
     *  with none has nothing to recap, which is worth showing next to a flag
     *  that only changes how a recap renders. */
    public record SeriesChampionship(long id, long seasonId, int year, String title, String className,
                                     String kind, boolean isCup, boolean isOverall, long rowCount) {
    }

    public record SeriesChampionshipsResponse(List<SeriesChampionship> championships) {
    }

    public record OverallRequest(@NotNull Boolean isOverall) {
    }

    @GetMapping
    public SeriesChampionshipsResponse list(@PathVariable long seriesId) {
        requireSeries(seriesId);
        List<SeriesChampionship> championships = db.sql("""
                        SELECT c.id, c.season_id, s.year, c.title, c.class_name, g.kind, g.is_cup,
                               c.is_overall,
                               (SELECT count(*) FROM standings_row sr WHERE sr.championship_id = c.id) AS row_count
                        FROM championship c
                                 JOIN season s ON s.id = c.season_id
                                 JOIN championship_group g ON g.id = c.group_id
                        WHERE s.series_id = :seriesId
                        ORDER BY s.year DESC, g.ordinal, c.class_name NULLS FIRST
                        """)
                .param("seriesId", seriesId)
                .query((rs, i) -> new SeriesChampionship(rs.getLong("id"), rs.getLong("season_id"),
                        rs.getInt("year"), rs.getString("title"), rs.getString("class_name"),
                        rs.getString("kind"), rs.getBoolean("is_cup"), rs.getBoolean("is_overall"),
                        rs.getLong("row_count")))
                .list();
        return new SeriesChampionshipsResponse(championships);
    }

    @PutMapping("/{championshipId}/overall")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void setOverall(@PathVariable long seriesId, @PathVariable long championshipId,
                           @Valid @RequestBody OverallRequest request) {
        requireSeries(seriesId);
        // Scoped to the series in the UPDATE itself, so a championship id from
        // another series is a 404 rather than a silent cross-series write.
        int updated = db.sql("""
                        UPDATE championship SET is_overall = :isOverall
                        WHERE id = :id
                          AND season_id IN (SELECT id FROM season WHERE series_id = :seriesId)
                        """)
                .param("isOverall", request.isOverall())
                .param("id", championshipId)
                .param("seriesId", seriesId)
                .update();
        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such championship in this series");
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
