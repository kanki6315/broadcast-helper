package com.broadcasthelper.auth;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Wires up the secured chain (auth enabled, dummy Google registration) and
 * asserts the method-based authorization rules: reads for any signed-in user,
 * writes only for ROLE_ADMIN, /api/me public. Boots the full context like
 * {@link com.broadcasthelper.BackendApplicationTests}, so it needs the local
 * Postgres.
 */
@SpringBootTest(properties = {
        "broadcast-helper.auth.enabled=true",
        "broadcast-helper.auth.allowed-emails=viewer@example.com",
        "broadcast-helper.auth.admin-emails=admin@example.com",
        // Auth-enabled startup requires a registration; never contacted by MockMvc.
        "spring.security.oauth2.client.registration.google.client-id=test",
        "spring.security.oauth2.client.registration.google.client-secret=test",
})
@AutoConfigureMockMvc
class SecuredChainTest {

    @Autowired
    private MockMvc mvc;

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
        mvc.perform(get("/api/series").with(oidcLogin()))
                .andExpect(status().isOk());
    }

    @Test
    void signedInViewerCannotWrite() throws Exception {
        mvc.perform(patch("/api/teams/notes").with(oidcLogin()))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminCanWrite() throws Exception {
        // 400 not 401/403: authorization passed, the controller rejected the
        // empty body — which is all this test is about.
        mvc.perform(patch("/api/teams/notes")
                        .with(oidcLogin().authorities(
                                new SimpleGrantedAuthority("ROLE_ADMIN"),
                                new SimpleGrantedAuthority("OIDC_USER"))))
                .andExpect(status().isBadRequest());
    }
}
