package com.pitpass.live;

import java.util.StringJoiner;

/**
 * Synthetic timing.analysis in the shapes of the AKS V2 spec (1.0.36,
 * pp. 52–58). No real analysis bytes have been recorded yet, and recordings
 * are licensed feed data that must not be committed, so every fixture is
 * built here by hand. Replace the guesses (marked) once a practice session
 * has been recorded with ALKAMELV2_ANALYSIS_ENABLED.
 */
final class AnalysisFixtures {

    static final long SESSION = 3150;
    static final long RACE_START = 1_769_000_000_000L;

    private AnalysisFixtures() {
    }

    static String info(long sessionDbId, String name) {
        return "{\"timing\":{\"session\":{\"info\":{\"sessionDbId\":" + sessionDbId
                + ",\"sessionMongoId\":\"66f0c0ffee\",\"eventDbId\":812,\"champDbId\":38"
                + ",\"champName\":\"IMSA WeatherTech SportsCar Championship\",\"eventName\":\"Showcase 120\""
                + ",\"eventShortName\":\"Road America\",\"name\":\"" + name
                + "\",\"type\":\"RACE\",\"date\":" + RACE_START + ",\"stintCalcType\":\"IMSA\",\"closed\":false}}}}";
    }

    /** One lap object as the spec prints it, with the heavy loopSectors and sections included. */
    static String lap(int lapNum, int driver, int timeMs, int loopSectors) {
        StringJoiner loops = new StringJoiner(",", "{", "}");
        for (int i = 1; i <= loopSectors; i++) {
            loops.add("\"" + i + "\":{\"number\":" + i + ",\"time\":" + (4_000 + i) + ",\"isValid\":true,"
                    + "\"speed\":271.4,\"flag\":\"GREEN\",\"name\":\"L" + i + "\"}");
        }
        int s1 = timeMs / 3;
        int s2 = timeMs / 3;
        int s3 = timeMs - s1 - s2;
        return "{\"driver\":" + driver + ",\"driverLapNum\":" + lapNum + ",\"isValid\":true,\"isLongLap\":false,"
                + "\"isShortLap\":false,\"lapNum\":" + lapNum + ",\"position\":3,\"startTime\":"
                + (RACE_START + (long) lapNum * timeMs) + ",\"time\":" + timeMs + ",\"topSpeed\":298.7,"
                + "\"trackLimits\":0,"
                + "\"sectors\":{\"1\":{\"flag\":\"GREEN\",\"isValid\":true,\"number\":1,\"time\":" + s1 + ",\"trackLimits\":0},"
                + "\"2\":{\"flag\":\"GREEN\",\"isValid\":true,\"number\":2,\"time\":" + s2 + ",\"trackLimits\":0},"
                + "\"3\":{\"flag\":\"YELLOW\",\"isValid\":true,\"number\":3,\"time\":" + s3 + ",\"trackLimits\":0}},"
                + "\"loopSectors\":" + loops + ","
                + "\"sections\":{\"1\":{\"name\":\"T1\",\"time\":1234},\"2\":{\"name\":\"Bus Stop\",\"time\":2345}}}";
    }

    static String stint(long startTime, String type, int driver, int open, Integer close, Long finish, long accum) {
        return "{\"type\":\"" + type + "\",\"driver\":" + driver + ",\"openLapNumber\":" + open
                + ",\"closeLapNumber\":" + close + ",\"startTime\":" + startTime + ",\"finishTime\":" + finish
                + ",\"driverAccumSessionTrackTime\":" + accum + ",\"driverAccumSessionTime\":" + (accum + 60_000)
                + ",\"driverAccumTrackTime\":" + accum + ",\"driverAccumTime\":" + (accum + 60_000) + "}";
    }

    static String lapsDiff(String car, String lapsBody) {
        return "{\"timing\":{\"analysis\":{\"laps\":{\"" + car + "\":{\"laps\":{" + lapsBody + "}}}}}}";
    }

    static String stintsDiff(String car, String stintsBody) {
        return "{\"timing\":{\"analysis\":{\"stints\":{\"" + car + "\":{\"stints\":{" + stintsBody + "}}}}}}";
    }
}
