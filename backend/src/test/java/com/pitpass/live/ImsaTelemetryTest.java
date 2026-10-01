package com.pitpass.live;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

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
            assertEquals(40, d.cars().get(0).lapsCompleted());
            assertEquals("GTP", d.cars().get(0).className());
            assertEquals("04", d.cars().get(1).number(), "the number exactly as sent");
            assertEquals(Boolean.TRUE, d.cars().get(1).pitLane());
        }
    }

    @Test
    void numberClassAndLapsComeFromTheLoggerNotImsasScoringBlock() {
        // As seen live: scoring.lapNumber stuck at 6 and scoring.class from another series.
        TelemetryDecoder.Decoded d = decoder.decode(cars(
                "{\"car_id\":\"GTP-10\",\"lap_number\":51.0,\"energy_remaining\":17.3,\"pit_lane\":true,"
                        + "\"scoring\":{\"number\":\"10\",\"class\":\"ND2\",\"lapNumber\":6.0}}",
                car("911", 90, 0, false, "GTD PRO"),
                car("023", 80, 12, false, "GTD"),
                "{\"car_id\":\"XYZ-5\",\"lap_number\":0,\"energy_remaining\":50}"));
        var gtp = d.cars().get(0);
        assertEquals("10", gtp.number());
        assertEquals("GTP", gtp.className());
        assertEquals(51, gtp.lapsCompleted());
        var gtdPro = d.cars().get(1);
        assertEquals("911", gtdPro.number());
        assertEquals("GTD PRO", gtdPro.className(), "GDP is GTD Pro");
        assertNull(gtdPro.lapsCompleted(), "0 is a logger that sends no lap count, not lap 0");
        assertEquals("023", d.cars().get(2).number(), "leading zeros kept");
        assertNull(d.cars().get(3).className(), "an unknown prefix is no class rather than a guess");
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
        assertEquals(List.of(new LiveTelemetry.LapSample("7", 11, 88.0, false, "GTP")), feed(t, "7", 88.0, 11),
                "the first reading at 11 laps completed is the energy at the line after lap 11");
        assertTrue(feed(t, "7", 87.5, 11).isEmpty());
        assertEquals(List.of(new LiveTelemetry.LapSample("7", 14, 83.0, false, "GTP")), feed(t, "7", 83.0, 14),
                "missed crossings are not invented: only the lap just completed");
    }

    @Test
    void aLoggerWithNoLapCountCrossesOnAlKamelsCount() {
        LiveTelemetry t = new LiveTelemetry();
        var gtd = decoder.decode(cars(car("023", 60.0, 0, false, "GTD")));
        assertTrue(t.accept(gtd, 0, Map.of("023", 20)).isEmpty());
        assertTrue(t.accept(decoder.decode(cars(car("023", 59.0, 0, false, "GTD"))), 0, Map.of("023", 20)).isEmpty());
        assertEquals(List.of(new LiveTelemetry.LapSample("023", 21, 57.5, false, "GTD")),
                t.accept(decoder.decode(cars(car("023", 57.5, 0, false, "GTD"))), 0, Map.of("023", 21)));
        assertTrue(t.accept(decoder.decode(cars(car("023", 57.0, 0, false, "GTD"))), 0, Map.of()).isEmpty(),
                "no count from either side is no crossing");

        LiveTelemetry loose = new LiveTelemetry();
        loose.accept(decoder.decode(cars(car("4", 60.0, 0, false, "GTD"))), 0, Map.of("04", 5));
        assertEquals(1, loose.accept(decoder.decode(cars(car("4", 58.0, 0, false, "GTD"))), 0, Map.of("04", 6)).size(),
                "unambiguous without leading zeros");
        LiveTelemetry ambiguous = new LiveTelemetry();
        ambiguous.accept(decoder.decode(cars(car("4", 60.0, 0, false, "GTD"))), 0, Map.of("04", 5, "004", 5));
        assertTrue(ambiguous.accept(decoder.decode(cars(car("4", 58.0, 0, false, "GTD"))), 0, Map.of("04", 6, "004", 6))
                .isEmpty(), "never a guess between #04 and #004");

        LiveTelemetry own = new LiveTelemetry();
        own.accept(decoder.decode(cars(car("10", 40.0, 50, false))), 0, Map.of("10", 49));
        assertEquals(List.of(new LiveTelemetry.LapSample("10", 51, 38.0, false, "GTP")),
                own.accept(decoder.decode(cars(car("10", 38.0, 51, false))), 0, Map.of("10", 49)),
                "the logger's own count wins when it sends one");
    }

    @Test
    void averageUseAndLapsLeftFollowTheStintAndIgnoreRefills() {
        LiveTelemetry t = new LiveTelemetry();
        feed(t, "04", 100, 1);
        feed(t, "04", 96, 2);   // at the line after lap 2: 96
        feed(t, "04", 50, 3);   // after lap 3: 50 (46 used)
        feed(t, "04", 98, 4);   // after lap 4: 98 — a refill, a rise, not use
        feed(t, "04", 95, 5);   // after lap 5: 3 used
        feed(t, "04", 91, 6);   // after lap 6: 4 used
        LiveTelemetry.CarEnergy stint = t.energy("04", null, 4, 0, 15_000);
        assertEquals(91.0, stint.energyPct());
        assertEquals(3.5, stint.avgPerLapPct(), 1e-9, "only this stint's laps (from lap 4)");
        assertEquals(26.0, stint.lapsLeft(), 1e-9);

        assertEquals((46 + 3 + 4) / 3.0, t.energy("04", null, null, 0, 15_000).avgPerLapPct(), 1e-9,
                "the refill's rise is left out, not counted as negative use");
        assertNull(t.energy("04", null, 4, 16_000, 15_000).energyPct(), "stale after 15 s");
    }

    @Test
    void carNumbersMatchExactlyFirst() {
        LiveTelemetry t = new LiveTelemetry();
        feed(t, "04", 70, 1);
        feed(t, "4", 40, 1);
        assertEquals(70.0, t.energy("04", null, null, 0, 15_000).energyPct());
        assertEquals(40.0, t.energy("4", null, null, 0, 15_000).energyPct());
        LiveTelemetry only = new LiveTelemetry();
        feed(only, "023", 55, 1);
        assertEquals(55.0, only.energy("23", null, null, 0, 15_000).energyPct(), "unambiguous without leading zeros");
    }
}
