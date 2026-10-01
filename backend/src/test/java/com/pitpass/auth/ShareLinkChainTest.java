package com.pitpass.auth;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The shareable timing link through the secured chain: it opens the timing
 * pages' reads and nothing else; a new link kills the old one at once; a
 * revoked one gets the 401 the shared page turns into "this link no longer
 * works". Committed fixtures, like SecuredChainTest; the local database's own
 * working link (if any) is restored afterwards.
 */
@SpringBootTest(properties = {
        "pit-pass.auth.enabled=true",
        "spring.security.oauth2.client.registration.google.client-id=test",
        "spring.security.oauth2.client.registration.google.client-secret=test",
})
@AutoConfigureMockMvc
class ShareLinkChainTest {

    private static final String ADMIN = "share-admin@example.test";
    private static final String VIEWER = "share-viewer@example.test";
    private static final String H = ShareTokenFilter.HEADER;

    @Autowired private MockMvc mvc;
    @Autowired private JdbcClient db;
    @Autowired private UserDirectory directory;
    @Autowired private ShareTokens tokens;

    private long firstTestRow;
    private Long previouslyWorking;

    @BeforeEach
    void seed() {
        previouslyWorking = db.sql("SELECT id FROM live_share_token WHERE revoked_at IS NULL")
                .query(Long.class).optional().orElse(null);
        firstTestRow = db.sql("SELECT COALESCE(max(id), 0) + 1 FROM live_share_token").query(Long.class).single();
        db.sql("DELETE FROM app_user WHERE email IN (:a, :v)").param("a", ADMIN).param("v", VIEWER).update();
        db.sql("INSERT INTO app_user (email, role) VALUES (:e, 'ADMIN')").param("e", ADMIN).update();
        db.sql("INSERT INTO app_user (email, role) VALUES (:e, 'VIEWER')").param("e", VIEWER).update();
        directory.reload();
    }

    @AfterEach
    void restore() {
        db.sql("DELETE FROM live_share_token WHERE id >= :first").param("first", firstTestRow).update();
        if (previouslyWorking != null) {
            db.sql("UPDATE live_share_token SET revoked_at = NULL WHERE id = :id").param("id", previouslyWorking).update();
        }
        tokens.revokeCacheForTests();
        db.sql("DELETE FROM app_user WHERE email IN (:a, :v)").param("a", ADMIN).param("v", VIEWER).update();
        directory.reload();
    }

    @Test
    void theLinkOpensTheTimingReadsAndNothingElse() throws Exception {
        String token = tokens.issue(ADMIN).token();

        for (String path : new String[] {"/api/live/timing", "/api/live/weekends", "/api/live/sessions?feedEvent=1"}) {
            mvc.perform(get(path).header(H, token)).andExpect(status().isOk());
        }
        // Who connected, and which process holds the login, are left out.
        mvc.perform(get("/api/live/status").header(H, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestedBy").doesNotExist())
                .andExpect(jsonPath("$.holder").doesNotExist());

        // Authenticated but not a member: everything else is 403, not a login bounce.
        for (String path : new String[] {"/api/live/state", "/api/live/championships/1", "/api/live/classification",
                "/api/live/share", "/api/live/feed-championships", "/api/series", "/api/events/1/sheet"}) {
            mvc.perform(get(path).header(H, token)).andExpect(status().isForbidden());
        }
        mvc.perform(post("/api/live/connect").header(H, token).contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/live/feed-events/1/event").header(H, token).contentType("application/json")
                .content("{\"none\":true}")).andExpect(status().isForbidden());
        mvc.perform(get("/api/me").header(H, token)).andExpect(jsonPath("$.email").doesNotExist());
    }

    @Test
    void aWrongMissingReplacedOrRevokedLinkIsSignedOut() throws Exception {
        mvc.perform(get("/api/live/timing")).andExpect(status().isUnauthorized());
        String first = tokens.issue(ADMIN).token();
        mvc.perform(get("/api/live/timing").header(H, first + "x")).andExpect(status().isUnauthorized());

        String second = tokens.issue(ADMIN).token();
        mvc.perform(get("/api/live/timing").header(H, first)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/live/timing").header(H, second)).andExpect(status().isOk());

        tokens.revoke();
        mvc.perform(get("/api/live/timing").header(H, second)).andExpect(status().isUnauthorized());
    }

    @Test
    void onlyAdminsManageTheLinkAndTheSecretIsShownOnce() throws Exception {
        mvc.perform(get("/api/live/share").with(oidcLogin().idToken(t -> t.claim("email", VIEWER))))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/live/share").with(oidcLogin().idToken(t -> t.claim("email", ADMIN))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isString())
                .andExpect(jsonPath("$.link.createdBy").value(ADMIN));
        mvc.perform(get("/api/live/share").with(oidcLogin().idToken(t -> t.claim("email", ADMIN))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.link.createdBy").value(ADMIN))
                .andExpect(jsonPath("$.link.token").doesNotExist());
        mvc.perform(delete("/api/live/share").with(oidcLogin().idToken(t -> t.claim("email", ADMIN))))
                .andExpect(status().isOk());
        mvc.perform(get("/api/live/share").with(oidcLogin().idToken(t -> t.claim("email", ADMIN))))
                .andExpect(jsonPath("$.link").doesNotExist());
    }

    @Test
    void aViewerFloodingTheLinkIsSlowedDown() {
        ShareTokenFilter filter = new ShareTokenFilter(tokens);
        for (int i = 0; i < ShareTokenFilter.CAPACITY; i++) {
            assertTrue(filter.take("203.0.113.9"));
        }
        assertFalse(filter.take("203.0.113.9"), "past the burst");
        assertTrue(filter.take("198.51.100.4"), "another viewer is unaffected");
    }
}
