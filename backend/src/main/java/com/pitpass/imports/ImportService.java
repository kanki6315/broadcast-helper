package com.pitpass.imports;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Service
public class ImportService {

    public record BatchSummary(long id, String kind, String format, String filename, String status,
                               String summary, OffsetDateTime createdAt) {
    }

    private final JdbcClient db;
    private final ObjectMapper json;
    private final IRacingClient iracing;
    private final com.pitpass.formats.RaceFormatService raceFormats;
    private final com.pitpass.teams.TeamAssignmentService teamAssignments;
    private final com.pitpass.teams.TeamResolver teamResolver;
    private final TransactionTemplate txTemplate;
    private final String parserPython;
    private final String parserScript;
    private final String pointsParserScript;
    private final String gridPdfParserScript;

    public ImportService(JdbcClient db, ObjectMapper json, IRacingClient iracing,
                         com.pitpass.formats.RaceFormatService raceFormats,
                         com.pitpass.teams.TeamAssignmentService teamAssignments,
                         com.pitpass.teams.TeamResolver teamResolver,
                         PlatformTransactionManager txManager,
                         @org.springframework.beans.factory.annotation.Value("${pit-pass.entry-list-parser.python:python3}") String parserPython,
                         @org.springframework.beans.factory.annotation.Value("${pit-pass.entry-list-parser.script:../parser/parse_entry_list.py}") String parserScript,
                         @org.springframework.beans.factory.annotation.Value("${pit-pass.points-parser.script:../parser/parse_points.py}") String pointsParserScript,
                         @org.springframework.beans.factory.annotation.Value("${pit-pass.grid-pdf-parser.script:../parser/parse_grid_pdf.py}") String gridPdfParserScript) {
        this.db = db;
        this.json = json;
        this.iracing = iracing;
        this.raceFormats = raceFormats;
        this.teamAssignments = teamAssignments;
        this.teamResolver = teamResolver;
        this.txTemplate = new TransactionTemplate(txManager);
        this.parserPython = parserPython;
        this.parserScript = parserScript;
        this.pointsParserScript = pointsParserScript;
        this.gridPdfParserScript = gridPdfParserScript;
    }

    // ---------------------------------------------------------------- staging

    /** One family parser's output, ready for the shared import_batch insert. */
    private record Staged(String kind, Object payload, String summary) {
    }

    /**
     * Stages an upload as one or more batches. Most files carry a single
     * document, but one championship-points PDF holds every championship of the
     * series — each becomes its own batch, reviewed and committed separately.
     */
    public List<BatchSummary> stage(String filename, byte[] content, ImportFormat format) {
        ImportFormat resolved = format == ImportFormat.AUTO ? resolveAuto(content) : format;
        List<Staged> staged = switch (resolved) {
            case AUTO -> throw new IllegalStateException("AUTO must be resolved before staging");
            case IMSA_JSON -> List.of(stageImsaJson(content));
            case IMSA_PDF -> List.of(stageImsaPdf(filename, content));
            case IMSA_POINTS_PDF -> stageImsaPointsPdf(filename, content);
            case IMSA_GRID_PDF -> List.of(stageImsaGridPdf(filename, content));
            case IMSA_CSV -> List.of(stageImsaCsv(filename, content));
            case IRACING_JSON -> stageIRacingJson(content);
        };
        return persist(staged, resolved, filename);
    }

    /**
     * Stages a subsession fetched from the Data API instead of uploaded. The
     * fetched payload is the exported file's result object minus its envelope, so
     * once IRacingParser has unwrapped it this shares the upload's parser and
     * review flow entirely — only where the bytes came from differs.
     */
    public List<BatchSummary> stageFromIRacing(long subsessionId) {
        JsonNode payload = iracing.fetchResult(subsessionId);
        return persist(stageIRacingResult(payload), ImportFormat.IRACING_JSON,
                "subsession-" + subsessionId + ".json");
    }

    /**
     * The rounds of a league season, so a caller can point at a season and import
     * a round by its subsession id (via {@link #stageFromIRacing}) without knowing
     * ids up front. Read-only — this stages nothing.
     */
    public List<IRacingParser.LeagueRound> listSeasonRounds(long leagueId, long seasonId) {
        JsonNode payload = iracing.get("/league/season_sessions", java.util.Map.of(
                "league_id", String.valueOf(leagueId),
                "season_id", String.valueOf(seasonId)));
        return IRacingParser.parseSeasonRounds(payload);
    }

