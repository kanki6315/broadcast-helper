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
                    e.path("bronze_cup").asBoolean(false),
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
            int inClass = classCounters.merge(className, 1, Integer::sum);

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
                    text(c, "fastest_lap_time"),
                    intOrNull(c, "fastest_lap_number"),
                    doubleOrNull(c, "fastest_lap_kph"),
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
