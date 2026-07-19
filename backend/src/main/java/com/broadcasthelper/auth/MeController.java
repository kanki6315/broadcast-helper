package com.broadcasthelper.auth;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The SPA polls this on load to learn auth state without being redirected: it is
 * public even when auth is on. {@code authEnabled=false} → open (local dev);
 * {@code authEnabled=true, email=null} → not signed in, so the frontend sends the
 * browser to Google; a non-null email means signed in. The backend, not the
 * frontend, decides that "auth off ⇒ admin", so the SPA has exactly one boolean
 * to consult for showing edit controls.
 */
@RestController
public class MeController {

    private final AuthProperties auth;
    private final UserDirectory directory;

    public MeController(AuthProperties auth, UserDirectory directory) {
        this.auth = auth;
        this.directory = directory;
    }

    public record Me(boolean authEnabled, String email, boolean isAdmin) {
    }

    @GetMapping("/api/me")
    public Me me(@AuthenticationPrincipal OidcUser user) {
        if (!auth.enabled()) {
            return new Me(false, null, true);
        }
        String email = user != null ? user.getEmail() : null;
        return new Me(true, email, directory.isAdmin(email));
    }
}
