package com.pitpass.imports;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The official-series standings flow end to end minus HTTP and the database:
 * a stubbed IRacingClient serves the trimmed real PESC 2020 payloads, and the
 * assertions cover what the orchestration adds on top of the parsers — the
 * voided round never being touched, chronological 1-based numbering, the
 * calendar gap a failed round fetch leaves, and the seriesId/seasonId pairing
 * check. buildOfficialStandings stops short of persistence for exactly this
 * test, so the service is constructed without a database or transactions.
 */
class ImportServiceOfficialStandingsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** The voided June Le Mans race — official_session false in the fixture. */
    private static final long VOIDED_SUBSESSION = 33053142L;
    /** Zandvoort, round 1 — served from the trimmed real payload. */
    private static final long OPENER_SUBSESSION = 32168491L;
    /** Barcelona, round 2 — the stub fails this fetch to prove the gap behavior. */
    private static final long FAILING_SUBSESSION = 32316399L;

    private static JsonNode fixture(String name) {
        try (InputStream in = ImportServiceOfficialStandingsTest.class
                .getResourceAsStream("/fixtures/iracing/" + name)) {
            assertNotNull(in, "missing fixture " + name);
            return MAPPER.readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Serves the fixtures the way the live client would: past_seasons and
     * season_results by path, the Zandvoort opener as its real trimmed result,
     * every other official round as a minimal synthetic result, and the
     * standings as pre-concatenated chunk rows. Records what was fetched so a
     * test can assert the voided round was never requested.
     */
    private static class StubClient extends IRacingClient {
        final List<Long> fetchedResults = new ArrayList<>();

        StubClient() {
            super("id", "secret", "user", "pw", "http://unused/token", "http://unused/data");
        }

        @Override
        public JsonNode get(String path, Map<String, String> params) {
            return switch (path) {
                // Another series answers with its own seasons, none of them 2812.
                case "/series/past_seasons" -> "409".equals(params.get("series_id"))
                        ? fixture("series-past-seasons-409.json")
                        : MAPPER.createObjectNode();
                case "/results/season_results" -> fixture("season-results-2812.json");
                case "/stats/season_driver_standings" ->
                        MAPPER.createObjectNode().set("chunk_info", MAPPER.createObjectNode());
                default -> throw new AssertionError("unexpected path " + path);
            };
        }

        @Override
        public JsonNode fetchResult(long subsessionId) {
            fetchedResults.add(subsessionId);
            if (subsessionId == FAILING_SUBSESSION) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "stubbed outage");
            }
            if (subsessionId == OPENER_SUBSESSION) {
                return fixture("subsession-official-pesc-2020.json");
            }
            // Any other official round: a minimal one-race result whose only
            // driver is Job (cust_id 119101), worth 50 for the win.
            var row = MAPPER.createObjectNode()
                    .put("cust_id", 119101).put("champ_points", 50);
            var sim = MAPPER.createObjectNode()
                    .put("simsession_type", 6).put("simsession_name", "FEATURE");
            sim.set("results", MAPPER.createArrayNode().add(row));
            var root = MAPPER.createObjectNode();
            root.set("track", MAPPER.createObjectNode().put("track_name", "Track " + subsessionId));
            root.set("session_results", MAPPER.createArrayNode().add(sim));
            return root;
        }

        @Override
        public ArrayNode fetchChunkedRows(JsonNode chunkInfo) {
            ArrayNode rows = MAPPER.createArrayNode();
            rows.addAll((ArrayNode) fixture("season-standings-chunk-2812-1.json"));
            rows.addAll((ArrayNode) fixture("season-standings-chunk-2812-2.json"));
            return rows;
        }
    }

    private static ImportService service(StubClient client) {
        return new ImportService(null, MAPPER, client, null, null, null, null, "python3", "unused", "unused");
    }

    @Test
    void buildsTheSeasonStandingsFromOfficialRoundsOnly() {
        StubClient client = new StubClient();
        List<StandingsImport> imports = service(client).buildOfficialStandings(409, 2812);

        // One car class, one standings table.
        assertEquals(1, imports.size());
        StandingsImport st = imports.get(0);

        // Name and year come from past_seasons — the name carries its year as a
        // suffix, where the league flow's leading-year heuristic would fail.
        assertEquals("Porsche TAG Heuer Esports Supercup - 2020 Season", st.name());
        assertEquals("2020", st.year());

        // The voided Le Mans was never even fetched, let alone numbered.
        assertFalse(client.fetchedResults.contains(VOIDED_SUBSESSION),
                "a voided race must not be touched by the standings walk");

        // 10 official rounds, minus the one whose fetch failed: nine rounds —
        // a failed round leaves a calendar gap, it does not sink the import.
        // Rounds are counted by distinct event name, because each contributes
        // one session per scoring sim-session: Zandvoort's real payload brings
        // three (qualifying, heat, feature), the eight synthetic rounds one each.
        assertEquals(9, st.sessions().stream()
                .map(StandingsImport.SessionRef::eventName).distinct().count());
        assertEquals(11, st.sessions().size());
        for (int i = 0; i < st.sessions().size(); i++) {
            assertEquals(i + 1, st.sessions().get(i).sessionIndex(), "indexes run 1..N with no hole");
        }

        // A round's sessions all carry its venue as their event name — that is
        // what the recap groups on to collapse them back into one column —
        // while each keeps its own session name.
        List<StandingsImport.SessionRef> zandvoort = st.sessions().subList(0, 3);
        assertTrue(zandvoort.stream().allMatch(
                        s -> "Circuit Park Zandvoort Grand Prix - 2009".equals(s.eventName())),
                "the round's sessions must share one event name");
        assertEquals(List.of("Qualifying", "Heat 1", "Feature"),
                zandvoort.stream().map(StandingsImport.SessionRef::sessionName).toList());

        // Standings totals are the endpoint's authoritative figures.
        assertEquals(6, st.rows().size());
        StandingsImport.Row champion = st.rows().get(0);
        assertEquals("119101", champion.key());
        assertEquals("Sebastian Job", champion.team());
        assertEquals(659.0, champion.totalPoints());

        // Job's Zandvoort is split 6 + 16 + 50 across the round's three
        // sessions — which still sums to the 72 iRacing scored him — and each
        // synthetic round pays its flat 50.
        assertEquals(11, champion.pointsBySession().size());
        assertEquals(List.of(6.0, 16.0, 50.0), champion.pointsBySession().subList(0, 3).stream()
                .map(StandingsImport.SessionPoints::totalPoints).toList());
        assertEquals(72.0, champion.pointsBySession().subList(0, 3).stream()
                .mapToDouble(StandingsImport.SessionPoints::totalPoints).sum());
        assertEquals(50.0, champion.pointsBySession().get(3).totalPoints());
    }

    @Test
    void refusesAMismatchedSeriesAndSeasonPairing() {
        // Season 2812 belongs to series 409; asking series 373 for it must not
        // import under the wrong series.
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service(new StubClient()).buildOfficialStandings(373, 2812));
        assertEquals(HttpStatus.NOT_FOUND, e.getStatusCode());
    }

    @Test
    void refusesASeasonWithNoStandingsRows() {
        StubClient empty = new StubClient() {
            @Override
            public ArrayNode fetchChunkedRows(JsonNode chunkInfo) {
                return MAPPER.createArrayNode();
            }
        };
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service(empty).buildOfficialStandings(409, 2812));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, e.getStatusCode());
    }
}
