package com.pitpass.imports.alkamel;

import com.pitpass.imports.alkamel.AlKamelCatalog.Kind;
import com.pitpass.imports.alkamel.AlKamelCatalog.SessionFolder;
import com.pitpass.imports.alkamel.AlKamelCatalog.SourceFile;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Walks the Al Kamel site into the shapes the import planner needs: years,
 * event folders, the series folders of an event, a series folder's sessions and
 * standings, and the files of one session — with the era-specific detours
 * ({@code 00_Starting Grids/}, the last {@code Hour N/} folder, a points folder)
 * folded in so callers see one flat picture per session.
 *
 * Read-only and DB-free: series folders come back as names for the planner to
 * resolve through the series aliases; nothing is guessed here.
 */
@Component
public class AlKamelIndex {

    private final AlKamelClient client;

    public AlKamelIndex(AlKamelClient client) {
        this.client = client;
    }

    /** A season folder, "17_2017". */
    public record YearFolder(int year, String path, String folderName) {
    }

    /** An event weekend folder, "06_Long Beach Street Circuit". The number is
     *  a posting order, not a round. */
    public record EventFolder(int year, String path, String folderName, String name, String modified) {
    }

    /** A series folder inside an event, "01_IMSA WeatherTech SportsCar Championship". */
    public record SeriesFolder(String path, String folderName, String name, String modified) {
    }

    /** A session folder with its parsed stamp and label. */
    public record SessionRef(String path, SessionFolder folder, String modified) {
    }

    /**
     * What an event folder holds. Three Carrera Cup NA weekends in 2023–24 were
     * posted with the session folders directly under the event, no series
     * folder at all — those come back as {@code looseSessions} for the planner
     * to attribute.
     */
    public record EventListing(List<SeriesFolder> series, List<SessionRef> looseSessions) {
    }

    /** A series folder's sessions and the series-level documents beside them. */
    public record SeriesContents(List<SessionRef> sessions, List<SourceFile> standings,
                                 List<SourceFile> entryLists) {
    }

    /** Every candidate file of a session, by kind, after descending into the
     *  grids and last-hour subfolders. */
    public record SessionFiles(Map<Kind, List<SourceFile>> byKind) {

        public List<SourceFile> of(Kind kind) {
            return byKind.getOrDefault(kind, List.of());
        }

        public Optional<SourceFile> best(Kind kind, boolean f1Weekend) {
            return AlKamelCatalog.best(of(kind), kind, f1Weekend);
        }
    }

    public List<YearFolder> years() {
        List<YearFolder> out = new ArrayList<>();
        for (IndexEntry e : client.list("")) {
            if (!e.directory()) {
                continue;
            }
            OptionalInt year = AlKamelCatalog.yearOf(e.name());
            if (year.isPresent()) {
                out.add(new YearFolder(year.getAsInt(), e.href(), e.name()));
            }
        }
        out.sort(Comparator.comparingInt(YearFolder::year));
        return out;
    }

    public Optional<YearFolder> year(int year) {
        return years().stream().filter(y -> y.year() == year).findFirst();
    }

    /** The event folders of a year, in posting order. */
    public List<EventFolder> events(YearFolder year) {
        List<EventFolder> out = new ArrayList<>();
        for (IndexEntry e : client.list(year.path())) {
            if (e.directory()) {
                out.add(new EventFolder(year.year(), year.path() + e.href(), e.name(),
                        AlKamelCatalog.displayName(e.name()), e.modified()));
            }
        }
        return out;
    }

    public EventListing event(String eventPath) {
        return event(eventPath, false);
    }

    public EventListing event(String eventPath, boolean fresh) {
        List<SeriesFolder> series = new ArrayList<>();
        List<SessionRef> loose = new ArrayList<>();
        for (IndexEntry e : fresh ? client.listFresh(eventPath) : client.list(eventPath)) {
            if (!e.directory()) {
                continue;
            }
            Optional<SessionFolder> session = AlKamelCatalog.parseSessionFolder(e.name());
            if (session.isPresent()) {
                loose.add(new SessionRef(eventPath + e.href(), session.get(), e.modified()));
            } else if (!AlKamelCatalog.isPointsFolder(e.name())) {
                series.add(new SeriesFolder(eventPath + e.href(), e.name(),
                        AlKamelCatalog.displayName(e.name()), e.modified()));
            }
        }
        return new EventListing(series, loose);
    }

