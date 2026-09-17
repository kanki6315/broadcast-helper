package com.pitpass.imports;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Whether an event counts as a championship round. Round numbers are handed
 * out by date to the events with the flag set; the Roar Before the 24 and the
 * test weekends sit on the calendar without one. The Al Kamel planner sets
 * the flag from its pre-season verdict at commit; this is the admin's way to
 * correct it for any event, whatever its source. Admin-only (PUT under /api).
 */
@RestController
@RequestMapping("/api/events")
public class EventRoundController {

    private final ImportService imports;

    public EventRoundController(ImportService imports) {
        this.imports = imports;
    }

    public record RoundRequest(boolean isRound) {
    }

    @PutMapping("/{id}/round")
    public void setRound(@PathVariable long id, @RequestBody RoundRequest request) {
        imports.setEventRound(id, request.isRound());
    }
}
