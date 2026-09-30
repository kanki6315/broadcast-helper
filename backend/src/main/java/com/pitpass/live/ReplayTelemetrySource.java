package com.pitpass.live;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.GZIPInputStream;

/**
 * Plays a telemetry recording ({@code <epoch ms> TAB <websocket message>} per
 * line, gzip or plain) through the same {@link AppSyncSession} the live source
 * uses, so the decoding path is the production one. The recorded subscribe
 * ids are not this session's, so channels come back unknown and the decoder
 * tells cars from session clocks by shape — as it has to anyway.
 *
 * When the recording runs out the "connection" stays up and quiet, like a
 * session between runs.
 */
final class ReplayTelemetrySource implements TelemetrySource {

    private final Path file;
    private final double speed;
    private final ObjectMapper mapper;
    private volatile boolean closed;
    private volatile Thread runner;

    ReplayTelemetrySource(Path file, double speed, ObjectMapper mapper) {
        this.file = file;
        this.speed = speed;
        this.mapper = mapper;
    }

    @Override
    public void run(Listener listener) throws Exception {
        runner = Thread.currentThread();
        AppSyncSession session = new AppSyncSession(mapper, "replay", "replay", List.of(), new AppSyncSession.Output() {
            @Override
            public void send(String text) {
            }

            @Override
            public void event(String channel, String payload) {
                listener.event(channel, payload);
            }
        });
        listener.connected("replay of " + file.getFileName());
        long previous = -1;
        try (InputStream raw = Files.newInputStream(file);
             InputStream in = file.toString().endsWith(".gz") ? new GZIPInputStream(raw) : raw;
             BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while (!closed && (line = reader.readLine()) != null) {
                int tab = line.indexOf('\t');
                if (tab <= 0) {
                    continue;
                }
                long at = Long.parseLong(line.substring(0, tab));
                if (previous >= 0 && speed > 0) {
                    Thread.sleep(Math.max(0, Math.min((long) ((at - previous) / speed), 10_000)));
                }
                previous = at;
                String text = line.substring(tab + 1);
                listener.raw(System.currentTimeMillis(), text);
                try {
                    session.receive(text);
                } catch (AppSyncSession.ProtocolError e) {
                    // a recorded refusal: skip it, the replay goes on
                }
            }
        } catch (java.io.EOFException truncated) {
            // a segment from a killed process ends mid-stream; what was read is good
        } catch (InterruptedException e) {
            if (!closed) {
                throw e;
            }
        }
        while (!closed) {
            try {
                Thread.sleep(1_000);
            } catch (InterruptedException e) {
                // closed
            }
        }
        throw new IOException("Telemetry replay closed");
    }

    @Override
    public void close() {
        closed = true;
        Thread t = runner;
        if (t != null) {
            t.interrupt();
        }
    }
}
