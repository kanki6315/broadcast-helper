package com.pitpass.live;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.StringJoiner;

/**
 * Synthetic IMSA telemetry in the shapes read off the telemetry app's code
 * (2026-09-29): per-car objects with {@code energy_remaining} and a
 * {@code scoring} block, base64 JSON inside AppSync Events data messages.
 * Nothing here was captured from a live session; replace the guesses once a
 * weekend has been recorded with IMSA_TELEMETRY_ENABLED.
 */
final class TelemetryFixtures {

    private TelemetryFixtures() {
    }

    static String car(String number, double energy, int lap, boolean pitLane) {
        return "{\"car_id\":\"c" + number + "\",\"speed\":251.3,\"throttle_percentage\":100,\"brake_percentage_front\":0,"
                + "\"gear\":6,\"energy_remaining\":" + energy + ",\"regen\":0,\"is_recharging\":false,\"pit_lane\":" + pitLane
                + ",\"is_jacked_up\":false,\"time\":1769000000,\"sIndex\":3,\"scoring\":{\"position\":1,\"class\":\"GTP\","
                + "\"number\":\"" + number + "\",\"team\":\"Team " + number + "\",\"manufacturer\":\"Porsche\","
                + "\"activeDriver\":\"Driver " + number + "\",\"lapNumber\":" + lap + "}}";
    }

    static String cars(String... cars) {
        StringJoiner j = new StringJoiner(",", "[", "]");
        for (String c : cars) {
            j.add(c);
        }
        return j.toString();
    }

    static String base64(String json) {
        return Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    /** An AppSync data message whose event is {"data":"<base64 JSON>"}, as a JSON-encoded string. */
    static String data(String subscriptionId, String json) {
        String event = "{\"data\":\"" + base64(json) + "\"}";
        return "{\"type\":\"data\",\"id\":\"" + subscriptionId + "\",\"event\":" + quote(event) + "}";
    }

    static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
