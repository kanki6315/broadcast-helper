package com.pitpass.auth;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The SPA polls this on load to learn auth state without being redirected: it is
 * public even when auth is on. {@code authEnabled=false} → open (local dev);
 * {@code authEnabled=true, email=null} → not signed in, so the frontend sends the
 * browser to Google; a non-null email means signed in. The backend, not the
 * frontend, decides that "auth off ⇒ admin", so the SPA has exactly one boolean
 * to consult for showing edit controls. The principal may be a Google session
 * or a native-app device token; {@link Principals} tells them apart.
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
    public Me me(@AuthenticationPrincipal Object user) {
        if (!auth.enabled()) {
            return new Me(false, null, true);
        }
        String email = Principals.emailOf(user);
        return new Me(true, email, directory.isAdmin(email));
    }
}
