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
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class ImportService {

    /** sourceUrl / sourceEvent are set only on batches fetched from the Al
     *  Kamel site (see {@link SourceContext}); null for uploads and iRacing.
     *  sourcePreseason is the planner's verdict that the fetched weekend is no
     *  round; false for everything else. */
    public record BatchSummary(long id, String kind, String format, String filename, String status,
                               String summary, OffsetDateTime createdAt, String sourceUrl, String sourceEvent,
                               boolean sourcePreseason) {
    }

    private static final String BATCH_COLUMNS =
            "id, kind, format, filename, status, summary, created_at, source_url, source_event, source_preseason";

    private static BatchSummary batchRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new BatchSummary(rs.getLong("id"), rs.getString("kind"),
                rs.getString("format"), rs.getString("filename"), rs.getString("status"),
                rs.getString("summary"), rs.getObject("created_at", OffsetDateTime.class),
                rs.getString("source_url"), rs.getString("source_event"), rs.getBoolean("source_preseason"));
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
    private final String f1PdfParserScript;

    public ImportService(JdbcClient db, ObjectMapper json, IRacingClient iracing,
                         com.pitpass.formats.RaceFormatService raceFormats,
                         com.pitpass.teams.TeamAssignmentService teamAssignments,
                         com.pitpass.teams.TeamResolver teamResolver,
                         PlatformTransactionManager txManager,
                         @org.springframework.beans.factory.annotation.Value("${pit-pass.entry-list-parser.python:python3}") String parserPython,
                         @org.springframework.beans.factory.annotation.Value("${pit-pass.entry-list-parser.script:../parser/parse_entry_list.py}") String parserScript,
                         @org.springframework.beans.factory.annotation.Value("${pit-pass.points-parser.script:../parser/parse_points.py}") String pointsParserScript,
                         @org.springframework.beans.factory.annotation.Value("${pit-pass.grid-pdf-parser.script:../parser/parse_grid_pdf.py}") String gridPdfParserScript,
                         @org.springframework.beans.factory.annotation.Value("${pit-pass.f1-pdf-parser.script:../parser/parse_f1_pdf.py}") String f1PdfParserScript) {
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
        this.f1PdfParserScript = f1PdfParserScript;
    }

    // ---------------------------------------------------------------- staging

    /** One family parser's output, ready for the shared import_batch insert. */
    record Staged(String kind, Object payload, String summary) {
    }

    /**
     * Stages an upload as one or more batches. Most files carry a single
     * document, but one championship-points PDF holds every championship of the
     * series — each becomes its own batch, reviewed and committed separately.
     */
    public List<BatchSummary> stage(String filename, byte[] content, ImportFormat format) {
        return stage(filename, content, format, null);
    }

    /**
     * As above, for a file fetched from the Al Kamel site: the {@link SourceContext}
     * completes what the payload leaves blank (series, event, session start and
     * label for a CSV or a PDF sheet; the season year for a points PDF) so the
     * batch reviews and commits like a timing JSON, and the batch records where
     * it came from.
     */
    public List<BatchSummary> stage(String filename, byte[] content, ImportFormat format, SourceContext source) {
        ImportFormat resolved = format == ImportFormat.AUTO ? resolveAuto(content) : format;
        List<Staged> staged = switch (resolved) {
            case AUTO -> throw new IllegalStateException("AUTO must be resolved before staging");
            case IMSA_JSON -> List.of(stageImsaJson(content));
            case IMSA_PDF -> List.of(stageImsaPdf(filename, content));
            case IMSA_POINTS_PDF -> stageImsaPointsPdf(filename, content);
            case IMSA_GRID_PDF -> List.of(stageImsaGridPdf(filename, content));
            case IMSA_CSV -> List.of(stageImsaCsv(filename, content));
            case F1_PDF -> List.of(stageF1Pdf(filename, content));
            case IRACING_JSON -> stageIRacingJson(content);
        };
        if (source != null) {
            staged = staged.stream().map(s -> applyContext(s, resolved, source)).toList();
        }
        return persist(staged, resolved, filename, source);
    }

    /**
     * Completes a staged payload from its source context. Only blanks are
     * filled: a payload that names its own session keeps it. A session-level
     * file with no session name takes the folder label and the ordinal that
     * label carries ("Race 2" → 2). A points PDF takes the folder's year — its
     * own guess is the PDF creation date, wrong for a season republished in
     * January. Entry lists carry their own dates and are left alone.
     */
    static Staged applyContext(Staged s, ImportFormat format, SourceContext ctx) {
        Object payload = s.payload();
        if (payload instanceof RaceResultsImport r) {
            boolean fillName = r.sessionName() == null && ctx.sessionLabel() != null;
            payload = new RaceResultsImport(
                    r.championshipName() != null ? r.championshipName() : ctx.seriesName(),
                    r.eventName() != null ? r.eventName() : ctx.eventName(),
                    fillName ? ctx.sessionLabel() : r.sessionName(),
                    r.sessionType(),
                    fillName ? ImportParser.sessionOrdinal(ctx.sessionLabel()) : r.sessionOrdinal(),
                    r.reportMark(), r.reportMessage(),
                    r.sessionStart() != null ? r.sessionStart() : ctx.sessionStart(),
                    r.circuitName(), r.circuitLengthM(), r.circuitCountry(), r.rows());
        } else if (payload instanceof GridImport g) {
            boolean fillName = g.sessionName() == null && ctx.sessionLabel() != null;
            payload = new GridImport(
                    g.championshipName() != null ? g.championshipName() : ctx.seriesName(),
                    g.eventName() != null ? g.eventName() : ctx.eventName(),
                    fillName ? ctx.sessionLabel() : g.sessionName(),
                    g.sessionType(),
                    fillName ? ImportParser.sessionOrdinal(ctx.sessionLabel()) : g.sessionOrdinal(),
                    g.sessionStart() != null ? g.sessionStart() : ctx.sessionStart(),
                    g.circuitName(), g.circuitLengthM(), g.circuitCountry(), g.rows());
        } else if (payload instanceof FlagsImport f) {
            payload = new FlagsImport(
                    f.championshipName() != null ? f.championshipName() : ctx.seriesName(),
                    f.eventName() != null ? f.eventName() : ctx.eventName(),
                    f.sessionName() != null ? f.sessionName() : ctx.sessionLabel(),
                    f.sessionType(), f.sessionOrdinal(), f.reportMark(), f.reportMessage(),
                    f.sessionStart() != null ? f.sessionStart() : ctx.sessionStart(),
                    f.circuitName(), f.circuitLengthM(), f.circuitCountry(), f.rows());
        } else if (payload instanceof StandingsImport st && format == ImportFormat.IMSA_POINTS_PDF) {
            // A sheet that names no series at all ("PRO Driver Championship",
            // Lamborghini Super Trofeo 2021) takes the folder's, so the title
            // resolves the way every other series' does.
            String title = withSeriesPrefix(st.mainTitle(), ctx.seriesName());
            String year = ctx.year() != null ? String.valueOf(ctx.year()) : st.year();
            payload = new StandingsImport(title.equals(st.mainTitle()) ? st.name() : title, title, st.subTitle(),
                    year, st.sessions(), st.rows());
        }
        return new Staged(s.kind(), payload, s.summary());
    }

    /**
     * The title with the series' name in front when the title names no series
     * of its own — judged by whether any distinctive word of the series name
     * (four letters or more, not a generic "cup"/"championship"/"series")
     * appears in the title. A title that names its series in other words
     * ("IMSA WeatherTech…" for a series recorded without the "IMSA") is left
     * alone; a title that names nothing gets the prefix.
     */
    static String withSeriesPrefix(String mainTitle, String seriesName) {
        if (mainTitle == null || seriesName == null || seriesName.isBlank()) {
            return mainTitle;
        }
        String title = " " + mainTitle.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ") + " ";
        for (String word : seriesName.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (word.length() >= 4 && !GENERIC_SERIES_WORDS.contains(word) && title.contains(" " + word + " ")) {
                return mainTitle;
            }
        }
        return seriesName.trim() + " " + mainTitle.trim();
    }

    private static final Set<String> GENERIC_SERIES_WORDS = Set.of(
            "championship", "series", "challenge", "presented", "north", "america", "imsa");

    /** The event a fetched weekend already created or attached to, by the
     *  folder key stamped on it at commit. Empty for uploads. */
    private Optional<Long> findEventBySource(long seasonId, String sourceEvent) {
        if (sourceEvent == null || sourceEvent.isBlank()) {
            return Optional.empty();
        }
        return db.sql("SELECT id FROM event WHERE season_id = :s AND source_ref = :ref ORDER BY id LIMIT 1")
                .param("s", seasonId).param("ref", sourceEvent).query(Long.class).optional();
    }

    /** Stamp the folder key on the event a fetched batch landed in — once; the
     *  first stamp stays, so a stray attach can't relabel an event. */
    private void stampEventSource(long eventId, String sourceEvent) {
        db.sql("UPDATE event SET source_ref = :ref WHERE id = :id AND source_ref IS NULL")
                .param("ref", sourceEvent).param("id", eventId).update();
    }

    /** The review's event guess: the season's event stamped with this batch's
     *  source folder wins, else the venue-and-weekend match. */
    private Long guessEvent(Optional<Long> seriesId, Integer year, String circuit, LocalDate date,
                            String sourceEvent) {
        return seriesId.flatMap(sid -> year == null ? Optional.<Long>empty() : findSeasonId(sid, year))
                .flatMap(sn -> findEventBySource(sn, sourceEvent).or(() -> findMatchingEvent(sn, circuit, date)))
                .orElse(null);
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
        return persist(staged, format, filename, null);
    }

    private List<BatchSummary> persist(List<Staged> staged, ImportFormat format, String filename,
                                       SourceContext source) {
        List<BatchSummary> out = new ArrayList<>();
        for (Staged s : staged) {
            long id = db.sql("""
                            INSERT INTO import_batch (kind, format, filename, payload, summary,
                                                      source_url, source_modified, source_event, source_preseason)
                            VALUES (:kind, :format, :filename, :payload::jsonb, :summary,
                                    :sourceUrl, :sourceModified, :sourceEvent, :sourcePreseason)
                            RETURNING id
                            """)
                    .param("kind", s.kind())
                    .param("format", format.name())
                    .param("filename", filename)
                    .param("payload", toJson(s.payload()))
                    .param("summary", s.summary())
                    .param("sourceUrl", source == null ? null : source.sourceUrl())
                    .param("sourceModified", source == null ? null : source.sourceModified())
                    .param("sourceEvent", source == null ? null : source.sourceEvent())
                    .param("sourcePreseason", source != null && source.preseason())
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
     * ordinal from the title's race number, where the title has one. A
     * single-driver sheet names one driver per slot with no roster to resolve
     * a seat against, so attribution stays null — readers fall back to the
     * entry's sole crew member. A crew sheet (WeatherTech, Pilot Challenge
     * through 2021) lists the crew as initialled names and marks the starting
     * and qualifying driver by emphasis; those come through as the row's
     * roster and seats, and resolve at commit through the stored lineup the
     * way an initialled results name does.
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
            List<RaceResultsImport.DriverRow> crew = new ArrayList<>();
            int seat = 0;
            for (JsonNode d : r.path("drivers")) {
                String name = d.path("name").asText("").trim();
                int cut = name.lastIndexOf(' ');
                if (cut > 0) {
                    crew.add(new RaceResultsImport.DriverRow(++seat, name.substring(0, cut).trim(),
                            name.substring(cut + 1).trim(), null, null, null));
                }
            }
            Integer starting = r.path("starting_driver_seat").isInt() ? r.path("starting_driver_seat").asInt() : null;
            Integer qualifying = r.path("qualifying_driver_seat").isInt() ? r.path("qualifying_driver_seat").asInt() : null;
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
                    starting != null && starting <= crew.size() ? starting : null,
                    qualifying != null && qualifying <= crew.size() ? qualifying : null,
                    crew
            ));
        }
        // A numberless title ("Race Official Starting Grid") is the weekend's
        // one race: ordinal 1, as the folder label confirms at staging.
        return new GridImport(null, null, null, null,
                root.path("race").isInt() ? root.path("race").asInt() : 1, null, null, null, null, rows);
    }

    /** Stages a Formula 1 support-race sheet via the Python sidecar, which
     *  tells the race classification, qualifying classification and starting
     *  grid apart by title. None carries a date, so each batch goes through the
     *  reviewer-supplies-the-target flow with its session pre-filled. */
    private Staged stageF1Pdf(String filename, byte[] pdf) {
        if (!isPdf(pdf)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Not a PDF file (expected an F1 support-race results, qualifying or grid PDF)");
        }
        JsonNode root;
        try {
            root = json.readTree(runPdfParser(f1PdfParserScript, "F1 PDF", "f1-pdf", filename, pdf));
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "F1 PDF parser returned invalid JSON: " + e.getMessage());
        }
        String header = "%s %d — %s".formatted(
                root.path("location").asText("F1 weekend"), root.path("year").asInt(),
                root.path("session").asText());
        String mark = root.path("revised").asBoolean(false) ? " (Revised)" : "";
        if (F1PdfMapper.isGrid(root)) {
            GridImport parsed = F1PdfMapper.mapGrid(root);
            return new Staged("GRID", parsed,
                    "%s starting grid%s, %d cars".formatted(header, mark, parsed.rows().size()));
        }
        RaceResultsImport parsed = F1PdfMapper.mapResults(root);
        String status = root.path("status").asText("");
        return new Staged("RACE_RESULTS", parsed, "%s %sclassification%s, %d entries".formatted(
                header, status.isEmpty() ? "" : status.toLowerCase() + " ", mark, parsed.rows().size()));
    }

    /** The IMSA CSV family, told apart by header: the starting grid
     *  (POSITION;CLASS;NUMBER;...), race results (POSITION;NUMBER;STATUS;...),
     *  and qualifying results (POS;NUMBER;LAP;TIME;...). None carry event or
     *  session metadata; the reviewer supplies both at commit. */
    private Staged stageImsaCsv(String filename, byte[] content) {
        try {
            if (ImportParser.looksLikeGridCsv(content)) {
                GridImport parsed = withFileSession(ImportParser.parseGridCsv(content), filename);
                return new Staged("GRID", parsed,
                        "Starting grid CSV%s — %d cars".formatted(sessionSuffix(parsed.sessionName()),
                                parsed.rows().size()));
            }
            if (ImportParser.looksLikeResultsCsv(content)) {
                RaceResultsImport parsed = withFileSession(ImportParser.parseResultsCsv(content), filename);
                return new Staged("RACE_RESULTS", parsed,
                        "Race results CSV%s — %d entries".formatted(sessionSuffix(parsed.sessionName()),
                                parsed.rows().size()));
            }
            if (ImportParser.looksLikeQualifyingCsv(content)) {
                RaceResultsImport parsed = withFileSession(ImportParser.parseQualifyingCsv(content), filename);
                String summary = "Qualifying results CSV%s — %d entries".formatted(
                        sessionSuffix(parsed.sessionName()), parsed.rows().size());
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

    /** A CSV carries no session metadata, but an Al Kamel file name does
     *  ("03_Results_Qualifying - GTD Position.CSV"): keep it as the session's
     *  name and ordinal hint. The header still decides race vs qualifying. */
    static RaceResultsImport withFileSession(RaceResultsImport imp, String filename) {
        String name = SessionNames.sessionNameFromFilename(filename);
        if (name == null) {
            return imp;
        }
        return new RaceResultsImport(imp.championshipName(), imp.eventName(), name, imp.sessionType(),
                ImportParser.sessionOrdinal(name), imp.reportMark(), imp.reportMessage(), imp.sessionStart(),
                imp.circuitName(), imp.circuitLengthM(), imp.circuitCountry(), imp.rows());
    }

    static GridImport withFileSession(GridImport imp, String filename) {
        String name = SessionNames.sessionNameFromFilename(filename);
        if (name == null) {
            return imp;
        }
        return new GridImport(imp.championshipName(), imp.eventName(), name, imp.sessionType(),
                ImportParser.sessionOrdinal(name), imp.sessionStart(), imp.circuitName(),
                imp.circuitLengthM(), imp.circuitCountry(), imp.rows());
    }

    private static String sessionSuffix(String sessionName) {
        return sessionName == null ? "" : " (" + sessionName + ")";
    }

    public List<BatchSummary> list() {
        return db.sql("SELECT " + BATCH_COLUMNS + " FROM import_batch ORDER BY id DESC")
                .query((rs, i) -> batchRow(rs))
                .list();
    }

    public BatchSummary get(long id) {
        return db.sql("SELECT " + BATCH_COLUMNS + " FROM import_batch WHERE id = :id")
                .param("id", id)
                .query((rs, i) -> batchRow(rs))
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
            CupGuess cup = cupOf(imp.mainTitle(), className);
            if (cup != null) {
                className = cup.className();
            }
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
     *  and DNQs). orphanedCars is the subset of missingCars with no results
     *  and no drivers — entries nothing durable references (a withdrawn car
     *  keeps its entry-list drivers, so it never qualifies), which the
     *  reviewer may opt to remove with the commit. Null when there is no
     *  roster to compare against. */
    public record RosterDiff(List<CarRef> newCars, List<CarRef> missingCars,
                             List<CarRef> orphanedCars, int eventEntryCount) {
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
                               // A split session's own name ("Qualifying - GTD Position"):
                               // it is numbered at commit, so the ordinal picker doesn't apply.
                               String sessionNameHint,
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
        return reviewTarget(id, chosenEventId, null, chosenYear);
    }

    /** As above; seriesId + seasonYear together name the season a metadata-less
     *  file's *new* event will land in, so its classes get checked against that
     *  season's (when it already exists) instead of against nothing. */
    public ImportReview reviewTarget(long id, Long chosenEventId, Long chosenSeriesId, Integer chosenYear) {
        BatchSummary batch = get(id);
        Optional<Long> describedSeason = chosenEventId == null && chosenSeriesId != null && chosenYear != null
                ? findSeasonId(chosenSeriesId, chosenYear) : Optional.empty();
        ClassReview cr = classReview(id);
        String payload = payloadJson(id);
        try {
            boolean needsSession = false;
            String sessionTypeHint = null;
            Integer sessionOrdinalHint = null;
            String sessionNameHint = null;
            boolean gridTimesAllBlank = false;
            List<CarRef> fileCars = null;
            TargetGuess guess;
            switch (batch.kind()) {
                case "ENTRY_LIST" -> {
                    EntryListImport imp = json.readValue(payload, EntryListImport.class);
                    guess = guessEntryList(imp, batch.sourceEvent());
                    fileCars = carRefs(imp);
                }
                case "RACE_RESULTS" -> {
                    RaceResultsImport imp = json.readValue(payload, RaceResultsImport.class);
                    guess = guessRaceResults(imp, batch.sourceEvent());
                    fileCars = carRefs(imp);
                    needsSession = imp.sessionStart() == null;
                    if (needsSession) {
                        sessionTypeHint = normalizeSessionType(imp.sessionType(), imp.sessionName());
                        sessionOrdinalHint = imp.sessionOrdinal();
                        sessionNameHint = SessionNames.splitLabel(sessionTypeHint, imp.sessionName()) != null
                                ? imp.sessionName() : null;
                        if (chosenEventId != null && "STAGED".equals(batch.status())) {
                            cr = classReviewForSeason(seasonIdOfEvent(chosenEventId),
                                    imp.rows().stream().map(RaceResultsImport.Row::className).toList());
                        } else if (describedSeason.isPresent() && "STAGED".equals(batch.status())) {
                            cr = classReviewForSeason(describedSeason.get(),
                                    imp.rows().stream().map(RaceResultsImport.Row::className).toList());
                        }
                    }
                }
                case "FLAGS" -> guess = guessFlags(json.readValue(payload, FlagsImport.class), batch.sourceEvent());
                case "GRID" -> {
                    GridImport imp = json.readValue(payload, GridImport.class);
                    guess = guessGrid(imp, batch.sourceEvent());
                    fileCars = carRefs(imp);
                    needsSession = imp.sessionStart() == null;
                    if (needsSession) {
                        sessionTypeHint = normalizeSessionType(imp.sessionType(), imp.sessionName());
                        sessionOrdinalHint = imp.sessionOrdinal();
                        sessionNameHint = SessionNames.splitLabel(sessionTypeHint, imp.sessionName()) != null
                                ? imp.sessionName() : null;
                    }
                    gridTimesAllBlank = !imp.rows().isEmpty() && imp.rows().stream()
                            .allMatch(r -> r.time() == null || r.time().isBlank());
                    if (chosenEventId != null && "STAGED".equals(batch.status())) {
                        cr = classReviewForSeason(seasonIdOfEvent(chosenEventId),
                                imp.rows().stream().map(GridImport.Row::className).toList());
                    } else if (needsSession && describedSeason.isPresent() && "STAGED".equals(batch.status())) {
                        cr = classReviewForSeason(describedSeason.get(),
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
                    sessionTypeHint, sessionOrdinalHint, sessionNameHint, gridTimesAllBlank, rosterDiff);
        } catch (JsonProcessingException e) {
            return new ImportReview(batch.kind(), null, cr, false, null, null, null, false, null);
        }
    }

    private TargetGuess guessEntryList(EntryListImport imp, String sourceEvent) {
        Integer year = imp.event().startDate() != null ? imp.event().startDate().getYear() : null;
        Optional<Long> seriesId = findSeriesByCode(imp.event().series());
        LocalDate date = imp.event().endDate() != null ? imp.event().endDate() : imp.event().startDate();
        Long eventGuess = guessEvent(seriesId, year, imp.event().circuit(), date, sourceEvent);
        return new TargetGuess(seriesId.orElse(null), seriesId.map(this::seriesName).orElse(null), year,
                eventGuess, imp.event().name(), imp.event().circuit(),
                date != null ? date.toString() : null, null, null, null, null);
    }

    private TargetGuess guessRaceResults(RaceResultsImport imp, String sourceEvent) {
        Integer year = imp.sessionStart() != null ? imp.sessionStart().getYear() : null;
        Optional<Long> seriesId = findSeriesByName(imp.championshipName());
        LocalDate date = imp.sessionStart() != null ? imp.sessionStart().toLocalDate() : null;
        Long eventGuess = guessEvent(seriesId, year, imp.circuitName(), date, sourceEvent);
        return new TargetGuess(seriesId.orElse(null), seriesId.map(this::seriesName).orElse(null), year,
                eventGuess, imp.eventName(), imp.circuitName(),
                date != null ? date.toString() : null, null, null, null, null);
    }

    /** Same event resolution as results: the flags file shares the session header. */
    private TargetGuess guessFlags(FlagsImport imp, String sourceEvent) {
        Integer year = imp.sessionStart() != null ? imp.sessionStart().getYear() : null;
        Optional<Long> seriesId = findSeriesByName(imp.championshipName());
        LocalDate date = imp.sessionStart() != null ? imp.sessionStart().toLocalDate() : null;
        Long eventGuess = guessEvent(seriesId, year, imp.circuitName(), date, sourceEvent);
        return new TargetGuess(seriesId.orElse(null), seriesId.map(this::seriesName).orElse(null), year,
                eventGuess, imp.eventName(), imp.circuitName(),
                date != null ? date.toString() : null, null, null, null, null);
    }

    private TargetGuess guessGrid(GridImport imp, String sourceEvent) {
        Integer year = imp.sessionStart() != null ? imp.sessionStart().getYear() : null;
        Optional<Long> seriesId = findSeriesByName(imp.championshipName());
        LocalDate date = imp.sessionStart() != null ? imp.sessionStart().toLocalDate() : null;
        Long eventGuess = guessEvent(seriesId, year, imp.circuitName(), date, sourceEvent);
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
        // Carrera Cup NA's points JSON titles every file with the series alone and
        // names the championship in the subtitle — "Pro Drivers - Championship
        // Points Standings" — the same shape its 2025 PDF prints. Read the class
        // and kind from there when the title itself carries none.
        if (ck.kind() == null && imp.subTitle() != null) {
            Matcher m = SUBTITLE_CHAMPIONSHIP.matcher(imp.subTitle().trim());
            if (m.find()) {
                ck = classKindOf(m.group(1));
            }
        }
        String seriesName = seriesId.map(this::seriesName).orElse(null);
        // Default: the primary championship, grouped under the series name. The
        // cups a title names outright (the Endurance Cup tables beside the
        // season tables, a Bronze or Rookie cup) come pre-flipped with their
        // family; any other cup is the reviewer flipping is_cup.
        CupGuess cup = cupOf(imp.mainTitle(), ck.className());
        return new TargetGuess(seriesId.orElse(null), seriesName, year, null, null, null, null,
                cup != null ? cup.className() : ck.className(), ck.kind(),
                cup != null, cup != null ? cup.family() : seriesName);
    }

    /** A cup a standings title names, with the family it files under and the
     *  class it scores (null for a cup across classes). */
    record CupGuess(String family, String className) {
    }

    /**
     * The cups the JSON era publishes beside the season tables and the sheets
     * name outright: "IMSA Michelin Endurance Cup GT Daytona PRO Drivers"
     * (per class; the title spells the class out, the season's entries use
     * the code), "…Grand Sport BRONZE Drivers" (the class's Bronze cup),
     * "…Rookie Drivers" (no class). Null for a season table.
     */
    static CupGuess cupOf(String mainTitle, String className) {
        String title = mainTitle == null ? "" : mainTitle.trim();
        String words = " " + title.toUpperCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ") + " ";
        if (words.contains(" MICHELIN ENDURANCE CUP ") || words.contains(" IMEC ")) {
            // "GT Daytona PRO Drivers", or the JSON's own "LMP2 DRIVERS OVERALL":
            // walk back to the kind word, the class is what precedes it.
            List<String> rest = new ArrayList<>(List.of(stripTitleDecoration(title
                    .replaceFirst("(?i)^.*?michelin endurance cup\\s*", "")
                    .replaceFirst("(?i)^IMEC\\s*", "")).split("\\s+")));
            while (rest.size() > 1 && !CLOSED_KINDS.contains(canonicalKind(rest.get(rest.size() - 1)))) {
                rest.remove(rest.size() - 1);
            }
            String phrase = rest.size() > 1 ? String.join(" ", rest.subList(0, rest.size() - 1)) : "";
            return new CupGuess("Michelin Endurance Cup", imsaClassCode(phrase));
        }
        if (words.contains(" BRONZE ")) {
            String cls = className == null ? "" : className.replaceAll("(?i)\\s*bronze\\s*", " ").trim();
            return new CupGuess("Bronze Cup", cls.isEmpty() ? null : cls);
        }
        if (words.contains(" ROOKIE ")) {
            return new CupGuess("Rookie Cup", null);
        }
        return null;
    }

    private static final Set<String> CLOSED_KINDS = Set.of("DRIVERS", "TEAMS", "MANUFACTURERS");

    /** IMSA's spelled-out class names as the codes its entries carry. */
    static String imsaClassCode(String phrase) {
        String key = phrase == null ? "" : phrase.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return switch (key) {
            case "" -> null;
            case "gtdaytonapro", "gtdpro" -> "GTDPRO";
            case "gtdaytona", "gtd" -> "GTD";
            case "gtp" -> "GTP";
            case "gtlm", "gtlemans" -> "GTLM";
            case "lmp2" -> "LMP2";
            case "lmp3" -> "LMP3";
            case "dpi" -> "DPi";
            default -> phrase.trim();
        };
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
        // Entries with results or drivers are anchored regardless of what this
        // file says. Grid rows deliberately don't anchor: a commit may be about
        // to replace them, and the delete re-checks all three at commit time.
        java.util.Set<String> anchored = new java.util.HashSet<>(db.sql("""
                        SELECT e.car_number FROM entry e
                        WHERE e.event_id = :eventId
                          AND (EXISTS (SELECT 1 FROM result r WHERE r.entry_id = e.id)
                               OR EXISTS (SELECT 1 FROM driver_assignment da WHERE da.entry_id = e.id))
                        """)
                .param("eventId", eventId)
                .query(String.class)
                .list());
        List<CarRef> orphanedCars = missingCars.stream()
                .filter(c -> !anchored.contains(c.number()))
                .toList();
        return new RosterDiff(newCars, missingCars, orphanedCars, roster.size());
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
    /** "Pro Drivers - Championship Points Standings": the championship a
     *  standings subtitle names, when it names one. */
    private static final Pattern SUBTITLE_CHAMPIONSHIP =
            Pattern.compile("^(.+?)\\s+-\\s+Championship Points Standings\\b");

    /** "Pro Drivers" → (Pro, DRIVERS); "Entrants" → (null, TEAMS); "" → nothing. */
    private static ClassAndKind classKindOf(String phrase) {
        String[] parts = phrase == null ? new String[0] : phrase.trim().split("\\s+");
        if (parts.length == 0 || parts[0].isEmpty()) {
            return new ClassAndKind(null, null);
        }
        if (parts.length == 1) {
            return new ClassAndKind(null, canonicalKind(parts[0]));
        }
        return new ClassAndKind(String.join(" ", java.util.Arrays.copyOf(parts, parts.length - 1)),
                canonicalKind(parts[parts.length - 1]));
    }

    private static ClassAndKind deriveClassKindFromTail(String title) {
        String[] parts = title == null ? new String[0] : stripTitleDecoration(title.trim()).split("\\s+");
        if (parts.length < 2) {
            return new ClassAndKind(null, null);
        }
        return new ClassAndKind(parts[parts.length - 2], canonicalKind(parts[parts.length - 1]));
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
            Boolean allowNewEntries,
            // Reviewer opted to delete entries this file doesn't list that end up
            // with no results, no grid rows and no drivers once the commit's
            // writes land (the review's rosterDiff.orphanedCars). Never implied:
            // null/false leaves every entry in place.
            Boolean removeOrphanedEntries,
            // For a file with no session metadata (results/grid CSVs) landing in a
            // brand-new event: the CSV carries no date or venue, so the reviewer
            // supplies them. The date pins the season (its year) and the round
            // order; the circuit is optional. Ignored when eventId is set.
            LocalDate eventDate,
            String circuitName
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

    /**
     * Deletes the event's entries this file doesn't list, where nothing —
     * result, grid row, driver assignment — references them anymore. Runs after
     * the commit's writes so an entry whose only anchor was the replaced grid
     * (the CTMP fabrication case) qualifies, and the zero-references check is
     * evaluated against the final state, never the review's prediction. Opt-in
     * per commit via the review's orphanedCars acknowledgment; a no-op without
     * it, and cascade-safe by construction (there is nothing left to cascade).
     */
    private void removeOrphanedEntries(long eventId, List<CarRef> fileCars, ImportTarget target) {
        if (!Boolean.TRUE.equals(target.removeOrphanedEntries()) || fileCars.isEmpty()) {
            return;
        }
        List<String> fileNumbers = fileCars.stream().map(CarRef::number).distinct().toList();
        db.sql("""
                        DELETE FROM entry e
                        WHERE e.event_id = :eventId
                          AND e.car_number NOT IN (:fileNumbers)
                          AND NOT EXISTS (SELECT 1 FROM result r WHERE r.entry_id = e.id)
                          AND NOT EXISTS (SELECT 1 FROM grid_position g WHERE g.entry_id = e.id)
                          AND NOT EXISTS (SELECT 1 FROM driver_assignment da WHERE da.entry_id = e.id)
                        """)
                .param("eventId", eventId)
                .param("fileNumbers", fileNumbers)
                .update();
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
            // Event-kind commits hand back the event they landed in, so a fetched
            // batch can stamp its source folder on it (standings hang off a
            // championship, not an event).
            Long eventId = switch (batch.kind()) {
                // iRacing numbers its own sessions; every other source's session
                // name is what tells a split session apart (SessionNames).
                case "RACE_RESULTS" -> commitRaceResults(json.readValue(payload, RaceResultsImport.class), target,
                        nameKeyed(batch));
                case "GRID" -> commitGrid(json.readValue(payload, GridImport.class), target, nameKeyed(batch));
                case "FLAGS" -> commitFlags(json.readValue(payload, FlagsImport.class), target, nameKeyed(batch));
                case "STANDINGS" -> {
                    commitStandings(json.readValue(payload, StandingsImport.class), target);
                    yield null;
                }
                case "ENTRY_LIST" -> commitEntryList(json.readValue(payload, EntryListImport.class), target);
                default -> throw new IllegalStateException("Unknown batch kind " + batch.kind());
            };
            if (eventId != null && batch.sourceEvent() != null) {
                stampEventSource(eventId, batch.sourceEvent());
            }
            if (eventId != null && batch.sourcePreseason()) {
                // The planner judged the weekend no round (the Roar, a test): the
                // event it landed in takes no round number.
                db.sql("UPDATE event SET is_round = FALSE WHERE id = :id").param("id", eventId).update();
                renumberSeasonRounds(seasonIdOfEvent(eventId));
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
        try {
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
        } finally {
            classSeeding.remove();
        }

        int committed = (int) results.stream().filter(r -> "COMMITTED".equals(r.status())).count();
        return new GroupCommitResult(committed, results.size() - committed, results);
    }

    private record SeasonKey(long seriesId, int year) {
    }

    /**
     * Seasons that had no classes when the current group commit began, each
     * with the group's own classes as its canon. The review judged "unknown
     * class" against the season as it was; inside the group the first batch's
     * rows would otherwise become the canon that the next batch is held to — a
     * qualifying CSV listing one class, then the race listing four, failed on
     * "Unrecognized class" for the classes the review had waved through. For
     * these seasons the canon is the union of the group's class spellings
     * (first spelling wins, case and spaces ignored), so a later batch's
     * "Gtd " still folds onto the first's "GTD" instead of standing beside it.
     * Request-scoped: set and cleared by {@link #commitGroup}.
     */
    private final ThreadLocal<Map<SeasonKey, List<String>>> classSeeding = ThreadLocal.withInitial(HashMap::new);

    /** Called inside the group's transaction once its event is resolved, so a
     *  season the group itself just created (a new series) is seen too. */
    private void markClassSeeding(long eventId, List<GroupBatch> batches) {
        try {
            SeasonKey key = db.sql("SELECT s.series_id, s.year FROM season s JOIN event e ON e.season_id = s.id WHERE e.id = :id")
                    .param("id", eventId)
                    .query((rs, i) -> new SeasonKey(rs.getLong("series_id"), rs.getInt("year")))
                    .optional().orElse(null);
            if (key == null || classSeeding.get().containsKey(key)) {
                return;
            }
            boolean empty = findSeasonId(key.seriesId(), key.year())
                    .map(sn -> seasonEntryClasses(sn).isEmpty()).orElse(true);
            if (!empty) {
                return;
            }
            List<String> canon = new ArrayList<>();
            for (GroupBatch gb : batches) {
                for (String c : payloadClasses(gb.batchId())) {
                    if (c != null && !c.isBlank() && canon.stream().noneMatch(k -> normClass(k).equals(normClass(c)))) {
                        canon.add(c);
                    }
                }
            }
            classSeeding.get().put(key, canon);
        } catch (RuntimeException ignored) {
            // Marking is an optimisation of the gate, never a reason to fail the group;
            // the commit below reports any real problem.
        }
    }

    /** The class spellings an event-kind batch's rows carry. */
    private List<String> payloadClasses(long batchId) {
        BatchSummary batch = get(batchId);
        String payload = payloadJson(batchId);
        try {
            return switch (batch.kind()) {
                case "RACE_RESULTS" -> json.readValue(payload, RaceResultsImport.class).rows().stream()
                        .map(RaceResultsImport.Row::className).toList();
                case "GRID" -> json.readValue(payload, GridImport.class).rows().stream()
                        .map(GridImport.Row::className).toList();
                case "ENTRY_LIST" -> json.readValue(payload, EntryListImport.class).entries().stream()
                        .map(EntryListImport.Entry::className).toList();
                default -> List.of();
            };
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }

    /** Commit one group's batches in a single transaction; roll the whole group
     *  back (batches stay STAGED) on any failure, reporting it per batch. */
    private List<BatchResult> commitEventGroup(ProposedEvent pe, List<GroupBatch> batches) {
        try {
            long eventId = txTemplate.execute(status -> {
                long resolved = pe.eventId() != null ? attachEventId(pe.eventId()) : createGroupEvent(pe, batches);
                markClassSeeding(resolved, batches);
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
                t.allowNewEntries(), t.removeOrphanedEntries(), t.eventDate(), t.circuitName());
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

    private static boolean nameKeyed(BatchSummary batch) {
        return !ImportFormat.IRACING_JSON.name().equals(batch.format());
    }

    private record SessionSlot(int ordinal, String name, String splitLabel) {
    }

    /**
     * Where a session lands within its event. A plain name keeps the ordinal it
     * was given ("Race 2" -> 2). A split name ("Qualifying - GTD Position") is
     * its own session: re-importing it finds the session of the same name, and
     * a new one takes the next free ordinal of its type — otherwise every
     * unnumbered split session would overwrite ordinal 1.
     */
    private SessionSlot resolveSessionSlot(long eventId, String sessionType, int ordinal, String name,
                                           boolean nameKeyed) {
        String label = nameKeyed ? SessionNames.splitLabel(sessionType, name) : null;
        if (label == null) {
            return new SessionSlot(ordinal, name, null);
        }
        record Existing(int ordinal, String name) {
        }
        List<Existing> existing = db.sql("""
                        SELECT ordinal, name FROM race_session
                        WHERE event_id = :eventId AND session_type = :type
                        ORDER BY ordinal
                        """)
                .param("eventId", eventId)
                .param("type", sessionType)
                .query((rs, i) -> new Existing(rs.getInt("ordinal"), rs.getString("name")))
                .list();
        String key = SessionNames.normalize(name);
        for (Existing e : existing) {
            if (key.equals(SessionNames.normalize(e.name()))) {
                return new SessionSlot(e.ordinal(), name, label);
            }
        }
        int next = existing.stream().mapToInt(Existing::ordinal).max().orElse(0) + 1;
        return new SessionSlot(next, name, label);
    }

    private long commitRaceResults(RaceResultsImport imp, ImportTarget target, boolean nameKeyed) {
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
            // event — which pins the season — or described a new one (the file
            // has no date or venue of its own), and named the session. The
            // payload knows race from qualifying by its own header, so its
            // session type wins over the reviewer's; the ordinal is the
            // reviewer's call (the file can't tell Race 1 from Race 2).
            if (target.eventId() != null) {
                eventId = target.eventId();
                seasonId = seasonIdOfEvent(eventId);
            } else {
                seasonId = seasonForDescribedEvent(target, "Results file");
                eventId = createDescribedEvent(seasonId, target);
            }
            sessionType = resolveCsvSessionType(imp.sessionType(), target.sessionType());
            sessionOrdinal = target.sessionOrdinal() != null ? target.sessionOrdinal() : imp.sessionOrdinal();
            // A split session keeps the name its file carries; the reviewer's
            // ordinal can't tell "GTD Position" from "GTD Points".
            sessionName = SessionNames.splitLabel(sessionType, imp.sessionName()) != null
                    ? imp.sessionName() : sessionDisplayName(sessionType, sessionOrdinal);
        }
        SessionSlot slot = resolveSessionSlot(eventId, sessionType, sessionOrdinal, sessionName, nameKeyed);
        Set<String> pointsOnly = "QUALIFYING".equals(sessionType)
                ? SessionNames.pointsOnlyClasses(slot.splitLabel(),
                        imp.rows().stream().map(RaceResultsImport.Row::className).distinct().toList())
                : Set.of();
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
        long sessionId = findOrCreateRaceSession(eventId, sessionType, slot.ordinal(),
                slot.name(), imp.sessionStart(), imp.reportMark(), imp.reportMessage());
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
                                                fastest_lap_driver_seat, pit_stops, points_only)
                            VALUES (:sessionId, :entryId, :posOverall, :posInClass, :status,
                                    :notFinished, :notFinishedCause, :laps, :elapsedTime, :gapFirst,
                                    :gapPrevious, :flTime, :flNumber, :flKph, :flSeat, :pitStops, :pointsOnly)
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
                    .param("pointsOnly", pointsOnly.contains(row.className()))
                    .update();
        }
        removeOrphanedEntries(eventId, carRefs(imp), target);
        // The event's race shape may have changed (a new session appeared);
        // recompute AUTO format assignments within the same transaction.
        raceFormats.autoAssignEvent(eventId);
        teamAssignments.applySeason(seasonId);
        return eventId;
    }

    /**
     * Flags/RC-message stream for one session. No entries or classes are touched
     * — the stream hangs off the session alone. The header's report_mark/message
     * ride through findOrCreateRaceSession, whose COALESCE upsert lets this
     * (later-generated) file refresh the stewards' notes without a null wiping
     * what a results file already stored.
     */
    private long commitFlags(FlagsImport imp, ImportTarget target, boolean nameKeyed) {
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
        SessionSlot slot = resolveSessionSlot(eventId, sessionType, imp.sessionOrdinal(), imp.sessionName(),
                nameKeyed);
        long sessionId = findOrCreateRaceSession(eventId, sessionType, slot.ordinal(),
                slot.name(), imp.sessionStart(), imp.reportMark(), imp.reportMessage());
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
        return eventId;
    }

    private long commitGrid(GridImport imp, ImportTarget target, boolean nameKeyed) {
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
            // event — which pins the season — or described a new one (the file
            // has no date or venue of its own; normally the entry list imported
            // first creates the event), and named the session.
            if (target.eventId() != null) {
                eventId = target.eventId();
                seasonId = seasonIdOfEvent(eventId);
            } else {
                seasonId = seasonForDescribedEvent(target, "Grid file");
                eventId = createDescribedEvent(seasonId, target);
            }
            sessionType = normalizeSessionType(target.sessionType(), null); // null -> RACE
            sessionOrdinal = target.sessionOrdinal() != null ? target.sessionOrdinal() : imp.sessionOrdinal();
            sessionName = SessionNames.splitLabel(sessionType, imp.sessionName()) != null
                    ? imp.sessionName() : sessionDisplayName(sessionType, sessionOrdinal);
        }
        SessionSlot slot = resolveSessionSlot(eventId, sessionType, sessionOrdinal, sessionName, nameKeyed);
        requireNewEntriesAck(eventId, carRefs(imp), target, "grid file");
        List<String> knownClasses = seasonEntryClasses(seasonId);
        Map<String, String> classAliases = classAliasesForSeason(seasonId);
        Map<String, String> mapping = target.mapping();
        String context = imp.championshipName() != null && imp.sessionStart() != null
                ? imp.championshipName() + " " + imp.sessionStart().getYear() : "grid import";

        // The grid belongs to a race session; find-or-create it by the stable key
        // (the results file may not have been imported yet), then replace only its
        // grid rows — the session's results, if any, are untouched.
        long sessionId = findOrCreateRaceSession(eventId, sessionType, slot.ordinal(),
                slot.name(), imp.sessionStart(), null, null);
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
                    .param("startingDriverId", resolveGridDriver(row.startingDriverSeat(), roster, bySeat, entryId))
                    .param("qualifyingDriverId", resolveGridDriver(row.qualifyingDriverSeat(), roster, bySeat, entryId))
                    .update();
        }
        removeOrphanedEntries(eventId, carRefs(imp), target);
        // A grid can find-or-create the session before its results arrive;
        // keep format assignments in step with the new shape.
        raceFormats.autoAssignEvent(eventId);
        teamAssignments.applySeason(seasonId);
        return eventId;
    }

    /**
     * Resolves a grid file's 1-based seat index to a driver id. The file's own
     * roster defines what seat N means, so it wins over the stored lineup (which
     * may have come from a differently ordered entry list); the stored lineup is
     * the fallback for roster-less sources (grid CSVs). Unresolvable stays null
     * — attribution is never guessed.
     */
    private Long resolveGridDriver(Integer seat, List<RaceResultsImport.DriverRow> roster,
                                   Map<Integer, Long> bySeat, long entryId) {
        if (seat == null) {
            return null;
        }
        for (RaceResultsImport.DriverRow d : roster) {
            if (d.seatOrder() == seat && d.firstName() != null && d.surname() != null) {
                return resolveDriver(d, entryId);
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
        writeStandings(imp, seriesId, findOrCreateSeason(seriesId, resolveSeasonYear(imp, target)), target);
    }

    /**
     * Standings for a season the caller already knows — a source that is
     * pointed at a season (the IMSA Esports correction) rather than one whose
     * payload names a year, which could only find the season's MAIN row.
     * The target supplies class / kind / cup / family exactly as a reviewed
     * standings commit would; its series and year are ignored.
     */
    public void commitStandingsToSeason(StandingsImport imp, long seasonId, ImportTarget target) {
        long seriesId = db.sql("SELECT series_id FROM season WHERE id = :id")
                .param("id", seasonId)
                .query(Long.class)
                .optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such season"));
        writeStandings(imp, seriesId, seasonId, target);
    }

    private void writeStandings(StandingsImport imp, long seriesId, long seasonId, ImportTarget target) {
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
        long groupId = findOrCreateChampionshipGroup(seasonId, family, kind, isCup,
                sheetKindWording(imp.mainTitle(), kind));

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

    private long commitEntryList(EntryListImport imp, ImportTarget target) {
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
        removeOrphanedEntries(eventId, carRefs(imp), target);
        teamAssignments.applySeason(seasonId);
        return eventId;
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
        Optional<Long> aliased = driverByAlias(fullName);
        if (aliased.isPresent()) {
            db.sql("UPDATE driver SET country = COALESCE(:country, country) WHERE id = :id")
                    .param("country", country)
                    .param("id", aliased.get())
                    .update();
            return aliased.get();
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

    /**
     * MAIN seasons only: a season already flipped to QUALIFIER is invisible
     * here, so a later import for the same year starts a fresh MAIN season
     * instead of merging into a qualifying stage. The workflow is therefore
     * import-then-flip — bring in all of a stage's data while its season is
     * still MAIN, then mark it a qualifier on the Series page.
     */
    private long findOrCreateSeason(long seriesId, int year) {
        Optional<Long> existing = db.sql(
                        "SELECT id FROM season WHERE series_id = :seriesId AND year = :year AND kind = 'MAIN'")
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
    /** {@code sheetWording} is the sheet's own word for the kind ("Entrants"),
     *  used for a brand-new group when no season before it set one. */
    private long findOrCreateChampionshipGroup(long seasonId, String family, String kind, boolean isCup,
                                               String sheetWording) {
        Optional<Long> existing = db.sql("""
                        SELECT id FROM championship_group
                        WHERE season_id = :seasonId AND family = :family AND kind IS NOT DISTINCT FROM :kind
                        """)
                .param("seasonId", seasonId).param("family", family).param("kind", kind)
                .query(Long.class).optional();
        if (existing.isPresent()) {
            return existing.get();
        }
        // The series' own wording for the kind carries over from last season's
        // group ("Entrants" stays "Entrants" without re-typing it every year).
        String kindLabel = inheritedKindLabel(seasonId, family, kind);
        if (kindLabel == null) {
            kindLabel = sheetWording;
        }
        String label = family + " — " + (kindLabel != null ? kindLabel : kind == null || kind.isBlank()
                ? "Overall"
                : kind.charAt(0) + kind.substring(1).toLowerCase());
        int ordinal = db.sql("SELECT COALESCE(max(ordinal), 0) + 1 FROM championship_group WHERE season_id = :seasonId")
                .param("seasonId", seasonId).query(Integer.class).single();
        return db.sql("""
                        INSERT INTO championship_group (season_id, family, kind, label, kind_label, ordinal, is_cup)
                        VALUES (:seasonId, :family, :kind, :label, :kindLabel, :ordinal, :isCup)
                        RETURNING id
                        """)
                .param("seasonId", seasonId).param("family", family).param("kind", kind)
                .param("label", label).param("kindLabel", kindLabel).param("ordinal", ordinal).param("isCup", isCup)
                .query(Long.class).single();
    }

    /**
     * The kind wording the series last used for this kind, or null. Set once in
     * Manage → Series on any season's group, it follows the series forward:
     * the most recent season's group of the same kind wins, preferring the
     * same family, then the primary over a cup (a cup can word its kind
     * differently from the primary, and must not lend that wording to a new
     * primary).
     * Package-private for its test.
     */
    String inheritedKindLabel(long seasonId, String family, String kind) {
        if (kind == null || kind.isBlank()) {
            return null;
        }
        return db.sql("""
                        SELECT g.kind_label
                        FROM championship_group g
                                 JOIN season s ON s.id = g.season_id
                        WHERE s.series_id = (SELECT series_id FROM season WHERE id = :seasonId)
                          AND g.kind = :kind
                          AND g.kind_label IS NOT NULL
                        ORDER BY (g.family = :family) DESC, g.is_cup, s.year DESC, g.id DESC
                        LIMIT 1
                        """)
                .param("seasonId", seasonId).param("family", family).param("kind", kind)
                .query(String.class).optional().orElse(null);
    }

    /**
     * (Re)assign each event in the season a 1-based round_ordinal by calendar
     * order. Idempotent: called after any event is created so the ordinal — the
     * axis for pre-round standings snapshots — stays correct as rounds arrive.
     */
    /**
     * Number the season's rounds by date. A round is an event with
     * {@code is_round} set — every event unless it was marked otherwise (the
     * Roar Before the 24, a test day, a prologue: the Al Kamel planner's
     * pre-season verdict, stamped at commit). The others sit on the calendar
     * with no number, so the recap's round N still lines up with the
     * standings' round N. The shape of an event's sessions is deliberately
     * not consulted: a real round is qualifying-only from its Saturday import
     * until its race lands, and must keep its number throughout.
     */
    /** Mark an event as a round or not — the admin's override for what the
     *  Al Kamel planner's pre-season verdict (or nothing) set — and renumber
     *  the season so the rounds close up around it. */
    public void setEventRound(long eventId, boolean isRound) {
        int updated = db.sql("UPDATE event SET is_round = :r WHERE id = :id")
                .param("r", isRound).param("id", eventId).update();
        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such event");
        }
        renumberSeasonRounds(seasonIdOfEvent(eventId));
    }

    public void renumberSeasonRounds(long seasonId) {
        // Same-day rounds (IMSA Esports runs GTP at one track and GTD at
        // another on one evening) order by the source's own round number when
        // one was recorded (event.source_round, V56), else by creation.
        db.sql("""
                        WITH ranked AS (
                            SELECT e.id, row_number() OVER (
                                ORDER BY e.event_date NULLS LAST, e.source_round NULLS LAST, e.id) AS rn
                            FROM event e
                            WHERE e.season_id = :seasonId AND e.is_round
                        )
                        UPDATE event e SET round_ordinal = ranked.rn
                        FROM ranked WHERE ranked.id = e.id
                        """)
                .param("seasonId", seasonId)
                .update();
        db.sql("UPDATE event SET round_ordinal = NULL WHERE season_id = :seasonId AND NOT is_round")
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

    /**
     * The season a reviewer-described event lands in: the chosen series (existing
     * or newly named) at the year of the date the reviewer typed. Only for files
     * whose payload carries no session metadata — everything the event needs
     * comes from the target, so it is validated here rather than trusted.
     */
    private long seasonForDescribedEvent(ImportTarget target, String fileLabel) {
        if (target.eventName() == null || target.eventName().isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    fileLabel + " has no event/session metadata; choose an existing event in review, "
                    + "or name the new event to create");
        }
        if (target.eventDate() == null) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    fileLabel + " has no date of its own; give the new event a date in review");
        }
        if (target.seriesId() == null && (target.newSeriesName() == null || target.newSeriesName().isBlank())) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Choose the series the new event belongs to");
        }
        long seriesId = resolveSeriesId(target);
        return findOrCreateSeason(seriesId, target.eventDate().getYear());
    }

    /** Create the event a metadata-less file described in review (see
     *  {@link #seasonForDescribedEvent}) and slot it into the season's round order. */
    private long createDescribedEvent(long seasonId, ImportTarget target) {
        String circuit = target.circuitName() == null || target.circuitName().isBlank()
                ? null : target.circuitName().trim();
        long eventId = createEvent(seasonId, target.eventName().trim(), circuit, null, null, target.eventDate());
        renumberSeasonRounds(seasonId);
        return eventId;
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
        // A source can print a car with no class at all — the 2024 Miami F1-weekend
        // grid lists #74 with neither class nor time. The class then comes from
        // what the event already knows (the qualifying imported before it); a
        // car nothing has classified yet is a real gap, named rather than a
        // NOT NULL violation.
        if (className == null || className.isBlank()) {
            // The class the event already has for the car. It has to be supplied on
            // the INSERT itself: Postgres checks NOT NULL on the proposed row before
            // ON CONFLICT gets a say, so a COALESCE in the update clause alone is
            // not enough.
            className = db.sql("SELECT class_name FROM entry WHERE event_id = :e AND car_number = :n AND class_name IS NOT NULL")
                    .param("e", eventId).param("n", number).query(String.class).optional().orElse(null);
            boolean known = className != null;
            if (!known) {
                // Not on this event yet (#74 skipped qualifying, so the grid is the
                // first sheet naming it): borrow the class the car ran earlier in
                // the season. A placeholder only — the race results committed after
                // the grid carry the class and overwrite it.
                className = db.sql("""
                                SELECT en.class_name FROM entry en
                                         JOIN event ev ON ev.id = en.event_id
                                WHERE ev.season_id = (SELECT season_id FROM event WHERE id = :e)
                                  AND en.car_number = :n AND en.class_name IS NOT NULL
                                ORDER BY ev.event_date DESC NULLS LAST, ev.id DESC
                                LIMIT 1
                                """)
                        .param("e", eventId).param("n", number).query(String.class).optional().orElse(null);
            }
            if (!known && className == null) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "Car #" + number + (team != null ? " (" + team + ")" : "")
                        + " has no class in this file and nothing this season classifies it — import a file"
                        + " that does (qualifying, results, entry list) first");
            }
        }
        // is_guest is deliberately untouched on update: it is user-managed state.
        // manufacturer/class_group only overwrite when the source supplies them —
        // a metadata-poor import (grid CSV) must not erase entry-list richness;
        // a class-less row keeps the class the event already has.
        return db.sql("""
                        INSERT INTO entry (event_id, car_number, class_name, team_name, team_id, vehicle, manufacturer, class_group)
                        VALUES (:eventId, :number, :className, :team, :teamId, :vehicle, :manufacturer, :group)
                        ON CONFLICT (event_id, car_number) DO UPDATE
                            SET class_name = COALESCE(EXCLUDED.class_name, entry.class_name),
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

        // Resolve before the DELETE: an initialled name is matched partly
        // against this entry's current lineup.
        List<Long> driverIds = drivers.stream().map(d -> resolveDriver(d, entryId)).toList();
        db.sql("DELETE FROM driver_assignment WHERE entry_id = :entryId").param("entryId", entryId).update();
        for (int i = 0; i < drivers.size(); i++) {
            RaceResultsImport.DriverRow d = drivers.get(i);
            long driverId = driverIds.get(i);
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

    private static final java.util.regex.Pattern INITIALS =
            java.util.regex.Pattern.compile("^(?:\\p{Lu}{1,3}\\.\\s*)+$");

    /**
     * A driver for one seat of an entry. Some timing sheets shorten the given
     * name to an initial ("N. LASTOCHKIN", "A. R. FERNANDES"); taken literally
     * that would mint a second driver beside the full-named one, so an
     * initialled name first looks for a known driver with that surname and
     * initial — preferring one who drove this car number this season, then
     * anyone in this season, then anyone at all — and only when exactly one
     * fits. No unique match falls back to the literal name; re-committing once
     * the full name is known (import that weekend's grid first) replaces it.
     */
    long resolveDriver(RaceResultsImport.DriverRow d, long entryId) {
        if (d.firstName() != null && d.surname() != null
                && INITIALS.matcher(d.firstName().trim()).matches()) {
            Optional<Long> known = findByInitial(d.firstName().trim(), d.surname(), entryId);
            if (known.isPresent()) {
                return known.get();
            }
            // No driver record yet, but the season's standings (JSON, full names)
            // may already list the person: a driver who only ever raced the F1
            // weekends has no full-named result anywhere else to match.
            Optional<String> fromStandings = fullNameFromStandings(d.firstName().trim(), d.surname(), entryId);
            if (fromStandings.isPresent()) {
                // The key ends in the sheet's surname (that is how it matched), so
                // split there — "Kelvin van der Linde" is Kelvin / van der Linde, the
                // way the results JSON spells it, not Kelvin van der / Linde.
                String full = fromStandings.get().trim();
                int cut = full.length() - d.surname().trim().length();
                return findOrCreateDriver(full.substring(0, cut).trim(), full.substring(cut).trim(),
                        d.country(), d.hometown());
            }
        }
        return findOrCreateDriver(d.firstName(), d.surname(), d.country(), d.hometown());
    }

    /** The one standings row of the entry's season whose key ends in this
     *  surname and starts with this initial ("Andre Renha Fernandes" for
     *  "A. R. FERNANDES"); empty unless exactly one fits. */
    private Optional<String> fullNameFromStandings(String initials, String surname, long entryId) {
        List<String> keys = db.sql("""
                        SELECT DISTINCT r.competitor_key
                        FROM standings_row r
                                 JOIN championship c ON c.id = r.championship_id
                                 JOIN championship_group g ON g.id = c.group_id
                        WHERE c.season_id = (SELECT ev.season_id FROM entry e JOIN event ev ON ev.id = e.event_id WHERE e.id = :entryId)
                          AND g.kind = 'DRIVERS'
                          AND lower(right(r.competitor_key, length(:surname) + 1)) = ' ' || lower(:surname)
                          AND upper(left(r.competitor_key, 1)) = :initial
                        """)
                .param("entryId", entryId).param("surname", surname.trim())
                .param("initial", initials.substring(0, 1).toUpperCase(Locale.ROOT))
                .query(String.class).list();
        return keys.size() == 1 ? Optional.of(keys.get(0)) : Optional.empty();
    }

    private Optional<Long> findByInitial(String initials, String surname, long entryId) {
        record Candidate(long id, boolean sameCar, boolean sameSeason) {
        }
        List<Candidate> candidates = db.sql("""
                        WITH ctx AS (
                            SELECT regexp_replace(trim(e.car_number), '^0+(?=\\d)', '') AS car, ev.season_id
                            FROM entry e JOIN event ev ON ev.id = e.event_id
                            WHERE e.id = :entryId
                        ), seen AS (
                            SELECT da.driver_id,
                                   bool_or(regexp_replace(trim(e2.car_number), '^0+(?=\\d)', '') = ctx.car) AS same_car
                            FROM driver_assignment da
                            JOIN entry e2 ON e2.id = da.entry_id
                            JOIN event ev2 ON ev2.id = e2.event_id
                            JOIN ctx ON ctx.season_id = ev2.season_id
                            WHERE da.driver_id IS NOT NULL
                            GROUP BY da.driver_id
                        )
                        SELECT d.id, COALESCE(seen.same_car, false) AS same_car, seen.driver_id IS NOT NULL AS same_season
                        FROM driver d
                        LEFT JOIN seen ON seen.driver_id = d.id
                        WHERE lower(d.surname) = lower(:surname)
                          AND upper(left(d.first_name, 1)) = :initial
                          AND d.first_name !~ '^([A-Z]{1,3}\\.\\s*)+$'
                        """)
                .param("entryId", entryId)
                .param("surname", surname)
                .param("initial", initials.substring(0, 1).toUpperCase(Locale.ROOT))
                .query((rs, i) -> new Candidate(rs.getLong("id"), rs.getBoolean("same_car"),
                        rs.getBoolean("same_season")))
                .list();
        for (java.util.function.Predicate<Candidate> tier : List.<java.util.function.Predicate<Candidate>>of(
                Candidate::sameCar, Candidate::sameSeason, c -> true)) {
            List<Candidate> fit = candidates.stream().filter(tier).toList();
            if (fit.size() == 1) {
                return Optional.of(fit.get(0).id());
            }
            if (fit.size() > 1) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    /** The one driver identity rule: find-or-create on case-insensitive
     *  (first_name, surname), never erasing known country/hometown with a
     *  source that omits them. */
    long findOrCreateDriver(String firstName, String surname, String country, String hometown) {
        record DriverRow(long id, String first, String surname) {
        }
        // Sources split the same person differently — an F1-paddock sheet's
        // "A. R. FERNANDES" resolves to "Andre Renha" / "Fernandes", the timing
        // JSON says "Andre" / "Renha Fernandes" — so a driver whose whole name
        // matches is the same driver, whatever the split.
        Optional<DriverRow> sameName = db.sql("""
                        UPDATE driver SET country = COALESCE(:country, country),
                                          hometown = COALESCE(:hometown, hometown)
                        WHERE id = (SELECT id FROM driver
                                    WHERE lower(regexp_replace(first_name || ' ' || surname, '\\s+', ' ', 'g'))
                                        = lower(regexp_replace(:first || ' ' || :surname, '\\s+', ' ', 'g'))
                                    ORDER BY id LIMIT 1)
                        RETURNING id, first_name, surname
                        """)
                .param("first", firstName == null ? "" : firstName.trim())
                .param("surname", surname == null ? "" : surname.trim())
                .param("country", country)
                .param("hometown", hometown)
                .query((rs, i) -> new DriverRow(rs.getLong("id"), rs.getString("first_name"), rs.getString("surname")))
                .optional();
        if (sameName.isPresent()) {
            DriverRow found = sameName.get();
            // The same split as the stored row gets the usual recasing; a
            // different split keeps the stored spelling, which came first.
            recaseIfShouty(found.id(), found.first(), found.surname(), firstName, surname);
            return found.id();
        }
        Optional<Long> aliased = driverByAlias(
                (firstName == null ? "" : firstName.trim()) + " " + (surname == null ? "" : surname.trim()));
        if (aliased.isPresent()) {
            db.sql("""
                            UPDATE driver SET country = COALESCE(:country, country),
                                              hometown = COALESCE(:hometown, hometown)
                            WHERE id = :id
                            """)
                    .param("country", country)
                    .param("hometown", hometown)
                    .param("id", aliased.get())
                    .update();
            return aliased.get();
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

    /** A spelling retired by a driver merge (driver_alias, V55) resolves to the
     *  driver that absorbed it — consulted only once no driver has the name. */
    private Optional<Long> driverByAlias(String fullName) {
        return db.sql("""
                        SELECT driver_id FROM driver_alias
                        WHERE lower(regexp_replace(trim(alias), '\\s+', ' ', 'g'))
                            = lower(regexp_replace(trim(:name), '\\s+', ' ', 'g'))
                        """)
                .param("name", fullName)
                .query(Long.class)
                .optional();
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
        String remainder = stripTitleDecoration(mainTitle.substring(matchedPrefix.length()).trim());
        int lastSpace = remainder.lastIndexOf(' ');
        if (lastSpace > 0) {
            return new ClassAndKind(remainder.substring(0, lastSpace).trim(),
                    canonicalKind(remainder.substring(lastSpace + 1)));
        }
        return new ClassAndKind(null, remainder.isEmpty() ? null : canonicalKind(remainder));
    }

    /**
     * A title's tail without the words some sheets hang after the kind — "PRO
     * Driver Championship", "P3-1 Bronze Drivers Cup" — so the kind word is
     * last again. Only trailing words go, and only while the word before them
     * is not already a kind; the series prefix ("…SportsCar Championship") has
     * been removed before this is called.
     */
    static String stripTitleDecoration(String title) {
        String t = title == null ? "" : title.trim();
        while (true) {
            int lastSpace = t.lastIndexOf(' ');
            if (lastSpace <= 0) {
                return t;
            }
            String last = t.substring(lastSpace + 1).toLowerCase(Locale.ROOT);
            if (!TITLE_DECORATION.contains(last)) {
                return t;
            }
            t = t.substring(0, lastSpace).trim();
        }
    }

    private static final Set<String> TITLE_DECORATION = Set.of("championship", "championships", "standings", "cup");

    /**
     * A title's kind word as one of the closed kinds. Series word the same
     * thing differently — Mustang Challenge and Carrera Cup rank "Entrants",
     * WeatherTech "Teams" — and the word is only ever the sheet's wording for
     * a kind, not a kind of its own. Unknown words come back upper-cased for
     * the reviewer to sort out.
     */
    static String canonicalKind(String word) {
        String w = word == null ? "" : word.trim().toUpperCase();
        return switch (w) {
            case "TEAM", "TEAMS", "ENTRANT", "ENTRANTS", "CREW", "CREWS" -> "TEAMS";
            case "DRIVER", "DRIVERS" -> "DRIVERS";
            case "MANUFACTURER", "MANUFACTURERS", "MAKE", "MAKES", "BRANDS" -> "MANUFACTURERS";
            default -> w.isEmpty() ? null : w;
        };
    }

    /**
     * The sheet's own wording for its kind when it differs from the kind's
     * plain name — "Entrants" on a TEAMS sheet — to seed a new championship
     * group's wording when the series has none yet; null otherwise.
     */
    static String sheetKindWording(String mainTitle, String kind) {
        if (mainTitle == null || kind == null) {
            return null;
        }
        String[] parts = mainTitle.trim().split("\\s+");
        String word = parts[parts.length - 1];
        if (!kind.equals(canonicalKind(word))) {
            return null;
        }
        String cased = word.substring(0, 1).toUpperCase() + word.substring(1).toLowerCase();
        String plain = kind.charAt(0) + kind.substring(1).toLowerCase();
        return cased.equals(plain) ? null : cased;
    }

    /** Normalize a class spelling for comparison: case- and space-insensitive. */
    private static String normClass(String s) {
        return s == null ? null : s.toLowerCase().replace(" ", "");
    }

    /** The season's canonical classes: the distinct entry (entry-list) classes. */
    private List<String> seasonEntryClasses(long seasonId) {
        if (!classSeeding.get().isEmpty()) {
            List<String> seeded = db.sql("SELECT series_id, year FROM season WHERE id = :id").param("id", seasonId)
                    .query((rs, i) -> new SeasonKey(rs.getLong("series_id"), rs.getInt("year")))
                    .optional().map(k -> classSeeding.get().get(k)).orElse(null);
            if (seeded != null) {
                return seeded;
            }
        }
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

    /** MAIN seasons only, mirroring findOrCreateSeason: review guesses must
     *  point at the season a commit would actually land in. */
    private Optional<Long> findSeasonId(long seriesId, int year) {
        return db.sql("SELECT id FROM season WHERE series_id = :seriesId AND year = :year AND kind = 'MAIN'")
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