    /**
     * Stages a league season's driver standings — the championship table a single
     * result file can't produce. The reviewer confirms class / kind / season year
     * on commit, the same as a points-PDF standings import.
     *
     * The /league/season_standings endpoint gives season totals only, so this also
     * walks the season's completed rounds and reads the per-round league points
     * iRacing already scored on each round's result. That reconstructs the per-round
     * breakdown the recap needs — its columns and its points-per-round grid. It
     * costs one result fetch per round — the same order as importing the season's
     * results — so it is a deliberate action.
     *
     * Rounds are enumerated oldest-first and numbered from 1, because the recap
     * matches championship round N to the season event with round_ordinal N (see
     * SeasonViewController.recap — the match is by ordinal, NOT by venue, since a
     * venue abbreviation is not unique within a season). The round's venue name
     * supplies the recap's column *label* only. So the season's events must carry
     * the same chronological round ordinals this calendar does — which holds when
     * they come from the round-results import, whose commit renumbers by date.
     */
    public List<BatchSummary> stageStandingsFromIRacing(long leagueId, long seasonId) {
        JsonNode standings = iracing.get("/league/season_standings", java.util.Map.of(
                "league_id", String.valueOf(leagueId),
                "season_id", String.valueOf(seasonId)));
        String seasonName = seasonName(leagueId, seasonId);
        String year = leadingYear(seasonName);

        // Walk the completed rounds oldest-first, pulling each round's per-driver
        // league points. A round we can't fetch just leaves a gap in the calendar
        // rather than sinking the whole standings import.
        List<IRacingParser.LeagueRound> rounds = listSeasonRounds(leagueId, seasonId).stream()
                .filter(IRacingParser.LeagueRound::hasResults)
                .toList();
        List<StandingsImport.SessionRef> sessions = new ArrayList<>();
        Map<Long, Map<Integer, Double>> pointsByCustSession = new java.util.HashMap<>();
        int sessionIndex = 0;
        for (IRacingParser.LeagueRound round : rounds) {
            JsonNode result;
            try {
                result = iracing.fetchResult(round.subsessionId());
            } catch (RuntimeException e) {
                continue;
            }
            // Each scoring sim-session of the round is its own championship
            // session, all sharing the round's venue as their event name — the
            // recap groups by that name and sums, so they read as one round
            // column while keeping qualifying and each race separable.
            String venue = IRacingParser.roundVenueName(result);
            for (IRacingParser.RoundSession scored : IRacingParser.parseRoundLeagueSessions(result)) {
                int idx = ++sessionIndex;
                sessions.add(new StandingsImport.SessionRef(idx, venue, scored.sessionName()));
                scored.pointsByCust().forEach((custId, pts) ->
                        pointsByCustSession.computeIfAbsent(custId, k -> new java.util.HashMap<>())
                                .put(idx, pts));
            }
        }

        StandingsImport imp = IRacingParser.assembleSeasonStandings(
                standings, seasonName, year, sessions, pointsByCustSession);
        if (imp.rows().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "No driver standings found for league " + leagueId + " season " + seasonId);
        }
        Staged staged = new Staged("STANDINGS", imp,
                "%s — %d competitors, %d rounds".formatted(imp.name(), imp.rows().size(), roundCount(imp)));
        return persist(List.of(staged), ImportFormat.IRACING_JSON,
                "league-" + leagueId + "-season-" + seasonId + "-standings.json");
    }

    /**
     * The OFFICIAL race rounds of an official-series season — the same read-only
     * listing role as {@link #listSeasonRounds}, driven by /results/season_results
     * (event_type 5 = race; race_week_num omitted so every week arrives).
     *
     * Only official sessions are listed, anywhere in the official flow: a voided
     * race stays in the payload flagged unofficial with all-zero points (PESC
     * 2020's first Le Mans, re-run months later), and surfacing it would offer a
     * phantom round. Week numbers are not round numbers — the calendar has
     * raceless and voided weeks — so rounds are numbered by start time.
     */
    public List<IRacingParser.LeagueRound> listOfficialSeasonRounds(long seasonId) {
        JsonNode payload = iracing.get("/results/season_results", java.util.Map.of(
                "season_id", String.valueOf(seasonId),
                "event_type", "5"));
        return IRacingParser.parseOfficialSeasonRounds(payload);
    }

    /**
     * Stages an official-series season's driver standings, the official twin of
     * {@link #stageStandingsFromIRacing}: totals from the season standings
     * endpoint stay authoritative, and the per-round breakdown the recap needs
     * is read from each official round's result — champ points iRacing already
     * scored, qualifying included (verified on PESC 2020: the per-round sums
     * reconcile to the published totals exactly). One batch per car class;
     * PESC-style single-class seasons stage exactly one.
     */
    public List<BatchSummary> stageOfficialStandingsFromIRacing(long seriesId, long seasonId) {
        List<StandingsImport> imports = buildOfficialStandings(seriesId, seasonId);
        List<Staged> staged = imports.stream()
                .map(imp -> new Staged("STANDINGS", imp,
                        "%s — %d competitors, %d rounds".formatted(
                                imp.name(), imp.rows().size(), roundCount(imp))))
                .toList();
        return persist(staged, ImportFormat.IRACING_JSON,
                "series-" + seriesId + "-season-" + seasonId + "-standings.json");
    }

    /** Rounds, not scoring sessions: a round contributes a qualifying session
     *  and a session per race, all sharing its venue as their event name. */
    private static long roundCount(StandingsImport imp) {
        return imp.sessions().stream().map(StandingsImport.SessionRef::eventName).distinct().count();
    }

    /**
     * Everything up to persistence — package-private so the flow is testable
     * against a stubbed {@link IRacingClient} without a database.
     */
    List<StandingsImport> buildOfficialStandings(long seriesId, long seasonId) {
        JsonNode past = iracing.get("/series/past_seasons", java.util.Map.of(
                "series_id", String.valueOf(seriesId)));
        IRacingParser.OfficialSeason season = IRacingParser.parseOfficialSeason(past, seasonId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Series " + seriesId + " has no season " + seasonId));

        // Walk the official rounds oldest-first, reading the champ points each
        // round's result already carries. Same resilience as the league flow: a
        // round that can't be fetched leaves a calendar gap rather than sinking
        // the import.
        List<StandingsImport.SessionRef> sessions = new ArrayList<>();
        Map<Long, Map<Integer, Double>> pointsByCustSession = new java.util.HashMap<>();
        int sessionIndex = 0;
        for (IRacingParser.LeagueRound round : listOfficialSeasonRounds(seasonId)) {
            JsonNode result;
            try {
                result = iracing.fetchResult(round.subsessionId());
            } catch (RuntimeException e) {
                continue;
            }
            // One championship session per scoring sim-session (qualifying, each
            // heat, the feature), sharing the round's venue so the recap sums
            // them back into a single round column. See the league walk above.
            String venue = IRacingParser.roundVenueName(result);
            for (IRacingParser.RoundSession scored : IRacingParser.parseRoundChampSessions(result)) {
                int idx = ++sessionIndex;
                sessions.add(new StandingsImport.SessionRef(idx, venue, scored.sessionName()));
                scored.pointsByCust().forEach((custId, pts) ->
                        pointsByCustSession.computeIfAbsent(custId, k -> new java.util.HashMap<>())
                                .put(idx, pts));
            }
        }

        // One standings table per car class. The per-round points map is shared:
        // it is keyed by cust_id season-wide, and each class's rows select their
        // own drivers out of it.
        List<StandingsImport> imports = new ArrayList<>();
        boolean multiClass = season.carClasses().size() > 1;
        for (IRacingParser.OfficialSeason.CarClass carClass : season.carClasses()) {
            JsonNode standings = iracing.get("/stats/season_driver_standings", java.util.Map.of(
                    "season_id", String.valueOf(seasonId),
                    "car_class_id", String.valueOf(carClass.carClassId())));
            JsonNode rows = iracing.fetchChunkedRows(standings.path("chunk_info"));
            // The class name joins the batch name only when it must — it is the
            // championship's replace key within a season, so a single-class
            // season keeps the season name alone.
            String name = multiClass
                    ? season.seasonName() + " — " + carClass.name()
                    : season.seasonName();
            StandingsImport imp = IRacingParser.assembleOfficialStandings(
                    rows, name, String.valueOf(season.seasonYear()), sessions, pointsByCustSession);
            if (!imp.rows().isEmpty()) {
                imports.add(imp);
            }
        }
        if (imports.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "No driver standings found for series " + seriesId + " season " + seasonId);
        }
        return imports;
    }

    /**
     * Outcome of staging several subsessions at once (a whole season, or a
     * hand-picked list). Resilient by design, so it reports both sides: how many
     * were requested, how many staged, every batch produced, and each subsession
     * that failed.
     */
    public record IRacingImport(int requested, int staged,
                                List<BatchSummary> batches, List<Failure> failures) {
    }

    public record Failure(long subsessionId, String track, String reason) {
    }

    /**
     * Stages every round of a league season that has results, in schedule order.
     * Staging only, and a season is a lot of batches to review, so this is for
     * when importing a whole season at once is genuinely wanted.
     */
    public IRacingImport stageSeasonFromIRacing(long leagueId, long seasonId) {
        List<IRacingParser.LeagueRound> rounds = listSeasonRounds(leagueId, seasonId).stream()
                .filter(IRacingParser.LeagueRound::hasResults)
                .toList();
        List<BatchSummary> batches = new ArrayList<>();
        List<Failure> failures = new ArrayList<>();
        int staged = 0;
        for (IRacingParser.LeagueRound round : rounds) {
            if (tryStage(round.subsessionId(), round.trackName(), batches, failures)) {
                staged++;
            }
        }
        return new IRacingImport(rounds.size(), staged, batches, failures);
    }

    /**
     * Stages a hand-picked list of subsessions — the "these five races are my
     * season" case, where the subsessions aren't a league-season enumeration.
     * Same resilience as a bulk season import: one bad id doesn't sink the rest.
     */
    public IRacingImport stageSubsessionsFromIRacing(List<Long> subsessionIds) {
        List<BatchSummary> batches = new ArrayList<>();
        List<Failure> failures = new ArrayList<>();
        int staged = 0;
        for (Long id : subsessionIds) {
            if (tryStage(id, null, batches, failures)) {
                staged++;
            }
        }
        return new IRacingImport(subsessionIds.size(), staged, batches, failures);
    }

    /** Stages one subsession, folding a failure into the report rather than
     *  throwing — so a batch of imports survives a single bad one. */
    private boolean tryStage(long subsessionId, String trackHint,
                             List<BatchSummary> batches, List<Failure> failures) {
        try {
            batches.addAll(stageFromIRacing(subsessionId));
            return true;
        } catch (RuntimeException e) {
            String reason = e instanceof ResponseStatusException rse ? rse.getReason() : e.getMessage();
            failures.add(new Failure(subsessionId, trackHint, reason));
            return false;
        }
    }

    /** The season's display name from /league/seasons, for titling the standings.
     *  Falls back to a generic label if the season isn't listed. */
    private String seasonName(long leagueId, long seasonId) {
        JsonNode seasons = iracing.get("/league/seasons", java.util.Map.of(
                "league_id", String.valueOf(leagueId)));
        for (JsonNode s : seasons.path("seasons")) {
            if (s.path("season_id").asLong() == seasonId) {
                String name = s.path("season_name").asText("");
                if (!name.isBlank()) {
                    return name.trim();
                }
            }
        }
        return "League " + leagueId + " season " + seasonId;
    }

    /** A leading four-digit year ("2025 Porsche…" -> "2025"), else null — the
     *  reviewer confirms the season year regardless. */
    private static String leadingYear(String name) {
        if (name == null) {
            return null;
        }
        var m = java.util.regex.Pattern.compile("^\\s*(\\d{4})\\b").matcher(name);
        return m.find() ? m.group(1) : null;
    }

    private List<BatchSummary> persist(List<Staged> staged, ImportFormat format, String filename) {
        List<BatchSummary> out = new ArrayList<>();
        for (Staged s : staged) {
            long id = db.sql("""
                            INSERT INTO import_batch (kind, format, filename, payload, summary)
                            VALUES (:kind, :format, :filename, :payload::jsonb, :summary)
                            RETURNING id
                            """)
                    .param("kind", s.kind())
                    .param("format", format.name())
                    .param("filename", filename)
                    .param("payload", toJson(s.payload()))
                    .param("summary", s.summary())
                    .query(Long.class)
                    .single();
            out.add(get(id));
        }
        return out;
    }

    /**
     * AUTO covers what the tool historically accepted: IMSA entry-list PDFs and
     * timing-provider JSON. CSVs are never auto-detected — their shapes are
     * provider-specific and would collide across families — but get a targeted
     * hint instead of the generic JSON error.
     *
     * The JSON families do separate cleanly: an iRacing subsession names itself
     * in its envelope, so unlike the PDF families this one needs no user hint.
     */
    private ImportFormat resolveAuto(byte[] content) {
        if (isPdf(content)) {
            return ImportFormat.IMSA_PDF;
        }
        if (ImportParser.looksLikeGridCsv(content) || ImportParser.looksLikeResultsCsv(content)
                || ImportParser.looksLikeQualifyingCsv(content)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "This looks like a semicolon-delimited CSV — choose a format explicitly"
                    + " (e.g. IMSA — CSV) instead of Auto-detect");
        }
        if (looksLikeIRacingJson(content)) {
            return ImportFormat.IRACING_JSON;
        }
        return ImportFormat.IMSA_JSON;
    }

    /** Sniffs the iRacing envelope. Unparseable JSON falls through to the IMSA
     *  family, whose staging reports the parse error properly. */
    private boolean looksLikeIRacingJson(byte[] content) {
        try {
            return IRacingParser.looksLikeEventResult(json.readTree(content));
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * One iRacing subsession is a whole meeting, so it fans out: a RACE_RESULTS
     * batch per scoring session (qualifying, then each race) and a GRID batch per
     * race. Practice and warmup are dropped in the parser. Each batch is reviewed
     * and committed on its own, the same way a points PDF splits per championship.
     */
    private List<Staged> stageIRacingJson(byte[] content) {
        JsonNode root;
        try {
            root = json.readTree(content);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Not valid JSON: " + e.getMessage());
        }
        return stageIRacingResult(root);
    }

    /** Stages a parsed subsession payload, whether uploaded (wrapped in an
     *  "event_result" envelope) or fetched from the Data API (the same object
     *  unwrapped). IRacingParser accepts both shapes. */
    List<Staged> stageIRacingResult(JsonNode root) {
        if (!IRacingParser.looksLikeEventResult(root)) {
            // Name the actual top-level fields — if the payload shape shifts again,
            // this says how, instead of leaving the next reader to guess.
            List<String> keys = new ArrayList<>();
            root.fieldNames().forEachRemaining(keys::add);
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Not an iRacing subsession result: expected session_results (in an "
                    + "\"event_result\" envelope or at the top level), but the payload's top-level "
                    + "fields were " + keys);
        }
        List<Staged> out = new ArrayList<>();
        for (RaceResultsImport session : IRacingParser.parseSessions(root)) {
            out.add(new Staged("RACE_RESULTS", session, "%s — %s, %d classified entries".formatted(
                    session.eventName(), session.sessionName(), session.rows().size())));
        }
        for (GridImport grid : IRacingParser.parseGrids(root)) {
            out.add(new Staged("GRID", grid, "%s — %s starting grid, %d cars".formatted(
                    grid.eventName(), grid.sessionName(), grid.rows().size())));
        }
        if (out.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "No qualifying or race sessions found in this subsession");
        }
        return out;
    }

    private Staged stageImsaPdf(String filename, byte[] pdf) {
        if (!isPdf(pdf)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Not a PDF file (expected an IMSA entry-list PDF)");
        }
        Staged staged = stageImsaJson(runEntryListParser(filename, pdf));
        // An entry list with nothing in it is not a quiet success. The likeliest
        // cause is another kind of IMSA PDF fed to this parser — AUTO can't tell
        // the families apart without opening the file — so say so rather than
        // staging an empty batch that looks importable.
        if (staged.payload() instanceof EntryListImport entryList && entryList.entries().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "No entries found in " + filename + ". If this is a championship-points PDF,"
                    + " choose the \"IMSA — Championship points PDF\" format instead of Auto-detect.");
        }
        return staged;
    }

    /**
     * One championship-points PDF holds every championship of the series (11 for
     * a WeatherTech sheet), so it fans out into one STANDINGS batch each. The
     * sidecar has already checked that every row re-adds to its printed total;
     * if it hadn't, it would have failed rather than returning.
     */
    private List<Staged> stageImsaPointsPdf(String filename, byte[] pdf) {
        if (!isPdf(pdf)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Not a PDF file (expected an IMSA championship-points PDF)");
        }
        JsonNode root;
        try {
            root = json.readTree(runPointsParser(filename, pdf));
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Points parser returned invalid JSON: " + e.getMessage());
        }
        List<Staged> out = new ArrayList<>();
        for (JsonNode champ : root.path("championships")) {
            StandingsImport parsed = ImportParser.parseStandings(champ);
            out.add(new Staged("STANDINGS", parsed, "%s — %d competitors, %d sessions".formatted(
                    parsed.mainTitle(), parsed.rows().size(), parsed.sessions().size())));
        }
        if (out.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "No championship standings found in " + filename);
        }
        return out;
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
        } else if (ImportParser.looksLikeFlags(root)) {
            FlagsImport parsed = ImportParser.parseFlags(root);
            return new Staged("FLAGS", parsed, "%s — %s, %d flag records".formatted(
                    parsed.eventName(), parsed.sessionName(), parsed.rows().size()));
        }
        throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                "Unrecognized file format: expected a results file (session + classification), "
                + "a starting grid (session + grid), a flags report (session + flags), "
                + "or a standings file (championship + classification)");
    }

    /** Stages a starting-grid PDF via the Python sidecar. Like the grid CSV,
     *  the sheet names no event or date, so the batch goes through the
     *  reviewer-supplies-the-target flow; the parsed race number rides along
     *  as the session ordinal to pre-fill the reviewer's picker. */
    private Staged stageImsaGridPdf(String filename, byte[] pdf) {
        if (!isPdf(pdf)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Not a PDF file (expected an IMSA starting-grid PDF)");
        }
        JsonNode root;
        try {
            root = json.readTree(runGridPdfParser(filename, pdf));
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Grid parser returned invalid JSON: " + e.getMessage());
        }
        GridImport parsed = mapGridPdfJson(root);
        String summary = "%s%s starting grid PDF — %d cars".formatted(
                root.path("session").asText("Race"),
                root.path("revised").asBoolean(false) ? " (Revised)" : "",
                parsed.rows().size());
        return new Staged("GRID", parsed, summary);
    }

    /**
     * Maps the grid-PDF sidecar's JSON onto GridImport. Session metadata stays
     * null (there is none in the sheet, so the reviewer flow fires) except the
     * ordinal from the title's race number. The sheet names one driver per
     * slot with no roster to resolve a seat against, so attribution stays
     * null — readers fall back to the entry's sole crew member, which is
     * always right in the single-driver series that publish these; the
     * results import supplies the roster itself.
     */
    static GridImport mapGridPdfJson(JsonNode root) {
        List<GridImport.Row> rows = new ArrayList<>();
        Map<String, Integer> classCounters = new HashMap<>();
        for (JsonNode r : root.path("rows")) {
            String number = r.path("number").asText(null);
            if (number == null || number.isBlank()) {
                continue;
            }
            String className = r.path("class").asText(null);
            Integer inClass = classCounters.merge(className, 1, Integer::sum);
            rows.add(new GridImport.Row(
                    r.path("position").asInt(),
                    inClass,
                    number,
                    className,
                    null,
                    r.path("team").asText(null),
                    r.path("car").asText(null),
                    null,
                    r.path("time").asText(null),
                    null,
                    null,
                    List.of()
            ));
        }
        return new GridImport(null, null, null, null,
                root.path("race").asInt(1), null, null, null, null, rows);
    }

    /** The IMSA CSV family, told apart by header: the starting grid
     *  (POSITION;CLASS;NUMBER;...), race results (POSITION;NUMBER;STATUS;...),
     *  and qualifying results (POS;NUMBER;LAP;TIME;...). None carry event or
     *  session metadata; the reviewer supplies both at commit. */
    private Staged stageImsaCsv(String filename, byte[] content) {
        try {
            if (ImportParser.looksLikeGridCsv(content)) {
                GridImport parsed = ImportParser.parseGridCsv(content);
                return new Staged("GRID", parsed,
                        "Starting grid CSV — %d cars".formatted(parsed.rows().size()));
            }
            if (ImportParser.looksLikeResultsCsv(content)) {
                RaceResultsImport parsed = ImportParser.parseResultsCsv(content);
                return new Staged("RACE_RESULTS", parsed,
                        "Race results CSV — %d entries".formatted(parsed.rows().size()));
            }
            if (ImportParser.looksLikeQualifyingCsv(content)) {
                RaceResultsImport parsed = ImportParser.parseQualifyingCsv(content);
                String summary = "Qualifying results CSV — %d entries".formatted(parsed.rows().size());
                // The "Results by 2nd Fastest Lap" sheet shares this header. It is
                // a secondary classification that only exists to set the next
                // race's grid — which is imported as its own grid file — so it is
                // normally skipped. Warn rather than reject: the filename is the
                // only tell, and filenames lie.
                if (filename != null && filename.toLowerCase().contains("2nd fastest")) {
                    summary += " — looks like a 2nd-fastest-lap sheet; the race 2 grid"
                            + " already carries these times, usually skip it";
                }
                return new Staged("RACE_RESULTS", parsed, summary);
            }
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
        }
        throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                "Unrecognized IMSA CSV: expected a starting-grid header (POSITION;CLASS;NUMBER;...),"
                + " a race-results header (POSITION;NUMBER;STATUS;...),"
                + " or a qualifying header (POS;NUMBER;LAP;TIME;...)");
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
            return reviewStandings(imp, Integer.parseInt(imp.year()));
        } catch (NumberFormatException e) {
            return new ClassReview(List.of(), List.of());
        }
    }

    /** Class review for a standings batch against a given season. The year is a
     *  parameter because a points PDF only guesses its own (see resolveSeasonYear):
     *  when the reviewer corrects it, the classes must be re-checked against the
     *  season they'll actually land in, or a mapping they need never gets offered. */
    private ClassReview reviewStandings(StandingsImport imp, int year) {
        try {
            SeriesMatch match = matchSeriesByTitle(imp.mainTitle());
            Optional<Long> seasonId = findSeasonId(match.seriesId(), year);
            if (seasonId.isEmpty()) {
                return new ClassReview(List.of(), List.of());
            }
            List<String> known = seasonEntryClasses(seasonId.get());
            String className = deriveClassAndKind(imp.mainTitle(), match.matchedPrefix()).className();
            List<String> unknown = isUnknownClass(className, known, classAliasesForSeason(seasonId.get()))
                    ? List.of(className) : List.of();
            return new ClassReview(known, unknown);
        } catch (ResponseStatusException e) {
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

    /** The tool's guess for the import target; every field is editable in review. */
    public record TargetGuess(Long seriesId, String seriesName, Integer seasonYear,
                              Long eventId, String eventName, String circuit, String eventDate,
                              String classCode, String kind, Boolean isCup, String familyName) {
        TargetGuess withSeasonYear(Integer year) {
            return new TargetGuess(seriesId, seriesName, year, eventId, eventName, circuit, eventDate,
                    classCode, kind, isCup, familyName);
        }
    }

    /** One car in a file-vs-event roster comparison. {@code number} is the raw
     *  car_number — the entry table's uniqueness key, where "04" and "4" are
     *  different cars — so the diff reports exactly what a commit would do. */
    public record CarRef(String number, String className, String teamName) {
    }

    /** How this file's cars compare to the target event's committed roster.
     *  newCars would be created by the commit (the wrong-file fingerprint: a
     *  correct file rarely names a car the event never entered); missingCars
     *  are entered but absent from the file (normal for grids — withdrawals
     *  and DNQs). Null when there is no roster to compare against. */
    public record RosterDiff(List<CarRef> newCars, List<CarRef> missingCars, int eventEntryCount) {
    }

    public record ImportReview(String kind, TargetGuess guess,
                               ClassReview classReview,
                               boolean needsSession,
                               // Pre-fills for the reviewer's session picker when needsSession:
                               // a results CSV knows race from qualifying by its header, and a
                               // grid PDF names which race it starts. Null when the payload
                               // carries no such hint.
                               String sessionTypeHint,
                               Integer sessionOrdinalHint,
                               // GRID only: no slot has a time — the fingerprint of a grid set
                               // by something other than qualifying. The UI uses it to suggest
                               // filling in the grid basis.
                               boolean gridTimesAllBlank,
                               // File cars vs the target event's entries, for kinds that write
                               // entries (grid/results/entry list). Null when no event is
                               // chosen or guessed, or the event has no entries yet.
                               RosterDiff rosterDiff) {
    }

    /** Everything the review screen needs: the guessed target and the
     *  class-mapping review — so nothing is inferred at commit. (The series and
     *  event pick-lists come from /api/series and /api/events; the review carries
     *  only what is specific to this batch.)
     *  chosenEventId / chosenYear (optional) recompute the class review against the
     *  season the reviewer actually picked, for payloads that can't resolve one by
     *  themselves (grid CSVs) or only guess it (points PDFs). */
    public ImportReview reviewTarget(long id, Long chosenEventId, Integer chosenYear) {
        BatchSummary batch = get(id);
        ClassReview cr = classReview(id);
        String payload = payloadJson(id);
        try {
            boolean needsSession = false;
            String sessionTypeHint = null;
            Integer sessionOrdinalHint = null;
            boolean gridTimesAllBlank = false;
            List<CarRef> fileCars = null;
            TargetGuess guess;
            switch (batch.kind()) {
                case "ENTRY_LIST" -> {
                    EntryListImport imp = json.readValue(payload, EntryListImport.class);
                    guess = guessEntryList(imp);
                    fileCars = carRefs(imp);
                }
                case "RACE_RESULTS" -> {
                    RaceResultsImport imp = json.readValue(payload, RaceResultsImport.class);
                    guess = guessRaceResults(imp);
                    fileCars = carRefs(imp);
                    needsSession = imp.sessionStart() == null;
                    if (needsSession) {
                        sessionTypeHint = normalizeSessionType(imp.sessionType(), imp.sessionName());
                        sessionOrdinalHint = imp.sessionOrdinal();
                        if (chosenEventId != null && "STAGED".equals(batch.status())) {
                            cr = classReviewForSeason(seasonIdOfEvent(chosenEventId),
                                    imp.rows().stream().map(RaceResultsImport.Row::className).toList());
                        }
                    }
                }
                case "FLAGS" -> guess = guessFlags(json.readValue(payload, FlagsImport.class));
                case "GRID" -> {
                    GridImport imp = json.readValue(payload, GridImport.class);
                    guess = guessGrid(imp);
                    fileCars = carRefs(imp);
                    needsSession = imp.sessionStart() == null;
                    if (needsSession) {
                        sessionTypeHint = normalizeSessionType(imp.sessionType(), imp.sessionName());
                        sessionOrdinalHint = imp.sessionOrdinal();
                    }
                    gridTimesAllBlank = !imp.rows().isEmpty() && imp.rows().stream()
                            .allMatch(r -> r.time() == null || r.time().isBlank());
                    if (chosenEventId != null && "STAGED".equals(batch.status())) {
                        cr = classReviewForSeason(seasonIdOfEvent(chosenEventId),
                                imp.rows().stream().map(GridImport.Row::className).toList());
                    }
                }
                case "STANDINGS" -> {
                    StandingsImport imp = json.readValue(payload, StandingsImport.class);
                    guess = guessStandings(imp);
                    if (chosenYear != null && "STAGED".equals(batch.status())) {
                        cr = reviewStandings(imp, chosenYear);
                        guess = guess.withSeasonYear(chosenYear);
                    }
                }
                default -> guess = null;
            }
            RosterDiff rosterDiff = null;
            if (fileCars != null && "STAGED".equals(batch.status())) {
                Long effectiveEvent = chosenEventId != null ? chosenEventId
                        : (guess != null ? guess.eventId() : null);
                if (effectiveEvent != null) {
                    rosterDiff = rosterDiff(effectiveEvent, fileCars);
                }
            }
            return new ImportReview(batch.kind(), guess, cr, needsSession,
                    sessionTypeHint, sessionOrdinalHint, gridTimesAllBlank, rosterDiff);
        } catch (JsonProcessingException e) {
            return new ImportReview(batch.kind(), null, cr, false, null, null, false, null);
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

    /** Same event resolution as results: the flags file shares the session header. */
    private TargetGuess guessFlags(FlagsImport imp) {
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

    /** Class review for a known season: which of the batch's class spellings
     *  match none of the season's canonical (entry-list) classes. */
    private ClassReview classReviewForSeason(long seasonId, List<String> batchClasses) {
        List<String> known = seasonEntryClasses(seasonId);
        Map<String, String> aliases = classAliasesForSeason(seasonId);
        LinkedHashSet<String> unknown = new LinkedHashSet<>();
        for (String className : batchClasses) {
            if (isUnknownClass(className, known, aliases)) {
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

    /** The event's committed roster, keyed by raw car_number (the entry
     *  uniqueness key — see V2: "04" and "4" are distinct cars). */
    private Map<String, CarRef> eventRoster(long eventId) {
        Map<String, CarRef> roster = new LinkedHashMap<>();
        db.sql("""
                        SELECT car_number, class_name, team_name FROM entry
                        WHERE event_id = :eventId
                        ORDER BY class_name NULLS LAST, car_number
                        """)
                .param("eventId", eventId)
                .query((rs, i) -> roster.put(rs.getString("car_number"),
                        new CarRef(rs.getString("car_number"), rs.getString("class_name"),
                                rs.getString("team_name"))))
                .list();
        return roster;
    }

    /** Null when the event has no entries yet: a first import has no roster to
     *  disagree with, so there is nothing to flag (and nothing to guard). */
    private RosterDiff rosterDiff(long eventId, List<CarRef> fileCars) {
        Map<String, CarRef> roster = eventRoster(eventId);
        if (roster.isEmpty()) {
            return null;
        }
        // Dedupe by raw number, mirroring the upsert's conflict key — a file
        // that lists a car twice still creates (or updates) one entry.
        Map<String, CarRef> file = new LinkedHashMap<>();
        for (CarRef c : fileCars) {
            file.putIfAbsent(c.number(), c);
        }
        List<CarRef> newCars = file.values().stream()
                .filter(c -> !roster.containsKey(c.number()))
                .toList();
        List<CarRef> missingCars = roster.values().stream()
                .filter(c -> !file.containsKey(c.number()))
                .toList();
        return new RosterDiff(newCars, missingCars, roster.size());
    }

    private static List<CarRef> carRefs(GridImport imp) {
        return imp.rows().stream()
                .map(r -> new CarRef(r.number(), r.className(), r.team()))
                .toList();
    }

    private static List<CarRef> carRefs(RaceResultsImport imp) {
        return imp.rows().stream()
                .map(r -> new CarRef(r.number(), r.className(), r.team()))
                .toList();
    }

    private static List<CarRef> carRefs(EntryListImport imp) {
        return imp.entries().stream()
                .map(e -> new CarRef(e.carNumber(), e.classCode(), e.team()))
                .toList();
    }

    /**
     * The season a standings batch lands in. A standings JSON states its year, so
     * the guess is the answer. A points PDF has no year in its text worth trusting
     * — a points value can look like one — so the parser falls back to the sheet's
     * creation date, which is wrong for a full season republished the following
     * January. Hence the reviewer's override wins where they set one.
     */
    private int resolveSeasonYear(StandingsImport imp, ImportTarget target) {
        if (target.seasonYear() != null) {
            return target.seasonYear();
        }
        try {
            return Integer.parseInt(imp.year());
        } catch (NumberFormatException e) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "This standings file names no season year (found " + quoted(imp.year())
                    + "). Set the year in review before committing.");
        }
    }

    private static String quoted(String s) {
        return s == null ? "none" : "'" + s + "'";
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
            String eventName,                      // when creating an event, overrides the payload's name
            String classCode, String kind, Boolean isCup, String familyName, // standings championship
            Integer seasonYear,                    // standings: overrides the year the payload claims
            String sessionType, Integer sessionOrdinal, // for files with no session metadata (grid CSVs)
            Map<String, String> classMapping,
            String gridBasis, // grid commits: how the grid was set, when not by qualifying
            // Reviewer confirmed creating entries for cars absent from the target
            // event's roster (the review's rosterDiff.newCars). Null/false blocks
            // such a commit with a 422 — see requireNewEntriesAck.
            Boolean allowNewEntries
    ) {
        Map<String, String> mapping() {
            return classMapping == null ? Map.of() : classMapping;
        }
    }

    /**
     * The name a newly created event takes: the reviewer's override wins, else the
     * payload's own name. Lets two same-track rounds (iRacing names both after the
     * bare circuit) be de-collided before they hit the UNIQUE(season_id, name)
     * constraint.
     */
    static String chosenEventName(ImportTarget target, String payloadName) {
        return target.eventName() != null && !target.eventName().isBlank()
                ? target.eventName().trim()
                : payloadName;
    }

    /**
     * 422 when this commit would create entries on an event that already has a
     * roster, unless the reviewer acknowledged it (allowNewEntries). A car the
     * event never entered is the fingerprint of a file targeted at the wrong
     * event — the mistake that once replaced CTMP's grid with Watkins Glen's.
     * No-op for an empty event: a first import has no roster to disagree with,
     * so the event-creating paths can never trip this.
     */
    private void requireNewEntriesAck(long eventId, List<CarRef> fileCars, ImportTarget target,
                                      String fileNoun) {
        if (Boolean.TRUE.equals(target.allowNewEntries())) {
            return;
        }
        RosterDiff diff = rosterDiff(eventId, fileCars);
        if (diff == null || diff.newCars().isEmpty()) {
            return;
        }
        List<String> cars = diff.newCars().stream().limit(10)
                .map(c -> "#" + c.number() + (c.teamName() != null && !c.teamName().isBlank()
                        ? " (" + c.teamName() + ")" : ""))
                .toList();
        int extra = diff.newCars().size() - cars.size();
        throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                "This " + fileNoun + " would add " + diff.newCars().size()
                + " car(s) not on the event's entry list: " + String.join(", ", cars)
                + (extra > 0 ? " and " + extra + " more" : "")
                + ". The event already has " + diff.eventEntryCount()
                + " entries — check the file matches the event, or confirm adding new entries"
                + " in review and commit again.");
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
                case "FLAGS" -> commitFlags(json.readValue(payload, FlagsImport.class), target);
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

    // ---------------------------------------------------------- group commit

    /**
     * Confirm-and-commit a whole staged batch as grouped events. Each proposed
     * event's batches (a subsession's results + grid, a weekend's entry list +
     * results, …) commit together so the sibling batches share one resolved event
     * — which single-batch {@link #commit} can't guarantee, since it either
     * attaches to a chosen eventId or creates a brand-new event per batch.
     *
     * eventId null on a {@link ProposedEvent} means "create it"; the group's first
     * event-kind batch supplies the circuit/date, and the reviewer-chosen name
     * de-collides two same-track rounds before they hit UNIQUE(season_id, name).
     * Standings batches carry a null eventKey and commit on their own.
     */
    public record ProposedEvent(String key, Long eventId, String name, String eventDate) {
    }

    public record GroupBatch(long batchId, String eventKey, ImportTarget target) {
    }

    public record GroupCommitRequest(List<ProposedEvent> events, List<GroupBatch> batches) {
    }

    public record BatchResult(long batchId, String status, String message, Long eventId) {
    }

    public record GroupCommitResult(int committed, int failed, List<BatchResult> results) {
    }

    /**
     * Not {@code @Transactional}: it spans one transaction per event group (via
     * {@link #txTemplate}) so one bad group rolls back alone while the rest of the
     * season still commits. The self-call to {@link #commit} joins the template's
     * transaction (proxy self-invocation, so its own {@code @Transactional} is a
     * no-op — exactly what we want here).
     */
    public GroupCommitResult commitGroup(GroupCommitRequest req) {
        Map<String, ProposedEvent> byKey = validateGroupRequest(req);
        Map<String, List<GroupBatch>> grouped = groupByEventKey(req.batches());

        List<BatchResult> results = new ArrayList<>();
        // Standings and any other keyless batches: each its own transaction.
        for (GroupBatch gb : grouped.getOrDefault(null, List.of())) {
            results.add(commitOneInTx(gb, null));
        }
        // Event groups: all the group's batches in one transaction, event resolved once.
        for (Map.Entry<String, List<GroupBatch>> e : grouped.entrySet()) {
            if (e.getKey() == null) {
                continue;
            }
            results.addAll(commitEventGroup(byKey.get(e.getKey()), e.getValue()));
        }

        int committed = (int) results.stream().filter(r -> "COMMITTED".equals(r.status())).count();
        return new GroupCommitResult(committed, results.size() - committed, results);
    }

    /** Commit one group's batches in a single transaction; roll the whole group
     *  back (batches stay STAGED) on any failure, reporting it per batch. */
    private List<BatchResult> commitEventGroup(ProposedEvent pe, List<GroupBatch> batches) {
        try {
            long eventId = txTemplate.execute(status -> {
                long resolved = pe.eventId() != null ? attachEventId(pe.eventId()) : createGroupEvent(pe, batches);
                for (GroupBatch gb : batches) {
                    commit(gb.batchId(), withEvent(gb.target(), resolved, pe.name()));
                }
                return resolved;
            });
            List<BatchResult> ok = new ArrayList<>();
            for (GroupBatch gb : batches) {
                ok.add(new BatchResult(gb.batchId(), "COMMITTED", null, eventId));
            }
            return ok;
        } catch (RuntimeException ex) {
            String msg = messageOf(ex);
            List<BatchResult> failed = new ArrayList<>();
            for (GroupBatch gb : batches) {
                failed.add(new BatchResult(gb.batchId(), "FAILED", msg, null));
            }
            return failed;
        }
    }

    /** A keyless (standings) batch in its own transaction. */
    private BatchResult commitOneInTx(GroupBatch gb, Long eventId) {
        try {
            txTemplate.executeWithoutResult(status -> commit(gb.batchId(), gb.target()));
            return new BatchResult(gb.batchId(), "COMMITTED", null, eventId);
        } catch (RuntimeException ex) {
            return new BatchResult(gb.batchId(), "FAILED", messageOf(ex), null);
        }
    }

    /** Confirm an attach-target event exists, returning its id. */
    private long attachEventId(long eventId) {
        Optional<Long> found = db.sql("SELECT id FROM event WHERE id = :id").param("id", eventId)
                .query(Long.class).optional();
        return found.orElseThrow(() -> new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                "Chosen event no longer exists"));
    }

    /** Create the group's event from its first event-kind batch's circuit/date and
     *  the reviewer-chosen name, then renumber the season's rounds by date. */
    private long createGroupEvent(ProposedEvent pe, List<GroupBatch> batches) {
        if (pe.name() == null || pe.name().isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "New event needs a name");
        }
        GroupBatch first = batches.get(0);
        EventMeta meta = eventMetaFromBatch(first.batchId());
        if (meta.date() == null) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "This import has no date, so it can't create the event \"" + pe.name() + "\"");
        }
        long seriesId = resolveSeriesId(first.target());
        long seasonId = findOrCreateSeason(seriesId, meta.date().getYear());
        boolean nameTaken = db.sql("SELECT 1 FROM event WHERE season_id = :s AND name = :n")
                .param("s", seasonId).param("n", pe.name().trim())
                .query(Integer.class).optional().isPresent();
        if (nameTaken) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "An event named \"" + pe.name().trim() + "\" already exists this season — "
                            + "rename it or attach to the existing event");
        }
        long eventId = createEvent(seasonId, pe.name().trim(), meta.circuit(),
                meta.lengthM(), meta.country(), meta.date());
        renumberSeasonRounds(seasonId);
        return eventId;
    }

    private record EventMeta(String defaultName, String circuit, Double lengthM, String country, LocalDate date) {
    }

    /** Circuit/date metadata read from an event-kind batch's stored payload. */
    private EventMeta eventMetaFromBatch(long batchId) {
        BatchSummary batch = get(batchId);
        String payload = payloadJson(batchId);
        try {
            return switch (batch.kind()) {
                case "RACE_RESULTS" -> {
                    RaceResultsImport i = json.readValue(payload, RaceResultsImport.class);
                    yield new EventMeta(i.eventName(), i.circuitName(), i.circuitLengthM(), i.circuitCountry(),
                            i.sessionStart() != null ? i.sessionStart().toLocalDate() : null);
                }
                case "GRID" -> {
                    GridImport i = json.readValue(payload, GridImport.class);
                    yield new EventMeta(i.eventName(), i.circuitName(), i.circuitLengthM(), i.circuitCountry(),
                            i.sessionStart() != null ? i.sessionStart().toLocalDate() : null);
                }
                case "FLAGS" -> {
                    FlagsImport i = json.readValue(payload, FlagsImport.class);
                    yield new EventMeta(i.eventName(), i.circuitName(), i.circuitLengthM(), i.circuitCountry(),
                            i.sessionStart() != null ? i.sessionStart().toLocalDate() : null);
                }
                case "ENTRY_LIST" -> {
                    EntryListImport i = json.readValue(payload, EntryListImport.class);
                    LocalDate d = i.event().endDate() != null ? i.event().endDate() : i.event().startDate();
                    yield new EventMeta(i.event().name(), i.event().circuit(), null, null, d);
                }
                default -> throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "Batch " + batchId + " (" + batch.kind() + ") can't anchor an event group");
            };
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Stored payload no longer parses", ex);
        }
    }

    /** A copy of the target pinned to a resolved event (so commit attaches rather
     *  than creating), carrying the chosen name through for good measure. */
    private static ImportTarget withEvent(ImportTarget t, long eventId, String eventName) {
        return new ImportTarget(t.seriesId(), t.newSeriesName(), eventId, eventName,
                t.classCode(), t.kind(), t.isCup(), t.familyName(), t.seasonYear(),
                t.sessionType(), t.sessionOrdinal(), t.classMapping(), t.gridBasis(),
                t.allowNewEntries());
    }

    private static String messageOf(RuntimeException ex) {
        if (ex instanceof ResponseStatusException rse && rse.getReason() != null) {
            return rse.getReason();
        }
        return ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
    }

    /** Structural validation (frontend-contract errors → 400). Returns the
     *  key→event map. Per-season name/state issues surface per-group at commit. */
    static Map<String, ProposedEvent> validateGroupRequest(GroupCommitRequest req) {
        if (req == null || req.events() == null || req.batches() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Empty group-commit request");
        }
        Map<String, ProposedEvent> byKey = new LinkedHashMap<>();
        Map<String, Long> createNames = new java.util.HashMap<>();
        for (ProposedEvent pe : req.events()) {
            if (pe.key() == null || byKey.put(pe.key(), pe) != null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Duplicate or missing event key");
            }
            if (pe.eventId() == null) {
                if (pe.name() == null || pe.name().isBlank()) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A new event needs a name");
                }
                // Two create-groups with the same name would collide on commit.
                if (createNames.merge(pe.name().trim().toLowerCase(), 1L, Long::sum) > 1) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "Two new events share the name \"" + pe.name().trim() + "\"");
                }
            }
        }
        for (GroupBatch gb : req.batches()) {
            if (gb.eventKey() != null && !byKey.containsKey(gb.eventKey())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Batch " + gb.batchId() + " references unknown event key \"" + gb.eventKey() + "\"");
            }
        }
        return byKey;
    }

    /** Partition batches by eventKey, order-preserving; keyless (standings) under
     *  the null key. */
    static Map<String, List<GroupBatch>> groupByEventKey(List<GroupBatch> batches) {
        Map<String, List<GroupBatch>> grouped = new LinkedHashMap<>();
        for (GroupBatch gb : batches) {
            grouped.computeIfAbsent(gb.eventKey(), k -> new ArrayList<>()).add(gb);
        }
        return grouped;
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
                    : createEvent(seasonId, chosenEventName(target, imp.eventName()), imp.circuitName(),
                    imp.circuitLengthM(), imp.circuitCountry(), imp.sessionStart().toLocalDate());
            renumberSeasonRounds(seasonId);
            sessionType = normalizeSessionType(imp.sessionType(), imp.sessionName());
            sessionOrdinal = imp.sessionOrdinal();
            sessionName = imp.sessionName();
        } else {
            // No session metadata (results CSVs): the reviewer chose an existing
            // event — which pins the season — and the session. Same shape as the
            // grid CSV path: the file has no date to create an event with. The
            // payload knows race from qualifying by its own header, so its
            // session type wins over the reviewer's; the ordinal is the
            // reviewer's call (the file can't tell Race 1 from Race 2).
            if (target.eventId() == null) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "Results file has no event/session metadata; choose an existing event in review");
            }
            eventId = target.eventId();
            seasonId = seasonIdOfEvent(eventId);
            sessionType = resolveCsvSessionType(imp.sessionType(), target.sessionType());
            sessionOrdinal = target.sessionOrdinal() != null ? target.sessionOrdinal() : imp.sessionOrdinal();
            sessionName = sessionDisplayName(sessionType, sessionOrdinal);
        }
        requireNewEntriesAck(eventId, carRefs(imp), target, "results file");
        // Read the canonical class set before upserting entries, so the file's
        // own rows don't seed it (see canonicalizeClass).
        List<String> knownClasses = seasonEntryClasses(seasonId);
        Map<String, String> classAliases = classAliasesForSeason(seasonId);
        Map<String, String> mapping = target.mapping();
        String context = imp.championshipName() != null && imp.sessionStart() != null
                ? imp.championshipName() + " " + imp.sessionStart().getYear() : "results import";

        // Find-or-create the session by its stable (event, session_type, ordinal)
        // key — not the free-text name, so a source that renames "Race" to
        // "Race 1" updates its predecessor instead of adding a second RACE
        // session. Then replace only this session's results; a starting grid
        // imported separately hangs off the same session and must survive.
        long sessionId = findOrCreateRaceSession(eventId, sessionType, sessionOrdinal,
                sessionName, imp.sessionStart(), imp.reportMark(), imp.reportMessage());
        db.sql("DELETE FROM result WHERE session_id = :sessionId").param("sessionId", sessionId).update();

        for (RaceResultsImport.Row row : imp.rows()) {
            String className = canonicalizeClass(row.className(), knownClasses, mapping, classAliases,
                    context);
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
        // The event's race shape may have changed (a new session appeared);
        // recompute AUTO format assignments within the same transaction.
        raceFormats.autoAssignEvent(eventId);
        teamAssignments.applySeason(seasonId);
    }

    /**
     * Flags/RC-message stream for one session. No entries or classes are touched
     * — the stream hangs off the session alone. The header's report_mark/message
     * ride through findOrCreateRaceSession, whose COALESCE upsert lets this
     * (later-generated) file refresh the stewards' notes without a null wiping
     * what a results file already stored.
     */
    private void commitFlags(FlagsImport imp, ImportTarget target) {
        if (imp.sessionStart() == null) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Flags file has no session date; cannot determine the season");
        }
        long seriesId = resolveSeriesId(target);
        long seasonId = findOrCreateSeason(seriesId, imp.sessionStart().getYear());
        long eventId = target.eventId() != null ? target.eventId()
                : createEvent(seasonId, chosenEventName(target, imp.eventName()), imp.circuitName(),
                imp.circuitLengthM(), imp.circuitCountry(), imp.sessionStart().toLocalDate());
        renumberSeasonRounds(seasonId);

        String sessionType = normalizeSessionType(imp.sessionType(), imp.sessionName());
        long sessionId = findOrCreateRaceSession(eventId, sessionType, imp.sessionOrdinal(),
                imp.sessionName(), imp.sessionStart(), imp.reportMark(), imp.reportMessage());
        db.sql("DELETE FROM session_flag WHERE session_id = :sessionId").param("sessionId", sessionId).update();

        List<FlagsImport.FlagRow> rows = imp.rows();
        for (int seq = 0; seq < rows.size(); seq++) {
            FlagsImport.FlagRow row = rows.get(seq);
            db.sql("""
                            INSERT INTO session_flag (session_id, seq, wall_time, elapsed, rec_type,
                                                      flag, message, flag_time, accum_time, lap)
                            VALUES (:sessionId, :seq, :wallTime, :elapsed, :recType,
                                    :flag, :message, :flagTime, :accumTime, :lap)
                            """)
                    .param("sessionId", sessionId)
                    .param("seq", seq)
                    .param("wallTime", row.wallTime())
                    .param("elapsed", row.elapsed())
                    .param("recType", row.recType() != null ? row.recType() : "")
                    .param("flag", row.flag())
                    .param("message", row.message())
                    .param("flagTime", row.flagTime())
                    .param("accumTime", row.accumTime())
                    .param("lap", row.lap())
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
                    : createEvent(seasonId, chosenEventName(target, imp.eventName()), imp.circuitName(),
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
        requireNewEntriesAck(eventId, carRefs(imp), target, "grid file");
        List<String> knownClasses = seasonEntryClasses(seasonId);
        Map<String, String> classAliases = classAliasesForSeason(seasonId);
        Map<String, String> mapping = target.mapping();
        String context = imp.championshipName() != null && imp.sessionStart() != null
                ? imp.championshipName() + " " + imp.sessionStart().getYear() : "grid import";

        // The grid belongs to a race session; find-or-create it by the stable key
        // (the results file may not have been imported yet), then replace only its
        // grid rows — the session's results, if any, are untouched.
        long sessionId = findOrCreateRaceSession(eventId, sessionType, sessionOrdinal,
                sessionName, imp.sessionStart(), null, null);
        // The reviewer's note on how this grid was set ("2nd fastest qualifying
        // lap", "Championship points — qualifying cancelled"). A targeted
        // UPDATE, not part of the shared session upsert: grid commits are the
        // only writer, and blank means "leave whatever an earlier commit said".
        if (target.gridBasis() != null && !target.gridBasis().isBlank()) {
            db.sql("UPDATE race_session SET grid_basis = :basis WHERE id = :sessionId")
                    .param("basis", target.gridBasis().trim())
                    .param("sessionId", sessionId)
                    .update();
        }
        db.sql("DELETE FROM grid_position WHERE session_id = :sessionId").param("sessionId", sessionId).update();

        for (GridImport.Row row : imp.rows()) {
            String className = canonicalizeClass(row.className(), knownClasses, mapping, classAliases, context);
            long entryId = upsertEntry(eventId, row.number(), className, row.team(),
                    row.vehicle(), row.manufacturer(), row.group());
            // A batch staged before the attribution fields existed deserializes
            // them as null; treat that like a roster-less source.
            List<RaceResultsImport.DriverRow> roster = row.drivers() != null ? row.drivers() : List.of();

            // Seat -> driver resolution needs a lineup. The entry list and
            // results files own driver_assignment; the grid roster only seeds it
            // when nothing else has yet (grid imported first), and those sources
            // replace it wholesale later.
            Map<Integer, Long> bySeat = new java.util.HashMap<>();
            db.sql("""
                            SELECT seat_order, driver_id FROM driver_assignment
                            WHERE entry_id = :entryId AND driver_id IS NOT NULL
                            """)
                    .param("entryId", entryId)
                    .query((rs, i) -> bySeat.put(rs.getInt("seat_order"), rs.getLong("driver_id")))
                    .list();
            Integer assignmentCount = db.sql("SELECT count(*) FROM driver_assignment WHERE entry_id = :entryId")
                    .param("entryId", entryId)
                    .query(Integer.class)
                    .single();
            if (assignmentCount == 0 && !roster.isEmpty()) {
                replaceDriverAssignments(entryId, roster);
            }

            db.sql("""
                            INSERT INTO grid_position (session_id, entry_id, position_overall, position_in_class,
                                                       qualifying_time, starting_driver_id, qualifying_driver_id)
                            VALUES (:sessionId, :entryId, :posOverall, :posInClass,
                                    :qualifyingTime, :startingDriverId, :qualifyingDriverId)
                            """)
                    .param("sessionId", sessionId)
                    .param("entryId", entryId)
                    .param("posOverall", row.positionOverall())
                    .param("posInClass", row.positionInClass())
                    .param("qualifyingTime", row.time())
                    .param("startingDriverId", resolveGridDriver(row.startingDriverSeat(), roster, bySeat))
                    .param("qualifyingDriverId", resolveGridDriver(row.qualifyingDriverSeat(), roster, bySeat))
                    .update();
        }
        // A grid can find-or-create the session before its results arrive;
        // keep format assignments in step with the new shape.
        raceFormats.autoAssignEvent(eventId);
        teamAssignments.applySeason(seasonId);
    }

    /**
     * Resolves a grid file's 1-based seat index to a driver id. The file's own
     * roster defines what seat N means, so it wins over the stored lineup (which
     * may have come from a differently ordered entry list); the stored lineup is
     * the fallback for roster-less sources (grid CSVs). Unresolvable stays null
     * — attribution is never guessed.
     */
    private Long resolveGridDriver(Integer seat, List<RaceResultsImport.DriverRow> roster,
                                   Map<Integer, Long> bySeat) {
        if (seat == null) {
            return null;
        }
        for (RaceResultsImport.DriverRow d : roster) {
            if (d.seatOrder() == seat && d.firstName() != null && d.surname() != null) {
                return findOrCreateDriver(d.firstName(), d.surname(), d.country(), d.hometown());
            }
        }
        return bySeat.get(seat);
    }

    /** Session type for a metadata-less results file. The payload's own type
     *  wins — a results CSV knows race from qualifying by its header, and the
     *  reviewer shouldn't be able to file a qualifying sheet as a race by
     *  leaving a dropdown on its default. The reviewer's choice covers
     *  payloads that carry none. */
    static String resolveCsvSessionType(String payloadType, String targetType) {
        return normalizeSessionType(payloadType != null ? payloadType : targetType, null);
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
        long seasonId = findOrCreateSeason(seriesId, resolveSeasonYear(imp, target));

        // Class / kind / cup are confirmed by the reviewer (pre-filled from the
        // title). Standings often spell classes differently from the entry list
        // (the Endurance Cup's "GT Daytona PRO" vs "GTDPRO"); resolve to the
        // season's canonical (entry-list) class, mapping unknowns in review.
        String kind = normalizeKind(target.kind(), imp.mainTitle());
        boolean isCup = Boolean.TRUE.equals(target.isCup());
        String className = canonicalizeClass(target.classCode(), seasonEntryClasses(seasonId),
                target.mapping(), classAliasesForSeason(seasonId), imp.mainTitle());
        // The award set (family + kind) this class championship groups under. The
        // family is the reviewer's confirmed name (defaults to the series name for
        // the primary championship, the cup's own name for a cup).
        String family = target.familyName() != null && !target.familyName().isBlank()
                ? target.familyName().trim() : seriesName(seriesId);
        long groupId = findOrCreateChampionshipGroup(seasonId, family, kind, isCup);

        // Replace this championship wholesale (cascade removes sessions/rows/points).
        // is_overall survives the replace: it is set by hand (or by the no-class
        // rule) after import, and a routine standings refresh must not silently
        // turn an overall championship back into a class one.
        boolean isOverall = className == null || className.isBlank()
                || db.sql("SELECT is_overall FROM championship WHERE season_id = :seasonId AND name = :name")
                        .param("seasonId", seasonId).param("name", imp.name())
                        .query(Boolean.class).optional().orElse(false);
        db.sql("DELETE FROM championship WHERE season_id = :seasonId AND name = :name")
                .param("seasonId", seasonId).param("name", imp.name()).update();
        long championshipId = db.sql("""
                        INSERT INTO championship (season_id, group_id, name, title, class_name, is_overall)
                        VALUES (:seasonId, :groupId, :name, :title, :className, :isOverall)
                        RETURNING id
                        """)
                .param("seasonId", seasonId)
                .param("groupId", groupId)
                .param("name", imp.name())
                .param("title", imp.mainTitle())
                .param("className", className)
                .param("isOverall", isOverall)
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
            StandingsImport.Adjustments adj = row.adjustments();
            long rowId = db.sql("""
                            INSERT INTO standings_row (championship_id, position, competitor_key, competitor_name,
                                                       total_points, net_position, total_net_points,
                                                       base_points, positive_adjustments, negative_adjustments)
                            VALUES (:chId, :position, :key, :name, :points, :netPosition, :netPoints,
                                    :basePoints, :posAdj, :negAdj)
                            RETURNING id
                            """)
                    .param("chId", championshipId)
                    .param("position", row.position())
                    .param("key", row.key())
                    .param("name", row.team())
                    .param("points", row.totalPoints())
                    .param("netPosition", row.netPosition())
                    .param("netPoints", row.totalNetPoints())
                    // Null throughout when the source reports no adjustments,
                    // which is not the same as reporting none.
                    .param("basePoints", adj == null ? null : adj.basePoints())
                    .param("posAdj", adj == null ? null : adj.positive())
                    .param("negAdj", adj == null ? null : adj.negative())
                    .query(Long.class)
                    .single();
            for (StandingsImport.SessionPoints p : row.pointsBySession()) {
                db.sql("""
                                INSERT INTO standings_session_points (standings_row_id, session_index, total_points,
                                                                      race_points, pole_points, fastest_lap_points,
                                                                      penalty_points, bonus_points, status)
                                VALUES (:rowId, :idx, :total, :race, :pole, :fl, :penalty, :bonus, :status)
                                """)
                        .param("rowId", rowId)
                        .param("idx", p.sessionIndex())
                        .param("total", p.totalPoints())
                        .param("race", p.racePoints())
                        .param("pole", p.polePoints())
                        .param("fl", p.fastestLapPoints())
                        .param("penalty", p.penaltyPoints())
                        .param("bonus", p.bonusPoints())
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
                : createEvent(seasonId, chosenEventName(target, imp.event().name()), imp.event().circuit(),
                null, null, eventDate);
        renumberSeasonRounds(seasonId);
        requireNewEntriesAck(eventId, carRefs(imp), target, "entry list");
        Map<String, String> classAliases = classAliasesForSeason(seasonId);

        for (EntryListImport.Entry e : imp.entries()) {
            // Class codes are normalized by dropping spaces so entry-list spelling
            // ("GTD PRO") joins with results-file spelling ("GTDPRO"). The entry
            // list is the class authority, but a series-level alias still wins:
            // it's a standing rename, and without it a re-imported entry list
            // would re-seed the retired spelling as canon.
            String className = e.classCode() != null ? e.classCode().replace(" ", "") : null;
            if (className != null) {
                className = classAliases.getOrDefault(normClass(className), className);
            }
            // A VIP / Invitational entry (blue-V icon on a driver, "Indicates
            // driver is a VIP Entry" / "Invitational Entry" in the legend) scores
            // no points — the sheet's GUEST treatment. The entry list is the
            // authority for this, so it sets is_guest on commit.
            boolean isGuest = e.drivers().stream()
                    .anyMatch(d -> d.markers() != null && d.markers().contains("invitational"));
            long entryId = db.sql("""
                            INSERT INTO entry (event_id, car_number, class_name, team_name, team_id, vehicle,
                                               manufacturer, sponsor, tire, fuel, is_guest)
                            VALUES (:eventId, :number, :className, :team, :teamId, :vehicle, :manufacturer,
                                    :sponsor, :tire, :fuel, :isGuest)
                            ON CONFLICT (event_id, car_number) DO UPDATE
                                SET class_name = EXCLUDED.class_name,
                                    team_name = EXCLUDED.team_name,
                                    team_id = EXCLUDED.team_id,
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
                    .param("teamId", teamResolver.resolveOrCreate(e.team()))
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
        teamAssignments.applySeason(seasonId);
    }

    // ---------------------------------------------------------------- helpers

    private static boolean isPdf(byte[] content) {
        return content.length > 4 && content[0] == '%' && content[1] == 'P'
               && content[2] == 'D' && content[3] == 'F';
    }

    /** Runs the Python parser sidecar (parser/parse_entry_list.py): PDF in, entries.json out. */
    private byte[] runEntryListParser(String filename, byte[] pdf) {
        return runPdfParser(parserScript, "Entry-list", "entry-list", filename, pdf);
    }

    /** Runs the Python parser sidecar (parser/parse_points.py): PDF in, points.json out.
     *  A non-zero exit is the sidecar's row checksum failing — surface it verbatim,
     *  it names the row that didn't add up. */
    private byte[] runPointsParser(String filename, byte[] pdf) {
        return runPdfParser(pointsParserScript, "Points", "points", filename, pdf);
    }

    /** Runs the Python parser sidecar (parser/parse_grid_pdf.py): starting-grid
     *  PDF in, grid JSON out. */
    private byte[] runGridPdfParser(String filename, byte[] pdf) {
        return runPdfParser(gridPdfParserScript, "Grid", "grid-pdf", filename, pdf);
    }

    private byte[] runPdfParser(String script, String label, String slug, String filename, byte[] pdf) {
        try {
            // Keep the original filename: the entry-list parser detects the
            // series code (IWSC/IMPC/...) from it, and both name it in errors.
            java.nio.file.Path dir = java.nio.file.Files.createTempDirectory(slug + "-");
            String safeName = java.nio.file.Path.of(filename == null ? slug + ".pdf" : filename)
                    .getFileName().toString();
            java.nio.file.Path tmp = dir.resolve(safeName);
            try {
                java.nio.file.Files.write(tmp, pdf);
                Process process = new ProcessBuilder(parserPython, script, tmp.toString())
                        .redirectErrorStream(false)
                        .start();
                byte[] out = process.getInputStream().readAllBytes();
                String err = new String(process.getErrorStream().readAllBytes());
                if (!process.waitFor(120, java.util.concurrent.TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                            label + " parser timed out on " + filename);
                }
                if (process.exitValue() != 0) {
                    throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                            label + " parser failed on " + filename + ": " + err.trim());
                }
                return out;
            } finally {
                java.nio.file.Files.deleteIfExists(tmp);
                java.nio.file.Files.deleteIfExists(dir);
            }
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Could not run " + label.toLowerCase() + " parser (" + parserPython + " " + script
                    + "): " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    label + " parser interrupted");
        }
    }

    /**
     * Entry lists carry full names ("Tijmen van der Helm"); match against the
     * driver table by full name first so we never duplicate a driver whose
     * results-file first/surname split differs from a naive first-space split.
     */
    Long findOrCreateDriverByFullName(String fullName, String country) {
        record DriverRow(long id, String first, String surname) {
        }
        Optional<DriverRow> existing = db.sql("""
                        SELECT id, first_name, surname FROM driver
                        WHERE lower(first_name || ' ' || surname) = lower(:name)
                        """)
                .param("name", fullName)
                .query((rs, i) -> new DriverRow(rs.getLong("id"), rs.getString("first_name"), rs.getString("surname")))
                .optional();
        int split = fullName.indexOf(' ');
        String first = split > 0 ? fullName.substring(0, split) : fullName;
        String surname = split > 0 ? fullName.substring(split + 1) : "";
        if (existing.isPresent()) {
            DriverRow row = existing.get();
            recaseIfShouty(row.id(), row.first(), row.surname(), first, surname);
            return row.id();
        }
        return db.sql("""
                        INSERT INTO driver (first_name, surname, country)
                        VALUES (:first, :surname, :country)
                        ON CONFLICT (lower(first_name), lower(surname)) DO UPDATE
                            SET country = COALESCE(EXCLUDED.country, driver.country)
                        RETURNING id
                        """)
                .param("first", first)
                .param("surname", surname)
                .param("country", country)
                .query(Long.class)
                .single();
    }

    /** Results CSVs/PDFs shout names ("CHAD GILSINGER"); the first source that
     *  spells the same name properly upgrades the stored casing. Same-split
     *  only — a full-name-derived split must never overwrite a source-supplied
     *  one, that's the identity key. */
    private void recaseIfShouty(long id, String storedFirst, String storedSurname,
                                String first, String surname) {
        String stored = storedFirst + " " + storedSurname;
        String incoming = first + " " + surname;
        if (storedFirst.equalsIgnoreCase(first) && storedSurname.equalsIgnoreCase(surname)
                && stored.equals(stored.toUpperCase(Locale.ROOT))
                && !incoming.equals(incoming.toUpperCase(Locale.ROOT))) {
            db.sql("UPDATE driver SET first_name = :first, surname = :surname WHERE id = :id")
                    .param("first", first)
                    .param("surname", surname)
                    .param("id", id)
                    .update();
        }
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
                        INSERT INTO entry (event_id, car_number, class_name, team_name, team_id, vehicle, manufacturer, class_group)
                        VALUES (:eventId, :number, :className, :team, :teamId, :vehicle, :manufacturer, :group)
                        ON CONFLICT (event_id, car_number) DO UPDATE
                            SET class_name = EXCLUDED.class_name,
                                team_name = EXCLUDED.team_name,
                                team_id = EXCLUDED.team_id,
                                vehicle = EXCLUDED.vehicle,
                                manufacturer = COALESCE(EXCLUDED.manufacturer, entry.manufacturer),
                                class_group = COALESCE(EXCLUDED.class_group, entry.class_group)
                        RETURNING id
                        """)
                .param("eventId", eventId)
                .param("number", number)
                .param("className", className)
                .param("team", team)
                .param("teamId", teamResolver.resolveOrCreate(team))
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
            long driverId = findOrCreateDriver(d.firstName(), d.surname(), d.country(), d.hometown());
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

    /** The one driver identity rule: find-or-create on case-insensitive
     *  (first_name, surname), never erasing known country/hometown with a
     *  source that omits them. */
    long findOrCreateDriver(String firstName, String surname, String country, String hometown) {
        record DriverRow(long id, String first, String surname) {
        }
        DriverRow row = db.sql("""
                        INSERT INTO driver (first_name, surname, country, hometown)
                        VALUES (:first, :surname, :country, :hometown)
                        ON CONFLICT (lower(first_name), lower(surname)) DO UPDATE
                            SET country = COALESCE(EXCLUDED.country, driver.country),
                                hometown = COALESCE(EXCLUDED.hometown, driver.hometown)
                        RETURNING id, first_name, surname
                        """)
                .param("first", firstName)
                .param("surname", surname)
                .param("country", country)
                .param("hometown", hometown)
                .query((rs, i) -> new DriverRow(rs.getLong("id"), rs.getString("first_name"), rs.getString("surname")))
                .single();
        recaseIfShouty(row.id(), row.first(), row.surname(), firstName, surname);
        return row.id();
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
     * What a championship ranks. A closed set: these are the only values anything
     * downstream distinguishes (SeasonViewController asks "is it DRIVERS",
     * SheetController and the season grid rank TEAMS first, TeamController joins
     * on kind = 'TEAMS'). A sheet's own wording can differ — Mustang Challenge
     * prints "Entrants" for what IWSC calls "Teams" — and the reviewer maps it to
     * one of these at import.
     */
    private static final List<String> CHAMPIONSHIP_KINDS = List.of("DRIVERS", "TEAMS", "MANUFACTURERS");

    /**
     * The reviewer's confirmed kind, or a hard failure. This is validated rather
     * than trusted because it is free text on the wire: a "DRIVER" typo once
     * created a whole second award group silently alongside "DRIVERS", and an
     * unset dropdown would post "" and group a championship under no kind at all.
     */
    private static String normalizeKind(String raw, String context) {
        String kind = raw == null ? "" : raw.trim().toUpperCase();
        if (!CHAMPIONSHIP_KINDS.contains(kind)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Championship kind " + (kind.isEmpty() ? "is required" : "'" + raw + "' is not recognized")
                    + " for " + context + ". Choose one of " + CHAMPIONSHIP_KINDS + ".");
        }
        return kind;
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

    private static boolean isUnknownClass(String className, List<String> known, Map<String, String> aliases) {
        if (className == null || known.isEmpty()) {
            return false;
        }
        String n = normClass(className);
        if (aliases.containsKey(n)) {
            return false;
        }
        return known.stream().noneMatch(k -> normClass(k).equals(n));
    }

    /**
     * Resolve a source class spelling to the season's canonical (entry-list)
     * class. A caller-supplied mapping wins (the reviewer's choice), then a
     * per-series alias (a standing rename the user recorded — it beats the
     * bootstrap case so a cold season imports canonical from the start).
     * Otherwise a spelling that matches a known class ignoring case/spaces is
     * auto-resolved to that class. With no canonical set yet (bootstrap: no
     * entry list imported), the raw spelling establishes canon. Anything else
     * is unrecognized and fails the commit so it gets mapped in the review
     * screen first.
     */
    private String canonicalizeClass(String raw, List<String> known, Map<String, String> mapping,
                                     Map<String, String> aliases, String context) {
        // No class is a real answer, not a missing one: an overall championship
        // spans every class ("...Points (Overall)") and a teams/dealer one isn't
        // scoped to a class at all. Blank has to mean the same as null here — the
        // reviewer clears the box to say it, and a caller that omits the field
        // sends "" — otherwise the only way past this check is naming a class the
        // championship doesn't have. That is not hypothetical: a PACCA (Overall)
        // sheet was committed as class PRO to satisfy this validator.
        if (raw == null || raw.isBlank()) {
            return null;
        }
        if (mapping != null && mapping.containsKey(raw)) {
            return mapping.get(raw);
        }
        String n = normClass(raw);
        if (aliases.containsKey(n)) {
            return aliases.get(n);
        }
        if (known.isEmpty()) {
            return raw;
        }
        for (String k : known) {
            if (normClass(k).equals(n)) {
                return k;
            }
        }
        throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                "Unrecognized class '" + raw + "' for " + context
                + ". Map it to a known class in the review screen before committing. Known classes: " + known);
    }

    /** The season's series' class aliases, keyed by normalized alias spelling. */
    private Map<String, String> classAliasesForSeason(long seasonId) {
        Map<String, String> aliases = new java.util.LinkedHashMap<>();
        db.sql("""
                        SELECT ca.alias, ca.class_name
                        FROM class_alias ca
                                 JOIN season s ON s.series_id = ca.series_id
                        WHERE s.id = :seasonId
                        """)
                .param("seasonId", seasonId)
                .query((rs, i) -> aliases.put(normClass(rs.getString("alias")), rs.getString("class_name")))
                .list();
        return aliases;
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
