package com.pitpass.live;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rows the classification is matched against, loaded from the real
 * schema: the bound event's entries, its season's car-number aliases and its
 * series' class aliases — and nobody else's. The feed side is a subclass of
 * the service holding a fixed tree; no socket is involved.
 */
@SpringBootTest
@Transactional
class LiveClassificationServiceTest {

    @Autowired JdbcClient db;
    @Autowired LiveTimingStore store;
    @Autowired AlKamelV2Properties props;
    @Autowired ObjectMapper mapper;

    private static final String FEED = """
            {"standings": {"byClass": {"active": {
              "GTP": {"class": "GTP", "standings": {
                "1": {"participant": "85", "position": 1, "status": "CLASSIFIED"},
                "2": {"participant": "007", "position": 2, "status": "CLASSIFIED"}}},
              "PRO": {"class": "PRO", "standings": {
                "1": {"participant": "500", "position": 1, "status": "CLASSIFIED"}}}
            }}}}
            """;

    /** A LIVE service whose feed is {@link #FEED}, bound to whatever the row says. */
    private LiveTimingService liveWith(String sessionJson) throws Exception {
        JsonNode session = mapper.readTree(sessionJson);
        return new LiveTimingService(props, store, mapper, null, null, LiveTimingService.Pacing.PRODUCTION) {
            @Override
            public JsonNode state(String path) {
                return "timing.session".equals(path) ? session : null;
            }
        };
    }

    private long event(long seasonId, String name) {
        return db.sql("INSERT INTO event (season_id, name, round_ordinal) VALUES (:s, :n, 1) RETURNING id")
                .param("s", seasonId).param("n", name).query(Long.class).single();
    }

    private void entry(long eventId, String number, String className, String team) {
        db.sql("INSERT INTO entry (event_id, car_number, class_name, team_name) VALUES (:e, :n, :c, :t)")
                .param("e", eventId).param("n", number).param("c", className).param("t", team).update();
    }

    @Test
    void matchesAgainstTheBoundEventsRowsOnly() throws Exception {
        String suffix = UUID.randomUUID().toString();
        long seriesId = db.sql("INSERT INTO series (name) VALUES (:n) RETURNING id")
                .param("n", "Live series " + suffix).query(Long.class).single();
        long seasonId = db.sql("INSERT INTO season (series_id, year) VALUES (:s, 2099) RETURNING id")
                .param("s", seriesId).query(Long.class).single();
        long bound = event(seasonId, "Bound " + suffix);
        entry(bound, "85", "GTP", "JDC-Miller MotorSports");
        entry(bound, "7", "GTP", "Porsche Penske Motorsport");
        db.sql("""
                INSERT INTO car_number_alias (season_id, class_name, car_number, canonical_number)
                VALUES (:s, 'GTP', '85', '5')
                """).param("s", seasonId).update();
        db.sql("INSERT INTO class_alias (series_id, alias, class_name) VALUES (:s, 'PRO', 'GTD PRO')")
                .param("s", seriesId).update();

        // Another event of the same season holds #500 — it must not match.
        long other = event(seasonId, "Other " + suffix);
        entry(other, "500", "GTD PRO", "Somebody Else");

        store.request(true, bound, "admin@example.test");
        var response = new LiveClassificationService(db, liveWith(FEED)).current();

        assertEquals(bound, response.eventId());
        assertEquals("Bound " + suffix, response.eventName());
        var result = response.classification();
        assertEquals(2, result.matched());
        assertEquals(3, result.total());

        var gtp = result.classes().get(0);
        assertEquals("5", gtp.cars().get(0).competitorKey(), "through the season's car_number_alias");
        assertEquals("JDC-Miller MotorSports", gtp.cars().get(0).teamName());
        assertEquals("7", gtp.cars().get(1).competitorKey(), "the feed's 007 is the entry's 7");

        assertEquals("GTD PRO", result.classes().get(1).className(), "named by the series' class_alias");
        assertEquals(List.of("500"), result.unmatched().stream().map(u -> u.carNumber()).toList());
    }

