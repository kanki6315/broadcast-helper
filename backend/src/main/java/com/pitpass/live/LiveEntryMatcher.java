package com.pitpass.live;

import com.pitpass.live.LiveClassification.Entry;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What the feed is matched against for one bound event: its entries, the
 * season's car-number aliases, the series' class aliases and each entry's
 * crew. Shared by the classification (running order) and the analysis
 * ingest (who drove a lap). Small queries against indexed keys; the rows can
 * change under a running session (an admin fixing an entry), so nothing is
 * cached.
 */
@Component
public class LiveEntryMatcher {

    /** One seat of an entry's crew, in seat order. rating is ours (B/S/G/P), from driver_assignment. */
    public record CrewMember(long driverId, String firstName, String surname, String rating) {
        public String name() {
            return firstName + " " + surname;
        }
    }

    private final JdbcClient db;

    public LiveEntryMatcher(JdbcClient db) {
        this.db = db;
    }

    public List<Entry> entries(long eventId) {
        return db.sql("""
                SELECT id, car_number, class_name, team_name, vehicle, manufacturer, is_guest
                FROM entry WHERE event_id = :event
                """)
                .param("event", eventId)
                .query((rs, i) -> new Entry(rs.getLong("id"), rs.getString("car_number"), rs.getString("class_name"),
                        rs.getString("team_name"), rs.getString("vehicle"), rs.getString("manufacturer"),
                        rs.getBoolean("is_guest")))
                .list();
    }

    /** The season's car_number_alias, keyed {@link LiveClassification#aliasKey}. */
    public Map<String, String> numberAliases(long eventId) {
        Map<String, String> aliases = new HashMap<>();
        db.sql("""
                SELECT a.class_name, a.car_number, a.canonical_number
                FROM car_number_alias a JOIN event ev ON ev.season_id = a.season_id
                WHERE ev.id = :event
                """)
                .param("event", eventId)
                .query((rs, i) -> new String[] {
                        LiveClassification.aliasKey(rs.getString("class_name"), rs.getString("car_number")),
                        rs.getString("canonical_number")})
                .list()
                .forEach(pair -> aliases.put(pair[0], pair[1]));
        return aliases;
    }

    /** The series' class_alias, lower-cased alias → class name. */
    public Map<String, String> classAliases(long eventId) {
        Map<String, String> aliases = new HashMap<>();
        db.sql("""
                SELECT ca.alias, ca.class_name
                FROM class_alias ca
                JOIN season se ON se.series_id = ca.series_id
                JOIN event ev ON ev.season_id = se.id
                WHERE ev.id = :event
                """)
                .param("event", eventId)
                .query((rs, i) -> new String[] {rs.getString("alias").trim().toLowerCase(), rs.getString("class_name")})
                .list()
                .forEach(pair -> aliases.put(pair[0], pair[1]));
        return aliases;
    }

    /** Entry id → its crew in seat order. */
    public Map<Long, List<CrewMember>> crews(long eventId) {
        Map<Long, List<CrewMember>> crews = new HashMap<>();
        db.sql("""
                SELECT da.entry_id, d.id, d.first_name, d.surname, da.rating
                FROM driver_assignment da
                         JOIN driver d ON d.id = da.driver_id
                         JOIN entry en ON en.id = da.entry_id
                WHERE en.event_id = :event
                ORDER BY da.seat_order
                """)
                .param("event", eventId)
                .query((rs, i) -> Map.entry(rs.getLong("entry_id"), new CrewMember(rs.getLong("id"),
                        rs.getString("first_name"), rs.getString("surname"), rs.getString("rating"))))
                .list()
                .forEach(e -> crews.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add(e.getValue()));
        return crews;
    }
}
