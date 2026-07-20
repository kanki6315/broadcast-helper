package com.pitpass.auth;

import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.stereotype.Component;

/**
 * Request-time authorization: every decision reads the current allowlist
 * instead of trusting a role stamped into the session at login, so a
 * membership change takes effect on the next request rather than when the
 * session expires (up to days later).
 *
 * <p>Anonymous callers carry no OidcUser → null email → deny; the
 * ExceptionTranslationFilter then routes unauthenticated denials to the
 * HttpStatusEntryPoint (401) and authenticated ones to the default handler
 * (403), so the SPA's 401-only login redirect (lib/authRedirect.ts) is
 * unaffected.
 */
@Component
public class LiveAuthorization {

    private final UserDirectory directory;

    public LiveAuthorization(UserDirectory directory) {
        this.directory = directory;
    }

    /** Any listed email (viewer or admin) — gates GET/HEAD under /api. */
    public AuthorizationManager<RequestAuthorizationContext> member() {
        return (authentication, context) ->
                new AuthorizationDecision(directory.allows(emailOf(authentication.get())));
    }

    /** Admin emails only — gates every other method under /api. */
    public AuthorizationManager<RequestAuthorizationContext> admin() {
        return (authentication, context) ->
                new AuthorizationDecision(directory.isAdmin(emailOf(authentication.get())));
    }

    private static String emailOf(Authentication authentication) {
        return authentication != null && authentication.getPrincipal() instanceof OidcUser u
                ? u.getEmail()
                : null;
    }
}
