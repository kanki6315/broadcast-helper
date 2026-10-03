package com.pitpass.live;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

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
    private final LiveAnalysisService analysis;

    public LiveTimingPageController(LiveTimingPageService service, LiveAnalysisService analysis) {
        this.service = service;
        this.analysis = analysis;
    }

    /** Every car's gap to its class leader after each lap, class by class. */
    @GetMapping("/gaps")
    public LiveAnalysisService.GapsResponse gaps(@RequestParam(required = false) Long session) {
        return analysis.gaps(session);
    }

    /** Each car's best sectors and theoretical best over its valid laps, class by class. */
    @GetMapping("/sectors")
    public LiveAnalysisService.SectorsResponse sectors(@RequestParam(required = false) Long session) {
        return analysis.sectors(session);
    }

    /** Each car's pit stops: pit-lane time, lap, and who got in and out. */
    @GetMapping("/pits")
    public LiveAnalysisService.PitsResponse pits(@RequestParam(required = false) Long session) {
        return analysis.pits(session);
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

    /** Race control's messages for a session, newest first. */
    @GetMapping("/race-control")
    public LiveTimingPageService.RaceControlLog raceControl(@RequestParam(required = false) Long session) {
        return service.raceControl(session);
    }

    /** The weather station's readings for a session, one a minute, oldest first. */
    @GetMapping("/weather")
    public LiveTimingPageService.WeatherLog weather(@RequestParam(required = false) Long session) {
        return service.weather(session);
    }

    @GetMapping("/drive-time")
    public LiveTimingPageService.DriveTimeResponse driveTime(@RequestParam(required = false) Long session) {
        return service.driveTime(session);
    }

    /** Every recorded session of an event, or of one series weekend (feedEvent), newest first. */
    @GetMapping("/sessions")
    public List<LiveTimingPageService.SessionSummary> sessions(@RequestParam(required = false) Long eventId,
                                                               @RequestParam(required = false) Long feedEvent) {
        if ((eventId == null) == (feedEvent == null)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ask for an eventId or a feedEvent");
        }
        return eventId != null ? service.sessions(eventId) : service.sessionsOfFeedEvent(feedEvent);
    }
}
