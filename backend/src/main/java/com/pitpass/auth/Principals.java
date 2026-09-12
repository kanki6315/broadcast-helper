package com.pitpass.auth;

import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

/**
 * The one place that knows what a signed-in principal looks like. Two kinds
 * exist: the Google {@link OidcUser} a browser session carries, and the
 * {@link DeviceUser} a native-app bearer token resolves to. Everything that
 * needs "who is calling" — {@link LiveAuthorization}, {@code /api/me}, the
 * scratchpad owner — asks here rather than pattern-matching on OidcUser, so
 * adding a third kind is one edit.
 */
public final class Principals {

    private Principals() {
    }

    /** Email of a signed-in principal, or null for anonymous/unknown. */
    public static String emailOf(Object principal) {
        if (principal instanceof OidcUser u) {
            return u.getEmail();
        }
        if (principal instanceof DeviceUser d) {
            return d.email();
        }
        return null;
    }

    public static String emailOf(Authentication authentication) {
        return authentication == null ? null : emailOf(authentication.getPrincipal());
    }
}
