package com.broadcasthelper.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The record-before-throw wiring at the login door: an unlisted email is
 * written to denied_login and rejected; a listed one passes through untouched.
 * Uses a stubbed directory so no app_user rows are needed.
 */
@SpringBootTest
@Transactional
class RequireListedTest {

    @Autowired
    private DeniedLogins deniedLogins;

    @Autowired
    private JdbcClient db;

    @BeforeEach
    void clearTable() {
        db.sql("DELETE FROM denied_login").update();
    }

    private static final UserDirectory ONLY_MEMBER = new UserDirectory(null) {
        @Override
        public boolean allows(String email) {
            return "member@example.test".equals(email);
        }
    };

    private static OidcUser googleUser(String email) {
        return new DefaultOidcUser(List.of(),
                OidcIdToken.withTokenValue("t").claim("sub", "x").claim("email", email).build());
    }

    @Test
    void unlistedEmailIsRecordedThenRejected() {
        OidcUser stranger = googleUser("stranger@example.test");
        assertThrows(OAuth2AuthenticationException.class,
                () -> SecurityConfig.requireListed(stranger, ONLY_MEMBER, deniedLogins));

        var rows = deniedLogins.list();
        assertEquals(1, rows.size());
        assertEquals("stranger@example.test", rows.get(0).email());
    }

    @Test
    void listedEmailPassesThroughWithoutARecord() {
        OidcUser member = googleUser("member@example.test");
        assertSame(member, SecurityConfig.requireListed(member, ONLY_MEMBER, deniedLogins));
        assertTrue(deniedLogins.list().isEmpty());
    }
}
