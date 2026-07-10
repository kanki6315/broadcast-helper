package com.broadcasthelper.sheets;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Assembles the pit-lane entry list sheet for an event: per car, the crew with
 * ratings, qualifying result (blank until imported), championship position
 * (teams points by default, per series style), best and last race result of
 * the season so far (strictly before this event), and the livery image.
 */
@RestController
@RequestMapping("/api")
public class SheetController {

    private final JdbcClient db;

    public SheetController(JdbcClient db) {
        this.db = db;
    }

    public record SheetDriver(String name, String rating, boolean isTbd, String nationality) {
    }

    public record SheetEntry(long entryId, String carNumber, String teamName, String vehicle,
                             String manufacturer, Long manufacturerLogoVersion, boolean isGuest,
                             List<SheetDriver> drivers, String qualifying, String championship,
                             String best, String last, String priorYearNote, boolean priorYearAuto,
                             Long imageVersion) {
    }

    public record SheetClass(String className, String color, List<SheetEntry> entries) {
    }

    public record Sheet(long eventId, String eventName, String circuitName, LocalDate eventDate,
                        int year, Integer roundOrdinal, String seriesName, String championshipLabel,
                        String priorYearLabel, List<SheetClass> classes) {
    }

    @GetMapping("/events/{id}/sheet")
    public Sheet sheet(@PathVariable long id) {
        var header = db.sql("""
                        SELECT e.name, e.circuit_name, e.event_date, e.season_id, e.round_ordinal, s.year,
                               sr.id AS series_id, sr.name AS series_name
                        FROM event e JOIN season s ON s.id = e.season_id JOIN series sr ON sr.id = s.series_id
                        WHERE e.id = :id
                        """)
                .param("id", id)
                .query((rs, i) -> new Object[]{rs.getString("name"), rs.getString("circuit_name"),
                        rs.getObject("event_date", LocalDate.class), rs.getLong("season_id"),
                        rs.getObject("round_ordinal", Integer.class), rs.getInt("year"), rs.getString("series_name"),
                        rs.getLong("series_id")})
                .optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such event"));
        String eventName = (String) header[0];
        String circuitName = (String) header[1];
        LocalDate eventDate = (LocalDate) header[2];
        long seasonId = (Long) header[3];
        Integer roundOrdinal = (Integer) header[4];
        int year = (Integer) header[5];
        String seriesName = (String) header[6];
        long seriesId = (Long) header[7];

        // Per-series class display config (order + header colour); classes with
        // no row fall back to a neutral colour sorted last.
        record ClassStyle(int ordinal, String color) {
        }
        Map<String, ClassStyle> classStyles = new HashMap<>();
        db.sql("SELECT class_code, ordinal, color FROM class_style WHERE series_id = :seriesId")
                .param("seriesId", seriesId)
                .query((rs, i) -> classStyles.put(rs.getString("class_code"),
                        new ClassStyle(rs.getInt("ordinal"), rs.getString("color"))))
                .list();

        // Default champ column: the series' own TEAMS standings per class (teams
        // points for team-based series — see PLAN.md decisions), as they stood
        // *going into* this round. Standings points live on each championship's
        // own calendar; group its sessions into rounds and sum only the rounds
        // before this event's round_ordinal, then rank per class. Round 1 (or an
        // event with no ordinal yet) has no prior rounds, so the column is blank.
        record ChampRow(String className, String key, int position, double points) {
        }
        record ChampAgg(String className, String key, double points) {
        }
        // The primary (non-cup) championship to show per class: teams points for a
        // team-based series, drivers points for a single-driver series. Prefer a
        // TEAMS championship when one exists, else DRIVERS. Its kind decides how a
        // car is matched to a standings row (car number vs driver name).
        record PrimaryChamp(long id, String className, String kind) {
        }
        Map<String, PrimaryChamp> chosenByClass = new HashMap<>();
        db.sql("""
                        SELECT c.id, c.class_name, g.kind
                        FROM championship c JOIN championship_group g ON g.id = c.group_id
                        WHERE c.season_id = :seasonId AND g.is_cup = false
                        """)
                .param("seasonId", seasonId)
                .query((rs, i) -> new PrimaryChamp(rs.getLong("id"), rs.getString("class_name"), rs.getString("kind")))
                .list()
                .forEach(pc -> chosenByClass.merge(pc.className(), pc,
                        (a, b) -> champKindRank(a.kind()) <= champKindRank(b.kind()) ? a : b));
        Map<String, String> champKindByClass = new HashMap<>();
        java.util.Set<Long> champIds = new java.util.HashSet<>();
        chosenByClass.forEach((cls, pc) -> {
            champKindByClass.put(cls, pc.kind());
            champIds.add(pc.id());
        });

        Map<String, Map<String, ChampRow>> champByClass = new HashMap<>();
        Map<String, List<ChampAgg>> aggByClass = new LinkedHashMap<>();
        if (!champIds.isEmpty() && roundOrdinal != null) {
            db.sql("""
                            SELECT c.class_name, sr.competitor_key, COALESCE(sum(ssp.total_points), 0) AS points
                            FROM standings_row sr
                                     JOIN championship c ON c.id = sr.championship_id
                                     JOIN standings_session_points ssp ON ssp.standings_row_id = sr.id
                                     JOIN (
                                         SELECT championship_id, session_index,
                                                dense_rank() OVER (PARTITION BY championship_id ORDER BY first_idx) AS round_no
                                         FROM (
                                             SELECT championship_id, session_index,
                                                    min(session_index) OVER (PARTITION BY championship_id, event_name) AS first_idx
                                             FROM championship_session
                                         ) t
                                     ) rnd ON rnd.championship_id = c.id AND rnd.session_index = ssp.session_index
                            WHERE c.id IN (:champIds) AND rnd.round_no < :round
                            GROUP BY c.class_name, sr.competitor_key
                            """)
                    .param("champIds", champIds)
                    .param("round", roundOrdinal)
                    .query((rs, i) -> new ChampAgg(rs.getString("class_name"), rs.getString("competitor_key"),
                            rs.getDouble("points")))
                    .list()
                    .forEach(a -> aggByClass.computeIfAbsent(a.className(), k -> new ArrayList<>()).add(a));
        }

        // Rank each class by points (desc); ties share a position (1, 2, 2, 4).
        aggByClass.forEach((className, aggs) -> {
            aggs.sort(Comparator.comparingDouble(ChampAgg::points).reversed());
            double lastPoints = Double.NaN;
            int lastPos = 0;
            for (int i = 0; i < aggs.size(); i++) {
                ChampAgg a = aggs.get(i);
                int pos = a.points() == lastPoints ? lastPos : i + 1;
                lastPoints = a.points();
                lastPos = pos;
                champByClass.computeIfAbsent(className, k -> new HashMap<>())
                        .put(a.key(), new ChampRow(className, a.key(), pos, a.points()));
            }
        });

        // Qualifying result for this event (blank until a quali file is imported).
        Map<Long, Integer> quali = new HashMap<>();
        db.sql("""
                        SELECT r.entry_id, min(r.position_in_class) AS pos
                        FROM result r JOIN race_session rs ON rs.id = r.session_id
                        WHERE rs.event_id = :id AND rs.session_type = 'QUALIFYING'
                        GROUP BY r.entry_id
                        """)
                .param("id", id)
                .query((rs, i) -> quali.put(rs.getLong("entry_id"), rs.getInt("pos")))
                .list();

        // Last year's result at this venue, auto-passed when car number and team
        // carry over without significant change; a manual prior_year_note wins.
        record PriorYearResult(String team, String className, int positionInClass, String status) {
        }
        Map<String, PriorYearResult> priorYear = new HashMap<>();
        String venue = venueAbbrev(eventName, circuitName);
        db.sql("""
                        SELECT en.car_number, en.team_name, en.class_name, r.position_in_class, r.status,
                               e.name AS event_name, e.circuit_name, e.event_date
                        FROM result r
                                 JOIN race_session rs ON rs.id = r.session_id AND rs.session_type = 'RACE'
                                 JOIN entry en ON en.id = r.entry_id
                                 JOIN event e ON e.id = en.event_id
                                 JOIN season s ON s.id = e.season_id
                        WHERE s.series_id = (SELECT series_id FROM season WHERE id = :seasonId)
                          AND s.year = :priorYear
                        ORDER BY e.event_date
                        """)
                .param("seasonId", seasonId)
                .param("priorYear", year - 1)
                .query((rs, i) -> {
                    if (venueAbbrev(rs.getString("event_name"), rs.getString("circuit_name")).equals(venue)) {
                        priorYear.put(rs.getString("car_number"),
                                new PriorYearResult(rs.getString("team_name"), rs.getString("class_name"),
                                        rs.getInt("position_in_class"), rs.getString("status")));
                    }
                    return null;
                })
                .list();

        // Season race results strictly before this event, per (car, class).
        // positionInClass is null for a DNS (no finishing position).
        record PriorResult(LocalDate date, String eventName, String circuit, Integer positionInClass) {
        }
        Map<String, List<PriorResult>> priorByCar = new HashMap<>();
        db.sql("""
                        SELECT en.car_number, en.class_name, e.name AS event_name, e.circuit_name, e.event_date,
                               r.position_in_class
                        FROM result r
                                 JOIN race_session rs ON rs.id = r.session_id AND rs.session_type = 'RACE'
                                 JOIN entry en ON en.id = r.entry_id
                                 JOIN event e ON e.id = en.event_id
                        WHERE e.season_id = :seasonId AND e.event_date < :date
                        """)
                .param("seasonId", seasonId)
                .param("date", eventDate)
                .query((rs, i) -> priorByCar
                        .computeIfAbsent(rs.getString("car_number") + "|" + rs.getString("class_name"),
                                k -> new ArrayList<>())
                        .add(new PriorResult(rs.getObject("event_date", LocalDate.class),
                                rs.getString("event_name"), rs.getString("circuit_name"),
                                rs.getObject("position_in_class", Integer.class))))
                .list();

        record EntryRow(long entryId, String carNumber, String className, String teamName, String vehicle,
                        String manufacturer, OffsetDateTime logoUploadedAt, boolean isGuest,
                        String priorYearNote, OffsetDateTime imageUploadedAt) {
        }
        List<EntryRow> entryRows = db.sql("""
                        SELECT en.id, en.car_number, en.class_name, en.team_name, en.vehicle, en.manufacturer,
                               en.is_guest, en.prior_year_note, ci.uploaded_at AS image_uploaded_at,
                               ml.uploaded_at AS logo_uploaded_at
                        FROM entry en
                                 LEFT JOIN car_image ci ON ci.season_id = :seasonId AND ci.car_number = en.car_number
                                 LEFT JOIN manufacturer_logo ml ON ml.name = lower(trim(en.manufacturer))
                        WHERE en.event_id = :id
                        """)
                .param("id", id)
                .param("seasonId", seasonId)
                .query((rs, i) -> new EntryRow(rs.getLong("id"), rs.getString("car_number"),
                        rs.getString("class_name"), rs.getString("team_name"), rs.getString("vehicle"),
                        rs.getString("manufacturer"), rs.getObject("logo_uploaded_at", OffsetDateTime.class),
                        rs.getBoolean("is_guest"), rs.getString("prior_year_note"),
                        rs.getObject("image_uploaded_at", OffsetDateTime.class)))
                .list();

        Map<Long, List<SheetDriver>> driversByEntry = new HashMap<>();
        db.sql("""
                        SELECT da.entry_id, da.rating, da.is_tbd, d.country,
                               COALESCE(d.first_name || ' ' || d.surname, 'TBD') AS name
                        FROM driver_assignment da LEFT JOIN driver d ON d.id = da.driver_id
                        WHERE da.entry_id IN (SELECT id FROM entry WHERE event_id = :id)
                        ORDER BY da.seat_order
                        """)
                .param("id", id)
                .query((rs, i) -> driversByEntry
                        .computeIfAbsent(rs.getLong("entry_id"), k -> new ArrayList<>())
                        .add(new SheetDriver(rs.getString("name"), rs.getString("rating"),
                                rs.getBoolean("is_tbd"), rs.getString("country"))))
                .list();

        // Assemble, grouped by class in the order classes appear when sorted by
        // the class's best overall finishing position (falls back to name).
        Map<String, List<SheetEntry>> byClass = new LinkedHashMap<>();
        entryRows.stream()
                .sorted(Comparator.comparing((EntryRow r) -> r.className())
                        .thenComparing(r -> numericValue(r.carNumber()))
                        .thenComparing(EntryRow::carNumber))
                .forEach(r -> {
                    List<PriorResult> prior = priorByCar.getOrDefault(r.carNumber() + "|" + r.className(), List.of());

                    // Best is the strongest actual finish; DNS rounds (null
                    // position) don't count. Ties across venues list all venues.
                    String best = null;
                    List<PriorResult> classified = prior.stream()
                            .filter(p -> p.positionInClass() != null)
                            .toList();
                    if (!classified.isEmpty()) {
                        int bestPos = classified.stream().mapToInt(PriorResult::positionInClass).min().orElseThrow();
                        List<String> venues = classified.stream()
                                .filter(p -> p.positionInClass() == bestPos)
                                .sorted(Comparator.comparing(PriorResult::date))
                                .map(p -> venueAbbrev(p.eventName(), p.circuit()))
                                .distinct()
                                .toList();
                        best = ordinal(bestPos) + " – " + String.join("/", venues);
                    }

                    // Last shows the finishing position; a DNS has none, so "DNS".
                    String last = prior.stream().max(Comparator.comparing(PriorResult::date))
                            .map(p -> p.positionInClass() == null ? "DNS" : ordinal(p.positionInClass()))
                            .orElse(null);

                    Map<String, ChampRow> classChamp = champByClass.getOrDefault(r.className(), Map.of());
                    String champText = null;
                    if (r.isGuest()) {
                        champText = "GUEST";
                    } else if ("DRIVERS".equals(champKindByClass.get(r.className()))) {
                        // Drivers championship: match each crew member by name. A
                        // single-driver car shows one position + points; a crew
                        // lists each member's standing (see PLAN's decisions).
                        List<SheetDriver> drivers = driversByEntry.getOrDefault(r.entryId(), List.of());
                        List<ChampRow> hits = drivers.stream()
                                .map(d -> byNormalizedKey(classChamp, d.name()))
                                .filter(java.util.Objects::nonNull)
                                .toList();
                        if (hits.size() == 1) {
                            champText = ordinal(hits.get(0).position()) + " (" + formatPoints(hits.get(0).points()) + " pts)";
                        } else if (hits.size() > 1) {
                            champText = hits.stream()
                                    .map(cr -> ordinal(cr.position()) + " " + surname(cr.key()))
                                    .reduce((a, b) -> a + " · " + b).orElse(null);
                        }
                    } else {
                        ChampRow champ = classChamp.get(r.carNumber());
                        if (champ != null) {
                            champText = ordinal(champ.position()) + " (" + formatPoints(champ.points()) + " pts)";
                        }
                    }

                    String priorText = r.priorYearNote();
                    boolean priorAuto = false;
                    if (priorText == null) {
                        PriorYearResult py = priorYear.get(r.carNumber());
                        if (py != null && similarTeams(r.teamName(), py.team())) {
                            priorText = "Not Started".equalsIgnoreCase(py.status())
                                    ? "DNS" : ordinal(py.positionInClass());
                            if (!py.className().equals(r.className())) {
                                priorText += " (" + py.className() + ")";
                            }
                            priorAuto = true;
                        }
                    }

                    Integer qualiPos = quali.get(r.entryId());
                    byClass.computeIfAbsent(r.className(), k -> new ArrayList<>())
                            .add(new SheetEntry(r.entryId(), r.carNumber(), r.teamName(), r.vehicle(),
                                    r.manufacturer(),
                                    r.logoUploadedAt() != null ? r.logoUploadedAt().toInstant().toEpochMilli() : null,
                                    r.isGuest(), driversByEntry.getOrDefault(r.entryId(), List.of()),
                                    qualiPos != null ? ordinal(qualiPos) : null,
                                    champText, best, last, priorText, priorAuto,
                                    r.imageUploadedAt() != null ? r.imageUploadedAt().toInstant().toEpochMilli() : null));
                });

        String defaultColor = "#1a1a1a";
        List<SheetClass> classes = byClass.entrySet().stream()
                .map(e -> {
                    ClassStyle st = classStyles.get(e.getKey());
                    return new SheetClass(e.getKey(), st != null ? st.color() : defaultColor, e.getValue());
                })
                .sorted(Comparator.comparingInt(c -> {
                    ClassStyle st = classStyles.get(c.className());
                    return st != null ? st.ordinal() : Integer.MAX_VALUE;
                }))
                .toList();
        String priorYearLabel = "'" + String.format("%02d", (year - 1) % 100) + " "
                                + venueAbbrev(eventName, circuitName);
        return new Sheet(id, eventName, circuitName, eventDate, year, roundOrdinal, seriesName,
                seriesName + " " + year + " Teams", priorYearLabel, classes);
    }

