package com.broadcasthelper.imports;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
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
 * Proven against the live service on 2025 Daytona subsession 74553295: it
 * authenticates, follows the signed link, and its batches come back byte-for-byte
 * identical to that round's exported file. The unit tests still run against a stub
 * (IRacingClientHttpTest) — including the two things the first live call caught
 * that the stub originally missed: the signed link must be fetched as a URI, not a
 * String template (its %2F-encoded AWS signature is otherwise corrupted), and the
 * signed-link payload is the result object unwrapped, without the exported file's
 * {"type":"event_result","data":{...}} envelope (IRacingParser handles both).
 *
 * Handle with care regardless: iRacing rate-limits the token endpoint hard and
 * locks the client out after repeated failures, and the client id cannot be
 * replaced (issuance has been paused since 2025). On an auth failure, fail loudly
 * and stop — never retry into a lockout.
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

    /** Stateless for reads; the signed payloads are parsed from bytes, not
     *  content-type-negotiated, so a S3 object served as octet-stream still parses. */
    private static final ObjectMapper JSON = new ObjectMapper();

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

        // Pass a URI, not a String: RestClient.uri(String) treats its argument as
        // a URI template and re-encodes it. The query values here are already
        // percent-encoded, and the signed link below carries an AWS pre-signed
        // signature whose X-Amz-Credential contains %2F — template expansion
        // mangles both. URI.create hands the URL over verbatim.
        JsonNode response = http.get()
                .uri(URI.create(url.toString()))
                .header("Authorization", "Bearer " + accessToken())
                .retrieve()
                .body(JsonNode.class);
        if (response == null) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Empty response from iRacing for " + path);
        }
        // Two indirection shapes: results/get answers {"link": "..."}, while
        // league/roster wraps a {"data_url": "..."} beside inline metadata. Both
        // point at a short-lived (~15 min) pre-signed S3 object; follow whichever
        // is present. No bearer token on the signed request — it is pre-signed,
        // and sending credentials to the S3 host would hand them to a third party.
        JsonNode link = response.path("link");
        if (!link.isTextual()) {
            link = response.path("data_url");
        }
        if (!link.isTextual()) {
            return response;
        }
        // Fetch the signed object as bytes and parse it directly, rather than
        // letting RestClient content-negotiate: S3 serves the roster link as
        // application/octet-stream, for which there is no JSON converter, even
        // though the bytes are JSON. Bytes sidestep the content-type entirely.
        byte[] raw = http.get().uri(URI.create(link.asText())).retrieve().body(byte[].class);
        if (raw == null || raw.length == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Empty payload from iRacing signed link for " + path);
        }
        JsonNode payload;
        try {
            payload = JSON.readTree(raw);
        } catch (java.io.IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Malformed payload from iRacing signed link for " + path);
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
