package com.pitpass.live;

import com.pitpass.live.DriveTimeRuleController.Rule;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest
@Transactional
class DriveTimeRuleControllerTest {

    @Autowired JdbcClient db;
    @Autowired DriveTimeRuleController controller;

    private long event() {
        String u = UUID.randomUUID().toString();
        long series = db.sql("INSERT INTO series (name) VALUES (:n) RETURNING id").param("n", "DT " + u).query(Long.class).single();
        long season = db.sql("INSERT INTO season (series_id, year) VALUES (:s, 2099) RETURNING id").param("s", series).query(Long.class).single();
        return db.sql("INSERT INTO event (season_id, name, round_ordinal) VALUES (:s, :n, 1) RETURNING id")
                .param("s", season).param("n", "DT " + u).query(Long.class).single();
    }

    @Test
    void replacesTheWholeSetAndNormalisesRatings() {
        long event = event();
        controller.replace(event, List.of(new Rule("GTD", null, null, 14_400_000L, "4h max")));
        List<Rule> saved = controller.replace(event, List.of(
                new Rule(" GTD ", "Bronze", 3_600_000L, null, " "),
                new Rule("GTD", "", null, 14_400_000L, "4h max")));
        assertEquals(List.of(
                new Rule("GTD", null, null, 14_400_000L, "4h max"),
                new Rule("GTD", "B", 3_600_000L, null, null)), saved);
        assertEquals(saved, controller.list(event));
    }

    @Test
    void refusesWhatCannotBeARule() {
        long event = event();
        for (List<Rule> bad : List.of(
                List.of(new Rule("", null, 1L, null, null)),
                List.of(new Rule("GTD", "X", 1L, null, null)),
                List.of(new Rule("GTD", null, null, null, null)),
                List.of(new Rule("GTD", null, 10L, 5L, null)),
                List.of(new Rule("GTD", "B", 1L, null, null), new Rule("gtd", "Bronze", 2L, null, null)))) {
            assertEquals(422, assertThrows(ResponseStatusException.class, () -> controller.replace(event, bad))
                    .getStatusCode().value());
        }
        assertEquals(404, assertThrows(ResponseStatusException.class, () -> controller.list(-1))
                .getStatusCode().value());
    }
}
