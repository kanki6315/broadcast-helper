package com.pitpass.live;

import java.io.Closeable;

/**
 * Where IMSA telemetry comes from: the live AppSync websocket, or a recording
 * of one. {@link #run} blocks for the life of the connection and always ends
 * by exception or {@link #close}; retrying belongs to {@link TelemetryRunner}.
 */
interface TelemetrySource extends Closeable {

    interface Listener {
        /** Subscribed; says to what (never the API key). */
        void connected(String description);

        /** Every inbound websocket message, exactly as received — this is what gets recorded. */
        void raw(long epochMs, String text);

        /** One event payload, and its channel when known. */
        void event(String channel, String payload);
    }

    void run(Listener listener) throws Exception;

    @Override
    void close();
}
