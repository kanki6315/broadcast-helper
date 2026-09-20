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
        Recording recording,
        Replay replay) {

    /** Raw inbound lines kept for replay — the only test data this feed will ever have. */
    public record Recording(boolean enabled, String directory, String bucket,
                            int segmentMinutes, int maxLocalMegabytes) {
    }

    /** Local dev: serve a recording from an in-process fake server instead of dialling out. */
    public record Replay(String file, double speed) {
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
