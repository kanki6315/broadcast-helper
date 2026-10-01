package com.pitpass.live;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Turns one AppSync event payload into car readings or a session clock. Seen
 * live (Petit Le Mans 2026): the event is {@code {"data":"<base64 JSON>"}},
 * once a second, every car in one array. Other plausible wrappings are still
 * accepted: a JSON string holding base64, or the JSON itself. Anything
 * unreadable decodes to nothing.
 *
 * Each car carries its logger's own fields and a {@code scoring} block, which
 * is IMSA's scoring joined on by number — and that block is not trustworthy:
 * its lapNumber lagged and froze (6 when the car had run 51), and its class
 * followed whichever series IMSA's scoring was timing (VP Challenge classes on
 * WeatherTech cars). So number and class come from {@code car_id}
 * ("GTP-10", "GTD-023", "GDP-911"), and laps from the top-level
 * {@code lap_number}, which matched Al Kamel's lap count on every car that
 * sent it. Most GTD and GTD Pro loggers send 0 there; those are left without
 * a lap count for LiveTelemetry to take from the Al Kamel feed.
 */
final class TelemetryDecoder {

    /**
     * One car's reading. number is car_id's number exactly as sent (#04 is not
     * #4); className is from car_id's prefix, null when unknown. lapsCompleted
     * is the logger's own count, null when it sends none (0).
     */
    record CarReading(String number, String carId, String className, Double energyPct, Integer lapsCompleted,
                      Boolean pitLane, Double speedKph) {
    }

    /** telemetry/session: epoch seconds and seconds. */
    record SessionClock(Long startEpochSeconds, Long durationSeconds, Long secondsRemaining) {
    }

    record Decoded(List<CarReading> cars, SessionClock session) {
        static final Decoded EMPTY = new Decoded(List.of(), null);

        boolean isEmpty() {
            return cars.isEmpty() && session == null;
        }
    }

    private static final int MAX_UNWRAP = 4;

    /** car_id prefixes as IMSA's telemetry writes them; GDP is GTD Pro. */
    private static final Map<String, String> CLASS_PREFIXES = Map.of(
            "GTP", "GTP", "GTD", "GTD", "GDP", "GTD PRO");

    private final ObjectMapper mapper;

    TelemetryDecoder(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    Decoded decode(String event) {
        if (event == null || event.isBlank()) {
            return Decoded.EMPTY;
        }
        JsonNode node = unwrap(parse(event), 0);
        if (node == null) {
            return Decoded.EMPTY;
        }
        if (node.isArray()) {
            List<CarReading> cars = new ArrayList<>();
            node.forEach(n -> {
                CarReading car = car(n);
                if (car != null) {
                    cars.add(car);
                }
            });
            return new Decoded(cars, null);
        }
        if (node.isObject()) {
            if (node.has("session_start_time") || node.has("seconds_remaining") || node.has("session_duration")) {
                return new Decoded(List.of(), new SessionClock(longOf(node, "session_start_time"),
                        longOf(node, "session_duration"), longOf(node, "seconds_remaining")));
            }
            CarReading car = car(node);
            if (car != null) {
                return new Decoded(List.of(car), null);
            }
        }
        return Decoded.EMPTY;
    }

    /** Peels base64 and "data" wrappers until something that looks like telemetry is left. */
    private JsonNode unwrap(JsonNode node, int depth) {
        if (node == null || depth > MAX_UNWRAP) {
            return null;
        }
        if (node.isTextual()) {
            String text = node.asText().trim();
            JsonNode inner = parse(text);
            if (inner != null && !inner.isTextual()) {
                return unwrap(inner, depth + 1);
            }
            String decoded = base64(text);
            return decoded == null ? null : unwrap(parse(decoded), depth + 1);
        }
        if (node.isObject() && node.has("data") && !looksLikeTelemetry(node)) {
            return unwrap(node.get("data"), depth + 1);
        }
        return node;
    }

    private static boolean looksLikeTelemetry(JsonNode node) {
        return node.has("car_id") || node.has("scoring") || node.has("energy_remaining")
                || node.has("session_start_time") || node.has("seconds_remaining");
    }

    private CarReading car(JsonNode n) {
        if (!n.isObject()) {
            return null;
        }
        String carId = text(n, "car_id");
        int dash = carId == null ? -1 : carId.indexOf('-');
        String number = dash > 0 && dash < carId.length() - 1 ? carId.substring(dash + 1).trim() : null;
        String className = dash > 0 ? CLASS_PREFIXES.get(carId.substring(0, dash).trim().toUpperCase()) : null;
        if (number == null) {
            number = text(n.path("scoring"), "number");
        }
        if (number == null) {
            number = carId;
        }
        if (number == null) {
            return null;
        }
        Integer laps = intOf(n, "lap_number");
        return new CarReading(number, carId, className, doubleOf(n, "energy_remaining"),
                laps != null && laps > 0 ? laps : null,
                n.has("pit_lane") && !n.get("pit_lane").isNull() ? truthy(n.get("pit_lane")) : null,
                doubleOf(n, "speed"));
    }

    private JsonNode parse(String text) {
        try {
            return mapper.readTree(text);
        } catch (Exception e) {
            return null;
        }
    }

    private static String base64(String text) {
        try {
            return new String(Base64.getDecoder().decode(text), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            try {
                return new String(Base64.getUrlDecoder().decode(text), StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e2) {
                return null;
            }
        }
    }

    private static boolean truthy(JsonNode v) {
        return v.isBoolean() ? v.asBoolean() : v.isNumber() ? v.asInt() != 0 : v.asText("").equalsIgnoreCase("true");
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull() || v.isContainerNode()) {
            return null;
        }
        String s = v.asText().trim();
        return s.isEmpty() ? null : s;
    }

    private static Double doubleOf(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        if (v.isNumber()) {
            return v.asDouble();
        }
        try {
            return Double.parseDouble(v.asText().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Integer intOf(JsonNode node, String field) {
        Double d = doubleOf(node, field);
        return d == null ? null : (int) Math.round(d);
    }

    private static Long longOf(JsonNode node, String field) {
        Double d = doubleOf(node, field);
        return d == null ? null : Math.round(d);
    }
}
