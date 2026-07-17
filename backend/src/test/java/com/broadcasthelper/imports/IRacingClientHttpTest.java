package com.broadcasthelper.imports;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the whole iRacing handshake against a stub standing in for iRacing:
 * the token grant, token reuse, refresh rotation, and the signed-link hop.
 *
 * These are worth the stub because the real thing punishes trial and error —
 * iRacing rate-limits the token endpoint and locks a client out after repeated
 * failures, so the first live call should be the first *credential* question,
 * not the first time the code has run. Everything here is verifiable without a
 * client secret; only whether iRacing accepts ours is not.
 */
class IRacingClientHttpTest {

    /**
     * A realistic S3 pre-signed query: X-Amz-Credential carries %2F-encoded
     * slashes that a URI-template expansion would corrupt. The real service
     * rejected exactly this with AuthorizationQueryParametersError; the stub now
     * reproduces it so the fix (fetch the link as a URI, verbatim) stays fixed.
     */
    private static final String PRESIGNED_QUERY =
            "X-Amz-Algorithm=AWS4-HMAC-SHA256"
            + "&X-Amz-Credential=AKIAEXAMPLE%2F20250201%2Fus-east-1%2Fs3%2Faws4_request"
            + "&X-Amz-Date=20250201T000000Z&X-Amz-Signature=abc123";

    private HttpServer server;
    private String baseUrl;

    /** Every token request the stub saw, decoded — the client's side of the handshake. */
    private final List<Map<String, String>> tokenRequests = new CopyOnWriteArrayList<>();
    /** Authorization headers seen on data + signed-link requests, in order. */
    private final List<String> dataAuthHeaders = new CopyOnWriteArrayList<>();
    private final List<String> linkAuthHeaders = new CopyOnWriteArrayList<>();
    /** The raw (still-encoded) query string the signed-link request arrived with. */
    private volatile String signedLinkRawQuery;

    /** Swapped per test to drive the token endpoint's behaviour. */
    private volatile TokenResponder tokenResponder;

    @FunctionalInterface
    private interface TokenResponder {
        String respond(Map<String, String> form);
    }

    @BeforeEach
    void startStub() throws IOException {
        tokenResponder = form -> json(600, "access-1", "refresh-1");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);

        server.createContext("/token", exchange -> {
            Map<String, String> form = parseForm(body(exchange));
            tokenRequests.add(form);
            String response = tokenResponder.respond(form);
            if (response == null) {
                send(exchange, 400, "{\"error\":\"invalid_grant\",\"password\":\"leaked\"}");
                return;
            }
            send(exchange, 200, response);
        });

