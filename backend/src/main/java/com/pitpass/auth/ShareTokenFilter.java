package com.pitpass.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Turns {@code X-Pit-Pass-Share: <secret>} into a {@link ShareAuthentication}
 * for the rest of the chain, when the secret is the working link's. A wrong
 * or revoked one leaves the request anonymous: the shared page gets a 401 and
 * says the link no longer works. A request that already carries a signed-in
 * user (a member previewing the page) keeps that user.
 *
 * <p>Shared viewers are rate-limited per client address — a token bucket that
 * allows a booth of a few screens polling the tower, and answers 429 past it,
 * so one leaked link cannot flood the server. Registered inside the secured
 * chain only, like {@link DeviceTokenFilter}.
 */
public class ShareTokenFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Pit-Pass-Share";

    static final double CAPACITY = 100;
    static final double REFILL_PER_SECOND = 10;
    private static final int MAX_CLIENTS = 10_000;

    private final ShareTokens tokens;
    private final Map<String, double[]> buckets = new ConcurrentHashMap<>();

    public ShareTokenFilter(ShareTokens tokens) {
        this.tokens = tokens;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String secret = request.getHeader(HEADER);
        var existing = SecurityContextHolder.getContext().getAuthentication();
        boolean signedIn = existing != null && Principals.emailOf(existing) != null;
        if (secret != null && !signedIn && tokens.matches(secret)) {
            if (!take(client(request))) {
                response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                response.getWriter().write("{\"status\":429,\"message\":\"Too many requests from this shared link\"}");
                return;
            }
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(new ShareAuthentication());
            SecurityContextHolder.setContext(context);
        }
        chain.doFilter(request, response);
    }

    // The proxy (Railway) puts the viewer first in X-Forwarded-For.
    private static String client(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    boolean take(String client) {
        if (buckets.size() > MAX_CLIENTS) {
            buckets.clear(); // crude, and rare: forgets everyone's history at once
        }
        long now = System.nanoTime();
        double[] bucket = buckets.computeIfAbsent(client, k -> new double[] {CAPACITY, now});
        synchronized (bucket) {
            double refilled = Math.min(CAPACITY, bucket[0] + (now - bucket[1]) / 1e9 * REFILL_PER_SECOND);
            bucket[1] = now;
            if (refilled < 1) {
                bucket[0] = refilled;
                return false;
            }
            bucket[0] = refilled - 1;
            return true;
        }
    }
}
