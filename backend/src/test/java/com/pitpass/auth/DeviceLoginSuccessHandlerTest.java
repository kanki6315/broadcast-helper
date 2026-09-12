package com.pitpass.auth;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where a completed Google login lands: the SPA root for browsers, the app's
 * custom scheme with a one-time code for logins the app started. The token
 * store is stubbed by subclass (house pattern).
 */
class DeviceLoginSuccessHandlerTest {

    private static final class RecordingTokens extends DeviceTokens {
        String email;
        String deviceName;

        RecordingTokens() {
            super(null);
        }

        @Override
        public String issueCode(String email, String deviceName) {
            this.email = email;
            this.deviceName = deviceName;
            return "code/with+chars";
        }
    }

    private static Authentication googleLogin(String email) {
        OidcIdToken token = new OidcIdToken("t", Instant.now(), Instant.now().plusSeconds(60),
                Map.of("sub", "s", "email", email));
        return new TestingAuthenticationToken(new DefaultOidcUser(List.of(), token, "email"), null);
    }

    @Test
    void browserLoginGoesToTheSpaRoot() throws Exception {
        RecordingTokens tokens = new RecordingTokens();
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        new DeviceLoginSuccessHandler(tokens).onAuthenticationSuccess(request, response, googleLogin("web@example.test"));

        assertEquals("/", response.getRedirectedUrl());
        assertNull(tokens.email, "no code minted for a browser login");
    }

    @Test
    void appLoginMintsACodeAndRedirectsIntoTheApp() throws Exception {
        RecordingTokens tokens = new RecordingTokens();
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(DeviceLoginSuccessHandler.DEVICE_LOGIN_ATTR, "Booth iPad");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSession(session);
        MockHttpServletResponse response = new MockHttpServletResponse();

        new DeviceLoginSuccessHandler(tokens).onAuthenticationSuccess(request, response, googleLogin("pad@example.test"));

        assertEquals("pitpass://auth?code=code%2Fwith%2Bchars", response.getRedirectedUrl());
        assertEquals("pad@example.test", tokens.email);
        assertEquals("Booth iPad", tokens.deviceName);
        assertTrue(session.isInvalid(), "the browser-sheet session is thrown away");
    }
}
