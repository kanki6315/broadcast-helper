package com.pitpass.live;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static com.pitpass.live.TelemetryFixtures.car;
import static com.pitpass.live.TelemetryFixtures.cars;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The pure parts of the IMSA telemetry adapter: payload decoding, the AppSync protocol, bundle scraping, lap sampling. */
class ImsaTelemetryTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final TelemetryDecoder decoder = new TelemetryDecoder(mapper);

    // ---- decoding ---------------------------------------------------------------------

    @Test
    void decodesEveryPlausibleWrappingOfTheSameCars() throws Exception {
        String json = cars(car("7", 62.5, 40, false), car("04", 80.0, 38, true));
        String b64 = TelemetryFixtures.base64(json);
        for (String event : List.of(
                "{\"data\":\"" + b64 + "\"}",                 // the app reads event.data as base64
                mapper.writeValueAsString(b64),               // a bare base64 JSON string
                json,                                         // plain JSON
                "{\"data\":" + json + "}")) {                // a data wrapper around plain JSON
            TelemetryDecoder.Decoded d = decoder.decode(event);
            assertEquals(2, d.cars().size(), event);
            assertEquals("7", d.cars().get(0).number());
            assertEquals(62.5, d.cars().get(0).energyPct());
            assertEquals(40, d.cars().get(0).lapNumber());
            assertEquals("04", d.cars().get(1).number(), "the number exactly as sent");
            assertEquals(Boolean.TRUE, d.cars().get(1).pitLane());
        }
    }

    @Test
    void decodesTheSessionClockAndIgnoresJunk() {
        var d = decoder.decode("{\"data\":\"" + TelemetryFixtures.base64(
                "{\"session_start_time\":1769000000,\"session_duration\":10800,\"seconds_remaining\":5400.4}") + "\"}");
        assertEquals(1_769_000_000L, d.session().startEpochSeconds());
        assertEquals(5400L, d.session().secondsRemaining());
        assertTrue(decoder.decode("not json at all ***").isEmpty());
        assertTrue(decoder.decode("{\"hello\":1}").isEmpty());
        assertTrue(decoder.decode(null).isEmpty());
    }

    // ---- AppSync protocol ----------------------------------------------------------------

    @Test
    void initAckSubscribeThenData() throws Exception {
        List<String> sent = new ArrayList<>();
        List<String> events = new ArrayList<>();
        AppSyncSession session = new AppSyncSession(mapper, "abc.appsync-api.us-east-1.amazonaws.com", "da2-key",
                List.of("/telemetry/message", "/telemetry/session"), new AppSyncSession.Output() {
            @Override
            public void send(String text) {
                sent.add(text);
            }

            @Override
            public void event(String channel, String payload) {
                events.add(channel + "|" + payload);
            }
        });
        session.start();
        assertEquals("{\"type\":\"connection_init\"}", sent.getFirst());

        session.receive("{\"type\":\"connection_ack\",\"connectionTimeoutMs\":120000}");
        assertTrue(session.acknowledged());
        assertEquals(120_000, session.timeoutMs());
        assertEquals(3, sent.size());
        JsonNode subscribe = mapper.readTree(sent.get(1));
        assertEquals("subscribe", subscribe.path("type").asText());
        assertEquals("/telemetry/message", subscribe.path("channel").asText());
        assertEquals("da2-key", subscribe.path("authorization").path("x-api-key").asText());
        assertEquals("abc.appsync-api.us-east-1.amazonaws.com", subscribe.path("authorization").path("host").asText());

        String id = subscribe.path("id").asText();
        session.receive("{\"type\":\"subscribe_success\",\"id\":\"" + id + "\"}");
        session.receive("{\"type\":\"ka\"}");
        session.receive(TelemetryFixtures.data(id, cars(car("7", 50, 3, false))));
        assertEquals(1, session.subscribed());
        assertEquals(1, events.size());
        assertTrue(events.getFirst().startsWith("/telemetry/message|{\"data\":"), events.getFirst());
        assertEquals(1, decoder.decode(events.getFirst().substring(events.getFirst().indexOf('|') + 1)).cars().size());
    }

    @Test
    void refusalsEndTheConnectionWithTheirReason() {
        AppSyncSession session = new AppSyncSession(mapper, "h", "k", List.of("/telemetry/message"), new AppSyncSession.Output() {
            @Override
            public void send(String text) {
            }

            @Override
            public void event(String channel, String payload) {
            }
        });
        var e = assertThrows(AppSyncSession.ProtocolError.class, () -> session.receive(
                "{\"type\":\"connection_error\",\"errors\":[{\"errorType\":\"UnauthorizedException\",\"message\":\"You are not authorized\"}]}"));
        assertTrue(e.getMessage().contains("UnauthorizedException You are not authorized"), e.getMessage());
    }

    @Test
    void theAuthSubprotocolIsBase64UrlJsonWithoutPadding() throws Exception {
        List<String> protocols = AppSyncSession.subprotocols(mapper, "abc.appsync-api.us-east-1.amazonaws.com", "da2-xyz");
        assertEquals("aws-appsync-event-ws", protocols.get(0));
        String encoded = protocols.get(1).substring("header-".length());
        assertFalse(encoded.contains("=") || encoded.contains("+") || encoded.contains("/"));
        JsonNode header = mapper.readTree(new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8));
        assertEquals("abc.appsync-api.us-east-1.amazonaws.com", header.path("host").asText());
        assertEquals("da2-xyz", header.path("x-api-key").asText());
    }

    // ---- bundle scraping -----------------------------------------------------------------

    @Test
    void findsTheEndpointAndKeyInTheAppBundle() {
        URI page = URI.create("https://d3aqeo5txo0gzi.cloudfront.net/");
        String html = "<html><head><link rel=\"stylesheet\" href=\"styles-X.css\"></head><body><app-root></app-root>"
                + "<script src=\"polyfills-AB12.js\" type=\"module\"></script><script src=\"main-CD34EF.js\" type=\"module\"></script></body></html>";
        assertEquals(URI.create("https://d3aqeo5txo0gzi.cloudfront.net/main-CD34EF.js"),
                AppSyncEndpoint.mainScript(page, html).orElseThrow());

        String bundle = "var a={API:{Events:{endpoint:\"https://wzidxebhlbgqpm7kt22wkx2pri.appsync-api.us-east-1.amazonaws.com/event\","
                + "region:\"us-east-1\",defaultAuthMode:\"apiKey\",apiKey:\"da2-abcdefghijklmnopqrstuvwxyz\"}}};";
        AppSyncEndpoint endpoint = AppSyncEndpoint.fromBundle(bundle).orElseThrow();
        assertEquals("wzidxebhlbgqpm7kt22wkx2pri.appsync-api.us-east-1.amazonaws.com", endpoint.httpHost());
        assertEquals("wss://wzidxebhlbgqpm7kt22wkx2pri.appsync-realtime-api.us-east-1.amazonaws.com/event/realtime",
                endpoint.realtimeUrl());
        assertEquals("da2-abcdefghijklmnopqrstuvwxyz", endpoint.apiKey());
        assertFalse(endpoint.toString().contains("abcdefghijklmnopqrstuvwxyz"), "the key is never logged whole");

        assertTrue(AppSyncEndpoint.fromBundle("no endpoint here da2-abcdefghijklmnopqrstuvwxyz").isEmpty());
    }

    // ---- lap sampling ------------------------------------------------------------------------

    private List<LiveTelemetry.LapSample> feed(LiveTelemetry t, String car, double energy, int lap) {
        return t.accept(decoder.decode(cars(car(car, energy, lap, false))), 0);
    }

    @Test
    void samplesEnergyOnceAtEachLapCrossing() {
        LiveTelemetry t = new LiveTelemetry();
        assertTrue(feed(t, "7", 90.0, 10).isEmpty(), "the first reading has nothing to cross from");
        assertTrue(feed(t, "7", 89.1, 10).isEmpty(), "readings within a lap are not stored");
        assertEquals(List.of(new LiveTelemetry.LapSample("7", 10, 88.0, false)), feed(t, "7", 88.0, 11),
                "the first reading on lap 11 is the energy at the line after lap 10");
        assertTrue(feed(t, "7", 87.5, 11).isEmpty());
        assertEquals(List.of(new LiveTelemetry.LapSample("7", 13, 83.0, false)), feed(t, "7", 83.0, 14),
                "missed crossings are not invented: only the lap just completed");
    }

    @Test
    void averageUseAndLapsLeftFollowTheStintAndIgnoreRefills() {
        LiveTelemetry t = new LiveTelemetry();
        feed(t, "04", 100, 1);
        feed(t, "04", 96, 2);   // at the line after lap 1: 96
        feed(t, "04", 50, 3);   // after lap 2: 50 (46 used)
        feed(t, "04", 98, 4);   // after lap 3: 98 — a refill, a rise, not use
        feed(t, "04", 95, 5);   // after lap 4: 3 used
        feed(t, "04", 91, 6);   // after lap 5: 4 used
        LiveTelemetry.CarEnergy stint = t.energy("04", 3, 0, 15_000);
        assertEquals(91.0, stint.energyPct());
        assertEquals(3.5, stint.avgPerLapPct(), 1e-9, "only this stint's laps (from lap 3)");
        assertEquals(26.0, stint.lapsLeft(), 1e-9);

        assertEquals((46 + 3 + 4) / 3.0, t.energy("04", null, 0, 15_000).avgPerLapPct(), 1e-9,
                "the refill's rise is left out, not counted as negative use");
        assertNull(t.energy("04", 3, 16_000, 15_000).energyPct(), "stale after 15 s");
    }

    @Test
    void carNumbersMatchExactlyFirst() {
        LiveTelemetry t = new LiveTelemetry();
        feed(t, "04", 70, 1);
        feed(t, "4", 40, 1);
        assertEquals(70.0, t.energy("04", null, 0, 15_000).energyPct());
        assertEquals(40.0, t.energy("4", null, 0, 15_000).energyPct());
        LiveTelemetry only = new LiveTelemetry();
        feed(only, "023", 55, 1);
        assertEquals(55.0, only.energy("23", null, 0, 15_000).energyPct(), "unambiguous without leading zeros");
    }
}
