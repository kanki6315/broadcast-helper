package com.pitpass.auth;

import org.springframework.security.authentication.AbstractAuthenticationToken;

import java.util.List;

/**
 * Whoever holds the shareable timing link. Not a person and not a member:
 * {@link Principals#emailOf} gives it no email, so every member and admin rule
 * refuses it, and only the timing reads SecurityConfig names let it through
 * ({@link LiveAuthorization#timingReader}).
 */
public class ShareAuthentication extends AbstractAuthenticationToken {

    public static final String NAME = "share-link";

    public ShareAuthentication() {
        super(List.of());
        setAuthenticated(true);
    }

    @Override
    public Object getCredentials() {
        return null;
    }

    @Override
    public Object getPrincipal() {
        return NAME;
    }

    @Override
    public String getName() {
        return NAME;
    }
}
