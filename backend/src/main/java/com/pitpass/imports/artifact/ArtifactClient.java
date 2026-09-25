package com.pitpass.imports.artifact;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

/**
 * Reads the public JSON API behind artifactracing.com, the IMSA Esports Global
 * Championship's competition site. It publishes the official classification —
 * after the stewards' post-race penalties — and the championship standings,
 * which iRacing's hosted-session results cannot carry. No credentials; every
 * call is behind an admin's click.
 *
 * The API speaks strings for most numbers ("121" laps) and leaves blanks as
 * "" — the records keep them raw and the correction service interprets them.
 * Tests subclass this and override {@link #get}.
 */
@Component
public class ArtifactClient {

    private static final String USER_AGENT = "PitPass/1.0 (IMSA Esports results; admin-triggered)";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(30);

    private final RestClient http = RestClient.builder().requestFactory(requestFactory()).build();
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final String baseUrl;

    /** The review re-plans on every decision; a minute's cache keeps that from
     *  re-reading a whole season from the site each time. Apply reads fresh. */
    private static final Duration CACHE_TTL = Duration.ofSeconds(60);

    private record Cached(JsonNode body, Instant at) {
    }

    private final java.util.Map<String, Cached> cache = new java.util.concurrent.ConcurrentHashMap<>();
    private final ThreadLocal<Boolean> fresh = ThreadLocal.withInitial(() -> false);

    public ArtifactClient(@Value("${pit-pass.artifact.base-url}") String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    private static SimpleClientHttpRequestFactory requestFactory() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT);
        factory.setReadTimeout(READ_TIMEOUT);
        return factory;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record League(String id, String name, String seriesName, Integer seasonNumber, boolean isActive) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Track(String name, String layout) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ParticipatingClass(String name) {
    }

    /** A round. {@code isComplete} is not trusted — finished 2025 rounds still
     *  say false — so "has results" means the results list is non-empty. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Event(String id, int round, String name, Instant raceStartTime, Track track,
                        List<ParticipatingClass> participatingClasses) {
    }

    /** One car's official result. {@code interval} is the gap to the overall
     *  leader: "-00.000" for the winner, "-1:04.113", "-16 L", or "-" when the
     *  stewards classified the car with no gap (a Drive Time Violation). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Result(String id, String entryId, String carNumber, @JsonProperty("class") String className,
                         String entryName, String carName, List<String> drivers,
                         String overallPosition, String classPosition, String startPosition,
                         String endStatus, String lapsCompleted, String interval,
                         String fastestLapTime, String fastestLapNumber, String incidents,
                         Double points, Double qualifyingPoints) {
    }

    /** A car's standings row: per-round race and qualifying points, null for a
     *  round the car did not take part in. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Standing(int standing, String carNumber, @JsonProperty("class") String className,
                           String teamName, double totalPoints, List<Double> points,
                           List<Double> qualifyingPoints) {
    }

    /** Every season, past ones included — without the flag the site lists only the active one. */
    public List<League> leagues() {
        return Arrays.asList(read(cached("/api/leagues?includeInactive=true"), League[].class));
    }

    public List<Event> events(String leagueId) {
        return Arrays.asList(read(cached("/api/leagues/" + id(leagueId) + "/events"), Event[].class));
    }

    public List<Result> results(String eventId) {
        return Arrays.asList(read(cached("/api/events/" + id(eventId) + "/results"), Result[].class));
    }

    public List<Standing> standings(String leagueId) {
        return Arrays.asList(read(cached("/api/leagues/" + id(leagueId) + "/standings"), Standing[].class));
    }

    /** Runs {@code work} with every read going to the site, refreshing the cache. */
    public <T> T fresh(java.util.function.Supplier<T> work) {
        boolean was = fresh.get();
        fresh.set(true);
        try {
            return work.get();
        } finally {
            fresh.set(was);
        }
    }

    private JsonNode cached(String path) {
        Cached hit = cache.get(path);
        if (!fresh.get() && hit != null && hit.at().plus(CACHE_TTL).isAfter(Instant.now())) {
            return hit.body();
        }
        JsonNode body = get(path);
        cache.put(path, new Cached(body, Instant.now()));
        return body;
    }

    /** One GET, parsed. Overridden by tests to serve fixtures. */
    public JsonNode get(String path) {
        URI uri = URI.create(baseUrl + path);
        try {
            String body = http.get().uri(uri)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "application/json")
                    .exchange((request, response) -> {
                        if (!response.getStatusCode().is2xxSuccessful()) {
                            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                                    "artifactracing.com answered " + response.getStatusCode().value() + " for " + uri);
                        }
                        return new String(response.getBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                    });
            return json.readTree(body);
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Could not read artifactracing.com at " + uri + ": " + e.getMessage(), e);
        }
    }

    private <T> T read(JsonNode node, Class<T> type) {
        try {
            return json.treeToValue(node, type);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "artifactracing.com sent a shape this importer doesn't read: " + e.getMessage(), e);
        }
    }

    /** Ids are GUIDs; anything else is refused before it becomes part of a URL. */
    private static String id(String raw) {
        if (raw == null || !raw.matches("[0-9a-fA-F-]{1,64}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Not an artifactracing.com id: " + raw);
        }
        return raw;
    }
}
