package com.pitpass.imports;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Maps parser/parse_f1_pdf.py output (Formula 1 support-race timing PDFs:
 * race classification, qualifying classification, starting grid) onto the
 * shared import shapes.
 *
 * The sheets carry no date, so every batch keeps sessionStart null and goes
 * through the reviewer-picks-the-event flow; the title's session type and race
 * number only pre-fill the session picker. Retirements follow the timing
 * JSON's convention — a classified retirement is status "Classified" with
 * not_finished set — so stats count them the same way.
 */
final class F1PdfMapper {

    /** One driver per car in these series: the seat is always 1. */
    private static final int SEAT = 1;

    private static final Pattern MC = Pattern.compile("^(Mc)(\\p{L})(.*)$");

    private F1PdfMapper() {
    }

    static boolean isGrid(JsonNode root) {
        return "GRID".equals(root.path("document").asText());
    }

    static RaceResultsImport mapResults(JsonNode root) {
        boolean qualifying = "QUALIFYING".equals(root.path("document").asText());
        List<RaceResultsImport.Row> rows = new ArrayList<>();
        Map<String, Integer> classCounters = new HashMap<>();
        for (JsonNode r : root.path("rows")) {
            String number = text(r, "number");
            if (number == null) {
                continue;
            }
            String className = text(r, "class");
            Integer position = intOrNull(r, "position");
            boolean classified = r.path("classified").asBoolean(position != null);
            String printed = text(r, "status"); // DNF / DNS / DSQ / ..., null for a finisher
            String status;
            boolean notFinished;
            if (printed == null) {
                status = classified ? "Classified" : "Not classified";
                notFinished = !classified;
            } else {
                switch (printed) {
                    case "DNS" -> { status = "Not Started"; notFinished = true; }
                    case "DSQ" -> { status = "Disqualified"; notFinished = false; }
                    // DNF: classified if it covered enough distance, else not.
                    default -> { status = classified ? "Classified" : "Not classified"; notFinished = true; }
                }
            }
            if (!classified) {
                position = null;
            }
            Integer inClass = position == null || "Not Started".equals(status)
                    ? null
                    : classCounters.merge(className, 1, Integer::sum);

            String lapTime = qualifying ? text(r, "time") : text(r, "fastest_lap");
            rows.add(new RaceResultsImport.Row(
                    position,
                    inClass,
                    number,
                    className,
                    null,
                    text(r, "team"),
                    null,
                    null,
                    status,
                    notFinished,
                    null,
                    intOrNull(r, "laps"),
                    qualifying ? null : text(r, "time"),
                    qualifying ? null : text(r, "gap"),
                    qualifying ? null : text(r, "interval"),
                    lapTime,
                    qualifying ? null : intOrNull(r, "fastest_lap_number"),
                    null, // the sheet's KM/H is the race average, not the best lap's speed
                    lapTime != null ? SEAT : null,
                    null,
                    driver(r)
            ));
        }
        String session = text(root, "session");
        return new RaceResultsImport(
                null,
                text(root, "event"),
                session,
                qualifying ? "QUALIFYING" : "RACE",
                root.path("race").asInt(1),
                text(root, "status"),
                text(root, "notes"),
                null,
                null,
                null,
                null,
                rows
        );
    }

    static GridImport mapGrid(JsonNode root) {
        List<GridImport.Row> rows = new ArrayList<>();
        Map<String, Integer> classCounters = new HashMap<>();
        for (JsonNode r : root.path("rows")) {
            String number = text(r, "number");
            if (number == null) {
                continue;
            }
            String className = text(r, "class");
            List<RaceResultsImport.DriverRow> roster = driver(r);
            // The grid names the car's one driver, who both qualified and
            // starts it — the attribution the IMSA grid PDF can't give.
            Integer seat = roster.isEmpty() ? null : SEAT;
            rows.add(new GridImport.Row(
                    r.path("position").asInt(),
                    classCounters.merge(className, 1, Integer::sum),
                    number,
                    className,
                    null,
                    text(r, "team"),
                    null,
                    null,
                    text(r, "time"),
                    seat,
                    seat,
                    roster
            ));
        }
        return new GridImport(null, text(root, "event"), text(root, "session"), "RACE",
                root.path("race").asInt(1), null, null, null, null, rows);
    }

    private static List<RaceResultsImport.DriverRow> driver(JsonNode r) {
        String surname = text(r, "surname");
        if (surname == null) {
            return List.of();
        }
        return List.of(new RaceResultsImport.DriverRow(
                SEAT, text(r, "first_name"), nameCase(surname), null, null, null));
    }

    /**
     * The sheets print surnames in capitals. Driver lookups ignore case, so an
     * existing driver keeps its stored spelling; this only shapes the name a
     * brand-new driver is created with: "DE LA TORRE" -> "De La Torre",
     * "MCCANN" -> "McCann", "O'CONNELL" -> "O'Connell", "RIPOLL JR" -> "Ripoll Jr".
     * Already mixed-case text is left alone.
     */
    static String nameCase(String name) {
        if (name == null || !name.equals(name.toUpperCase(Locale.ROOT))) {
            return name;
        }
        StringBuilder out = new StringBuilder(name.length());
        boolean start = true;
        for (char c : name.toCharArray()) {
            out.append(start ? Character.toUpperCase(c) : Character.toLowerCase(c));
            start = !Character.isLetter(c);
        }
        StringBuilder result = new StringBuilder();
        for (String word : out.toString().split(" ", -1)) {
            Matcher m = MC.matcher(word);
            if (result.length() > 0) {
                result.append(' ');
            }
            result.append(m.matches() && m.group(3).length() >= 2
                    ? m.group(1) + m.group(2).toUpperCase(Locale.ROOT) + m.group(3)
                    : word);
        }
        return result.toString();
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.path(field);
        if (v.isMissingNode() || v.isNull()) {
            return null;
        }
        String s = v.asText().trim();
        return s.isEmpty() ? null : s;
    }

    private static Integer intOrNull(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isNumber() ? v.asInt() : null;
    }
}
