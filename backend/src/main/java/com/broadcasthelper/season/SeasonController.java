package com.broadcasthelper.season;

import com.broadcasthelper.browse.BrowseController;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;

/**
 * The season hub: a series-season is the top of the navigation tree, gathering
 * its calendar, championships, entry lists and images in one place. The
 * importers own all writes except {@link #deleteData}, which exists to undo
 * them wholesale so a year can be reimported clean.
 */
@RestController
@RequestMapping("/api/seasons")
public class SeasonController {

    private final JdbcClient db;

    public SeasonController(JdbcClient db) {
        this.db = db;
    }

    public record SeasonSummary(long id, int year, long seriesId, String seriesName,
                                long roundCount, long championshipCount) {
    }

    @GetMapping
    public List<SeasonSummary> seasons() {
        return db.sql("""
                        SELECT s.id, s.year, sr.id AS series_id, sr.name AS series_name,
                               (SELECT count(*) FROM event e WHERE e.season_id = s.id)          AS round_count,
                               (SELECT count(*) FROM championship c WHERE c.season_id = s.id)   AS championship_count
                        FROM season s JOIN series sr ON sr.id = s.series_id
                        ORDER BY s.year DESC, sr.name
                        """)
                .query((rs, i) -> new SeasonSummary(rs.getLong("id"), rs.getInt("year"),
                        rs.getLong("series_id"), rs.getString("series_name"), rs.getLong("round_count"),
                        rs.getLong("championship_count")))
                .list();
    }

    public record CalendarEvent(long id, String name, String circuitName, LocalDate eventDate,
                                Integer roundOrdinal, long entryCount, long sessionCount) {
    }

    public record SeasonHub(long id, int year, long seriesId, String seriesName,
                            List<CalendarEvent> events,
                            List<BrowseController.ChampionshipSummary> championships,
                            List<String> entryClasses) {
    }

    @GetMapping("/{id}")
    public SeasonHub season(@PathVariable long id) {
        record Header(long id, int year, long seriesId, String seriesName) {
        }
        Header season = db.sql("""
                        SELECT s.id, s.year, sr.id AS series_id, sr.name AS series_name
                        FROM season s JOIN series sr ON sr.id = s.series_id
                        WHERE s.id = :id
                        """)
                .param("id", id)
                .query((rs, i) -> new Header(rs.getLong("id"), rs.getInt("year"),
                        rs.getLong("series_id"), rs.getString("series_name")))
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

        // Groups carry the display order (primary championship before its cups).
        List<BrowseController.ChampionshipSummary> championships = db.sql("""
                        SELECT c.id, c.title, g.family AS group_title, c.class_name, g.kind, g.is_cup, s.year,
                               s.id AS season_id, sr.name AS series_name,
                               (SELECT count(*) FROM standings_row r WHERE r.championship_id = c.id) AS row_count
                        FROM championship c
                                 JOIN championship_group g ON g.id = c.group_id
                                 JOIN season s ON s.id = c.season_id
                                 JOIN series sr ON sr.id = s.series_id
                        WHERE c.season_id = :id
                        ORDER BY g.ordinal, c.class_name
                        """)
                .param("id", id)
                .query((rs, i) -> new BrowseController.ChampionshipSummary(rs.getLong("id"), rs.getString("title"),
                        rs.getString("group_title"), rs.getString("class_name"), rs.getString("kind"),
                        rs.getBoolean("is_cup"), rs.getInt("year"), rs.getLong("season_id"),
                        rs.getString("series_name"), rs.getLong("row_count")))
                .list();

        // Classes that actually have entries this season. The UI offers only
        // classes that can answer (entries or standings) — a class configured
        // in class_style but absent from the data must not become a dead-end
        // filter button.
        List<String> entryClasses = db.sql("""
                        SELECT DISTINCT en.class_name
                        FROM entry en JOIN event ev ON ev.id = en.event_id
                        WHERE ev.season_id = :id AND en.class_name IS NOT NULL
                        """)
                .param("id", id)
                .query(String.class)
                .list();

        return new SeasonHub(season.id(), season.year(), season.seriesId(), season.seriesName(),
                events, championships, entryClasses);
    }

    public record SeasonDataDeleted(int roundsDeleted, int championshipsDeleted) {
    }

    /**
     * Wipes everything the importers created for this season — rounds with
     * their sessions, results, grids, flags and entries, and championships
     * with their standings — so the year can be reimported from scratch. The
     * season row itself and its car images survive: images are uploaded by
     * hand, not imported, and reimporting find-or-creates the same season.
     */
    @DeleteMapping("/{id}/data")
    @Transactional
    public SeasonDataDeleted deleteData(@PathVariable long id) {
        db.sql("SELECT id FROM season WHERE id = :id")
                .param("id", id)
                .query(Long.class)
                .optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such season"));

        // championship.season_id and event.season_id have no ON DELETE, and
        // championship.group_id blocks deleting groups first — so the order is
        // championships (cascades sessions and standings), then their groups,
        // then events (cascades sessions, results, grids, flags and entries).
        int championships = db.sql("DELETE FROM championship WHERE season_id = :id").param("id", id).update();
        db.sql("DELETE FROM championship_group WHERE season_id = :id").param("id", id).update();
        int rounds = db.sql("DELETE FROM event WHERE season_id = :id").param("id", id).update();
        return new SeasonDataDeleted(rounds, championships);
    }
}
