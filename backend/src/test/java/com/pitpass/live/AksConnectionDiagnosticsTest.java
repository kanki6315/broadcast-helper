package com.pitpass.live;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A connection that fails must say at which stage. The first real attempt
 * against Al Kamel logged only "Read timed out", which could not tell a port
 * that was not answering TLS from a server that ignored LOGIN. Each server
 * here is a bare socket misbehaving in one specific way.
 */
class AksConnectionDiagnosticsTest {

    private static final String PASSWORD = "s3cret-feed-password";
    private ServerSocket server;

    @AfterEach
    void close() throws IOException {
        if (server != null) {
            server.close();
        }
    }

    /** Accepts one client and hands it to the script on its own thread. */
    private int serve(Consumer<Socket> script) throws IOException {
        server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        Thread.ofVirtual().start(() -> {
            try (Socket client = server.accept()) {
                script.accept(client);
            } catch (IOException ignored) {
                // test over
            }
        });
        return server.getLocalPort();
    }

    private static void holdOpen(Socket client) {
        try {
            client.getInputStream().readAllBytes(); // until the client gives up
        } catch (IOException ignored) {
            // closed
        }
    }

    private IOException failure(int port, boolean tls) {
        return assertThrows(IOException.class, () -> connection(port, tls, new AtomicReference<>()).run());
    }

    private AksConnection connection(int port, boolean tls, AtomicReference<AksConnection.ServerInfo> loggedIn) {
        AlKamelV2Properties props = new AlKamelV2Properties("127.0.0.1", port, "feed-user", PASSWORD, tls, false,
                "Pit Pass test", List.of("timing.session.info"), 1 << 20, 2, 1,
                new AlKamelV2Properties.Recording(false, "", "", 10, 64), new AlKamelV2Properties.Replay("", 1.0));
        return new AksConnection(props, "127.0.0.1", port, tls, new AksStateTree(), new ObjectMapper(),
                new AksConnection.Listener() {
                    @Override
                    public void loggedIn(AksConnection.ServerInfo server) {
                        loggedIn.set(server);
                    }

                    @Override
                    public void received(long epochMs, byte[] line) {
                    }

                    @Override
                    public void warned(String message) {
                    }
                });
    }

    @Test
    void aPortThatAcceptsButNeverAnswersTlsSaysSo() throws Exception {
        IOException e = failure(serve(AksConnectionDiagnosticsTest::holdOpen), true);
        assertTrue(e.getMessage().contains("TLS handshake"), e.getMessage());
        assertTrue(e.getMessage().contains("not answering TLS"), e.getMessage());
    }

    @Test
    void aServerThatIgnoresLoginSaysSoAndThatNothingArrived() throws Exception {
        IOException e = failure(serve(AksConnectionDiagnosticsTest::holdOpen), false);
        assertTrue(e.getMessage().contains("sent LOGIN, but no reply in 1s"), e.getMessage());
        assertTrue(e.getMessage().contains("Received 0 line(s)"), e.getMessage());
        assertFalse(e.getMessage().contains(PASSWORD));
    }

    @Test
    void bytesThatNeverEndInANewlineAreShown() throws Exception {
        IOException e = failure(serve(client -> {
            try {
                client.getOutputStream().write("WELCOME v3".getBytes(StandardCharsets.UTF_8));
                client.getOutputStream().flush();
            } catch (IOException ignored) {
                // closed
            }
            holdOpen(client);
        }), false);
        assertTrue(e.getMessage().contains("unterminated bytes: WELCOME v3\\x01"), e.getMessage());
    }

    @Test
    void aRefusedConnectionNamesTheHostAndPort() throws Exception {
        int port = serve(client -> { });
        server.close(); // nothing listens there now
        IOException e = failure(port, false);
        assertTrue(e.getMessage().startsWith("Could not reach 127.0.0.1:" + port), e.getMessage());
    }

    @Test
    void aServerThatHangsUpOnLoginSaysSo() throws Exception {
        IOException e = failure(serve(client -> {
            try {
                new BufferedReader(new InputStreamReader(client.getInputStream())).readLine();
            } catch (IOException ignored) {
                // closed
            }
        }), false);
        assertEquals("Server closed the connection without answering LOGIN", e.getMessage());
    }

    @Test
    void aLoginReplyCountsHoweverItsMessageIdIsEchoed() throws Exception {
        // The spec says the reply echoes "n+"; nothing else is pending at this
        // point, so a server that echoes it differently must still log us in.
        AtomicReference<AksConnection.ServerInfo> loggedIn = new AtomicReference<>();
        int port = serve(client -> {
            try {
                new BufferedReader(new InputStreamReader(client.getInputStream())).readLine();
                client.getOutputStream().write("LOGIN:+::{\"name\":\"AKS\",\"pingRate\":\"20\",\"timeout\":\"40\"}\r\n"
                        .getBytes(StandardCharsets.UTF_8));
                client.getOutputStream().flush();
            } catch (IOException ignored) {
                // closed
            }
        });
        // The server then hangs up, which ends run() — after the login took.
        assertThrows(IOException.class, () -> connection(port, false, loggedIn).run());
        assertEquals("AKS", loggedIn.get().name());
        assertEquals(20, loggedIn.get().pingRateSeconds());
    }
}
