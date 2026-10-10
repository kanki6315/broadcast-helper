package com.pitpass.browse;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The recap marks a race line whose finish the organiser's points contradict
 * — Carrera Cup Asia's results sheets predate decisions its points sheet
 * reflects (Fuji's disqualification, Bangsaen's Masters reshuffle). Guests
 * have no standings row and never score, so finishing behind one is not a
 * mismatch. Other series score on more than finishing order and get no marks.
 */
@SpringBootTest
@Transactional
class RecapPointsNoteTest {

    @Autowired JdbcClient db;
    @Autowired SeasonViewController seasonView;

    /** One Am race: guest #99 wins; #61 2nd on 17 points, #16 3rd on 25
     *  (a penalty #61's line doesn't show), #7 4th but disqualified in the
     *  points, #22 5th on 14. Returns the championship id. */
    private long race(String seriesName) {
        String suffix = UUID.randomUUID().toString();
        long seriesId = db.sql("INSERT INTO series (name) VALUES (:n) RETURNING id")
                .param("n", seriesName + " " + suffix).query(Long.class).single();
        long seasonId = db.sql("INSERT INTO season (series_id, year) VALUES (:s, 2099) RETURNING id")
                .param("s", seriesId).query(Long.class).single();
        long groupId = db.sql("""
                        INSERT INTO championship_group (season_id, family, kind, label, ordinal)
                        VALUES (:s, 'Season', 'DRIVERS', 'Drivers', 1) RETURNING id
                        """)
                .param("s", seasonId).query(Long.class).single();
        long champId = db.sql("""
                        INSERT INTO championship (season_id, name, title, class_name, group_id)
                        VALUES (:s, :n, :n, 'AM', :g) RETURNING id
                        """)
                .param("s", seasonId).param("n", "Am " + suffix).param("g", groupId)
                .query(Long.class).single();
        db.sql("""
                        INSERT INTO championship_session (championship_id, session_index, event_name, session_name)
                        VALUES (:c, 1, 'Bangsaen', 'Round 8')
                        """)
                .param("c", champId).update();
        long eventId = db.sql("INSERT INTO event (season_id, name, round_ordinal) VALUES (:s, 'Bangsaen', 1) RETURNING id")
                .param("s", seasonId).query(Long.class).single();
        long sessionId = db.sql("""
                        INSERT INTO race_session (event_id, session_type, name, ordinal)
                        VALUES (:e, 'RACE', 'Race', 1) RETURNING id
                        """)
                .param("e", eventId).query(Long.class).single();

        record Car(String number, String driver, int finish, Double racePoints, String status) {
        }
        Car[] cars = {
                new Car("99", "Guest Driver" + suffix, 1, null, null),
                new Car("61", "Adrian Silva" + suffix, 2, 17.0, null),
                new Car("16", "John Shen" + suffix, 3, 25.0, null),
                new Car("7", "Jinlong Bao" + suffix, 4, 0.0, "disqualified"),
                new Car("22", "Zhongwei Wang" + suffix, 5, 14.0, null),
        };
        int position = 1;
        for (Car c : cars) {
            long entryId = db.sql("""
                            INSERT INTO entry (event_id, car_number, class_name, team_name)
                            VALUES (:e, :n, 'AM', 'Team') RETURNING id
                            """)
                    .param("e", eventId).param("n", c.number()).query(Long.class).single();
            String[] name = c.driver().split(" ", 2);
            long driverId = db.sql("INSERT INTO driver (first_name, surname) VALUES (:f, :s) RETURNING id")
                    .param("f", name[0]).param("s", name[1]).query(Long.class).single();
            db.sql("INSERT INTO driver_assignment (entry_id, driver_id, seat_order) VALUES (:e, :d, 1)")
                    .param("e", entryId).param("d", driverId).update();
            db.sql("""
                            INSERT INTO result (session_id, entry_id, position_overall, position_in_class, status)
                            VALUES (:s, :e, :p, :p, 'Classified')
                            """)
                    .param("s", sessionId).param("e", entryId).param("p", c.finish()).update();
            if (c.racePoints() == null) {
                continue; // the guest: no standings row
            }
            long rowId = db.sql("""
                            INSERT INTO standings_row (championship_id, position, competitor_key, total_points)
                            VALUES (:c, :p, :k, :t) RETURNING id
                            """)
                    .param("c", champId).param("p", position++).param("k", c.driver()).param("t", c.racePoints())
                    .query(Long.class).single();
            db.sql("""
                            INSERT INTO standings_session_points (standings_row_id, session_index, total_points,
                                                                  race_points, status)
                            VALUES (:r, 1, :p, :p, :st)
                            """)
                    .param("r", rowId).param("p", c.racePoints()).param("st", c.status()).update();
        }
        return champId;
    }

