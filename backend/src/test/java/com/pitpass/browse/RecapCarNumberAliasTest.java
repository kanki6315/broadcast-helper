package com.pitpass.browse;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The recap matches result cells to a car-number-keyed standings row through
 * car_number_alias (V38): the season where one entrant raced under a second
 * number — a one-off renumbering (JDC-Miller's #5 running Daytona as #85) or
 * a mid-season entry transfer (van der Steur's #19 GTD becoming Car Blanche's
 * #068) — shows every round on the one row the standings source published.
 */
@SpringBootTest
@Transactional
class RecapCarNumberAliasTest {

    @Autowired JdbcClient db;
    @Autowired SeasonViewController seasonView;

    private record Fixture(long seasonId, long champId) {
    }

    /** A two-round TEAMS championship whose standings row is keyed {@code key},
     *  with round 1 raced by {@code car1}/{@code team1} and round 2 by
     *  {@code car2}/{@code team2}, one race each, both with results. */
    private Fixture fixture(String className, String key, String rowName,
                            String car1, String team1, String car2, String team2) {
        String suffix = UUID.randomUUID().toString();
        long seriesId = db.sql("INSERT INTO series (name) VALUES (:n) RETURNING id")
                .param("n", "Recap alias series " + suffix).query(Long.class).single();
        long seasonId = db.sql("INSERT INTO season (series_id, year) VALUES (:s, 2099) RETURNING id")
                .param("s", seriesId).query(Long.class).single();
        long groupId = db.sql("""
                        INSERT INTO championship_group (season_id, family, kind, label, ordinal)
                        VALUES (:s, 'Season', 'TEAMS', 'Teams', 1) RETURNING id
                        """)
                .param("s", seasonId).query(Long.class).single();
        long champId = db.sql("""
                        INSERT INTO championship (season_id, name, title, class_name, group_id)
                        VALUES (:s, :n, :n, :cls, :g) RETURNING id
                        """)
                .param("s", seasonId).param("n", className + " Teams " + suffix)
                .param("cls", className).param("g", groupId)
                .query(Long.class).single();
        db.sql("""
                        INSERT INTO championship_session (championship_id, session_index, event_name, session_name)
                        VALUES (:c, 1, 'Daytona', 'Race'), (:c, 2, 'Sebring', 'Race')
                        """)
                .param("c", champId).update();

        long rowId = db.sql("""
                        INSERT INTO standings_row (championship_id, position, competitor_key, competitor_name, total_points)
                        VALUES (:c, 1, :key, :name, 100) RETURNING id
                        """)
                .param("c", champId).param("key", key).param("name", rowName)
                .query(Long.class).single();
        db.sql("""
                        INSERT INTO standings_session_points (standings_row_id, session_index, total_points, race_points)
                        VALUES (:r, 1, 30, 30), (:r, 2, 70, 70)
                        """)
                .param("r", rowId).update();

        record Round(int ordinal, String name, String car, String team) {
        }
        for (Round round : List.of(new Round(1, "Daytona", car1, team1),
                new Round(2, "Sebring", car2, team2))) {
            long eventId = db.sql("""
                            INSERT INTO event (season_id, name, round_ordinal)
                            VALUES (:s, :n, :o) RETURNING id
                            """)
                    .param("s", seasonId).param("n", round.name()).param("o", round.ordinal())
                    .query(Long.class).single();
            long raceId = db.sql("""
                            INSERT INTO race_session (event_id, session_type, name)
                            VALUES (:e, 'RACE', 'Race') RETURNING id
                            """)
                    .param("e", eventId).query(Long.class).single();
            long entryId = db.sql("""
                            INSERT INTO entry (event_id, car_number, class_name, team_name)
                            VALUES (:e, :car, :cls, :team) RETURNING id
                            """)
                    .param("e", eventId).param("car", round.car()).param("cls", className)
                    .param("team", round.team())
                    .query(Long.class).single();
            db.sql("""
                            INSERT INTO result (session_id, entry_id, position_in_class, position_overall, status)
                            VALUES (:s, :en, :pos, :pos, 'Finished')
                            """)
                    .param("s", raceId).param("en", entryId).param("pos", round.ordinal())
                    .update();
        }
        return new Fixture(seasonId, champId);
    }

    private SeasonViewController.RecapRow onlyRow(long champId) {
        List<SeasonViewController.RecapRow> rows = seasonView.recap(champId).rows();
        assertEquals(1, rows.size());
        return rows.get(0);
    }

    @Test
    void aRenumberedEntrantsCellsLandOnItsStandingsRowOnceAliased() {
        Fixture f = fixture("PX", "5", "JDC Test", "85", "JDC Test", "5", "JDC Test");

        // Without the alias, the Daytona weekend (raced as #85) is missing.
        SeasonViewController.RecapRow before = onlyRow(f.champId());
        assertFalse(before.cells().containsKey(1));
        assertTrue(before.cells().containsKey(2));

        db.sql("""
                        INSERT INTO car_number_alias (season_id, class_name, car_number, canonical_number)
                        VALUES (:s, 'PX', '85', '5')
                        """)
                .param("s", f.seasonId()).update();

        SeasonViewController.RecapRow after = onlyRow(f.champId());
        assertTrue(after.cells().containsKey(1));
        assertTrue(after.cells().containsKey(2));
        assertEquals(1, after.cells().get(1).get(0).finish());
        assertEquals(2, after.cells().get(2).get(0).finish());
    }

    @Test
    void aTransferredEntryMergesAcrossTeamsAndLeadingZeros() {
        // van der Steur's #19 became Car Blanche's #068; the standings source
        // keys the row "068" but the alias spells the canonical number "68" —
        // the number normalization must absorb the drift.
        Fixture f = fixture("GTX", "068", "Car Blanche Test",
                "19", "van der Steur Test", "068", "Car Blanche Test");
        db.sql("""
                        INSERT INTO car_number_alias (season_id, class_name, car_number, canonical_number)
                        VALUES (:s, 'GTX', '19', '68')
                        """)
                .param("s", f.seasonId()).update();

        SeasonViewController.RecapRow row = onlyRow(f.champId());
        assertTrue(row.cells().containsKey(1));
        assertTrue(row.cells().containsKey(2));
        // Both organizations' spellings ride along, in first-appearance order
        // after the row's own name.
        assertEquals(List.of("Car Blanche Test", "van der Steur Test"), row.teamNames());
    }

    @Test
    void aLinkStoredEitherWayRoundMatchesTheRow() {
        // LAP's renumbering: rounds raced as #30, then as #6. The standings
        // still key the row "30" (imported before the renumbering), but the
        // user stored the link in surviving-identity order: 30 counts as 6.
        // Symmetric resolution must land every round on the "30" row anyway.
        Fixture f = fixture("GX", "30", "LAP Test", "30", "LAP Test", "6", "LAP Test");
        db.sql("""
                        INSERT INTO car_number_alias (season_id, class_name, car_number, canonical_number)
                        VALUES (:s, 'GX', '30', '6')
                        """)
                .param("s", f.seasonId()).update();

        SeasonViewController.RecapRow row = onlyRow(f.champId());
        assertTrue(row.cells().containsKey(1));
        assertTrue(row.cells().containsKey(2));
    }

    @Test
    void theSameLinkSurvivesTheStandingsKeyFlippingToTheNewNumber() {
        // The season after the next standings import: the source now keys the
        // row by the NEW number, the link is unchanged. Old-number rounds must
        // follow the row to its new key.
        Fixture f = fixture("GY", "6", "LAP Test", "30", "LAP Test", "6", "LAP Test");
        db.sql("""
                        INSERT INTO car_number_alias (season_id, class_name, car_number, canonical_number)
                        VALUES (:s, 'GY', '30', '6')
                        """)
                .param("s", f.seasonId()).update();

        SeasonViewController.RecapRow row = onlyRow(f.champId());
        assertTrue(row.cells().containsKey(1));
        assertTrue(row.cells().containsKey(2));
    }

    @Test
    void anAliasInAnotherClassNeverBleedsIn() {
        Fixture f = fixture("PY", "5", "JDC Test", "85", "JDC Test", "5", "JDC Test");
        db.sql("""
                        INSERT INTO car_number_alias (season_id, class_name, car_number, canonical_number)
                        VALUES (:s, 'OTHER', '85', '5')
                        """)
                .param("s", f.seasonId()).update();

        assertFalse(onlyRow(f.champId()).cells().containsKey(1));
    }
}
