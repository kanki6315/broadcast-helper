package com.pitpass.live;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Feed drivers against a real event's crews: exact numbers first, surnames within the car, nothing dropped. */
@SpringBootTest
@Transactional
class LiveDriverResolverTest {

    @Autowired JdbcClient db;
    @Autowired LiveDriverResolver resolver;
    @Autowired ObjectMapper mapper;

    private long id(String sql, Object... params) {
        var spec = db.sql(sql);
        for (int i = 0; i < params.length; i += 2) {
            spec = spec.param((String) params[i], params[i + 1]);
        }
        return spec.query(Long.class).single();
    }

    private long driver(String first, String surname) {
        return id("INSERT INTO driver (first_name, surname) VALUES (:f, :s) RETURNING id", "f", first, "s", surname);
    }

    private void seat(long entry, long driver, int seat, String rating) {
        db.sql("INSERT INTO driver_assignment (entry_id, driver_id, seat_order, rating) VALUES (:e, :d, :o, :r)")
                .param("e", entry).param("d", driver).param("o", seat).param("r", rating).update();
    }

    @Test
    void matchesCarsExactlyThenDriversBySurnameAndListsTheRest() throws Exception {
        String u = UUID.randomUUID().toString().substring(0, 8);
        long series = id("INSERT INTO series (name) VALUES (:n) RETURNING id", "n", "Resolver " + u);
        long season = id("INSERT INTO season (series_id, year) VALUES (:s, 2099) RETURNING id", "s", series);
        long event = id("INSERT INTO event (season_id, name, round_ordinal) VALUES (:s, :n, 1) RETURNING id",
                "s", season, "n", "Resolver " + u);
        long zero4 = id("INSERT INTO entry (event_id, car_number, class_name, team_name) VALUES (:e, '04', 'GTD', 'Team') RETURNING id", "e", event);
        long four = id("INSERT INTO entry (event_id, car_number, class_name, team_name) VALUES (:e, '4', 'GTD', 'Team') RETURNING id", "e", event);
        long müller = driver("Anna" + u, "Müller" + u);
        long smithA = driver("Bob" + u, "Smith" + u);
        long smithB = driver("Cal" + u, "Smith" + u);
        long other = driver("Dee" + u, "Other" + u);
        seat(zero4, müller, 1, "S");
        seat(zero4, smithA, 2, "B");
        seat(zero4, smithB, 3, "G");
        seat(four, other, 1, "P");
        long session = 800_000_000L + (System.nanoTime() % 1_000_000);
        db.sql("INSERT INTO live_session (session_db_id, event_id) VALUES (:s, :e)").param("s", session).param("e", event).update();

        var entries = mapper.readTree("""
                {"04": {"number": "04", "drivers": {
                    "1": {"number": 1, "firstName": "ANNA%1$s", "lastName": "MULLER%1$s", "license": "Silver"},
                    "2": {"number": 2, "firstName": "Bob%1$s", "lastName": "Smith%1$s", "license": "Bronze"},
                    "3": {"number": 3, "firstName": "Cal%1$s", "lastName": "Smith%1$s", "license": "Gold"},
                    "4": {"number": 4, "firstName": "Eve", "lastName": "Late", "license": "Platinum"}}},
                 "4": {"number": "4", "drivers": {"1": {"firstName": "Dee%1$s", "lastName": "Other%1$s", "license": "Platinum"}}},
                 "99": {"number": "99", "drivers": {"1": {"firstName": "No", "lastName": "Entry", "license": "Bronze"}}}}
                """.formatted(u));
        resolver.resolve(session, entries, event);

        List<String> rows = db.sql("""
                SELECT car_number || '/' || driver_order || ' ' || COALESCE(driver_id::text, '-') || ' '
                       || COALESCE(entry_id::text, '-') || ' ' || COALESCE(rating, '-')
                FROM live_driver WHERE session_db_id = :s ORDER BY car_number, driver_order
                """).param("s", session).query(String.class).list();
        assertEquals(List.of(
                "04/1 " + müller + " " + zero4 + " S",   // accents and case folded
                "04/2 " + smithA + " " + zero4 + " B",   // a shared surname falls back to the full name
                "04/3 " + smithB + " " + zero4 + " G",
                "04/4 - " + zero4 + " P",                 // not in our crew: kept, with the feed's license
                "4/1 " + other + " " + four + " P",       // #4 is not #04
                "99/1 - - B"), rows);                     // a car we have no entry for
    }
}
