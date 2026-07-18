package com.broadcasthelper.imports;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses timing-provider JSON files into normalized import records.
 *
 * Known format quirks (all seen in real 2026 IMSA files):
 * - standings files carry a UTF-8 BOM (Jackson strips it when parsing bytes)
 * - session_date is "dd-MM-yyyy HH:mm" in most files but "dd/MM/yyyy HH:mm"
 *   in others (Detroit)
 * - car numbers keep leading zeros and must never be treated as integers
 * - classification positions are overall; in-class positions are derived here
 *   from overall order, which preserves penalty demotions
 */
public final class ImportParser {

    private static final DateTimeFormatter[] SESSION_DATE_FORMATS = {
            DateTimeFormatter.ofPattern("dd-MM-uuuu HH:mm"),
            DateTimeFormatter.ofPattern("dd/MM/uuuu HH:mm"),
    };

    private ImportParser() {
    }

    public static boolean looksLikeRaceResults(JsonNode root) {
        return root.has("session") && root.has("classification");
    }

    public static boolean looksLikeStandings(JsonNode root) {
        return root.has("championship") && root.has("classification");
    }

    public static boolean looksLikeEntryList(JsonNode root) {
        return root.has("event") && root.has("entries");
    }

    public static boolean looksLikeGrid(JsonNode root) {
        return root.has("session") && root.has("grid");
    }

    public static boolean looksLikeFlags(JsonNode root) {
        return root.has("session") && root.has("flags");
    }

    /** FlagsAnalysisWithRCMessages: the shared session header plus the session's
     *  chronological flag/RC-message stream. Rows keep source order — that IS
     *  the timeline. */
    public static FlagsImport parseFlags(JsonNode root) {
        JsonNode session = root.path("session");
        JsonNode circuit = session.path("circuit");

        List<FlagsImport.FlagRow> rows = new ArrayList<>();
        for (JsonNode f : root.path("flags")) {
            rows.add(new FlagsImport.FlagRow(
                    text(f, "time"),
                    text(f, "elapsed"),
                    text(f, "rec_type"),
                    text(f, "flag"),
                    text(f, "message"),
                    text(f, "flag_time"),
                    text(f, "accum_time"),
                    intOrNull(f, "lap")
            ));
        }

        return new FlagsImport(
                text(session, "championship_name"),
                text(session, "event_name"),
                text(session, "session_name"),
                text(session, "session_type"),
                sessionOrdinal(text(session, "session_name")),
                text(session, "report_mark"),
                text(session, "report_message"),
                parseSessionDate(text(session, "session_date")),
                text(circuit, "name"),
                doubleOrNull(circuit, "length"),
                text(circuit, "country"),
                rows
        );
    }

    public static GridImport parseGrid(JsonNode root) {
        JsonNode session = root.path("session");
        JsonNode circuit = session.path("circuit");

        List<GridImport.Row> rows = new ArrayList<>();
        Map<String, Integer> classCounters = new HashMap<>();
        for (JsonNode g : root.path("grid")) {
            // A blank slot (no car number) is a gap in the grid — a car that
            // qualified but withdrew. It carries no class, so it neither becomes a
            // row nor advances any class counter.
            String number = text(g, "number");
            if (number == null) {
                continue;
            }
            String className = text(g, "class");
            Integer inClass = classCounters.merge(className, 1, Integer::sum);
            // The grid names who qualified the car and who takes the start as
            // 1-based seat indexes into its own drivers[] roster (same per-car
            // numbering as a results file). Seat 0 means "no seat named", the
            // same convention as fastest_lap_driver_number.
            List<RaceResultsImport.DriverRow> drivers = new ArrayList<>();
            for (JsonNode d : g.path("drivers")) {
                drivers.add(new RaceResultsImport.DriverRow(
                        d.path("number").asInt(),
                        text(d, "firstname"),
                        text(d, "surname"),
                        text(d, "license"),
                        text(d, "hometown"),
                        text(d, "country")
                ));
            }
            rows.add(new GridImport.Row(
                    g.path("position").asInt(),
                    inClass,
                    number,
                    className,
                    text(g, "group"),
                    text(g, "team"),
                    text(g, "vehicle"),
                    text(g, "manufacturer"),
                    null, // JSON grids carry no qualifying time
                    seatOrNull(g, "starting_driver_number"),
                    seatOrNull(g, "qualifying_driver_number"),
                    drivers
            ));
        }

        return new GridImport(
                text(session, "championship_name"),
                text(session, "event_name"),
                text(session, "session_name"),
                text(session, "session_type"),
                sessionOrdinal(text(session, "session_name")),
                parseSessionDate(text(session, "session_date")),
                text(circuit, "name"),
                doubleOrNull(circuit, "length"),
                text(circuit, "country"),
                rows
        );
    }