    public SeriesContents series(String seriesPath) {
        return series(seriesPath, false);
    }

    /**
     * A series folder (or a misfiled event folder — same layout) read as its
     * sessions plus the standings and entry-list files beside them, including
     * those inside a points folder. Sessions come back in start order.
     */
    public SeriesContents series(String seriesPath, boolean fresh) {
        List<SessionRef> sessions = new ArrayList<>();
        List<SourceFile> standings = new ArrayList<>();
        List<SourceFile> entryLists = new ArrayList<>();
        for (IndexEntry e : fresh ? client.listFresh(seriesPath) : client.list(seriesPath)) {
            String path = seriesPath + e.href();
            if (e.directory()) {
                Optional<SessionFolder> session = AlKamelCatalog.parseSessionFolder(e.name());
                if (session.isPresent()) {
                    sessions.add(new SessionRef(path, session.get(), e.modified()));
                } else if (AlKamelCatalog.isPointsFolder(e.name())) {
                    for (IndexEntry f : fresh ? client.listFresh(path) : client.list(path)) {
                        if (!f.directory()) { // "20_Unofficial and Provisional/" inside 2017's points folder
                            standings.add(SourceFile.of(path + f.href(), f, Kind.STANDINGS));
                        }
                    }
                }
                continue;
            }
            AlKamelCatalog.classifySeriesFile(e.name()).ifPresent(kind -> {
                SourceFile file = SourceFile.of(path, e, kind);
                if (kind == Kind.STANDINGS) {
                    standings.add(file);
                } else {
                    entryLists.add(file);
                }
            });
        }
        sessions.sort(Comparator.comparing(s -> s.folder().start()));
        return new SeriesContents(sessions, standings, entryLists);
    }

    public SessionFiles session(String sessionPath) {
        return session(sessionPath, false);
    }

    /**
     * The importable candidates of one session. Grids may sit in a
     * {@code 00_Starting Grids/} subfolder (2024+); an endurance race's results
     * sit in its last {@code Hour N/} subfolder (hours are numbered by name,
     * not by their folder prefix — 2017 Daytona has "04_Hour 1" through
     * "09_Hour 9" and then "Hour 10"…"Hour 24"). Replay zips are ignored.
     */
    public SessionFiles session(String sessionPath, boolean fresh) {
        Map<Kind, List<SourceFile>> byKind = new EnumMap<>(Kind.class);
        List<IndexEntry> entries = fresh ? client.listFresh(sessionPath) : client.list(sessionPath);
        collectFiles(sessionPath, entries, byKind);
        IndexEntry lastHour = null;
        int lastHourNo = -1;
        for (IndexEntry e : entries) {
            if (!e.directory()) {
                continue;
            }
            if (AlKamelCatalog.isGridsFolder(e.name())) {
                collectFiles(sessionPath + e.href(), fresh ? client.listFresh(sessionPath + e.href())
                        : client.list(sessionPath + e.href()), byKind);
                continue;
            }
            OptionalInt hour = AlKamelCatalog.hourOf(e.name());
            if (hour.isPresent() && hour.getAsInt() > lastHourNo) {
                lastHourNo = hour.getAsInt();
                lastHour = e;
            }
        }
        if (lastHour != null && !byKind.containsKey(Kind.RESULTS)) {
            String hourPath = sessionPath + lastHour.href();
            collectFiles(hourPath, fresh ? client.listFresh(hourPath) : client.list(hourPath), byKind);
        }
        return new SessionFiles(byKind);
    }

    private static void collectFiles(String folderPath, List<IndexEntry> entries, Map<Kind, List<SourceFile>> into) {
        for (IndexEntry e : entries) {
            if (e.directory()) {
                continue;
            }
            AlKamelCatalog.classifySessionFile(e.name()).ifPresent(kind ->
                    into.computeIfAbsent(kind, k -> new ArrayList<>())
                            .add(SourceFile.of(folderPath + e.href(), e, kind)));
        }
    }
}
