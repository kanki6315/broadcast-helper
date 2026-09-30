package com.pitpass.live;

import com.fasterxml.jackson.databind.JsonNode;
import com.pitpass.live.LiveClassification.Entry;

import java.util.List;
import java.util.Map;

/**
 * Whether the session on track belongs to a Pit Pass event: the event's
 * entry list has to account for the cars running, by number **and** class.
 * Numbers alone are not enough — a weekend's series share them (#7 runs in
 * WeatherTech and in Pilot Challenge) — but their classes do not overlap
 * (GTP / LMP2 / GTD PRO / GTD against GS / TCR), so a car counts only when its
 * feed class agrees with the class it is entered in.
 *
 * This is what files a recorded session under an event. The admin's binding
 * says which event to try; it never files a session on its own, so binding
 * Pilot Challenge while WeatherTech is on track moves nothing, and a session
 * that loads before the rebind is filed the moment the rebind matches it.
 */
final class LiveEventMatch {

    /** total = cars on track; agreeing = found in the entry list, in the same class. */
    record Result(int total, int agreeing) {
        /** At least half the field accounted for. A late entry or two does not stop a match. */
        boolean matches() {
            return total > 0 && agreeing * 2 >= total;
        }
    }

    private LiveEventMatch() {
    }

    /**
     * @param session      the feed tree at {@code timing.session}, or null
     * @param classAliases the series' class_alias, lower-cased alias → class name
     */
    static Result score(JsonNode session, List<Entry> entries, Map<String, String> classAliases) {
        if (session == null || entries.isEmpty()) {
            return new Result(0, 0);
        }
        LiveClassification.Numbers numbers = new LiveClassification.Numbers(entries);
        int total = 0;
        int agreeing = 0;
        JsonNode byClass = session.path("standings").path("byClass");
        JsonNode order = byClass.path("active").isObject() && !byClass.path("active").isEmpty()
                ? byClass.path("active") : byClass.path("finishLine");
        if (order.isObject() && !order.isEmpty()) {
            for (var cls : order.properties()) {
                String feedClass = cls.getValue().path("class").asText(cls.getKey());
                for (var row : cls.getValue().path("standings").properties()) {
                    String number = row.getValue().path("participant").asText("");
                    if (number.isBlank()) {
                        continue;
                    }
                    total++;
                    if (agrees(numbers.find(number), feedClass, classAliases)) {
                        agreeing++;
                    }
                }
            }
            return new Result(total, agreeing);
        }
        // No running order yet (a session just loaded): the entry channel says who is here.
        for (var car : session.path("entry").properties()) {
            String number = car.getValue().path("number").asText(car.getKey());
            String feedClass = car.getValue().path("class").asText("");
            total++;
            if (agrees(numbers.find(number), feedClass, classAliases)) {
                agreeing++;
            }
        }
        return new Result(total, agreeing);
    }

    private static boolean agrees(Entry entry, String feedClass, Map<String, String> classAliases) {
        if (entry == null) {
            return false;
        }
        if (sameClass(entry.className(), feedClass)) {
            return true;
        }
        String alias = classAliases.get(feedClass == null ? "" : feedClass.trim().toLowerCase());
        return alias != null && sameClass(entry.className(), alias);
    }

    /** Class names compared as IMSA writes them in different places: "GTD PRO" = "GTDPRO" = "gtd-pro". */
    static boolean sameClass(String a, String b) {
        return a != null && b != null && !normalize(a).isEmpty() && normalize(a).equals(normalize(b));
    }

    static String normalize(String className) {
        return className.toLowerCase().replaceAll("[^a-z0-9]", "");
    }
}
