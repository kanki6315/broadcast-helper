package com.pitpass.live;

import com.pitpass.auth.Principals;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * Recorded sessions by weekend and series, and an admin's say over where a
 * series weekend (or one session) is filed. The PUTs are admin-only by
 * SecurityConfig's default. See docs/LIVE_TIMING_ALL_SERIES_PLAN.md, slice 4.
 */
@RestController
@RequestMapping("/api/live")
public class LiveWeekendController {

    /** Exactly one of: eventId (file it there), none (not in Pit Pass), auto (back to automatic filing). */
    public record BindRequest(Long eventId, Boolean none, Boolean auto) {
    }

    /** eventId null: the session goes back with its weekend. */
    public record OverrideRequest(Long eventId) {
    }

    private final LiveWeekends weekends;
    private final LiveFiling filing;
    private final JdbcClient db;

    public LiveWeekendController(LiveWeekends weekends, LiveFiling filing, JdbcClient db) {
        this.weekends = weekends;
        this.filing = filing;
        this.db = db;
    }

    @GetMapping("/weekends")
    public List<LiveWeekends.Weekend> weekends(@RequestParam(defaultValue = "60") int days) {
        return weekends.recent(Math.max(1, Math.min(days, 730)));
    }

    @GetMapping("/feed-events/{id}")
    public LiveWeekends.Championship feedEvent(@PathVariable long id) {
        return weekends.one(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such series weekend"));
    }

    @PutMapping("/feed-events/{id}/event")
    public LiveWeekends.Championship bind(@PathVariable long id, @RequestBody BindRequest request,
                                          Authentication authentication) {
        boolean none = request != null && Boolean.TRUE.equals(request.none());
        boolean auto = request != null && Boolean.TRUE.equals(request.auto());
        Long eventId = request == null ? null : request.eventId();
        if ((eventId != null ? 1 : 0) + (none ? 1 : 0) + (auto ? 1 : 0) != 1) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Choose an event, none, or automatic");
        }
        requireEvent(eventId);
        LiveFiling.Binding binding = eventId != null ? LiveFiling.Binding.EVENT
                : none ? LiveFiling.Binding.NONE : LiveFiling.Binding.AUTO;
        if (!filing.bindByAdmin(id, binding, eventId, Principals.emailOf(authentication))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such series weekend");
        }
        return feedEvent(id);
    }

    @PutMapping("/sessions/{id}/event")
    public void override(@PathVariable long id, @RequestBody OverrideRequest request) {
        Long eventId = request == null ? null : request.eventId();
        requireEvent(eventId);
        if (!filing.overrideSession(id, eventId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such live session");
        }
    }

    private void requireEvent(Long eventId) {
        if (eventId != null && db.sql("SELECT 1 FROM event WHERE id = :id").param("id", eventId)
                .query(Integer.class).optional().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such event");
        }
    }
}
