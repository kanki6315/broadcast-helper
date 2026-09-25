package com.pitpass.drivers;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@Transactional
class DriverAdminControllerTest {

    @Autowired JdbcClient db;
    @Autowired DriverAdminController controller;
    @Autowired DriverController drivers;

    @Test
    void mergeRepointsEveryReferenceKeepsTheOldSpellingAndDeletesTheSource() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        long target = driver("Jaden", "Munoz " + suffix, "US", null);
        long source = driver("Jaden", "Munoz2 " + suffix, null, "Miami");
        long seasonId = season(suffix);
        long eventId = db.sql("INSERT INTO event (season_id, name) VALUES (:s, 'Round') RETURNING id")
                .param("s", seasonId).query(Long.class).single();
        long entryId = entry(eventId, "5");
        long otherEntry = entry(eventId, "6");
        seat(entryId, source, 1);
        seat(otherEntry, target, 1);
        long sessionId = db.sql("""
                        INSERT INTO race_session (event_id, session_type, name) VALUES (:e, 'RACE', 'Race')
                        RETURNING id
                        """)
                .param("e", eventId).query(Long.class).single();
        db.sql("""
                        INSERT INTO grid_position (session_id, entry_id, position_overall,
                                                   qualifying_driver_id, starting_driver_id)
                        VALUES (:s, :e, 1, :d, :d)
                        """)
                .param("s", sessionId).param("e", entryId).param("d", source).update();
        long teamId = db.sql("INSERT INTO season_team (season_id, name) VALUES (:s, 'Williams') RETURNING id")
                .param("s", seasonId).query(Long.class).single();
        db.sql("""
                        INSERT INTO season_driver_team_assignment (season_id, driver_id, team_id, effective_from_round)
                        VALUES (:s, :d, :t, 1)
                        """)
                .param("s", seasonId).param("d", source).param("t", teamId).update();
        db.sql("""
                        INSERT INTO driver_photo (driver_id, content_type, object_key)
                        VALUES (:d, 'image/png', 'driver-photos/source.png')
                        """)
                .param("d", source).update();

        DriverAdminController.MergeResult result = controller.merge(target,
                new DriverAdminController.MergeRequest(source));

