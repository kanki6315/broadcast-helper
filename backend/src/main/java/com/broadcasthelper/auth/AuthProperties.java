package com.broadcasthelper.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Auth config (Phase 4a Step 2). When {@code enabled} is false (local dev
 * default) the app is open; when true it requires Google login and the signed-in
 * email must be in {@code allowedEmails}. An empty allowlist admits nobody — a
 * safe default that forces the deployment to name its editors explicitly.
 */
@ConfigurationProperties(prefix = "broadcast-helper.auth")
public record AuthProperties(boolean enabled, List<String> allowedEmails) {

    public AuthProperties {
        allowedEmails = allowedEmails == null ? List.of()
                : allowedEmails.stream()
                        .map(e -> e == null ? "" : e.trim().toLowerCase())
                        .filter(e -> !e.isBlank())
                        .toList();
    }

    public boolean allows(String email) {
        return email != null && allowedEmails.contains(email.trim().toLowerCase());
    }
}
