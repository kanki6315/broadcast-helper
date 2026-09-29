package com.pitpass.live;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * The Al Kamel live timing feed (AKS V2). Every value comes from an
 * {@code ALKAMELV2_*} env var through application.yml. Blank host = the
 * feature is off and no background thread exists, which is the state of local
 * dev, CI and any deployment that has not been given the feed.
 */
@ConfigurationProperties(prefix = "pit-pass.alkamel-v2")
public record AlKamelV2Properties(
        String host,
        int port,
        String username,
        String password,
        boolean tlsEnabled,
        boolean tlsVerifyCertificate,
        String clientAppName,
        List<String> channels,
        int maxLineBytes,
        int connectTimeoutSeconds,
        int loginTimeoutSeconds,
        Recording recording,
        Replay replay,
        Analysis analysis) {

    /** The lap and stint channels the timing page is built from. */
    public static final List<String> ANALYSIS_CHANNELS = List.of("timing.analysis.laps", "timing.analysis.stints");

    /** Raw inbound lines kept for replay — the only test data this feed will ever have. */
    public record Recording(boolean enabled, String directory, String bucket,
                            int segmentMinutes, int maxLocalMegabytes) {
    }

    /** Local dev: serve a recording from an in-process fake server instead of dialling out. */
    public record Replay(String file, double speed) {
    }

    /**
     * timing.analysis: every lap and stint of every car, streamed into Postgres
     * rather than the state tree. Off until a practice session has been
     * recorded with it. maxLineBytes is a sanity cap on one streamed line,
     * which is counted and never held (a 24-hour snapshot is 45–78 MB).
     */
    public record Analysis(boolean enabled, long maxLineBytes) {
    }

    public boolean analysisEnabled() {
        return analysis != null && analysis.enabled();
    }

    /** The configured channels, plus the analysis channels when those are on. */
    public List<String> joinedChannels() {
        List<String> joined = new java.util.ArrayList<>();
        channels.forEach(c -> joined.add(c.trim()));
        if (analysisEnabled()) {
            ANALYSIS_CHANNELS.stream().filter(c -> !joined.contains(c)).forEach(joined::add);
        }
        return joined;
    }

    /** Streamed JSON lines are bounded by this instead of maxLineBytes. */
    public long maxStreamedBytes() {
        return analysis != null && analysis.maxLineBytes() > 0 ? analysis.maxLineBytes() : 512L * 1024 * 1024;
    }

    public boolean replaying() {
        return replay != null && replay.file() != null && !replay.file().isBlank();
    }

    /** Credentials may legitimately be blank (the protocol allows it); a host may not. */
    public boolean configured() {
        return replaying() || (host != null && !host.isBlank());
    }

    // A record's generated toString would print the password into any log line
    // or exception that mentions this object.
    @Override
    public String toString() {
        return "AlKamelV2Properties[host=" + host + ", port=" + port + ", username=" + username
                + ", password=***, replaying=" + replaying() + "]";
    }
}