    /** Sniffs the IMSA starting-grid CSV by its header (after any UTF-8 BOM). */
    public static boolean looksLikeGridCsv(byte[] content) {
        String text = stripBom(new String(content, java.nio.charset.StandardCharsets.UTF_8));
        int eol = text.indexOf('\n');
        String firstLine = (eol >= 0 ? text.substring(0, eol) : text).trim();
        return firstLine.toUpperCase().startsWith("POSITION;CLASS;NUMBER");
    }

    /**
     * Parses a published starting-grid CSV (semicolon-delimited, header
     * POSITION;CLASS;NUMBER;...;TEAM;CAR;TIME;). Real files carry a UTF-8 BOM,
     * CRLF line endings, and a trailing semicolon per line. The
     * STARTING_DRIVER / QUALIFYING_DRIVER names are resolved to seat indexes
     * against the row's own DRIVER_1..6 columns (DRIVER_N = seat N); the name
     * columns themselves build no roster — a single full-name string can't be
     * split into first/surname without corrupting the driver identity key, so
     * the entry list stays the driver authority and commit resolves seats
     * through it. The file has no session or event metadata, so all of it is
     * null here; the reviewer supplies the event and session at commit.
     */
    public static GridImport parseGridCsv(byte[] content) {
        String text = stripBom(new String(content, java.nio.charset.StandardCharsets.UTF_8));
        String[] lines = text.split("\\R");
        if (lines.length == 0) {
            throw new IllegalArgumentException("Empty CSV file");
        }

        Map<String, Integer> header = new HashMap<>();
        String[] headerCells = lines[0].split(";", -1);
        for (int i = 0; i < headerCells.length; i++) {
            header.put(headerCells[i].trim().toUpperCase(), i);
        }
        for (String required : new String[]{"POSITION", "CLASS", "NUMBER", "TEAM", "CAR", "TIME"}) {
            if (!header.containsKey(required)) {
                throw new IllegalArgumentException(
                        "Not a recognized grid CSV: expected header POSITION;CLASS;NUMBER;...;TEAM;CAR;TIME"
                        + " but found no " + required + " column");
            }
        }

        List<GridImport.Row> rows = new ArrayList<>();
        Map<String, Integer> classCounters = new HashMap<>();
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].isBlank()) {
                continue;
            }
            String[] cells = lines[i].split(";", -1);
            // A blank slot (no car number) is a gap in the grid, same as the
            // JSON format: it neither becomes a row nor advances a counter.
            String number = cell(cells, header.get("NUMBER"));
            if (number == null) {
                continue;
            }
            String position = cell(cells, header.get("POSITION"));
            if (position == null) {
                continue;
            }
            String className = cell(cells, header.get("CLASS"));
            Integer inClass = classCounters.merge(className, 1, Integer::sum);
            rows.add(new GridImport.Row(
                    Integer.parseInt(position),
                    inClass,
                    number,
                    className,
                    null,
                    cell(cells, header.get("TEAM")),
                    cell(cells, header.get("CAR")),
                    null,
                    cell(cells, header.get("TIME")),
                    seatOfName(cellOrNull(cells, header.get("STARTING_DRIVER")), cells, header),
                    seatOfName(cellOrNull(cells, header.get("QUALIFYING_DRIVER")), cells, header),
                    List.of()
            ));
        }

        return new GridImport(null, null, null, null, 1, null, null, null, null, rows);
    }

    private static String stripBom(String text) {
        return text.startsWith("\uFEFF") ? text.substring(1) : text;
    }

    private static String cell(String[] cells, int index) {
        if (index >= cells.length) {
            return null;
        }
        String value = cells[index].trim();
        return value.isEmpty() ? null : value;
    }

    /** Like {@link #cell} but for an optional column that may be absent. */
    private static String cellOrNull(String[] cells, Integer index) {
        return index == null ? null : cell(cells, index);
    }

    /** A 1-based seat index; 0 or absent means "no seat named" (the same
     *  convention the results files use for fastest_lap_driver_number). */
    private static Integer seatOrNull(JsonNode node, String field) {
        Integer value = intOrNull(node, field);
        return value != null && value > 0 ? value : null;
    }

    /** Resolves a grid CSV attribution name ("Hannah Grisham") to its seat by
     *  matching it against the row's own DRIVER_1..6 columns (DRIVER_N = seat
     *  N), case-insensitively with whitespace collapsed. No match — a typo, or
     *  a file without driver columns — resolves to null, never a guess. */
    private static Integer seatOfName(String name, String[] cells, Map<String, Integer> header) {
        if (name == null) {
            return null;
        }
        String needle = name.trim().replaceAll("\\s+", " ");
        for (int seat = 1; seat <= 6; seat++) {
            String candidate = cellOrNull(cells, header.get("DRIVER_" + seat));
            if (candidate != null && candidate.replaceAll("\\s+", " ").equalsIgnoreCase(needle)) {
                return seat;
            }
        }
        return null;
    }

    public static EntryListImport parseEntryList(JsonNode root) {
        JsonNode ev = root.path("event");
        EntryListImport.Event event = new EntryListImport.Event(
                text(ev, "name"),
                text(ev, "circuit"),
                text(ev, "location"),
                text(ev, "series"),
                dateOrNull(ev, "start_date"),
                dateOrNull(ev, "end_date"),
                intOrNull(ev, "total_entries"),
                text(ev, "source_file")
        );

        List<EntryListImport.Entry> entries = new ArrayList<>();
        for (JsonNode e : root.path("entries")) {
            List<EntryListImport.Driver> drivers = new ArrayList<>();
            for (JsonNode d : e.path("drivers")) {
                List<String> markers = new ArrayList<>();
                for (JsonNode m : d.path("markers")) {
                    markers.add(m.asText());
                }
                drivers.add(new EntryListImport.Driver(
                        d.path("order").asInt(),
                        text(d, "rating"),
                        text(d, "name"),
                        text(d, "nationality"),
                        text(d, "hometown"),
                        markers,
                        d.path("is_tbd").asBoolean(false),
                        d.path("unparsed").asBoolean(false)
                ));
            }
            entries.add(new EntryListImport.Entry(
                    text(e, "class_name"),
                    text(e, "class_code"),
                    intOrNull(e, "class_order"),
                    text(e, "car_number"),
                    text(e, "team"),
                    text(e, "sponsor"),
                    text(e, "team_nationality"),
                    e.path("bronze_cup").asBoolean(false),
                    e.path("dealer_trophy").asBoolean(false),
                    text(e, "car_type"),
                    text(e, "tire"),
                    text(e, "engine"),
                    text(e, "fuel"),
                    drivers
            ));
        }
        return new EntryListImport(event, entries);
    }

    public static RaceResultsImport parseRaceResults(JsonNode root) {
        JsonNode session = root.path("session");
        JsonNode circuit = session.path("circuit");

        List<RaceResultsImport.Row> rows = new ArrayList<>();
        Map<String, Integer> classCounters = new HashMap<>();
        for (JsonNode c : root.path("classification")) {
            String className = text(c, "class");
            // A car that did not start has no in-class position in the source
            // classification; don't count it (which would fabricate one) and
            // leave its position_in_class null.
            Integer inClass = didNotStart(text(c, "status"))
                    ? null
                    : classCounters.merge(className, 1, Integer::sum);

            List<RaceResultsImport.DriverRow> drivers = new ArrayList<>();
            for (JsonNode d : c.path("drivers")) {
                drivers.add(new RaceResultsImport.DriverRow(
                        d.path("number").asInt(),
                        text(d, "firstname"),
                        text(d, "surname"),
                        text(d, "license"),
                        text(d, "hometown"),
                        text(d, "country")
                ));
            }

            // A race classification names each car's best lap as fastest_lap_*;
            // a "Qualifying Practice by Best Lap" file names the same three facts
            // as plain time / lap / kph (the lap IS the classifying lap there).
            // Read fastest_lap_* first so races are unchanged, and fall back to
            // the qualifying spelling — otherwise every qualifying entry stores a
            // null best lap, which is exactly what happened.
            rows.add(new RaceResultsImport.Row(
                    c.path("position").asInt(),
                    inClass,
                    text(c, "number"),
                    className,
                    text(c, "group"),
                    text(c, "team"),
                    text(c, "vehicle"),
                    text(c, "manufacturer"),
                    text(c, "status"),
                    c.path("not_finished").asBoolean(false),
                    text(c, "not_finished_cause"),
                    intOrNull(c, "laps"),
                    text(c, "elapsed_time"),
                    text(c, "gap_first"),
                    text(c, "gap_previous"),
                    firstText(c, "fastest_lap_time", "time"),
                    firstInt(c, "fastest_lap_number", "lap"),
                    firstDouble(c, "fastest_lap_kph", "kph"),
                    intOrNull(c, "fastest_lap_driver_number"),
                    intOrNull(c, "pit_stops"),
                    drivers
            ));
        }

        return new RaceResultsImport(
                text(session, "championship_name"),
                text(session, "event_name"),
                text(session, "session_name"),
                text(session, "session_type"),
                sessionOrdinal(text(session, "session_name")),
                text(session, "report_mark"),
                text(session, "report_message"),
                parseSessionDate(text(session, "session_date")),
                text(circuit, "name"),
                doubleOrNull(circuit, "length"),
                text(circuit, "country"),
                rows
        );
    }

    public static StandingsImport parseStandings(JsonNode root) {
        JsonNode ch = root.path("championship");

        List<StandingsImport.SessionRef> sessions = new ArrayList<>();
        for (JsonNode s : ch.path("sessions")) {
            sessions.add(new StandingsImport.SessionRef(
                    s.path("session_index").asInt(),
                    text(s, "event_name"),
                    text(s, "session_name")
            ));
        }

        List<StandingsImport.Row> rows = new ArrayList<>();
        for (JsonNode r : root.path("classification")) {
            List<StandingsImport.SessionPoints> points = new ArrayList<>();
            for (JsonNode p : r.path("points_by_session")) {
                points.add(new StandingsImport.SessionPoints(
                        p.path("session_index").asInt(),
                        p.path("total_points").asDouble(),
                        p.path("race_points").asDouble(),
                        p.path("pole_points").asDouble(),
                        p.path("fastest_lap_points").asDouble(),
                        p.path("penalty_points").asDouble(),
                        // Absent from a real standings JSON, which splits its
                        // extras properly; only the points-PDF sidecar sets it.
                        p.path("bonus_points").asDouble(),
                        text(p, "status")
                ));
            }
            rows.add(new StandingsImport.Row(
                    r.path("position").asInt(),
                    text(r, "key"),
                    text(r, "team"),
                    r.path("total_points").asDouble(),
                    intOrNull(r, "net_position"),
                    doubleOrNull(r, "total_net_points"),
                    // No adjustment concept in a published standings file: its
                    // corrections are already folded into the session points.
                    null,
                    points
            ));
        }

        return new StandingsImport(
                text(ch, "name"),
                text(ch, "main_title"),
                text(ch, "sub_title"),
                text(ch, "year"),
                sessions,
                rows
        );
    }

    static LocalDateTime parseSessionDate(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        for (DateTimeFormatter format : SESSION_DATE_FORMATS) {
            try {
                return LocalDateTime.parse(raw, format);
            } catch (DateTimeParseException ignored) {
                // try the next known format
            }
        }
        throw new IllegalArgumentException("Unrecognized session_date format: '" + raw + "'");
    }

    private static String text(JsonNode node, String field) {
        String value = node.path(field).asText("");
        return value.isBlank() ? null : value.trim();
    }

    /** First of {@code fields} that yields a non-blank value, else null. Used
     *  where a race file and a qualifying file spell the same fact differently. */
    private static String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            String value = text(node, field);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static Integer firstInt(JsonNode node, String... fields) {
        for (String field : fields) {
            Integer value = intOrNull(node, field);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static Double firstDouble(JsonNode node, String... fields) {
        for (String field : fields) {
            Double value = doubleOrNull(node, field);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    /**
     * The 1-based ordinal within a session type, read from the trailing number of
     * the session name ("Race 2" -> 2). A name with no number is the sole session
     * of its type ("Race", "Qualifying") -> 1. This is the stable per-event key
     * (with session_type), so re-import overwrites regardless of name drift.
     */
    private static int sessionOrdinal(String sessionName) {
        if (sessionName != null) {
            var m = java.util.regex.Pattern.compile("(\\d+)\\s*$").matcher(sessionName);
            if (m.find()) {
                return Integer.parseInt(m.group(1));
            }
        }
        return 1;
    }

    /** A car that did not take the start: no in-class finishing position. */
    private static boolean didNotStart(String status) {
        if (status == null) {
            return false;
        }
        return switch (status.trim().toLowerCase()) {
            case "not started", "did not start", "dns", "dnp" -> true;
            default -> false;
        };
    }

    private static Integer intOrNull(JsonNode node, String field) {
        JsonNode v = node.path(field);
        if (v.isNumber()) {
            return v.asInt();
        }
        String s = v.asText("");
        if (s.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static java.time.LocalDate dateOrNull(JsonNode node, String field) {
        String s = node.path(field).asText("");
        return s.isBlank() ? null : java.time.LocalDate.parse(s);
    }

    private static Double doubleOrNull(JsonNode node, String field) {
        JsonNode v = node.path(field);
        if (v.isNumber()) {
            return v.asDouble();
        }
        String s = v.asText("");
        if (s.isBlank()) {
            return null;
        }
        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
