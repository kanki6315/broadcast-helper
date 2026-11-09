package com.pitpass.browse;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The recap pairs a championship's rounds with the season's events by venue,
 * not by position: the Michelin Endurance Cup's third round is Watkins Glen,
 * not the season's third event (Long Beach), and a season missing a weekend
 * (2022 Pilot Challenge before its Watkins Glen/CTMP import) must leave that
 * column blank rather than slide every later round onto the next weekend.
 */
@SpringBootTest
@Transactional
class RecapRoundMatchingTest {

    @Autowired JdbcClient db;
    @Autowired SeasonViewController seasonView;

    /** A season with one event per name (round ordinals in the given order)
     *  and a championship whose calendar is {@code calendar}, one session per
     *  round. Returns the championship id followed by the event ids. */
    private List<Long> fixture(List<String> eventNames, List<String> calendar) {
        String suffix = UUID.randomUUID().toString();
        long seriesId = db.sql("INSERT INTO series (name) VALUES (:n) RETURNING id")
                .param("n", "Recap rounds series " + suffix).query(Long.class).single();
        long seasonId = db.sql("INSERT INTO season (series_id, year) VALUES (:s, 2099) RETURNING id")
                .param("s", seriesId).query(Long.class).single();
        long groupId = db.sql("""
                        INSERT INTO championship_group (season_id, family, kind, label, ordinal)
                        VALUES (:s, 'Season', 'TEAMS', 'Teams', 1) RETURNING id
                        """)
                .param("s", seasonId).query(Long.class).single();
        long champId = db.sql("""
                        INSERT INTO championship (season_id, name, title, class_name, group_id)
                        VALUES (:s, :n, :n, 'GTP', :g) RETURNING id
                        """)
                .param("s", seasonId).param("n", "Teams " + suffix).param("g", groupId)
                .query(Long.class).single();
        for (int i = 0; i < calendar.size(); i++) {
            db.sql("""
                            INSERT INTO championship_session (championship_id, session_index, event_name, session_name)
                            VALUES (:c, :i, :e, 'Race')
                            """)
                    .param("c", champId).param("i", i + 1).param("e", calendar.get(i)).update();
        }
        List<Long> ids = new ArrayList<>(List.of(champId));
        for (int i = 0; i < eventNames.size(); i++) {
            ids.add(db.sql("""
                            INSERT INTO event (season_id, name, round_ordinal)
                            VALUES (:s, :n, :o) RETURNING id
                            """)
                    .param("s", seasonId).param("n", eventNames.get(i)).param("o", i + 1)
                    .query(Long.class).single());
        }
        return ids;
    }

    private List<Long> eventIds(long champId) {
        return seasonView.recap(champId).rounds().stream().map(SeasonViewController.RecapRound::eventId).toList();
    }

    @Test
    void aCupsRoundsFindTheirOwnWeekends() {
        List<Long> f = fixture(
                List.of("Rolex 24 at Daytona", "Acura Grand Prix of Long Beach",
                        "Sahlen's Six Hours of The Glen", "Motul Petit Le Mans"),
                List.of("Daytona", "Watkins Glen", "Michelin Raceway"));
        assertEquals(List.of(f.get(1), f.get(3), f.get(4)), eventIds(f.get(0)));
    }

    @Test
    void aMissingWeekendLeavesItsColumnBlankInsteadOfShiftingTheRest() {
        List<Long> f = fixture(
                List.of("Daytona International Speedway", "Lime Rock Park", "Road America"),
                List.of("Daytona", "Watkins Glen", "Lime Rock", "Road America"));
        List<Long> ids = eventIds(f.get(0));
        assertEquals(f.get(1), ids.get(0));
        assertNull(ids.get(1));
        assertEquals(Arrays.asList(f.get(2), f.get(3)), ids.subList(2, 4));
    }

    @Test
    void aTrackVisitedTwicePairsInCalendarOrder() {
        // 2021 WeatherTech's names: "Belle Isle" is Detroit, "… - WGI" is the Glen.
        List<Long> f = fixture(
                List.of("Daytona International Speedway", "Belle Isle Street Course",
                        "6H - Watkins Glen International", "WeatherTech 240 - WGI", "Lime Rock Park"),
                List.of("Daytona", "Detroit", "Watkins Glen 1", "Watkins Glen 2", "Lime Rock"));
        assertEquals(f.subList(1, 6), eventIds(f.get(0)));
    }
}
