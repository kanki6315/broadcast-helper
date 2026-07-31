package com.pitpass.web;

import java.io.InputStream;
import java.util.regex.Pattern;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.web.filter.ShallowEtagHeaderFilter;

/**
 * Stamps a content-hash ETag on JSON GETs under {@code /api}. The PWA's
 * service worker serves those reads stale-while-revalidate and decides
 * "did the payload actually change?" by comparing response headers
 * (etag / content-length / last-modified — see the api-data route in
 * frontend/vite.config.ts). Jackson streams JSON as chunked transfer with
 * none of those headers, so without an ETag the change detector can never
 * fire and the "newer data available" nudge would stay silent forever.
 *
 * <p>Two deviations from the stock {@link ShallowEtagHeaderFilter}:
 *
 * <ul>
 *   <li>Eligibility ignores {@code Cache-Control: no-store}. Spring Security
 *       stamps that on every response as a browser-cache precaution, and the
 *       stock filter treats it as "don't fingerprint" — but the service
 *       worker's Cache Storage is exempt from HTTP cache directives, so the
 *       ETag is still load-bearing there. Weak ETags, so a fronting proxy
 *       that gzips (which invalidates strong validators) keeps them.</li>
 *   <li>Binary blob endpoints are skipped: they already carry a real
 *       Content-Length (byte[] responses aren't chunked), the service worker
 *       caches them CacheFirst behind ?v= busters and never revalidates, and
 *       buffering image bytes just to hash them would be pure overhead. The
 *       pattern mirrors the api-images matcher in vite.config.ts.</li>
 * </ul>
 */
@Configuration
public class ApiEtagConfig {

    private static final Pattern BINARY_BLOB = Pattern.compile(
            "^/api/(drivers/[^/]+/photo|car-images/[^/]+/data|series/[^/]+/logo/data|manufacturer-logos/[^/]+/data)$");

    @Bean
    FilterRegistrationBean<ShallowEtagHeaderFilter> apiEtagFilter() {
        ShallowEtagHeaderFilter filter = new ShallowEtagHeaderFilter() {
            @Override
            protected boolean shouldNotFilter(HttpServletRequest request) {
                return BINARY_BLOB.matcher(request.getRequestURI()).matches();
            }

            @Override
            protected boolean isEligibleForEtag(HttpServletRequest request, HttpServletResponse response,
                                                int responseStatusCode, InputStream inputStream) {
                // Stock rule minus the Cache-Control:no-store opt-out (see class doc).
                return !response.isCommitted()
                        && responseStatusCode >= 200 && responseStatusCode < 300
                        && HttpMethod.GET.matches(request.getMethod());
            }
        };
        filter.setWriteWeakETag(true);
        FilterRegistrationBean<ShallowEtagHeaderFilter> registration = new FilterRegistrationBean<>(filter);
        registration.addUrlPatterns("/api/*");
        return registration;
    }
}
