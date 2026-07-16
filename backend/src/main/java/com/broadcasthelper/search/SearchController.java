package com.broadcasthelper.search;

import com.broadcasthelper.drivers.DriverController;
import com.broadcasthelper.drivers.DriverController.SearchHit;
import com.broadcasthelper.teams.TeamController;
import com.broadcasthelper.teams.TeamController.TeamHit;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The ⌘K palette's one-round-trip search: drivers and teams for a query,
 * each ranked by their own controller's rules.
 */
@RestController
@RequestMapping("/api")
public class SearchController {

    private final DriverController drivers;
    private final TeamController teams;

    public SearchController(DriverController drivers, TeamController teams) {
        this.drivers = drivers;
        this.teams = teams;
    }

    public record SearchResults(List<SearchHit> drivers, List<TeamHit> teams) {
    }

    @GetMapping("/search")
    public SearchResults search(@RequestParam String q) {
        return new SearchResults(drivers.search(q, 8), teams.search(q, 6));
    }
}
