package com.pitpass.documents;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pitpass.sheets.SheetController;
import com.pitpass.web.HttpCaching;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Pit-lane box assignments, from IMSA's per-event pit-lane-assignments PDF.
 * The lane is shared by several series (one CAR#/TEAM column pair each);
 * upload runs the sidecar (parser/parse_pit_assignments.py), picks the column
 * that matches the event's entries best, and returns a <em>proposal</em> —
 * nothing but the PDF bytes is persisted until the admin confirms the
 * reviewed mapping with a PUT. Revised sheets drop mid-weekend; re-uploading
 * repeats the same flow and the confirm replaces the rows wholesale.
 */
@RestController
@RequestMapping("/api")
public class PitAssignmentController {

    private static final String KIND = "PIT_ASSIGNMENTS";

    private final JdbcClient db;
    private final ObjectMapper json;
    private final String parserPython;
    private final String parserScript;

    public PitAssignmentController(JdbcClient db, ObjectMapper json,
                                   @Value("${pit-pass.entry-list-parser.python:python3}") String parserPython,
                                   @Value("${pit-pass.pit-assignments-parser.script:../parser/parse_pit_assignments.py}") String parserScript) {
        this.db = db;
        this.json = json;
        this.parserPython = parserPython;
        this.parserScript = parserScript;
    }

    public record Landmark(int afterBox, String label) {
    }

    public record ProposalRow(int boxNumber, String carNumber, String teamName, Long entryId,
                              String entryTeam, String className) {
    }

    /** Parse result awaiting review; matchCounts shows why the column won. */
    public record Proposal(String versionNote, String seriesColumn, Map<String, Integer> matchCounts,
                           List<ProposalRow> rows, List<Landmark> landmarks) {
    }

    public record AssignmentRow(int boxNumber, String carNumber, String teamName, Long entryId,
                                String entryTeam, String className) {
    }

    public record PitAssignments(String filename, OffsetDateTime uploadedAt, long version, String versionNote,
                                 List<AssignmentRow> rows, List<Landmark> landmarks) {
    }

    @GetMapping("/events/{eventId}/pit-assignments")
    public PitAssignments get(@PathVariable long eventId) {
        record Doc(String filename, OffsetDateTime uploadedAt, String note) {
        }
        Doc doc = db.sql("""
                        SELECT source_filename, uploaded_at, note
                        FROM event_document WHERE event_id = :id AND kind = :kind
                        """)
                .param("id", eventId).param("kind", KIND)
                .query((rs, i) -> new Doc(rs.getString("source_filename"),
                        rs.getObject("uploaded_at", OffsetDateTime.class), rs.getString("note")))
                .optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No pit assignments for this event"));
        List<AssignmentRow> rows = db.sql("""
                        SELECT a.box_number, a.car_number, a.team_name, a.entry_id,
                               en.team_name AS entry_team, en.class_name
                        FROM pit_box_assignment a
                                 LEFT JOIN entry en ON en.id = a.entry_id
                        WHERE a.event_id = :id
                        ORDER BY a.box_number
                        """)
                .param("id", eventId)
                .query((rs, i) -> new AssignmentRow(rs.getInt("box_number"), rs.getString("car_number"),
                        rs.getString("team_name"), rs.getObject("entry_id", Long.class),
                        rs.getString("entry_team"), rs.getString("class_name")))
                .list();
        List<Landmark> landmarks = db.sql("""
                        SELECT after_box, label FROM pit_lane_landmark
                        WHERE event_id = :id ORDER BY ordinal
                        """)
                .param("id", eventId)
                .query((rs, i) -> new Landmark(rs.getInt("after_box"), rs.getString("label")))
                .list();
        return new PitAssignments(doc.filename(), doc.uploadedAt(), doc.uploadedAt().toInstant().toEpochMilli(),
                doc.note(), rows, landmarks);
    }

    /**
     * Stores the PDF and returns the parsed proposal for review. Existing
     * confirmed rows are left untouched — they only change when the reviewed
     * replacement is PUT, so an abandoned upload can't blank the modal
     * mid-broadcast.
     */
    @PostMapping("/events/{eventId}/pit-assignments/upload")
    @Transactional
    public Proposal upload(@PathVariable long eventId, @RequestParam("file") MultipartFile file) {
        requireEvent(eventId);
        byte[] data;
        try {
            data = file.getBytes();
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Could not read upload: " + e.getMessage());
        }
        if (data.length < 5 || data[0] != '%' || data[1] != 'P' || data[2] != 'D' || data[3] != 'F') {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Not a PDF: " + file.getOriginalFilename());
        }

        JsonNode parsed = runParser(file.getOriginalFilename(), data);
        String versionNote = parsed.path("version_note").isTextual() ? parsed.get("version_note").asText() : null;

        db.sql("""
                        INSERT INTO event_document (event_id, kind, source_filename, content_type, data, note)
                        VALUES (:eventId, :kind, :filename, 'application/pdf', :data, :note)
                        ON CONFLICT (event_id, kind) DO UPDATE
                            SET source_filename = EXCLUDED.source_filename,
                                data = EXCLUDED.data,
                                note = EXCLUDED.note,
                                uploaded_at = now()
                        """)
                .param("eventId", eventId)
                .param("kind", KIND)
                .param("filename", file.getOriginalFilename())
                .param("data", data)
                .param("note", versionNote)
                .update();

        return propose(eventId, parsed, versionNote);
    }

    public record SaveRow(Integer boxNumber, String carNumber, String teamName, Long entryId) {
    }

    public record SaveRequest(List<SaveRow> rows, List<Landmark> landmarks) {
    }

    /** The reviewed mapping: replaces any previous assignments wholesale. */
    @PutMapping("/events/{eventId}/pit-assignments")
    @Transactional
    public PitAssignments save(@PathVariable long eventId, @RequestBody SaveRequest request) {
        requireEvent(eventId);
        db.sql("SELECT 1 FROM event_document WHERE event_id = :id AND kind = :kind")
                .param("id", eventId).param("kind", KIND)
                .query(Integer.class)
                .optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Upload the pit-assignments PDF before saving"));
        List<SaveRow> rows = request.rows() == null ? List.of() : request.rows();
        List<Landmark> landmarks = request.landmarks() == null ? List.of() : request.landmarks();

        Set<Long> entryIds = new java.util.HashSet<>(db.sql("SELECT id FROM entry WHERE event_id = :id")
                .param("id", eventId).query(Long.class).list());
        Set<Integer> seenBoxes = new java.util.HashSet<>();
        for (SaveRow row : rows) {
            if (row.boxNumber() == null || row.boxNumber() < 1) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Box numbers must be positive");
            }
            if (row.carNumber() == null || row.carNumber().isBlank()) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "Box " + row.boxNumber() + " has no car number");
            }
            if (!seenBoxes.add(row.boxNumber())) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "Box " + row.boxNumber() + " appears twice");
            }
            if (row.entryId() != null && !entryIds.contains(row.entryId())) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "Box " + row.boxNumber() + ": entry " + row.entryId() + " is not in this event");
            }
        }

        db.sql("DELETE FROM pit_box_assignment WHERE event_id = :id").param("id", eventId).update();
        db.sql("DELETE FROM pit_lane_landmark WHERE event_id = :id").param("id", eventId).update();
        for (SaveRow row : rows) {
            db.sql("""
                            INSERT INTO pit_box_assignment (event_id, box_number, car_number, team_name, entry_id)
                            VALUES (:eventId, :box, :car, :team, :entryId)
                            """)
                    .param("eventId", eventId)
                    .param("box", row.boxNumber())
                    .param("car", row.carNumber().trim())
                    .param("team", row.teamName())
                    .param("entryId", row.entryId())
                    .update();
        }
        int ordinal = 0;
        for (Landmark mark : landmarks) {
            if (mark.label() == null || mark.label().isBlank()) {
                continue;
            }
            db.sql("""
                            INSERT INTO pit_lane_landmark (event_id, ordinal, after_box, label)
                            VALUES (:eventId, :ordinal, :afterBox, :label)
                            """)
                    .param("eventId", eventId)
                    .param("ordinal", ordinal++)
                    .param("afterBox", Math.max(0, mark.afterBox()))
                    .param("label", mark.label().trim())
                    .update();
        }
        return get(eventId);
    }

    @GetMapping("/events/{eventId}/pit-assignments/data")
    public ResponseEntity<byte[]> data(@PathVariable long eventId,
                                       @RequestParam(required = false) String v) {
        byte[] pdf = db.sql("SELECT data FROM event_document WHERE event_id = :id AND kind = :kind")
                .param("id", eventId).param("kind", KIND)
                .query(byte[].class)
                .optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No pit assignments for this event"));
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header("Cache-Control", HttpCaching.cacheControl(v != null))
                .body(pdf);
    }

    @DeleteMapping("/events/{eventId}/pit-assignments")
    @Transactional
    public void delete(@PathVariable long eventId) {
        db.sql("DELETE FROM pit_box_assignment WHERE event_id = :id").param("id", eventId).update();
        db.sql("DELETE FROM pit_lane_landmark WHERE event_id = :id").param("id", eventId).update();
        int deleted = db.sql("DELETE FROM event_document WHERE event_id = :id AND kind = :kind")
                .param("id", eventId).param("kind", KIND)
                .update();
        if (deleted == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No pit assignments for this event");
        }
    }

    // ---------------------------------------------------------------- helpers

    private record Entry(long id, String carNumber, String teamName, String className) {
    }

    /** Picks the series column matching the event's entries and pairs rows up. */
    Proposal propose(long eventId, JsonNode parsed, String versionNote) {
        Map<String, List<Entry>> entriesByNumber = new HashMap<>();
        db.sql("SELECT id, car_number, team_name, class_name FROM entry WHERE event_id = :id")
                .param("id", eventId)
                .query((rs, i) -> {
                    Entry en = new Entry(rs.getLong("id"), rs.getString("car_number"),
                            rs.getString("team_name"), rs.getString("class_name"));
                    entriesByNumber.computeIfAbsent(SheetController.normalizeCarNumber(en.carNumber()),
                            k -> new ArrayList<>()).add(en);
                    return en;
                })
                .list();

        // Column choice by match count, not header text: the app's series name
        // ("IMSA WeatherTech SportsCar Championship") never literally matches
        // the PDF's column key ("IWSC").
        Map<String, Integer> matchCounts = new LinkedHashMap<>();
        for (JsonNode series : parsed.path("series")) {
            matchCounts.put(series.asText(), 0);
        }
        for (JsonNode box : parsed.path("boxes")) {
            box.path("cars").properties().forEach(cars -> {
                String number = cars.getValue().path("car_number").asText(null);
                if (number != null && entriesByNumber.containsKey(SheetController.normalizeCarNumber(number))) {
                    matchCounts.merge(cars.getKey(), 1, Integer::sum);
                }
            });
        }
        String column = matchCounts.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .filter(best -> best.getValue() > 0)
                .map(Map.Entry::getKey)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "No series column in the PDF matches this event's entries"));

        List<ProposalRow> rows = new ArrayList<>();
        for (JsonNode box : parsed.path("boxes")) {
            JsonNode car = box.path("cars").path(column);
            String number = car.path("car_number").asText(null);
            if (number == null) {
                continue;
            }
            String team = car.path("team").asText(null);
            Entry matched = match(entriesByNumber.get(SheetController.normalizeCarNumber(number)), team);
            rows.add(new ProposalRow(box.path("box").asInt(), number, team,
                    matched != null ? matched.id() : null,
                    matched != null ? matched.teamName() : null,
                    matched != null ? matched.className() : null));
        }

        List<Landmark> landmarks = new ArrayList<>();
        for (JsonNode mark : parsed.path("landmarks")) {
            landmarks.add(new Landmark(mark.path("after_box").asInt(), mark.path("label").asText()));
        }
        return new Proposal(versionNote, column, matchCounts, rows, landmarks);
    }

    /** Same normalized number in two classes: break the tie on team-name words. */
    private static Entry match(List<Entry> candidates, String pdfTeam) {
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        if (candidates.size() == 1 || pdfTeam == null) {
            return candidates.getFirst();
        }
        Set<String> pdfWords = tokens(pdfTeam);
        Entry best = candidates.getFirst();
        long bestOverlap = -1;
        for (Entry candidate : candidates) {
            long overlap = tokens(candidate.teamName()).stream().filter(pdfWords::contains).count();
            if (overlap > bestOverlap) {
                bestOverlap = overlap;
                best = candidate;
            }
        }
        return best;
    }

    private static Set<String> tokens(String name) {
        return name == null ? Set.of()
                : java.util.Arrays.stream(name.toLowerCase(Locale.ROOT).split("\\W+"))
                        .filter(t -> !t.isBlank())
                        .collect(java.util.stream.Collectors.toSet());
    }

    private void requireEvent(long eventId) {
        long count = db.sql("SELECT count(*) FROM event WHERE id = :id").param("id", eventId)
                .query(Long.class).single();
        if (count == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such event");
        }
    }

    /** Runs the sidecar (parser/parse_pit_assignments.py): PDF in, JSON out. */
    private JsonNode runParser(String filename, byte[] pdf) {
        try {
            java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("pit-assignments-");
            String safeName = java.nio.file.Path.of(filename == null ? "pit-assignments.pdf" : filename)
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
                            "Pit-assignments parser timed out on " + filename);
                }
                if (process.exitValue() != 0) {
                    throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                            "Pit-assignments parser failed on " + filename + ": " + err.trim());
                }
                return json.readTree(out);
            } finally {
                java.nio.file.Files.deleteIfExists(tmp);
                java.nio.file.Files.deleteIfExists(dir);
            }
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Could not run pit-assignments parser (" + parserPython + " " + parserScript + "): "
                            + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Pit-assignments parser interrupted");
        }
    }
}
