package com.pitpass.auth;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

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
 * The shareable timing links through the secured chain: each reads what a
 * member reads, scratchpads and admin reads excepted, and writes nothing; links work side by side and revoking
 * one leaves the others working; a revoked one gets the 401 the shared page
 * turns into "this link no longer works". Committed fixtures, like
 * SecuredChainTest; the local database's own working links are restored
 * afterwards.
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
    private List<Long> previouslyWorking;

    @BeforeEach
    void seed() {
        previouslyWorking = db.sql("SELECT id FROM live_share_token WHERE revoked_at IS NULL")
                .query(Long.class).list();
        firstTestRow = db.sql("SELECT COALESCE(max(id), 0) + 1 FROM live_share_token").query(Long.class).single();
        db.sql("DELETE FROM app_user WHERE email IN (:a, :v)").param("a", ADMIN).param("v", VIEWER).update();
        db.sql("INSERT INTO app_user (email, role) VALUES (:e, 'ADMIN')").param("e", ADMIN).update();
        db.sql("INSERT INTO app_user (email, role) VALUES (:e, 'VIEWER')").param("e", VIEWER).update();
        directory.reload();
    }

    @AfterEach
    void restore() {
        db.sql("DELETE FROM live_share_token WHERE id >= :first").param("first", firstTestRow).update();
        if (!previouslyWorking.isEmpty()) {
            db.sql("UPDATE live_share_token SET revoked_at = NULL WHERE id IN (:ids)").param("ids", previouslyWorking).update();
        }
        tokens.revokeCacheForTests();
        db.sql("DELETE FROM app_user WHERE email IN (:a, :v)").param("a", ADMIN).param("v", VIEWER).update();
        directory.reload();
    }

    @Test
    void theLinkReadsWhatAMemberReadsAndWritesNothing() throws Exception {
        String token = tokens.issue("Sam", ADMIN).token();

        for (String path : new String[] {"/api/live/timing", "/api/live/weekends", "/api/live/sessions?feedEvent=1"}) {
            mvc.perform(get(path).header(H, token)).andExpect(status().isOk());
        }
        // Who connected, and which process holds the login, are left out.
        mvc.perform(get("/api/live/status").header(H, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestedBy").doesNotExist())
                .andExpect(jsonPath("$.holder").doesNotExist());
        // Member reads, the Points view's among them: past the chain (a
        // missing row may 404, but never 401/403).
        for (String path : new String[] {"/api/series", "/api/live/championships/1", "/api/live/classification",
                "/api/live/feed-championships", "/api/events/1/sheet", "/api/seasons/1"}) {
            int code = mvc.perform(get(path).header(H, token)).andReturn().getResponse().getStatus();
            assertTrue(code != 401 && code != 403, path + " answered " + code);
        }

        // Authenticated but not a member: admin reads, the raw feed and the
        // per-person scratchpads are 403, not a login bounce.
        for (String path : new String[] {"/api/live/state", "/api/live/share", "/api/users",
                "/api/users/sessions", "/api/imports/alkamel/years", "/api/events/1/scratchpad"}) {
            mvc.perform(get(path).header(H, token)).andExpect(status().isForbidden());
        }
        mvc.perform(post("/api/live/connect").header(H, token).contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/live/feed-events/1/event").header(H, token).contentType("application/json")
                .content("{\"none\":true}")).andExpect(status().isForbidden());
        mvc.perform(put("/api/events/1/scratchpad").header(H, token).contentType("application/json")
                .content("{}")).andExpect(status().isForbidden());
        mvc.perform(get("/api/me").header(H, token)).andExpect(jsonPath("$.email").doesNotExist());
    }

    @Test
    void linksWorkSideBySideAndRevokingOneLeavesTheRest() throws Exception {
        mvc.perform(get("/api/live/timing")).andExpect(status().isUnauthorized());
        var sam = tokens.issue("Sam", ADMIN);
        var alex = tokens.issue("Alex", ADMIN);
        mvc.perform(get("/api/live/timing").header(H, sam.token() + "x")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/live/timing").header(H, sam.token())).andExpect(status().isOk());
        mvc.perform(get("/api/live/timing").header(H, alex.token())).andExpect(status().isOk());

        assertTrue(tokens.revoke(sam.link().id()));
        assertFalse(tokens.revoke(sam.link().id()), "already revoked");
        mvc.perform(get("/api/live/timing").header(H, sam.token())).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/live/timing").header(H, alex.token())).andExpect(status().isOk());
    }

    @Test
    void onlyAdminsManageTheLinksAndEachSecretIsShownOnce() throws Exception {
        var admin = oidcLogin().idToken(t -> t.claim("email", ADMIN));
        mvc.perform(get("/api/live/share").with(oidcLogin().idToken(t -> t.claim("email", VIEWER))))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/live/share").with(oidcLogin().idToken(t -> t.claim("email", VIEWER)))
                .contentType("application/json").content("{\"label\":\"Sam\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/live/share").with(admin).contentType("application/json").content("{\"label\":\"  \"}"))
                .andExpect(status().isUnprocessableEntity());
        String body = mvc.perform(post("/api/live/share").with(admin)
                        .contentType("application/json").content("{\"label\":\" Sam (booth) \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isString())
                .andExpect(jsonPath("$.link.label").value("Sam (booth)"))
                .andExpect(jsonPath("$.link.createdBy").value(ADMIN))
                .andReturn().getResponse().getContentAsString();
        long id = ((Number) JsonPath.read(body, "$.link.id")).longValue();
        mvc.perform(get("/api/live/share").with(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.links[?(@.id == " + id + ")].label").value("Sam (booth)"))
                .andExpect(jsonPath("$.links[0].token").doesNotExist());
        mvc.perform(delete("/api/live/share/" + id).with(oidcLogin().idToken(t -> t.claim("email", VIEWER))))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/live/share/" + id).with(admin)).andExpect(status().isOk());
        mvc.perform(delete("/api/live/share/" + id).with(admin)).andExpect(status().isNotFound());
        mvc.perform(get("/api/live/share").with(admin))
                .andExpect(jsonPath("$.links[?(@.id == " + id + ")]").isEmpty());
    }

    @Test
    void aLinkFloodingIsSlowedDownWithoutSlowingTheOthers() {
        ShareTokenFilter filter = new ShareTokenFilter(tokens);
        for (int i = 0; i < ShareTokenFilter.CAPACITY; i++) {
            assertTrue(filter.take(1L));
        }
        assertFalse(filter.take(1L), "past the burst");
        assertTrue(filter.take(2L), "another link is unaffected");
    }
}
