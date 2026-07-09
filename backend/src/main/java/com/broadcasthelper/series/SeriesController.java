package com.broadcasthelper.series;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/api/series")
public class SeriesController {

    private final SeriesRepository repository;

    public SeriesController(SeriesRepository repository) {
        this.repository = repository;
    }

    public record SeriesResponse(Long id, String name, String abbreviation) {
        static SeriesResponse from(Series series) {
            return new SeriesResponse(series.getId(), series.getName(), series.getAbbreviation());
        }
    }

    public record CreateSeriesRequest(@NotBlank String name, String abbreviation) {
    }

    @GetMapping
    public List<SeriesResponse> list() {
        return repository.findAll(Sort.by("name")).stream()
                .map(SeriesResponse::from)
                .toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SeriesResponse create(@Valid @RequestBody CreateSeriesRequest request) {
        if (repository.existsByNameIgnoreCase(request.name())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A series with that name already exists");
        }
        Series saved = repository.save(new Series(request.name().trim(), request.abbreviation()));
        return SeriesResponse.from(saved);
    }
}
