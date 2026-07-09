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

    public ImportService(JdbcClient db, ObjectMapper json) {
        this.db = db;
        this.json = json;
    }

    // ---------------------------------------------------------------- staging

    public BatchSummary stage(String filename, byte[] content) {
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
        if (ImportParser.looksLikeStandings(root)) {
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

    // ---------------------------------------------------------------- commit

    @Transactional
    public BatchSummary commit(long id) {
        BatchSummary batch = get(id);
        if (!"STAGED".equals(batch.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Batch is not in STAGED state");
        }
        String payload = payloadJson(id);
        try {
            switch (batch.kind()) {
                case "RACE_RESULTS" -> commitRaceResults(json.readValue(payload, RaceResultsImport.class));
                case "STANDINGS" -> commitStandings(json.readValue(payload, StandingsImport.class));
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

    private void commitRaceResults(RaceResultsImport imp) {
        if (imp.sessionStart() == null) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Results file has no session date; cannot determine the season");
        }
        long seriesId = findOrCreateSeries(imp.championshipName());
        long seasonId = findOrCreateSeason(seriesId, imp.sessionStart().getYear());
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
            long entryId = upsertEntry(eventId, row);
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

    private void commitStandings(StandingsImport imp) {
        SeriesMatch match = matchSeriesByTitle(imp.mainTitle());
        long seasonId = findOrCreateSeason(match.seriesId(), Integer.parseInt(imp.year()));

        // Derive class/kind from what follows the matched prefix, e.g.
        // "IMSA WeatherTech SportsCar Championship GTP Teams"  -> "GTP Teams"
        // "IMSA Michelin Endurance Cup GT Daytona PRO Teams"   -> "GT Daytona PRO Teams" (via alias)
        String remainder = imp.mainTitle().substring(match.matchedPrefix().length()).trim();
        String kind = null;
        String className = null;
        int lastSpace = remainder.lastIndexOf(' ');
        if (lastSpace > 0) {
            kind = remainder.substring(lastSpace + 1).toUpperCase();
            className = remainder.substring(0, lastSpace).trim();
        } else if (!remainder.isEmpty()) {
            kind = remainder.toUpperCase();
        }

        // Replace this championship wholesale (cascade removes sessions/rows/points).
        db.sql("DELETE FROM championship WHERE season_id = :seasonId AND name = :name")
                .param("seasonId", seasonId).param("name", imp.name()).update();
        long championshipId = db.sql("""
                        INSERT INTO championship (season_id, name, title, class_name, kind)
                        VALUES (:seasonId, :name, :title, :className, :kind)
                        RETURNING id
                        """)
                .param("seasonId", seasonId)
                .param("name", imp.name())
                .param("title", imp.mainTitle())
                .param("className", className)
                .param("kind", kind)
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

    // ---------------------------------------------------------------- helpers

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

    private long upsertEntry(long eventId, RaceResultsImport.Row row) {
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
                .param("className", row.className())
                .param("team", row.team())
                .param("vehicle", row.vehicle())
                .param("manufacturer", row.manufacturer())
                .param("group", row.group())
                .query(Long.class)
                .single();
    }

    private void replaceDriverAssignments(long entryId, List<RaceResultsImport.DriverRow> drivers) {
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
            db.sql("""
                            INSERT INTO driver_assignment (entry_id, driver_id, seat_order, rating)
                            VALUES (:entryId, :driverId, :seat, :rating)
                            """)
                    .param("entryId", entryId)
                    .param("driverId", driverId)
                    .param("seat", d.seatOrder())
                    .param("rating", d.rating())
                    .update();
        }
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

    private String toJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
