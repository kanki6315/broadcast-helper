package com.pitpass.live;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The track's weather station (spec 1.0.36 §4.3), read for the timing page.
 * weather.currentData is the latest reading, every 5–20 s; weather.sessionData
 * the session's readings, one a minute, keyed by dayTime. Pure; the tower
 * hands it the nodes from the state tree, and the weather endpoint the rows
 * from live_weather.
 *
 * Every reading carries both unit systems. The server we connect to (protocol
 * 1.0.33) is older than the spec, whose changelog says the US units were added
 * at some point — so a missing one is converted from the other, and a number
 * sent as a string is read as a number.
 *
 * Built from the spec alone: no recording has joined this channel yet.
 */
final class LiveWeather {

    private LiveWeather() {
    }

    /**
     * One reading. dayTimeMs is when it was taken, epoch ms UTC (null when
     * the feed sends none). windDirection is in degrees, as the station sends
     * it — whether it is where the wind comes from (the weather convention) is
     * unverified. Any field the station does not send is null.
     */
    public record Reading(Long dayTimeMs, Double airC, Double airF, Double trackC, Double trackF,
                          Double humidityPct, Double pressureMbar, Double pressureInHg,
                          Integer windDirection, Double windKmh, Double windMph) {

        boolean isEmpty() {
            return airC == null && trackC == null && humidityPct == null && pressureMbar == null
                    && windDirection == null && windKmh == null;
        }
    }

    static final Comparator<Reading> OLDEST_FIRST =
            Comparator.comparing(Reading::dayTimeMs, Comparator.nullsFirst(Comparator.naturalOrder()));

    /**
     * For the tower: the latest reading, else the newest of the session's.
     * Null when the feed has sent no weather at all (channel off, not joined
     * yet, or a track without a station).
     */
    static Reading now(JsonNode current, JsonNode session) {
        Reading r = reading(current, null);
        if (r != null) {
            return r;
        }
        List<Reading> all = session(session);
        return all.isEmpty() ? null : all.getLast();
    }

    /** The session's readings from the tree, oldest first. */
    static List<Reading> session(JsonNode samples) {
        List<Reading> out = new ArrayList<>();
        if (samples != null && samples.isObject()) {
            for (var f : samples.properties()) {
                Reading r = reading(f.getValue(), parseLong(f.getKey()));
                if (r != null) {
                    out.add(r);
                }
            }
        }
        out.sort(OLDEST_FIRST);
        return out;
    }

    /**
     * One sample, units filled in from each other. keyTime is the sample's key
     * in sessionData (its dayTime), used when the sample carries none. Null
     * when it is not an object or holds no reading at all.
     */
    static Reading reading(JsonNode m, Long keyTime) {
        if (m == null || !m.isObject()) {
            return null;
        }
        Long day = m.hasNonNull("dayTime") ? parseLong(m.path("dayTime").asText()) : null;
        Reading r = filled(day != null ? day : keyTime, number(m, "ambientTemperature"),
                number(m, "ambientTemperatureF"), number(m, "trackTemperature"), number(m, "trackTemperatureF"),
                number(m, "humidity"), number(m, "pressure"), number(m, "pressureInHg"),
                number(m, "windDirection"), number(m, "windSpeed"), number(m, "windSpeedMi"));
        return r.isEmpty() ? null : r;
    }

    /** A stored row, filled in as the feed's readings are. */
    static Reading row(long dayTimeMs, Double airC, Double airF, Double trackC, Double trackF, Double humidity,
                       Double pressureMbar, Double pressureInHg, Double windDirection, Double windKmh, Double windMph) {
        Reading r = filled(dayTimeMs, airC, airF, trackC, trackF, humidity, pressureMbar, pressureInHg,
                windDirection, windKmh, windMph);
        return r.isEmpty() ? null : r;
    }

    private static Reading filled(Long day, Double airC, Double airF, Double trackC, Double trackF, Double humidity,
                                  Double mbar, Double inHg, Double windDir, Double kmh, Double mph) {
        return new Reading(day,
                round1(airC != null ? airC : fToC(airF)), round1(airF != null ? airF : cToF(airC)),
                round1(trackC != null ? trackC : fToC(trackF)), round1(trackF != null ? trackF : cToF(trackC)),
                round1(humidity),
                round1(mbar != null ? mbar : inHg == null ? null : inHg * MBAR_PER_INHG),
                round2(inHg != null ? inHg : mbar == null ? null : mbar / MBAR_PER_INHG),
                windDir == null ? null : Math.floorMod(Math.round(windDir), 360),
                round1(kmh != null ? kmh : mph == null ? null : mph * KM_PER_MILE),
                round1(mph != null ? mph : kmh == null ? null : kmh / KM_PER_MILE));
    }

    static final double KM_PER_MILE = 1.609344;
    static final double MBAR_PER_INHG = 33.8638866667;

    private static Double cToF(Double c) {
        return c == null ? null : c * 9 / 5 + 32;
    }

    private static Double fToC(Double f) {
        return f == null ? null : (f - 32) * 5 / 9;
    }

    private static Double round1(Double v) {
        return v == null ? null : Math.round(v * 10) / 10.0;
    }

    private static Double round2(Double v) {
        return v == null ? null : Math.round(v * 100) / 100.0;
    }

    /** A number, or a string holding one; null when absent, blank or not a finite number. */
    static Double number(JsonNode m, String field) {
        JsonNode v = m.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        double d;
        if (v.isNumber()) {
            d = v.doubleValue();
        } else if (v.isTextual()) {
            try {
                d = Double.parseDouble(v.asText().strip());
            } catch (NumberFormatException e) {
                return null;
            }
        } else {
            return null;
        }
        return Double.isFinite(d) ? d : null;
    }

    static Long parseLong(String s) {
        try {
            return s == null ? null : Long.parseLong(s.strip());
        } catch (NumberFormatException e) {
            try {
                double d = Double.parseDouble(s.strip());
                return Double.isFinite(d) ? Math.round(d) : null;
            } catch (NumberFormatException e2) {
                return null;
            }
        }
    }
}
