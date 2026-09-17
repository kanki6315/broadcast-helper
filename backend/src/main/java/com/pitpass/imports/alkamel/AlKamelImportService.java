package com.pitpass.imports.alkamel;

import com.pitpass.imports.ImportFormat;
import com.pitpass.imports.ImportService;
import com.pitpass.imports.ImportService.BatchSummary;
import com.pitpass.imports.SourceContext;
import com.pitpass.imports.alkamel.AlKamelCatalog.Kind;
import com.pitpass.imports.alkamel.AlKamelCatalog.SessionType;
import com.pitpass.imports.alkamel.AlKamelCatalog.SourceFile;
import com.pitpass.imports.alkamel.AlKamelIndex.EventFolder;
import com.pitpass.imports.alkamel.AlKamelIndex.EventListing;
import com.pitpass.imports.alkamel.AlKamelIndex.SeriesContents;
import com.pitpass.imports.alkamel.AlKamelIndex.SeriesFolder;
import com.pitpass.imports.alkamel.AlKamelIndex.SessionFiles;
import com.pitpass.imports.alkamel.AlKamelIndex.SessionRef;
import com.pitpass.imports.alkamel.AlKamelIndex.YearFolder;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Plans and runs imports from the Al Kamel results site (docs/ALKAMEL_IMPORT.md).
 *
 * A plan is listings only: for one season it names, per weekend and series,
 * the file the importer would read for each session and kind, the standings
 * and entry list beside them, and which weekends already exist here. Staging
 * downloads the files an admin picked and hands them to the ordinary import
 * pipeline with a {@link SourceContext}, one weekend per call, so the browser
 * can show progress and stop between weekends.
 *
 * Series are resolved only through what the admin has recorded — a series'
 * name, abbreviation or alias must equal the folder's name. A folder that
 * matches nothing is reported, never guessed.
 */
@Service
public class AlKamelImportService {

    private final AlKamelIndex index;
    private final AlKamelClient client;
    private final ImportService imports;
    private final JdbcClient db;

    public AlKamelImportService(AlKamelIndex index, AlKamelClient client, ImportService imports, JdbcClient db) {
        this.index = index;
        this.client = client;
        this.imports = imports;
        this.db = db;
    }

    // ------------------------------------------------------------------ plan

    /**
     * One file the importer could read. {@code format} is the parser family, or
     * null when the site only has this kind in a format nothing reads (a flags
     * PDF, a results PDF off a Formula 1 weekend) — then {@code note} says so.
     * {@code recommended} is the plan's default tick.
     */
    public record PlanFile(String path, String name, String kind, String format, String status, int amendment,
                           String modified, boolean recommended, String note, String state) {

        /** The same file with its refresh state, ticked unless nothing changed. */
        PlanFile withState(String state) {
            return new PlanFile(path, name, kind, format, status, amendment, modified,
                    recommended && !"UNCHANGED".equals(state), note, state);
        }
    }

    /** An event refresh's verdict per file: nothing committed for this session
     *  and kind yet, a different or newer copy than the last commit, or the
     *  very file already committed. Null on a season plan. */
    public static final String NEW = "NEW";
    public static final String UPDATED = "UPDATED";
    public static final String UNCHANGED = "UNCHANGED";

    /** A session folder with the chosen file per kind (null when the folder has
     *  no candidate of that kind at all). */
    public record PlanSession(String path, LocalDateTime start, String label, String type,
                              PlanFile results, PlanFile grid, PlanFile flags) {
    }

    /**
     * One weekend of one series — the unit the browser stages. {@code loose}
     * marks a weekend posted without a series folder, whose series the admin
     * must pick. {@code finalStandings} marks the series' last weekend of the
     * season that has a standings file: the one a past-season import stages by
     * default, since every weekend's snapshot would just overwrite the last.
     */
    public record PlanWeekend(String sourceEvent, String eventPath, String seriesPath, String eventName, int year,
                              String seriesFolder, Long seriesId, String seriesName, boolean loose,
                              boolean preseason, boolean f1Weekend, Long existingEventId, List<PlanSession> sessions,
                              List<PlanFile> standings, boolean finalStandings, PlanFile entryList,
                              String error) {
    }

