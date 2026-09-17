package com.pitpass.imports.alkamel;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fetches directory listings and files from the Al Kamel results site.
 *
 * The site is an open Apache index with no API, no robots.txt and no terms —
 * so this client behaves like a polite visitor rather than a crawler: one
 * request at a time, a fixed pause before every uncached request, an
 * identifying User-Agent, and listings cached for a while so re-planning a
 * season costs nothing. Nothing here runs on a schedule; every fetch is behind
 * an admin's click (see docs/ALKAMEL_IMPORT.md).
 *
 * Paths are relative to the base URL and built from the listings' own hrefs
 * (already percent-encoded), and they are fetched as a {@link URI} verbatim —
 * never through a URI template, which would re-encode them.
 */
@Component
public class AlKamelClient {

    private static final String USER_AGENT = "PitPass/1.0 (results importer; admin-triggered)";

    private final RestClient http = RestClient.create();
    private final String baseUrl;
    private final Duration delay;
    private final Duration listingTtl;
    private final long maxDownloadBytes;

    private record CachedListing(List<IndexEntry> entries, Instant fetchedAt) {
    }

    private final Map<String, CachedListing> listings = new ConcurrentHashMap<>();
    private final Object throttle = new Object();
    private Instant lastRequestAt = Instant.EPOCH;

    public AlKamelClient(@Value("${pit-pass.alkamel.base-url}") String baseUrl,
                         @Value("${pit-pass.alkamel.delay-ms}") long delayMs,
                         @Value("${pit-pass.alkamel.listing-cache-seconds}") long listingCacheSeconds,
                         @Value("${pit-pass.alkamel.max-download-bytes}") long maxDownloadBytes) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
        this.delay = Duration.ofMillis(delayMs);
        this.listingTtl = Duration.ofSeconds(listingCacheSeconds);
        this.maxDownloadBytes = maxDownloadBytes;
    }

    /** The absolute URL of a site-relative path. */
    public String url(String path) {
        return baseUrl + path;
    }

    /** A folder's rows, from the cache while it is fresh. {@code path} is
     *  "" for the root, otherwise ends with "/". */
    public List<IndexEntry> list(String path) {
        CachedListing cached = listings.get(path);
        if (cached != null && cached.fetchedAt().plus(listingTtl).isAfter(Instant.now())) {
            return cached.entries();
        }
        return listFresh(path);
    }

    /** A folder's rows straight from the site, refilling the cache — for the
     *  event folder a refresh is looking at, where "what changed" is the point. */
    public List<IndexEntry> listFresh(String path) {
        byte[] html = fetch(path, Long.MAX_VALUE);
        List<IndexEntry> entries = AlKamelCatalog.parseListing(new String(html, StandardCharsets.UTF_8));
        listings.put(path, new CachedListing(entries, Instant.now()));
        return entries;
    }

    /** A file's bytes; refuses anything over the configured size cap. */
    public byte[] download(String path) {
        return fetch(path, maxDownloadBytes);
    }

    /** Forget every cached listing (tests, and a way out of a stale cache). */
    public void clearCache() {
        listings.clear();
    }

    private byte[] fetch(String path, long maxBytes) {
        URI uri = URI.create(url(path));
        pause();
        try {
            return http.get().uri(uri)
                    .header("User-Agent", USER_AGENT)
                    .exchange((request, response) -> {
                        HttpStatus status = HttpStatus.resolve(response.getStatusCode().value());
                        if (status == null || !status.is2xxSuccessful()) {
                            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                                    "Al Kamel answered " + response.getStatusCode().value() + " for " + uri);
                        }
                        long declared = response.getHeaders().getContentLength();
                        if (declared > maxBytes) {
                            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                                    "Refusing to download " + uri + ": " + declared + " bytes is over the "
                                            + maxBytes + "-byte cap");
                        }
                        return readCapped(response.getBody(), maxBytes, uri);
                    });
        } catch (ResponseStatusException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Could not reach Al Kamel for " + uri + ": " + e.getMessage(), e);
        }
    }

    private static byte[] readCapped(InputStream in, long maxBytes, URI uri) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16 * 1024];
        long total = 0;
        int n;
        while ((n = in.read(buf)) >= 0) {
            total += n;
            if (total > maxBytes) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "Refusing to download " + uri + ": over the " + maxBytes + "-byte cap");
            }
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    /** Wait out the gap since the previous request. Serialises requests too:
     *  the site sees at most one in flight from this process. */
    private void pause() {
        synchronized (throttle) {
            Instant earliest = lastRequestAt.plus(delay);
            long wait = Duration.between(Instant.now(), earliest).toMillis();
            if (wait > 0) {
                try {
                    Thread.sleep(wait);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Interrupted while throttling");
                }
            }
            lastRequestAt = Instant.now();
        }
    }
}
