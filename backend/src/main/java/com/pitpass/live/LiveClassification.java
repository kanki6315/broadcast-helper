package com.pitpass.live;

import com.fasterxml.jackson.databind.JsonNode;
import com.pitpass.sheets.SheetController;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The feed's running order in each class, tied to the bound event's entries —
 * the one input the championship calculators need to go from "positions a
 * person typed in" to "positions as they stand". No points are computed here:
 * the scales live in the web and iPad calculators, and this hands them
 * {@code competitorKey}s that match their standings rows.
 *
 * A pure function of the feed tree and the event's rows, so it is tested
 * without a socket or a database.
 *
 * Matching is by car number alone — an event cannot hold the same number
 * twice. Exactly as written first: IMSA grids really do carry #04 and #4, #23
 * and #023, as different cars. Only a number with no exact match falls back to
 * ignoring leading zeros, and only when that points at one entry. The feed's
 * class names are not
 * trusted to equal ours ("GTDPRO" vs "GTD PRO"): a feed class is named after
 * the class most of its matched cars are entered in. Everything that fails to
 * line up is reported, never dropped.
 */
public final class LiveClassification {

    /** An entry of the bound event. */
    public record Entry(long id, String carNumber, String className, String teamName,
                        String vehicle, String manufacturer, boolean guest) {
    }

    /**
     * competitorKey is what a TEAMS standings row is keyed by: the car number
     * through the season's car_number_alias, leading zeros dropped — compare
     * it to a standings key normalized the same way, within the class (#04
     * and #4 share a key but never a class). carNumber stays as the feed
     * shows it. Gaps are one or the other: laps when lapped.
     */
    public record Car(int position, String carNumber, String competitorKey, Long entryId,
                      String teamName, String vehicle, String manufacturer, boolean guest,
                      String status, Integer laps, Long gapToLeaderMs, Integer gapToLeaderLaps) {
    }

    /** className is ours (what championships are keyed by); feedClass is Al Kamel's. */
    public record ClassOrder(String className, String feedClass, List<Car> cars) {
    }

    /** In the feed, not in the event: a late entry, or the wrong event is bound. */
    public record UnmatchedCar(String carNumber, String feedClass, int position, String teamName, String vehicle) {
    }

    /** In the event, not in the feed: withdrawn, or simply not in this session. */
    public record MissingEntry(String carNumber, String className, String teamName) {
    }

    /** Running in a feed class other than the one it is entered in — it scores with neither cleanly. */
    public record ClassMismatch(String carNumber, String enteredClass, String runningInClass) {
    }

    public record Result(List<ClassOrder> classes, int matched, int total,
                         List<UnmatchedCar> unmatched, List<MissingEntry> missing,
                         List<ClassMismatch> classMismatches) {
        static final Result EMPTY = new Result(List.of(), 0, 0, List.of(), List.of(), List.of());
    }

    private LiveClassification() {
    }

