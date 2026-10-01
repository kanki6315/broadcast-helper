package com.pitpass.live;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Race control's messages (spec 1.0.36 §4.2), read for the timing page.
 * raceControl.messages is the session's log, keyed by the feed's showTime;
 * raceControl.currentMessages is what race control's screen shows now, keyed
 * by line. Pure; the tower hands it the nodes from the state tree, and the
 * log endpoint the rows from live_race_control.
 *
 * Built from the spec alone: no recording has joined this channel yet.
 */
final class LiveRaceControl {

    private LiveRaceControl() {
    }

    /**
     * One message. key is the feed's (showTime for the log, the line for the
     * screen); dayTimeMs when it was shown, epoch ms UTC (null on the screen's
     * lines, which carry no time). Colours are #rrggbb or null — anything else
     * the feed sends is dropped rather than handed to a style attribute.
     */
    public record Message(String key, Long dayTimeMs, String text, String group, Integer line,
                          String foreground, String background, boolean blink) {
    }

    /**
     * For the strip above the tower: race control's screen as it is now
     * (lines in line order, often empty), and the newest message of the log.
     */
    public record Now(List<Message> lines, Message latest) {
    }

    private static final Pattern COLOR = Pattern.compile("#[0-9a-fA-F]{6}");

    /** Null when the feed has sent nothing for race control at all (channel off, or not joined yet). */
    static Now now(JsonNode current, JsonNode messages) {
        if (current == null && messages == null) {
            return null;
        }
        List<Message> lines = new ArrayList<>();
        if (current != null) {
            for (var f : current.properties()) {
                Message m = message(f.getKey(), f.getValue(), parseInt(f.getKey()));
                if (m != null) {
                    lines.add(m);
                }
            }
        }
        lines.sort(Comparator.comparing(Message::line, Comparator.nullsLast(Comparator.naturalOrder())));
        List<Message> log = log(messages);
        return new Now(lines, log.isEmpty() ? null : log.getFirst());
    }

    /** The log, newest first: by time shown, else by key. Blank and "null" messages are left out. */
    static List<Message> log(JsonNode messages) {
        List<Message> out = new ArrayList<>();
        if (messages != null) {
            for (var f : messages.properties()) {
                Message m = message(f.getKey(), f.getValue(), null);
                if (m != null) {
                    out.add(m);
                }
            }
        }
        out.sort(NEWEST_FIRST);
        return out;
    }

    static final Comparator<Message> NEWEST_FIRST = Comparator
            .comparing(Message::dayTimeMs, Comparator.nullsFirst(Comparator.<Long>naturalOrder()))
            .thenComparing(m -> parseLong(m.key()), Comparator.nullsFirst(Comparator.<Long>naturalOrder()))
            .thenComparing(Message::key)
            .reversed();

    private static Message message(String key, JsonNode m, Integer lineFromKey) {
        if (m == null || !m.isObject() || m.path("isNull").asBoolean(false)) {
            return null;
        }
        String text = m.hasNonNull("text") ? m.path("text").asText().strip() : "";
        if (text.isEmpty()) {
            return null;
        }
        return new Message(key, m.hasNonNull("dayTime") ? m.path("dayTime").asLong() : null, text,
                blankToNull(m.path("groupText").asText(null)),
                m.hasNonNull("line") ? Integer.valueOf(m.path("line").asInt()) : lineFromKey,
                color(m.path("foregroundColor").asText(null)), color(m.path("backgroundColor").asText(null)),
                m.path("blink").asBoolean(false));
    }

    /** A stored row, filtered as the feed's messages are. */
    static Message row(String key, Long dayTimeMs, String text, String group, Integer line, String foreground,
                       String background, Boolean blink, Boolean isNull) {
        if (Boolean.TRUE.equals(isNull) || text == null || text.isBlank()) {
            return null;
        }
        return new Message(key, dayTimeMs, text.strip(), blankToNull(group), line, color(foreground),
                color(background), Boolean.TRUE.equals(blink));
    }

    static String color(String c) {
        return c != null && COLOR.matcher(c.strip()).matches() ? c.strip().toLowerCase() : null;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    private static Integer parseInt(String s) {
        Long v = parseLong(s);
        return v == null || v > Integer.MAX_VALUE || v < Integer.MIN_VALUE ? null : v.intValue();
    }

    private static Long parseLong(String s) {
        try {
            return Long.parseLong(s.strip());
        } catch (NumberFormatException | NullPointerException e) {
            return null;
        }
    }
}
