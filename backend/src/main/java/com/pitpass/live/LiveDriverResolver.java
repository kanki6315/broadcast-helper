package com.pitpass.live;

import com.fasterxml.jackson.databind.JsonNode;
import com.pitpass.live.LiveClassification.Entry;
import com.pitpass.live.LiveEntryMatcher.CrewMember;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Fills live_driver: who driver_order N of each car is, from the feed's
 * timing.session.entry.&lt;car&gt;.drivers, and which Pit Pass driver that
 * is. Cars match the bound event's entries by number, exactly first (#04 and
 * #4 are different cars); drivers match the entry's crew by surname, then by
 * full name when a crew shares a surname. At the 2026 Indianapolis race that
 * matched 88 of 88. An unmatched driver is written with the feed's name and
 * a null driver_id — listed, never dropped.
 *
 * Runs on the analysis writer's thread. Entry diffs are frequent (the
 * current driver changes at every stop) and rarely change a crew, so an
 * unchanged result is not written again.
 */
@Component
public class LiveDriverResolver implements AnalysisWriter.DriverResolver {

    record Row(String car, int order, String firstName, String lastName, String shortName, String license,
               String country, Long entryId, Long driverId, String rating) {
    }

    private final JdbcClient db;
    private final LiveEntryMatcher matcher;
    private final TransactionTemplate tx;
    private volatile Object lastWritten;

    public LiveDriverResolver(JdbcClient db, LiveEntryMatcher matcher, TransactionTemplate tx) {
        this.db = db;
        this.matcher = matcher;
        this.tx = tx;
    }

    @Override
    public void resolve(long sessionDbId, JsonNode entries, Long eventId) {
        if (entries == null || !entries.isObject()) {
            return;
        }
        List<Entry> eventEntries = eventId == null ? List.of() : matcher.entries(eventId);
        Map<Long, List<CrewMember>> crews = eventId == null ? Map.of() : matcher.crews(eventId);
        List<Row> rows = rows(entries, eventEntries, crews);
        List<Object> key = List.of(sessionDbId, rows);
        if (key.equals(lastWritten)) {
            return;
        }
        tx.executeWithoutResult(status -> {
            for (Row r : rows) {
                db.sql("""
                        INSERT INTO live_driver (session_db_id, car_number, driver_order, first_name, last_name,
                                                 short_name, license, country, entry_id, driver_id, rating)
                        VALUES (:s, :car, :order, :first, :last, :short, :license, :country, :entry, :driver, :rating)
                        ON CONFLICT (session_db_id, car_number, driver_order) DO UPDATE SET
                            first_name = EXCLUDED.first_name, last_name = EXCLUDED.last_name,
                            short_name = EXCLUDED.short_name, license = EXCLUDED.license,
                            country = EXCLUDED.country, entry_id = EXCLUDED.entry_id,
                            driver_id = EXCLUDED.driver_id, rating = EXCLUDED.rating
                        """)
                        .param("s", sessionDbId).param("car", r.car()).param("order", r.order())
                        .param("first", r.firstName(), java.sql.Types.VARCHAR)
                        .param("last", r.lastName(), java.sql.Types.VARCHAR)
                        .param("short", r.shortName(), java.sql.Types.VARCHAR)
                        .param("license", r.license(), java.sql.Types.VARCHAR)
                        .param("country", r.country(), java.sql.Types.VARCHAR)
                        .param("entry", r.entryId(), java.sql.Types.BIGINT)
                        .param("driver", r.driverId(), java.sql.Types.BIGINT)
                        .param("rating", r.rating(), java.sql.Types.VARCHAR)
                        .update();
            }
        });
        lastWritten = key;
    }

    /** Pure: the feed's drivers against the event's entries and crews. */
    static List<Row> rows(JsonNode entries, List<Entry> eventEntries, Map<Long, List<CrewMember>> crews) {
        LiveClassification.Numbers numbers = new LiveClassification.Numbers(eventEntries);
        List<Row> rows = new ArrayList<>();
        for (var car : entries.properties()) {
            JsonNode feedCar = car.getValue();
            String number = feedCar.path("number").asText(car.getKey());
            if (number.isBlank()) {
                number = car.getKey();
            }
            Entry entry = numbers.find(number);
            List<CrewMember> crew = entry == null ? List.of() : crews.getOrDefault(entry.id(), List.of());
            for (var d : feedCar.path("drivers").properties()) {
                JsonNode driver = d.getValue();
                Integer order = order(d.getKey(), driver);
                if (order == null) {
                    continue;
                }
                String first = text(driver, "firstName");
                String last = text(driver, "lastName");
                CrewMember match = match(crew, first, last);
                rows.add(new Row(car.getKey(), order, first, last, text(driver, "shortName"),
                        text(driver, "license"), text(driver, "country"),
                        entry == null ? null : entry.id(),
                        match == null ? null : match.driverId(),
                        match != null && match.rating() != null ? match.rating() : rating(text(driver, "license"))));
            }
        }
        return rows;
    }

    // The drivers object is keyed "1", "2", … and each carries the same as number.
    private static Integer order(String key, JsonNode driver) {
        try {
            return Integer.parseInt(key.trim());
        } catch (NumberFormatException e) {
            return driver.path("number").canConvertToInt() && driver.path("number").isNumber()
                    ? driver.path("number").asInt() : null;
        }
    }

    private static CrewMember match(List<CrewMember> crew, String first, String last) {
        if (last == null) {
            return null;
        }
        List<CrewMember> bySurname = crew.stream().filter(c -> fold(c.surname()).equals(fold(last))).toList();
        if (bySurname.size() == 1) {
            return bySurname.getFirst();
        }
        List<CrewMember> byName = (bySurname.isEmpty() ? crew : bySurname).stream()
                .filter(c -> fold(c.name()).equals(fold(first + " " + last))).toList();
        return byName.size() == 1 ? byName.getFirst() : null;
    }

    /** Case, accents and spacing ignored: "Müller" in one source is "Muller" in another. */
    static String fold(String text) {
        if (text == null) {
            return "";
        }
        return Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .toLowerCase().replaceAll("[^a-z0-9]+", " ").trim();
    }

    /** The feed's license spelled as our rating letter, for a driver we could not match. */
    private static String rating(String license) {
        return license == null || license.isBlank() ? null : license.trim().substring(0, 1).toUpperCase();
    }

    private static String text(JsonNode node, String field) {
        return node.hasNonNull(field) && !node.path(field).asText().isBlank() ? node.path(field).asText() : null;
    }
}