    public record NoteRequest(String note) {
    }

    @PatchMapping("/entries/{id}/prior-year-note")
    public void updateNote(@PathVariable long id, @RequestBody NoteRequest request) {
        int updated = db.sql("UPDATE entry SET prior_year_note = :note WHERE id = :id")
                .param("note", request.note() == null || request.note().isBlank() ? null : request.note().trim())
                .param("id", id)
                .update();
        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such entry");
        }
    }

    // ---------------------------------------------------------------- helpers

    private static int numericValue(String carNumber) {
        try {
            return Integer.parseInt(carNumber);
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE;
        }
    }

    /** Championship kind preference for the sheet's champ column: teams first. */
    private static int champKindRank(String kind) {
        return switch (kind == null ? "" : kind) {
            case "TEAMS" -> 0;
            case "DRIVERS" -> 1;
            default -> 2;
        };
    }

    /** Look up a value by key, exact first then case/space-insensitively — so a
     *  standings driver key ("Cole Loftsgard") matches the entry's driver name. */
    private static <V> V byNormalizedKey(Map<String, V> map, String name) {
        if (name == null) {
            return null;
        }
        V exact = map.get(name);
        if (exact != null) {
            return exact;
        }
        String norm = name.trim().toLowerCase();
        for (Map.Entry<String, V> e : map.entrySet()) {
            if (e.getKey() != null && e.getKey().trim().toLowerCase().equals(norm)) {
                return e.getValue();
            }
        }
        return null;
    }

    private static String surname(String fullName) {
        if (fullName == null || fullName.isBlank()) {
            return "";
        }
        String[] parts = fullName.trim().split("\\s+");
        return parts[parts.length - 1];
    }

    static String ordinal(int n) {
        int mod100 = n % 100;
        String suffix = (mod100 >= 11 && mod100 <= 13) ? "th" : switch (n % 10) {
            case 1 -> "st";
            case 2 -> "nd";
            case 3 -> "rd";
            default -> "th";
        };
        return n + suffix;
    }

    private static String formatPoints(double points) {
        return points == Math.floor(points) ? String.valueOf((long) points) : String.valueOf(points);
    }

    /**
     * "Team name hasn't changed significantly": exact token set, one containing
     * the other (sponsor add-ons like "w/Dreyer & Reinbold"), or a majority
     * token overlap. Renames and takeovers stay below the bar on purpose —
     * those cells are left for the broadcaster's judgment.
     */
    static boolean similarTeams(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        var ta = teamTokens(a);
        var tb = teamTokens(b);
        if (ta.isEmpty() || tb.isEmpty()) {
            return false;
        }
        if (ta.equals(tb) || ta.containsAll(tb) || tb.containsAll(ta)) {
            return true;
        }
        var intersection = new java.util.HashSet<>(ta);
        intersection.retainAll(tb);
        var union = new java.util.HashSet<>(ta);
        union.addAll(tb);
        return (double) intersection.size() / union.size() >= 0.5;
    }

    private static java.util.Set<String> teamTokens(String name) {
        return java.util.Arrays.stream(name.toLowerCase().replaceAll("[^a-z0-9]+", " ").split(" "))
                .filter(t -> !t.isBlank())
                .collect(java.util.stream.Collectors.toSet());
    }

    /** Venue abbreviations as used on broadcast sheets; falls back to a prefix. */
    public static String venueAbbrev(String eventName, String circuitName) {
        String haystack = ((eventName != null ? eventName : "") + " "
                           + (circuitName != null ? circuitName : "")).toLowerCase();
        if (haystack.contains("daytona")) return "DAY";
        if (haystack.contains("sebring")) return "SEB";
        if (haystack.contains("long beach")) return "LBH";
        if (haystack.contains("laguna") || haystack.contains("monterey")) return "LAG";
        if (haystack.contains("detroit")) return "DET";
        if (haystack.contains("watkins") || haystack.contains("glen")) return "WGI";
        if (haystack.contains("canadian tire") || haystack.contains("bowmanville")) return "CTMP";
        if (haystack.contains("road america")) return "RDA";
        if (haystack.contains("virginia")) return "VIR";
        if (haystack.contains("indianapolis")) return "IMS";
        if (haystack.contains("road atlanta") || haystack.contains("michelin raceway")) return "ATL";
        String base = circuitName != null ? circuitName : eventName != null ? eventName : "???";
        return base.replaceAll("[^A-Za-z]", "").toUpperCase().substring(0, Math.min(3, base.length()));
    }
}
