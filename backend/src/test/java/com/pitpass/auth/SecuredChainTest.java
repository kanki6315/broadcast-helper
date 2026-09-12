package com.pitpass.auth;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Wires up the secured chain (auth enabled, dummy Google registration) and
 * asserts the request-time authorization rules end-to-end: app_user row →
 * UserDirectory snapshot → LiveAuthorization decision. Deliberately NOT
 * {@code @Transactional}: the directory reload must read committed rows, and a
 * rollback would leave the singleton snapshot caching state that never
 * existed — so fixtures are committed in setup and deleted (plus a snapshot
 * reload) in teardown. Boots the full context, so it needs the local Postgres.
 */
@SpringBootTest(properties = {
        "pit-pass.auth.enabled=true",
        // Auth-enabled startup requires a registration; never contacted by MockMvc.
        "spring.security.oauth2.client.registration.google.client-id=test",
        "spring.security.oauth2.client.registration.google.client-secret=test",
})
@AutoConfigureMockMvc
class SecuredChainTest {

    private static final String ADMIN = "secured-admin@example.test";
    private static final String VIEWER = "secured-viewer@example.test";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient db;

    @Autowired
    private UserDirectory directory;

    @Autowired
    private DeviceTokens deviceTokens;

    @BeforeEach
    void seedUsers() {
        removeFixtures();
        db.sql("INSERT INTO app_user (email, role) VALUES (:e, 'ADMIN')").param("e", ADMIN).update();
        db.sql("INSERT INTO app_user (email, role) VALUES (:e, 'VIEWER')").param("e", VIEWER).update();
        directory.reload();
    }

    @AfterEach
    void cleanUp() {
        removeFixtures();
        directory.reload();
    }

    private void removeFixtures() {
        db.sql("DELETE FROM app_user WHERE email IN (:a, :v)")
                .param("a", ADMIN).param("v", VIEWER).update();
        db.sql("DELETE FROM device_token WHERE owner_email IN (:a, :v)")
                .param("a", ADMIN).param("v", VIEWER).update();
    }

    /** A real device token for the email, as the iPad app would hold it. */
    private String deviceTokenFor(String email) {
        return deviceTokens.exchange(deviceTokens.issueCode(email, "Test iPad")).token();
    }

    private static RequestPostProcessor signedInAs(String email) {
        return oidcLogin().idToken(t -> t.claim("email", email));
    }

    @Test
    void anonymousGetsUnauthorizedOnApi() throws Exception {
        mvc.perform(get("/api/series")).andExpect(status().isUnauthorized());
    }

    @Test
    void anonymousCanReachMe() throws Exception {
        mvc.perform(get("/api/me")).andExpect(status().isOk());
    }

    @Test
    void signedInViewerCanRead() throws Exception {
        mvc.perform(get("/api/series").with(signedInAs(VIEWER)))
                .andExpect(status().isOk());
    }

    @Test
    void signedInViewerCannotWrite() throws Exception {
        mvc.perform(patch("/api/teams/notes").with(signedInAs(VIEWER)))
                .andExpect(status().isForbidden());
    }

    @Test
    void unlistedSignedInUserCannotRead() throws Exception {
        // The live check consults the roster on every request, so a session
        // whose row has been removed loses access immediately.
        mvc.perform(get("/api/series").with(signedInAs("stranger@example.test")))
                .andExpect(status().isForbidden());
    }

    @Test
    void rosterAndDeniedListsAreAdminOnlyEvenForReads() throws Exception {
        mvc.perform(get("/api/users").with(signedInAs(VIEWER)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/users/denied").with(signedInAs(VIEWER)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/users/sessions").with(signedInAs(VIEWER)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/users").with(signedInAs(ADMIN)))
                .andExpect(status().isOk());
    }

    @Test
    void signedInViewerCanWriteOwnScratchpad() throws Exception {
        // 400 not 403: the PUT carve-out admits members, and the empty body is
        // rejected only after authorization has already passed.
        mvc.perform(put("/api/events/1/scratchpad").with(signedInAs(VIEWER)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void anonymousCannotWriteScratchpad() throws Exception {
        mvc.perform(put("/api/events/1/scratchpad"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void adminCanWrite() throws Exception {
        // 400 not 401/403: authorization passed, the controller rejected the
        // empty body — which is all this test is about.
        mvc.perform(patch("/api/teams/notes").with(signedInAs(ADMIN)))
                .andExpect(status().isBadRequest());
    }

    // ---- native-app bearer tokens ----

    @Test
    void bearerTokenReadsLikeASession() throws Exception {
        String token = deviceTokenFor(VIEWER);
        mvc.perform(get("/api/series").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        // /api/me reports the device's identity, which is how the app knows
        // its token is still good.
        mvc.perform(get("/api/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(VIEWER))
                .andExpect(jsonPath("$.isAdmin").value(false));
    }

    @Test
    void bearerTokenObeysTheSameRosterRules() throws Exception {
        String viewer = deviceTokenFor(VIEWER);
        mvc.perform(get("/api/users").header("Authorization", "Bearer " + viewer))
                .andExpect(status().isForbidden());
        mvc.perform(patch("/api/teams/notes").header("Authorization", "Bearer " + viewer))
                .andExpect(status().isForbidden());
        String admin = deviceTokenFor(ADMIN);
        mvc.perform(get("/api/users/devices").header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk());
    }

    @Test
    void unknownOrRevokedBearerTokenIsUnauthorized() throws Exception {
        mvc.perform(get("/api/series").header("Authorization", "Bearer nope"))
                .andExpect(status().isUnauthorized());
        String token = deviceTokenFor(VIEWER);
        mvc.perform(delete("/api/auth/device").header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/series").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void deviceSignInEndpointsAreReachableSignedOut() throws Exception {
        mvc.perform(get("/api/auth/device/start").param("name", "iPad"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", startsWith("/oauth2/authorization/google")));
        mvc.perform(post("/api/auth/device/exchange")
                        .contentType("application/json")
                        .content("{\"code\":\"bogus\"}"))
                .andExpect(status().isBadRequest());
        // But signing a device out needs to BE a device.
        mvc.perform(delete("/api/auth/device")).andExpect(status().isUnauthorized());
    }
}
