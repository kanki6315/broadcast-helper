package com.broadcasthelper.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Auth config (Phase 4a Step 2). When {@code enabled} is false (local dev
 * default) the app is open; when true it requires Google login. Two tiers:
 * {@code adminEmails} get full access, {@code allowedEmails} are read-only
 * viewers (the secured chain rejects their non-GET {@code /api} calls). Admins
 * are implicitly allowed to sign in, so an email never needs to appear in both
 * lists. Both lists empty = nobody signs in — a safe default that forces the
 * deployment to name its users explicitly.
 */
@ConfigurationProperties(prefix = "broadcast-helper.auth")
public record AuthProperties(boolean enabled, List<String> allowedEmails, List<String> adminEmails) {

    public AuthProperties {
        allowedEmails = normalize(allowedEmails);
        adminEmails = normalize(adminEmails);
    }

    private static List<String> normalize(List<String> emails) {
        return emails == null ? List.of()
                : emails.stream()
                        .map(e -> e == null ? "" : e.trim().toLowerCase())
                        .filter(e -> !e.isBlank())
                        .toList();
    }

    public boolean allows(String email) {
        String e = norm(email);
        return e != null && (allowedEmails.contains(e) || adminEmails.contains(e));
    }

    public boolean isAdmin(String email) {
        String e = norm(email);
        return e != null && adminEmails.contains(e);
    }

    private static String norm(String email) {
        return email == null ? null : email.trim().toLowerCase();
    }
}
