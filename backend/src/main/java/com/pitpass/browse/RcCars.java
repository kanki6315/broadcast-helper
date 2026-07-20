package com.pitpass.browse;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts the car numbers a race-control message names. Precision over recall:
 * a number counts only when it directly follows "Car"/"Cars" (optionally with a
 * colon), continuing through a comma/&/and-separated run — so "Car 34 and 120:",
 * "IMPOUND CARS: 43, 04, 99, 52" and "CARS 13, 27 & 66" all link, while
 * "10 car lengths", "Turn 2", "Lap 4-7", "SR 22.3.10" and bare cross-references
 * like "Incident Responsibility with 13 & 66" deliberately do not — a wrong
 * link is worse than a missing one, and the incident that names cars bare is
 * always accompanied by a "CARS 13, 27 & 66" message that links them properly.
 *
 * Computed at read time from the verbatim stored message, never persisted, so
 * the heuristic can improve without a re-import.
 */
public final class RcCars {

    private static final Pattern CAR_RUN = Pattern.compile(
            "\\bcars?\\b:?\\s*(\\d+(?:(?:\\s*,\\s*(?:and\\s+)?|\\s*&\\s*|\\s+and\\s+)\\d+)*)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern NUMBER = Pattern.compile("\\d+");

    private RcCars() {
    }

    public static List<String> extract(String message) {
        if (message == null || message.isBlank()) {
            return List.of();
        }
        List<String> cars = new ArrayList<>();
        Matcher run = CAR_RUN.matcher(message);
        while (run.find()) {
            Matcher number = NUMBER.matcher(run.group(1));
            while (number.find()) {
                if (!cars.contains(number.group())) {
                    cars.add(number.group());
                }
            }
        }
        return cars;
    }
}
