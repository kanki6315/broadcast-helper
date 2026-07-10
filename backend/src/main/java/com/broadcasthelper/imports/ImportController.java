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
import java.util.Map;

@RestController
@RequestMapping("/api/imports")
public class ImportController {

    private final ImportService imports;

    public ImportController(ImportService imports) {
        this.imports = imports;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public ImportService.BatchSummary upload(@RequestParam("file") MultipartFile file) {
        try {
            return imports.stage(file.getOriginalFilename(), file.getBytes());
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Could not read upload: " + e.getMessage());
        }
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

    @GetMapping("/{id}/class-review")
    public ImportService.ClassReview classReview(@PathVariable long id) {
        return imports.classReview(id);
    }

    /** Optional body maps each unrecognized source class spelling to a canonical class. */
    public record CommitRequest(Map<String, String> classMapping) {
    }

    @PostMapping("/{id}/commit")
    public ImportService.BatchSummary commit(@PathVariable long id,
                                             @RequestBody(required = false) CommitRequest body) {
        return imports.commit(id, body == null ? Map.of() : body.classMapping());
    }

    @PostMapping("/{id}/discard")
    public ImportService.BatchSummary discard(@PathVariable long id) {
        imports.discard(id);
        return imports.get(id);
    }
}