    /**
     * A drivers championship end to end through the real schema: the crew from
     * driver_assignment, the weekend's qualifying from result, the standings
     * rows from standings_row — and the guards around them.
     */
    @Test
    void resolvesADriversChampionshipAgainstTheRunningOrder() throws Exception {
        String suffix = UUID.randomUUID().toString();
        long seriesId = db.sql("INSERT INTO series (name) VALUES (:n) RETURNING id")
                .param("n", "Live champ series " + suffix).query(Long.class).single();
        long seasonId = db.sql("INSERT INTO season (series_id, year) VALUES (:s, 2099) RETURNING id")
                .param("s", seriesId).query(Long.class).single();
        long otherSeason = db.sql("INSERT INTO season (series_id, year) VALUES (:s, 2098) RETURNING id")
                .param("s", seriesId).query(Long.class).single();
        long bound = event(seasonId, "Bound " + suffix);
        entry(bound, "85", "GTP", "JDC-Miller MotorSports");
        entry(bound, "7", "GTP", "Porsche Penske Motorsport");
        long car7 = db.sql("SELECT id FROM entry WHERE event_id = :e AND car_number = '7'").param("e", bound)
                .query(Long.class).single();
        long car85 = db.sql("SELECT id FROM entry WHERE event_id = :e AND car_number = '85'").param("e", bound)
                .query(Long.class).single();
        for (String[] seat : new String[][] {{"Felipe", "Nasr " + suffix, "7"}, {"Tijmen", "Helm " + suffix, "85"}}) {
            long driver = db.sql("INSERT INTO driver (first_name, surname) VALUES (:f, :s) RETURNING id")
                    .param("f", seat[0]).param("s", seat[1]).query(Long.class).single();
            db.sql("INSERT INTO driver_assignment (entry_id, driver_id, seat_order) VALUES (:e, :d, 1)")
                    .param("e", seat[2].equals("7") ? car7 : car85).param("d", driver).update();
        }
        long qualifying = db.sql("""
                INSERT INTO race_session (event_id, session_type, name, ordinal, format_source)
                VALUES (:e, 'QUALIFYING', 'Qualifying', 1, (SELECT format_source FROM race_session LIMIT 1)) RETURNING id
                """).param("e", bound).query(Long.class).single();
        db.sql("INSERT INTO result (session_id, entry_id, position_overall, position_in_class) VALUES (:s, :a, 1, 1), (:s, :b, 2, 2)")
                .param("s", qualifying).param("a", car7).param("b", car85).update();

        long champ = championship(seasonId, "DRIVERS", "GTP", suffix);
        db.sql("""
                INSERT INTO standings_row (championship_id, position, competitor_key, competitor_name, total_points)
                VALUES (:c, 1, :nasr, NULL, 2000), (:c, 2, :helm, NULL, 1900), (:c, 3, 'Sat Out', NULL, 1800)
                """).param("c", champ).param("nasr", "Felipe Nasr " + suffix).param("helm", "Tijmen Helm " + suffix).update();

        store.request(true, bound, "admin@example.test");
        var service = new LiveClassificationService(db, liveWith(FEED));
        var response = service.championship(champ);

        assertEquals("DRIVERS", response.kind());
        assertTrue(response.qualifyingImported());
        var helm = response.rows().get(1);
        assertEquals(1, helm.live().position(), "#85 leads the feed");
        assertEquals("85", helm.live().carNumber());
        assertEquals(2, helm.qualifyingPosition());
        assertEquals(2, response.rows().get(0).live().position(), "the feed's 007 is Nasr's #7");
        assertEquals(1, response.rows().get(0).qualifyingPosition());
        assertEquals(null, response.rows().get(2).live());

        // A championship of another season is not scored against this event…
        long elsewhere = championship(otherSeason, "DRIVERS", "GTP", "x" + suffix);
        assertEquals(409, org.junit.jupiter.api.Assertions.assertThrows(
                org.springframework.web.server.ResponseStatusException.class,
                () -> service.championship(elsewhere)).getStatusCode().value());
        // …and one that does not exist is a 404.
        assertEquals(404, org.junit.jupiter.api.Assertions.assertThrows(
                org.springframework.web.server.ResponseStatusException.class,
                () -> service.championship(-1)).getStatusCode().value());
    }

    private long championship(long seasonId, String kind, String className, String suffix) {
        long group = db.sql("""
                INSERT INTO championship_group (season_id, family, kind, label, ordinal)
                VALUES (:s, 'Season', :k, :k, 1) RETURNING id
                """).param("s", seasonId).param("k", kind).query(Long.class).single();
        return db.sql("""
                INSERT INTO championship (season_id, name, title, class_name, group_id)
                VALUES (:s, :n, :n, :cls, :g) RETURNING id
                """).param("s", seasonId).param("n", className + " " + kind + " " + suffix)
                .param("cls", className).param("g", group).query(Long.class).single();
    }

    @Test
    void withNoEventBoundThereIsNothingToScore() throws Exception {
        // The local row may carry a binding from real use; rolled back with the test.
        db.sql("UPDATE live_timing SET event_id = NULL").update();
        var response = new LiveClassificationService(db, liveWith(FEED)).current();
        assertEquals(null, response.eventId());
        assertTrue(response.classification().classes().isEmpty());
    }
}
