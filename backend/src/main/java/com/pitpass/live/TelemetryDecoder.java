package com.pitpass.live;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Turns one AppSync event payload into car readings or a session clock. The
 * payload is described as base64 JSON, but that has only been read off the
 * app's code, never seen on a live weekend — so every plausible wrapping is
 * accepted: a JSON string holding base64, an object with a base64 {@code data}
 * field, or the JSON itself. Anything unreadable decodes to nothing.
 */
final class TelemetryDecoder {

    /** One car's reading. number is scoring.number exactly as sent (#04 is not #4). */
    record CarReading(String number, String carId, String className, Double energyPct, Integer lapNumber,
                      Boolean pitLane, Double speedKph, Integer position, String activeDriver) {
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
        JsonNode scoring = n.path("scoring");
        String number = text(scoring, "number");
        String carId = text(n, "car_id");
        if (number == null && carId == null) {
            return null;
        }
        return new CarReading(number != null ? number : carId, carId, text(scoring, "class"),
                doubleOf(n, "energy_remaining"), intOf(scoring, "lapNumber"),
                n.has("pit_lane") && !n.get("pit_lane").isNull() ? truthy(n.get("pit_lane")) : null,
                doubleOf(n, "speed"), intOf(scoring, "position"), text(scoring, "activeDriver"));
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
