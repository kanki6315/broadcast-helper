package com.pitpass.live;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;

/**
 * The championships the live feed has carried, and which Pit Pass series each
 * stands for — Manage → Live timing. A championship resolves by
 * series.name or series_alias ({@link LiveFiling#seriesFor}); mapping one
 * writes a series_alias and tries its unfiled weekends again. The mapping is
 * a POST and so admin-only by SecurityConfig's default.
 */
@RestController
@RequestMapping("/api/live/feed-championships")
public class LiveFeedChampionshipController {

    /** series* are null when the name resolves to no series (or to several). */
    public record FeedChampionship(Long champDbId, String champName, Long seriesId, String seriesName,
                                   int weekends, int unfiledWeekends, Instant lastSeen) {
    }

    public record MapRequest(String champName, Long seriesId) {
    }

    public record MapResponse(FeedChampionship championship, int weekendsFiled) {
    }

    private final JdbcClient db;
    private final LiveFiling filing;

    public LiveFeedChampionshipController(JdbcClient db, LiveFiling filing) {
        this.db = db;
        this.filing = filing;
    }

    @GetMapping
    public List<FeedChampionship> list() {
        record Seen(Long champDbId, String champName, int weekends, int unfiled, Instant lastSeen) {
        }
        return db.sql("""
                SELECT max(s.champ_db_id) AS champ_db_id, s.champ_name,
                       count(DISTINCT s.feed_event_db_id) AS weekends,
                       count(DISTINCT s.feed_event_db_id) FILTER (WHERE fe.event_id IS NULL) AS unfiled,
                       max(s.first_seen_at) AS last_seen
                FROM live_session s LEFT JOIN live_feed_event fe ON fe.feed_event_db_id = s.feed_event_db_id
                WHERE s.champ_name IS NOT NULL
                GROUP BY s.champ_name
                ORDER BY max(s.first_seen_at) DESC
                """)
                .query((rs, i) -> new Seen(rs.getObject("champ_db_id", Long.class), rs.getString("champ_name"),
                        rs.getInt("weekends"), rs.getInt("unfiled"), rs.getTimestamp("last_seen").toInstant()))
                .list().stream()
                .map(c -> withSeries(c.champDbId(), c.champName(), c.weekends(), c.unfiled(), c.lastSeen()))
                .toList();
    }

    /** Maps a championship to a series by alias, then files what it now can. */
    @PostMapping("/map")
    public MapResponse map(@RequestBody MapRequest request) {
        if (request == null || request.champName() == null || request.champName().isBlank() || request.seriesId() == null) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Choose a championship and a series");
        }
        String name = request.champName().trim();
        if (db.sql("SELECT 1 FROM series WHERE id = :id").param("id", request.seriesId()).query(Integer.class)
                .optional().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such series");
        }
        if (db.sql("SELECT 1 FROM live_session WHERE lower(champ_name) = lower(:n) LIMIT 1").param("n", name)
                .query(Integer.class).optional().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "The feed has not carried " + name);
        }
        if (filing.seriesFor(name) != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, name + " already stands for a series");
        }
        try {
            db.sql("INSERT INTO series_alias (series_id, alias) VALUES (:s, :a)")
                    .param("s", request.seriesId()).param("a", name).update();
        } catch (DuplicateKeyException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That alias already exists");
        }
        int filed = filing.sweepChampionship(name);
        FeedChampionship after = list().stream().filter(c -> c.champName().equalsIgnoreCase(name)).findFirst()
                .orElseThrow();
        return new MapResponse(after, filed);
    }

    private FeedChampionship withSeries(Long champDbId, String champName, int weekends, int unfiled, Instant lastSeen) {
        Long series = filing.seriesFor(champName);
        String seriesName = series == null ? null
                : db.sql("SELECT name FROM series WHERE id = :id").param("id", series).query(String.class).single();
        return new FeedChampionship(champDbId, champName, series, seriesName, weekends, unfiled, lastSeen);
    }
}
