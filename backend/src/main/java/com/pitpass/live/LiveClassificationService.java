package com.pitpass.live;

import com.pitpass.live.LiveChampionshipPositions.Slot;
import com.pitpass.live.LiveChampionshipPositions.StandingsRow;
import com.pitpass.live.LiveClassification.Entry;
import com.pitpass.live.LiveTimingService.LiveStatus;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
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

    /**
     * One class championship against the running order. livePhase says which
     * calculator column the live positions belong to — RACE, QUALIFYING, or
     * null for a session that scores nothing (practice, warm-up).
     * qualifyingImported tells a race projection whether the weekend's
     * qualifying points could be included at all. Same no-timestamps rule as
     * {@link Response}, for the same reason.
     */
    public record ChampionshipResponse(LiveTimingService.State state, Long eventId, String eventName,
                                       LiveTimingService.Session session, long championshipId,
                                       String kind, String className, String livePhase,
                                       boolean qualifyingImported,
                                       List<LiveChampionshipPositions.Row> rows,
                                       List<LiveChampionshipPositions.Newcomer> newcomers) {
    }

    private record Championship(long id, long seasonId, String className, String kind, boolean overall) {
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

    public ChampionshipResponse championship(long championshipId) {
        Championship champ = db.sql("""
                SELECT c.id, c.season_id, c.class_name, g.kind, COALESCE(c.is_overall, false) AS is_overall
                FROM championship c LEFT JOIN championship_group g ON g.id = c.group_id
                WHERE c.id = :id
                """)
                .param("id", championshipId)
                .query((rs, i) -> new Championship(rs.getLong("id"), rs.getLong("season_id"),
                        rs.getString("class_name"), rs.getString("kind"), rs.getBoolean("is_overall")))
                .optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such championship"));
        if (champ.overall() || champ.className() == null || champ.className().isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Live positions are worked out per class; this championship spans classes");
        }

        LiveStatus status = live.status();
        String kind = champ.kind() == null ? "TEAMS" : champ.kind();
        if (status.eventId() == null) {
            return new ChampionshipResponse(status.state(), null, null, status.session(), champ.id(), kind,
                    champ.className(), null, false, List.of(), List.of());
        }
        long eventId = status.eventId();
        Long eventSeason = db.sql("SELECT season_id FROM event WHERE id = :id").param("id", eventId)
                .query(Long.class).optional().orElse(null);
        if (eventSeason == null || eventSeason != champ.seasonId()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Live timing is bound to an event of another season");
        }

        List<Entry> entries = entries(eventId);
        Map<String, String> numberAliases = numberAliases(eventId);
        LiveClassification.Result running = LiveClassification.build(live.state("timing.session"), entries,
                numberAliases, classAliases(eventId));
        List<Slot> liveOrder = running.classes().stream()
                .filter(c -> c.className().trim().equalsIgnoreCase(champ.className().trim()))
                .findFirst()
                .map(c -> c.cars().stream().map(car -> new Slot(car.position(), car.entryId(), car.carNumber(),
                        car.competitorKey(), car.teamName(), car.manufacturer(), car.status(), car.laps(),
                        car.gapToLeaderMs(), car.gapToLeaderLaps())).toList())
                .orElse(List.of());
        List<Slot> qualifying = qualifying(eventId, champ.className());

        List<StandingsRow> standings = db.sql("""
                SELECT competitor_key, competitor_name FROM standings_row
                WHERE championship_id = :id ORDER BY position
                """)
                .param("id", champ.id())
                .query((rs, i) -> new StandingsRow(rs.getString("competitor_key"), rs.getString("competitor_name")))
                .list();

        var result = LiveChampionshipPositions.build(kind, champ.className(), standings, liveOrder, qualifying,
                "DRIVERS".equals(kind) ? crews(eventId) : Map.of(), numberAliases);
        return new ChampionshipResponse(status.state(), eventId, status.eventName(), status.session(),
                champ.id(), kind, champ.className(), livePhase(status.session()), !qualifying.isEmpty(),
                result.rows(), result.newcomers());
    }

    private static String livePhase(LiveTimingService.Session session) {
        String type = session == null || session.type() == null ? "" : session.type();
        return type.equals("RACE") ? "RACE" : type.startsWith("QUALIFYING") ? "QUALIFYING" : null;
    }

    /**
     * The class's imported qualifying result for the event, best first. A
     * weekend can hold several qualifying sessions (one per class group); an
     * entry's position is taken from the latest one it has a place in.
     */
    private List<Slot> qualifying(long eventId, String className) {
        return db.sql("""
                SELECT DISTINCT ON (en.id) en.id, en.car_number, en.team_name, en.manufacturer, r.position_in_class
                FROM result r
                         JOIN race_session rs ON rs.id = r.session_id AND rs.session_type = 'QUALIFYING'
                         JOIN entry en ON en.id = r.entry_id
                WHERE rs.event_id = :event AND r.position_in_class IS NOT NULL
                  AND lower(trim(en.class_name)) = lower(trim(:className))
                ORDER BY en.id, rs.ordinal DESC
                """)
                .param("event", eventId)
                .param("className", className)
                .query((rs, i) -> new Slot(rs.getInt("position_in_class"), rs.getLong("id"), rs.getString("car_number"),
                        rs.getString("car_number"), rs.getString("team_name"), rs.getString("manufacturer"),
                        null, null, null, null))
                .list()
                .stream()
                .sorted(java.util.Comparator.comparingInt(Slot::position))
                .toList();
    }

    private Map<Long, List<String>> crews(long eventId) {
        Map<Long, List<String>> crews = new HashMap<>();
        db.sql("""
                SELECT da.entry_id, d.first_name || ' ' || d.surname AS name
                FROM driver_assignment da
                         JOIN driver d ON d.id = da.driver_id
                         JOIN entry en ON en.id = da.entry_id
                WHERE en.event_id = :event
                ORDER BY da.seat_order
                """)
                .param("event", eventId)
                .query((rs, i) -> Map.entry(rs.getLong("entry_id"), rs.getString("name")))
                .list()
                .forEach(e -> crews.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add(e.getValue()));
        return crews;
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
