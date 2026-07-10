package com.broadcasthelper.season;

import com.broadcasthelper.browse.BrowseController;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;

/**
 * The season hub: a series-season is the top of the navigation tree, gathering
 * its calendar, championships, entry lists and images in one place. Read-only;
 * the importers own all writes.
 */
@RestController
@RequestMapping("/api/seasons")
public class SeasonController {

    private final JdbcClient db;

    public SeasonController(JdbcClient db) {
        this.db = db;
    }

    public record SeasonSummary(long id, int year, String seriesName,
                                long roundCount, long championshipCount) {
    }

    @GetMapping
    public List<SeasonSummary> seasons() {
        return db.sql("""
                        SELECT s.id, s.year, sr.name AS series_name,
                               (SELECT count(*) FROM event e WHERE e.season_id = s.id)          AS round_count,
                               (SELECT count(*) FROM championship c WHERE c.season_id = s.id)   AS championship_count
                        FROM season s JOIN series sr ON sr.id = s.series_id
                        ORDER BY s.year DESC, sr.name
                        """)
                .query((rs, i) -> new SeasonSummary(rs.getLong("id"), rs.getInt("year"),
                        rs.getString("series_name"), rs.getLong("round_count"), rs.getLong("championship_count")))
                .list();
    }

    public record CalendarEvent(long id, String name, String circuitName, LocalDate eventDate,
                                Integer roundOrdinal, long entryCount, long sessionCount) {
    }

    public record SeasonHub(long id, int year, String seriesName,
                            List<CalendarEvent> events,
                            List<BrowseController.ChampionshipSummary> championships) {
    }

    @GetMapping("/{id}")
    public SeasonHub season(@PathVariable long id) {
        SeasonSummary season = db.sql("""
                        SELECT s.id, s.year, sr.name AS series_name, 0 AS round_count, 0 AS championship_count
                        FROM season s JOIN series sr ON sr.id = s.series_id
                        WHERE s.id = :id
                        """)
                .param("id", id)
                .query((rs, i) -> new SeasonSummary(rs.getLong("id"), rs.getInt("year"),
                        rs.getString("series_name"), 0, 0))
                .optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such season"));

        List<CalendarEvent> events = db.sql("""
                        SELECT e.id, e.name, e.circuit_name, e.event_date, e.round_ordinal,
                               (SELECT count(*) FROM entry en WHERE en.event_id = e.id)         AS entry_count,
                               (SELECT count(*) FROM race_session rs WHERE rs.event_id = e.id)  AS session_count
                        FROM event e
                        WHERE e.season_id = :id
                        ORDER BY e.round_ordinal NULLS LAST, e.event_date, e.id
                        """)
                .param("id", id)
                .query((rs, i) -> new CalendarEvent(rs.getLong("id"), rs.getString("name"),
                        rs.getString("circuit_name"), rs.getObject("event_date", LocalDate.class),
                        rs.getObject("round_ordinal", Integer.class),
                        rs.getLong("entry_count"), rs.getLong("session_count")))
                .list();

        // Same ordering as the standings browse view: the series' own
        // championships (group_title = series name) before cups; within a group,
        // primary kinds (DRIVERS/TEAMS) before the rest.
        List<BrowseController.ChampionshipSummary> championships = db.sql("""
                        SELECT c.id, c.title, c.group_title, c.class_name, c.kind, s.year, s.id AS season_id,
                               sr.name AS series_name,
                               (SELECT count(*) FROM standings_row r WHERE r.championship_id = c.id) AS row_count
                        FROM championship c
                                 JOIN season s ON s.id = c.season_id
                                 JOIN series sr ON sr.id = s.series_id
                        WHERE c.season_id = :id
                        ORDER BY (c.group_title IS NOT DISTINCT FROM sr.name) DESC, c.group_title,
                                 c.class_name, c.kind
                        """)
                .param("id", id)
                .query((rs, i) -> new BrowseController.ChampionshipSummary(rs.getLong("id"), rs.getString("title"),
                        rs.getString("group_title"), rs.getString("class_name"), rs.getString("kind"),
                        rs.getInt("year"), rs.getLong("season_id"), rs.getString("series_name"), rs.getLong("row_count")))
                .list();

        return new SeasonHub(season.id(), season.year(), season.seriesName(), events, championships);
    }
}
