package com.pitpass.browse;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Read-only per-driver stat tallies (wins, podiums, top-fives, DNFs, poles)
 * split by race format, for one season or a whole series (all seasons — the
 * Stats tab's "All-time" toggle). Counts are in-class facts: a win is
 * {@code position_in_class = 1}. Poles come only from QUALIFYING session
 * results, never a race's starting grid — a reversed feature grid's front row
 * is not a pole. A race result credits every crew member; a quali claim
 * credits only the qualifying driver of record (grid attribution, or the sole
 * crew member of a solo entry). The importers own all writes.
 */
@RestController
@RequestMapping("/api")
public class SeasonStatsController {

    private final JdbcClient db;

    public SeasonStatsController(JdbcClient db) {
        this.db = db;
    }

    public record FormatInfo(Long id, String name, int ordinal) {
    }

    public record FormatLine(Long formatId, int starts, int wins, int podiums, int top5s, int dnfs) {
    }

    public record QualiLine(int sessions, int poles, int top5s) {
    }

    public record DriverStatsRow(long driverId, String driverName, String className,
                                 String carNumber, String teamName,
                                 List<FormatLine> byFormat, QualiLine quali) {
    }

    public record StatsTable(List<FormatInfo> formats, List<DriverStatsRow> rows) {
    }

    public record TeamStatsRow(long teamId, String teamName, String className,
                               String carNumbers, List<FormatLine> byFormat, QualiLine quali) {
    }

    public record TeamStatsTable(List<FormatInfo> formats, List<TeamStatsRow> rows) {
    }

    @GetMapping("/seasons/{id}/stats")
    public StatsTable seasonStats(@PathVariable long id) {
        requireSeason(id);
        return statsTable("ev.season_id = :id", id);
    }

    /** All seasons of the series combined — formats are per-series, so the
     *  same buckets aggregate cleanly across its seasons. Qualifying stages
     *  stay out: their fields never raced the series proper. */
    @GetMapping("/series/{id}/stats")
    public StatsTable seriesStats(@PathVariable long id) {
        requireSeries(id);
        return statsTable("s.series_id = :id AND s.kind = 'MAIN'", id);
    }

    @GetMapping("/seasons/{id}/team-stats")
    public TeamStatsTable seasonTeamStats(@PathVariable long id) {
        requireSeason(id);
        return teamStatsTable("ev.season_id = :id", id);
    }

    @GetMapping("/series/{id}/team-stats")
    public TeamStatsTable seriesTeamStats(@PathVariable long id) {
        requireSeries(id);
        return teamStatsTable("s.series_id = :id AND s.kind = 'MAIN'", id);
    }

    private void requireSeason(long id) {
        db.sql("SELECT 1 FROM season WHERE id = :id").param("id", id).query(Integer.class)
                .optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such season"));
    }

    private void requireSeries(long id) {
        db.sql("SELECT 1 FROM series WHERE id = :id").param("id", id).query(Integer.class)
                .optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such series"));
    }

    private record RaceAgg(long driverId, String name, String className, Long formatId,
                           int starts, int wins, int podiums, int top5s, int dnfs) {
    }

    private record QualiAgg(long driverId, String className, int sessions, int poles, int top5s) {
    }

    private record Context(String carNumber, String teamName) {
    }

    private StatsTable statsTable(String scopePredicate, long id) {
        // Race tallies per driver × class × format. DNS carries a null
        // position_in_class (V11), so it never counts as a start.
        List<RaceAgg> raceAggs = db.sql("""
                        SELECT da.driver_id, d.first_name || ' ' || d.surname AS name,
                               en.class_name, rs.format_id,
                               count(*) FILTER (WHERE r.position_in_class IS NOT NULL) AS starts,
                               count(*) FILTER (WHERE r.position_in_class = 1)  AS wins,
                               count(*) FILTER (WHERE r.position_in_class <= 3) AS podiums,
                               count(*) FILTER (WHERE r.position_in_class <= 5) AS top5s,
                               count(*) FILTER (WHERE r.not_finished)           AS dnfs
                        FROM result r
                                 JOIN race_session rs ON rs.id = r.session_id AND rs.session_type = 'RACE'
                                 JOIN event ev ON ev.id = rs.event_id
                                 JOIN season s ON s.id = ev.season_id
                                 JOIN entry en ON en.id = r.entry_id
                                 JOIN driver_assignment da ON da.entry_id = en.id
                                 JOIN driver d ON d.id = da.driver_id
                        WHERE %s
                        GROUP BY da.driver_id, d.first_name, d.surname, en.class_name, rs.format_id
                        """.formatted(scopePredicate))
                .param("id", id)
                .query((rs, i) -> new RaceAgg(rs.getLong("driver_id"), rs.getString("name"),
                        rs.getString("class_name"), rs.getObject("format_id", Long.class),
                        rs.getInt("starts"), rs.getInt("wins"), rs.getInt("podiums"),
                        rs.getInt("top5s"), rs.getInt("dnfs")))
                .list();

        // Quali claims are individual, not crew-wide: a session/pole/top-5 goes
        // to the qualifying driver of record — the grid file's attribution
        // (lowest race of the event that names one), or the entry's sole crew
        // member where the source can't name one (iRacing solo entries). A
        // multi-driver crew with no attribution credits no one; the count(*)=1
        // fallback counts TBD seats too, so a partially named crew never
        // collapses to a false sole qualifier.
        List<QualiAgg> qualiAggs = db.sql("""
                        SELECT q.driver_id, q.class_name,
                               count(*) FILTER (WHERE q.position_in_class IS NOT NULL) AS sessions,
                               count(*) FILTER (WHERE q.position_in_class = 1)  AS poles,
                               count(*) FILTER (WHERE q.position_in_class <= 5) AS top5s
                        FROM (
                            SELECT r.position_in_class, en.class_name,
                                   COALESCE(
                                       (SELECT gp.qualifying_driver_id
                                        FROM grid_position gp
                                                 JOIN race_session grs ON grs.id = gp.session_id
                                                      AND grs.event_id = ev.id AND grs.session_type = 'RACE'
                                        WHERE gp.entry_id = en.id AND gp.qualifying_driver_id IS NOT NULL
                                        ORDER BY grs.ordinal LIMIT 1),
                                       (SELECT min(da.driver_id) FROM driver_assignment da
                                        WHERE da.entry_id = en.id HAVING count(*) = 1)
                                   ) AS driver_id
                            FROM result r
                                     JOIN race_session rs ON rs.id = r.session_id AND rs.session_type = 'QUALIFYING'
                                     JOIN event ev ON ev.id = rs.event_id
                                     JOIN season s ON s.id = ev.season_id
                                     JOIN entry en ON en.id = r.entry_id
                            WHERE %s
                        ) q
                        WHERE q.driver_id IS NOT NULL
                        GROUP BY q.driver_id, q.class_name
                        """.formatted(scopePredicate))
                .param("id", id)
                .query((rs, i) -> new QualiAgg(rs.getLong("driver_id"), rs.getString("class_name"),
                        rs.getInt("sessions"), rs.getInt("poles"), rs.getInt("top5s")))
                .list();

        // Latest car/team per driver within scope, for the row's context columns.
        Map<Long, Context> contexts = new LinkedHashMap<>();
        db.sql("""
                        SELECT DISTINCT ON (da.driver_id) da.driver_id, en.car_number, en.team_name
                        FROM driver_assignment da
                                 JOIN entry en ON en.id = da.entry_id
                                 JOIN event ev ON ev.id = en.event_id
                                 JOIN season s ON s.id = ev.season_id
                        WHERE %s
                        ORDER BY da.driver_id, ev.event_date DESC NULLS LAST, ev.id DESC
                        """.formatted(scopePredicate))
                .param("id", id)
                .query((rs, i) -> contexts.put(rs.getLong("driver_id"),
                        new Context(rs.getString("car_number"), rs.getString("team_name"))))
                .list();

        List<FormatInfo> formats = formatColumns(scopePredicate, id,
                raceAggs.stream().anyMatch(a -> a.formatId() == null));

        // Assemble one row per driver × class, format lines in column order.
        record RowKey(long driverId, String className) {
        }
        Map<RowKey, List<RaceAgg>> byRow = new LinkedHashMap<>();
        for (RaceAgg a : raceAggs) {
            byRow.computeIfAbsent(new RowKey(a.driverId(), a.className()), k -> new ArrayList<>()).add(a);
        }
        Map<RowKey, QualiAgg> qualiByRow = new LinkedHashMap<>();
        for (QualiAgg q : qualiAggs) {
            qualiByRow.put(new RowKey(q.driverId(), q.className()), q);
        }

        List<DriverStatsRow> rows = new ArrayList<>();
        for (Map.Entry<RowKey, List<RaceAgg>> e : byRow.entrySet()) {
            List<RaceAgg> aggs = e.getValue();
            List<FormatLine> lines = new ArrayList<>();
            for (FormatInfo f : formats) {
                aggs.stream().filter(a -> Objects.equals(a.formatId(), f.id())).findFirst()
                        .ifPresent(a -> lines.add(new FormatLine(a.formatId(), a.starts(),
                                a.wins(), a.podiums(), a.top5s(), a.dnfs())));
            }
            QualiAgg q = qualiByRow.get(e.getKey());
            Context ctx = contexts.get(e.getKey().driverId());
            rows.add(new DriverStatsRow(e.getKey().driverId(), aggs.get(0).name(),
                    e.getKey().className(),
                    ctx != null ? ctx.carNumber() : null, ctx != null ? ctx.teamName() : null,
                    lines, new QualiLine(q != null ? q.sessions() : 0,
                    q != null ? q.poles() : 0, q != null ? q.top5s() : 0)));
        }
        // Default order: most wins, then podiums, then name — the tab re-sorts
        // client-side but arrives readable.
        rows.sort((a, b) -> {
            int wa = a.byFormat().stream().mapToInt(FormatLine::wins).sum();
            int wb = b.byFormat().stream().mapToInt(FormatLine::wins).sum();
            if (wa != wb) {
                return wb - wa;
            }
            int pa = a.byFormat().stream().mapToInt(FormatLine::podiums).sum();
            int pb = b.byFormat().stream().mapToInt(FormatLine::podiums).sum();
            if (pa != pb) {
                return pb - pa;
            }
            return a.driverName().compareToIgnoreCase(b.driverName());
        });
        return new StatsTable(formats, rows);
    }

    /** Formats referenced by the scope's data, in their series ordinal order; a
     *  null format bucket ("Unassigned") only when a session actually lacks one. */
    private List<FormatInfo> formatColumns(String scopePredicate, long id, boolean includeUnassigned) {
        List<FormatInfo> formats = new ArrayList<>(db.sql("""
                        SELECT rf.id, rf.name, rf.ordinal
                        FROM race_format rf
                        WHERE rf.id IN (SELECT DISTINCT rs.format_id
                                        FROM race_session rs
                                                 JOIN event ev ON ev.id = rs.event_id
                                                 JOIN season s ON s.id = ev.season_id
                                        WHERE %s AND rs.format_id IS NOT NULL)
                        ORDER BY rf.ordinal, rf.name
                        """.formatted(scopePredicate))
                .param("id", id)
                .query((rs, i) -> new FormatInfo(rs.getLong("id"), rs.getString("name"), rs.getInt("ordinal")))
                .list());
        if (includeUnassigned) {
            formats.add(new FormatInfo(null, "Unassigned", 99));
        }
        return formats;
    }

    private record TeamRaceAgg(long teamId, String name, String className, Long formatId,
                               int starts, int wins, int podiums, int top5s, int dnfs) {
    }

    private record TeamQualiAgg(long teamId, String className, int sessions, int poles, int top5s) {
    }

    private TeamStatsTable teamStatsTable(String scopePredicate, long id) {
        // Race tallies per team × class × format. Counting is per car-entry —
        // one result row per entry, so a two-car team scores two starts a race.
        // Entries without a global team (iRacing privateers) are excluded.
        List<TeamRaceAgg> raceAggs = db.sql("""
                        SELECT en.team_id, t.name, en.class_name, rs.format_id,
                               count(*) FILTER (WHERE r.position_in_class IS NOT NULL) AS starts,
                               count(*) FILTER (WHERE r.position_in_class = 1)  AS wins,
                               count(*) FILTER (WHERE r.position_in_class <= 3) AS podiums,
                               count(*) FILTER (WHERE r.position_in_class <= 5) AS top5s,
                               count(*) FILTER (WHERE r.not_finished)           AS dnfs
                        FROM result r
                                 JOIN race_session rs ON rs.id = r.session_id AND rs.session_type = 'RACE'
                                 JOIN event ev ON ev.id = rs.event_id
                                 JOIN season s ON s.id = ev.season_id
                                 JOIN entry en ON en.id = r.entry_id
                                 JOIN team t ON t.id = en.team_id
                        WHERE %s
                        GROUP BY en.team_id, t.name, en.class_name, rs.format_id
                        """.formatted(scopePredicate))
                .param("id", id)
                .query((rs, i) -> new TeamRaceAgg(rs.getLong("team_id"), rs.getString("name"),
                        rs.getString("class_name"), rs.getObject("format_id", Long.class),
                        rs.getInt("starts"), rs.getInt("wins"), rs.getInt("podiums"),
                        rs.getInt("top5s"), rs.getInt("dnfs")))
                .list();

        // A quali claim needs no driver attribution here: whoever set the time,
        // the entry — and so the team — owns the result.
        List<TeamQualiAgg> qualiAggs = db.sql("""
                        SELECT en.team_id, en.class_name,
                               count(*) FILTER (WHERE r.position_in_class IS NOT NULL) AS sessions,
                               count(*) FILTER (WHERE r.position_in_class = 1)  AS poles,
                               count(*) FILTER (WHERE r.position_in_class <= 5) AS top5s
                        FROM result r
                                 JOIN race_session rs ON rs.id = r.session_id AND rs.session_type = 'QUALIFYING'
                                 JOIN event ev ON ev.id = rs.event_id
                                 JOIN season s ON s.id = ev.season_id
                                 JOIN entry en ON en.id = r.entry_id
                        WHERE %s AND en.team_id IS NOT NULL
                        GROUP BY en.team_id, en.class_name
                        """.formatted(scopePredicate))
                .param("id", id)
                .query((rs, i) -> new TeamQualiAgg(rs.getLong("team_id"), rs.getString("class_name"),
                        rs.getInt("sessions"), rs.getInt("poles"), rs.getInt("top5s")))
                .list();

        // Car numbers per team × class from its latest in-scope event, so the
        // column reads as "what they run now" (JDC-Miller shows #5, not #85).
        record CarKey(long teamId, String className) {
        }
        Map<CarKey, String> carNumbers = new LinkedHashMap<>();
        db.sql("""
                        SELECT DISTINCT ON (en.team_id, en.class_name)
                               en.team_id, en.class_name,
                               (SELECT string_agg(DISTINCT e2.car_number, ' ' ORDER BY e2.car_number)
                                FROM entry e2
                                WHERE e2.event_id = ev.id AND e2.team_id = en.team_id
                                  AND e2.class_name = en.class_name) AS car_numbers
                        FROM entry en
                                 JOIN event ev ON ev.id = en.event_id
                                 JOIN season s ON s.id = ev.season_id
                        WHERE %s AND en.team_id IS NOT NULL
                        ORDER BY en.team_id, en.class_name, ev.event_date DESC NULLS LAST, ev.id DESC
                        """.formatted(scopePredicate))
                .param("id", id)
                .query((rs, i) -> carNumbers.put(
                        new CarKey(rs.getLong("team_id"), rs.getString("class_name")),
                        rs.getString("car_numbers")))
                .list();

        List<FormatInfo> formats = formatColumns(scopePredicate, id,
                raceAggs.stream().anyMatch(a -> a.formatId() == null));

        record RowKey(long teamId, String className) {
        }
        Map<RowKey, List<TeamRaceAgg>> byRow = new LinkedHashMap<>();
        for (TeamRaceAgg a : raceAggs) {
            byRow.computeIfAbsent(new RowKey(a.teamId(), a.className()), k -> new ArrayList<>()).add(a);
        }
        Map<RowKey, TeamQualiAgg> qualiByRow = new LinkedHashMap<>();
        for (TeamQualiAgg q : qualiAggs) {
            qualiByRow.put(new RowKey(q.teamId(), q.className()), q);
        }
        // A team can qualify without racing (quali-only import); those rows
        // still deserve a line.
        for (TeamQualiAgg q : qualiAggs) {
            byRow.computeIfAbsent(new RowKey(q.teamId(), q.className()), k -> new ArrayList<>());
        }

        Map<Long, String> teamNames = new LinkedHashMap<>();
        for (TeamRaceAgg a : raceAggs) {
            teamNames.putIfAbsent(a.teamId(), a.name());
        }
        List<Long> unnamed = byRow.keySet().stream().map(RowKey::teamId)
                .filter(t -> !teamNames.containsKey(t)).distinct().toList();
        if (!unnamed.isEmpty()) {
            db.sql("SELECT id, name FROM team WHERE id IN (:ids)")
                    .param("ids", unnamed)
                    .query((rs, i) -> teamNames.put(rs.getLong("id"), rs.getString("name")))
                    .list();
        }

        List<TeamStatsRow> rows = new ArrayList<>();
        for (Map.Entry<RowKey, List<TeamRaceAgg>> e : byRow.entrySet()) {
            List<TeamRaceAgg> aggs = e.getValue();
            List<FormatLine> lines = new ArrayList<>();
            for (FormatInfo f : formats) {
                aggs.stream().filter(a -> Objects.equals(a.formatId(), f.id())).findFirst()
                        .ifPresent(a -> lines.add(new FormatLine(a.formatId(), a.starts(),
                                a.wins(), a.podiums(), a.top5s(), a.dnfs())));
            }
            TeamQualiAgg q = qualiByRow.get(e.getKey());
            rows.add(new TeamStatsRow(e.getKey().teamId(), teamNames.get(e.getKey().teamId()),
                    e.getKey().className(),
                    carNumbers.get(new CarKey(e.getKey().teamId(), e.getKey().className())),
                    lines, new QualiLine(q != null ? q.sessions() : 0,
                    q != null ? q.poles() : 0, q != null ? q.top5s() : 0)));
        }
        rows.sort((a, b) -> {
            int wa = a.byFormat().stream().mapToInt(FormatLine::wins).sum();
            int wb = b.byFormat().stream().mapToInt(FormatLine::wins).sum();
            if (wa != wb) {
                return wb - wa;
            }
            int pa = a.byFormat().stream().mapToInt(FormatLine::podiums).sum();
            int pb = b.byFormat().stream().mapToInt(FormatLine::podiums).sum();
            if (pa != pb) {
                return pb - pa;
            }
            return a.teamName().compareToIgnoreCase(b.teamName());
        });
        return new TeamStatsTable(formats, rows);
    }
}
