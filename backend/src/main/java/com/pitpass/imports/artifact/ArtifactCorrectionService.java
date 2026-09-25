package com.pitpass.imports.artifact;

import com.pitpass.drivers.DriverAdminController;
import com.pitpass.imports.ImportService;
import com.pitpass.imports.StandingsImport;
import com.pitpass.teams.TeamAdminController;
import com.pitpass.teams.TeamAssignmentService;
import com.pitpass.teams.TeamResolver;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.text.Normalizer;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The IMSA Esports correction: iRacing hosted-session results are imported
 * first (they carry the qualifying sessions and lap data), then this applies
 * the official classification and standings artifactracing.com publishes after
 * the stewards' post-race penalties. It never creates events, sessions or
 * results for a round iRacing hasn't supplied — it corrects them.
 *
 * <p>Cars are paired by their drivers, not their numbers: a team that
 * registered the wrong iRacing car number races under it, and the site carries
 * the right one. The site's driver names are the iRacing display names, so an
 * exact (case- and spacing-insensitive) name match pairs a car on its own; a
 * near-miss spelling, a car-number-only match or anything ambiguous is
 * flagged and waits for the reviewer. The site is authoritative for the
 * paired entry's number and team name and for the race result's
 * classification; what the site doesn't publish (pit stops, lap speeds, the
 * qualifying session) stays as iRacing had it.
 *
 * <p>{@link #plan} is read-only and returns every change and every decision
 * still needed; {@link #apply} re-plans with the reviewer's decisions and
 * writes it all in one transaction, refusing while anything still blocks.
 */
@Service
public class ArtifactCorrectionService {

    /** Gap readings closer than this are the two sources rounding differently. */
    private static final double GAP_TOLERANCE_SECONDS = 0.002;
    /** Event dates on the two sides may straddle midnight UTC; rounds are weeks apart. */
    private static final int EVENT_DATE_WINDOW_DAYS = 3;
    static final String SOURCE_PREFIX = "artifact:";

    private final JdbcClient db;
    private final ArtifactClient site;
    private final ImportService imports;
    private final DriverAdminController drivers;
    private final TeamResolver teams;
    private final TeamAssignmentService teamAssignments;
    private final TeamAdminController teamAdmin;
    private final TransactionTemplate tx;

    public ArtifactCorrectionService(JdbcClient db, ArtifactClient site, ImportService imports,
                                     DriverAdminController drivers, TeamResolver teams,
                                     TeamAssignmentService teamAssignments, TeamAdminController teamAdmin,
                                     PlatformTransactionManager transactions) {
        this.db = db;
        this.site = site;
        this.imports = imports;
        this.drivers = drivers;
        this.teams = teams;
        this.teamAssignments = teamAssignments;
        this.teamAdmin = teamAdmin;
        this.tx = new TransactionTemplate(transactions);
    }

    /* ------------------------------------------------------------------ */
    /* API shapes                                                           */
    /* ------------------------------------------------------------------ */

    public record LeagueOption(String id, String name, String seriesName, Integer seasonNumber, boolean active) {
    }

    /**
     * What to correct and the reviewer's decisions so far. Everything past
     * seasonId is optional: a first plan sends none of it.
     *
     * @param eventOverrides  site event id → Pit Pass event id, where the automatic match is wrong or missing
     * @param pairings        site result id → entry id, confirming a flagged pairing or choosing one;
     *                        a null value leaves that site row out of the correction
     * @param consolidations  near-miss spellings to adopt: the Pit Pass driver takes the site's name
     * @param dropEntryIds    entries the site doesn't classify, to delete from their event
     * @param classMapping    site class → Pit Pass class, where the paired entries don't settle it
     * @param importStandings false to correct results only
     */
    public record CorrectionRequest(String leagueId, long seasonId, Map<String, Long> eventOverrides,
                                    Map<String, Long> pairings, List<Consolidation> consolidations,
                                    List<Long> dropEntryIds, Map<String, String> classMapping,
                                    Boolean importStandings) {
        Map<String, Long> overrides() {
            return eventOverrides == null ? Map.of() : eventOverrides;
        }

        Map<String, Long> pairs() {
            return pairings == null ? Map.of() : pairings;
        }

        Set<Long> drops() {
            return dropEntryIds == null ? Set.of() : new HashSet<>(dropEntryIds);
        }

        Map<String, String> classes() {
            return classMapping == null ? Map.of() : classMapping;
        }
    }

    public record Consolidation(long driverId, String name) {
    }

    public record Plan(String leagueId, String leagueName, long seasonId, String seasonLabel,
                       List<RoundPlan> rounds, List<ClassPlan> classes, List<StandingsPlan> standings,
                       List<TeamFold> teamFolds, List<String> warnings, List<String> blocking, boolean ready) {
    }

    /** eventMatch: SOURCE (stamped by an earlier correction), DRIVERS, OVERRIDE, or NONE. */
    public record RoundPlan(int round, String siteEventId, String track, Instant raceStart, int siteResults,
                            EventRef event, String eventMatch, Long raceSessionId, List<CarPlan> cars,
                            List<EntryRef> unpairedEntries, List<String> blocking) {
    }

    public record EventRef(long id, String name, LocalDate date, Integer roundOrdinal) {
    }

    /**
     * One site row and the entry it corrects. match: EXACT_DRIVERS, CONFIRMED
     * (the reviewer's pairing), CLOSE_DRIVERS or CAR_NUMBER (both need
     * confirming when they contradict something), AMBIGUOUS, NONE, SKIPPED.
     */
    public record CarPlan(String siteResultId, String carNumber, String className, String teamName,
                          List<String> drivers, int overallPosition, int classPosition,
                          Long entryId, String entryCarNumber, String entryTeamName, String entryClassName,
                          String match, boolean needsConfirmation, List<EntryOption> candidates,
                          List<LineupLine> lineup, List<Change> changes, Integer classPositionDelta) {
    }

    public record EntryOption(long entryId, String carNumber, String teamName, List<String> drivers) {
    }

    /** match: EXACT, CLOSE (a consolidation candidate), MISSING (site only), EXTRA (Pit Pass only). */
    public record LineupLine(String siteName, Long driverId, String pitPassName, String match) {
    }

    public record Change(String field, String from, String to) {
    }

    /** An entry no site row pairs with. mustDrop: it holds a race result or a
     *  number a paired car is taking, so leaving it would contradict the site. */
    public record EntryRef(long entryId, String carNumber, String teamName, String className,
                           List<String> drivers, boolean hasRaceResult, boolean mustDrop, boolean dropping) {
    }

    /** source: PAIRED (the entries this class's cars paired with), MAPPED (the reviewer), NONE. */
    public record ClassPlan(String siteClass, String pitPassClass, String source) {
    }

    public record StandingsPlan(String siteClass, String pitPassClass, String championshipName,
                                int rows, int rounds, List<String> discrepancies) {
    }

    /**
     * A team the iRacing import created under a car's iRacing name ("Porsche
     * Coanda $91") that the correction leaves with no entries anywhere. It
     * folds into the site's registered team, its spelling kept as an alias,
     * so the next iRacing import resolves straight to the right team.
     */
    public record TeamFold(long fromTeamId, String fromName, String toName) {
    }

    public record ApplyResult(int roundsCorrected, int resultsWritten, int entriesRenumbered,
                              int teamsRenamed, int teamsFolded, int entriesDropped, int driversConsolidated,
                              int standingsChampionships) {
    }

    /* ------------------------------------------------------------------ */
    /* Leagues                                                              */
    /* ------------------------------------------------------------------ */

    public List<LeagueOption> leagues() {
        return site.leagues().stream()
                .sorted(Comparator.comparing((ArtifactClient.League l) -> Objects.requireNonNullElse(l.seasonNumber(), 0))
                        .reversed())
                .map(l -> new LeagueOption(l.id(), l.name(), l.seriesName(), l.seasonNumber(), l.isActive()))
                .toList();
    }

    /* ------------------------------------------------------------------ */
    /* Plan                                                                 */
    /* ------------------------------------------------------------------ */

    /** Everything the site says, laid against the season. Read-only. */
    public Plan plan(CorrectionRequest req) {
        return plan(req, fetch(req.leagueId())).plan();
    }

    private record SiteData(ArtifactClient.League league, List<ArtifactClient.Event> events,
                            Map<String, List<ArtifactClient.Result>> results,
                            List<ArtifactClient.Standing> standings) {
    }

    private SiteData fetch(String leagueId) {
        ArtifactClient.League league = site.leagues().stream()
                .filter(l -> l.id().equalsIgnoreCase(Objects.requireNonNullElse(leagueId, "")))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "artifactracing.com has no league " + leagueId));
        List<ArtifactClient.Event> events = site.events(league.id()).stream()
                .sorted(Comparator.comparingInt(ArtifactClient.Event::round))
                .toList();
        Map<String, List<ArtifactClient.Result>> results = new LinkedHashMap<>();
        for (ArtifactClient.Event e : events) {
            results.put(e.id(), site.results(e.id()));
        }
        return new SiteData(league, events, results, site.standings(league.id()));
    }

    /** The plan plus the working state apply needs to write it. */
    private record Planned(Plan plan, List<RoundWork> rounds, Map<String, String> classMap,
                           Map<String, StandingsImport> standings, Set<Consolidation> consolidationCandidates,
                           List<TeamFold> teamFolds) {
    }

    private record RoundWork(ArtifactClient.Event siteEvent, long eventId, long raceSessionId,
                             List<Pairing> pairings, Set<Long> unpairedEntryIds) {
    }

    private record Pairing(ArtifactClient.Result row, Entry entry, Written written) {
    }

    /** The result row as the correction will write it. */
    private record Written(int positionOverall, int positionInClass, String status, boolean notFinished,
                           String notFinishedCause, Integer laps, String gapFirst, String gapPrevious,
                           String fastestLapTime, Integer fastestLapNumber) {
    }

    private Planned plan(CorrectionRequest req, SiteData data) {
        Season season = requireSeason(req.seasonId());
        List<SeasonEvent> seasonEvents = seasonEvents(season.id());
        Map<Long, SeasonEvent> seasonEventsById = seasonEvents.stream()
                .collect(Collectors.toMap(SeasonEvent::id, e -> e));
        List<String> blocking = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Set<Long> drops = req.drops();

        List<RoundPlan> roundPlans = new ArrayList<>();
        List<RoundWork> work = new ArrayList<>();
        Set<Long> claimedEvents = new HashSet<>();
        Set<Consolidation> consolidationCandidates = new HashSet<>();
        Map<String, Map<String, Integer>> classVotes = new LinkedHashMap<>();
        Set<Long> pairedOrUnpairedEntryIds = new HashSet<>();

        for (ArtifactClient.Event se : data.events()) {
            List<ArtifactClient.Result> rows = data.results().getOrDefault(se.id(), List.of()).stream()
                    .sorted(Comparator.comparingInt(r -> intOr(r.overallPosition(), Integer.MAX_VALUE)))
                    .toList();
            if (rows.isEmpty()) {
                continue; // not raced, or not yet published
            }
            String track = trackLabel(se);
            List<String> roundBlocking = new ArrayList<>();

            // Which Pit Pass event this round corrects.
            String eventMatch = "NONE";
            SeasonEvent event = null;
            Long override = req.overrides().get(se.id());
            if (override != null) {
                event = seasonEventsById.get(override);
                eventMatch = "OVERRIDE";
                if (event == null) {
                    roundBlocking.add("Round %d (%s): event %d is not in this season.".formatted(se.round(), track, override));
                }
            } else {
                event = seasonEvents.stream()
                        .filter(e -> (SOURCE_PREFIX + se.id()).equals(e.sourceRef()))
                        .findFirst().orElse(null);
                if (event != null) {
                    eventMatch = "SOURCE";
                } else {
                    event = eventByDrivers(se, rows, seasonEvents, claimedEvents);
                    if (event != null) {
                        eventMatch = "DRIVERS";
                    }
                }
            }
            if (event != null && !claimedEvents.add(event.id())) {
                roundBlocking.add("Round %d (%s): %s is already matched to another round.".formatted(
                        se.round(), track, event.name()));
            }
            if (event == null && roundBlocking.isEmpty()) {
                roundBlocking.add(("Round %d (%s, %s): no Pit Pass event in this season raced by these drivers. "
                        + "Import the iRacing session first, or pick the event.").formatted(
                        se.round(), track, dateOf(se.raceStartTime())));
            }

            Long raceSessionId = null;
            List<CarPlan> cars = new ArrayList<>();
            List<EntryRef> unpaired = new ArrayList<>();
            if (event != null && roundBlocking.isEmpty()) {
                List<Long> races = db.sql("""
                                SELECT id FROM race_session WHERE event_id = :e AND session_type = 'RACE'
                                ORDER BY ordinal
                                """)
                        .param("e", event.id()).query(Long.class).list();
                if (races.isEmpty()) {
                    roundBlocking.add("Round %d (%s): %s has no race session to correct.".formatted(
                            se.round(), track, event.name()));
                } else if (races.size() > 1) {
                    roundBlocking.add("Round %d (%s): %s has %d race sessions; the site publishes one.".formatted(
                            se.round(), track, event.name(), races.size()));
                } else {
                    raceSessionId = races.get(0);
                }
            }
            if (raceSessionId != null) {
                List<Entry> entries = entries(event.id(), raceSessionId);
                PairingOutcome outcome = pair(rows, entries, req.pairs());
                Map<String, Written> written = written(rows);
                List<Pairing> pairings = new ArrayList<>();
                Set<String> takenNumbers = new HashSet<>();
                for (ArtifactClient.Result r : rows) {
                    Entry e = outcome.paired().get(r.id());
                    String match = outcome.match().get(r.id());
                    boolean confirm = outcome.needsConfirmation().contains(r.id());
                    List<LineupLine> lineup = e == null ? List.of() : lineup(r, e);
                    lineup.stream()
                            .filter(l -> "CLOSE".equals(l.match()))
                            .forEach(l -> consolidationCandidates.add(new Consolidation(l.driverId(), l.siteName())));
                    Written w = e == null ? written.get(r.id()) : keepWithinTolerance(written.get(r.id()), e.result());
                    List<Change> changes = e == null || "SKIPPED".equals(match) ? List.of() : changes(r, e, w);
                    Integer delta = e == null || e.result() == null || e.result().positionInClass() == null
                            ? null : e.result().positionInClass() - w.positionInClass();
                    cars.add(new CarPlan(r.id(), clean(r.carNumber()), clean(r.className()), clean(r.entryName()),
                            names(r), w.positionOverall(), w.positionInClass(),
                            e == null ? null : e.id(), e == null ? null : e.carNumber(),
                            e == null ? null : e.teamName(), e == null ? null : e.className(),
                            match, confirm, outcome.candidates().getOrDefault(r.id(), List.of()),
                            lineup, changes, delta));
                    if (e != null && !"SKIPPED".equals(match)) {
                        pairings.add(new Pairing(r, e, w));
                        takenNumbers.add(clean(r.carNumber()));
                        classVotes.computeIfAbsent(clean(r.className()), k -> new LinkedHashMap<>())
                                .merge(e.className(), 1, Integer::sum);
                        pairedOrUnpairedEntryIds.add(e.id());
                        if (drops.contains(e.id())) {
                            roundBlocking.add("Round %d: #%s is paired with a site car and can't also be dropped."
                                    .formatted(se.round(), e.carNumber()));
                        }
                    }
                    if (confirm) {
                        roundBlocking.add("Round %d: confirm #%s %s (%s match with Pit Pass #%s).".formatted(
                                se.round(), clean(r.carNumber()), clean(r.entryName()),
                                "CLOSE_DRIVERS".equals(match) ? "near-miss driver name" : "car number only",
                                e == null ? "?" : e.carNumber()));
                    } else if ("AMBIGUOUS".equals(match) || "NONE".equals(match)) {
                        roundBlocking.add("Round %d: pick the Pit Pass entry for #%s %s (%s), or leave it out."
                                .formatted(se.round(), clean(r.carNumber()), clean(r.entryName()),
                                        "AMBIGUOUS".equals(match) ? "several entries share its drivers"
                                                : "no entry shares its drivers or number"));
                    }
                }
                Set<Long> pairedIds = pairings.stream().map(p -> p.entry().id()).collect(Collectors.toSet());
                Set<Long> unpairedIds = new LinkedHashSet<>();
                for (Entry e : entries) {
                    if (pairedIds.contains(e.id())) {
                        continue;
                    }
                    unpairedIds.add(e.id());
                    pairedOrUnpairedEntryIds.add(e.id());
                    boolean numberClash = takenNumbers.contains(e.carNumber());
                    boolean mustDrop = e.result() != null || numberClash;
                    boolean dropping = drops.contains(e.id());
                    unpaired.add(new EntryRef(e.id(), e.carNumber(), e.teamName(), e.className(),
                            e.drivers().stream().map(Driver::name).toList(), e.result() != null, mustDrop, dropping));
                    if (mustDrop && !dropping) {
                        roundBlocking.add(("Round %d: Pit Pass #%s %s %s but the site doesn't classify it — "
                                + "pair it with a site car or drop it.").formatted(se.round(), e.carNumber(),
                                e.teamName(), numberClash && e.result() == null
                                        ? "holds a number a corrected car takes" : "has a race result"));
                    }
                }
                work.add(new RoundWork(se, event.id(), raceSessionId, pairings, unpairedIds));
            }
            blocking.addAll(roundBlocking);
            roundPlans.add(new RoundPlan(se.round(), se.id(), track, se.raceStartTime(), rows.size(),
                    event == null ? null : new EventRef(event.id(), event.name(), event.date(), event.roundOrdinal()),
                    eventMatch, raceSessionId, cars, unpaired, roundBlocking));
        }
        for (Long drop : drops) {
            if (!pairedOrUnpairedEntryIds.contains(drop)) {
                blocking.add("Entry %d is not in any round being corrected.".formatted(drop));
            }
        }
        if (req.consolidations() != null) {
            for (Consolidation c : req.consolidations()) {
                if (!consolidationCandidates.contains(c)) {
                    blocking.add("Driver %d → \"%s\" is not a near-miss spelling in this plan.".formatted(
                            c.driverId(), c.name()));
                }
            }
        }

        // Site class → Pit Pass class: what the paired entries say, unless the reviewer says otherwise.
        Set<String> siteClasses = new LinkedHashSet<>(classVotes.keySet());
        data.standings().forEach(s -> siteClasses.add(clean(s.className())));
        Map<String, String> classMap = new LinkedHashMap<>();
        List<ClassPlan> classPlans = new ArrayList<>();
        for (String sc : siteClasses) {
            String mapped = req.classes().get(sc);
            String source;
            if (mapped != null && !mapped.isBlank()) {
                source = "MAPPED";
            } else {
                mapped = classVotes.getOrDefault(sc, Map.of()).entrySet().stream()
                        .max(Map.Entry.comparingByValue())
                        .map(Map.Entry::getKey).orElse(null);
                source = mapped == null ? "NONE" : "PAIRED";
            }
            classMap.put(sc, mapped);
            classPlans.add(new ClassPlan(sc, mapped, source));
        }
        List<String> seasonClasses = db.sql("""
                        SELECT DISTINCT en.class_name FROM entry en JOIN event ev ON ev.id = en.event_id
                        WHERE ev.season_id = :s
                        """)
                .param("s", season.id()).query(String.class).list();
        classMap.forEach((sc, pp) -> {
            if (pp != null && !seasonClasses.contains(pp)) {
                warnings.add("No entry in this season is in class %s yet; the site's %s standings will create it."
                        .formatted(pp, sc));
            }
        });

        // Standings, one TEAMS championship per site class.
        boolean withStandings = !Boolean.FALSE.equals(req.importStandings());
        Map<String, StandingsImport> standings = new LinkedHashMap<>();
        List<StandingsPlan> standingsPlans = new ArrayList<>();
        if (withStandings && !data.standings().isEmpty()) {
            Map<Integer, String> eventNameByRound = new HashMap<>();
            for (ArtifactClient.Event se : data.events()) {
                eventNameByRound.put(se.round(), trackLabel(se));
            }
            for (RoundPlan rp : roundPlans) {
                if (rp.event() != null) {
                    eventNameByRound.put(rp.round(), rp.event().name());
                }
            }
            int roundCount = data.standings().stream()
                    .mapToInt(s -> Math.max(lastScored(s.points()), lastScored(s.qualifyingPoints())))
                    .max().orElse(0);
            Map<String, List<ArtifactClient.Standing>> byClass = data.standings().stream()
                    .collect(Collectors.groupingBy(s -> clean(s.className()), LinkedHashMap::new, Collectors.toList()));
            for (Map.Entry<String, List<ArtifactClient.Standing>> cls : byClass.entrySet()) {
                String ppClass = classMap.get(cls.getKey());
                String name = data.league().name() + " " + cls.getKey();
                List<String> discrepancies = new ArrayList<>();
                StandingsImport imp = standingsImport(name, data.league(), cls.getKey(), season.year(),
                        roundCount, eventNameByRound, cls.getValue(), discrepancies);
                if (ppClass == null) {
                    blocking.add("Map the site's %s class to a Pit Pass class for its standings.".formatted(cls.getKey()));
                } else {
                    standings.put(ppClass, imp);
                }
                standingsPlans.add(new StandingsPlan(cls.getKey(), ppClass, name, imp.rows().size(),
                        roundCount, discrepancies));
            }
        }

        warnings.addAll(roundOrderWarnings(season.id(), roundPlans));
        List<TeamFold> folds = teamFolds(work);
        Plan plan = new Plan(data.league().id(), data.league().name(), season.id(), season.label(),
                roundPlans, classPlans, standingsPlans, folds, warnings, blocking, blocking.isEmpty());
        return new Planned(plan, work, classMap, standings, consolidationCandidates, folds);
    }

    /** Old teams every one of whose entries the correction renames to one site name. */
    private List<TeamFold> teamFolds(List<RoundWork> rounds) {
        Map<Long, Set<String>> targets = new LinkedHashMap<>();
        Map<Long, String> oldNames = new HashMap<>();
        Map<Long, Set<Long>> renamedEntries = new HashMap<>();
        for (RoundWork round : rounds) {
            for (Pairing p : round.pairings()) {
                String team = clean(p.row().entryName());
                Long from = p.entry().teamId();
                if (from == null || team.isEmpty() || team.equals(p.entry().teamName())) {
                    continue;
                }
                targets.computeIfAbsent(from, k -> new LinkedHashSet<>()).add(team);
                oldNames.putIfAbsent(from, p.entry().teamName());
                renamedEntries.computeIfAbsent(from, k -> new HashSet<>()).add(p.entry().id());
            }
        }
        List<TeamFold> folds = new ArrayList<>();
        for (Map.Entry<Long, Set<String>> t : targets.entrySet()) {
            if (t.getValue().size() != 1) {
                continue; // one iRacing name, two registered teams: not a spelling variant
            }
            long others = db.sql("SELECT count(*) FROM entry WHERE team_id = :t AND id NOT IN (:renamed)")
                    .param("t", t.getKey())
                    .param("renamed", renamedEntries.get(t.getKey()))
                    .query(Long.class).single();
            if (others == 0) {
                folds.add(new TeamFold(t.getKey(), oldNames.get(t.getKey()), t.getValue().iterator().next()));
            }
        }
        return folds;
    }

    /* ------------------------------------------------------------------ */
    /* Apply                                                                */
    /* ------------------------------------------------------------------ */

    /** Re-plans with the decisions and, when nothing blocks, writes it all. */
    public ApplyResult apply(CorrectionRequest req) {
        SiteData data = fetch(req.leagueId());
        return tx.execute(status -> {
            Planned planned = plan(req, data);
            if (!planned.plan().ready()) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "Not ready to apply: " + String.join(" ", planned.plan().blocking()));
            }
            long seasonId = planned.plan().seasonId();

            int consolidated = 0;
            for (Consolidation c : Objects.requireNonNullElse(req.consolidations(), List.<Consolidation>of())) {
                drivers.consolidate(c.driverId(), c.name());
                consolidated++;
            }
            int dropped = 0;
            for (long id : req.drops()) {
                dropped += db.sql("DELETE FROM entry WHERE id = :id").param("id", id).update();
            }

            int renumbered = 0;
            int renamed = 0;
            int written = 0;
            for (RoundWork round : planned.rounds()) {
                // Numbers move in two steps so two cars swapping numbers never
                // collide on UNIQUE (event_id, car_number).
                List<Pairing> moving = round.pairings().stream()
                        .filter(p -> !clean(p.row().carNumber()).equals(p.entry().carNumber()))
                        .toList();
                for (Pairing p : moving) {
                    db.sql("UPDATE entry SET car_number = '~' || id WHERE id = :id")
                            .param("id", p.entry().id()).update();
                }
                for (Pairing p : moving) {
                    db.sql("UPDATE entry SET car_number = :n WHERE id = :id")
                            .param("n", clean(p.row().carNumber())).param("id", p.entry().id()).update();
                    renumbered++;
                }
                for (Pairing p : round.pairings()) {
                    String team = clean(p.row().entryName());
                    if (!team.isEmpty() && !team.equals(p.entry().teamName())) {
                        db.sql("UPDATE entry SET team_name = :team, team_id = :teamId WHERE id = :id")
                                .param("team", team)
                                .param("teamId", teams.resolveOrCreate(team))
                                .param("id", p.entry().id())
                                .update();
                        renamed++;
                    }
                    writeResult(round.raceSessionId(), p.entry().id(), p.written());
                    written++;
                }
                db.sql("""
                                UPDATE event SET source_ref = COALESCE(source_ref, :ref), source_round = :round
                                WHERE id = :id
                                """)
                        .param("ref", SOURCE_PREFIX + round.siteEvent().id())
                        .param("round", round.siteEvent().round())
                        .param("id", round.eventId())
                        .update();
            }
            int folded = 0;
            for (TeamFold fold : planned.teamFolds()) {
                Long into = teams.resolveOrCreate(fold.toName());
                if (into != null && into != fold.fromTeamId()) {
                    teamAdmin.merge(into, new TeamAdminController.MergeRequest(fold.fromTeamId()));
                    folded++;
                }
            }
            imports.renumberSeasonRounds(seasonId);
            teamAssignments.applySeason(seasonId);

            for (Map.Entry<String, StandingsImport> s : planned.standings().entrySet()) {
                imports.commitStandingsToSeason(s.getValue(), seasonId, new ImportService.ImportTarget(
                        null, null, null, null, s.getKey(), "TEAMS", false, null, null,
                        null, null, Map.of(s.getKey(), s.getKey()), null, null, null, null, null));
            }
            return new ApplyResult(planned.rounds().size(), written, renumbered, renamed, folded, dropped,
                    consolidated, planned.standings().size());
        });
    }

    private void writeResult(long sessionId, long entryId, Written w) {
        db.sql("""
                        INSERT INTO result (session_id, entry_id, position_overall, position_in_class, status,
                                            not_finished, not_finished_cause, laps, gap_first, gap_previous,
                                            fastest_lap_time, fastest_lap_number)
                        VALUES (:s, :e, :po, :pc, :status, :nf, :cause, :laps, :gf, :gp, :flt, :fln)
                        ON CONFLICT (session_id, entry_id) DO UPDATE
                            SET position_overall = EXCLUDED.position_overall,
                                position_in_class = EXCLUDED.position_in_class,
                                status = EXCLUDED.status,
                                not_finished = EXCLUDED.not_finished,
                                not_finished_cause = EXCLUDED.not_finished_cause,
                                laps = EXCLUDED.laps,
                                gap_first = EXCLUDED.gap_first,
                                gap_previous = EXCLUDED.gap_previous,
                                fastest_lap_time = EXCLUDED.fastest_lap_time,
                                fastest_lap_number = EXCLUDED.fastest_lap_number
                        """)
                .param("s", sessionId).param("e", entryId)
                .param("po", w.positionOverall()).param("pc", w.positionInClass())
                .param("status", w.status()).param("nf", w.notFinished()).param("cause", w.notFinishedCause())
                .param("laps", w.laps()).param("gf", w.gapFirst()).param("gp", w.gapPrevious())
                .param("flt", w.fastestLapTime()).param("fln", w.fastestLapNumber())
                .update();
    }

    /* ------------------------------------------------------------------ */
    /* Pit Pass side                                                        */
    /* ------------------------------------------------------------------ */

    private record Season(long id, int year, String label) {
    }

    private record SeasonEvent(long id, String name, LocalDate date, Integer roundOrdinal, String sourceRef,
                               boolean isRound, Integer sourceRound) {
    }

    private record Driver(long id, String name, Set<String> keys) {
    }

    private record ExistingResult(Integer positionOverall, Integer positionInClass, String status,
                                  boolean notFinished, Integer laps, String gapFirst, String fastestLapTime,
                                  Integer fastestLapNumber) {
    }

    private record Entry(long id, String carNumber, String teamName, Long teamId, String className,
                         List<Driver> drivers, ExistingResult result) {
        Set<String> exactKeys() {
            return drivers.stream().flatMap(d -> d.keys().stream()).collect(Collectors.toSet());
        }
    }

    private Season requireSeason(long seasonId) {
        return db.sql("""
                        SELECT s.id, s.year,
                               sr.name || ' ' || COALESCE(NULLIF(s.label, ''), s.year::text) AS label
                        FROM season s JOIN series sr ON sr.id = s.series_id
                        WHERE s.id = :id
                        """)
                .param("id", seasonId)
                .query((rs, i) -> new Season(rs.getLong("id"), rs.getInt("year"), rs.getString("label")))
                .optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such season"));
    }

    private List<SeasonEvent> seasonEvents(long seasonId) {
        return db.sql("""
                        SELECT id, name, event_date, round_ordinal, source_ref, is_round, source_round
                        FROM event WHERE season_id = :s
                        ORDER BY event_date NULLS LAST, id
                        """)
                .param("s", seasonId)
                .query((rs, i) -> new SeasonEvent(rs.getLong("id"), rs.getString("name"),
                        rs.getObject("event_date", LocalDate.class), rs.getObject("round_ordinal", Integer.class),
                        rs.getString("source_ref"), rs.getBoolean("is_round"),
                        rs.getObject("source_round", Integer.class)))
                .list();
    }

    /** The event's entries with their crews (names and retired spellings) and race result. */
    private List<Entry> entries(long eventId, long raceSessionId) {
        record Seat(long entryId, long driverId, String name) {
        }
        Map<Long, List<Seat>> seats = new HashMap<>();
        db.sql("""
                        SELECT da.entry_id, d.id AS driver_id, d.first_name || ' ' || d.surname AS name
                        FROM driver_assignment da
                                 JOIN driver d ON d.id = da.driver_id
                                 JOIN entry en ON en.id = da.entry_id
                        WHERE en.event_id = :e
                        ORDER BY da.entry_id, da.seat_order
                        """)
                .param("e", eventId)
                .query((rs, i) -> new Seat(rs.getLong("entry_id"), rs.getLong("driver_id"), rs.getString("name")))
                .list()
                .forEach(s -> seats.computeIfAbsent(s.entryId(), k -> new ArrayList<>()).add(s));
        Map<Long, List<String>> aliases = new HashMap<>();
        db.sql("""
                        SELECT a.driver_id, a.alias FROM driver_alias a
                        WHERE a.driver_id IN (SELECT da.driver_id FROM driver_assignment da
                                                       JOIN entry en ON en.id = da.entry_id
                                              WHERE en.event_id = :e)
                        """)
                .param("e", eventId)
                .query((rs, i) -> aliases.computeIfAbsent(rs.getLong("driver_id"), k -> new ArrayList<>())
                        .add(rs.getString("alias")))
                .list();
        return db.sql("""
                        SELECT en.id, en.car_number, en.team_name, en.team_id, en.class_name,
                               r.entry_id IS NOT NULL AS has_result, r.position_overall, r.position_in_class,
                               r.status, COALESCE(r.not_finished, false) AS not_finished, r.laps, r.gap_first,
                               r.fastest_lap_time, r.fastest_lap_number
                        FROM entry en
                                 LEFT JOIN result r ON r.entry_id = en.id AND r.session_id = :s
                        WHERE en.event_id = :e
                        ORDER BY r.position_overall NULLS LAST, en.car_number
                        """)
                .param("e", eventId).param("s", raceSessionId)
                .query((rs, i) -> {
                    long id = rs.getLong("id");
                    List<Driver> crew = seats.getOrDefault(id, List.of()).stream()
                            .map(s -> {
                                Set<String> keys = new HashSet<>();
                                keys.add(exactKey(s.name()));
                                aliases.getOrDefault(s.driverId(), List.of()).forEach(a -> keys.add(exactKey(a)));
                                return new Driver(s.driverId(), s.name(), keys);
                            })
                            .toList();
                    ExistingResult result = rs.getBoolean("has_result") ? new ExistingResult(
                            rs.getObject("position_overall", Integer.class),
                            rs.getObject("position_in_class", Integer.class), rs.getString("status"),
                            rs.getBoolean("not_finished"), rs.getObject("laps", Integer.class),
                            rs.getString("gap_first"), rs.getString("fastest_lap_time"),
                            rs.getObject("fastest_lap_number", Integer.class)) : null;
                    return new Entry(id, clean(rs.getString("car_number")), clean(rs.getString("team_name")),
                            rs.getObject("team_id", Long.class), rs.getString("class_name"), crew, result);
                })
                .list();
    }

    /** The season event, near the site's race date, whose crews share the most drivers with this round. */
    private SeasonEvent eventByDrivers(ArtifactClient.Event se, List<ArtifactClient.Result> rows,
                                       List<SeasonEvent> seasonEvents, Set<Long> claimed) {
        LocalDate date = dateOf(se.raceStartTime());
        Set<String> siteKeys = rows.stream().flatMap(r -> names(r).stream()).map(ArtifactCorrectionService::exactKey)
                .collect(Collectors.toSet());
        SeasonEvent best = null;
        long bestOverlap = 0;
        for (SeasonEvent e : seasonEvents) {
            if (claimed.contains(e.id()) || (e.sourceRef() != null && e.sourceRef().startsWith(SOURCE_PREFIX))) {
                continue;
            }
            if (date != null && e.date() != null
                    && Math.abs(e.date().toEpochDay() - date.toEpochDay()) > EVENT_DATE_WINDOW_DAYS) {
                continue;
            }
            Set<String> keys = new HashSet<>(db.sql("""
                            SELECT lower(regexp_replace(trim(d.first_name || ' ' || d.surname), '\\s+', ' ', 'g'))
                            FROM driver_assignment da
                                     JOIN driver d ON d.id = da.driver_id
                                     JOIN entry en ON en.id = da.entry_id
                            WHERE en.event_id = :e
                            UNION
                            SELECT lower(regexp_replace(trim(a.alias), '\\s+', ' ', 'g'))
                            FROM driver_alias a
                                     JOIN driver_assignment da ON da.driver_id = a.driver_id
                                     JOIN entry en ON en.id = da.entry_id
                            WHERE en.event_id = :e
                            """)
                    .param("e", e.id()).query(String.class).list());
            long overlap = siteKeys.stream().filter(keys::contains).count();
            if (overlap > bestOverlap) {
                best = e;
                bestOverlap = overlap;
            }
        }
        return best;
    }

    /**
     * Where standings round N will land: the recap matches it to the season's
     * Nth event, so a corrected round that Pit Pass numbers differently puts
     * its points in the wrong column. Predicted with the site's round numbers
     * as the same-day tiebreak the correction will record.
     */
    private List<String> roundOrderWarnings(long seasonId, List<RoundPlan> rounds) {
        Map<Long, Integer> siteRoundByEvent = new HashMap<>();
        rounds.stream().filter(r -> r.event() != null)
                .forEach(r -> siteRoundByEvent.put(r.event().id(), r.round()));
        List<SeasonEvent> ordered = seasonEvents(seasonId).stream()
                .filter(SeasonEvent::isRound)
                .sorted(Comparator.comparing(SeasonEvent::date, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(e -> siteRoundByEvent.getOrDefault(e.id(), e.sourceRound()),
                                Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(SeasonEvent::id))
                .toList();
        List<String> warnings = new ArrayList<>();
        for (int i = 0; i < ordered.size(); i++) {
            Integer siteRound = siteRoundByEvent.get(ordered.get(i).id());
            if (siteRound != null && siteRound != i + 1) {
                warnings.add(("%s will be Pit Pass round %d but is the site's round %d — its standings points "
                        + "would show under the wrong round. Check the season's events.")
                        .formatted(ordered.get(i).name(), i + 1, siteRound));
            }
        }
        return warnings;
    }

    /* ------------------------------------------------------------------ */
    /* Pairing                                                              */
    /* ------------------------------------------------------------------ */

    private record PairingOutcome(Map<String, Entry> paired, Map<String, String> match,
                                  Set<String> needsConfirmation, Map<String, List<EntryOption>> candidates) {
    }

    private static PairingOutcome pair(List<ArtifactClient.Result> rows, List<Entry> entries,
                                       Map<String, Long> decisions) {
        Map<String, Entry> paired = new LinkedHashMap<>();
        Map<String, String> match = new HashMap<>();
        Set<String> confirm = new HashSet<>();
        Map<String, List<EntryOption>> candidates = new HashMap<>();
        Set<Long> reserved = new HashSet<>();
        Map<Long, Entry> byId = entries.stream().collect(Collectors.toMap(Entry::id, e -> e));

        // 1. The reviewer's word.
        for (ArtifactClient.Result r : rows) {
            if (!decisions.containsKey(r.id())) {
                continue;
            }
            Long entryId = decisions.get(r.id());
            if (entryId == null) {
                match.put(r.id(), "SKIPPED");
                continue;
            }
            Entry e = byId.get(entryId);
            if (e != null && reserved.add(e.id())) {
                paired.put(r.id(), e);
                match.put(r.id(), "CONFIRMED");
            }
        }

        // 2. Exact driver names. A car claimed by two site rows is ambiguous for both.
        Map<String, Entry> tentative = new HashMap<>();
        for (ArtifactClient.Result r : rows) {
            if (match.containsKey(r.id())) {
                continue;
            }
            Set<String> keys = names(r).stream().map(ArtifactCorrectionService::exactKey).collect(Collectors.toSet());
            if (keys.isEmpty()) {
                continue;
            }
            List<Entry> best = bestBy(entries, reserved, e -> overlap(keys, e.exactKeys()));
            if (best.size() == 1) {
                tentative.put(r.id(), best.get(0));
            } else if (best.size() > 1) {
                match.put(r.id(), "AMBIGUOUS");
                candidates.put(r.id(), options(best));
            }
        }
        settle(rows, tentative, "EXACT_DRIVERS", false, paired, match, confirm, candidates, reserved);

        // 3. Near-miss spellings — a pairing to confirm, never to assume.
        tentative.clear();
        for (ArtifactClient.Result r : rows) {
            if (match.containsKey(r.id()) || names(r).isEmpty()) {
                continue;
            }
            List<Entry> best = bestBy(entries, reserved, e -> closeOverlap(names(r), e));
            if (best.size() == 1) {
                tentative.put(r.id(), best.get(0));
            } else if (best.size() > 1) {
                match.put(r.id(), "AMBIGUOUS");
                candidates.put(r.id(), options(best));
            }
        }
        settle(rows, tentative, "CLOSE_DRIVERS", true, paired, match, confirm, candidates, reserved);

        // 4. The number alone: fine when nobody's name contradicts it (the site
        //    lists no crew for a car that never turned a lap), otherwise confirm.
        for (ArtifactClient.Result r : rows) {
            if (match.containsKey(r.id())) {
                continue;
            }
            Optional<Entry> same = entries.stream()
                    .filter(e -> !reserved.contains(e.id()) && e.carNumber().equals(clean(r.carNumber())))
                    .findFirst();
            if (same.isPresent()) {
                Entry e = same.get();
                reserved.add(e.id());
                paired.put(r.id(), e);
                match.put(r.id(), "CAR_NUMBER");
                if (!names(r).isEmpty() && !e.drivers().isEmpty()) {
                    confirm.add(r.id());
                }
            } else {
                match.put(r.id(), "NONE");
            }
        }
        // Anything unresolved can be pointed at any entry still free.
        for (ArtifactClient.Result r : rows) {
            String m = match.get(r.id());
            if ("NONE".equals(m) || "AMBIGUOUS".equals(m)) {
                List<EntryOption> free = options(entries.stream().filter(e -> !reserved.contains(e.id())).toList());
                candidates.merge(r.id(), free, (a, b) -> {
                    List<EntryOption> all = new ArrayList<>(a);
                    b.stream().filter(o -> a.stream().noneMatch(x -> x.entryId() == o.entryId())).forEach(all::add);
                    return all;
                });
            }
        }
        return new PairingOutcome(paired, match, confirm, candidates);
    }

    private static void settle(List<ArtifactClient.Result> rows, Map<String, Entry> tentative, String how,
                               boolean needsConfirmation, Map<String, Entry> paired, Map<String, String> match,
                               Set<String> confirm, Map<String, List<EntryOption>> candidates, Set<Long> reserved) {
        Map<Long, Long> claims = tentative.values().stream()
                .collect(Collectors.groupingBy(Entry::id, Collectors.counting()));
        for (ArtifactClient.Result r : rows) {
            Entry e = tentative.get(r.id());
            if (e == null) {
                continue;
            }
            if (claims.get(e.id()) > 1) {
                match.put(r.id(), "AMBIGUOUS");
                candidates.put(r.id(), options(List.of(e)));
                continue;
            }
            reserved.add(e.id());
            paired.put(r.id(), e);
            match.put(r.id(), how);
            if (needsConfirmation) {
                confirm.add(r.id());
            }
        }
    }

    private static List<Entry> bestBy(List<Entry> entries, Set<Long> reserved,
                                      java.util.function.ToLongFunction<Entry> score) {
        long best = 0;
        List<Entry> top = new ArrayList<>();
        for (Entry e : entries) {
            if (reserved.contains(e.id())) {
                continue;
            }
            long s = score.applyAsLong(e);
            if (s > best) {
                best = s;
                top.clear();
                top.add(e);
            } else if (s == best && s > 0) {
                top.add(e);
            }
        }
        return top;
    }

    private static long overlap(Set<String> a, Set<String> b) {
        return a.stream().filter(b::contains).count();
    }

    private static long closeOverlap(List<String> siteNames, Entry e) {
        return siteNames.stream()
                .filter(n -> e.drivers().stream().anyMatch(d -> d.keys().stream().anyMatch(k -> close(n, k))))
                .count();
    }

    private static List<EntryOption> options(List<Entry> entries) {
        return entries.stream()
                .map(e -> new EntryOption(e.id(), e.carNumber(), e.teamName(),
                        e.drivers().stream().map(Driver::name).toList()))
                .toList();
    }

    /** Each site driver against the paired crew, plus the crew the site doesn't list. */
    private static List<LineupLine> lineup(ArtifactClient.Result r, Entry e) {
        List<LineupLine> lines = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (String n : names(r)) {
            Driver exact = e.drivers().stream().filter(d -> d.keys().contains(exactKey(n))).findFirst().orElse(null);
            if (exact != null) {
                seen.add(exact.id());
                lines.add(new LineupLine(n, exact.id(), exact.name(), "EXACT"));
                continue;
            }
            Driver near = e.drivers().stream()
                    .filter(d -> !seen.contains(d.id()) && d.keys().stream().anyMatch(k -> close(n, k)))
                    .findFirst().orElse(null);
            if (near != null) {
                seen.add(near.id());
                lines.add(new LineupLine(n, near.id(), near.name(), "CLOSE"));
            } else {
                lines.add(new LineupLine(n, null, null, "MISSING"));
            }
        }
        e.drivers().stream().filter(d -> !seen.contains(d.id()))
                .forEach(d -> lines.add(new LineupLine(null, d.id(), d.name(), "EXTRA")));
        return lines;
    }

    /* ------------------------------------------------------------------ */
    /* The site's classification in Pit Pass's shapes                       */
    /* ------------------------------------------------------------------ */

    /** Each row as it will be written, the gap to the car ahead derived the
     *  way the iRacing parser derives it (the site, like iRacing, publishes
     *  only the gap to the leader). */
    private static Map<String, Written> written(List<ArtifactClient.Result> ordered) {
        Map<String, Written> out = new HashMap<>();
        ArtifactClient.Result prev = null;
        for (ArtifactClient.Result r : ordered) {
            int overall = intOr(r.overallPosition(), 0);
            boolean leader = overall == 1;
            String end = clean(r.endStatus());
            boolean running = end.isEmpty() || "Running".equalsIgnoreCase(end);
            String fastest = blankToNull(r.fastestLapTime());
            String status = !running ? end : fastest == null ? "No Time" : "Classified";
            out.put(r.id(), new Written(overall, intOr(r.classPosition(), 0), status, !running,
                    running ? null : end, intOrNull(r.lapsCompleted()), gapFirst(r.interval(), leader),
                    prev == null ? "-" : gapPrevious(r.interval(), prev.interval()),
                    fastest, positiveOrNull(intOrNull(r.fastestLapNumber()))));
            prev = r;
        }
        return out;
    }

    private static List<Change> changes(ArtifactClient.Result r, Entry e, Written w) {
        List<Change> changes = new ArrayList<>();
        diff(changes, "car number", e.carNumber(), clean(r.carNumber()));
        String team = clean(r.entryName());
        if (!team.isEmpty()) {
            diff(changes, "team", e.teamName(), team);
        }
        ExistingResult old = e.result();
        diff(changes, "overall", old == null ? null : str(old.positionOverall()), str(w.positionOverall()));
        diff(changes, "class position", old == null ? null : str(old.positionInClass()), str(w.positionInClass()));
        diff(changes, "status", old == null ? null : old.status(), w.status());
        diff(changes, "laps", old == null ? null : str(old.laps()), str(w.laps()));
        diff(changes, "gap", old == null ? null : old.gapFirst(), w.gapFirst());
        diff(changes, "fastest lap", old == null ? null : old.fastestLapTime(), w.fastestLapTime());
        diff(changes, "fastest lap no.", old == null ? null : str(old.fastestLapNumber()), str(w.fastestLapNumber()));
        return changes;
    }

    /**
     * The two sources time the same lap to the millisecond and round it
     * differently ("1:12.413" vs "1:12.412"); a reading within the tolerance
     * keeps what Pit Pass already has, so neither the review nor the write
     * churns on noise.
     */
    private static Written keepWithinTolerance(Written w, ExistingResult old) {
        if (old == null) {
            return w;
        }
        String gap = sameGap(old.gapFirst(), w.gapFirst()) ? old.gapFirst() : w.gapFirst();
        String lap = sameGap(old.fastestLapTime(), w.fastestLapTime()) ? old.fastestLapTime() : w.fastestLapTime();
        return new Written(w.positionOverall(), w.positionInClass(), w.status(), w.notFinished(),
                w.notFinishedCause(), w.laps(), gap, w.gapPrevious(), lap, w.fastestLapNumber());
    }

    private static void diff(List<Change> out, String field, String from, String to) {
        if (!Objects.equals(from, to)) {
            out.add(new Change(field, from, to));
        }
    }

    /** "-00.000" → "-" for the leader; "-1:04.113" → "+64.113"; "-16 L" → "16 Laps"; "-" → null. */
    static String gapFirst(String interval, boolean leader) {
        if (leader) {
            return "-";
        }
        Integer laps = lapsDown(interval);
        if (laps != null) {
            return lapsText(laps);
        }
        Double seconds = gapSeconds(interval);
        return seconds == null ? null : "+" + String.format(Locale.ROOT, "%.3f", seconds);
    }

    static String gapPrevious(String interval, String prevInterval) {
        Double s = gapSeconds(interval);
        Double p = gapSeconds(prevInterval);
        if (s != null && p != null) {
            return "+" + String.format(Locale.ROOT, "%.3f", Math.max(0, s - p));
        }
        Integer laps = lapsDownOrZero(interval);
        Integer prevLaps = lapsDownOrZero(prevInterval);
        return laps == null || prevLaps == null ? null : lapsText(laps - prevLaps);
    }

    /** Two readings of the same gap agree when both are times within the tolerance. */
    static boolean sameGap(String a, String b) {
        if (Objects.equals(a, b)) {
            return true;
        }
        Double x = gapSeconds(a);
        Double y = gapSeconds(b);
        return x != null && y != null && Math.abs(x - y) <= GAP_TOLERANCE_SECONDS;
    }

    private static Integer lapsDown(String interval) {
        String s = stripSign(interval);
        if (s == null || !s.matches("\\d+\\s*(L|Laps?)")) {
            return null;
        }
        return Integer.parseInt(s.replaceAll("\\D", ""));
    }

    private static Integer lapsDownOrZero(String interval) {
        Integer laps = lapsDown(interval);
        return laps != null ? laps : gapSeconds(interval) != null ? 0 : null;
    }

    /** Seconds from "1:04.113", "64.113", "1:02:03.456" (sign ignored); null if not a time. */
    static Double gapSeconds(String gap) {
        String s = stripSign(gap);
        if (s == null || !s.matches("(\\d+:){0,2}\\d+(\\.\\d+)?")) {
            return null;
        }
        double total = 0;
        for (String part : s.split(":")) {
            total = total * 60 + Double.parseDouble(part);
        }
        return total;
    }

    private static String stripSign(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        while (t.startsWith("-") || t.startsWith("+")) {
            t = t.substring(1).trim();
        }
        return t.isEmpty() ? null : t;
    }

    private static String lapsText(int laps) {
        return laps <= 0 ? null : laps + (laps == 1 ? " Lap" : " Laps");
    }

    /* ------------------------------------------------------------------ */
    /* Standings                                                            */
    /* ------------------------------------------------------------------ */

    /**
     * One class's table as a TEAMS championship keyed by car number, a round as
     * two sessions (qualifying points, race points) sharing an event name — the
     * recap sums them into one column. Every round up to the last one anybody
     * scored is listed for every class, so a class that sat a round out keeps
     * its later rounds under the right number; its cars read did_not_race there.
     * Where a car's rounds don't add up to its total, the gap is recorded as a
     * season adjustment (V28) and reported.
     */
    private static StandingsImport standingsImport(String name, ArtifactClient.League league, String siteClass,
                                                   int year, int rounds, Map<Integer, String> eventNames,
                                                   List<ArtifactClient.Standing> table, List<String> discrepancies) {
        List<StandingsImport.SessionRef> sessions = new ArrayList<>();
        for (int round = 1; round <= rounds; round++) {
            String event = "R%d %s".formatted(round, eventNames.getOrDefault(round, "Round " + round));
            sessions.add(new StandingsImport.SessionRef(2 * round - 1, event, "Qualifying"));
            sessions.add(new StandingsImport.SessionRef(2 * round, event, "Race"));
        }
        List<StandingsImport.Row> rows = new ArrayList<>();
        for (ArtifactClient.Standing s : table.stream().sorted(Comparator.comparingInt(ArtifactClient.Standing::standing)).toList()) {
            List<StandingsImport.SessionPoints> points = new ArrayList<>();
            double sum = 0;
            for (int round = 1; round <= rounds; round++) {
                Double q = at(s.qualifyingPoints(), round);
                Double p = at(s.points(), round);
                points.add(sessionPoints(2 * round - 1, q));
                points.add(sessionPoints(2 * round, p));
                sum += (q == null ? 0 : q) + (p == null ? 0 : p);
            }
            double diff = s.totalPoints() - sum;
            StandingsImport.Adjustments adjustments = null;
            if (Math.abs(diff) > 1e-6) {
                adjustments = new StandingsImport.Adjustments(sum, Math.max(diff, 0), Math.min(diff, 0));
                discrepancies.add(("#%s %s: rounds add up to %s, the site's total is %s (%+.0f) — "
                        + "a points penalty or bonus applied to the season rather than a round?")
                        .formatted(clean(s.carNumber()), clean(s.teamName()), fmt(sum), fmt(s.totalPoints()), diff));
            }
            rows.add(new StandingsImport.Row(s.standing(), clean(s.carNumber()), clean(s.teamName()),
                    s.totalPoints(), null, null, adjustments, points));
        }
        String title = Objects.requireNonNullElse(league.seriesName(), league.name()) + " " + siteClass + " Teams";
        return new StandingsImport(name, title, null, String.valueOf(year), sessions, rows);
    }

    private static StandingsImport.SessionPoints sessionPoints(int index, Double points) {
        return points == null
                ? new StandingsImport.SessionPoints(index, 0, 0, 0, 0, 0, 0, "did_not_race")
                : new StandingsImport.SessionPoints(index, points, points, 0, 0, 0, 0, null);
    }

    private static int lastScored(List<Double> points) {
        if (points == null) {
            return 0;
        }
        for (int i = points.size() - 1; i >= 0; i--) {
            if (points.get(i) != null) {
                return i + 1;
            }
        }
        return 0;
    }

    private static Double at(List<Double> list, int round) {
        return list == null || list.size() < round ? null : list.get(round - 1);
    }

    private static String fmt(double d) {
        return d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d);
    }

    /* ------------------------------------------------------------------ */
    /* Names and small parsing                                              */
    /* ------------------------------------------------------------------ */

    /** Case and spacing ignored — the identity the importer and driver_alias use. */
    static String exactKey(String name) {
        return name == null ? "" : name.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    /** Accents, digits (iRacing's "Munoz2") and punctuation dropped. */
    static String looseKey(String name) {
        String s = Normalizer.normalize(name == null ? "" : name, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z]+", " ");
        return s.trim().replaceAll("\\s+", " ");
    }

    /**
     * A near-miss: not the same name, but the same once accents, digits and
     * punctuation go, or within two edits of it (a dropped letter) — only for
     * names long enough that two edits can't turn one person into another.
     */
    static boolean close(String a, String b) {
        if (exactKey(a).equals(exactKey(b))) {
            return false;
        }
        String x = looseKey(a);
        String y = looseKey(b);
        if (x.isEmpty() || y.isEmpty()) {
            return false;
        }
        if (x.equals(y)) {
            return true;
        }
        return Math.min(x.length(), y.length()) >= 8 && levenshtein(x, y) <= 2;
    }

    private static int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return prev[b.length()];
    }

    private static List<String> names(ArtifactClient.Result r) {
        return r.drivers() == null ? List.of()
                : r.drivers().stream().map(ArtifactCorrectionService::clean).filter(n -> !n.isEmpty()).toList();
    }

    private static String trackLabel(ArtifactClient.Event e) {
        return e.track() == null ? "Round " + e.round() : clean(e.track().name());
    }

    private static LocalDate dateOf(Instant instant) {
        return instant == null ? null : instant.atZone(ZoneOffset.UTC).toLocalDate();
    }

    static String clean(String s) {
        return s == null ? "" : s.trim();
    }

    private static String blankToNull(String s) {
        String t = clean(s);
        return t.isEmpty() || "-".equals(t) ? null : t;
    }

    private static Integer intOrNull(String s) {
        try {
            return s == null || s.isBlank() ? null : Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static int intOr(String s, int fallback) {
        Integer i = intOrNull(s);
        return i == null ? fallback : i;
    }

    private static Integer positiveOrNull(Integer i) {
        return i == null || i <= 0 ? null : i;
    }

    private static String str(Integer i) {
        return i == null ? null : i.toString();
    }
}
