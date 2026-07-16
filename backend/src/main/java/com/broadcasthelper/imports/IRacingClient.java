package com.broadcasthelper.imports;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;

/**
 * Fetches payloads from the iRacing Data API.
 *
 * WIP — NEVER RUN AGAINST THE REAL IRACING SERVICE. Every test covering this
 * class talks to a stub (IRacingClientHttpTest), because no client secret was
 * available when it was written. The request shapes come from iRacing's docs and
 * a community implementation, not from a response iRacing actually sent us. Take
 * nothing here as confirmed until it has completed one live fetch.
 *
 * When the client secret arrives, before trusting this in anger:
 *   1. Set IRACING_CLIENT_ID / _CLIENT_SECRET / _USERNAME / _PASSWORD.
 *   2. POST /api/imports/iracing/74553295 — the 2025 Daytona round whose exported
 *      file is the parser's fixture. The batches it stages must match the ones
 *      that file produces, which are known good.
 *   3. Only then point it at anything unknown.
 *
 * Get that wrong quietly and it costs more than a stack trace: iRacing rate-limits
 * the token endpoint hard and locks the client out after repeated failures, and
 * the client id cannot be replaced (issuance has been paused since 2025). So fail
 * loudly and stop, rather than retrying into a lockout.
 *
 * Authentication uses the password_limited grant, which is iRacing's extension
 * for headless clients acting for a handful of pre-registered users. It is the
 * only workable choice here: legacy read-only auth was retired in December 2025,
 * and the authorization-code flow would need a browser redirect an importer has
 * no way to perform. password_limited is also the one flow that bypasses 2FA,
 * which the account has enabled.
 *
 * Both secrets are "masked" before they leave the process — iRacing never
 * receives the plaintext, and neither does any log.
 *
 * Tokens are held in memory only. A refresh token is a live credential with a
 * seven-day life, so writing it to disk would trade a cheap re-auth on restart
 * for a credential at rest; the re-auth is cheaper.
 */
@Component
public class IRacingClient {

    private static final String SCOPE = "iracing.auth";

    /**
     * Renew this long before the access token actually lapses, so a request
     * can't be issued with a token that expires in flight. Tokens live 600s.
     */
    private static final Duration EXPIRY_BUFFER = Duration.ofSeconds(60);

    private final RestClient http = RestClient.create();

    private final String clientId;
    private final String clientSecret;
    private final String username;
    private final String password;
    private final String tokenUrl;
    private final String dataBase;

    private String accessToken;
    private Instant accessTokenExpiry = Instant.EPOCH;
    private String refreshToken;
    private Instant refreshTokenExpiry = Instant.EPOCH;

    public IRacingClient(
            @Value("${broadcast-helper.iracing.client-id:}") String clientId,
            @Value("${broadcast-helper.iracing.client-secret:}") String clientSecret,
            @Value("${broadcast-helper.iracing.username:}") String username,
            @Value("${broadcast-helper.iracing.password:}") String password,
            // Overridable so tests can point the client at a stub. iRacing also
            // runs the Data API per environment ("members-ng" is the live one),
            // so these are not test-only seams.
            @Value("${broadcast-helper.iracing.token-url:https://oauth.iracing.com/oauth2/token}") String tokenUrl,
            @Value("${broadcast-helper.iracing.data-base:https://members-ng.iracing.com/data}") String dataBase) {
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.username = username;
        this.password = password;
        this.tokenUrl = tokenUrl;
        this.dataBase = dataBase;
    }

    /** Whether credentials are present. Absent in local dev and in CI. */
    public boolean isConfigured() {
        return !clientId.isBlank() && !clientSecret.isBlank()
               && !username.isBlank() && !password.isBlank();
    }

    /**
     * One subsession's full result — the same payload a user gets by exporting
     * the file from iRacing, so IRacingParser handles both without caring which.
     */
    public JsonNode fetchResult(long subsessionId) {
        return get("/results/get", Map.of(
                "subsession_id", String.valueOf(subsessionId),
                // Licences give each driver's class and safety rating, which the
                // parser renders as the on-air "A 4.99".
                "include_licenses", "true"));
    }

