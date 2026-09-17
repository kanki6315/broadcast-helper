package com.pitpass.browse;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Transactional
class ChampionshipCalculatorTest {
    @Autowired JdbcClient db;
    @Autowired SeasonViewController controller;

    @Test void cupUsesVenueInsteadOfSeasonOrdinalAndLeavesRecapUntouched() {
        long series = db.sql("INSERT INTO series (name) VALUES (:n) RETURNING id")
                .param("n", "Calculator " + UUID.randomUUID()).query(Long.class).single();
        long season = db.sql("INSERT INTO season (series_id, year) VALUES (:s, 2026) RETURNING id")
                .param("s", series).query(Long.class).single();
        long group = db.sql("INSERT INTO championship_group (season_id, family, kind, label, ordinal, is_cup) VALUES (:s, 'IMEC', 'TEAMS', 'Teams', 1, true) RETURNING id")
                .param("s", season).query(Long.class).single();
        long champ = db.sql("INSERT INTO championship (season_id, name, title, class_name, group_id) VALUES (:s, 'IMEC GTP', 'IMEC GTP', 'GTP', :g) RETURNING id")
                .param("s", season).param("g", group).query(Long.class).single();
        db.sql("INSERT INTO championship_session (championship_id, session_index, event_name, session_name) VALUES (:c, 1, 'Daytona', 'Hour 6'), (:c, 2, 'Watkins Glen', 'Hour 3'), (:c, 3, 'Watkins Glen', 'Finish')")
                .param("c", champ).update();
        long row = db.sql("INSERT INTO standings_row (championship_id, position, competitor_key, competitor_name, total_points) VALUES (:c, 1, '7', 'Penske', 5) RETURNING id")
                .param("c", champ).query(Long.class).single();
        db.sql("INSERT INTO standings_session_points (standings_row_id, session_index, total_points, race_points) VALUES (:r, 1, 5, 5)")
                .param("r", row).update();
        db.sql("INSERT INTO event (season_id, name, round_ordinal) VALUES (:s, 'Daytona', 1), (:s, 'Sebring', 2)").param("s", season).update();
        long glen = db.sql("INSERT INTO event (season_id, name, circuit_name, round_ordinal) VALUES (:s, 'Six Hours', 'Watkins Glen International', 6) RETURNING id")
                .param("s", season).query(Long.class).single();
        var before = controller.recap(champ);
        var baseline = controller.calculator(champ);
        assertEquals(glen, baseline.rounds().get(1).eventId());
        assertEquals(2, baseline.rounds().get(1).sessions().size());
        assertEquals(before.rows(), baseline.rows());
        assertEquals(before, controller.recap(champ));
        // Ambiguity must never silently select a different event.
        db.sql("INSERT INTO event (season_id, name, round_ordinal) VALUES (:s, 'Watkins Glen second visit', 7)").param("s", season).update();
        assertNull(controller.calculator(champ).rounds().get(1).eventId());
    }
}
