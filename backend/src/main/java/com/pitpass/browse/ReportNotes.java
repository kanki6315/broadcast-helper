package com.pitpass.browse;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Splits a session's stewards' report message into per-line notes, each tagged
 * with the car numbers it names, so the UI can flag the affected rows.
 *
 * A message is newline-separated (results files use \n, flags files \r\n), one
 * note per line, most lines shaped "Car #33 - Lap 4 time invalidated…" or
 * "Cars #16 & #18 - Discontinued participation". Only the head before the FIRST
 * " - " is scanned for cars: the note text itself is full of numbers that are
 * not cars ("Lap 24", "88 second", "Article 29.10.1"). A line whose head is not
 * a car list is kept as a session-wide note with no cars.
 *
 * Note text stays verbatim — source typos ("responsability") included. Real
 * messages contain a hash-less number in a car list ("Cars #73, #1, #9, #94
 * & 5"), so bare digit runs in the head count as cars too.
 */
public final class ReportNotes {

    public record SessionNote(String text, List<String> carNumbers) {
    }

    private static final Pattern CAR_LIST_HEAD = Pattern.compile("^cars?\\b.*", Pattern.CASE_INSENSITIVE);
    private static final Pattern CAR_TOKEN = Pattern.compile("#(\\w+)|\\b(\\d+)\\b");

    private ReportNotes() {
    }

    public static List<SessionNote> parse(String reportMessage) {
        if (reportMessage == null || reportMessage.isBlank()) {
            return List.of();
        }
        List<SessionNote> notes = new ArrayList<>();
        for (String raw : reportMessage.split("\\r?\\n")) {
            String line = raw.strip();
            if (line.isEmpty()) {
                continue;
            }
            notes.add(new SessionNote(line, carsOf(line)));
        }
        return notes;
    }

    private static List<String> carsOf(String line) {
        int dash = line.indexOf(" - ");
        if (dash < 0) {
            return List.of();
        }
        String head = line.substring(0, dash);
        if (!CAR_LIST_HEAD.matcher(head).matches()) {
            return List.of();
        }
        List<String> cars = new ArrayList<>();
        Matcher m = CAR_TOKEN.matcher(head);
        while (m.find()) {
            String number = m.group(1) != null ? m.group(1) : m.group(2);
            if (!cars.contains(number)) {
                cars.add(number);
            }
        }
        return cars;
    }
}
