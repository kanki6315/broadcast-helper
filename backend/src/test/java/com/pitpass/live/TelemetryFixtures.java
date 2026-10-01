package com.pitpass.live;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.StringJoiner;

/**
 * Synthetic IMSA telemetry in the shape seen live at Petit Le Mans 2026: an
 * array of per-car objects, base64 JSON inside {"data":…} AppSync events. The
 * number and class are in car_id ("GTP-7", "GDP-911" for GTD Pro), laps
 * completed in the top-level lap_number (0 when the logger sends none). The
 * scoring block is IMSA's own join and was stale and wrong on the day, so
 * here it is deliberately wrong too: the adapter must not read it.
 */
final class TelemetryFixtures {

    private TelemetryFixtures() {
    }

    static String car(String number, double energy, int laps, boolean pitLane) {
        return car(number, energy, laps, pitLane, "GTP");
    }

    /** laps is the logger's completed-lap count; 0 means it sends none. */
    static String car(String number, double energy, int laps, boolean pitLane, String className) {
        String prefix = className.replace(" ", "").equalsIgnoreCase("GTDPRO") ? "GDP" : className;
        return "{\"car_id\":\"" + prefix + "-" + number + "\",\"lap_number\":" + laps + ".0,\"speed\":251.3,"
                + "\"throttle_percentage\":100,\"brake_percentage_front\":0,\"gear\":\"6\",\"energy_remaining\":" + energy
                + ",\"regen\":0,\"is_recharging\":false,\"pit_lane\":" + pitLane + ",\"is_jacked_up\":0,"
                + "\"time\":1769000000,\"sIndex\":3,\"elr\":0,\"dt\":[],\"scoring\":{\"position\":1,\"class\":\"ND2\","
                + "\"number\":\"9" + number + "\",\"team\":\"Team " + number + "\",\"manufacturer\":\"Porsche\","
                + "\"lapNumber\":3}}";
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
