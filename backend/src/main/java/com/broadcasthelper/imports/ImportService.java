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
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class ImportService {

    public record BatchSummary(long id, String kind, String filename, String status, String summary,
                               OffsetDateTime createdAt) {
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

    public BatchSummary stage(String filename, byte[] content) {
        if (isPdf(content)) {
            content = runEntryListParser(filename, content);
        }

        JsonNode root;
        try {
            root = json.readTree(content); // Jackson strips the UTF-8 BOM some files carry
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Not valid JSON: " + e.getMessage());
        }

        String kind;
        Object payload;
        String summary;
        if (ImportParser.looksLikeEntryList(root)) {
            kind = "ENTRY_LIST";
            EntryListImport parsed = ImportParser.parseEntryList(root);
            payload = parsed;
            long tbd = parsed.entries().stream()
                    .flatMap(e -> e.drivers().stream()).filter(EntryListImport.Driver::isTbd).count();
            long unparsed = parsed.entries().stream()
                    .flatMap(e -> e.drivers().stream()).filter(EntryListImport.Driver::unparsed).count();
            summary = "%s — entry list, %d entries".formatted(parsed.event().name(), parsed.entries().size());
            if (tbd > 0) {
                summary += ", %d TBD seat(s)".formatted(tbd);
            }
            if (unparsed > 0) {
                summary += ", %d UNPARSED driver line(s)".formatted(unparsed);
            }
        } else if (ImportParser.looksLikeStandings(root)) {
            kind = "STANDINGS";
            StandingsImport parsed = ImportParser.parseStandings(root);
            payload = parsed;
            summary = "%s — %d competitors, %d sessions".formatted(
                    parsed.mainTitle(), parsed.rows().size(), parsed.sessions().size());
        } else if (ImportParser.looksLikeRaceResults(root)) {
            kind = "RACE_RESULTS";
            RaceResultsImport parsed = ImportParser.parseRaceResults(root);
            payload = parsed;
            summary = "%s — %s, %d classified entries".formatted(
                    parsed.eventName(), parsed.sessionName(), parsed.rows().size());
        } else {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Unrecognized file format: expected a results file (session + classification) "
                    + "or a standings file (championship + classification)");
        }

        long id = db.sql("""
                        INSERT INTO import_batch (kind, filename, payload, summary)
                        VALUES (:kind, :filename, :payload::jsonb, :summary)
                        RETURNING id
                        """)
                .param("kind", kind)
                .param("filename", filename)
                .param("payload", toJson(payload))
                .param("summary", summary)
                .query(Long.class)
                .single();
        return get(id);
    }

    public List<BatchSummary> list() {
        return db.sql("""
                        SELECT id, kind, filename, status, summary, created_at
                        FROM import_batch ORDER BY id DESC
                        """)
                .query((rs, i) -> new BatchSummary(rs.getLong("id"), rs.getString("kind"),
                        rs.getString("filename"), rs.getString("status"), rs.getString("summary"),
                        rs.getObject("created_at", OffsetDateTime.class)))
                .list();
    }

    public BatchSummary get(long id) {
        return db.sql("""
                        SELECT id, kind, filename, status, summary, created_at
                        FROM import_batch WHERE id = :id
                        """)
                .param("id", id)
                .query((rs, i) -> new BatchSummary(rs.getLong("id"), rs.getString("kind"),
                        rs.getString("filename"), rs.getString("status"), rs.getString("summary"),
                        rs.getObject("created_at", OffsetDateTime.class)))
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
        List<String> known = seasonEntryClasses(seasonId.get());
        LinkedHashSet<String> unknown = new LinkedHashSet<>();
        for (RaceResultsImport.Row row : imp.rows()) {
            if (isUnknownClass(row.className(), known)) {
                unknown.add(row.className());
            }
        }
        return new ClassReview(known, new ArrayList<>(unknown));
    }

    // ---------------------------------------------------------------- commit

    @Transactional
    public BatchSummary commit(long id, Map<String, String> classMapping) {
        Map<String, String> mapping = classMapping == null ? Map.of() : classMapping;
        BatchSummary batch = get(id);
        if (!"STAGED".equals(batch.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Batch is not in STAGED state");
        }
        String payload = payloadJson(id);
        try {
            switch (batch.kind()) {
                case "RACE_RESULTS" -> commitRaceResults(json.readValue(payload, RaceResultsImport.class), mapping);
                case "STANDINGS" -> commitStandings(json.readValue(payload, StandingsImport.class), mapping);
                case "ENTRY_LIST" -> commitEntryList(json.readValue(payload, EntryListImport.class));
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

    private void commitRaceResults(RaceResultsImport imp, Map<String, String> mapping) {
        if (imp.sessionStart() == null) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Results file has no session date; cannot determine the season");
        }
        long seriesId = findOrCreateSeries(imp.championshipName());
        long seasonId = findOrCreateSeason(seriesId, imp.sessionStart().getYear());
        // Read the canonical class set before upserting entries, so the file's
        // own rows don't seed it (see canonicalizeClass).
        List<String> knownClasses = seasonEntryClasses(seasonId);
        long eventId = findOrCreateEvent(seasonId, imp);

        // Replace the session (and via cascade its results) if it was imported before.
        db.sql("DELETE FROM race_session WHERE event_id = :eventId AND name = :name")
                .param("eventId", eventId).param("name", imp.sessionName()).update();
        long sessionId = db.sql("""
                        INSERT INTO race_session (event_id, session_type, name, session_start, report_mark, report_message)
                        VALUES (:eventId, :type, :name, :start, :mark, :message)
                        RETURNING id
                        """)
                .param("eventId", eventId)
                .param("type", normalizeSessionType(imp.sessionType(), imp.sessionName()))
                .param("name", imp.sessionName())
                .param("start", imp.sessionStart())
                .param("mark", imp.reportMark())
                .param("message", imp.reportMessage())
                .query(Long.class)
                .single();

        for (RaceResultsImport.Row row : imp.rows()) {
            String className = canonicalizeClass(row.className(), knownClasses, mapping,
                    imp.championshipName() + " " + imp.sessionStart().getYear());
            long entryId = upsertEntry(eventId, row, className);
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

    private void commitStandings(StandingsImport imp, Map<String, String> mapping) {
        SeriesMatch match = matchSeriesByTitle(imp.mainTitle());
        long seasonId = findOrCreateSeason(match.seriesId(), Integer.parseInt(imp.year()));

        ClassAndKind ck = deriveClassAndKind(imp.mainTitle(), match.matchedPrefix());
        String kind = ck.kind();
        // Standings often spell classes differently from the entry list (e.g. the
        // Michelin Endurance Cup's "GT Daytona PRO" vs the entry-list "GTDPRO").
        // Resolve to the season's canonical (entry-list) class; an unrecognized
        // spelling fails the commit until it is mapped in the review screen.
        String className = canonicalizeClass(ck.className(), seasonEntryClasses(seasonId), mapping, imp.mainTitle());

        // Replace this championship wholesale (cascade removes sessions/rows/points).
        db.sql("DELETE FROM championship WHERE season_id = :seasonId AND name = :name")
                .param("seasonId", seasonId).param("name", imp.name()).update();
        long championshipId = db.sql("""
                        INSERT INTO championship (season_id, name, title, class_name, kind, group_title)
                        VALUES (:seasonId, :name, :title, :className, :kind, :groupTitle)
                        RETURNING id
                        """)
                .param("seasonId", seasonId)
                .param("name", imp.name())
                .param("title", imp.mainTitle())
                .param("className", className)
                .param("kind", kind)
                .param("groupTitle", match.matchedPrefix())
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

    private void commitEntryList(EntryListImport imp) {
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

        long seriesId = matchSeriesByCode(imp.event().series());
        long seasonId = findOrCreateSeason(seriesId, imp.event().startDate().getYear());

        Optional<Long> existing = db.sql("SELECT id FROM event WHERE season_id = :seasonId AND name = :name")
                .param("seasonId", seasonId).param("name", imp.event().name()).query(Long.class).optional();
        long eventId = existing.orElseGet(() -> db.sql("""
                        INSERT INTO event (season_id, name, circuit_name, event_date)
                        VALUES (:seasonId, :name, :circuit, :date)
                        RETURNING id
                        """)
                .param("seasonId", seasonId)
                .param("name", imp.event().name())
                .param("circuit", imp.event().circuit())
                .param("date", imp.event().endDate() != null ? imp.event().endDate() : imp.event().startDate())
                .query(Long.class)
                .single());

        for (EntryListImport.Entry e : imp.entries()) {
            // Class codes are normalized by dropping spaces so entry-list spelling
            // ("GTD PRO") joins with results-file spelling ("GTDPRO").
            String className = e.classCode() != null ? e.classCode().replace(" ", "") : null;
            long entryId = db.sql("""
                            INSERT INTO entry (event_id, car_number, class_name, team_name, vehicle, manufacturer,
                                               sponsor, tire, fuel)
                            VALUES (:eventId, :number, :className, :team, :vehicle, :manufacturer,
                                    :sponsor, :tire, :fuel)
                            ON CONFLICT (event_id, car_number) DO UPDATE
                                SET class_name = EXCLUDED.class_name,
                                    team_name = EXCLUDED.team_name,
                                    vehicle = EXCLUDED.vehicle,
                                    manufacturer = EXCLUDED.manufacturer,
                                    sponsor = EXCLUDED.sponsor,
                                    tire = EXCLUDED.tire,
                                    fuel = EXCLUDED.fuel
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

    /** Matches an entry list's series code (e.g. "IWSC") to a series by abbreviation or alias. */
    private long matchSeriesByCode(String code) {
        if (code == null || code.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Entry list carries no series code; rename the file to include it (e.g. IWSC)");
        }
        Optional<Long> match = db.sql("""
                        SELECT id FROM series WHERE lower(abbreviation) = lower(:code)
                        UNION
                        SELECT series_id FROM series_alias WHERE lower(alias) = lower(:code)
                        """)
                .param("code", code)
                .query(Long.class)
                .optional();
        return match.orElseThrow(() -> new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                "No series has abbreviation or alias '" + code
                + "'. Add it as an alias on the Series page."));
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

    private long findOrCreateEvent(long seasonId, RaceResultsImport imp) {
        LocalDateTime start = imp.sessionStart();
        Optional<Long> existing = db.sql("SELECT id FROM event WHERE season_id = :seasonId AND name = :name")
                .param("seasonId", seasonId).param("name", imp.eventName()).query(Long.class).optional();
        if (existing.isPresent()) {
            return existing.get();
        }
        return db.sql("""
                        INSERT INTO event (season_id, name, circuit_name, circuit_length_m, country, event_date)
                        VALUES (:seasonId, :name, :circuit, :length, :country, :date)
                        RETURNING id
                        """)
                .param("seasonId", seasonId)
                .param("name", imp.eventName())
                .param("circuit", imp.circuitName())
                .param("length", imp.circuitLengthM())
                .param("country", imp.circuitCountry())
                .param("date", start.toLocalDate())
                .query(Long.class)
                .single();
    }

    private long upsertEntry(long eventId, RaceResultsImport.Row row, String className) {
        // is_guest is deliberately untouched on update: it is user-managed state.
        return db.sql("""
                        INSERT INTO entry (event_id, car_number, class_name, team_name, vehicle, manufacturer, class_group)
                        VALUES (:eventId, :number, :className, :team, :vehicle, :manufacturer, :group)
                        ON CONFLICT (event_id, car_number) DO UPDATE
                            SET class_name = EXCLUDED.class_name,
                                team_name = EXCLUDED.team_name,
                                vehicle = EXCLUDED.vehicle,
                                manufacturer = EXCLUDED.manufacturer,
                                class_group = EXCLUDED.class_group
                        RETURNING id
                        """)
                .param("eventId", eventId)
                .param("number", row.number())
                .param("className", className)
                .param("team", row.team())
                .param("vehicle", row.vehicle())
                .param("manufacturer", resolveManufacturer(className, row.vehicle(), row.manufacturer()))
                .param("group", row.group())
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

    private Optional<Long> findSeriesByName(String name) {
        return db.sql("SELECT id FROM series WHERE lower(name) = lower(:name)")
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
