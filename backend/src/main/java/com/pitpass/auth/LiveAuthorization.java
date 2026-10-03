package com.pitpass.auth;

import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.stereotype.Component;

/**
 * Request-time authorization: every decision reads the current allowlist
 * instead of trusting a role stamped into the session at login, so a
 * membership change takes effect on the next request rather than when the
 * session expires (up to days later).
 *
 * <p>Anonymous callers carry no principal → null email → deny; the
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

    /** Any listed email (viewer or admin). */
    public AuthorizationManager<RequestAuthorizationContext> member() {
        return (authentication, context) ->
                new AuthorizationDecision(directory.allows(emailOf(authentication.get())));
    }

    /** Admin emails only — gates every other method under /api. */
    public AuthorizationManager<RequestAuthorizationContext> admin() {
        return (authentication, context) ->
                new AuthorizationDecision(directory.isAdmin(emailOf(authentication.get())));
    }

    /**
     * Reads: any member, or whoever holds a shareable timing link (a
     * {@link ShareAuthentication}, which no other rule admits). Gates GET/HEAD
     * under /api, after the admin-only reads and the scratchpad are ruled out.
     */
    public AuthorizationManager<RequestAuthorizationContext> reader() {
        return (authentication, context) -> {
            Authentication a = authentication.get();
            return new AuthorizationDecision(a instanceof ShareAuthentication || directory.allows(emailOf(a)));
        };
    }

    private static String emailOf(Authentication authentication) {
        return Principals.emailOf(authentication);
    }
}