    private Map<String, String> notesByCar(long champId) {
        Map<String, String> notes = new HashMap<>();
        for (SeasonViewController.RecapRow row : seasonView.recap(champId).rows()) {
            row.cells().values().forEach(races -> races.forEach(r -> notes.put(r.carNumber(), r.pointsNote())));
        }
        return notes;
    }

    @Test
    void carreraCupAsiaMarksFinishesThePointsContradict() {
        Map<String, String> notes = notesByCar(race("Porsche ARbox Carrera Cup Asia"));
        assertTrue(notes.get("61").startsWith("Scored behind #16"), notes.get("61"));
        assertTrue(notes.get("7").startsWith("Disqualified in the points"), notes.get("7"));
        // #16 and #22 scored in finishing order among registered drivers; the
        // guest's win ahead of #61 is not what flags #61.
        assertNull(notes.get("16"));
        assertNull(notes.get("22"));
        assertEquals(4, notes.size());
    }

    @Test
    void nonStartersReadAsDns() {
        long champId = race("Porsche ARbox Carrera Cup Asia");
        long seasonId = db.sql("SELECT season_id FROM championship WHERE id = :c")
                .param("c", champId).query(Long.class).single();
        long eventId = db.sql("SELECT id FROM event WHERE season_id = :s").param("s", seasonId)
                .query(Long.class).single();
        long sessionId = db.sql("SELECT id FROM race_session WHERE event_id = :e").param("e", eventId)
                .query(Long.class).single();
        String suffix = UUID.randomUUID().toString();
        // #4 is on the grid but not in the results (it never started).
        long entry4 = db.sql("""
                        INSERT INTO entry (event_id, car_number, class_name, team_name)
                        VALUES (:e, '4', 'AM', 'Team') RETURNING id
                        """)
                .param("e", eventId).query(Long.class).single();
        long lu = db.sql("INSERT INTO driver (first_name, surname) VALUES ('Wei', :s) RETURNING id")
                .param("s", "Lu" + suffix).query(Long.class).single();
        db.sql("INSERT INTO driver_assignment (entry_id, driver_id, seat_order) VALUES (:e, :d, 1)")
                .param("e", entry4).param("d", lu).update();
        db.sql("""
                        INSERT INTO grid_position (session_id, entry_id, position_overall, position_in_class)
                        VALUES (:s, :e, 12, 6)
                        """)
                .param("s", sessionId).param("e", entry4).update();
        // #12 withdrew: on no sheet but the points, which say DNS.
        for (String driver : List.of("Wei Lu" + suffix, "Hoang Dat Sawer" + suffix)) {
            long rowId = db.sql("""
                            INSERT INTO standings_row (championship_id, position, competitor_key, total_points)
                            VALUES (:c, 9, :k, 0) RETURNING id
                            """)
                    .param("c", champId).param("k", driver).query(Long.class).single();
            db.sql("""
                            INSERT INTO standings_session_points (standings_row_id, session_index, total_points,
                                                                  race_points, status)
                            VALUES (:r, 1, 0, 0, 'not_classified')
                            """)
                    .param("r", rowId).update();
        }

        Map<String, SeasonViewController.RecapRace> byDriver = new HashMap<>();
        for (SeasonViewController.RecapRow row : seasonView.recap(champId).rows()) {
            row.cells().values().forEach(races -> races.forEach(r -> byDriver.put(row.competitorKey(), r)));
        }
        SeasonViewController.RecapRace luWei = byDriver.get("Wei Lu" + suffix);
        assertEquals("Not Started", luWei.status());
        assertEquals(6, luWei.start());
        SeasonViewController.RecapRace sawer = byDriver.get("Hoang Dat Sawer" + suffix);
        assertEquals("Not Started", sawer.status());
        assertNull(sawer.start());
    }

    @Test
    void otherSeriesAreNotMarked() {
        Map<String, String> notes = notesByCar(race("IMSA Michelin Pilot Challenge"));
        assertTrue(notes.values().stream().allMatch(n -> n == null), notes.toString());
    }
}