    /**
     * @param session       the feed tree at {@code timing.session}, or null
     * @param numberAliases the season's car_number_alias, keyed {@link #aliasKey}
     * @param classAliases  the series' class_alias, lower-cased alias → class name
     */
    public static Result build(JsonNode session, List<Entry> entries,
                               Map<String, String> numberAliases, Map<String, String> classAliases) {
        if (session == null) {
            return Result.EMPTY;
        }
        JsonNode byClass = session.path("standings").path("byClass");
        JsonNode order = byClass.path("active").isObject() && !byClass.path("active").isEmpty()
                ? byClass.path("active") : byClass.path("finishLine");
        if (!order.isObject() || order.isEmpty()) {
            return Result.EMPTY;
        }

        Numbers byNumber = new Numbers(entries);
        JsonNode feedEntries = session.path("entry");

        List<ClassOrder> classes = new ArrayList<>();
        List<UnmatchedCar> unmatched = new ArrayList<>();
        List<ClassMismatch> mismatches = new ArrayList<>();
        Map<Long, Entry> seen = new LinkedHashMap<>();
        int total = 0;

        for (var field : order.properties()) {
            String feedClass = field.getValue().path("class").asText(field.getKey());
            List<JsonNode> rows = new ArrayList<>();
            field.getValue().path("standings").properties().forEach(p -> rows.add(p.getValue()));
            rows.removeIf(row -> row.path("participant").asText("").isBlank());
            rows.sort(Comparator.comparingInt(row -> row.path("position").asInt(Integer.MAX_VALUE)));

            String className = className(feedClass, rows, byNumber, classAliases);
            List<Car> cars = new ArrayList<>();
            for (JsonNode row : rows) {
                total++;
                String shown = row.path("participant").asText();
                int position = row.path("position").asInt(cars.size() + 1);
                Entry entry = byNumber.find(shown);
                JsonNode feedEntry = feedEntries.path(shown);
                if (entry == null) {
                    unmatched.add(new UnmatchedCar(shown, feedClass, position,
                            text(feedEntry, "team"), text(feedEntry, "vehicle")));
                } else {
                    seen.put(entry.id(), entry);
                    if (!entry.className().trim().equalsIgnoreCase(className)) {
                        mismatches.add(new ClassMismatch(shown, entry.className(), className));
                    }
                }
                cars.add(new Car(position, shown,
                        entry == null ? SheetController.normalizeCarNumber(shown) : competitorKey(entry, numberAliases),
                        entry == null ? null : entry.id(),
                        entry == null ? text(feedEntry, "team") : entry.teamName(),
                        entry == null ? text(feedEntry, "vehicle") : entry.vehicle(),
                        entry == null ? text(feedEntry, "manufacturer") : entry.manufacturer(),
                        entry != null && entry.guest(),
                        text(row, "status"),
                        row.has("lapNumber") ? row.path("lapNumber").asInt() : null,
                        gap(row, "gapFirstTime") == 0 ? null : gap(row, "gapFirstTime"),
                        row.path("gapFirstLaps").asInt(0) == 0 ? null : Math.abs(row.path("gapFirstLaps").asInt())));
            }
            classes.add(new ClassOrder(className, feedClass, cars));
        }
        sortByFeedClassOrder(classes, session.path("classes"));

        // With nothing matched the feed is showing some other series' session;
        // listing the whole entry list as missing would only bury that fact.
        List<MissingEntry> missing = new ArrayList<>();
        if (!seen.isEmpty()) {
            for (Entry entry : entries) {
                if (!seen.containsKey(entry.id())) {
                    missing.add(new MissingEntry(entry.carNumber(), entry.className(), entry.teamName()));
                }
            }
        }
        return new Result(classes, seen.size(), total, unmatched, missing, mismatches);
    }

    /** The event's entries by car number: exact, then unambiguous-without-leading-zeros. */
    private static final class Numbers {
        private final Map<String, Entry> exact = new HashMap<>();
        private final Map<String, List<Entry>> normalized = new HashMap<>();

        Numbers(List<Entry> entries) {
            for (Entry entry : entries) {
                exact.put(entry.carNumber().trim(), entry);
                normalized.computeIfAbsent(SheetController.normalizeCarNumber(entry.carNumber()),
                        k -> new ArrayList<>()).add(entry);
            }
        }

        Entry find(String shown) {
            Entry entry = exact.get(shown.trim());
            if (entry != null) {
                return entry;
            }
            List<Entry> loose = normalized.getOrDefault(SheetController.normalizeCarNumber(shown), List.of());
            return loose.size() == 1 ? loose.get(0) : null;
        }
    }

    /** Same shape as SheetController's alias lookup: class + normalized number. */
    public static String aliasKey(String className, String carNumber) {
        return Objects.toString(className, "").trim().toLowerCase() + "|" + SheetController.normalizeCarNumber(carNumber);
    }

    private static String competitorKey(Entry entry, Map<String, String> numberAliases) {
        String canonical = numberAliases.get(aliasKey(entry.className(), entry.carNumber()));
        return SheetController.normalizeCarNumber(canonical != null ? canonical : entry.carNumber());
    }

    // Our name for a feed class: what most of its matched cars are entered
    // in; failing any match, the series' class alias; failing that, theirs.
    private static String className(String feedClass, List<JsonNode> rows, Numbers byNumber,
                                    Map<String, String> classAliases) {
        Map<String, Integer> votes = new LinkedHashMap<>();
        for (JsonNode row : rows) {
            Entry entry = byNumber.find(row.path("participant").asText());
            if (entry != null) {
                votes.merge(entry.className().trim(), 1, Integer::sum);
            }
        }
        return votes.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElseGet(() -> classAliases.getOrDefault(feedClass.trim().toLowerCase(), feedClass));
    }

    // timing.session.classes is keyed by display order; classes it omits go last.
    private static void sortByFeedClassOrder(List<ClassOrder> classes, JsonNode feedClasses) {
        Map<String, Integer> rank = new HashMap<>();
        feedClasses.properties().forEach(p -> rank.put(
                p.getValue().path("name").asText(""), p.getValue().path("order").asInt(Integer.MAX_VALUE)));
        classes.sort(Comparator.comparingInt(c -> rank.getOrDefault(c.feedClass(), Integer.MAX_VALUE)));
    }

    private static long gap(JsonNode row, String field) {
        return row.path(field).asLong(0);
    }

    private static String text(JsonNode node, String field) {
        String value = node.path(field).asText("");
        return value.isBlank() ? null : value;
    }
}
