package com.pitpass.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Auth config. When {@code enabled} is false (local dev default) the app is
 * open; when true it requires Google login. Who may sign in and who may write
 * lives in the {@code app_user} table (see {@link UserDirectory}), managed
 * from Manage → Users — not in env vars.
 */
@ConfigurationProperties(prefix = "pit-pass.auth")
public record AuthProperties(boolean enabled) {
}
