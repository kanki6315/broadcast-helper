package com.broadcasthelper.imports;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/api/imports")
public class ImportController {

    private final ImportService imports;

    public ImportController(ImportService imports) {
        this.imports = imports;
    }

    /** One upload can stage several batches: a championship-points PDF carries
     *  every championship of the series, one batch each. */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public List<ImportService.BatchSummary> upload(@RequestParam("file") MultipartFile file,
                                                   @RequestParam(value = "format", defaultValue = "AUTO") ImportFormat format) {
        try {
            return imports.stage(file.getOriginalFilename(), file.getBytes(), format);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Could not read upload: " + e.getMessage());
        }
    }

    /**
     * Stages an iRacing subsession straight from the Data API — the same batches
     * uploading its exported file produces, without the export. The subsession id
     * is the number in the result's URL on iRacing's site.
     *
     * Needs credentials (see application-local.yml / IRacingClient); without them
     * it answers 503, and uploading the exported file remains a credential-free
     * alternative.
     */
    @PostMapping("/iracing/{subsessionId}")
    @ResponseStatus(HttpStatus.CREATED)
    public List<ImportService.BatchSummary> fetchIRacing(@PathVariable long subsessionId) {
        return imports.stageFromIRacing(subsessionId);
    }

    /** A league season's rounds, newest last — each carries the subsession id to
     *  import via {@link #fetchIRacing}. Lets a caller point at a season and pick
     *  a round without knowing subsession ids. */
    @GetMapping("/iracing/league/{leagueId}/season/{seasonId}/rounds")
    public List<IRacingParser.LeagueRound> leagueSeasonRounds(
            @PathVariable long leagueId, @PathVariable long seasonId) {
        return imports.listSeasonRounds(leagueId, seasonId);
    }

    /** Stages a league season's driver standings for review (season totals only —
     *  the API gives no per-round breakdown). Needs credentials; 503 without. */
    @PostMapping("/iracing/league/{leagueId}/season/{seasonId}/standings")
    @ResponseStatus(HttpStatus.CREATED)
    public List<ImportService.BatchSummary> fetchIRacingStandings(
            @PathVariable long leagueId, @PathVariable long seasonId) {
        return imports.stageStandingsFromIRacing(leagueId, seasonId);
    }

    /** Stages every round of a league season that has results — a lot of batches
     *  to review at once. Resilient: a round that fails is reported, not fatal. */
    @PostMapping("/iracing/league/{leagueId}/season/{seasonId}/import")
    @ResponseStatus(HttpStatus.CREATED)
    public ImportService.IRacingImport fetchIRacingSeason(
            @PathVariable long leagueId, @PathVariable long seasonId) {
        return imports.stageSeasonFromIRacing(leagueId, seasonId);
    }

    public record SubsessionIds(List<Long> subsessionIds) {
    }

    /** Stages a hand-picked list of subsessions (a season assembled by hand).
     *  Resilient: a bad id is reported, not fatal. Needs credentials; 503 without. */
    @PostMapping("/iracing/subsessions")
    @ResponseStatus(HttpStatus.CREATED)
    public ImportService.IRacingImport fetchIRacingSubsessions(@RequestBody SubsessionIds body) {
        if (body == null || body.subsessionIds() == null || body.subsessionIds().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No subsession ids given");
        }
        return imports.stageSubsessionsFromIRacing(body.subsessionIds());
    }

    @GetMapping
    public List<ImportService.BatchSummary> list() {
        return imports.list();
    }

    @GetMapping("/{id}")
    public ImportService.BatchSummary get(@PathVariable long id) {
        return imports.get(id);
    }

    @GetMapping(value = "/{id}/payload", produces = MediaType.APPLICATION_JSON_VALUE)
    public String payload(@PathVariable long id) {
        return imports.payloadJson(id);
    }

    /** The guessed target, selectable options, and class-mapping review.
     *  eventId / seasonYear recompute the class review against the season the
     *  reviewer picked — needed for files with no session metadata (grid CSVs)
     *  or that only guess their season (championship-points PDFs). */
    @GetMapping("/{id}/review")
    public ImportService.ImportReview review(@PathVariable long id,
                                             @RequestParam(required = false) Long eventId,
                                             @RequestParam(required = false) Integer seasonYear) {
        return imports.reviewTarget(id, eventId, seasonYear);
    }

    /** Commit to the reviewer-confirmed target (series/event/championship + class map). */
    @PostMapping("/{id}/commit")
    public ImportService.BatchSummary commit(@PathVariable long id,
                                             @RequestBody ImportService.ImportTarget target) {
        return imports.commit(id, target);
    }

    @PostMapping("/{id}/discard")
    public ImportService.BatchSummary discard(@PathVariable long id) {
        imports.discard(id);
        return imports.get(id);
    }
}
