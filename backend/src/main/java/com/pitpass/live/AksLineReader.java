package com.pitpass.live;

import com.fasterxml.jackson.core.JsonProcessingException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Reads AKS V2 lines off the socket. Most lines are small and come back as a
 * buffer, bounded by {@code max-line-bytes}. A JSON frame can instead be
 * handed to a {@link StreamHandler} as a stream that ends at the line's
 * {@code \n}: a reconnect snapshot of {@code timing.analysis} is a single
 * 45–78 MB line, and holding it — let alone parsing it into a tree — does not
 * fit the heap.
 *
 * Every byte of every line goes through the {@link Tee} exactly once, in
 * order, so a recording is byte-identical whichever path a line took. A line
 * is its bytes without the CRLF (a lone trailing CR is dropped too), and blank
 * lines are skipped — the same rules for both paths.
 */
final class AksLineReader {

    /** Where a line's bytes are copied (the recording, the counters). */
    interface Tee {
        void write(byte[] bytes, int offset, int length);

        /** The line is complete, or was cut off by a fault. */
        void end();
    }

    interface TeeFactory {
        Tee begin(long epochMs);
    }

    /**
     * Consumes one streamed JSON frame. The stream holds the data after the
     * frame's third colon and reports end of stream at the end of the line.
     * Whatever the handler leaves unread is drained afterwards.
     */
    interface StreamHandler {
        void frame(String messageId, String channel, InputStream data) throws IOException;
    }

    /** A streamed line the handler could not parse. The line was still read and recorded in full. */
    static final class UnreadableFrame extends Exception {
        UnreadableFrame(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private final InputStream in;
    private final int maxLineBytes;
    private final long maxStreamedBytes;
    private final TeeFactory tees;
    private final byte[] buf = new byte[64 * 1024];
    private final ByteArrayOutputStream line = new ByteArrayOutputStream(16 * 1024);
    private int pos;
    private int lim;
    private volatile StreamHandler handler;

    AksLineReader(InputStream in, int maxLineBytes, long maxStreamedBytes, TeeFactory tees) {
        this.in = in;
        this.maxLineBytes = maxLineBytes;
        this.maxStreamedBytes = maxStreamedBytes;
        this.tees = tees;
    }

    /** From now on JSON frames are streamed to this handler instead of buffered. Null = buffer everything. */
    void streamJsonTo(StreamHandler handler) {
        this.handler = handler;
    }

    /** Bytes read into the current line that no line ending has closed yet — the clue when LOGIN times out. */
    byte[] unterminated() {
        return line.toByteArray();
    }

    /**
     * The next buffered line, already teed, or null at end of stream. A
     * streamed frame is handled inside this call and not returned; an
     * unparseable one surfaces as {@link UnreadableFrame} after it has been
     * read to its end, so the caller can warn and carry on.
     */
    byte[] next() throws IOException, UnreadableFrame {
        while (true) {
            line.reset();
            int colons = 0;
            int[] at = new int[3];
            int b = -1;
            // The frame header: up to the third colon, or the end of a short line.
            while (colons < 3 && (b = read()) != -1 && b != '\n') {
                guard(line.size());
                if (b == ':') {
                    at[colons++] = line.size();
                }
                line.write(b);
            }
            StreamHandler streaming = handler;
            if (colons == 3 && streaming != null && command(at[0]).equals("JSON")) {
                stream(streaming, at);
                continue;
            }
            // Buffered: the rest of the line.
            while (b != -1 && b != '\n' && (b = read()) != -1 && b != '\n') {
                guard(line.size());
                line.write(b);
            }
            byte[] bytes = line.toByteArray();
            int length = bytes.length;
            if (length > 0 && bytes[length - 1] == '\r') {
                length--;
            }
            if (length > 0) {
                byte[] out = length == bytes.length ? bytes : Arrays.copyOf(bytes, length);
                line.reset();
                Tee tee = tees.begin(System.currentTimeMillis());
                tee.write(out, 0, out.length);
                tee.end();
                return out;
            }
            if (b == -1) {
                line.reset();
                return null;
            }
        }
    }

    private void stream(StreamHandler streaming, int[] at) throws IOException, UnreadableFrame {
        byte[] header = line.toByteArray();
        line.reset();
        String messageId = text(header, at[0] + 1, at[1]);
        String channel = text(header, at[1] + 1, at[2]);
        Tee tee = tees.begin(System.currentTimeMillis());
        tee.write(header, 0, header.length);
        LineStream data = new LineStream(tee, header.length);
        UnreadableFrame unreadable = null;
        try {
            try {
                streaming.frame(messageId, channel, data);
            } catch (JsonProcessingException e) {
                unreadable = new UnreadableFrame("Unreadable JSON frame: " + e.getOriginalMessage(), e);
            }
            data.drain();
        } finally {
            tee.end();
        }
        if (unreadable != null) {
            throw unreadable;
        }
    }

    private String command(int firstColon) {
        return text(line.toByteArray(), 0, firstColon);
    }

    private static String text(byte[] bytes, int from, int to) {
        return new String(bytes, from, to - from, StandardCharsets.UTF_8).trim();
    }

    private void guard(int size) throws IOException {
        if (size >= maxLineBytes) {
            throw new IOException("Line exceeds " + maxLineBytes + " bytes");
        }
    }

    private int read() throws IOException {
        if (pos == lim && !fill()) {
            return -1;
        }
        return buf[pos++] & 0xff;
    }

    private boolean fill() throws IOException {
        int n = in.read(buf, 0, buf.length);
        pos = 0;
        lim = Math.max(0, n);
        return n > 0;
    }

    /**
     * The rest of one line, straight from the read buffer. Every byte handed
     * out is teed first. A CR is held back until the next byte shows whether
     * it ends the line.
     */
    private final class LineStream extends InputStream {
        private final Tee tee;
        private long count;
        private boolean ended;
        private boolean pendingCr;

        LineStream(Tee tee, long already) {
            this.tee = tee;
            this.count = already;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int n = read(one, 0, 1);
            return n == -1 ? -1 : one[0] & 0xff;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (ended) {
                return -1;
            }
            if (len == 0) {
                return 0;
            }
            int n = 0;
            while (n < len) {
                if (pos == lim && !fill()) {
                    ended = true; // end of stream: a dangling CR is dropped, as on the buffered path
                    break;
                }
                byte c = buf[pos];
                if (c == '\n') {
                    pos++;
                    ended = true;
                    break;
                }
                if (pendingCr) {
                    b[off + n++] = '\r'; // not a line ending after all; c is looked at again
                    pendingCr = false;
                    continue;
                }
                pos++;
                if (c == '\r') {
                    pendingCr = true;
                    continue;
                }
                b[off + n++] = c;
            }
            if (n == 0) {
                return -1;
            }
            count += n;
            if (count > maxStreamedBytes) {
                throw new IOException("Streamed line exceeds " + maxStreamedBytes + " bytes");
            }
            tee.write(b, off, n);
            return n;
        }

        void drain() throws IOException {
            byte[] skip = new byte[8192];
            while (read(skip, 0, skip.length) != -1) {
                // teed and discarded
            }
        }

        @Override
        public void close() {
            // the socket is not ours to close; drain() finishes the line
        }
    }
}
