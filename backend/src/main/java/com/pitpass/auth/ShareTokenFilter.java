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
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Turns {@code X-Pit-Pass-Share: <secret>} into a {@link ShareAuthentication}
 * for the rest of the chain, when the secret is a working link's. A wrong
 * or revoked one leaves the request anonymous: the shared page gets a 401 and
 * says the link no longer works. A request that already carries a signed-in
 * user (a member previewing the page) keeps that user.
 *
 * <p>Each link is rate-limited on its own — a token bucket that allows a
 * booth of a few screens polling the tower, and answers 429 past it, so one
 * leaked link cannot flood the server and does not slow anyone else's link.
 * Per link rather than per client address, since viewers behind one network
 * share an address. Registered inside the secured chain only, like
 * {@link DeviceTokenFilter}.
 */
public class ShareTokenFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Pit-Pass-Share";

    static final double CAPACITY = 100;
    static final double REFILL_PER_SECOND = 10;

    private final ShareTokens tokens;
    private final Map<Long, double[]> buckets = new ConcurrentHashMap<>();

    public ShareTokenFilter(ShareTokens tokens) {
        this.tokens = tokens;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String secret = request.getHeader(HEADER);
        var existing = SecurityContextHolder.getContext().getAuthentication();
        boolean signedIn = existing != null && Principals.emailOf(existing) != null;
        OptionalLong link = secret != null && !signedIn ? tokens.match(secret) : OptionalLong.empty();
        if (link.isPresent()) {
            if (!take(link.getAsLong())) {
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

    // One bucket per link ever matched on this process: a handful, so never pruned.
    boolean take(long link) {
        long now = System.nanoTime();
        double[] bucket = buckets.computeIfAbsent(link, k -> new double[] {CAPACITY, now});
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
