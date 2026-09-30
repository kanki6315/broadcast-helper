package com.pitpass.live;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * IMSA's own telemetry (imsa.com/telemetry): an AWS AppSync Events websocket
 * behind the public telemetry page, carrying energy remaining per car. It is
 * unofficial and undocumented, so it is off by default, fails soft, and never
 * affects the Al Kamel connection. Every value comes from an
 * {@code IMSA_TELEMETRY_*} env var through application.yml.
 *
 * The API key and endpoint are read from the telemetry app's JavaScript
 * bundle at every connect: AppSync keys expire, so a key copied into config
 * would silently stop working.
 */
@ConfigurationProperties(prefix = "pit-pass.imsa-telemetry")
public record ImsaTelemetryProperties(
        boolean enabled,
        String appUrl,
        List<String> series,
        List<String> channels,
        int staleSeconds,
        boolean recordingEnabled,
        String replayFile,
        double replaySpeed) {

    /**
     * Only these series carry IMSA energy telemetry (WeatherTech's GTP): the
     * connection runs only while the bound event belongs to one, matched on
     * series name or abbreviation, ignoring case.
     */
    public boolean coversSeries(String name, String abbreviation) {
        return (series == null ? List.<String>of() : series).stream().map(String::trim).filter(s -> !s.isEmpty())
                .anyMatch(s -> s.equalsIgnoreCase(name == null ? "" : name.trim())
                        || s.equalsIgnoreCase(abbreviation == null ? "" : abbreviation.trim()));
    }

    public boolean replaying() {
        return replayFile != null && !replayFile.isBlank();
    }

    /** Runs when switched on, or when a recording is being replayed locally. */
    public boolean configured() {
        return enabled || replaying();
    }

    /** AppSync Events channel paths start with a slash; accept them written either way. */
    public List<String> channelPaths() {
        return (channels == null ? List.<String>of() : channels).stream()
                .map(String::trim)
                .filter(c -> !c.isEmpty())
                .map(c -> c.startsWith("/") ? c : "/" + c)
                .toList();
    }
}
