package com.pitpass.live;

import java.net.URI;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Where IMSA's telemetry app talks to, read from its own JavaScript bundle:
 * the AppSync Events HTTP endpoint and the public API key. Both are in the
 * bundle because the browser needs them; AppSync keys expire within a year,
 * so they are read again at every connect instead of being copied into
 * config.
 */
record AppSyncEndpoint(String httpHost, String realtimeUrl, String apiKey) {

    private static final Pattern ENDPOINT =
            Pattern.compile("https://([a-z0-9]+)\\.appsync-api\\.([a-z0-9-]+)\\.amazonaws\\.com/event");
    private static final Pattern API_KEY = Pattern.compile("\\bda2-[a-z0-9]{26}\\b");
    // Angular emits <script src="main-HASH.js" type="module">; older builds main.HASH.js.
    private static final Pattern MAIN_SCRIPT = Pattern.compile("src=\"([^\"]*main[.-][^\"]*\\.js)\"");

    /** The app's entry script, resolved against the page URL. */
    static Optional<URI> mainScript(URI page, String html) {
        Matcher m = MAIN_SCRIPT.matcher(html);
        return m.find() ? Optional.of(page.resolve(m.group(1))) : Optional.empty();
    }

    /** Both the endpoint and a key, or nothing. */
    static Optional<AppSyncEndpoint> fromBundle(String js) {
        Matcher endpoint = ENDPOINT.matcher(js);
        Matcher key = API_KEY.matcher(js);
        if (!endpoint.find() || !key.find()) {
            return Optional.empty();
        }
        String id = endpoint.group(1);
        String region = endpoint.group(2);
        return Optional.of(new AppSyncEndpoint(
                id + ".appsync-api." + region + ".amazonaws.com",
                "wss://" + id + ".appsync-realtime-api." + region + ".amazonaws.com/event/realtime",
                key.group()));
    }

    // Never print the key whole: it is public, but it still does not belong in logs.
    @Override
    public String toString() {
        return "AppSyncEndpoint[" + httpHost + ", key=" + apiKey.substring(0, Math.min(8, apiKey.length())) + "…]";
    }
}
