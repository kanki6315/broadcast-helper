package com.pitpass.live;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPInputStream;

/**
 * A stand-in AKS V2 server on localhost that plays a {@link LiveRecorder}
 * recording back over the real protocol — LOGIN, JOIN, PING/ACK and all — so
 * the production client code runs unchanged against it. It is how the feed is
 * developed against (ALKAMELV2_REPLAY_FILE) and what the tests drive, because
 * the account's one login is never available to either.
 *
 * Plain TCP: it only ever listens on loopback, and the JDK cannot mint a
 * certificate without shelling out to keytool.
 *
 * Like the real account it admits one login at a time. What the real server
 * does with a second login is not documented; this one refuses it.
 */
public final class AksReplayServer implements Closeable {

    public record Recorded(long epochMs, String line) {
    }

    private final List<Recorded> lines;
    private final double speed;
    private final int pingRateSeconds;
    private final ServerSocket server;
    private final AtomicInteger logins = new AtomicInteger();
    private final AtomicInteger pings = new AtomicInteger();

    private volatile Socket active;
    private volatile String rejectReason;
    private volatile boolean closed;

    /** speed: 1 = as recorded, 10 = ten times faster, 0 or less = no pauses at all. */
    public AksReplayServer(List<Recorded> lines, double speed, int pingRateSeconds) throws IOException {
        this.lines = List.copyOf(lines);
        this.speed = speed;
        this.pingRateSeconds = pingRateSeconds;
        this.server = new ServerSocket(0, 8, InetAddress.getLoopbackAddress());
    }

    /** Reads a recording, gzip or plain. Only JSON lines are kept — replies are regenerated live. */
    public static List<Recorded> load(Path file) throws IOException {
        List<Recorded> out = new ArrayList<>();
        try (InputStream raw = Files.newInputStream(file);
             InputStream in = file.toString().endsWith(".gz") ? new GZIPInputStream(raw) : raw;
             BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                int tab = line.indexOf('\t');
                if (tab > 0 && line.startsWith("JSON", tab + 1)) {
                    out.add(new Recorded(Long.parseLong(line.substring(0, tab)), line.substring(tab + 1)));
                }
            }
        } catch (java.io.EOFException truncated) {
            // A segment from a killed process ends mid-stream; what was read is good.
        }
        return out;
    }

    public AksReplayServer start() {
        Thread.ofVirtual().name("aks-replay-accept").start(this::acceptLoop);
        return this;
    }

    public int port() {
        return server.getLocalPort();
    }

    public int logins() {
        return logins.get();
    }

    public int pings() {
        return pings.get();
    }

    /** Answer every LOGIN with this ERROR until cleared with null. */
    public void rejectLogins(String reason) {
        this.rejectReason = reason;
    }

    /** Cut the current client off mid-session, as a network fault would. */
    public void dropClient() {
        Socket s = active;
        if (s != null) {
            closeQuietly(s);
        }
    }

    @Override
    public void close() {
        closed = true;
        closeQuietly(server);
        dropClient();
    }

    private void acceptLoop() {
        while (!closed) {
            try {
                Socket client = server.accept();
                Thread.ofVirtual().name("aks-replay-client").start(() -> serve(client));
            } catch (IOException e) {
                return; // closed
            }
        }
    }

    private void serve(Socket client) {
        boolean loggedIn = false;
        Thread streamer = null;
        try (client) {
            OutputStream out = client.getOutputStream();
            BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
            String line;
            while ((line = in.readLine()) != null) {
                AksFrame frame = AksFrame.parse(line);
                String reply = frame.messageId() + "+";
                switch (frame.command()) {
                    case "LOGIN" -> {
                        String refusal = rejectReason != null ? rejectReason
                                : active != null ? "User limit reached" : null;
                        if (refusal != null) {
                            write(out, "ERROR:" + reply + "::{\"reason\":\"" + refusal + "\",\"advice\":\"\"}");
                            return;
                        }
                        active = client;
                        loggedIn = true;
                        logins.incrementAndGet();
                        write(out, "LOGIN:" + reply + "::{\"name\":\"Pit Pass replay\",\"ver\":\"1.0.36\","
                                + "\"min_ver\":\"1.0.0\",\"pingRate\":\"" + pingRateSeconds + "\",\"timeout\":\""
                                + pingRateSeconds * 2 + "\"}");
                    }
                    case "JOIN" -> {
                        if (!loggedIn) {
                            write(out, "ERROR:" + reply + "::{\"reason\":\"Not logged in\",\"advice\":\"\"}");
                            return;
                        }
                        write(out, "JOIN:" + reply + ":" + frame.channel() + ":");
                        if (streamer == null) {
                            streamer = Thread.ofVirtual().name("aks-replay-stream").start(() -> stream(client, out));
                        }
                    }
                    case "LEAVE" -> write(out, "LEAVE:" + reply + ":" + frame.channel() + ":");
                    case "PING" -> {
                        pings.incrementAndGet();
                        write(out, "ACK:" + reply + "::");
                    }
                    default -> {
                        // nothing else is expected from a client
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            // client gone
        } finally {
            if (streamer != null) {
                streamer.interrupt();
            }
            if (loggedIn && active == client) {
                active = null;
            }
        }
    }

    // The recording was made with the same subscriptions, so it is sent whole
    // rather than filtered by what this client joined. When it runs out the
    // connection stays up and quiet, like a session between runs.
    private void stream(Socket client, OutputStream out) {
        try {
            long previous = -1;
            for (Recorded recorded : lines) {
                if (previous >= 0 && speed > 0) {
                    // Capped: a recording spanning a lunch break shouldn't stall a dev session.
                    long pause = (long) ((recorded.epochMs() - previous) / speed);
                    Thread.sleep(Math.max(0, Math.min(pause, 10_000)));
                }
                previous = recorded.epochMs();
                write(out, recorded.line());
            }
        } catch (InterruptedException | IOException e) {
            closeQuietly(client);
        }
    }

    private void write(OutputStream out, String line) throws IOException {
        synchronized (out) {
            out.write((line + "\r\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
        }
    }

    private static void closeQuietly(Closeable c) {
        try {
            c.close();
        } catch (IOException ignored) {
            // closing anyway
        }
    }
}
