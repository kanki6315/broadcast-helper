package com.pitpass.live;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * One line of the AKS V2 protocol: {@code command:[message-id[+]]:[channel]:[data]}.
 * Only the first three colons are structure — the data is JSON and full of
 * them. The data stays as bytes so a large snapshot is parsed straight from
 * the wire buffer without first becoming a String.
 */
public record AksFrame(String command, String messageId, String channel, byte[] data) {

    /** Thrown for a line that is not a frame; the connection is not trusted after one. */
    public static class MalformedFrame extends RuntimeException {
        public MalformedFrame(String message) {
            super(message);
        }
    }

    public static AksFrame parse(byte[] line, int length) {
        int[] colons = new int[3];
        int found = 0;
        for (int i = 0; i < length && found < 3; i++) {
            if (line[i] == ':') {
                colons[found++] = i;
            }
        }
        if (found < 3) {
            throw new MalformedFrame("Not an AKS frame: "
                    + new String(line, 0, Math.min(length, 80), StandardCharsets.UTF_8));
        }
        return new AksFrame(
                text(line, 0, colons[0]),
                text(line, colons[0] + 1, colons[1]),
                text(line, colons[1] + 1, colons[2]),
                trimmed(line, colons[2] + 1, length));
    }

    public static AksFrame parse(String line) {
        byte[] bytes = line.getBytes(StandardCharsets.UTF_8);
        return parse(bytes, bytes.length);
    }

    /** A client command as it goes on the wire, CRLF included. */
    public static byte[] encode(String command, long messageId, String channel, String json) {
        return (command + ":" + messageId + ":" + channel + ":" + json + "\r\n")
                .getBytes(StandardCharsets.UTF_8);
    }

    /** The server marks a reply by echoing the client's id with a trailing '+'. */
    public boolean isReplyTo(long id) {
        return messageId.equals(id + "+");
    }

    public boolean hasData() {
        return data.length > 0;
    }

    // The spec's own examples pad fields with spaces ("JSON: : : {"), so trim.
    private static String text(byte[] line, int from, int to) {
        return new String(line, from, to - from, StandardCharsets.UTF_8).trim();
    }

    private static byte[] trimmed(byte[] line, int from, int to) {
        while (from < to && line[from] <= ' ') {
            from++;
        }
        while (to > from && line[to - 1] <= ' ') {
            to--;
        }
        return Arrays.copyOfRange(line, from, to);
    }
}
