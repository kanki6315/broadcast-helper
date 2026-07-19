package com.broadcasthelper.auth;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;

/**
 * Two mutually-exclusive filter chains selected by {@code broadcast-helper.auth.enabled}:
 * a permit-all chain for local dev (no Google creds needed), and a Google-OIDC
 * chain for the hosted deployment. The secured chain gates every {@code /api/**}
 * except {@code /api/me} (the SPA polls it to learn auth state) and leaves the
 * static bundle open so the app shell can load and drive the login redirect.
 * Authorization is decided per request by {@link LiveAuthorization} — reads for
 * any listed email, writes for admins, and {@code /api/users/**} (the roster +
 * denied-login lists) admin-only even for reads — so membership changes apply
 * immediately instead of when the session expires. Login itself only rejects
 * unlisted emails (the access_denied UX, recorded via {@link DeniedLogins});
 * it stamps no roles into the session.
 */
@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(AuthProperties.class)
public class SecurityConfig {

    // Static SPA shell + the endpoints the login flow itself needs.
    private static final String[] PUBLIC = {
            "/", "/index.html", "/favicon.svg", "/assets/**",
            "/api/me", "/oauth2/**", "/login/**", "/error"
    };

    @Bean
    @ConditionalOnProperty(prefix = "broadcast-helper.auth", name = "enabled", havingValue = "true")
    SecurityFilterChain securedChain(HttpSecurity http, UserDirectory directory,
                                     LiveAuthorization live, DeniedLogins deniedLogins)
            throws Exception {
        http
                .authorizeHttpRequests(a -> a
                        // PUBLIC first so /api/me stays reachable signed-out.
                        .requestMatchers(PUBLIC).permitAll()
                        // The roster and denied-login lists are admin-only even
                        // for reads — must precede the general GET rule below.
                        .requestMatchers("/api/users/**").access(live.admin())
                        .requestMatchers(HttpMethod.GET, "/api/**").access(live.member())
                        .requestMatchers(HttpMethod.HEAD, "/api/**").access(live.member())
                        // Every other method — including OPTIONS and anything
                        // exotic — fails closed to admins. Same-origin SPA sends
                        // no CORS preflights, so nothing legitimate is lost.
                        .requestMatchers("/api/**").access(live.admin())
                        .anyRequest().authenticated())
                .oauth2Login(o -> o
                        .userInfoEndpoint(u -> u.oidcUserService(
                                allowlistUserService(directory, deniedLogins)))
                        // Login itself returns to the SPA; a rejected email lands
                        // back with a flag the frontend can surface.
                        .defaultSuccessUrl("/", true)
                        .failureUrl("/?authError=1"))
                .logout(l -> l.logoutSuccessUrl("/").permitAll())
                // Same-origin SPA with a SameSite=Lax session cookie; no CSRF tokens.
                .csrf(c -> c.disable())
                // Protected resources are all XHR (/api/**) — answer 401 so the SPA
                // can redirect to login itself, rather than a server-side 302.
                .exceptionHandling(e -> e.authenticationEntryPoint(
                        new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)));
        return http.build();
    }

    @Bean
    @ConditionalOnProperty(prefix = "broadcast-helper.auth", name = "enabled", havingValue = "false", matchIfMissing = true)
    SecurityFilterChain openChain(HttpSecurity http) throws Exception {
        http
                .authorizeHttpRequests(a -> a.anyRequest().permitAll())
                .csrf(c -> c.disable());
        return http.build();
    }

    /**
     * Loads the Google profile and rejects any email not in the user roster so
     * a stranger gets the access_denied message at the door. Roles are NOT
     * granted here — {@link LiveAuthorization} decides them per request.
     */
    private OAuth2UserService<OidcUserRequest, OidcUser> allowlistUserService(
            UserDirectory directory, DeniedLogins deniedLogins) {
        OidcUserService delegate = new OidcUserService();
        return request -> emailNamed(requireListed(delegate.loadUser(request), directory, deniedLogins));
    }

    /**
     * Records the attempt and rejects when the email isn't in the roster.
     * Package-private so a unit test can cover record-before-throw without a
     * real Google flow.
     */
    static OidcUser requireListed(OidcUser user, UserDirectory directory, DeniedLogins deniedLogins) {
        if (!directory.allows(user.getEmail())) {
            deniedLogins.record(user.getEmail());
            throw new OAuth2AuthenticationException(
                    new OAuth2Error("access_denied"),
                    "Email not on the user list: " + user.getEmail());
        }
        return user;
    }

    /**
     * Rewraps the user so {@code getName()} — and therefore Spring Session's
     * {@code principal_name} column — is the email rather than Google's opaque
     * {@code sub}, which is what makes the Sessions admin page readable. Called
     * only after {@link #requireListed} confirms a non-null email, so the name
     * attribute is always present. Authorities are preserved.
     */
    static OidcUser emailNamed(OidcUser user) {
        return new DefaultOidcUser(user.getAuthorities(), user.getIdToken(), user.getUserInfo(), "email");
    }
}
