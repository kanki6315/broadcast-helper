package com.pitpass.live;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.zip.GZIPOutputStream;

/**
 * Writes every inbound line of one connection to gzip segments, each line as
 * {@code <receive epoch ms> TAB <raw line>}. Al Kamel offers no test or replay
 * server and the account's single login belongs to production, so these files
 * are the only way the feed is ever seen in development.
 *
 * Inbound only, by design: the one outbound line worth recording is LOGIN,
 * and it carries the password.
 *
 * A recording fault must never cost the live connection, so nothing here
 * throws: a segment that cannot be written is abandoned and logged.
 */
final class LiveRecorder implements Closeable {

    /** Takes a finished segment off the recorder's hands (upload, prune…). */
    interface Sink {
        void finished(Path segment, String key);
    }

    private static final Logger log = LoggerFactory.getLogger(LiveRecorder.class);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    private final Path directory;
    private final Duration segmentLength;
    private final Sink sink;
    private final String connectionStamp = STAMP.format(Instant.now());

    private OutputStream out;
    private Path current;
    private Instant openedAt;
    private int sequence;
    private boolean failed;
    private long lastFlushMs;

    LiveRecorder(Path directory, Duration segmentLength, Sink sink) {
        this.directory = directory;
        this.segmentLength = segmentLength;
        this.sink = sink;
    }

    synchronized void write(long epochMs, byte[] line) {
        if (failed) {
            return;
        }
        try {
            if (out != null && Duration.between(openedAt, Instant.now()).compareTo(segmentLength) >= 0) {
                finish(false);
            }
            if (out == null) {
                open();
            }
            out.write(Long.toString(epochMs).getBytes(StandardCharsets.US_ASCII));
            out.write('\t');
            out.write(line);
            out.write('\n');
            // The stream is opened sync-flush; flushing every few seconds is what
            // actually bounds the loss when the process is killed outright.
            if (epochMs - lastFlushMs >= 5_000) {
                out.flush();
                lastFlushMs = epochMs;
            }
        } catch (IOException e) {
            failed = true;
            log.warn("Live timing recording stopped for this connection: {}", e.toString());
        }
    }

    /**
     * Stores the last segment on the calling thread: at shutdown a background
     * upload would die with the JVM, and on Railway the local disk dies too.
     */
    @Override
    public synchronized void close() {
        try {
            finish(true);
        } catch (IOException e) {
            log.warn("Could not finish live timing segment: {}", e.toString());
        }
    }

    private void open() throws IOException {
        Files.createDirectories(directory);
        sequence++;
        current = directory.resolve("%s-%04d.aks.gz".formatted(connectionStamp, sequence));
        // syncFlush so a killed process leaves a segment readable up to the last flush (see write).
        out = new GZIPOutputStream(Files.newOutputStream(current), 64 * 1024, true);
        openedAt = Instant.now();
    }

    private void finish(boolean inline) throws IOException {
        if (out == null) {
            return;
        }
        OutputStream closing = out;
        Path finished = current;
        String key = "aks-v2/" + DAY.format(openedAt) + "/" + finished.getFileName();
        out = null;
        current = null;
        closing.close();
        Runnable store = () -> {
            try {
                sink.finished(finished, key);
            } catch (RuntimeException e) {
                log.warn("Live timing segment {} was not stored: {}", finished.getFileName(), e.toString());
            }
        };
        if (inline) {
            store.run();
        } else {
            // Mid-connection, off the reader thread: an upload takes seconds the socket shouldn't wait for.
            Thread.ofVirtual().name("aks-recording-sink").start(store);
        }
    }
}