        // The Data API answers with a pointer to the payload, not the payload.
        server.createContext("/data/results/get", exchange -> {
            dataAuthHeaders.add(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
            send(exchange, 200, "{\"link\":\"" + baseUrl + "/signed/result?" + PRESIGNED_QUERY + "\"}");
        });
        // A few endpoints answer inline instead; both shapes must work.
        server.createContext("/data/inline/get", exchange -> {
            dataAuthHeaders.add(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
            send(exchange, 200, "{\"type\":\"inline\",\"data\":{\"ok\":true}}");
        });
        server.createContext("/signed/result", exchange -> {
            linkAuthHeaders.add(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
            signedLinkRawQuery = exchange.getRequestURI().getRawQuery();
            // Shaped like the real thing (envelope + session_results), so the
            // assertion that the parser accepts a fetched payload means something.
            send(exchange, 200, "{\"type\":\"event_result\",\"data\":{\"subsession_id\":74553295,"
                                + "\"session_results\":[]}}");
        });

        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopStub() {
        server.stop(0);
    }

    private IRacingClient client() {
        return new IRacingClient("client-id", "client-secret", "User@Example.com", "pw",
                baseUrl + "/token", baseUrl + "/data");
    }

    @Test
    void sendsAPasswordLimitedGrantWithBothSecretsMasked() {
        client().fetchResult(74553295);

        assertEquals(1, tokenRequests.size());
        Map<String, String> form = tokenRequests.get(0);
        assertEquals("password_limited", form.get("grant_type"));
        assertEquals("client-id", form.get("client_id"));
        assertEquals("iracing.auth", form.get("scope"));
        // The username travels as-is; the two secrets never do.
        assertEquals("User@Example.com", form.get("username"));
        assertEquals(IRacingClient.mask("client-secret", "client-id"), form.get("client_secret"));
        assertEquals(IRacingClient.mask("pw", "User@Example.com"), form.get("password"));
        assertFalse(form.containsValue("client-secret"), "client secret must never be sent in plaintext");
        assertFalse(form.containsValue("pw"), "password must never be sent in plaintext");
    }

    @Test
    void followsTheSignedLinkAndReturnsTheRealPayload() {
        JsonNode payload = client().fetchResult(74553295);

        assertEquals("event_result", payload.path("type").asText());
        assertEquals(74553295, payload.path("data").path("subsession_id").asInt());
        assertTrue(IRacingParser.looksLikeEventResult(payload),
                "a fetched payload must be exactly what the parser reads from a file");
    }

    @Test
    void fetchesThePreSignedLinkVerbatimWithoutReEncodingIt() {
        client().fetchResult(74553295);

        // The %2F sequences in X-Amz-Credential must arrive exactly as sent —
        // decoding them to '/' or double-encoding to %252F breaks the S3
        // signature. Passing the link as a String let RestClient template-expand
        // and corrupt it; the real service answered AuthorizationQueryParametersError.
        assertEquals(PRESIGNED_QUERY, signedLinkRawQuery);
        assertTrue(signedLinkRawQuery.contains("%2F"), signedLinkRawQuery);
    }

    @Test
    void bearsTheTokenToTheApiButNotToTheSignedLink() {
        client().fetchResult(74553295);

        assertEquals(List.of("Bearer access-1"), dataAuthHeaders);
        // The link is pre-signed. Sending credentials to whatever host it names
        // would hand them to a third party.
        assertEquals(List.of("null"), linkAuthHeaders);
    }

    @Test
    void returnsAnInlinePayloadWhenThereIsNoLink() {
        JsonNode payload = client().get("/inline/get", Map.of());

        assertEquals("inline", payload.path("type").asText());
        assertTrue(linkAuthHeaders.isEmpty());
    }

    @Test
    void reusesOneAccessTokenAcrossRequests() {
        IRacingClient client = client();
        client.fetchResult(1);
        client.fetchResult(2);
        client.fetchResult(3);

        // Token requests are rate-limited and take seconds; three fetches must
        // not mean three grants.
        assertEquals(1, tokenRequests.size());
        assertEquals(3, dataAuthHeaders.size());
    }

    @Test
    void refreshesWithTheRotatedTokenWhenTheAccessTokenLapses() {
        // A token already inside the renewal buffer forces the next call to renew.
        tokenResponder = form -> json(1, "access-1", "refresh-1");
        IRacingClient client = client();
        client.fetchResult(1);

        tokenResponder = form -> json(600, "access-2", "refresh-2");
        client.fetchResult(2);

        assertEquals(2, tokenRequests.size());
        Map<String, String> refresh = tokenRequests.get(1);
        assertEquals("refresh_token", refresh.get("grant_type"));
        assertEquals("refresh-1", refresh.get("refresh_token"));
        assertNull(refresh.get("password"), "a refresh must not resend credentials");
        assertEquals(List.of("Bearer access-1", "Bearer access-2"), dataAuthHeaders);
    }

    @Test
    void fallsBackToAFullGrantWhenTheSingleUseRefreshIsRejected() {
        tokenResponder = form -> json(1, "access-1", "refresh-1");
        IRacingClient client = client();
        client.fetchResult(1);

        // Refresh tokens are single-use: a rejected one is spent, so retrying it
        // would only burn the rate limit. Re-authenticate instead.
        tokenResponder = form -> "refresh_token".equals(form.get("grant_type"))
                ? null
                : json(600, "access-2", "refresh-2");
        client.fetchResult(2);

        assertEquals(3, tokenRequests.size());
        assertEquals("refresh_token", tokenRequests.get(1).get("grant_type"));
        assertEquals("password_limited", tokenRequests.get(2).get("grant_type"));
        assertEquals("Bearer access-2", dataAuthHeaders.get(1));
    }

    @Test
    void doesNotRetryARefreshThatAlreadyFailed() {
        tokenResponder = form -> json(1, "access-1", "refresh-1");
        IRacingClient client = client();
        client.fetchResult(1);

        tokenResponder = form -> "refresh_token".equals(form.get("grant_type"))
                ? null
                : json(1, "access-2", null); // no new refresh token issued
        client.fetchResult(2);
        tokenRequests.clear();
        client.fetchResult(3);

        assertTrue(tokenRequests.stream().noneMatch(f -> "refresh_token".equals(f.get("grant_type"))),
                "a burned refresh token must not be presented again");
    }

    @Test
    void rejectsATokenGrantedWithNoScope() {
        // Scope is withheld when the account is not registered against the client
        // id. Failing here beats a 401 on every request that follows.
        tokenResponder = form -> "{\"access_token\":\"a\",\"expires_in\":600}";
        IRacingClient client = client();

        Exception e = assertThrows(Exception.class, () -> client.fetchResult(1));
        assertTrue(e.getMessage().contains("scope"), e.getMessage());
    }

    @Test
    void doesNotLeakTheTokenResponseBodyOnFailure() {
        tokenResponder = form -> null; // stub echoes a password back in its error
        IRacingClient client = client();

        Exception e = assertThrows(Exception.class, () -> client.fetchResult(1));
        assertFalse(e.getMessage().contains("leaked"),
                "a token error can echo the request; it must never reach a log or the UI");
        assertTrue(e.getMessage().contains("lock"), "the lockout risk is the useful part of this error");
    }

    @Test
    void refusesToCallWithoutCredentials() {
        IRacingClient unconfigured = new IRacingClient("", "", "", "", baseUrl + "/token", baseUrl + "/data");

        Exception e = assertThrows(Exception.class, () -> unconfigured.fetchResult(1));
        assertTrue(e.getMessage().contains("not configured"), e.getMessage());
        assertTrue(tokenRequests.isEmpty(), "an unconfigured client must not reach the network");
    }

    @Test
    void asksForLicencesSoDriverRatingsArrive() {
        List<String> queries = new CopyOnWriteArrayList<>();
        server.removeContext("/data/results/get");
        server.createContext("/data/results/get", exchange -> {
            queries.add(exchange.getRequestURI().getQuery());
            send(exchange, 200, "{\"type\":\"event_result\",\"data\":{}}");
        });

        client().fetchResult(74553295);

        assertEquals(1, queries.size());
        Map<String, String> params = parseForm(queries.get(0));
        assertEquals("74553295", params.get("subsession_id"));
        assertEquals("true", params.get("include_licenses"));
    }

    // ------------------------------------------------------------------ stub

    private static String json(int expiresIn, String accessToken, String refreshToken) {
        StringBuilder out = new StringBuilder("{\"access_token\":\"").append(accessToken)
                .append("\",\"expires_in\":").append(expiresIn)
                .append(",\"scope\":\"iracing.auth\"");
        if (refreshToken != null) {
            out.append(",\"refresh_token\":\"").append(refreshToken)
                    .append("\",\"refresh_token_expires_in\":604800");
        }
        return out.append('}').toString();
    }

    private static String body(HttpExchange exchange) throws IOException {
        try (InputStream in = exchange.getRequestBody()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void send(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static Map<String, String> parseForm(String encoded) {
        Map<String, String> out = new HashMap<>();
        if (encoded == null || encoded.isBlank()) {
            return out;
        }
        for (String pair : encoded.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) {
                continue;
            }
            out.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return out;
    }
}
