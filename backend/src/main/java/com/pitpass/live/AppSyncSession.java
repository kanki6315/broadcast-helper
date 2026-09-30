package com.pitpass.live;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The AWS AppSync Events realtime protocol, as a pure state machine over text
 * messages so it is tested without a socket: connection_init → connection_ack
 * (which says how long the server may stay silent) → one subscribe per
 * channel → data events, with {@code ka} keep-alives in between.
 *
 * Written from AWS's published protocol; IMSA's endpoint has not been seen
 * live. Unknown message types are ignored, errors are surfaced.
 */
final class AppSyncSession {

    /** What the session wants done in answer to a message. */
    interface Output {
        void send(String text);

        /** An event payload (the data message's {@code event} string) and the channel it came on, when known. */
        void event(String channel, String payload);
    }

    /** A protocol-level refusal: the connection is not worth keeping. */
    static final class ProtocolError extends RuntimeException {
        ProtocolError(String message) {
            super(message);
        }
    }

    /** From the connection_ack; AWS's documented default is five minutes. */
    static final long DEFAULT_TIMEOUT_MS = 300_000;

    private final ObjectMapper mapper;
    private final String httpHost;
    private final String apiKey;
    private final List<String> channels;
    private final Output out;
    private final Map<String, String> subscriptions = new LinkedHashMap<>();

    private boolean acknowledged;
    private long timeoutMs = DEFAULT_TIMEOUT_MS;
    private int subscribed;

    AppSyncSession(ObjectMapper mapper, String httpHost, String apiKey, List<String> channels, Output out) {
        this.mapper = mapper;
        this.httpHost = httpHost;
        this.apiKey = apiKey;
        this.channels = List.copyOf(channels);
        this.out = out;
    }

    /** The websocket subprotocols: the protocol name, and the auth header as base64url JSON. */
    static List<String> subprotocols(ObjectMapper mapper, String httpHost, String apiKey) {
        ObjectNode header = mapper.createObjectNode().put("host", httpHost).put("x-api-key", apiKey);
        String encoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(header.toString().getBytes(StandardCharsets.UTF_8));
        return List.of("aws-appsync-event-ws", "header-" + encoded);
    }

    /** Sent once the socket is open. */
    void start() {
        out.send("{\"type\":\"connection_init\"}");
    }

    boolean acknowledged() {
        return acknowledged;
    }

    /** Longest silence the server allows before the link counts as dead. */
    long timeoutMs() {
        return timeoutMs;
    }

    int subscribed() {
        return subscribed;
    }

    void receive(String text) {
        JsonNode msg;
        try {
            msg = mapper.readTree(text);
        } catch (Exception e) {
            return; // not JSON: nothing this protocol sends
        }
        switch (msg.path("type").asText("")) {
            case "connection_ack" -> {
                acknowledged = true;
                long advertised = msg.path("connectionTimeoutMs").asLong(0);
                timeoutMs = advertised > 0 ? advertised : DEFAULT_TIMEOUT_MS;
                for (String channel : channels) {
                    String id = UUID.randomUUID().toString();
                    subscriptions.put(id, channel);
                    ObjectNode subscribe = mapper.createObjectNode()
                            .put("type", "subscribe").put("id", id).put("channel", channel);
                    subscribe.putObject("authorization").put("host", httpHost).put("x-api-key", apiKey);
                    out.send(subscribe.toString());
                }
            }
            case "subscribe_success" -> subscribed++;
            case "subscribe_error" -> throw new ProtocolError("Subscribing to "
                    + subscriptions.getOrDefault(msg.path("id").asText(), "a channel") + " was refused: " + errors(msg));
            case "connection_error", "error" -> throw new ProtocolError("AppSync refused the connection: " + errors(msg));
            case "data" -> {
                JsonNode event = msg.get("event");
                if (event != null && !event.isNull()) {
                    out.event(subscriptions.get(msg.path("id").asText()),
                            event.isTextual() ? event.asText() : event.toString());
                }
            }
            default -> {
                // ka and anything newer: arrival alone resets the silence timer
            }
        }
    }

    private static String errors(JsonNode msg) {
        List<String> parts = new ArrayList<>();
        msg.path("errors").forEach(e -> parts.add(e.path("errorType").asText("")
                + (e.hasNonNull("message") ? " " + e.path("message").asText() : "")));
        return parts.isEmpty() ? "no reason given" : String.join("; ", parts).trim();
    }
}
