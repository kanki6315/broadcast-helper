package com.pitpass.imports.alkamel;

import com.pitpass.imports.alkamel.AlKamelImportService.AlKamelImport;
import com.pitpass.imports.alkamel.AlKamelImportService.StageRequest;
import com.pitpass.imports.alkamel.AlKamelImportService.YearPlan;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Admin-clicked imports from the Al Kamel results site. Every call here
 * reaches the external site, so the whole path is admin-only — including the
 * GETs, which SecurityConfig carves out of the members-may-read rule.
 */
@RestController
@RequestMapping("/api/imports/alkamel")
public class AlKamelImportController {

    private final AlKamelImportService service;

    public AlKamelImportController(AlKamelImportService service) {
        this.service = service;
    }

    /** The seasons the site publishes, oldest first. */
    @GetMapping("/years")
    public List<Integer> years() {
        return service.years();
    }

    /** Listings only — what a season import would read, weekend by weekend.
     *  With seriesId, just that series (plus weekends posted with no series folder). */
    @GetMapping("/plan")
    public YearPlan plan(@RequestParam int year, @RequestParam(required = false) Long seriesId) {
        return service.planYear(year, seriesId);
    }

    /** An event's weekend folder read fresh, each file marked new / updated /
     *  unchanged against what that folder already committed — or, for an
     *  event with no folder stamp, the candidate folders to pick from.
     *  sourceEvent names the folder to read when picking or overriding. */
    @GetMapping("/events/{eventId}/plan")
    public AlKamelImportService.EventPlan eventPlan(@PathVariable long eventId,
                                                    @RequestParam(required = false) String sourceEvent) {
        return service.planEvent(eventId, sourceEvent);
    }

    /** Downloads and stages one weekend's chosen files. Resilient: a file that
     *  fails is reported, the rest stage. The browser calls this once per
     *  weekend so a season import shows progress and can stop between them. */
    @PostMapping("/stage")
    @ResponseStatus(HttpStatus.CREATED)
    public AlKamelImport stage(@RequestBody StageRequest request) {
        return service.stage(request);
    }
}
