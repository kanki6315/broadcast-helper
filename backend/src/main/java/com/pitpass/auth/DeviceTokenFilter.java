package com.pitpass.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Turns {@code Authorization: Bearer <token>} into a {@link DeviceAuthentication}
 * for the rest of the chain. An unknown or revoked token simply leaves the
 * request anonymous, so it falls through to the normal 401 — the app treats
 * that as "signed out, link again". Registered inside the secured chain only
 * (not a {@code @Component}, which Boot would also install as a servlet filter
 * for every request); the open dev chain never sees a bearer header.
 */
public class DeviceTokenFilter extends OncePerRequestFilter {

    private static final String PREFIX = "Bearer ";

    private final DeviceTokens tokens;

    public DeviceTokenFilter(DeviceTokens tokens) {
        this.tokens = tokens;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.regionMatches(true, 0, PREFIX, 0, PREFIX.length())) {
            DeviceUser user = tokens.resolve(header.substring(PREFIX.length()).trim());
            if (user != null) {
                SecurityContext context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(new DeviceAuthentication(user));
                SecurityContextHolder.setContext(context);
            }
        }
        chain.doFilter(request, response);
    }
}
