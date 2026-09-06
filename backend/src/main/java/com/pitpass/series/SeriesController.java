package com.pitpass.series;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/series")
public class SeriesController {

    private final SeriesRepository repository;
    private final JdbcClient db;

    public SeriesController(SeriesRepository repository, JdbcClient db) {
        this.repository = repository;
        this.db = db;
    }

    /** {@code primaryKind} is the series' headline championship kind (DRIVERS |
     *  TEAMS | MANUFACTURERS) — the one the season hub, the recap modal and the
     *  sheet's champ column put first. Null keeps the Teams-first default. */
    public record SeriesResponse(Long id, String name, String abbreviation, String primaryKind,
                                 List<String> aliases, Long logoVersion) {
    }

    /** The closed set of championship kinds — what a standings row ranks and
     *  therefore how it matches entries. {@code ImportService} validates the
     *  reviewer's kind against the same three values. */
    public static final List<String> CHAMPIONSHIP_KINDS = List.of("DRIVERS", "TEAMS", "MANUFACTURERS");

    public record PrimaryKindRequest(String primaryKind) {
    }

    public record CreateSeriesRequest(@NotBlank String name, String abbreviation) {
    }

    public record UpdateSeriesRequest(@NotBlank String name, String abbreviation) {
    }

    public record AddAliasRequest(@NotBlank String alias) {
    }

    @GetMapping
    public List<SeriesResponse> list() {
        Map<Long, List<String>> aliases = loadAliases();
        Map<Long, Long> logoVersions = loadLogoVersions();
        Map<Long, String> primaryKinds = loadPrimaryKinds();
        return repository.findAll(Sort.by("name")).stream()
                .map(s -> toResponse(s, aliases, logoVersions, primaryKinds))
                .toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SeriesResponse create(@Valid @RequestBody CreateSeriesRequest request) {
        if (repository.existsByNameIgnoreCase(request.name())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A series with that name already exists");
        }
        Series saved = repository.save(new Series(request.name().trim(), request.abbreviation()));
        return toResponse(saved, Map.of(), Map.of(), Map.of());
    }

    @PatchMapping("/{id}")
    public SeriesResponse update(@PathVariable long id, @Valid @RequestBody UpdateSeriesRequest request) {
        Series series = repository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such series"));
        String name = request.name().trim();
        if (!series.getName().equalsIgnoreCase(name) && repository.existsByNameIgnoreCase(name)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A series with that name already exists");
        }
        String abbreviation = request.abbreviation() == null || request.abbreviation().isBlank()
                ? null
                : request.abbreviation().trim();
        series.updateIdentity(name, abbreviation);
        return toResponse(repository.save(series), loadAliases(), loadLogoVersions(), loadPrimaryKinds());
    }

    /**
     * Which championship kind is this series' headline. Everything that ranks
     * kinds against each other reads it: the hub's kind chips default to it,
     * the sheet's champ column prefers it per class. A blank clears it back to
     * the Teams-first default. Stored outside the JPA entity like the aliases —
     * a plain column the JdbcClient reads.
     */
    @PutMapping("/{id}/primary-kind")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void setPrimaryKind(@PathVariable long id, @RequestBody PrimaryKindRequest request) {
        String raw = request.primaryKind() == null ? "" : request.primaryKind().trim().toUpperCase();
        String kind = raw.isEmpty() ? null : raw;
        if (kind != null && !CHAMPIONSHIP_KINDS.contains(kind)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Championship kind '" + request.primaryKind() + "' is not recognized. Choose one of "
                    + CHAMPIONSHIP_KINDS + ".");
        }
        int updated = db.sql("UPDATE series SET primary_kind = :kind WHERE id = :id")
                .param("kind", kind)
                .param("id", id)
                .update();
        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such series");
        }
    }

    @PostMapping("/{id}/aliases")
    @ResponseStatus(HttpStatus.CREATED)
    public SeriesResponse addAlias(@PathVariable long id, @Valid @RequestBody AddAliasRequest request) {
        Series series = repository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such series"));
        try {
            db.sql("INSERT INTO series_alias (series_id, alias) VALUES (:seriesId, :alias)")
                    .param("seriesId", id)
                    .param("alias", request.alias().trim())
                    .update();
        } catch (DuplicateKeyException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That alias already exists");
        }
        return toResponse(series, loadAliases(), loadLogoVersions(), loadPrimaryKinds());
    }

    private Map<Long, String> loadPrimaryKinds() {
        return db.sql("SELECT id, primary_kind FROM series WHERE primary_kind IS NOT NULL")
                .query((rs, i) -> Map.entry(rs.getLong("id"), rs.getString("primary_kind")))
                .list()
                .stream()
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private Map<Long, Long> loadLogoVersions() {
        return db.sql("SELECT series_id, uploaded_at FROM series_logo")
                .query((rs, i) -> Map.entry(rs.getLong("series_id"),
                        rs.getObject("uploaded_at", OffsetDateTime.class).toInstant().toEpochMilli()))
                .list()
                .stream()
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private Map<Long, List<String>> loadAliases() {
        return db.sql("SELECT series_id, alias FROM series_alias ORDER BY alias")
                .query((rs, i) -> Map.entry(rs.getLong("series_id"), rs.getString("alias")))
                .list()
                .stream()
                .collect(java.util.stream.Collectors.groupingBy(Map.Entry::getKey,
                        java.util.stream.Collectors.mapping(Map.Entry::getValue,
                                java.util.stream.Collectors.toList())));
    }

    private static SeriesResponse toResponse(Series series, Map<Long, List<String>> aliases,
                                             Map<Long, Long> logoVersions, Map<Long, String> primaryKinds) {
        return new SeriesResponse(series.getId(), series.getName(), series.getAbbreviation(),
                primaryKinds.get(series.getId()),
                aliases.getOrDefault(series.getId(), List.of()),
                logoVersions.get(series.getId()));
    }
}
