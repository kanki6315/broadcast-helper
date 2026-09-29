package com.pitpass.live;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The timing page's reads. Members may poll them all (GETs); the API's ETag
 * filter turns an unchanged body into a 304, and responses over 1 kB are
 * gzipped (server.compression). See docs/LIVE_TIMING.md.
 */
@RestController
@RequestMapping("/api/live")
public class LiveTimingPageController {

    private final LiveTimingPageService service;

    public LiveTimingPageController(LiveTimingPageService service) {
        this.service = service;
    }

    /** The tower: order, gaps and intervals from the feed, plus driver, laps and stint per car. */
    @GetMapping("/timing")
    public LiveTimingPageService.Tower timing() {
        return service.tower();
    }

    /** One car's laps, stints and drivers. session defaults to the one being fed, else the bound event's latest. */
    @GetMapping("/cars/{car}")
    public LiveTimingPageService.CarDetail car(@PathVariable String car, @RequestParam(required = false) Long session) {
        return service.car(car, session);
    }

    @GetMapping("/drive-time")
    public LiveTimingPageService.DriveTimeResponse driveTime(@RequestParam(required = false) Long session) {
        return service.driveTime(session);
    }

    /** Every recorded session of an event, newest first. */
    @GetMapping("/sessions")
    public List<LiveTimingPageService.SessionSummary> sessions(@RequestParam long eventId) {
        return service.sessions(eventId);
    }
}
