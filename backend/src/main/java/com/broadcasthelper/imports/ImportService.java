package com.broadcasthelper.imports;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class ImportService {

    public record BatchSummary(long id, String kind, String format, String filename, String status,
                               String summary, OffsetDateTime createdAt) {
    }

    private final JdbcClient db;
    private final ObjectMapper json;
    private final String parserPython;
    private final String parserScript;

    public ImportService(JdbcClient db, ObjectMapper json,
                         @org.springframework.beans.factory.annotation.Value("${broadcast-helper.entry-list-parser.python:python3}") String parserPython,
                         @org.springframework.beans.factory.annotation.Value("${broadcast-helper.entry-list-parser.script:../parser/parse_entry_list.py}") String parserScript) {
        this.db = db;
        this.json = json;
        this.parserPython = parserPython;
        this.parserScript = parserScript;
    }

    // ---------------------------------------------------------------- staging

    /** One family parser's output, ready for the shared import_batch insert. */
    private record Staged(String kind, Object payload, String summary) {
    }

    public BatchSummary stage(String filename, byte[] content, ImportFormat format) {
        ImportFormat resolved = format == ImportFormat.AUTO ? resolveAuto(content) : format;
        Staged staged = switch (resolved) {
            case AUTO -> throw new IllegalStateException("AUTO must be resolved before staging");
            case IMSA_JSON -> stageImsaJson(content);
            case IMSA_PDF -> stageImsaPdf(filename, content);
            case IMSA_CSV -> stageImsaCsv(content);
        };

        long id = db.sql("""
                        INSERT INTO import_batch (kind, format, filename, payload, summary)
                        VALUES (:kind, :format, :filename, :payload::jsonb, :summary)
                        RETURNING id
                        """)
                .param("kind", staged.kind())
                .param("format", resolved.name())
                .param("filename", filename)
                .param("payload", toJson(staged.payload()))
                .param("summary", staged.summary())
                .query(Long.class)
                .single();
        return get(id);
    }

    /**
     * AUTO covers what the tool historically accepted: IMSA entry-list PDFs and
     * timing-provider JSON. CSVs are never auto-detected — their shapes are
     * provider-specific and would collide across families — but get a targeted
     * hint instead of the generic JSON error.
     */
    private ImportFormat resolveAuto(byte[] content) {
        if (isPdf(content)) {
            return ImportFormat.IMSA_PDF;
        }
        if (ImportParser.looksLikeGridCsv(content)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "This looks like a semicolon-delimited CSV — choose a format explicitly"
                    + " (e.g. IMSA — Grid CSV) instead of Auto-detect");
        }
        return ImportFormat.IMSA_JSON;
    }

    private Staged stageImsaPdf(String filename, byte[] pdf) {
        if (!isPdf(pdf)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Not a PDF file (expected an IMSA entry-list PDF)");
        }
        return stageImsaJson(runEntryListParser(filename, pdf));
    }

    private Staged stageImsaJson(byte[] content) {
        JsonNode root;
        try {
            root = json.readTree(content); // Jackson strips the UTF-8 BOM some files carry
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Not valid JSON: " + e.getMessage());
        }

        if (ImportParser.looksLikeEntryList(root)) {
            EntryListImport parsed = ImportParser.parseEntryList(root);
            long tbd = parsed.entries().stream()
                    .flatMap(e -> e.drivers().stream()).filter(EntryListImport.Driver::isTbd).count();
            long unparsed = parsed.entries().stream()
                    .flatMap(e -> e.drivers().stream()).filter(EntryListImport.Driver::unparsed).count();
            String summary = "%s — entry list, %d entries".formatted(parsed.event().name(), parsed.entries().size());
            if (tbd > 0) {
                summary += ", %d TBD seat(s)".formatted(tbd);
            }
            if (unparsed > 0) {
                summary += ", %d UNPARSED driver line(s)".formatted(unparsed);
            }
            return new Staged("ENTRY_LIST", parsed, summary);
        } else if (ImportParser.looksLikeStandings(root)) {
            StandingsImport parsed = ImportParser.parseStandings(root);
            return new Staged("STANDINGS", parsed, "%s — %d competitors, %d sessions".formatted(
                    parsed.mainTitle(), parsed.rows().size(), parsed.sessions().size()));
        } else if (ImportParser.looksLikeRaceResults(root)) {
            RaceResultsImport parsed = ImportParser.parseRaceResults(root);
            return new Staged("RACE_RESULTS", parsed, "%s — %s, %d classified entries".formatted(
                    parsed.eventName(), parsed.sessionName(), parsed.rows().size()));
        } else if (ImportParser.looksLikeGrid(root)) {
            GridImport parsed = ImportParser.parseGrid(root);
            return new Staged("GRID", parsed, "%s — %s starting grid, %d cars".formatted(
                    parsed.eventName(), parsed.sessionName(), parsed.rows().size()));
        }
        throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                "Unrecognized file format: expected a results file (session + classification), "
                + "a starting grid (session + grid), or a standings file (championship + classification)");
    }

    /** The IMSA CSV family. Today it recognizes one document kind: the starting
     *  grid (POSITION;CLASS;NUMBER;... header). A results CSV later is a new
     *  header branch here, not a new format. */
    private Staged stageImsaCsv(byte[] content) {
        if (!ImportParser.looksLikeGridCsv(content)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Unrecognized IMSA CSV: expected a starting-grid header (POSITION;CLASS;NUMBER;...)");
        }
        GridImport parsed;
        try {
            parsed = ImportParser.parseGridCsv(content);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
        }
        return new Staged("GRID", parsed, "Starting grid CSV — %d cars".formatted(parsed.rows().size()));
    }

    public List<BatchSummary> list() {
        return db.sql("""
                        SELECT id, kind, format, filename, status, summary, created_at
                        FROM import_batch ORDER BY id DESC
                        """)
                .query((rs, i) -> new BatchSummary(rs.getLong("id"), rs.getString("kind"),
                        rs.getString("format"), rs.getString("filename"), rs.getString("status"),
                        rs.getString("summary"), rs.getObject("created_at", OffsetDateTime.class)))
                .list();
    }

    public BatchSummary get(long id) {
        return db.sql("""
                        SELECT id, kind, format, filename, status, summary, created_at
                        FROM import_batch WHERE id = :id
                        """)
                .param("id", id)
                .query((rs, i) -> new BatchSummary(rs.getLong("id"), rs.getString("kind"),
                        rs.getString("format"), rs.getString("filename"), rs.getString("status"),
                        rs.getString("summary"), rs.getObject("created_at", OffsetDateTime.class)))
                .optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such import batch"));
    }

    public String payloadJson(long id) {
        return db.sql("SELECT payload::text FROM import_batch WHERE id = :id")
                .param("id", id)
                .query(String.class)
                .optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such import batch"));
    }

    public void discard(long id) {
        int updated = db.sql("UPDATE import_batch SET status = 'DISCARDED' WHERE id = :id AND status = 'STAGED'")
                .param("id", id)
                .update();
        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Batch is not in STAGED state");
        }
    }

    // -------------------------------------------------------------- class review

    /** knownClasses: the season's canonical (entry-list) classes.
     *  unknownClasses: class spellings in this batch that match none of them and
     *  need a manual mapping before the batch can be committed. */
    public record ClassReview(List<String> knownClasses, List<String> unknownClasses) {
    }

    /**
     * Flags class spellings in a staged results/standings batch that don't match
     * the season's canonical (entry-list) classes — e.g. Endurance Cup standings
     * spelling "GT Daytona PRO" where entries say "GTDPRO". Best-effort and never
     * throws: if the series/season isn't resolvable yet (or has no entries to be
     * the authority), there is nothing to flag.
     */
    public ClassReview classReview(long id) {
        BatchSummary batch = get(id);
        if (!"STAGED".equals(batch.status())) {
            return new ClassReview(List.of(), List.of());
        }
        String payload = payloadJson(id);
        try {
            return switch (batch.kind()) {
                case "STANDINGS" -> reviewStandings(json.readValue(payload, StandingsImport.class));
                case "RACE_RESULTS" -> reviewRaceResults(json.readValue(payload, RaceResultsImport.class));
                case "GRID" -> reviewGrid(json.readValue(payload, GridImport.class));
                default -> new ClassReview(List.of(), List.of());
            };
        } catch (JsonProcessingException e) {
            return new ClassReview(List.of(), List.of());
        }
    }

    private ClassReview reviewStandings(StandingsImport imp) {
        try {
            SeriesMatch match = matchSeriesByTitle(imp.mainTitle());
            Optional<Long> seasonId = findSeasonId(match.seriesId(), Integer.parseInt(imp.year()));
            if (seasonId.isEmpty()) {
                return new ClassReview(List.of(), List.of());
            }
            List<String> known = seasonEntryClasses(seasonId.get());
            String className = deriveClassAndKind(imp.mainTitle(), match.matchedPrefix()).className();
            List<String> unknown = isUnknownClass(className, known) ? List.of(className) : List.of();
            return new ClassReview(known, unknown);
        } catch (ResponseStatusException | NumberFormatException e) {
            return new ClassReview(List.of(), List.of());
        }
    }

    private ClassReview reviewRaceResults(RaceResultsImport imp) {
        if (imp.sessionStart() == null) {
            return new ClassReview(List.of(), List.of());
        }
        Optional<Long> seriesId = findSeriesByName(imp.championshipName());
        Optional<Long> seasonId = seriesId.flatMap(sid -> findSeasonId(sid, imp.sessionStart().getYear()));
        if (seasonId.isEmpty()) {
            return new ClassReview(List.of(), List.of());
        }
        return classReviewForSeason(seasonId.get(),
                imp.rows().stream().map(RaceResultsImport.Row::className).toList());
    }

    private ClassReview reviewGrid(GridImport imp) {
        if (imp.sessionStart() == null) {
            return new ClassReview(List.of(), List.of());
        }
        Optional<Long> seriesId = findSeriesByName(imp.championshipName());
        Optional<Long> seasonId = seriesId.flatMap(sid -> findSeasonId(sid, imp.sessionStart().getYear()));
        if (seasonId.isEmpty()) {
            return new ClassReview(List.of(), List.of());
        }
        return classReviewForSeason(seasonId.get(),
                imp.rows().stream().map(GridImport.Row::className).toList());
    }

    // -------------------------------------------------------------- target review

    public record SeriesOption(long id, String name, String abbreviation) {
    }

    public record EventOption(long id, String name, String eventDate) {
    }

    /** The tool's guess for the import target; every field is editable in review. */
    public record TargetGuess(Long seriesId, String seriesName, Integer seasonYear,
                              Long eventId, String eventName, String circuit, String eventDate,
                              String classCode, String kind, Boolean isCup, String familyName) {
    }

    public record ImportReview(String kind, TargetGuess guess,
                               List<SeriesOption> seriesOptions, List<EventOption> eventOptions,
                               ClassReview classReview,
                               boolean needsSession) {
    }

    /** Everything the review screen needs: the guessed target, the options to pick
     *  from, and the class-mapping review — so nothing is inferred at commit.
     *  chosenEventId (optional) recomputes the class review against that event's
     *  season, for files whose payload can't resolve a season by itself. */
    public ImportReview reviewTarget(long id, Long chosenEventId) {
        BatchSummary batch = get(id);
        List<SeriesOption> seriesOptions = db.sql("SELECT id, name, abbreviation FROM series ORDER BY name")
                .query((rs, i) -> new SeriesOption(rs.getLong("id"), rs.getString("name"),
                        rs.getString("abbreviation")))
                .list();
        ClassReview cr = classReview(id);
        String payload = payloadJson(id);
        try {
            boolean needsSession = false;
            TargetGuess guess;
            switch (batch.kind()) {
                case "ENTRY_LIST" -> guess = guessEntryList(json.readValue(payload, EntryListImport.class));
                case "RACE_RESULTS" -> guess = guessRaceResults(json.readValue(payload, RaceResultsImport.class));
                case "GRID" -> {
                    GridImport imp = json.readValue(payload, GridImport.class);
                    guess = guessGrid(imp);
                    needsSession = imp.sessionStart() == null;
                    if (chosenEventId != null && "STAGED".equals(batch.status())) {
                        cr = classReviewForSeason(seasonIdOfEvent(chosenEventId),
                                imp.rows().stream().map(GridImport.Row::className).toList());
                    }
                }
                case "STANDINGS" -> guess = guessStandings(json.readValue(payload, StandingsImport.class));
                default -> guess = null;
            }
            // Without a series/season guess the usual per-season event list is
            // empty and the reviewer would be stuck with only "+ new event" —
            // forking a duplicate event rather than attaching to the real one.
            // Fall back to every event so they can always override the guess.
            List<EventOption> events;
            if (guess != null && guess.seriesId() != null && guess.seasonYear() != null) {
                events = eventsInSeason(guess.seriesId(), guess.seasonYear());
            } else {
                events = allEvents();
            }
            return new ImportReview(batch.kind(), guess, seriesOptions, events, cr, needsSession);
        } catch (JsonProcessingException e) {
            return new ImportReview(batch.kind(), null, seriesOptions, List.of(), cr, false);
        }
    }

    private TargetGuess guessEntryList(EntryListImport imp) {
        Integer year = imp.event().startDate() != null ? imp.event().startDate().getYear() : null;
        Optional<Long> seriesId = findSeriesByCode(imp.event().series());
        LocalDate date = imp.event().endDate() != null ? imp.event().endDate() : imp.event().startDate();
        Long eventGuess = seriesId.flatMap(sid -> year == null ? Optional.<Long>empty()
                : findSeasonId(sid, year)).flatMap(sn -> findMatchingEvent(sn, imp.event().circuit(), date))
                .orElse(null);
        return new TargetGuess(seriesId.orElse(null), seriesId.map(this::seriesName).orElse(null), year,
                eventGuess, imp.event().name(), imp.event().circuit(),
                date != null ? date.toString() : null, null, null, null, null);
    }

    private TargetGuess guessRaceResults(RaceResultsImport imp) {
        Integer year = imp.sessionStart() != null ? imp.sessionStart().getYear() : null;
        Optional<Long> seriesId = findSeriesByName(imp.championshipName());
        LocalDate date = imp.sessionStart() != null ? imp.sessionStart().toLocalDate() : null;
        Long eventGuess = seriesId.flatMap(sid -> year == null ? Optional.<Long>empty()
                : findSeasonId(sid, year)).flatMap(sn -> findMatchingEvent(sn, imp.circuitName(), date))
                .orElse(null);
        return new TargetGuess(seriesId.orElse(null), seriesId.map(this::seriesName).orElse(null), year,
                eventGuess, imp.eventName(), imp.circuitName(),
                date != null ? date.toString() : null, null, null, null, null);
    }

    private TargetGuess guessGrid(GridImport imp) {
        Integer year = imp.sessionStart() != null ? imp.sessionStart().getYear() : null;
        Optional<Long> seriesId = findSeriesByName(imp.championshipName());
        LocalDate date = imp.sessionStart() != null ? imp.sessionStart().toLocalDate() : null;
        Long eventGuess = seriesId.flatMap(sid -> year == null ? Optional.<Long>empty()
                : findSeasonId(sid, year)).flatMap(sn -> findMatchingEvent(sn, imp.circuitName(), date))
                .orElse(null);
        return new TargetGuess(seriesId.orElse(null), seriesId.map(this::seriesName).orElse(null), year,
                eventGuess, imp.eventName(), imp.circuitName(),
                date != null ? date.toString() : null, null, null, null, null);
    }

    private TargetGuess guessStandings(StandingsImport imp) {
        Integer year = null;
        try {
            year = Integer.parseInt(imp.year());
        } catch (NumberFormatException ignored) {
            // leave null; reviewer supplies it via the series/season
        }
        Optional<SeriesMatch> match = matchSeriesByTitleOpt(imp.mainTitle());
        Optional<Long> seriesId = match.map(SeriesMatch::seriesId);
        ClassAndKind ck = match.isPresent()
                ? deriveClassAndKind(imp.mainTitle(), match.get().matchedPrefix())
                : deriveClassKindFromTail(imp.mainTitle());
        String seriesName = seriesId.map(this::seriesName).orElse(null);
        // Default: the primary championship, grouped under the series name. A cup
        // is the reviewer flipping is_cup and naming the family.
        return new TargetGuess(seriesId.orElse(null), seriesName, year, null, null, null, null,
                ck.className(), ck.kind(), Boolean.FALSE, seriesName);
    }

    /** Every event across all seasons, labeled with series + year, newest first.
     *  The fallback event list when a payload carries nothing to narrow by. */
    private List<EventOption> allEvents() {
        return db.sql("""
                        SELECT e.id, sr.name || ' ' || s.year || ' — ' || e.name AS label, e.event_date
                        FROM event e
                                 JOIN season s ON s.id = e.season_id
                                 JOIN series sr ON sr.id = s.series_id
                        ORDER BY e.event_date DESC NULLS LAST, e.id DESC
                        """)
                .query((rs, i) -> new EventOption(rs.getLong("id"), rs.getString("label"),
                        rs.getObject("event_date", LocalDate.class) != null
                                ? rs.getObject("event_date", LocalDate.class).toString() : null))
                .list();
    }

    /** Class review for a known season: which of the batch's class spellings
     *  match none of the season's canonical (entry-list) classes. */
    private ClassReview classReviewForSeason(long seasonId, List<String> batchClasses) {
        List<String> known = seasonEntryClasses(seasonId);
        LinkedHashSet<String> unknown = new LinkedHashSet<>();
        for (String className : batchClasses) {
            if (isUnknownClass(className, known)) {
                unknown.add(className);
            }
        }
        return new ClassReview(known, new ArrayList<>(unknown));
    }

    private long seasonIdOfEvent(long eventId) {
        return db.sql("SELECT season_id FROM event WHERE id = :id")
                .param("id", eventId)
                .query(Long.class)
                .optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "No such event: " + eventId));
    }

    private List<EventOption> eventsInSeason(long seriesId, int year) {
        return db.sql("""
                        SELECT e.id, e.name, e.event_date
                        FROM event e JOIN season s ON s.id = e.season_id
                        WHERE s.series_id = :seriesId AND s.year = :year
                        ORDER BY e.event_date NULLS LAST, e.id
                        """)
                .param("seriesId", seriesId).param("year", year)
                .query((rs, i) -> new EventOption(rs.getLong("id"), rs.getString("name"),
                        rs.getObject("event_date", LocalDate.class) != null
                                ? rs.getObject("event_date", LocalDate.class).toString() : null))
                .list();
    }

    private Optional<Long> findSeriesByCode(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        return db.sql("""
                        SELECT id FROM series WHERE lower(abbreviation) = lower(:code)
                        UNION
                        SELECT series_id FROM series_alias WHERE lower(alias) = lower(:code)
                        """)
                .param("code", code).query(Long.class).optional();
    }

    private Optional<SeriesMatch> matchSeriesByTitleOpt(String mainTitle) {
        try {
            return Optional.of(matchSeriesByTitle(mainTitle));
        } catch (ResponseStatusException e) {
            return Optional.empty();
        }
    }

    /** Fallback class/kind when no series prefix matched: kind is the last word,
     *  class the word before it (works for single-token classes like GTP/DH). */
    private static ClassAndKind deriveClassKindFromTail(String title) {
        String[] parts = title == null ? new String[0] : title.trim().split("\\s+");
        if (parts.length < 2) {
            return new ClassAndKind(null, null);
        }
        return new ClassAndKind(parts[parts.length - 2], parts[parts.length - 1].toUpperCase());
    }

    // ---------------------------------------------------------------- commit

    /**
     * The import's confirmed destination, chosen by the reviewer (pre-filled with
     * the tool's guess). Series/event/championship are selected explicitly rather
     * than re-matched from the file's free-text names at commit time.
     */
    public record ImportTarget(
            Long seriesId, String newSeriesName,   // pick an existing series, or create one
            Long eventId,                          // results/entry-list: attach to this event, or null = new
            String classCode, String kind, Boolean isCup, String familyName, // standings championship
            String sessionType, Integer sessionOrdinal, // for files with no session metadata (grid CSVs)
            Map<String, String> classMapping
    ) {
        Map<String, String> mapping() {
            return classMapping == null ? Map.of() : classMapping;
        }
    }

    @Transactional
    public BatchSummary commit(long id, ImportTarget target) {
        if (target == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No import target supplied");
        }
        BatchSummary batch = get(id);
        if (!"STAGED".equals(batch.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Batch is not in STAGED state");
        }
        String payload = payloadJson(id);
        try {
            switch (batch.kind()) {
                case "RACE_RESULTS" -> commitRaceResults(json.readValue(payload, RaceResultsImport.class), target);
                case "GRID" -> commitGrid(json.readValue(payload, GridImport.class), target);
                case "STANDINGS" -> commitStandings(json.readValue(payload, StandingsImport.class), target);
                case "ENTRY_LIST" -> commitEntryList(json.readValue(payload, EntryListImport.class), target);
                default -> throw new IllegalStateException("Unknown batch kind " + batch.kind());
            }
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored payload no longer parses", e);
        }
        db.sql("UPDATE import_batch SET status = 'COMMITTED', committed_at = now() WHERE id = :id")
                .param("id", id)
                .update();
        return get(id);
    }

    /** The chosen series: an existing id, or a new series created from a typed name. */
    private long resolveSeriesId(ImportTarget target) {
        if (target.seriesId() != null) {
            return target.seriesId();
        }
        if (target.newSeriesName() != null && !target.newSeriesName().isBlank()) {
            return findOrCreateSeries(target.newSeriesName().trim());
        }
        throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "No series selected for this import");
    }

    private void commitRaceResults(RaceResultsImport imp, ImportTarget target) {
        if (imp.sessionStart() == null) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Results file has no session date; cannot determine the season");
        }
        long seriesId = resolveSeriesId(target);
        long seasonId = findOrCreateSeason(seriesId, imp.sessionStart().getYear());
        // Read the canonical class set before upserting entries, so the file's
        // own rows don't seed it (see canonicalizeClass).
        List<String> knownClasses = seasonEntryClasses(seasonId);
        long eventId = target.eventId() != null ? target.eventId()
                : createEvent(seasonId, imp.eventName(), imp.circuitName(),
                imp.circuitLengthM(), imp.circuitCountry(), imp.sessionStart().toLocalDate());
        renumberSeasonRounds(seasonId);
        Map<String, String> mapping = target.mapping();

        // Find-or-create the session by its stable (event, session_type, ordinal)
        // key — not the free-text name, so a source that renames "Race" to
        // "Race 1" updates its predecessor instead of adding a second RACE
        // session. Then replace only this session's results; a starting grid
        // imported separately hangs off the same session and must survive.
        String sessionType = normalizeSessionType(imp.sessionType(), imp.sessionName());
        long sessionId = findOrCreateRaceSession(eventId, sessionType, imp.sessionOrdinal(),
                imp.sessionName(), imp.sessionStart(), imp.reportMark(), imp.reportMessage());
        db.sql("DELETE FROM result WHERE session_id = :sessionId").param("sessionId", sessionId).update();

        for (RaceResultsImport.Row row : imp.rows()) {
            String className = canonicalizeClass(row.className(), knownClasses, mapping,
                    imp.championshipName() + " " + imp.sessionStart().getYear());
            long entryId = upsertEntry(eventId, row.number(), className, row.team(), row.vehicle(),
                    row.manufacturer(), row.group());
            replaceDriverAssignments(entryId, row.drivers());
            db.sql("""
                            INSERT INTO result (session_id, entry_id, position_overall, position_in_class, status,
                                                not_finished, not_finished_cause, laps, elapsed_time, gap_first,
                                                gap_previous, fastest_lap_time, fastest_lap_number, fastest_lap_kph,
                                                fastest_lap_driver_seat, pit_stops)
                            VALUES (:sessionId, :entryId, :posOverall, :posInClass, :status,
                                    :notFinished, :notFinishedCause, :laps, :elapsedTime, :gapFirst,
                                    :gapPrevious, :flTime, :flNumber, :flKph, :flSeat, :pitStops)
                            """)
                    .param("sessionId", sessionId)
                    .param("entryId", entryId)
                    .param("posOverall", row.positionOverall())
                    .param("posInClass", row.positionInClass())
                    .param("status", row.status())
                    .param("notFinished", row.notFinished())
                    .param("notFinishedCause", row.notFinishedCause())
                    .param("laps", row.laps())
                    .param("elapsedTime", row.elapsedTime())
                    .param("gapFirst", row.gapFirst())
                    .param("gapPrevious", row.gapPrevious())
                    .param("flTime", row.fastestLapTime())
                    .param("flNumber", row.fastestLapNumber())
                    .param("flKph", row.fastestLapKph())
                    .param("flSeat", row.fastestLapDriverSeat())
                    .param("pitStops", row.pitStops())
                    .update();
        }
    }

    private void commitGrid(GridImport imp, ImportTarget target) {
        long seasonId;
        long eventId;
        String sessionType;
        int sessionOrdinal;
        String sessionName;
        if (imp.sessionStart() != null) {
            // Timing-provider JSON: the file names its own season/event/session.
            long seriesId = resolveSeriesId(target);
            seasonId = findOrCreateSeason(seriesId, imp.sessionStart().getYear());
            eventId = target.eventId() != null ? target.eventId()
                    : createEvent(seasonId, imp.eventName(), imp.circuitName(),
                    imp.circuitLengthM(), imp.circuitCountry(), imp.sessionStart().toLocalDate());
            renumberSeasonRounds(seasonId);
            sessionType = normalizeSessionType(imp.sessionType(), imp.sessionName());
            sessionOrdinal = imp.sessionOrdinal();
            sessionName = imp.sessionName();
        } else {
            // No session metadata (grid CSVs): the reviewer chose an existing
            // event — which pins the season — and named the session. No new
            // event: the file has no date to create one with, and the entry
            // list imported first creates it in the normal workflow.
            if (target.eventId() == null) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "Grid file has no event/session metadata; choose an existing event in review");
            }
            eventId = target.eventId();
            seasonId = seasonIdOfEvent(eventId);
            sessionType = normalizeSessionType(target.sessionType(), null); // null -> RACE
            sessionOrdinal = target.sessionOrdinal() != null ? target.sessionOrdinal() : 1;
            sessionName = sessionDisplayName(sessionType, sessionOrdinal);
        }
        List<String> knownClasses = seasonEntryClasses(seasonId);
        Map<String, String> mapping = target.mapping();
        String context = imp.championshipName() != null && imp.sessionStart() != null
                ? imp.championshipName() + " " + imp.sessionStart().getYear() : "grid import";

        // The grid belongs to a race session; find-or-create it by the stable key
        // (the results file may not have been imported yet), then replace only its
        // grid rows — the session's results, if any, are untouched.
        long sessionId = findOrCreateRaceSession(eventId, sessionType, sessionOrdinal,
                sessionName, imp.sessionStart(), null, null);
        db.sql("DELETE FROM grid_position WHERE session_id = :sessionId").param("sessionId", sessionId).update();

        for (GridImport.Row row : imp.rows()) {
            String className = canonicalizeClass(row.className(), knownClasses, mapping, context);
            long entryId = upsertEntry(eventId, row.number(), className, row.team(),
                    row.vehicle(), row.manufacturer(), row.group());
            db.sql("""
                            INSERT INTO grid_position (session_id, entry_id, position_overall, position_in_class, qualifying_time)
                            VALUES (:sessionId, :entryId, :posOverall, :posInClass, :qualifyingTime)
                            """)
                    .param("sessionId", sessionId)
                    .param("entryId", entryId)
                    .param("posOverall", row.positionOverall())
                    .param("posInClass", row.positionInClass())
                    .param("qualifyingTime", row.time())
                    .update();
        }
    }

    /** Display name for a reviewer-defined session, built so its trailing number
     *  round-trips the ordinal parse: "Race", "Race 2", "Qualifying". */
    private static String sessionDisplayName(String sessionType, int ordinal) {
        String base = switch (sessionType) {
            case "QUALIFYING" -> "Qualifying";
            case "PRACTICE" -> "Practice";
            default -> "Race";
        };
        return ordinal > 1 ? base + " " + ordinal : base;
    }

    private void commitStandings(StandingsImport imp, ImportTarget target) {
        long seriesId = resolveSeriesId(target);
        long seasonId = findOrCreateSeason(seriesId, Integer.parseInt(imp.year()));

        // Class / kind / cup are confirmed by the reviewer (pre-filled from the
        // title). Standings often spell classes differently from the entry list
        // (the Endurance Cup's "GT Daytona PRO" vs "GTDPRO"); resolve to the
        // season's canonical (entry-list) class, mapping unknowns in review.
        String kind = target.kind();
        boolean isCup = Boolean.TRUE.equals(target.isCup());
        String className = canonicalizeClass(target.classCode(), seasonEntryClasses(seasonId),
                target.mapping(), imp.mainTitle());
        // The award set (family + kind) this class championship groups under. The
        // family is the reviewer's confirmed name (defaults to the series name for
        // the primary championship, the cup's own name for a cup).
        String family = target.familyName() != null && !target.familyName().isBlank()
                ? target.familyName().trim() : seriesName(seriesId);
        long groupId = findOrCreateChampionshipGroup(seasonId, family, kind, isCup);

        // Replace this championship wholesale (cascade removes sessions/rows/points).
        db.sql("DELETE FROM championship WHERE season_id = :seasonId AND name = :name")
                .param("seasonId", seasonId).param("name", imp.name()).update();
        long championshipId = db.sql("""
                        INSERT INTO championship (season_id, group_id, name, title, class_name)
                        VALUES (:seasonId, :groupId, :name, :title, :className)
                        RETURNING id
                        """)
                .param("seasonId", seasonId)
                .param("groupId", groupId)
                .param("name", imp.name())
                .param("title", imp.mainTitle())
                .param("className", className)
                .query(Long.class)
                .single();

        for (StandingsImport.SessionRef s : imp.sessions()) {
            db.sql("""
                            INSERT INTO championship_session (championship_id, session_index, event_name, session_name)
                            VALUES (:chId, :idx, :event, :session)
                            """)
                    .param("chId", championshipId)
                    .param("idx", s.sessionIndex())
                    .param("event", s.eventName())
                    .param("session", s.sessionName())
                    .update();
        }

        for (StandingsImport.Row row : imp.rows()) {
            long rowId = db.sql("""
                            INSERT INTO standings_row (championship_id, position, competitor_key, competitor_name,
                                                       total_points, net_position, total_net_points)
                            VALUES (:chId, :position, :key, :name, :points, :netPosition, :netPoints)
                            RETURNING id
                            """)
                    .param("chId", championshipId)
                    .param("position", row.position())
                    .param("key", row.key())
                    .param("name", row.team())
                    .param("points", row.totalPoints())
                    .param("netPosition", row.netPosition())
                    .param("netPoints", row.totalNetPoints())
                    .query(Long.class)
                    .single();
            for (StandingsImport.SessionPoints p : row.pointsBySession()) {
                db.sql("""
                                INSERT INTO standings_session_points (standings_row_id, session_index, total_points,
                                                                      race_points, pole_points, fastest_lap_points,
                                                                      penalty_points, status)
                                VALUES (:rowId, :idx, :total, :race, :pole, :fl, :penalty, :status)
                                """)
                        .param("rowId", rowId)
                        .param("idx", p.sessionIndex())
                        .param("total", p.totalPoints())
                        .param("race", p.racePoints())
                        .param("pole", p.polePoints())
                        .param("fl", p.fastestLapPoints())
                        .param("penalty", p.penaltyPoints())
                        .param("status", p.status())
                        .update();
            }
        }
    }

    private void commitEntryList(EntryListImport imp, ImportTarget target) {
        // Unparsed driver lines mean the parser saw a layout it didn't recognize.
        // Per the entries.json contract these must fail loud, not import silently.
        List<String> unparsed = imp.entries().stream()
                .flatMap(e -> e.drivers().stream().filter(EntryListImport.Driver::unparsed)
                        .map(d -> "#" + e.carNumber() + ": '" + d.name() + "'"))
                .toList();
        if (!unparsed.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Entry list has unparsed driver lines, fix the source or parser first: " + unparsed);
        }
        if (imp.event().startDate() == null) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Entry list has no event dates; cannot determine the season");
        }

        long seriesId = resolveSeriesId(target);
        long seasonId = findOrCreateSeason(seriesId, imp.event().startDate().getYear());

        LocalDate eventDate = imp.event().endDate() != null ? imp.event().endDate() : imp.event().startDate();
        long eventId = target.eventId() != null ? target.eventId()
                : createEvent(seasonId, imp.event().name(), imp.event().circuit(), null, null, eventDate);
        renumberSeasonRounds(seasonId);

        for (EntryListImport.Entry e : imp.entries()) {
            // Class codes are normalized by dropping spaces so entry-list spelling
            // ("GTD PRO") joins with results-file spelling ("GTDPRO").
            String className = e.classCode() != null ? e.classCode().replace(" ", "") : null;
            // A VIP / Invitational entry (blue-V icon on a driver, "Indicates
            // driver is a VIP Entry" / "Invitational Entry" in the legend) scores
            // no points — the sheet's GUEST treatment. The entry list is the
            // authority for this, so it sets is_guest on commit.
            boolean isGuest = e.drivers().stream()
                    .anyMatch(d -> d.markers() != null && d.markers().contains("invitational"));
            long entryId = db.sql("""
                            INSERT INTO entry (event_id, car_number, class_name, team_name, vehicle, manufacturer,
                                               sponsor, tire, fuel, is_guest)
                            VALUES (:eventId, :number, :className, :team, :vehicle, :manufacturer,
                                    :sponsor, :tire, :fuel, :isGuest)
                            ON CONFLICT (event_id, car_number) DO UPDATE
                                SET class_name = EXCLUDED.class_name,
                                    team_name = EXCLUDED.team_name,
                                    vehicle = EXCLUDED.vehicle,
                                    manufacturer = EXCLUDED.manufacturer,
                                    sponsor = EXCLUDED.sponsor,
                                    tire = EXCLUDED.tire,
                                    fuel = EXCLUDED.fuel,
                                    is_guest = EXCLUDED.is_guest
                            RETURNING id
                            """)
                    .param("eventId", eventId)
                    .param("number", e.carNumber())
                    .param("className", className)
                    .param("team", e.team())
                    .param("vehicle", e.carType())
                    .param("manufacturer", resolveManufacturer(className, e.carType(), e.engine()))
                    .param("sponsor", e.sponsor())
                    .param("tire", e.tire())
                    .param("fuel", e.fuel())
                    .param("isGuest", isGuest)
                    .query(Long.class)
                    .single();

            db.sql("DELETE FROM driver_assignment WHERE entry_id = :entryId").param("entryId", entryId).update();
            for (EntryListImport.Driver d : e.drivers()) {
                Long driverId = d.isTbd() ? null : findOrCreateDriverByFullName(d.name(), d.nationality());
                db.sql("""
                                INSERT INTO driver_assignment (entry_id, driver_id, seat_order, rating, rating_source, is_tbd)
                                VALUES (:entryId, :driverId, :seat, :rating, 'ENTRY_LIST', :isTbd)
                                """)
                        .param("entryId", entryId)
                        .param("driverId", driverId)
                        .param("seat", d.order())
                        .param("rating", d.rating())
                        .param("isTbd", d.isTbd())
                        .update();
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    private static boolean isPdf(byte[] content) {
        return content.length > 4 && content[0] == '%' && content[1] == 'P'
               && content[2] == 'D' && content[3] == 'F';
    }

    /** Runs the Python parser sidecar (parser/parse_entry_list.py): PDF in, entries.json out. */
    private byte[] runEntryListParser(String filename, byte[] pdf) {
        try {
            // Keep the original filename: the parser detects the series code
            // (IWSC/IMPC/...) from it.
            java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("entry-list-");
            String safeName = java.nio.file.Path.of(filename == null ? "entry-list.pdf" : filename)
                    .getFileName().toString();
            java.nio.file.Path tmp = dir.resolve(safeName);
            try {
                java.nio.file.Files.write(tmp, pdf);
                Process process = new ProcessBuilder(parserPython, parserScript, tmp.toString())
                        .redirectErrorStream(false)
                        .start();
                byte[] out = process.getInputStream().readAllBytes();
                String err = new String(process.getErrorStream().readAllBytes());
                if (!process.waitFor(120, java.util.concurrent.TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                            "Entry-list parser timed out on " + filename);
                }
                if (process.exitValue() != 0) {
                    throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                            "Entry-list parser failed on " + filename + ": " + err.trim());
                }
                return out;
            } finally {
                java.nio.file.Files.deleteIfExists(tmp);
                java.nio.file.Files.deleteIfExists(dir);
            }
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Could not run entry-list parser (" + parserPython + " " + parserScript + "): " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Entry-list parser interrupted");
        }
    }

    /**
     * Entry lists carry full names ("Tijmen van der Helm"); match against the
     * driver table by full name first so we never duplicate a driver whose
     * results-file first/surname split differs from a naive first-space split.
     */
    private Long findOrCreateDriverByFullName(String fullName, String country) {
        Optional<Long> existing = db.sql("""
                        SELECT id FROM driver WHERE lower(first_name || ' ' || surname) = lower(:name)
                        """)
                .param("name", fullName)
                .query(Long.class)
                .optional();
        if (existing.isPresent()) {
            return existing.get();
        }
        int split = fullName.indexOf(' ');
        String first = split > 0 ? fullName.substring(0, split) : fullName;
        String surname = split > 0 ? fullName.substring(split + 1) : "";
        return db.sql("""
                        INSERT INTO driver (first_name, surname, country)
                        VALUES (:first, :surname, :country)
                        ON CONFLICT (first_name, surname) DO UPDATE
                            SET country = COALESCE(EXCLUDED.country, driver.country)
                        RETURNING id
                        """)
                .param("first", first)
                .param("surname", surname)
                .param("country", country)
                .query(Long.class)
                .single();
    }

    private long findOrCreateSeries(String name) {
        Optional<Long> existing = db.sql("SELECT id FROM series WHERE lower(name) = lower(:name)")
                .param("name", name).query(Long.class).optional();
        return existing.orElseGet(() ->
                db.sql("INSERT INTO series (name, created_at) VALUES (:name, now()) RETURNING id")
                        .param("name", name).query(Long.class).single());
    }

    private record SeriesMatch(long seriesId, String matchedPrefix) {
    }

    /**
     * Matches a standings title to a series by longest prefix, considering both
     * series names and series aliases (cups within a series publish standings
     * under their own title, e.g. "IMSA Michelin Endurance Cup ...").
     */
    private SeriesMatch matchSeriesByTitle(String mainTitle) {
        List<Map<String, Object>> candidates = db.sql("""
                        SELECT id, name AS label FROM series
                        UNION ALL
                        SELECT series_id AS id, alias AS label FROM series_alias
                        """)
                .query().listOfRows();
        SeriesMatch best = null;
        for (Map<String, Object> row : candidates) {
            String label = (String) row.get("label");
            if (mainTitle.toLowerCase().startsWith(label.toLowerCase())
                    && (best == null || label.length() > best.matchedPrefix().length())) {
                best = new SeriesMatch(((Number) row.get("id")).longValue(), label);
            }
        }
        if (best == null) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "No series or series alias matches standings title '" + mainTitle
                    + "'. Create the series, or add an alias for this title prefix on the Series page.");
        }
        return best;
    }

    private long findOrCreateSeason(long seriesId, int year) {
        Optional<Long> existing = db.sql("SELECT id FROM season WHERE series_id = :seriesId AND year = :year")
                .param("seriesId", seriesId).param("year", year).query(Long.class).optional();
        return existing.orElseGet(() ->
                db.sql("INSERT INTO season (series_id, year) VALUES (:seriesId, :year) RETURNING id")
                        .param("seriesId", seriesId).param("year", year).query(Long.class).single());
    }

    /**
     * The award set a class championship belongs to (family + kind), created on
     * first use. Whether it's a cup (the Endurance Cup, historically a Sprint Cup)
     * vs the primary championship is confirmed by the reviewer, not inferred from
     * the name.
     */
    private long findOrCreateChampionshipGroup(long seasonId, String family, String kind, boolean isCup) {
        Optional<Long> existing = db.sql("""
                        SELECT id FROM championship_group
                        WHERE season_id = :seasonId AND family = :family AND kind IS NOT DISTINCT FROM :kind
                        """)
                .param("seasonId", seasonId).param("family", family).param("kind", kind)
                .query(Long.class).optional();
        if (existing.isPresent()) {
            return existing.get();
        }
        String label = family + " — " + (kind == null || kind.isBlank()
                ? "Overall"
                : kind.charAt(0) + kind.substring(1).toLowerCase());
        int ordinal = db.sql("SELECT COALESCE(max(ordinal), 0) + 1 FROM championship_group WHERE season_id = :seasonId")
                .param("seasonId", seasonId).query(Integer.class).single();
        return db.sql("""
                        INSERT INTO championship_group (season_id, family, kind, label, ordinal, is_cup)
                        VALUES (:seasonId, :family, :kind, :label, :ordinal, :isCup)
                        RETURNING id
                        """)
                .param("seasonId", seasonId).param("family", family).param("kind", kind)
                .param("label", label).param("ordinal", ordinal).param("isCup", isCup)
                .query(Long.class).single();
    }

    /**
     * (Re)assign each event in the season a 1-based round_ordinal by calendar
     * order. Idempotent: called after any event is created so the ordinal — the
     * axis for pre-round standings snapshots — stays correct as rounds arrive.
     */
    private void renumberSeasonRounds(long seasonId) {
        db.sql("""
                        WITH ranked AS (
                            SELECT id, row_number() OVER (ORDER BY event_date NULLS LAST, id) AS rn
                            FROM event WHERE season_id = :seasonId
                        )
                        UPDATE event e SET round_ordinal = ranked.rn
                        FROM ranked WHERE ranked.id = e.id
                        """)
                .param("seasonId", seasonId)
                .update();
    }

    /**
     * The tool's best guess for which existing event a session/entry-list belongs
     * to, used to pre-fill the review: sources name the same weekend differently
     * (entry list "Mid-Ohio SportsCar Weekend" vs results "O'Reilly Auto Parts 4
     * Hours of Mid-Ohio"), so match on venue + weekend (same circuit within two
     * weeks), not the free-text name. The reviewer confirms or overrides it.
     */
    private Optional<Long> findMatchingEvent(long seasonId, String circuit, LocalDate date) {
        if (circuit == null || circuit.isBlank() || date == null) {
            return Optional.empty();
        }
        return db.sql("""
                        SELECT id FROM event
                        WHERE season_id = :seasonId AND event_date IS NOT NULL
                          AND lower(regexp_replace(trim(circuit_name), '\\s+', ' ', 'g'))
                            = lower(regexp_replace(trim(:circuit), '\\s+', ' ', 'g'))
                          AND abs(event_date - :date) <= 14
                        ORDER BY abs(event_date - :date)
                        LIMIT 1
                        """)
                .param("seasonId", seasonId).param("circuit", circuit).param("date", date)
                .query(Long.class).optional();
    }

    private long createEvent(long seasonId, String name, String circuit,
                             Double lengthM, String country, LocalDate date) {
        return db.sql("""
                        INSERT INTO event (season_id, name, circuit_name, circuit_length_m, country, event_date)
                        VALUES (:seasonId, :name, :circuit, :length, :country, :date)
                        RETURNING id
                        """)
                .param("seasonId", seasonId)
                .param("name", name)
                .param("circuit", circuit)
                .param("length", lengthM)
                .param("country", country)
                .param("date", date)
                .query(Long.class)
                .single();
    }

    private String seriesName(long seriesId) {
        return db.sql("SELECT name FROM series WHERE id = :id").param("id", seriesId)
                .query(String.class).single();
    }

    /**
     * Find-or-create a session by its stable (event, session_type, ordinal) key,
     * returning its id. Results and starting grids are separate files that hang
     * off the same session and may arrive in either order, so both resolve it
     * this way rather than delete-and-recreate (which would cascade away the
     * other's rows). name/start refresh on each import; report fields only
     * overwrite when supplied (a grid doesn't carry results' report marks).
     */
    private long findOrCreateRaceSession(long eventId, String sessionType, int ordinal, String name,
                                         LocalDateTime start, String mark, String message) {
        return db.sql("""
                        INSERT INTO race_session (event_id, session_type, ordinal, name, session_start, report_mark, report_message)
                        VALUES (:eventId, :type, :ordinal, :name, :start, :mark, :message)
                        ON CONFLICT (event_id, session_type, ordinal) DO UPDATE
                            SET name = EXCLUDED.name,
                                session_start = COALESCE(EXCLUDED.session_start, race_session.session_start),
                                report_mark = COALESCE(EXCLUDED.report_mark, race_session.report_mark),
                                report_message = COALESCE(EXCLUDED.report_message, race_session.report_message)
                        RETURNING id
                        """)
                .param("eventId", eventId)
                .param("type", sessionType)
                .param("ordinal", ordinal)
                .param("name", name)
                .param("start", start)
                .param("mark", mark)
                .param("message", message)
                .query(Long.class)
                .single();
    }

    private long upsertEntry(long eventId, String number, String className, String team,
                             String vehicle, String manufacturer, String group) {
        // is_guest is deliberately untouched on update: it is user-managed state.
        // manufacturer/class_group only overwrite when the source supplies them —
        // a metadata-poor import (grid CSV) must not erase entry-list richness.
        return db.sql("""
                        INSERT INTO entry (event_id, car_number, class_name, team_name, vehicle, manufacturer, class_group)
                        VALUES (:eventId, :number, :className, :team, :vehicle, :manufacturer, :group)
                        ON CONFLICT (event_id, car_number) DO UPDATE
                            SET class_name = EXCLUDED.class_name,
                                team_name = EXCLUDED.team_name,
                                vehicle = EXCLUDED.vehicle,
                                manufacturer = COALESCE(EXCLUDED.manufacturer, entry.manufacturer),
                                class_group = COALESCE(EXCLUDED.class_group, entry.class_group)
                        RETURNING id
                        """)
                .param("eventId", eventId)
                .param("number", number)
                .param("className", className)
                .param("team", team)
                .param("vehicle", vehicle)
                .param("manufacturer", resolveManufacturer(className, vehicle, manufacturer))
                .param("group", group)
                .query(Long.class)
                .single();
    }

    /**
     * LMP2 cars are identified by chassis, not engine: every LMP2 is
     * Gibson-powered, so the meaningful marque is the constructor (ORECA),
     * which is the first word of the car type. Other classes keep the source
     * file's manufacturer (where "Corvette" would wrongly shadow "Chevrolet").
     */
    private static String resolveManufacturer(String className, String vehicle, String fallback) {
        if (className != null && className.replace(" ", "").equalsIgnoreCase("LMP2")
            && vehicle != null && !vehicle.isBlank()) {
            return vehicle.trim().split("\\s+")[0];
        }
        return fallback;
    }

    private void replaceDriverAssignments(long entryId, List<RaceResultsImport.DriverRow> drivers) {
        // Ratings imported from an entry list are authoritative (derogations);
        // remember them before replacing the lineup with the results file's.
        Map<Long, String> entryListRatings = new java.util.HashMap<>();
        db.sql("""
                        SELECT driver_id, rating FROM driver_assignment
                        WHERE entry_id = :entryId AND rating_source = 'ENTRY_LIST'
                          AND driver_id IS NOT NULL AND rating IS NOT NULL
                        """)
                .param("entryId", entryId)
                .query((rs, i) -> entryListRatings.put(rs.getLong("driver_id"), rs.getString("rating")))
                .list();

        db.sql("DELETE FROM driver_assignment WHERE entry_id = :entryId").param("entryId", entryId).update();
        for (RaceResultsImport.DriverRow d : drivers) {
            long driverId = db.sql("""
                            INSERT INTO driver (first_name, surname, country, hometown)
                            VALUES (:first, :surname, :country, :hometown)
                            ON CONFLICT (first_name, surname) DO UPDATE
                                SET country = COALESCE(EXCLUDED.country, driver.country),
                                    hometown = COALESCE(EXCLUDED.hometown, driver.hometown)
                            RETURNING id
                            """)
                    .param("first", d.firstName())
                    .param("surname", d.surname())
                    .param("country", d.country())
                    .param("hometown", d.hometown())
                    .query(Long.class)
                    .single();
            String entryListRating = entryListRatings.get(driverId);
            db.sql("""
                            INSERT INTO driver_assignment (entry_id, driver_id, seat_order, rating, rating_source)
                            VALUES (:entryId, :driverId, :seat, :rating, :source)
                            """)
                    .param("entryId", entryId)
                    .param("driverId", driverId)
                    .param("seat", d.seatOrder())
                    .param("rating", entryListRating != null ? entryListRating : ratingLetter(d.rating()))
                    .param("source", entryListRating != null ? "ENTRY_LIST" : "RESULTS")
                    .update();
        }
    }

    /** Results files spell ratings out ("Platinum"); store the single letter everywhere. */
    private static String ratingLetter(String rating) {
        return rating == null || rating.isBlank() ? null : rating.substring(0, 1).toUpperCase();
    }

    private static String normalizeSessionType(String sessionType, String sessionName) {
        String source = sessionType != null ? sessionType : sessionName != null ? sessionName : "";
        String lower = source.toLowerCase();
        if (lower.contains("qual")) {
            return "QUALIFYING";
        }
        if (lower.contains("practice") || lower.contains("warm")) {
            return "PRACTICE";
        }
        return "RACE";
    }

    // ---------------------------------------------------------- class canon

    private record ClassAndKind(String className, String kind) {
    }

    /**
     * Splits the title remainder after the matched series prefix into class and
     * kind, e.g. "GTP Teams" -> ("GTP", "TEAMS") and "GT Daytona PRO Teams" ->
     * ("GT Daytona PRO", "TEAMS"). An overall championship with no class yields a
     * null className.
     */
    private static ClassAndKind deriveClassAndKind(String mainTitle, String matchedPrefix) {
        String remainder = mainTitle.substring(matchedPrefix.length()).trim();
        int lastSpace = remainder.lastIndexOf(' ');
        if (lastSpace > 0) {
            return new ClassAndKind(remainder.substring(0, lastSpace).trim(),
                    remainder.substring(lastSpace + 1).toUpperCase());
        }
        return new ClassAndKind(null, remainder.isEmpty() ? null : remainder.toUpperCase());
    }

    /** Normalize a class spelling for comparison: case- and space-insensitive. */
    private static String normClass(String s) {
        return s == null ? null : s.toLowerCase().replace(" ", "");
    }

    /** The season's canonical classes: the distinct entry (entry-list) classes. */
    private List<String> seasonEntryClasses(long seasonId) {
        return db.sql("""
                        SELECT DISTINCT e.class_name
                        FROM entry e
                                 JOIN event ev ON ev.id = e.event_id
                        WHERE ev.season_id = :seasonId AND e.class_name IS NOT NULL
                        ORDER BY e.class_name
                        """)
                .param("seasonId", seasonId)
                .query(String.class)
                .list();
    }

    private static boolean isUnknownClass(String className, List<String> known) {
        if (className == null || known.isEmpty()) {
            return false;
        }
        String n = normClass(className);
        return known.stream().noneMatch(k -> normClass(k).equals(n));
    }

    /**
     * Resolve a source class spelling to the season's canonical (entry-list)
     * class. A caller-supplied mapping wins (the reviewer's choice). Otherwise a
     * spelling that matches a known class ignoring case/spaces is auto-resolved to
     * that class. With no canonical set yet (bootstrap: no entry list imported),
     * the raw spelling establishes canon. Anything else is unrecognized and fails
     * the commit so it gets mapped in the review screen first.
     */
    private String canonicalizeClass(String raw, List<String> known, Map<String, String> mapping, String context) {
        if (raw == null) {
            return null;
        }
        if (mapping != null && mapping.containsKey(raw)) {
            return mapping.get(raw);
        }
        if (known.isEmpty()) {
            return raw;
        }
        String n = normClass(raw);
        for (String k : known) {
            if (normClass(k).equals(n)) {
                return k;
            }
        }
        throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                "Unrecognized class '" + raw + "' for " + context
                + ". Map it to a known class in the review screen before committing. Known classes: " + known);
    }

    /**
     * A session file names its series in free text, and one weekend's files can
     * disagree ("Ford Mustang Challenge" on the live grid, "Mustang Challenge" on
     * the results regenerated days later). Aliases cover the drift, the same way
     * they do for standings titles.
     */
    private Optional<Long> findSeriesByName(String name) {
        return db.sql("""
                        SELECT id FROM series WHERE lower(name) = lower(:name)
                        UNION ALL
                        SELECT series_id FROM series_alias WHERE lower(alias) = lower(:name)
                        LIMIT 1
                        """)
                .param("name", name).query(Long.class).optional();
    }

    private Optional<Long> findSeasonId(long seriesId, int year) {
        return db.sql("SELECT id FROM season WHERE series_id = :seriesId AND year = :year")
                .param("seriesId", seriesId).param("year", year).query(Long.class).optional();
    }

    private String toJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
