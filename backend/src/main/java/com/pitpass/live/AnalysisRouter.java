package com.pitpass.live;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pitpass.live.AnalysisRows.EntriesChanged;
import com.pitpass.live.AnalysisRows.LapDeleted;
import com.pitpass.live.AnalysisRows.LapPatch;
import com.pitpass.live.AnalysisRows.Op;
import com.pitpass.live.AnalysisRows.Sector;
import com.pitpass.live.AnalysisRows.SessionInfo;
import com.pitpass.live.AnalysisRows.SessionSeen;
import com.pitpass.live.AnalysisRows.StintDeleted;
import com.pitpass.live.AnalysisRows.StintPatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Routes each streamed JSON frame, token by token, so {@code timing.analysis}
 * never exists in memory as a tree:
 *
 * - {@code timing.analysis.laps} and {@code .stints} become one small patch
 *   per lap or stint, handed to the writer's queue. {@code loopSectors} and
 *   {@code sections} — most of a lap's bytes — are skipped unread, as is every
 *   other analysis sub-channel.
 * - Everything else (all of {@code timing.session}) is read as a tree and
 *   merged into {@link AksStateTree} exactly as before.
 *
 * Nulls: a null lap or stint deletes that row. A null above that — a car, a
 * whole channel — clears the in-memory summaries but deletes nothing: the
 * history is kept, and a new session gets its own key.
 *
 * Runs on the socket thread, so it never waits: a full queue drops the patch
 * and counts it ({@link Sink#offer}).
 */
final class AnalysisRouter implements AksLineReader.StreamHandler {

    /** The writer's queue. False = full, the op was not taken. */
    interface Sink {
        boolean offer(Op op);
    }

    private static final Logger log = LoggerFactory.getLogger(AnalysisRouter.class);
    /** Paths walked into field by field; any other path under them is read whole into the tree. */
    private static final Set<String> WALKED = Set.of("", "timing", "timing.analysis");
    private static final int MAX_SECTOR = 64;

    private final ObjectMapper mapper;
    private final AksStateTree tree;
    private final Sink sink;
    private final LiveCarSummaries summaries;
    private final AtomicLong laps = new AtomicLong();
    private final AtomicLong stints = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong withoutSession = new AtomicLong();

    private SessionInfo session;

    AnalysisRouter(ObjectMapper mapper, AksStateTree tree, Sink sink, LiveCarSummaries summaries) {
        this.mapper = mapper;
        this.tree = tree;
        this.sink = sink;
        this.summaries = summaries;
    }

    long laps() {
        return laps.get();
    }

    long stints() {
        return stints.get();
    }

    /** Patches a full queue turned away. Recovered by the next reconnect's snapshot. */
    long dropped() {
        return dropped.get();
    }

    /** Laps and stints that arrived before any timing.session.info named the session. */
    long withoutSession() {
        return withoutSession.get();
    }

    @Override
    public void frame(String messageId, String channel, InputStream data) throws IOException {
        try (JsonParser p = mapper.getFactory().createParser(data)) {
            JsonToken first = p.nextToken();
            if (first == null) {
                return;
            }
            if (first != JsonToken.START_OBJECT) {
                p.skipChildren();
                return;
            }
            // Pushes are rooted at {"timing":…} with an empty channel header.
            // Should a JOIN snapshot instead be rooted at its channel, read it there.
            JsonToken t = p.nextToken();
            if (t != JsonToken.FIELD_NAME) {
                return;
            }
            if (!channel.isBlank() && channel.startsWith("timing.") && !"timing".equals(p.currentName())) {
                objectAtField(channel.trim(), p);
            } else {
                fields("", p);
            }
        }
    }

    /** The object at path, whose first FIELD_NAME the parser already stands on. */
    private void objectAtField(String path, JsonParser p) throws IOException {
        switch (path) {
            case "timing.analysis.laps" -> lapsChannel(p, true);
            case "timing.analysis.stints" -> stintsChannel(p, true);
            default -> {
                if (WALKED.contains(path)) {
                    fields(path, p);
                } else if (path.startsWith("timing.analysis")) {
                    for (JsonToken t = p.currentToken(); t == JsonToken.FIELD_NAME; t = p.nextToken()) {
                        p.nextToken();
                        p.skipChildren();
                    }
                } else {
                    JsonNode node = mapper.readTree(p); // Jackson reads an object from its first field on
                    merge(path, node);
                    if (path.startsWith("timing.session")) {
                        sessionChanged(path, node);
                    }
                }
            }
        }
    }

    /** Reads an object's fields; the parser stands on the first FIELD_NAME. */
    private void fields(String path, JsonParser p) throws IOException {
        for (JsonToken t = p.currentToken(); t == JsonToken.FIELD_NAME; t = p.nextToken()) {
            String key = p.currentName();
            p.nextToken();
            value(path.isEmpty() ? key : path + "." + key, p);
        }
    }

    private void value(String path, JsonParser p) throws IOException {
        JsonToken t = p.currentToken();
        switch (path) {
            case "timing.analysis.laps" -> {
                if (t == JsonToken.START_OBJECT) {
                    lapsChannel(p, false);
                } else {
                    channelCleared(path, p);
                }
                return;
            }
            case "timing.analysis.stints" -> {
                if (t == JsonToken.START_OBJECT) {
                    stintsChannel(p, false);
                } else {
                    channelCleared(path, p);
                }
                return;
            }
            default -> {
            }
        }
        if (path.startsWith("timing.analysis.")) {
            p.skipChildren(); // pitIn, pitOut and anything newer: laps and stints carry what we keep
            return;
        }
        if (WALKED.contains(path) && t == JsonToken.START_OBJECT) {
            if (p.nextToken() == JsonToken.FIELD_NAME) {
                fields(path, p);
            }
            return;
        }
        if (path.equals("timing.analysis")) {
            channelCleared(path, p);
            return;
        }
        if (path.equals("timing") && t == JsonToken.VALUE_NULL) {
            summaries.reset(null);
            session = null;
        }
        // Not analysis: into the tree, as every JSON frame went before streaming.
        JsonNode node = mapper.readTree(p);
        merge(path, node == null ? NullNode.getInstance() : node);
        if (path.equals("timing.session") || path.startsWith("timing.session.")) {
            sessionChanged(path, node);
        }
    }

    private void channelCleared(String path, JsonParser p) throws IOException {
        p.skipChildren();
        summaries.reset(session == null ? null : session.sessionDbId());
        log.debug("Live analysis: {} cleared; stored laps and stints are kept", path);
    }

    private void merge(String path, JsonNode node) {
        ObjectNode diff = mapper.createObjectNode();
        ObjectNode at = diff;
        String[] keys = path.split("\\.");
        for (int i = 0; i < keys.length - 1; i++) {
            at = at.putObject(keys[i]);
        }
        at.set(keys[keys.length - 1], node);
        tree.merge(diff);
    }

    /** After a session diff: a new session id starts fresh summaries; new entry data re-resolves drivers. */
    private void sessionChanged(String path, JsonNode node) {
        boolean touchesInfo = path.equals("timing.session") ? node == null || node.isNull() || node.has("info")
                : path.startsWith("timing.session.info");
        boolean touchesEntry = path.equals("timing.session") ? node != null && node.has("entry")
                : path.startsWith("timing.session.entry");
        if (touchesInfo) {
            JsonNode info = tree.copyOf("timing.session.info");
            SessionInfo now = info == null || !info.hasNonNull("sessionDbId") ? null : new SessionInfo(
                    info.path("sessionDbId").asLong(),
                    text(info, "sessionMongoId"),
                    info.hasNonNull("eventDbId") ? info.path("eventDbId").asLong() : null,
                    text(info, "name"), text(info, "type"),
                    info.hasNonNull("date") ? info.path("date").asLong() : null);
            if (!Objects.equals(now, session)) {
                if (now == null || session == null || now.sessionDbId() != session.sessionDbId()) {
                    summaries.reset(now == null ? null : now.sessionDbId());
                }
                session = now;
                if (now != null) {
                    offer(new SessionSeen(now));
                    touchesEntry = true; // the new session's drivers need resolving too
                }
            }
        }
        if (touchesEntry && session != null) {
            offer(new EntriesChanged(session.sessionDbId()));
        }
    }

    // ---- timing.analysis.laps.<car>.laps.<lap> ------------------------------------------

    private void lapsChannel(JsonParser p, boolean onField) throws IOException {
        for (JsonToken c = onField ? p.currentToken() : p.nextToken(); c == JsonToken.FIELD_NAME; c = p.nextToken()) {
            String car = p.currentName();
            JsonToken t = p.nextToken();
            if (t != JsonToken.START_OBJECT) {
                p.skipChildren(); // a car deleted wholesale: history kept
                continue;
            }
            while (p.nextToken() == JsonToken.FIELD_NAME) {
                String key = p.currentName();
                JsonToken v = p.nextToken();
                if (key.equals("laps") && v == JsonToken.START_OBJECT) {
                    carLaps(car, p);
                } else {
                    p.skipChildren();
                }
            }
        }
    }

    private void carLaps(String car, JsonParser p) throws IOException {
        while (p.nextToken() == JsonToken.FIELD_NAME) {
            Integer lap = parseInt(p.currentName());
            JsonToken t = p.nextToken();
            if (lap == null || (t != JsonToken.START_OBJECT && t != JsonToken.VALUE_NULL)) {
                p.skipChildren();
                continue;
            }
            Long sessionId = sessionId();
            if (sessionId == null) {
                p.skipChildren();
                continue;
            }
            if (t == JsonToken.VALUE_NULL) {
                offer(new LapDeleted(sessionId, car, lap));
                continue;
            }
            LapPatch patch = new LapPatch(sessionId, car, lap);
            while (p.nextToken() == JsonToken.FIELD_NAME) {
                String field = p.currentName();
                p.nextToken();
                lapField(patch, field, p);
            }
            laps.incrementAndGet();
            summaries.lap(patch);
            offer(patch);
        }
    }

    private void lapField(LapPatch l, String field, JsonParser p) throws IOException {
        switch (field) {
            case "driver" -> { l.present |= LapPatch.DRIVER; l.driverOrder = integer(p); }
            case "driverLapNum" -> { l.present |= LapPatch.DRIVER_LAP; l.driverLapNumber = integer(p); }
            case "position" -> { l.present |= LapPatch.POSITION; l.position = integer(p); }
            case "startTime" -> { l.present |= LapPatch.START; l.startTimeMs = number(p); }
            case "time" -> { l.present |= LapPatch.TIME; l.lapTimeMs = integer(p); }
            case "topSpeed" -> { l.present |= LapPatch.TOP_SPEED; l.topSpeed = decimal(p); }
            case "isValid" -> { l.present |= LapPatch.VALID; l.valid = bool(p); }
            case "isLongLap" -> { l.present |= LapPatch.LONG_LAP; l.longLap = bool(p); }
            case "isShortLap" -> { l.present |= LapPatch.SHORT_LAP; l.shortLap = bool(p); }
            case "trackLimits" -> { l.present |= LapPatch.TRACK_LIMITS; l.trackLimits = integer(p); }
            case "pitIn" -> { l.present |= LapPatch.PIT_IN; l.pitInMs = pitTime(p); }
            case "pitOut" -> { l.present |= LapPatch.PIT_OUT; l.pitOutMs = pitTime(p); }
            case "sectors" -> sectors(l, p);
            // loopSectors and sections are the bulk of a lap; lapNum repeats the key.
            default -> p.skipChildren();
        }
    }

    /** {driver, time}: only the time is kept, the lap already names the driver. */
    private Long pitTime(JsonParser p) throws IOException {
        if (p.currentToken() != JsonToken.START_OBJECT) {
            return number(p);
        }
        Long time = null;
        while (p.nextToken() == JsonToken.FIELD_NAME) {
            String key = p.currentName();
            p.nextToken();
            if (key.equals("time")) {
                time = number(p);
            } else {
                p.skipChildren();
            }
        }
        return time;
    }

    private void sectors(LapPatch l, JsonParser p) throws IOException {
        if (p.currentToken() == JsonToken.VALUE_NULL) {
            l.sectorsCleared = true;
            l.sectors = null;
            return;
        }
        if (p.currentToken() != JsonToken.START_OBJECT) {
            p.skipChildren();
            return;
        }
        while (p.nextToken() == JsonToken.FIELD_NAME) {
            Integer n = parseInt(p.currentName());
            JsonToken t = p.nextToken();
            if (n == null || n < 1 || n > MAX_SECTOR) {
                p.skipChildren();
                continue;
            }
            if (t == JsonToken.VALUE_NULL) {
                l.sectors().put(n, null);
                continue;
            }
            if (t != JsonToken.START_OBJECT) {
                p.skipChildren();
                continue;
            }
            Sector s = new Sector();
            while (p.nextToken() == JsonToken.FIELD_NAME) {
                String key = p.currentName();
                p.nextToken();
                switch (key) {
                    case "time" -> { s.hasTime = true; s.timeMs = integer(p); }
                    case "flag" -> { s.hasFlag = true; s.flag = string(p); }
                    default -> p.skipChildren();
                }
            }
            if (s.hasTime || s.hasFlag) {
                l.sectors().put(n, s);
            }
        }
    }

    // ---- timing.analysis.stints.<car>.stints.<startTime> ---------------------------------

    private void stintsChannel(JsonParser p, boolean onField) throws IOException {
        for (JsonToken c = onField ? p.currentToken() : p.nextToken(); c == JsonToken.FIELD_NAME; c = p.nextToken()) {
            String car = p.currentName();
            if (p.nextToken() != JsonToken.START_OBJECT) {
                p.skipChildren();
                continue;
            }
            while (p.nextToken() == JsonToken.FIELD_NAME) {
                String key = p.currentName();
                JsonToken v = p.nextToken();
                if (key.equals("stints") && v == JsonToken.START_OBJECT) {
                    carStints(car, p);
                } else {
                    p.skipChildren();
                }
            }
        }
    }

    private void carStints(String car, JsonParser p) throws IOException {
        while (p.nextToken() == JsonToken.FIELD_NAME) {
            Long start = parseLong(p.currentName());
            JsonToken t = p.nextToken();
            if (start == null || (t != JsonToken.START_OBJECT && t != JsonToken.VALUE_NULL)) {
                p.skipChildren();
                continue;
            }
            Long sessionId = sessionId();
            if (sessionId == null) {
                p.skipChildren();
                continue;
            }
            if (t == JsonToken.VALUE_NULL) {
                offer(new StintDeleted(sessionId, car, start));
                continue;
            }
            StintPatch s = new StintPatch(sessionId, car, start);
            while (p.nextToken() == JsonToken.FIELD_NAME) {
                String field = p.currentName();
                p.nextToken();
                switch (field) {
                    case "type" -> { s.present |= StintPatch.TYPE; s.type = string(p); }
                    case "pitType" -> { s.present |= StintPatch.PIT_TYPE; s.pitType = string(p); }
                    case "driver" -> { s.present |= StintPatch.DRIVER; s.driverOrder = integer(p); }
                    case "openLapNumber" -> { s.present |= StintPatch.OPEN_LAP; s.openLap = integer(p); }
                    case "closeLapNumber" -> { s.present |= StintPatch.CLOSE_LAP; s.closeLap = integer(p); }
                    case "finishTime" -> { s.present |= StintPatch.FINISH; s.finishTimeMs = number(p); }
                    case "driverAccumSessionTrackTime" -> { s.present |= StintPatch.ACCUM_SESSION_TRACK; s.accumSessionTrackMs = number(p); }
                    case "driverAccumSessionTime" -> { s.present |= StintPatch.ACCUM_SESSION; s.accumSessionMs = number(p); }
                    case "driverAccumTrackTime" -> { s.present |= StintPatch.ACCUM_TRACK; s.accumTrackMs = number(p); }
                    case "driverAccumTime" -> { s.present |= StintPatch.ACCUM; s.accumMs = number(p); }
                    // startTime repeats the key
                    default -> p.skipChildren();
                }
            }
            stints.incrementAndGet();
            summaries.stint(s);
            offer(s);
        }
    }

    // ---- helpers -----------------------------------------------------------------------

    private Long sessionId() {
        if (session == null) {
            if (withoutSession.getAndIncrement() == 0) {
                log.warn("Live analysis arrived before timing.session.info named the session; skipped until it does");
            }
            return null;
        }
        return session.sessionDbId();
    }

    private void offer(Op op) {
        if (!sink.offer(op)) {
            if (dropped.getAndIncrement() % 10_000 == 0) {
                log.warn("Live analysis queue full; {} patch(es) dropped so far", dropped.get());
            }
        }
    }

    /**
     * Tolerant readers: the server is older than the spec, so a number may
     * come as a string and a flag as 0/1. Anything unreadable is null, and a
     * nested value where a scalar was expected is skipped.
     */
    private static Long number(JsonParser p) throws IOException {
        return switch (p.currentToken()) {
            case VALUE_NUMBER_INT -> p.getLongValue();
            case VALUE_NUMBER_FLOAT -> Math.round(p.getDoubleValue());
            case VALUE_STRING -> parseLong(p.getText().trim());
            case VALUE_TRUE -> 1L;
            case VALUE_FALSE -> 0L;
            case START_OBJECT, START_ARRAY -> {
                p.skipChildren();
                yield null;
            }
            default -> null;
        };
    }

    private static Integer integer(JsonParser p) throws IOException {
        Long value = number(p);
        return value == null || value > Integer.MAX_VALUE || value < Integer.MIN_VALUE ? null : value.intValue();
    }

    private static Double decimal(JsonParser p) throws IOException {
        return switch (p.currentToken()) {
            case VALUE_NUMBER_INT, VALUE_NUMBER_FLOAT -> p.getDoubleValue();
            case VALUE_STRING -> {
                try {
                    yield Double.parseDouble(p.getText().trim());
                } catch (NumberFormatException e) {
                    yield null;
                }
            }
            case START_OBJECT, START_ARRAY -> {
                p.skipChildren();
                yield null;
            }
            default -> null;
        };
    }

    private static Boolean bool(JsonParser p) throws IOException {
        return switch (p.currentToken()) {
            case VALUE_TRUE -> true;
            case VALUE_FALSE -> false;
            case VALUE_NUMBER_INT -> p.getLongValue() != 0;
            case VALUE_STRING -> List.of("true", "1", "yes").contains(p.getText().trim().toLowerCase())
                    ? Boolean.TRUE : List.of("false", "0", "no").contains(p.getText().trim().toLowerCase()) ? Boolean.FALSE : null;
            case START_OBJECT, START_ARRAY -> {
                p.skipChildren();
                yield null;
            }
            default -> null;
        };
    }

    private static String string(JsonParser p) throws IOException {
        return switch (p.currentToken()) {
            case VALUE_NULL -> null;
            case START_OBJECT, START_ARRAY -> {
                p.skipChildren();
                yield null;
            }
            default -> p.getText();
        };
    }

    private static Integer parseInt(String text) {
        Long value = parseLong(text);
        return value == null || value > Integer.MAX_VALUE || value < Integer.MIN_VALUE ? null : value.intValue();
    }

    private static Long parseLong(String text) {
        try {
            return Long.parseLong(text.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.path(field).asText() : null;
    }
}
