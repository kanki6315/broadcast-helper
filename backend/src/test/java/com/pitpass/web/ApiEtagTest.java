package com.pitpass.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The service worker's stale-while-revalidate change detection compares the
 * ETag between the cached and fresh copy of an /api read (see ApiEtagConfig).
 * These tests pin the two behaviors that detection depends on: JSON GETs
 * carry a weak content-hash ETag despite Spring Security's no-store header,
 * and the same body yields the same tag (a differing tag is what triggers
 * the frontend's "newer data available" nudge). Uses /api/me because it
 * needs no fixtures under the default open chain.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ApiEtagTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void jsonGetCarriesStableWeakEtag() throws Exception {
        MvcResult first = mvc.perform(get("/api/me"))
                .andExpect(status().isOk())
                .andExpect(header().exists("ETag"))
                .andReturn();

        String etag = first.getResponse().getHeader("ETag");
        assertThat(etag).startsWith("W/\"");

        // Same payload → same fingerprint, so an unchanged revalidation
        // never nudges the user.
        mvc.perform(get("/api/me"))
                .andExpect(header().string("ETag", etag));
    }

    @Test
    void conditionalGetShortCircuitsTo304() throws Exception {
        String etag = mvc.perform(get("/api/me"))
                .andReturn().getResponse().getHeader("ETag");

        mvc.perform(get("/api/me").header("If-None-Match", etag))
                .andExpect(status().isNotModified());
    }
}