    /**
     * A weekend that is not a round: the Roar Before the 24, a test, a
     * prologue. Offered unticked — the Roar's qualifying race is importable
     * (it set Daytona's grid) but it is no round, and the round numbering
     * leaves it out by the same name.
     */
    static boolean isPreseason(String eventFolderName) {
        String n = eventFolderName.toLowerCase(Locale.ROOT);
        return n.contains("roar") || n.contains("test") || n.contains("prologue");
    }

    public record YearPlan(int year, List<PlanWeekend> weekends, List<String> unmatchedSeriesFolders) {
    }

    public List<Integer> years() {
        return index.years().stream().map(YearFolder::year).toList();
    }

    /**
     * The season's importable files, weekend by weekend. With a series id only
     * that series' weekends (and the loose ones) are planned; unmatched series
     * folders are reported in all-series mode only, where they matter.
     */
    public YearPlan planYear(int year, Long onlySeriesId) {
        YearFolder yearFolder = index.year(year).orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND, "Al Kamel publishes no " + year + " folder"));
        List<PlanWeekend> weekends = new ArrayList<>();
        LinkedHashSet<String> unmatched = new LinkedHashSet<>();
        for (EventFolder event : index.events(yearFolder)) {
            String sourceEvent = yearFolder.folderName() + "/" + event.folderName();
            EventListing listing;
            try {
                listing = index.event(event.path());
            } catch (ResponseStatusException e) {
                weekends.add(failedWeekend(sourceEvent, event, e.getReason()));
                continue;
            }
            for (SeriesFolder sf : listing.series()) {
                Optional<SeriesRow> series = resolveSeries(sf.name());
                if (series.isEmpty()) {
                    if (onlySeriesId == null) {
                        unmatched.add(sf.name());
                    }
                    continue;
                }
                if (onlySeriesId != null && series.get().id() != onlySeriesId) {
                    continue;
                }
                weekends.add(planWeekend(sourceEvent, event, sf.path(), sf.name(), series.get(), false, false));
            }
            if (!listing.looseSessions().isEmpty()) {
                // A weekend posted without series folders. Planning one series, it is
                // that series' (so an already-imported one is recognised); planning
                // all, the admin says whose it is.
                SeriesRow assumed = onlySeriesId == null ? null : seriesRow(onlySeriesId);
                weekends.add(planWeekend(sourceEvent, event, event.path(), null, assumed, true, false));
            }
        }
        markFinalStandings(weekends);
        return new YearPlan(year, weekends, new ArrayList<>(unmatched));
    }

    record SeriesRow(long id, String name) {
    }

    private SeriesRow seriesRow(long seriesId) {
        return db.sql("SELECT id, name FROM series WHERE id = :id").param("id", seriesId)
                .query((rs, i) -> new SeriesRow(rs.getLong("id"), rs.getString("name")))
                .optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such series"));
    }

    /** A series folder's display name against the recorded names, exactly. */
    Optional<SeriesRow> resolveSeries(String folderName) {
        return db.sql("""
                        SELECT id, name FROM series WHERE lower(name) = lower(:n) OR lower(abbreviation) = lower(:n)
                        UNION
                        SELECT s.id, s.name FROM series s JOIN series_alias a ON a.series_id = s.id
                        WHERE lower(a.alias) = lower(:n)
                        """)
                .param("n", folderName.trim())
                .query((rs, i) -> new SeriesRow(rs.getLong("id"), rs.getString("name")))
                .optional();
    }

    /** One weekend of one series, read from {@code seriesPath} (the event folder
     *  itself for a weekend posted without series folders). {@code fresh}
     *  bypasses the listing cache — an event refresh's whole point. */
    private PlanWeekend planWeekend(String sourceEvent, EventFolder event, String seriesPath, String seriesFolder,
                                    SeriesRow series, boolean loose, boolean fresh) {
        SeriesContents contents;
        try {
            contents = index.series(seriesPath, fresh);
        } catch (ResponseStatusException e) {
            return failedWeekend(sourceEvent, event, e.getReason());
        }
        List<SessionRef> scoring = contents.sessions().stream()
                .filter(s -> s.folder().type() == SessionType.QUALIFYING || s.folder().type() == SessionType.RACE)
                .toList();
        Map<SessionRef, SessionFiles> files = new LinkedHashMap<>();
        for (SessionRef ref : scoring) {
            try {
                files.put(ref, index.session(ref.path(), fresh));
            } catch (ResponseStatusException e) {
                return failedWeekend(sourceEvent, event, e.getReason());
            }
        }
        // A Formula 1 support weekend publishes its results as F1-paddock PDFs
        // and nothing machine-readable — the only case a results PDF is read.
        boolean anyMachine = files.values().stream()
                .anyMatch(f -> AlKamelCatalog.best(f.of(Kind.RESULTS), Kind.RESULTS, false).isPresent());
        boolean anyPdf = files.values().stream()
                .anyMatch(f -> f.of(Kind.RESULTS).stream().anyMatch(x -> "PDF".equals(x.extension())));
        boolean f1 = !anyMachine && anyPdf;

        List<PlanSession> sessions = new ArrayList<>();
        List<SourceFile> entryLists = new ArrayList<>(contents.entryLists());
        for (Map.Entry<SessionRef, SessionFiles> e : files.entrySet()) {
            SessionRef ref = e.getKey();
            SessionFiles sf = e.getValue();
            entryLists.addAll(sf.of(Kind.ENTRY_LIST));
            sessions.add(new PlanSession(ref.path(), ref.folder().start(), ref.folder().label(),
                    ref.folder().type().name(),
                    planFile(sf.of(Kind.RESULTS), Kind.RESULTS, f1),
                    ref.folder().type() == SessionType.RACE ? planFile(sf.of(Kind.GRID), Kind.GRID, f1) : null,
                    planFile(sf.of(Kind.FLAGS), Kind.FLAGS, f1)));
        }
        Long existing = series == null ? null : findExistingEvent(series.id(), event.year(), sourceEvent);
        return new PlanWeekend(sourceEvent, event.path(), seriesPath, event.name(), event.year(), seriesFolder,
                series == null ? null : series.id(), series == null ? null : series.name(), loose,
                isPreseason(event.folderName()), f1, existing,
                sessions, planStandings(contents.standings()), false, planFile(entryLists, Kind.ENTRY_LIST, f1),
                null);
    }

    private static PlanWeekend failedWeekend(String sourceEvent, EventFolder event, String reason) {
        return new PlanWeekend(sourceEvent, event.path(), null, event.name(), event.year(), null, null, null, false,
                isPreseason(event.folderName()), false, null, List.of(), List.of(), false, null, reason);
    }

    /** The one file to read for a kind, or the fact that nothing reads what is there. */
    private static PlanFile planFile(List<SourceFile> candidates, Kind kind, boolean f1) {
        if (candidates.isEmpty()) {
            return null;
        }
        Optional<SourceFile> best = AlKamelCatalog.best(candidates, kind, f1);
        if (best.isPresent()) {
            SourceFile f = best.get();
            return new PlanFile(f.path(), f.name(), kind.name(),
                    AlKamelCatalog.formatFor(kind, f.extension(), f1).orElseThrow().name(),
                    f.status().name(), f.amendment(), f.modified(), true, null, null);
        }
        SourceFile shown = candidates.stream().min(AlKamelCatalog.preference()).orElseThrow();
        String formats = String.join("/", candidates.stream().map(SourceFile::extension).distinct().sorted().toList());
        return new PlanFile(shown.path(), shown.name(), kind.name(), null, shown.status().name(),
                shown.amendment(), shown.modified(), false, formats + " only — no parser reads it", null);
    }

    private static final Pattern STATUS_WORDS = Pattern.compile(
            "(?i)\\b(official|provisional|unofficial|revised|amended)\\b\\s*\\d*");

    /**
     * Every standings file, ticked by default where it is the best copy of its
     * sheet. Copies share a name stem — "- Official" and "- Revised Official"
     * of the Championship Points PDF; the same "IWSC 01 GTP Drivers.json" in
     * the Official and the Provisional points folder — and only the best copy
     * (status, then amendment, then newest) is ticked. Different stems are
     * different sheets ("Championship Points" vs "TPNAEC Points"), each ticked.
     * PDFs are ticked only where the weekend has no JSON at all, and award
     * sheets (Front Runner, Trueman-Akin, Sustainability) are listed but never
     * ticked — they are not championships this tool tracks.
     */
    static List<PlanFile> planStandings(List<SourceFile> standings) {
        boolean anyJson = standings.stream().anyMatch(f -> "JSON".equals(f.extension()));
        Map<String, SourceFile> bestByStem = new HashMap<>();
        for (SourceFile f : standings) {
            if (AlKamelCatalog.formatFor(Kind.STANDINGS, f.extension(), false).isEmpty()) {
                continue;
            }
            bestByStem.merge(f.extension() + ":" + stem(f.name()), f,
                    (a, b) -> AlKamelCatalog.preference().compare(a, b) <= 0 ? a : b);
        }
        List<PlanFile> out = new ArrayList<>();
        for (SourceFile f : standings.stream().sorted(AlKamelCatalog.preference()).toList()) {
            Optional<ImportFormat> format = AlKamelCatalog.formatFor(Kind.STANDINGS, f.extension(), false);
            if (format.isEmpty()) {
                continue; // the CSV twin of a points JSON
            }
            boolean award = isAward(f.name());
            boolean bestCopy = bestByStem.get(f.extension() + ":" + stem(f.name())) == f;
            // Among PDFs only the series' own points sheet is ticked: the cup sheets
            // beside it (IMEC, TPNAEC, "Sebring 12H") lay their points out per
            // checkpoint — Hour 6 / 12 / 18 / Finish — which the points parser does
            // not read, so they are offered, not assumed.
            boolean cupSheet = "PDF".equals(f.extension()) && !award && !isMainPointsSheet(f.name());
            boolean recommended = !award && !cupSheet && bestCopy && ("JSON".equals(f.extension()) || !anyJson);
            out.add(new PlanFile(f.path(), f.name(), Kind.STANDINGS.name(), format.get().name(),
                    f.status().name(), f.amendment(), f.modified(), recommended,
                    award ? "award sheet" : cupSheet ? "cup sheet — tick to try; its layout is untested" : null,
                    null));
        }
        return out;
    }

    private static String stem(String name) {
        String n = name.toLowerCase(Locale.ROOT).replaceAll("\\.[^.]+$", "");
        n = STATUS_WORDS.matcher(n).replaceAll("");
        return n.replaceAll("^\\d+_", "").replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
    }

    /** "00_Championship Points - Official.pdf" (2017 spells it "Champonship");
     *  a cup's sheet carries its code first ("00_IMEC Championship Points"). */
    static boolean isMainPointsSheet(String name) {
        String s = stem(name);
        return s.equals("championship points") || s.equals("champonship points");
    }

    private static boolean isAward(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.contains("award") || n.contains("sustainability") || n.contains("trueman") || n.contains("akin");
    }

    /** Per series (loose weekends count as their own), the latest weekend with
     *  standings gets the final-standings mark. */
    private static void markFinalStandings(List<PlanWeekend> weekends) {
        Map<Object, Integer> latest = new HashMap<>();
        for (int i = 0; i < weekends.size(); i++) {
            PlanWeekend w = weekends.get(i);
            if (w.standings().isEmpty()) {
                continue;
            }
            Object key = w.seriesId() != null ? w.seriesId() : "loose";
            Integer prev = latest.get(key);
            if (prev == null || latestStart(w).compareTo(latestStart(weekends.get(prev))) >= 0) {
                latest.put(key, i);
            }
        }
        for (Integer i : latest.values()) {
            PlanWeekend w = weekends.get(i);
            weekends.set(i, new PlanWeekend(w.sourceEvent(), w.eventPath(), w.seriesPath(), w.eventName(),
                    w.year(), w.seriesFolder(), w.seriesId(), w.seriesName(), w.loose(), w.preseason(),
                    w.f1Weekend(), w.existingEventId(), w.sessions(), w.standings(), true, w.entryList(), w.error()));
        }
    }

    private static LocalDateTime latestStart(PlanWeekend w) {
        return w.sessions().stream().map(PlanSession::start).max(Comparator.naturalOrder())
                .orElse(LocalDateTime.MIN);
    }

    private Long findExistingEvent(long seriesId, int year, String sourceEvent) {
        return db.sql("""
                        SELECT e.id FROM event e JOIN season s ON s.id = e.season_id
                        WHERE s.series_id = :series AND s.year = :year AND e.source_ref = :ref
                        ORDER BY e.id LIMIT 1
                        """)
                .param("series", seriesId).param("year", year).param("ref", sourceEvent)
                .query(Long.class).optional().orElse(null);
    }

    // ---------------------------------------------------------- event refresh

    /**
     * What an event refresh found. {@code weekend} is the event's folder read
     * fresh with every file's state, or null when the event carries no folder
     * stamp yet — then {@code candidates} are that season's weekends of the
     * same series, nearest the event's date first, for the admin to pick from
     * (the pick is stamped on the event when its batches commit).
     */
    public record EventPlan(long eventId, String eventName, int year, long seriesId, String seriesName,
                            String sourceRef, PlanWeekend weekend, List<PlanWeekend> candidates) {
    }

    private record EventRow(String name, String sourceRef, java.time.LocalDate date, int year, long seriesId,
                            String seriesName) {
    }

    /**
     * Reads the event's weekend folder afresh — every listing bypasses the
     * cache, since "what changed since last time" is the question — and marks
     * each file NEW / UPDATED / UNCHANGED against the batches already committed
     * from that folder. {@code chosenSourceEvent} names the folder when the
     * event has no stamp yet (or the admin overrides it).
     */
    public EventPlan planEvent(long eventId, String chosenSourceEvent) {
        EventRow ev = db.sql("""
                        SELECT e.name, e.source_ref, e.event_date, s.year, s.series_id, sr.name AS series_name
                        FROM event e JOIN season s ON s.id = e.season_id JOIN series sr ON sr.id = s.series_id
                        WHERE e.id = :id
                        """)
                .param("id", eventId)
                .query((rs, i) -> new EventRow(rs.getString("name"), rs.getString("source_ref"),
                        rs.getObject("event_date", java.time.LocalDate.class), rs.getInt("year"),
                        rs.getLong("series_id"), rs.getString("series_name")))
                .optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such event"));
        String ref = chosenSourceEvent != null && !chosenSourceEvent.isBlank() ? chosenSourceEvent.trim()
                : ev.sourceRef();
        if (ref == null) {
            List<PlanWeekend> candidates = planYear(ev.year(), ev.seriesId()).weekends().stream()
                    .filter(w -> w.error() == null && (w.existingEventId() == null || w.existingEventId() == eventId))
                    .sorted(Comparator.comparingLong(w -> daysFrom(w, ev.date())))
                    .limit(6)
                    .toList();
            return new EventPlan(eventId, ev.name(), ev.year(), ev.seriesId(), ev.seriesName(), null, null, candidates);
        }
        YearFolder yearFolder = index.year(ev.year()).orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND, "Al Kamel publishes no " + ev.year() + " folder"));
        EventFolder folder = index.events(yearFolder).stream()
                .filter(f -> (yearFolder.folderName() + "/" + f.folderName()).equals(ref))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "The site no longer lists the folder \"" + ref + "\""));
        EventListing listing = index.event(folder.path(), true);
        PlanWeekend weekend = null;
        for (SeriesFolder sf : listing.series()) {
            Optional<SeriesRow> series = resolveSeries(sf.name());
            if (series.isPresent() && series.get().id() == ev.seriesId()) {
                weekend = planWeekend(ref, folder, sf.path(), sf.name(), series.get(), false, true);
                break;
            }
        }
        if (weekend == null && !listing.looseSessions().isEmpty()) {
            weekend = planWeekend(ref, folder, folder.path(), null, null, true, true);
        }
        if (weekend == null) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "The folder \"" + ref + "\" has no " + ev.seriesName() + " sessions");
        }
        return new EventPlan(eventId, ev.name(), ev.year(), ev.seriesId(), ev.seriesName(), ref,
                withStates(weekend, eventId), List.of());
    }

    private static long daysFrom(PlanWeekend w, java.time.LocalDate date) {
        LocalDateTime start = w.sessions().stream().map(PlanSession::start).min(Comparator.naturalOrder()).orElse(null);
        if (start == null || date == null) {
            return Long.MAX_VALUE / 2;
        }
        return Math.abs(java.time.temporal.ChronoUnit.DAYS.between(date, start.toLocalDate()));
    }

    private record Committed(String url, String modified, String kind) {
    }

    /** Every file's state against what this folder already committed, and the
     *  weekend pinned to the event it refreshes. */
    private PlanWeekend withStates(PlanWeekend w, long eventId) {
        List<Committed> committed = db.sql("""
                        SELECT source_url, source_modified, kind FROM import_batch
                        WHERE source_event = :e AND status = 'COMMITTED' AND source_url IS NOT NULL
                        """)
                .param("e", w.sourceEvent())
                .query((rs, i) -> new Committed(rs.getString("source_url"), rs.getString("source_modified"),
                        rs.getString("kind")))
                .list();
        List<PlanSession> sessions = new ArrayList<>();
        for (PlanSession s : w.sessions()) {
            String prefix = client.url(s.path());
            sessions.add(new PlanSession(s.path(), s.start(), s.label(), s.type(),
                    stateOf(s.results(), prefix, "RACE_RESULTS", committed),
                    stateOf(s.grid(), prefix, "GRID", committed),
                    stateOf(s.flags(), prefix, "FLAGS", committed)));
        }
        String seriesPrefix = client.url(w.seriesPath() != null ? w.seriesPath() : w.eventPath());
        List<PlanFile> standings = w.standings().stream()
                .map(f -> stateOf(f, client.url(f.path()), "STANDINGS", committed))
                .toList();
        return new PlanWeekend(w.sourceEvent(), w.eventPath(), w.seriesPath(), w.eventName(), w.year(),
                w.seriesFolder(), w.seriesId(), w.seriesName(), w.loose(), w.preseason(), w.f1Weekend(), eventId, sessions,
                standings, true, stateOf(w.entryList(), seriesPrefix, "ENTRY_LIST", committed), w.error());
    }

    /**
     * A file's state: the committed batches of its kind under {@code prefix}
     * (the session folder for session files, the series folder for an entry
     * list, the file itself for a standings sheet) decide — none is NEW, the
     * very URL at the very last-modified is UNCHANGED, anything else UPDATED
     * (the Official copy replacing the Provisional, an amendment, a re-post).
     */
    private PlanFile stateOf(PlanFile f, String prefix, String batchKind, List<Committed> committed) {
        if (f == null) {
            return null;
        }
        String url = client.url(f.path());
        List<Committed> same = committed.stream()
                .filter(c -> batchKind.equals(c.kind()) && c.url().startsWith(prefix))
                .toList();
        if (same.isEmpty()) {
            return f.withState(NEW);
        }
        boolean unchanged = same.stream().anyMatch(c -> url.equals(c.url())
                && java.util.Objects.equals(c.modified(), f.modified()));
        return f.withState(unchanged ? UNCHANGED : UPDATED);
    }

    // ----------------------------------------------------------------- stage

    /** A file the admin ticked, with the session it belongs to (null for
     *  series-level files). */
    public record FileRef(String path, String kind, String format, String modified,
                          LocalDateTime sessionStart, String sessionLabel) {
    }

    /** One weekend of one series to stage. */
    public record StageRequest(int year, String sourceEvent, String eventName, Long seriesId, List<FileRef> files) {
    }

    public record Failure(String path, String name, String reason) {
    }

    public record AlKamelImport(int requested, int staged, List<BatchSummary> batches, List<Failure> failures) {
    }

    /**
     * Downloads and stages a weekend's files in the order later commits want
     * them — entry list, qualifying, grids, races, flags, standings — so a
     * grid's lineup exists before the grid and the grid before the race. One
     * bad file is reported and the rest still stage.
     */
    public AlKamelImport stage(StageRequest req) {
        if (req == null || req.files() == null || req.files().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No files chosen");
        }
        if (req.seriesId() == null) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Choose the series these sessions belong to");
        }
        String seriesName = db.sql("SELECT name FROM series WHERE id = :id").param("id", req.seriesId())
                .query(String.class).optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "No such series"));
        List<FileRef> ordered = req.files().stream().sorted(Comparator
                .comparingInt(AlKamelImportService::stageRank)
                .thenComparing(f -> f.sessionStart() == null ? LocalDateTime.MAX : f.sessionStart())).toList();
        List<BatchSummary> batches = new ArrayList<>();
        List<Failure> failures = new ArrayList<>();
        for (FileRef f : ordered) {
            String name = leaf(f.path());
            try {
                ImportFormat format = ImportFormat.valueOf(f.format());
                byte[] bytes = client.download(f.path());
                SourceContext ctx = new SourceContext(client.url(f.path()), f.modified(), req.sourceEvent(),
                        req.year(), seriesName, req.eventName(), f.sessionStart(), f.sessionLabel());
                batches.addAll(imports.stage(filenameOf(f.path()), bytes, format, ctx));
            } catch (ResponseStatusException e) {
                failures.add(new Failure(f.path(), name, e.getReason()));
            } catch (RuntimeException e) {
                failures.add(new Failure(f.path(), name, e.getMessage()));
            }
        }
        return new AlKamelImport(ordered.size(), ordered.size() - failures.size(), batches, failures);
    }

    private static int stageRank(FileRef f) {
        return switch (f.kind()) {
            case "ENTRY_LIST" -> 0;
            case "RESULTS" -> f.sessionLabel() != null && f.sessionLabel().toLowerCase(Locale.ROOT).contains("qualif")
                    ? 1 : 3;
            case "GRID" -> 2;
            case "FLAGS" -> 4;
            default -> 5;
        };
    }

    /** The batch's filename: the decoded path under the season folder, so the
     *  file name (which names the session) is still the leaf and the weekend
     *  and series are readable in the imports table. */
    static String filenameOf(String path) {
        String decoded = URLDecoder.decode(path.replace("+", "%2B"), StandardCharsets.UTF_8);
        int slash = decoded.indexOf('/');
        return slash >= 0 ? decoded.substring(slash + 1) : decoded;
    }

    private static String leaf(String path) {
        String decoded = URLDecoder.decode(path.replace("+", "%2B"), StandardCharsets.UTF_8);
        return decoded.substring(decoded.lastIndexOf('/') + 1);
    }
}
