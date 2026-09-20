package com.pitpass.live;

import com.pitpass.live.LiveClassification.Entry;
import com.pitpass.live.LiveTimingService.LiveStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads what {@link LiveClassification} needs about the bound event — its
 * entries, the season's car-number aliases, the series' class aliases — and
 * pairs the result with where the connection stands. Three small queries per
 * poll against indexed keys; the rows can change under a running session (an
 * admin fixing an entry), so nothing is cached.
 */
@Component
public class LiveClassificationService {

    /**
     * Deliberately carries no timestamp or counter: the body changes only when
     * the running order does, so a polling client's If-None-Match mostly earns
     * a 304. Staleness is {@code state} — anything but LIVE means the order is
     * last-known (BACKING_OFF) or absent.
     */
    public record Response(LiveTimingService.State state, Long eventId, String eventName,
                           LiveTimingService.Session session, LiveClassification.Result classification) {
    }

    private final JdbcClient db;
    private final LiveTimingService live;

    public LiveClassificationService(JdbcClient db, LiveTimingService live) {
        this.db = db;
        this.live = live;
    }

    public Response current() {
        LiveStatus status = live.status();
        LiveClassification.Result result = status.eventId() == null
                ? LiveClassification.Result.EMPTY
                : LiveClassification.build(live.state("timing.session"), entries(status.eventId()),
                        numberAliases(status.eventId()), classAliases(status.eventId()));
        return new Response(status.state(), status.eventId(), status.eventName(), status.session(), result);
    }

    private List<Entry> entries(long eventId) {
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

    private Map<String, String> numberAliases(long eventId) {
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

    private Map<String, String> classAliases(long eventId) {
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
}
