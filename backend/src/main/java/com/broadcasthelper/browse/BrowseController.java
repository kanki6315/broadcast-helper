package com.broadcasthelper.browse;

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
 * Read-only endpoints backing the browse pages. Queries are written directly
 * against the schema; the importers own all writes.
 */
@RestController
@RequestMapping("/api")
public class BrowseController {

    private final JdbcClient db;

    public BrowseController(JdbcClient db) {
        this.db = db;
    }

    public record EventSummary(long id, String name, String circuitName, LocalDate eventDate,
                               int year, String seriesName, long sessionCount, long entryCount) {
    }

    @GetMapping("/events")
    public List<EventSummary> events() {
        return db.sql("""
                        SELECT e.id, e.name, e.circuit_name, e.event_date, s.year, sr.name AS series_name,
                               (SELECT count(*) FROM race_session rs WHERE rs.event_id = e.id)  AS session_count,
                               (SELECT count(*) FROM entry en WHERE en.event_id = e.id)         AS entry_count
                        FROM event e
                                 JOIN season s ON s.id = e.season_id
                                 JOIN series sr ON sr.id = s.series_id
                        ORDER BY e.event_date
                        """)
                .query((rs, i) -> new EventSummary(rs.getLong("id"), rs.getString("name"),
                        rs.getString("circuit_name"), rs.getObject("event_date", LocalDate.class),
                        rs.getInt("year"), rs.getString("series_name"),
                        rs.getLong("session_count"), rs.getLong("entry_count")))
                .list();
    }

    public record EventEntry(long entryId, String carNumber, String className, String classGroup, String teamName,
                             String vehicle, String manufacturer, boolean isGuest, String drivers,
                             Integer racePositionOverall, Integer racePositionInClass, String raceStatus) {
    }

    public record EventDetail(EventSummary event, List<EventEntry> entries) {
    }

    @GetMapping("/events/{id}")
    public EventDetail event(@PathVariable long id) {
        EventSummary summary = db.sql("""
                        SELECT e.id, e.name, e.circuit_name, e.event_date, s.year, sr.name AS series_name,
                               (SELECT count(*) FROM race_session rs WHERE rs.event_id = e.id)  AS session_count,
                               (SELECT count(*) FROM entry en WHERE en.event_id = e.id)         AS entry_count
                        FROM event e
                                 JOIN season s ON s.id = e.season_id
                                 JOIN series sr ON sr.id = s.series_id
                        WHERE e.id = :id
                        """)
                .param("id", id)
                .query((rs, i) -> new EventSummary(rs.getLong("id"), rs.getString("name"),
                        rs.getString("circuit_name"), rs.getObject("event_date", LocalDate.class),
                        rs.getInt("year"), rs.getString("series_name"),
                        rs.getLong("session_count"), rs.getLong("entry_count")))
                .optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such event"));

        List<EventEntry> entries = db.sql("""
                        SELECT en.id AS entry_id, en.car_number, en.class_name, en.class_group, en.team_name,
                               en.vehicle, en.manufacturer, en.is_guest,
                               (SELECT string_agg(d.first_name || ' ' || d.surname ||
                                                  COALESCE(' (' || left(da.rating, 1) || ')', ''),
                                                  ', ' ORDER BY da.seat_order)
                                FROM driver_assignment da JOIN driver d ON d.id = da.driver_id
                                WHERE da.entry_id = en.id)                                      AS drivers,
                               r.position_overall, r.position_in_class, r.status
                        FROM entry en
                                 LEFT JOIN race_session rs ON rs.event_id = en.event_id AND rs.session_type = 'RACE'
                                 LEFT JOIN result r ON r.session_id = rs.id AND r.entry_id = en.id
                        WHERE en.event_id = :id
                        ORDER BY en.class_name, r.position_in_class NULLS LAST, en.car_number
                        """)
                .param("id", id)
                .query((rs, i) -> new EventEntry(rs.getLong("entry_id"), rs.getString("car_number"),
                        rs.getString("class_name"), rs.getString("class_group"), rs.getString("team_name"),
                        rs.getString("vehicle"), rs.getString("manufacturer"), rs.getBoolean("is_guest"),
                        rs.getString("drivers"), (Integer) rs.getObject("position_overall"),
                        (Integer) rs.getObject("position_in_class"), rs.getString("status")))
                .list();
        return new EventDetail(summary, entries);
    }

    public record ChampionshipSummary(long id, String title, String groupTitle, String className, String kind,
                                      int year, String seriesName, long rowCount) {
    }

    @GetMapping("/championships")
    public List<ChampionshipSummary> championships() {
        // The series' own championships (group_title = series name) sort before
        // cups; within a group, primary kinds (DRIVERS/TEAMS) before the rest.
        return db.sql("""
                        SELECT c.id, c.title, c.group_title, c.class_name, c.kind, s.year, sr.name AS series_name,
                               (SELECT count(*) FROM standings_row r WHERE r.championship_id = c.id) AS row_count
                        FROM championship c
                                 JOIN season s ON s.id = c.season_id
                                 JOIN series sr ON sr.id = s.series_id
                        ORDER BY s.year DESC, sr.name,
                                 (c.group_title IS NOT DISTINCT FROM sr.name) DESC, c.group_title,
                                 c.class_name, c.kind
                        """)
                .query((rs, i) -> new ChampionshipSummary(rs.getLong("id"), rs.getString("title"),
                        rs.getString("group_title"), rs.getString("class_name"), rs.getString("kind"),
                        rs.getInt("year"), rs.getString("series_name"), rs.getLong("row_count")))
                .list();
    }

    public record StandingsEntry(int position, String competitorKey, String competitorName, double totalPoints) {
    }

    public record ChampionshipDetail(ChampionshipSummary championship, List<StandingsEntry> rows) {
    }

    @GetMapping("/championships/{id}")
    public ChampionshipDetail championship(@PathVariable long id) {
        ChampionshipSummary summary = db.sql("""
                        SELECT c.id, c.title, c.group_title, c.class_name, c.kind, s.year, sr.name AS series_name,
                               (SELECT count(*) FROM standings_row r WHERE r.championship_id = c.id) AS row_count
                        FROM championship c
                                 JOIN season s ON s.id = c.season_id
                                 JOIN series sr ON sr.id = s.series_id
                        WHERE c.id = :id
                        """)
                .param("id", id)
                .query((rs, i) -> new ChampionshipSummary(rs.getLong("id"), rs.getString("title"),
                        rs.getString("group_title"), rs.getString("class_name"), rs.getString("kind"),
                        rs.getInt("year"), rs.getString("series_name"), rs.getLong("row_count")))
                .optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such championship"));

        List<StandingsEntry> rows = db.sql("""
                        SELECT position, competitor_key, competitor_name, total_points
                        FROM standings_row
                        WHERE championship_id = :id
                        ORDER BY position
                        """)
                .param("id", id)
                .query((rs, i) -> new StandingsEntry(rs.getInt("position"), rs.getString("competitor_key"),
                        rs.getString("competitor_name"), rs.getDouble("total_points")))
                .list();
        return new ChampionshipDetail(summary, rows);
    }
}
