package com.pitpass.imports.artifact;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.pitpass.drivers.DriverAdminController;
import com.pitpass.imports.ImportFormat;
import com.pitpass.imports.ImportService;
import com.pitpass.imports.artifact.ArtifactCorrectionService.CarPlan;
import com.pitpass.imports.artifact.ArtifactCorrectionService.CorrectionRequest;
import com.pitpass.imports.artifact.ArtifactCorrectionService.Plan;
import com.pitpass.teams.TeamAdminController;
import com.pitpass.teams.TeamAssignmentService;
import com.pitpass.teams.TeamResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The correction end to end on real data: IMSA Esports 2025 round 3 at Long
 * Beach, imported from its iRacing hosted-session file, then corrected from
 * the site's published classification and standings (fixtures captured from
 * artifactracing.com). The iRacing file has Maniti Racing as #19 where the
 * site has #17, a "Team iRacing DT2" car the site doesn't classify, and
 * iRacing display names for most teams.
 */
@SpringBootTest
@Transactional
class ArtifactCorrectionServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String LEAGUE = "8675521b-e198-4316-b20c-114c0afe050a";
    private static final String LONG_BEACH = "c05a0af4-0d50-4bcd-8e68-bb8a65d70898";

    @Autowired JdbcClient db;
    @Autowired ImportService imports;
    @Autowired DriverAdminController drivers;
    @Autowired TeamResolver teams;
    @Autowired TeamAssignmentService teamAssignments;
    @Autowired TeamAdminController teamAdmin;
    @Autowired PlatformTransactionManager transactions;

    ArtifactCorrectionService service;
    long seasonId;
    long eventId;

    @BeforeEach
    void importLongBeachFromIRacing() throws IOException {
        service = new ArtifactCorrectionService(db, new FixtureClient(), imports, drivers, teams,
                teamAssignments, teamAdmin, transactions);
        byte[] file;
        try (InputStream in = getClass().getResourceAsStream("/fixtures/iracing/subsession-longbeach-team-2025.json")) {
            file = in.readAllBytes();
        }
        List<ImportService.BatchSummary> batches = imports.stage("subsession-longbeach.json", file, ImportFormat.IRACING_JSON);
        String series = "Artifact correction " + UUID.randomUUID();
        Long seriesId = null;
        Long event = null;
        for (ImportService.BatchSummary b : batches) {
            imports.commit(b.id(), new ImportService.ImportTarget(seriesId, seriesId == null ? series : null, event,
                    null, null, null, null, null, null, null, null, Map.of(), null, true, null, null, null));
            if (seriesId == null) {
                seriesId = db.sql("SELECT id FROM series WHERE name = :n").param("n", series).query(Long.class).single();
                event = db.sql("""
                                SELECT ev.id FROM event ev JOIN season s ON s.id = ev.season_id WHERE s.series_id = :s
                                """)
                        .param("s", seriesId).query(Long.class).single();
            }
        }
        eventId = event;
        seasonId = db.sql("SELECT season_id FROM event WHERE id = :e").param("e", eventId).query(Long.class).single();
    }

    @Test
    void planPairsEveryCarByItsDriversAndStopsOnTheCarTheSiteDoesNotClassify() {
        Plan plan = service.plan(request(null, null, null, null));

        assertEquals(1, plan.rounds().size());
        var round = plan.rounds().get(0);
        assertEquals(eventId, round.event().id());
        assertEquals("DRIVERS", round.eventMatch());
        assertTrue(round.cars().stream().allMatch(c -> "EXACT_DRIVERS".equals(c.match())));
        // Maniti raced as #19 in iRacing; the site's #17 is the same crew.
        CarPlan maniti = car(plan, "17");
        assertEquals("19", maniti.entryCarNumber());
        assertTrue(maniti.changes().stream().anyMatch(c -> "car number".equals(c.field())
                && "19".equals(c.from()) && "17".equals(c.to())));
        // A 1 ms rounding difference in the fastest lap is not a change.
        assertTrue(car(plan, "91").changes().stream().noneMatch(c -> "fastest lap".equals(c.field())));
        assertTrue(car(plan, "91").changes().stream().anyMatch(c -> "team".equals(c.field())
                && "Porsche Coanda $91".equals(c.from()) && "Porsche Coanda Esports Racing Team".equals(c.to())));

        var dt2 = round.unpairedEntries().get(0);
        assertEquals("21", dt2.carNumber());
        assertTrue(dt2.mustDrop());
        assertFalse(plan.ready());
        assertTrue(plan.blocking().get(0).contains("#21 Team iRacing DT2"));
        assertEquals("GTP", plan.classes().get(0).pitPassClass());
    }

    @Test
    void applyCorrectsTheRoundLoadsStandingsAndIsIdempotent() {
        long dt2 = service.plan(request(null, null, null, null)).rounds().get(0).unpairedEntries().get(0).entryId();

        var result = service.apply(request(null, List.of(dt2), null, Map.of("GTD", "GTD")));

        assertEquals(1, result.roundsCorrected());
        assertEquals(20, result.resultsWritten());
        assertEquals(1, result.entriesRenumbered());
        assertEquals(1, result.entriesDropped());
        assertEquals(2, result.standingsChampionships());
        assertEquals(0, count("SELECT count(*) FROM entry WHERE event_id = %d AND car_number IN ('19', '21')"));
        assertEquals("Maniti Racing", db.sql("""
                        SELECT team_name FROM entry WHERE event_id = :e AND car_number = '17'
                        """).param("e", eventId).query(String.class).single());
        assertEquals("Porsche Coanda Esports Racing Team", db.sql("""
                        SELECT team_name FROM entry WHERE event_id = :e AND car_number = '91'
                        """).param("e", eventId).query(String.class).single());
        // The event now answers to the site's round, and knows its number.
        assertEquals(ArtifactCorrectionService.SOURCE_PREFIX + LONG_BEACH, db.sql(
                "SELECT source_ref FROM event WHERE id = :e").param("e", eventId).query(String.class).single());
        assertEquals(3, db.sql("SELECT source_round FROM event WHERE id = :e")
                .param("e", eventId).query(Integer.class).single());

        // GTP standings: #33 BMW M Team Redline won the title on 1373.
        long gtp = db.sql("SELECT id FROM championship WHERE season_id = :s AND class_name = 'GTP'")
                .param("s", seasonId).query(Long.class).single();
        assertEquals(1373.0, db.sql("""
                        SELECT total_points FROM standings_row WHERE championship_id = :c AND competitor_key = '33'
                        """).param("c", gtp).query(Double.class).single());
        // Five rounds of qualifying + race; GTP sat out VIR (round 4).
        assertEquals(10, count("SELECT count(*) FROM championship_session WHERE championship_id = " + gtp));
        assertEquals("did_not_race", db.sql("""
                        SELECT ssp.status FROM standings_session_points ssp
                                 JOIN standings_row sr ON sr.id = ssp.standings_row_id
                        WHERE sr.championship_id = :c AND sr.competitor_key = '33' AND ssp.session_index = 8
                        """).param("c", gtp).query(String.class).single());

        // Run again: nothing left to change, nothing blocking.
        Plan again = service.plan(request(null, null, null, Map.of("GTD", "GTD")));
        assertTrue(again.ready(), String.join(" ", again.blocking()));
        assertEquals("SOURCE", again.rounds().get(0).eventMatch());
        assertTrue(again.rounds().get(0).cars().stream().allMatch(c -> c.changes().isEmpty()),
                () -> again.rounds().get(0).cars().stream().filter(c -> !c.changes().isEmpty())
                        .map(c -> c.carNumber() + " " + c.changes()).toList().toString());
    }

    @Test
    void aNearMissCrewNeedsConfirmingAndCanTakeTheSitesSpelling() {
        // Pit Pass spells both of #90's drivers differently from the site.
        long simon = driverOn("90", "Tamas Simon");
        long sivi = driverOn("90", "Daniel Sivi-Szabo");
        rename(simon, "Tamás", "Simon");
        rename(sivi, "Daniel", "Sivi Szabo");

        Plan plan = service.plan(request(null, null, null, null));
        CarPlan car90 = car(plan, "90");
        assertEquals("CLOSE_DRIVERS", car90.match());
        assertTrue(car90.needsConfirmation());
        assertTrue(plan.blocking().stream().anyMatch(b -> b.contains("confirm #90")));
        assertTrue(car90.lineup().stream().allMatch(l -> "CLOSE".equals(l.match())));

        long dt2 = plan.rounds().get(0).unpairedEntries().get(0).entryId();
        CorrectionRequest confirmed = new CorrectionRequest(LEAGUE, seasonId, null,
                Map.of(car90.siteResultId(), car90.entryId()),
                List.of(new ArtifactCorrectionService.Consolidation(simon, "Tamas Simon")),
                List.of(dt2), Map.of("GTD", "GTD"), false, null);
        Plan ready = service.plan(confirmed);
        assertEquals("CONFIRMED", car(ready, "90").match());
        assertTrue(ready.ready(), String.join(" ", ready.blocking()));

        var result = service.apply(confirmed);

        assertEquals(1, result.driversConsolidated());
        assertEquals("Tamas Simon", db.sql("SELECT first_name || ' ' || surname FROM driver WHERE id = :d")
                .param("d", simon).query(String.class).single());
        assertEquals(List.of("Tamás Simon"), db.sql("SELECT alias FROM driver_alias WHERE driver_id = :d")
                .param("d", simon).query(String.class).list());
        // Standings were switched off for this run.
        assertEquals(0, count("SELECT count(*) FROM championship WHERE season_id = " + seasonId));
    }

    @Test
    void aConsolidationTheReviewDidNotOfferIsRefused() {
        long anyone = driverOn("91", "Charlie Collins");
        Plan plan = service.plan(new CorrectionRequest(LEAGUE, seasonId, null, null,
                List.of(new ArtifactCorrectionService.Consolidation(anyone, "Somebody Else")), null, null, null, null));
        assertTrue(plan.blocking().stream().anyMatch(b -> b.contains("not a near-miss spelling")));
    }

    @Test
    void aSiteRowCanBeLeftOutOrPointedAtAnEntryByHand() {
        Plan plan = service.plan(request(null, null, null, null));
        CarPlan car91 = car(plan, "91");
        long dt2 = plan.rounds().get(0).unpairedEntries().get(0).entryId();

        // Leaving #91 out leaves its entry unpaired with a race result: that blocks.
        java.util.Map<String, Long> skip = new java.util.HashMap<>();
        skip.put(car91.siteResultId(), null);
        Plan skipped = service.plan(request(skip, List.of(dt2), null, null));
        assertEquals("SKIPPED", car(skipped, "91").match());
        assertTrue(skipped.blocking().stream().anyMatch(b -> b.contains("#91")));

        // Pointing the site's #91 at the DT2 entry pairs it by hand instead.
        Plan manual = service.plan(request(Map.of(car91.siteResultId(), dt2), null, null, null));
        assertEquals("CONFIRMED", car(manual, "91").match());
        assertEquals(dt2, car(manual, "91").entryId());
    }

    @Test
    void anUntickedTeamMergeLeavesTheIRacingTeamAlone() {
        // Two iRacing-name teams used by nothing but this round's cars (the
        // shared local database may already know the real spellings).
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        long kept = teams.resolveOrCreate("Porsche Coanda $91 " + suffix);
        long merged = teams.resolveOrCreate("Team Redline 34 " + suffix);
        db.sql("UPDATE entry SET team_name = :n, team_id = :t WHERE event_id = :e AND car_number = '91'")
                .param("n", "Porsche Coanda $91 " + suffix).param("t", kept).param("e", eventId).update();
        db.sql("UPDATE entry SET team_name = :n, team_id = :t WHERE event_id = :e AND car_number = '34'")
                .param("n", "Team Redline 34 " + suffix).param("t", merged).param("e", eventId).update();

        Plan plan = service.plan(request(null, null, null, null));
        var fold = plan.teamFolds().stream().filter(f -> f.fromTeamId() == kept).findFirst().orElseThrow();
        assertTrue(fold.folding());
        assertEquals("Porsche Coanda Esports Racing Team", fold.toName());
        assertTrue(plan.teamFolds().stream().anyMatch(f -> f.fromTeamId() == merged));
        long dt2 = plan.rounds().get(0).unpairedEntries().get(0).entryId();

        var result = service.apply(new CorrectionRequest(LEAGUE, seasonId, null, null, null, List.of(dt2),
                Map.of("GTD", "GTD"), false, List.of(fold.fromTeamId())));

        assertEquals(plan.teamFolds().size() - 1, result.teamsFolded());
        assertEquals(1, count("SELECT count(*) FROM team WHERE id = " + kept));
        assertEquals(0, count("SELECT count(*) FROM team WHERE id = " + merged));
        // The merged spelling now resolves to the registered team.
        assertEquals(teams.resolveOrCreate("Team Redline"), teams.resolveOrCreate("Team Redline 34 " + suffix));
    }

    @Test
    void applyRefusesWhileAnythingBlocks() {
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service.apply(request(null, null, null, null)));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, e.getStatusCode());
        assertEquals("19", db.sql("SELECT car_number FROM entry WHERE event_id = :e AND team_name = 'Maniti Racing'")
                .param("e", eventId).query(String.class).single());
    }

    @Test
    void anUnknownLeagueIs404() {
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service.plan(new CorrectionRequest("00000000-0000-0000-0000-000000000000", seasonId,
                        null, null, null, null, null, null, null)));
        assertEquals(HttpStatus.NOT_FOUND, e.getStatusCode());
    }

    @Test
    void sameDayRoundsOrderBySourceRound() {
        long other = db.sql("""
                        INSERT INTO event (season_id, name, event_date) SELECT season_id, 'VIR', event_date
                        FROM event WHERE id = :e RETURNING id
                        """).param("e", eventId).query(Long.class).single();
        db.sql("UPDATE event SET source_round = 4 WHERE id = :id").param("id", eventId).update();
        db.sql("UPDATE event SET source_round = 3 WHERE id = :id").param("id", other).update();

        imports.renumberSeasonRounds(seasonId);

        assertEquals(1, db.sql("SELECT round_ordinal FROM event WHERE id = :id")
                .param("id", other).query(Integer.class).single());
        assertEquals(2, db.sql("SELECT round_ordinal FROM event WHERE id = :id")
                .param("id", eventId).query(Integer.class).single());
    }

    @Test
    void siteGapsBecomeIRacingShapedGaps() {
        assertEquals("-", ArtifactCorrectionService.gapFirst("-00.000", true));
        assertEquals("+64.911", ArtifactCorrectionService.gapFirst("-1:04.911", false));
        assertEquals("+6.285", ArtifactCorrectionService.gapFirst("-06.285", false));
        assertEquals("16 Laps", ArtifactCorrectionService.gapFirst("-16 L", false));
        assertEquals("1 Lap", ArtifactCorrectionService.gapFirst("-1 L", false));
        assertNull(ArtifactCorrectionService.gapFirst("-", false));
        assertEquals("+43.493", ArtifactCorrectionService.gapPrevious("-1:04.911", "-21.418"));
        assertEquals("1 Lap", ArtifactCorrectionService.gapPrevious("-2 L", "-1 L"));
        assertEquals("1 Lap", ArtifactCorrectionService.gapPrevious("-1 L", "-1:04.911"));
        assertNull(ArtifactCorrectionService.gapPrevious("-", "-1 L"));
        assertTrue(ArtifactCorrectionService.sameGap("+64.912", "+64.911"));
        assertFalse(ArtifactCorrectionService.sameGap("+64.912", "+64.900"));
    }

    @Test
    void nearMissNames() {
        assertTrue(ArtifactCorrectionService.close("Jaden Munoz2", "Jaden Muñoz"));
        assertTrue(ArtifactCorrectionService.close("Alxander Spetz", "Alexander Spetz"));
        assertTrue(ArtifactCorrectionService.close("Daniel Sivi-Szabo", "Daniel Sivi Szabo"));
        assertFalse(ArtifactCorrectionService.close("Carlos  Fenollosa", "carlos fenollosa")); // that's exact
        assertFalse(ArtifactCorrectionService.close("Max Li", "Max Lu"));                     // too short to judge
        assertFalse(ArtifactCorrectionService.close("Marcus Hamilton", "Lewis Hamilton"));
    }

    /* ------------------------------------------------------------------ */

    private CorrectionRequest request(Map<String, Long> pairings, List<Long> drops,
                                      List<ArtifactCorrectionService.Consolidation> consolidations,
                                      Map<String, String> classes) {
        return new CorrectionRequest(LEAGUE, seasonId, null, pairings, consolidations, drops, classes, null, null);
    }

    private static CarPlan car(Plan plan, String number) {
        return plan.rounds().get(0).cars().stream().filter(c -> number.equals(c.carNumber())).findFirst().orElseThrow();
    }

    private long driverOn(String carNumber, String name) {
        return db.sql("""
                        SELECT d.id FROM driver d
                                 JOIN driver_assignment da ON da.driver_id = d.id
                                 JOIN entry en ON en.id = da.entry_id
                        WHERE en.event_id = :e AND en.car_number = :n
                          AND lower(regexp_replace(d.first_name || ' ' || d.surname, '\\s+', ' ', 'g')) = lower(:name)
                        """)
                .param("e", eventId).param("n", carNumber).param("name", name)
                .query(Long.class).single();
    }

    private void rename(long driverId, String first, String surname) {
        db.sql("UPDATE driver SET first_name = :f, surname = :s WHERE id = :id")
                .param("f", first).param("s", surname).param("id", driverId).update();
    }

    private int count(String sql) {
        return db.sql(sql.formatted(eventId)).query(Integer.class).single();
    }

    /** artifactracing.com as captured, the season narrowed to the Long Beach round. */
    private static class FixtureClient extends ArtifactClient {
        FixtureClient() {
            super("http://unused");
        }

        @Override
        public JsonNode get(String path) {
            if (path.equals("/api/leagues?includeInactive=true")) {
                return fixture("leagues.json");
            }
            if (path.equals("/api/leagues/" + LEAGUE + "/events")) {
                ArrayNode events = MAPPER.createArrayNode();
                fixture("events-2025.json").forEach(e -> {
                    if (LONG_BEACH.equals(e.path("id").asText())) {
                        events.add(e);
                    }
                });
                return events;
            }
            if (path.equals("/api/leagues/" + LEAGUE + "/standings")) {
                return fixture("standings-2025.json");
            }
            if (path.startsWith("/api/events/") && path.endsWith("/results")) {
                return fixture("results-" + path.split("/")[3] + ".json");
            }
            throw new AssertionError("unexpected path " + path);
        }

        private static JsonNode fixture(String name) {
            try (InputStream in = ArtifactCorrectionServiceTest.class
                    .getResourceAsStream("/fixtures/artifact/" + name)) {
                return MAPPER.readTree(in);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
