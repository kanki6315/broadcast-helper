package com.pitpass.live;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Types;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * An event's drive-time rules (V58). Read by any member; replaced as a whole
 * set by an admin (PUT, admin-only by SecurityConfig's default for writes),
 * which is how the rules editor saves. Times are milliseconds.
 */
@RestController
public class DriveTimeRuleController {

    public record Rule(String className, String rating, Long minMs, Long maxMs, String note) {
    }

    private static final Set<String> RATINGS = Set.of("B", "S", "G", "P");

    private final JdbcClient db;

    public DriveTimeRuleController(JdbcClient db) {
        this.db = db;
    }

    @GetMapping("/api/events/{eventId}/drive-time-rules")
    public List<Rule> list(@PathVariable long eventId) {
        requireEvent(eventId);
        return rules(db, eventId);
    }

    @PutMapping("/api/events/{eventId}/drive-time-rules")
    @Transactional
    public List<Rule> replace(@PathVariable long eventId, @RequestBody List<Rule> rules) {
        requireEvent(eventId);
        List<Rule> clean = validate(rules == null ? List.of() : rules);
        db.sql("DELETE FROM drive_time_rule WHERE event_id = :e").param("e", eventId).update();
        for (Rule r : clean) {
            db.sql("""
                    INSERT INTO drive_time_rule (event_id, class_name, rating, min_ms, max_ms, note)
                    VALUES (:e, :c, :r, :min, :max, :note)
                    """)
                    .param("e", eventId).param("c", r.className())
                    .param("r", r.rating(), Types.VARCHAR)
                    .param("min", r.minMs(), Types.BIGINT)
                    .param("max", r.maxMs(), Types.BIGINT)
                    .param("note", r.note(), Types.VARCHAR)
                    .update();
        }
        return rules(db, eventId);
    }

    static List<Rule> rules(JdbcClient db, long eventId) {
        return db.sql("""
                SELECT class_name, rating, min_ms, max_ms, note FROM drive_time_rule
                WHERE event_id = :e ORDER BY lower(class_name), rating NULLS FIRST
                """)
                .param("e", eventId)
                .query((rs, i) -> new Rule(rs.getString("class_name"), rs.getString("rating"),
                        rs.getObject("min_ms", Long.class), rs.getObject("max_ms", Long.class), rs.getString("note")))
                .list();
    }

    private static List<Rule> validate(List<Rule> rules) {
        Set<String> seen = new HashSet<>();
        return rules.stream().map(r -> {
            String className = r.className() == null ? "" : r.className().trim();
            if (className.isEmpty()) {
                throw unprocessable("Every rule needs a class");
            }
            String rating = r.rating() == null || r.rating().isBlank() ? null : r.rating().trim().substring(0, 1).toUpperCase();
            if (rating != null && !RATINGS.contains(rating)) {
                throw unprocessable("Rating must be Bronze, Silver, Gold or Platinum (or blank for everyone)");
            }
            if (r.minMs() == null && r.maxMs() == null) {
                throw unprocessable("A rule for " + className + " needs a minimum, a maximum or both");
            }
            if ((r.minMs() != null && r.minMs() < 0) || (r.maxMs() != null && r.maxMs() < 0)) {
                throw unprocessable("Drive times cannot be negative");
            }
            if (r.minMs() != null && r.maxMs() != null && r.minMs() > r.maxMs()) {
                throw unprocessable("The minimum for " + className + " is above its maximum");
            }
            if (!seen.add(className.toLowerCase() + "|" + rating)) {
                throw unprocessable("Two rules for " + className + (rating == null ? "" : " " + rating));
            }
            String note = r.note() == null || r.note().isBlank() ? null : r.note().trim();
            return new Rule(className, rating, r.minMs(), r.maxMs(), note);
        }).toList();
    }

    private void requireEvent(long eventId) {
        if (db.sql("SELECT 1 FROM event WHERE id = :e").param("e", eventId).query(Integer.class).optional().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such event");
        }
    }

    private static ResponseStatusException unprocessable(String message) {
        return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, message);
    }
}
