package com.pitpass.live;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.security.GeneralSecurityException;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One login's worth of the AKS V2 protocol: connect, LOGIN, JOIN the
 * channels, then PING on the server's schedule while merging every JSON diff
 * into the state tree. {@link #run} blocks for the life of the connection and
 * always ends by exception or {@link #close}; deciding whether and when to
 * try again belongs to {@link LiveTimingService}.
 */
final class AksConnection implements Closeable {

    /** What the connection reports upward. Called on the reader thread. */
    interface Listener {
        void loggedIn(ServerInfo server);

        /** Every inbound line, exactly as received — this is what gets recorded. */
        void received(long epochMs, byte[] line);

        /** A non-fatal ERROR from the server (a refused JOIN, say). */
        void warned(String message);
    }

    record ServerInfo(String name, String version, int pingRateSeconds, int timeoutSeconds) {
    }

    /** LOGIN was answered with ERROR — wrong credentials, or the one login is in use. */
    static final class LoginRejected extends IOException {
        LoginRejected(String message) {
            super(message);
        }
    }

    private static final Logger log = LoggerFactory.getLogger(AksConnection.class);
    private static final String PROTOCOL = "AKS V2 Protocol";
    private static final String PROTOCOL_VERSION = "1.0.36";

    private final AlKamelV2Properties props;
    private final String host;
    private final int port;
    private final boolean tls;
    private final AksStateTree tree;
    private final Listener listener;
    private final ObjectMapper mapper;
    private final AtomicLong nextId = new AtomicLong(1);
    private final Map<Long, String> pending = new ConcurrentHashMap<>();
    private final Object writeLock = new Object();
    private final ByteArrayOutputStream lineBuffer = new ByteArrayOutputStream(16 * 1024);

    private volatile Socket socket;
    private volatile boolean closed;

    AksConnection(AlKamelV2Properties props, String host, int port, boolean tls,
                  AksStateTree tree, ObjectMapper mapper, Listener listener) {
        this.props = props;
        this.host = host;
        this.port = port;
        this.tls = tls;
        this.tree = tree;
        this.mapper = mapper;
        this.listener = listener;
    }

    void run() throws IOException {
        Thread pinger = null;
        try {
            Socket s = open();
            socket = s;
            if (closed) {
                throw new IOException("Closed while connecting");
            }
            InputStream in = new BufferedInputStream(s.getInputStream(), 64 * 1024);
            OutputStream out = s.getOutputStream();

            s.setSoTimeout(loginTimeoutMs());
            ServerInfo server = login(in, out);
            listener.loggedIn(server);

            // We ping every pingRate and each ping is ACKed, so a silence
            // longer than the server's own timeout means the link is dead.
            s.setSoTimeout(server.timeoutSeconds() * 1000);
            for (String channel : props.channels()) {
                long id = send(out, "JOIN", channel.trim(), "");
                pending.put(id, "JOIN " + channel.trim());
            }
            pinger = Thread.ofVirtual().name("aks-ping").start(() -> pingLoop(out, server.pingRateSeconds()));

            byte[] line;
            while ((line = readLine(in)) != null) {
                handle(line);
            }
            throw new IOException("Server closed the connection");
        } finally {
            if (pinger != null) {
                pinger.interrupt();
            }
            close();
        }
    }

    @Override
    public void close() {
        closed = true;
        Socket s = socket;
        if (s != null) {
            try {
                s.close(); // also unblocks the reader
            } catch (IOException ignored) {
                // closing anyway
            }
        }
    }

    // Every stage names itself when it fails: "Read timed out" alone cannot say
    // whether the port was unreachable, not speaking TLS, or deaf to LOGIN.
    private Socket open() throws IOException {
        String where = host + ":" + port;
        long started = System.nanoTime();
        Socket plain = new Socket();
        try {
            plain.connect(new InetSocketAddress(host, port), props.connectTimeoutSeconds() * 1000);
        } catch (IOException e) {
            plain.close();
            throw new IOException("Could not reach " + where + " (" + reason(e) + ")", e);
        }
        plain.setKeepAlive(true);
        plain.setTcpNoDelay(true);
        log.info("Live timing: TCP connected to {} ({}) in {} ms", where,
                plain.getInetAddress().getHostAddress(), (System.nanoTime() - started) / 1_000_000);
        if (!tls) {
            return plain;
        }
        try {
            // Layered over the connected socket so the host name is carried for SNI.
            SSLContext context = props.tlsVerifyCertificate() ? SSLContext.getDefault() : trustingContext();
            SSLSocket secure = (SSLSocket) context.getSocketFactory().createSocket(plain, host, port, true);
            if (props.tlsVerifyCertificate()) {
                SSLParameters params = secure.getSSLParameters();
                params.setEndpointIdentificationAlgorithm("HTTPS");
                secure.setSSLParameters(params);
            }
            secure.setSoTimeout(loginTimeoutMs());
            secure.startHandshake();
            log.info("Live timing: TLS handshake done ({}, {})", secure.getSession().getProtocol(),
                    secure.getSession().getCipherSuite());
            return secure;
        } catch (SocketTimeoutException e) {
            plain.close();
            throw new IOException("TLS handshake with " + where + " got no answer in " + props.loginTimeoutSeconds()
                    + "s: the port accepted the connection but is not answering TLS", e);
        } catch (GeneralSecurityException | IOException e) {
            plain.close();
            throw new IOException("TLS handshake with " + where + " failed (" + reason(e) + ")", e);
        }
    }

    private int loginTimeoutMs() {
        return Math.max(1, props.loginTimeoutSeconds()) * 1000;
    }

    private static String reason(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    /**
     * The spec instructs clients to "ignore certificate errors" — the timing
     * servers present certificates no public CA signed. This context is built
     * per connection and handed to nothing else; the JVM default is untouched.
     */
    private static SSLContext trustingContext() throws GeneralSecurityException {
        TrustManager trustAll = new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        };
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, new TrustManager[] {trustAll}, null);
        return context;
    }

    private ServerInfo login(InputStream in, OutputStream out) throws IOException {
        ObjectNode credentials = mapper.createObjectNode()
                .put("user", props.username())
                .put("password", props.password())
                .put("app", props.clientAppName())
                .put("app_ver", "1.0.0")
                .put("protocol", PROTOCOL)
                .put("protocol_ver", PROTOCOL_VERSION);
        send(out, "LOGIN", "", mapper.writeValueAsString(credentials));
        log.info("Live timing: LOGIN sent as '{}', waiting for the reply", props.username());

        int lines = 0;
        String last = null;
        try {
            byte[] line;
            while ((line = readLine(in)) != null) {
                lines++;
                last = preview(line, line.length);
                listener.received(System.currentTimeMillis(), line);
                AksFrame frame = AksFrame.parse(line, line.length);
                if (frame.command().equals("ERROR")) {
                    throw new LoginRejected("Login refused: " + describeError(frame));
                }
                // Any LOGIN frame at this point is the reply: nothing else was
                // asked, so how the server echoes the message id is not tested.
                if (frame.command().equals("LOGIN")) {
                    JsonNode info = frame.hasData() ? mapper.readTree(frame.data()) : mapper.createObjectNode();
                    return new ServerInfo(
                            info.path("name").asText(""),
                            info.path("ver").asText(""),
                            seconds(info.path("pingRate"), 20),
                            seconds(info.path("timeout"), 40));
                }
                log.info("Live timing: before the LOGIN reply the server sent: {}", last);
            }
        } catch (SocketTimeoutException e) {
            // What did arrive is the whole clue — a banner, a reply in a framing
            // we do not read, or nothing at all. Inbound only: never our password.
            String partial = lineBuffer.size() > 0 ? preview(lineBuffer.toByteArray(), lineBuffer.size()) : null;
            throw new IOException("Connected" + (tls ? " over TLS" : "") + " and sent LOGIN, but no reply in "
                    + props.loginTimeoutSeconds() + "s. Received " + lines + " line(s)"
                    + (last != null ? ", last: " + last : "")
                    + (partial != null ? "; unterminated bytes: " + partial : "") , e);
        }
        throw new IOException("Server closed the connection " + (lines == 0 ? "without answering LOGIN" : "during login, after: " + last));
    }

    /** Up to 160 bytes of what the server sent, control characters made visible. */
    private static String preview(byte[] bytes, int length) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < Math.min(length, 160); i++) {
            int b = bytes[i] & 0xff;
            out.append(b >= 0x20 && b < 0x7f ? String.valueOf((char) b) : String.format("\\x%02x", b));
        }
        return length > 160 ? out + "…" : out.toString();
    }

    private void handle(byte[] line) throws IOException {
        listener.received(System.currentTimeMillis(), line);
        AksFrame frame = AksFrame.parse(line, line.length);
        switch (frame.command()) {
            case "JSON" -> {
                if (frame.hasData() && mapper.readTree(frame.data()) instanceof ObjectNode diff) {
                    tree.merge(diff);
                }
            }
            case "JOIN", "LEAVE" -> pending.remove(replyId(frame));
            case "ERROR" -> {
                String what = pending.remove(replyId(frame));
                listener.warned((what == null ? "Server error" : what + " refused") + ": " + describeError(frame));
            }
            case "ACK" -> {
                // Arrival alone resets the read timeout, which is all a ping is for.
            }
            default -> log.debug("Ignoring AKS command {}", frame.command());
        }
    }

    private void pingLoop(OutputStream out, int pingRateSeconds) {
        try {
            while (!closed) {
                Thread.sleep(pingRateSeconds * 1000L);
                send(out, "PING", "", "");
            }
        } catch (InterruptedException e) {
            // connection ended
        } catch (IOException e) {
            close(); // surfaces in the reader as the connection's failure
        }
    }

    private long send(OutputStream out, String command, String channel, String json) throws IOException {
        long id = nextId.getAndIncrement();
        synchronized (writeLock) {
            out.write(AksFrame.encode(command, id, channel, json));
            out.flush();
        }
        return id;
    }

    /**
     * One line without its CRLF, or null at end of stream. Blank lines are
     * skipped. Bounded so a runaway line is a fault, not an OOM.
     */
    private byte[] readLine(InputStream in) throws IOException {
        while (true) {
            lineBuffer.reset();
            int b;
            while ((b = in.read()) != -1 && b != '\n') {
                if (lineBuffer.size() >= props.maxLineBytes()) {
                    throw new IOException("Line exceeds " + props.maxLineBytes() + " bytes");
                }
                lineBuffer.write(b);
            }
            byte[] bytes = lineBuffer.toByteArray();
            int length = bytes.length;
            if (length > 0 && bytes[length - 1] == '\r') {
                length--;
            }
            if (length > 0) {
                return length == bytes.length ? bytes : Arrays.copyOf(bytes, length);
            }
            if (b == -1) {
                return null;
            }
        }
    }

    private String describeError(AksFrame frame) {
        try {
            JsonNode error = mapper.readTree(frame.data());
            String reason = error.path("reason").asText("");
            String advice = error.path("advice").asText("");
            return advice.isBlank() ? reason : reason + " (" + advice + ")";
        } catch (IOException e) {
            return "unreadable error";
        }
    }

    private static Long replyId(AksFrame frame) {
        try {
            return Long.parseLong(frame.messageId().replace("+", ""));
        } catch (NumberFormatException e) {
            return -1L;
        }
    }

    // The spec types pingRate/timeout as strings ("20"); accept a number too.
    private static int seconds(JsonNode node, int fallback) {
        int value = node.isNumber() ? node.asInt() : parse(node.asText(""), fallback);
        return value > 0 ? value : fallback;
    }

    private static int parse(String text, int fallback) {
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
