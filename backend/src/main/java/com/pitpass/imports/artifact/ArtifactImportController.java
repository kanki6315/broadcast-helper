package com.pitpass.imports.artifact;

import com.pitpass.imports.artifact.ArtifactCorrectionService.ApplyResult;
import com.pitpass.imports.artifact.ArtifactCorrectionService.CorrectionRequest;
import com.pitpass.imports.artifact.ArtifactCorrectionService.LeagueOption;
import com.pitpass.imports.artifact.ArtifactCorrectionService.Plan;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The IMSA Esports correction from artifactracing.com (see
 * {@link ArtifactCorrectionService}). Every call reads the external site, so
 * the whole path is admin-only, GETs included — SecurityConfig carves it out
 * of the members-may-read rule.
 */
@RestController
@RequestMapping("/api/imports/artifact")
public class ArtifactImportController {

    private final ArtifactCorrectionService service;

    public ArtifactImportController(ArtifactCorrectionService service) {
        this.service = service;
    }

    /** The site's seasons, newest first. */
    @GetMapping("/leagues")
    public List<LeagueOption> leagues() {
        return service.leagues();
    }

    /** What the correction would change and what still needs deciding. Writes nothing. */
    @PostMapping("/plan")
    public Plan plan(@RequestBody CorrectionRequest request) {
        return service.plan(request);
    }

    /** Re-plans with the request's decisions and applies it; 422 while anything still blocks. */
    @PostMapping("/apply")
    public ApplyResult apply(@RequestBody CorrectionRequest request) {
        return service.apply(request);
    }
}
