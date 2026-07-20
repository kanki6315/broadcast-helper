package com.pitpass.teams;

import com.pitpass.season.SeasonController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
@Transactional
class SeasonTeamAssignmentControllerTest {

    @Autowired JdbcClient db;
    @Autowired SeasonTeamAssignmentController controller;
    @Autowired SeasonController seasons;

    @Test
    void sharedTeamsMidSeasonChangesPrivateersAndYearDeletionStayConsistent() {
        long seriesId = id("INSERT INTO series (name) VALUES (:name) RETURNING id",
                "name", "Assignment test " + UUID.randomUUID());
        long seasonId = id("INSERT INTO season (series_id, year) VALUES (:series, 2099) RETURNING id",
                "series", seriesId);
        long round1 = event(seasonId, "Round one", 1);
        long round2 = event(seasonId, "Round two", 2);
        long alice = driver("Alice", "Apex");
        long ben = driver("Ben", "Brake");
        long casey = driver("Casey", "Cruise");

        entry(round1, "1", alice);
        entry(round1, "2", ben);
        entry(round1, "3", casey);
        entry(round2, "1", alice);
        entry(round2, "2", ben);
        entry(round2, "3", casey);

        controller.save(seasonId, new SeasonTeamAssignmentController.SaveRequest(List.of(
                new SeasonTeamAssignmentController.SaveAssignment(alice, "Redline", false, 1),
                new SeasonTeamAssignmentController.SaveAssignment(alice, "Coanda", false, 2),
                new SeasonTeamAssignmentController.SaveAssignment(ben, "Redline", false, 1),
                new SeasonTeamAssignmentController.SaveAssignment(casey, "Privateer", true, 1)
        )));

        assertEquals("Redline", team(round1, "1"));
        assertEquals("Coanda", team(round2, "1"));
        assertEquals("Redline", team(round1, "2"));
        assertEquals("Redline", team(round2, "2"));
        assertEquals("Privateer", team(round1, "3"));
        assertEquals("Privateer", team(round2, "3"));
        assertEquals(1, count("SELECT count(*) FROM season_team WHERE season_id = :id AND name = 'Redline'", seasonId));
        assertEquals(1, count("SELECT count(*) FROM season_team WHERE season_id = :id AND privateer_driver_id = :driver",
                seasonId, "driver", casey));
        assertEquals(2, controller.get(seasonId).drivers().stream()
                .filter(driver -> driver.id() == alice).findFirst().orElseThrow().assignments().size());

        seasons.deleteData(seasonId);
        assertEquals(0, count("SELECT count(*) FROM season_team WHERE season_id = :id", seasonId));
        assertEquals(0, count("SELECT count(*) FROM event WHERE season_id = :id", seasonId));
    }

    private long event(long seasonId, String name, int round) {
        return db.sql("""
                        INSERT INTO event (season_id, name, round_ordinal)
                        VALUES (:season, :name, :round) RETURNING id
                        """)
                .param("season", seasonId).param("name", name).param("round", round)
                .query(Long.class).single();
    }

    private long driver(String first, String surname) {
        return db.sql("INSERT INTO driver (first_name, surname) VALUES (:first, :surname) RETURNING id")
                .param("first", first).param("surname", surname + UUID.randomUUID())
                .query(Long.class).single();
    }

    private void entry(long eventId, String number, long driverId) {
        long entryId = db.sql("""
                        INSERT INTO entry (event_id, car_number, class_name, team_name)
                        VALUES (:event, :number, 'PRO', 'Provider placeholder') RETURNING id
                        """)
                .param("event", eventId).param("number", number).query(Long.class).single();
        db.sql("INSERT INTO driver_assignment (entry_id, driver_id, seat_order) VALUES (:entry, :driver, 1)")
                .param("entry", entryId).param("driver", driverId).update();
    }

    private String team(long eventId, String number) {
        return db.sql("SELECT team_name FROM entry WHERE event_id = :event AND car_number = :number")
                .param("event", eventId).param("number", number).query(String.class).single();
    }

    private int count(String sql, long seasonId, Object... extra) {
        var query = db.sql(sql).param("id", seasonId);
        for (int i = 0; i < extra.length; i += 2) query = query.param((String) extra[i], extra[i + 1]);
        return query.query(Integer.class).single();
    }

    private long id(String sql, String parameter, Object value) {
        return db.sql(sql).param(parameter, value).query(Long.class).single();
    }
}
