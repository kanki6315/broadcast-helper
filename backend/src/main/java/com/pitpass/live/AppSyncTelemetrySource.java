package com.pitpass.live;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The live source: reads the endpoint and key from IMSA's telemetry app,
 * opens the AppSync Events websocket and subscribes. The protocol lives in
 * {@link AppSyncSession}; this class is only the socket and the clock that
 * declares a silent link dead.
 */
final class AppSyncTelemetrySource implements TelemetrySource {

    private static final Logger log = LoggerFactory.getLogger(AppSyncTelemetrySource.class);
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(15);
    private static final long ACK_TIMEOUT_MS = 15_000;

    private final ImsaTelemetryProperties props;
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(HTTP_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final CompletableFuture<Void> done = new CompletableFuture<>();

    private volatile WebSocket socket;

    AppSyncTelemetrySource(ImsaTelemetryProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
    }

    @Override
    public void run(Listener listener) throws Exception {
        AppSyncEndpoint endpoint = discover();
        StringBuilder partial = new StringBuilder();
        long[] lastHeard = {System.currentTimeMillis()};
        AppSyncSession[] session = new AppSyncSession[1];
        session[0] = new AppSyncSession(mapper, endpoint.httpHost(), endpoint.apiKey(), props.channelPaths(),
                new AppSyncSession.Output() {
                    @Override
                    public void send(String text) {
                        WebSocket ws = socket;
                        if (ws != null) {
                            ws.sendText(text, true);
                        }
                    }

                    @Override
                    public void event(String channel, String payload) {
                        listener.event(channel, payload);
                    }
                });

        List<String> protocols = AppSyncSession.subprotocols(mapper, endpoint.httpHost(), endpoint.apiKey());
        WebSocket.Listener wsListener = new WebSocket.Listener() {
            @Override
            public void onOpen(WebSocket ws) {
                ws.request(1);
            }

            @Override
            public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
                partial.append(data);
                if (last) {
                    String text = partial.toString();
                    partial.setLength(0);
                    long now = System.currentTimeMillis();
                    lastHeard[0] = now;
                    listener.raw(now, text);
                    try {
                        boolean wasSubscribed = session[0].subscribed() == props.channelPaths().size();
                        session[0].receive(text);
                        if (!wasSubscribed && session[0].subscribed() == props.channelPaths().size()) {
                            listener.connected(endpoint.httpHost() + " " + props.channelPaths());
                        }
                    } catch (AppSyncSession.ProtocolError e) {
                        done.completeExceptionally(new IOException(e.getMessage()));
                    } catch (RuntimeException e) {
                        log.debug("IMSA telemetry: could not handle a message: {}", e.toString());
                    }
                }
                ws.request(1);
                return null;
            }

            @Override
            public CompletionStage<?> onClose(WebSocket ws, int status, String reason) {
                done.completeExceptionally(new IOException("AppSync closed the connection (" + status
                        + (reason == null || reason.isBlank() ? "" : ", " + reason) + ")"));
                return null;
            }

            @Override
            public void onError(WebSocket ws, Throwable error) {
                done.completeExceptionally(new IOException("Telemetry websocket failed: " + error.getMessage(), error));
            }
        };

        try {
            socket = http.newWebSocketBuilder()
                    .connectTimeout(HTTP_TIMEOUT)
                    .subprotocols(protocols.get(0), protocols.get(1))
                    .buildAsync(URI.create(endpoint.realtimeUrl()), wsListener)
                    .get(HTTP_TIMEOUT.toSeconds() + 5, TimeUnit.SECONDS);
        } catch (ExecutionException | TimeoutException e) {
            throw new IOException("Could not open the telemetry websocket at " + endpoint.httpHost() + ": "
                    + (e.getCause() != null ? e.getCause().getMessage() : e.toString()), e);
        }
        session[0].start();

        // The watchdog: no ack in time, or silence past what the server promised, ends the link.
        while (!done.isDone()) {
            try {
                done.get(1, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                long silent = System.currentTimeMillis() - lastHeard[0];
                if (!session[0].acknowledged() && silent > ACK_TIMEOUT_MS) {
                    close();
                    throw new IOException("AppSync never acknowledged the connection");
                }
                if (session[0].acknowledged() && silent > session[0].timeoutMs() + 10_000) {
                    close();
                    throw new IOException("Telemetry went silent for " + silent / 1000 + " s");
                }
            } catch (ExecutionException e) {
                close();
                throw e.getCause() instanceof Exception cause ? cause : e;
            }
        }
        throw new IOException("Telemetry connection closed");
    }

    /** The app page → its main script → the endpoint and key inside it. */
    private AppSyncEndpoint discover() throws IOException, InterruptedException {
        URI page = URI.create(props.appUrl());
        String html = get(page);
        URI script = AppSyncEndpoint.mainScript(page, html)
                .orElseThrow(() -> new IOException("The telemetry app at " + page + " has no main script any more"));
        AppSyncEndpoint endpoint = AppSyncEndpoint.fromBundle(get(script))
                .orElseThrow(() -> new IOException("The telemetry app's bundle no longer names an AppSync endpoint and key"));
        log.info("IMSA telemetry: using {}", endpoint);
        return endpoint;
    }

    private String get(URI uri) throws IOException, InterruptedException {
        HttpResponse<String> response = http.send(
                HttpRequest.newBuilder(uri).timeout(HTTP_TIMEOUT).header("User-Agent", "Pit Pass").GET().build(),
                HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new IOException(uri + " answered " + response.statusCode());
        }
        return response.body();
    }

    @Override
    public void close() {
        WebSocket ws = socket;
        if (ws != null) {
            ws.abort();
        }
        done.complete(null);
    }
}
