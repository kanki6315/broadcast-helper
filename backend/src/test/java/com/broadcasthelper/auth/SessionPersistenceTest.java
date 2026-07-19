package com.broadcasthelper.auth;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Sessions live in Postgres (V34) so a redeploy doesn't sign everyone out. The
 * risk that buys is serialization: the SecurityContext holding the OidcUser and
 * its ID token is Java-serialized into ATTRIBUTE_BYTES, and a signed-in user is
 * only as durable as that round-trip. This proves it survives — storing exactly
 * what the OAuth2 login stores, then reading it back through a fresh load.
 *
 * <p>Not {@code @Transactional}: the session repository writes in its own
 * transaction, so a rollback wouldn't clean up anyway — fixtures are deleted
 * explicitly.
 */
@SpringBootTest
class SessionPersistenceTest {

    private static final String KEY = HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY;
    private static final String EMAIL = "session-persist@example.test";

    @Autowired
    private SessionRepository<? extends Session> sessions;

    @Autowired
    private JdbcClient db;

    private String savedId;

    @AfterEach
    void cleanUp() {
        if (savedId != null) {
            sessions.deleteById(savedId);
            savedId = null;
        }
    }

    /** Capture the repository's session type so create/save line up. */
    private <S extends Session> String persist(SessionRepository<S> repository, Object attribute) {
        S session = repository.createSession();
        session.setAttribute(KEY, attribute);
        repository.save(session);
        return session.getId();
    }

    private static OidcUser oidcUser() {
        return new DefaultOidcUser(
                List.of(new SimpleGrantedAuthority("OIDC_USER")),
                OidcIdToken.withTokenValue("token-value")
                        .claim("sub", "google-subject-id")
                        .claim("email", EMAIL)
                        .build());
    }

    @Test
    void signedInUserSurvivesTheRoundTripThroughPostgres() {
        OidcUser user = oidcUser();
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(
                new OAuth2AuthenticationToken(user, user.getAuthorities(), "google"));

        savedId = persist(sessions, context);

        // A fresh load, not the in-flight object — this is the path a request
        // after a restart takes.
        Session loaded = sessions.findById(savedId);
        assertNotNull(loaded, "session should be readable back from Postgres");

        SecurityContext restored = loaded.getAttribute(KEY);
        assertNotNull(restored, "SecurityContext should deserialize");
        OidcUser principal = (OidcUser) restored.getAuthentication().getPrincipal();
        assertEquals(EMAIL, principal.getEmail());
        assertEquals("token-value", principal.getIdToken().getTokenValue());
    }

    @Test
    void sessionIsActuallyPersistedAsRows() {
        savedId = persist(sessions, SecurityContextHolder.createEmptyContext());

        Integer rows = db.sql("SELECT count(*) FROM spring_session WHERE session_id = :id")
                .param("id", savedId)
                .query(Integer.class)
                .single();
        assertEquals(1, rows, "the session should be a row, not just in memory");
    }
}
