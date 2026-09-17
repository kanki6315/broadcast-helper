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
                           String modified, boolean recommended, String note) {
    }

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
    public record PlanWeekend(String sourceEvent, String eventPath, String eventName, int year,
                              String seriesFolder, Long seriesId, String seriesName, boolean loose,
                              boolean f1Weekend, Long existingEventId, List<PlanSession> sessions,
                              List<PlanFile> standings, boolean finalStandings, PlanFile entryList,
                              String error) {
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
                weekends.add(planWeekend(sourceEvent, event, sf.name(), series.get(), false,
                        () -> index.series(sf.path())));
            }
            if (!listing.looseSessions().isEmpty()) {
                weekends.add(planWeekend(sourceEvent, event, null, null, true, () -> index.series(event.path())));
            }
        }
        markFinalStandings(weekends);
        return new YearPlan(year, weekends, new ArrayList<>(unmatched));
    }

    record SeriesRow(long id, String name) {
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

    private interface ContentsSupplier {
        SeriesContents get();
    }

    private PlanWeekend planWeekend(String sourceEvent, EventFolder event, String seriesFolder, SeriesRow series,
                                    boolean loose, ContentsSupplier supplier) {
        SeriesContents contents;
        try {
            contents = supplier.get();
        } catch (ResponseStatusException e) {
            return failedWeekend(sourceEvent, event, e.getReason());
        }
        List<SessionRef> scoring = contents.sessions().stream()
                .filter(s -> s.folder().type() == SessionType.QUALIFYING || s.folder().type() == SessionType.RACE)
                .toList();
        Map<SessionRef, SessionFiles> files = new LinkedHashMap<>();
        for (SessionRef ref : scoring) {
            try {
                files.put(ref, index.session(ref.path()));
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
        return new PlanWeekend(sourceEvent, event.path(), event.name(), event.year(), seriesFolder,
                series == null ? null : series.id(), series == null ? null : series.name(), loose, f1, existing,
                sessions, planStandings(contents.standings()), false, planFile(entryLists, Kind.ENTRY_LIST, f1),
                null);
    }

    private static PlanWeekend failedWeekend(String sourceEvent, EventFolder event, String reason) {
        return new PlanWeekend(sourceEvent, event.path(), event.name(), event.year(), null, null, null, false,
                false, null, List.of(), List.of(), false, null, reason);
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
                    f.status().name(), f.amendment(), f.modified(), true, null);
        }
        SourceFile shown = candidates.stream().min(AlKamelCatalog.preference()).orElseThrow();
        String formats = String.join("/", candidates.stream().map(SourceFile::extension).distinct().sorted().toList());
        return new PlanFile(shown.path(), shown.name(), kind.name(), null, shown.status().name(),
                shown.amendment(), shown.modified(), false, formats + " only — no parser reads it");
    }

    private static final Pattern STATUS_WORDS = Pattern.compile(
            "(?i)\\b(official|provisional|unofficial|revised|amended)\\b\\s*\\d*");

    /**
     * Every standings file, ticked by default where it is the best copy of its
     * sheet: each JSON (one championship each from 2024), and among the PDFs
     * the best of each name stem ("Championship Points" and "TPNAEC Points"
     * are different sheets; "- Official" and "- Revised Official" are copies).
     * Award sheets (Front Runner, Trueman-Akin, Sustainability) are listed but
     * not ticked — they are not championships this tool tracks.
     */
    private static List<PlanFile> planStandings(List<SourceFile> standings) {
        boolean anyJson = standings.stream().anyMatch(f -> "JSON".equals(f.extension()));
        Map<String, SourceFile> bestPdfByStem = new HashMap<>();
        for (SourceFile f : standings) {
            if (!"PDF".equals(f.extension())) {
                continue;
            }
            String stem = stem(f.name());
            bestPdfByStem.merge(stem, f, (a, b) -> AlKamelCatalog.preference().compare(a, b) <= 0 ? a : b);
        }
        List<PlanFile> out = new ArrayList<>();
        for (SourceFile f : standings.stream().sorted(AlKamelCatalog.preference()).toList()) {
            Optional<ImportFormat> format = AlKamelCatalog.formatFor(Kind.STANDINGS, f.extension(), false);
            if (format.isEmpty()) {
                continue; // the CSV twin of a points JSON
            }
            boolean award = isAward(f.name());
            boolean recommended = !award && ("JSON".equals(f.extension())
                    ? true
                    : !anyJson && bestPdfByStem.get(stem(f.name())) == f);
            out.add(new PlanFile(f.path(), f.name(), Kind.STANDINGS.name(), format.get().name(),
                    f.status().name(), f.amendment(), f.modified(), recommended,
                    award ? "award sheet" : null));
        }
        return out;
    }

    private static String stem(String name) {
        String n = name.toLowerCase(Locale.ROOT).replaceAll("\\.[^.]+$", "");
        n = STATUS_WORDS.matcher(n).replaceAll("");
        return n.replaceAll("^\\d+_", "").replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
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
            weekends.set(i, new PlanWeekend(w.sourceEvent(), w.eventPath(), w.eventName(), w.year(),
                    w.seriesFolder(), w.seriesId(), w.seriesName(), w.loose(), w.f1Weekend(), w.existingEventId(),
                    w.sessions(), w.standings(), true, w.entryList(), w.error()));
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
