package com.pitpass.browse;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Transactional
class MultiSeasonRecapTest {
    @Autowired JdbcClient db;
    @Autowired MultiSeasonRecapController controller;

    @Test void canonicalDriverSurvivesAliasesTeamAndNumberChangesAndExcludesQualifiers() {
        String suffix = UUID.randomUUID().toString();
        long series = db.sql("INSERT INTO series(name) VALUES (:n) RETURNING id").param("n", "Recap " + suffix).query(Long.class).single();
        long driver = db.sql("INSERT INTO driver(first_name,surname) VALUES ('Test',:n) RETURNING id").param("n", suffix).query(Long.class).single();
        String alias = "Old " + suffix;
        db.sql("INSERT INTO driver_alias(driver_id,alias) VALUES (:d,:n)").param("d", driver).param("n", alias).update();
        for (int year = 2097; year <= 2099; year++) {
            long season = db.sql("INSERT INTO season(series_id,year,kind,label) VALUES (:s,:y,:k,'Stage') RETURNING id")
                    .param("s", series).param("y", year).param("k", year == 2099 ? "QUALIFIER" : "MAIN").query(Long.class).single();
            long group = db.sql("INSERT INTO championship_group(season_id,family,kind,label,ordinal) VALUES (:s,'Main','DRIVERS','Drivers',1) RETURNING id").param("s", season).query(Long.class).single();
            long champ = db.sql("INSERT INTO championship(season_id,name,class_name,group_id) VALUES (:s,'Main','F4',:g) RETURNING id").param("s", season).param("g", group).query(Long.class).single();
            db.sql("INSERT INTO championship_session(championship_id,session_index,event_name,session_name) VALUES (:c,1,'Fuji','Race')").param("c", champ).update();
            db.sql("INSERT INTO standings_row(championship_id,position,competitor_key,competitor_name,total_points) VALUES (:c,1,'external-id',:n,25)").param("c", champ).param("n", year == 2097 ? alias : "Test " + suffix).update();
            long event = db.sql("INSERT INTO event(season_id,name,round_ordinal) VALUES (:s,'Fuji',1) RETURNING id").param("s", season).query(Long.class).single();
            long race = db.sql("INSERT INTO race_session(event_id,session_type,name,ordinal) VALUES (:e,'RACE','Race',1) RETURNING id").param("e", event).query(Long.class).single();
            long entry = db.sql("INSERT INTO entry(event_id,car_number,class_name,team_name) VALUES (:e,:n,'F4',:t) RETURNING id").param("e", event).param("n", String.valueOf(year)).param("t", "Team " + year).query(Long.class).single();
            db.sql("INSERT INTO driver_assignment(entry_id,driver_id,seat_order) VALUES (:e,:d,1)").param("e", entry).param("d", driver).update();
            db.sql("INSERT INTO result(session_id,entry_id,position_overall,position_in_class) VALUES (:s,:e,1,1)").param("s", race).param("e", entry).update();
        }
        var history = controller.history(series);
        assertEquals(List.of(2097,2098), history.seasons().stream().map(MultiSeasonRecapController.Season::year).toList());
        for (var season : history.seasons()) {
            assertEquals(driver, season.driverIds().get("external-id"));
            assertEquals(1, season.recap().rows().getFirst().cells().get(1).getFirst().finish());
            assertEquals(1, season.stats().rows().getFirst().byFormat().getFirst().wins());
            assertEquals(0, season.stats().rows().getFirst().quali().poles());
        }
    }

    @Test void ambiguousAndMissingIdentitiesAreNotGuessed() {
        var identities = Map.of("test driver", Set.of(1L), "duplicate", Set.of(2L,3L));
        assertEquals(1L, MultiSeasonRecapController.resolve(identities, " Test   DRIVER "));
        assertNull(MultiSeasonRecapController.resolve(identities, "duplicate"));
        assertNull(MultiSeasonRecapController.resolve(identities, null));
        assertThrows(ResponseStatusException.class, () -> controller.history(-1));
    }
}
