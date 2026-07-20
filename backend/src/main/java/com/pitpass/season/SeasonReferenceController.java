package com.pitpass.season;

import com.pitpass.sheets.SheetController;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The season reference table: rows = cars, columns = rounds, each cell the car's
 * start → finish (in-class) for that round's race(s). A multi-race weekend shows
 * one line per race. Grouped per class in the series' configured class order.
 * Read-only; reads results (finish) and grid_position (start) that the importers
 * populate.
 */
@RestController
@RequestMapping("/api/seasons/{id}/reference")
public class SeasonReferenceController {

    private final JdbcClient db;

    public SeasonReferenceController(JdbcClient db) {
        this.db = db;
    }

    public record RefRound(int ordinal, String venue, String circuitName, long eventId, int raceCount) {
    }

    /** One race's line in a cell: the car's in-class start and finish (either may
     *  be null — no grid imported, or a DNS with no finishing position). */
    public record RefRace(int raceOrdinal, Integer start, Integer finish, String status) {
    }

    public record RefEntry(String carNumber, String team, boolean isGuest,
                           Map<Integer, List<RefRace>> byRound) {
    }

    public record RefClass(String className, String color, List<RefEntry> entries) {
    }

    public record ReferenceTable(long seasonId, int year, String seriesName,
                                 List<RefRound> rounds, List<RefClass> classes) {
    }

    @GetMapping
    public ReferenceTable reference(@PathVariable long id) {
        Object[] header = db.sql("""
                        SELECT s.year, sr.id AS series_id, sr.name AS series_name
                        FROM season s JOIN series sr ON sr.id = s.series_id
                        WHERE s.id = :id
                        """)
                .param("id", id)
                .query((rs, i) -> new Object[]{rs.getInt("year"), rs.getLong("series_id"), rs.getString("series_name")})
                .optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such season"));
        int year = (Integer) header[0];
        long seriesId = (Long) header[1];
        String seriesName = (String) header[2];

        // Columns: rounds that have at least one race, in calendar order.
        List<RefRound> rounds = db.sql("""
                        SELECT ev.id, ev.round_ordinal, ev.name, ev.circuit_name,
                               (SELECT count(*) FROM race_session rs
                                WHERE rs.event_id = ev.id AND rs.session_type = 'RACE') AS race_count
                        FROM event ev
                        WHERE ev.season_id = :id AND ev.round_ordinal IS NOT NULL
                          AND EXISTS (SELECT 1 FROM race_session rs
                                      WHERE rs.event_id = ev.id AND rs.session_type = 'RACE')
                        ORDER BY ev.round_ordinal
                        """)
                .param("id", id)
                .query((rs, i) -> new RefRound(rs.getInt("round_ordinal"),
                        SheetController.venueAbbrev(rs.getString("name"), rs.getString("circuit_name")),
                        rs.getString("circuit_name"), rs.getLong("id"), rs.getInt("race_count")))
                .list();

        // Per-series class display config (order + colour), same source as the sheet.
        record ClassStyle(int ordinal, String color) {
        }
        Map<String, ClassStyle> classStyles = new HashMap<>();
        db.sql("SELECT class_code, ordinal, color FROM class_style WHERE series_id = :seriesId")
                .param("seriesId", seriesId)
                .query((rs, i) -> classStyles.put(rs.getString("class_code"),
                        new ClassStyle(rs.getInt("ordinal"), rs.getString("color"))))
                .list();

        // One row per (entry, race session): the car's start (grid) and finish
        // (result) in class. A car that didn't run a race yields nulls and is
        // dropped below; team_name is tracked at the latest round for the label.
        record Cell(String className, String carNumber, String team, boolean isGuest,
                    int roundOrdinal, int raceOrdinal, Integer start, Integer finish, String status) {
        }
        List<Cell> cells = db.sql("""
                        SELECT en.class_name, en.car_number, en.team_name, en.is_guest,
                               ev.round_ordinal, rs.ordinal AS race_ordinal,
                               g.position_in_class AS start_pos, r.position_in_class AS finish_pos, r.status
                        FROM entry en
                                 JOIN event ev ON ev.id = en.event_id
                                 JOIN race_session rs ON rs.event_id = ev.id AND rs.session_type = 'RACE'
                                 LEFT JOIN result r ON r.session_id = rs.id AND r.entry_id = en.id
                                 LEFT JOIN grid_position g ON g.session_id = rs.id AND g.entry_id = en.id
                        WHERE ev.season_id = :id AND ev.round_ordinal IS NOT NULL
                        ORDER BY ev.round_ordinal, rs.ordinal
                        """)
                .param("id", id)
                .query((rs, i) -> new Cell(rs.getString("class_name"), rs.getString("car_number"),
                        rs.getString("team_name"), rs.getBoolean("is_guest"),
                        rs.getInt("round_ordinal"), rs.getInt("race_ordinal"),
                        (Integer) rs.getObject("start_pos"), (Integer) rs.getObject("finish_pos"),
                        rs.getString("status")))
                .list();

        // Assemble: class -> car -> round -> races. Row identity is (class, car),
        // the same key best/last uses; a car that switched class appears in both.
        record CarKey(String className, String carNumber) {
        }
        Map<CarKey, RefEntry> entries = new LinkedHashMap<>();
        Map<CarKey, Integer> latestRound = new HashMap<>();
        Map<CarKey, String> latestTeam = new HashMap<>();
        Map<CarKey, Boolean> guest = new HashMap<>();
        for (Cell c : cells) {
            // A row with no start and no finish means the car wasn't in this race.
            if (c.start() == null && c.finish() == null && c.status() == null) {
                continue;
            }
            CarKey key = new CarKey(c.className(), c.carNumber());
            entries.computeIfAbsent(key, k -> new RefEntry(c.carNumber(), null, false, new LinkedHashMap<>()))
                    .byRound().computeIfAbsent(c.roundOrdinal(), r -> new ArrayList<>())
                    .add(new RefRace(c.raceOrdinal(), c.start(), c.finish(), c.status()));
            if (c.roundOrdinal() >= latestRound.getOrDefault(key, 0)) {
                latestRound.put(key, c.roundOrdinal());
                latestTeam.put(key, c.team());
                guest.put(key, c.isGuest());
            }
        }

        // Group into classes, ordered by class_style; cars by number within class.
        String defaultColor = "#1a1a1a";
        Map<String, List<RefEntry>> byClass = new LinkedHashMap<>();
        entries.forEach((key, e) -> byClass.computeIfAbsent(key.className(), k -> new ArrayList<>())
                .add(new RefEntry(e.carNumber(), latestTeam.get(key), guest.getOrDefault(key, false), e.byRound())));

        List<RefClass> classes = byClass.entrySet().stream()
                .map(e -> {
                    List<RefEntry> sorted = e.getValue().stream()
                            .sorted(Comparator.comparingInt((RefEntry r) -> numericValue(r.carNumber()))
                                    .thenComparing(RefEntry::carNumber))
                            .toList();
                    ClassStyle st = classStyles.get(e.getKey());
                    return new RefClass(e.getKey(), st != null ? st.color() : defaultColor, sorted);
                })
                .sorted(Comparator.comparingInt(c -> {
                    ClassStyle st = classStyles.get(c.className());
                    return st != null ? st.ordinal() : Integer.MAX_VALUE;
                }))
                .toList();

        return new ReferenceTable(id, year, seriesName, rounds, classes);
    }

    private static int numericValue(String carNumber) {
        try {
            return Integer.parseInt(carNumber);
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE;
        }
    }
}
