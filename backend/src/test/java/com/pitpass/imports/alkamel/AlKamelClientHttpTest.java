package com.pitpass.imports.alkamel;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The client's manners against a stub site: it identifies itself, caches
 * listings, spaces uncached requests out, and turns the site's failures into
 * actionable errors instead of crawling on.
 */
class AlKamelClientHttpTest {

    private FixtureSite site;

    @BeforeEach
    void start() throws IOException {
        site = new FixtureSite();
    }

    @AfterEach
    void stop() {
        site.close();
    }

    @Test
    void listsAFolderWithAnIdentifyingUserAgent() {
        List<IndexEntry> rows = site.client().list("17_2017/");
        assertEquals(27, rows.stream().filter(IndexEntry::directory).count());
        assertEquals(List.of("17_2017/"), site.requests);
        assertTrue(site.userAgents.get(0).startsWith("PitPass/"), site.userAgents.get(0));
    }

    @Test
    void cachesListingsUntilAskedForAFreshOne() {
        AlKamelClient client = site.client();
        client.list("17_2017/");
        client.list("17_2017/");
        assertEquals(1, site.requests.size(), "second list served from cache");
        client.listFresh("17_2017/");
        assertEquals(2, site.requests.size());
        client.list("17_2017/");
        assertEquals(2, site.requests.size(), "fresh fetch refilled the cache");
        client.clearCache();
        client.list("17_2017/");
        assertEquals(3, site.requests.size());
    }

    @Test
    void expiredListingsAreRefetched() throws InterruptedException {
        AlKamelClient client = site.client(0, 0, 1024);
        client.list("17_2017/");
        Thread.sleep(5);
        client.list("17_2017/");
        assertEquals(2, site.requests.size());
    }

    @Test
    void spacesUncachedRequestsOut() {
        AlKamelClient client = site.client(120, 3600, 1024 * 1024);
        Instant t0 = Instant.now();
        client.listFresh("17_2017/");
        client.listFresh("26_2026/");
        client.listFresh("");
        long elapsed = Duration.between(t0, Instant.now()).toMillis();
        assertTrue(elapsed >= 240, "three requests at a 120 ms spacing took " + elapsed + " ms");
    }

    @Test
    void downloadsAFileVerbatim() {
        byte[] body = "﻿{\"session\":{}}".getBytes();
        site.put("26_2026/x/03_Results_Race_Official.JSON", body);
        assertArrayEquals(body, site.client().download("26_2026/x/03_Results_Race_Official.JSON"));
    }

    @Test
    void aMissingPathIsAnUpstreamErrorNamingTheUrl() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> site.client().list("99_2099/"));
        assertEquals(502, ex.getStatusCode().value());
        assertTrue(ex.getReason().contains("404"), ex.getReason());
        assertTrue(ex.getReason().contains("99_2099/"), ex.getReason());
    }

    @Test
    void refusesAFileOverTheSizeCap() {
        site.put("26_2026/big.pdf", new byte[200]);
        AlKamelClient client = site.client(0, 3600, 100);
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> client.download("26_2026/big.pdf"));
        assertEquals(422, ex.getStatusCode().value());
        assertTrue(ex.getReason().contains("cap"), ex.getReason());
    }

    @Test
    void keepsEncodedPathsVerbatimInTheUrl() {
        String path = "17_2017/06_Long%20Beach%20Street%20Circuit/";
        site.client().list(path);
        assertEquals(List.of(path), site.requests);
        assertEquals(site.baseUrl() + path, site.client().url(path));
    }
}