    /**
     * A Data API GET. Most endpoints answer with a signed link to the real
     * payload rather than the payload itself, so follow it when present. The
     * link is pre-signed and must be fetched without the bearer token.
     */
    public JsonNode get(String path, Map<String, String> params) {
        if (!isConfigured()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "iRacing API credentials are not configured");
        }
        StringBuilder url = new StringBuilder(dataBase).append(path);
        boolean first = true;
        for (Map.Entry<String, String> p : params.entrySet()) {
            url.append(first ? '?' : '&')
                    .append(java.net.URLEncoder.encode(p.getKey(), StandardCharsets.UTF_8))
                    .append('=')
                    .append(java.net.URLEncoder.encode(p.getValue(), StandardCharsets.UTF_8));
            first = false;
        }

        JsonNode response = http.get()
                .uri(url.toString())
                .header("Authorization", "Bearer " + accessToken())
                .retrieve()
                .body(JsonNode.class);
        if (response == null) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Empty response from iRacing for " + path);
        }
        JsonNode link = response.path("link");
        if (!link.isTextual()) {
            return response;
        }
        JsonNode payload = http.get().uri(link.asText()).retrieve().body(JsonNode.class);
        if (payload == null) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Empty payload from iRacing signed link for " + path);
        }
        return payload;
    }

    // ------------------------------------------------------------------ tokens

    /**
     * A live access token, minted only when the cached one is spent. Token
     * requests are strictly rate-limited and take upwards of two seconds, so
     * every request sharing one token for its full life is the point, not an
     * optimization.
     */
    private synchronized String accessToken() {
        if (accessToken != null && Instant.now().isBefore(accessTokenExpiry.minus(EXPIRY_BUFFER))) {
            return accessToken;
        }
        // Refresh tokens are single-use: a failed refresh burns it, so fall back
        // to a full re-auth rather than retrying with the same one.
        if (refreshToken != null && Instant.now().isBefore(refreshTokenExpiry)) {
            try {
                authenticate(refreshRequest());
                return accessToken;
            } catch (RuntimeException e) {
                refreshToken = null;
                refreshTokenExpiry = Instant.EPOCH;
            }
        }
        authenticate(passwordLimitedRequest());
        return accessToken;
    }

    private MultiValueMap<String, String> passwordLimitedRequest() {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "password_limited");
        form.add("client_id", clientId);
        form.add("client_secret", mask(clientSecret, clientId));
        form.add("username", username);
        form.add("password", mask(password, username));
        form.add("scope", SCOPE);
        return form;
    }

    private MultiValueMap<String, String> refreshRequest() {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "refresh_token");
        form.add("client_id", clientId);
        form.add("client_secret", mask(clientSecret, clientId));
        form.add("refresh_token", refreshToken);
        return form;
    }

    private void authenticate(MultiValueMap<String, String> form) {
        JsonNode response;
        try {
            response = http.post()
                    .uri(tokenUrl)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (org.springframework.web.client.RestClientException e) {
            // Never surface the response body: a token error can echo the request.
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "iRacing authentication was rejected. Check the credentials, and note that "
                    + "repeated failures lock the client out for a period.");
        }
        if (response == null || !response.path("access_token").isTextual()) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "iRacing returned no access token");
        }
        // A token with no scope cannot call the Data API — treat it as a failure
        // here rather than as a 401 on every request that follows.
        if (!response.path("scope").isTextual()) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "iRacing granted no scope. The account may not be registered against this client id.");
        }
        accessToken = response.path("access_token").asText();
        accessTokenExpiry = Instant.now().plusSeconds(response.path("expires_in").asLong(600));
        if (response.path("refresh_token").isTextual()) {
            refreshToken = response.path("refresh_token").asText();
            refreshTokenExpiry = Instant.now()
                    .plusSeconds(response.path("refresh_token_expires_in").asLong(604_800));
        }
    }

    /**
     * iRacing's masking: base64(sha256(secret + identifier)), where the
     * identifier is trimmed and lowercased. The client secret is salted with the
     * client id, the password with the username. Masking is what goes over the
     * wire — it is not a substitute for keeping the plaintext secret.
     */
    static String mask(String secret, String identifier) {
        String normalized = identifier.strip().toLowerCase(Locale.ROOT);
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            byte[] digest = sha256.digest((secret + normalized).getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required but unavailable", e);
        }
    }
}