        assertEquals(1, result.seatsMoved());
        assertEquals(0, result.seatsDropped());
        assertEquals(0, count("SELECT count(*) FROM driver WHERE id = " + source));
        assertEquals(target, single("SELECT driver_id FROM driver_assignment WHERE entry_id = " + entryId));
        assertEquals(target, single("SELECT qualifying_driver_id FROM grid_position WHERE session_id = " + sessionId));
        assertEquals(target, single("SELECT starting_driver_id FROM grid_position WHERE session_id = " + sessionId));
        assertEquals(target, single("SELECT driver_id FROM season_driver_team_assignment WHERE team_id = " + teamId));
        assertEquals("driver-photos/source.png", db.sql("SELECT object_key FROM driver_photo WHERE driver_id = :d")
                .param("d", target).query(String.class).single());
        // The target's own bio wins; a gap is filled from the source.
        assertEquals("US", db.sql("SELECT country FROM driver WHERE id = :d")
                .param("d", target).query(String.class).single());
        assertEquals("Miami", db.sql("SELECT hometown FROM driver WHERE id = :d")
                .param("d", target).query(String.class).single());
        assertEquals(List.of("Jaden Munoz2 " + suffix), db.sql("SELECT alias FROM driver_alias WHERE driver_id = :d")
                .param("d", target).query(String.class).list());
        // Search finds the survivor by the retired spelling.
        assertTrue(drivers.search("munoz2 " + suffix, 12).stream().anyMatch(h -> h.id() == target));
    }

    @Test
    void bothDriversOnOneEntryKeepsOnlyTheTargetsSeat() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        long target = driver("Carlos", "Fenollosa " + suffix, null, null);
        long source = driver("Carlos", "Fenolosa " + suffix, null, null);
        long eventId = db.sql("INSERT INTO event (season_id, name) VALUES (:s, 'Round') RETURNING id")
                .param("s", season(suffix)).query(Long.class).single();
        long entryId = entry(eventId, "92");
        seat(entryId, target, 1);
        seat(entryId, source, 2);

        DriverAdminController.MergeResult result = controller.merge(target,
                new DriverAdminController.MergeRequest(source));

        assertEquals(0, result.seatsMoved());
        assertEquals(1, result.seatsDropped());
        assertEquals(List.of(target), db.sql("SELECT driver_id FROM driver_assignment WHERE entry_id = :e")
                .param("e", entryId).query(Long.class).list());
    }

    @Test
    void privateerTeamsInTheSameSeasonFoldIntoTheTargets() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        long target = driver("Solo", "Racer " + suffix, null, null);
        long source = driver("Solo", "Racer1 " + suffix, null, null);
        long seasonId = season(suffix);
        long targetTeam = privateer(seasonId, target);
        long sourceTeam = privateer(seasonId, source);
        long eventId = db.sql("INSERT INTO event (season_id, name) VALUES (:s, 'Round') RETURNING id")
                .param("s", seasonId).query(Long.class).single();
        long entryId = entry(eventId, "12");
        db.sql("UPDATE entry SET season_team_id = :t WHERE id = :e")
                .param("t", sourceTeam).param("e", entryId).update();

        controller.merge(target, new DriverAdminController.MergeRequest(source));

        assertEquals(0, count("SELECT count(*) FROM season_team WHERE id = " + sourceTeam));
        assertEquals(targetTeam, single("SELECT season_team_id FROM entry WHERE id = " + entryId));
    }

    @Test
    void aSpellingThatOnlyDiffersBySpacingNeedsNoAlias() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        long target = driver("Carlos", "Fenollosa " + suffix, null, null);
        long source = driver("Carlos ", " Fenollosa " + suffix, null, null);

        controller.merge(target, new DriverAdminController.MergeRequest(source));

        assertEquals(0, count("SELECT count(*) FROM driver_alias WHERE driver_id = " + target));
    }

    @Test
    void aliasesOfTheSourceFollowItIntoTheTarget() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        long a = driver("First", "Spelling " + suffix, null, null);
        long b = driver("Second", "Spelling " + suffix, null, null);
        long c = driver("Third", "Spelling " + suffix, null, null);
        controller.merge(b, new DriverAdminController.MergeRequest(c));

        DriverAdminController.MergeResult result = controller.merge(a, new DriverAdminController.MergeRequest(b));

        assertEquals(1, result.aliasesMoved());
        assertEquals(List.of("Second Spelling " + suffix, "Third Spelling " + suffix),
                db.sql("SELECT alias FROM driver_alias WHERE driver_id = :d ORDER BY alias")
                        .param("d", a).query(String.class).list());
    }

    @Test
    void consolidatingToANameAnotherDriverHasMergesIntoThatDriver() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        long keeper = driver("Jaden", "Munoz " + suffix, null, null);
        long variant = driver("Jaden", "Munoz2 " + suffix, null, null);

        assertEquals(keeper, controller.consolidate(variant, "jaden  munoz " + suffix));

        assertEquals(0, count("SELECT count(*) FROM driver WHERE id = " + variant));
        assertEquals(List.of("Jaden Munoz2 " + suffix), db.sql("SELECT alias FROM driver_alias WHERE driver_id = :d")
                .param("d", keeper).query(String.class).list());
    }

    @Test
    void consolidatingBackToARetiredSpellingRenamesAndSwapsTheAlias() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        long d = driver("Alexander", "Spetz " + suffix, null, null);
        controller.consolidate(d, "Alxander Spetz " + suffix);
        assertEquals(List.of("Alexander Spetz " + suffix), db.sql("SELECT alias FROM driver_alias WHERE driver_id = :d")
                .param("d", d).query(String.class).list());

        assertEquals(d, controller.consolidate(d, "Alexander Spetz " + suffix));

        assertEquals("Alexander Spetz " + suffix, db.sql("SELECT first_name || ' ' || surname FROM driver WHERE id = :d")
                .param("d", d).query(String.class).single());
        assertEquals(List.of("Alxander Spetz " + suffix), db.sql("SELECT alias FROM driver_alias WHERE driver_id = :d")
                .param("d", d).query(String.class).list());
    }

    @Test
    void mergeIntoItselfIsRejected() {
        long d = driver("Self", "Merge " + UUID.randomUUID(), null, null);
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> controller.merge(d, new DriverAdminController.MergeRequest(d)));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, e.getStatusCode());
    }

    @Test
    void anUnknownDriverIs404() {
        long d = driver("Known", "Driver " + UUID.randomUUID(), null, null);
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> controller.merge(d, new DriverAdminController.MergeRequest(-1)));
        assertEquals(HttpStatus.NOT_FOUND, e.getStatusCode());
    }

    private long driver(String first, String surname, String country, String hometown) {
        return db.sql("""
                        INSERT INTO driver (first_name, surname, country, hometown)
                        VALUES (:f, :s, :c, :h) RETURNING id
                        """)
                .param("f", first).param("s", surname).param("c", country).param("h", hometown)
                .query(Long.class).single();
    }

    private long season(String suffix) {
        long seriesId = db.sql("INSERT INTO series (name) VALUES (:n) RETURNING id")
                .param("n", "Driver merge " + suffix).query(Long.class).single();
        return db.sql("INSERT INTO season (series_id, year) VALUES (:s, 2099) RETURNING id")
                .param("s", seriesId).query(Long.class).single();
    }

    private long entry(long eventId, String number) {
        return db.sql("""
                        INSERT INTO entry (event_id, car_number, class_name, team_name)
                        VALUES (:e, :n, 'GTP', 'Team') RETURNING id
                        """)
                .param("e", eventId).param("n", number).query(Long.class).single();
    }

    private void seat(long entryId, long driverId, int seat) {
        db.sql("INSERT INTO driver_assignment (entry_id, driver_id, seat_order) VALUES (:e, :d, :s)")
                .param("e", entryId).param("d", driverId).param("s", seat).update();
    }

    private long privateer(long seasonId, long driverId) {
        return db.sql("""
                        INSERT INTO season_team (season_id, name, privateer_driver_id)
                        VALUES (:s, 'Privateer', :d) RETURNING id
                        """)
                .param("s", seasonId).param("d", driverId).query(Long.class).single();
    }

    private int count(String sql) {
        return db.sql(sql).query(Integer.class).single();
    }

    private long single(String sql) {
        return db.sql(sql).query(Long.class).single();
    }
}
