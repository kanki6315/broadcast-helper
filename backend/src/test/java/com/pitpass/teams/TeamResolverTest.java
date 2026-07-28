package com.pitpass.teams;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@SpringBootTest
@Transactional
class TeamResolverTest {

    @Autowired JdbcClient db;
    @Autowired TeamResolver resolver;

    @Test
    void blankNamesResolveToNoTeam() {
        assertNull(resolver.resolveOrCreate(null));
        assertNull(resolver.resolveOrCreate("   "));
    }

    @Test
    void unknownNameCreatesTeamWithItselfAsAlias() {
        String name = "Resolver test " + UUID.randomUUID();
        Long teamId = resolver.resolveOrCreate(name);

        assertEquals(name, db.sql("SELECT name FROM team WHERE id = :id")
                .param("id", teamId).query(String.class).single());
        assertEquals(1, db.sql("SELECT count(*) FROM team_alias WHERE team_id = :id")
                .param("id", teamId).query(Integer.class).single());
    }

    @Test
    void lookupIsCaseAndWhitespaceInsensitive() {
        String name = "Vasser Sullivan " + UUID.randomUUID();
        Long teamId = resolver.resolveOrCreate(name);

        assertEquals(teamId, resolver.resolveOrCreate(name.toUpperCase()));
        assertEquals(teamId, resolver.resolveOrCreate("  " + name + "  "));
    }

    /** The importer's invariant: an aliased spelling lands on the same team
     *  while entry.team_name keeps each file's raw spelling. */
    @Test
    void aliasedSpellingsShareOneTeamWhileRawNamesSurvive() {
        String suffix = UUID.randomUUID().toString();
        String original = "JDC-Miller MotorSports " + suffix;
        String variant = "JDC Miller MotorSports " + suffix;

        Long teamId = resolver.resolveOrCreate(original);
        Long variantTeam = resolver.resolveOrCreate(variant);
        assertNotEquals(teamId, variantTeam);

        // The admin records the variant as an alias; later imports converge.
        db.sql("DELETE FROM team_alias WHERE team_id = :id").param("id", variantTeam).update();
        db.sql("DELETE FROM team WHERE id = :id").param("id", variantTeam).update();
        db.sql("INSERT INTO team_alias (team_id, alias) VALUES (:id, :alias)")
                .param("id", teamId).param("alias", variant).update();
        assertEquals(teamId, resolver.resolveOrCreate(variant));

        long seriesId = db.sql("INSERT INTO series (name) VALUES (:n) RETURNING id")
                .param("n", "Resolver series " + suffix).query(Long.class).single();
        long seasonId = db.sql("INSERT INTO season (series_id, year) VALUES (:s, 2099) RETURNING id")
                .param("s", seriesId).query(Long.class).single();
        long eventId = db.sql("INSERT INTO event (season_id, name) VALUES (:s, 'Round') RETURNING id")
                .param("s", seasonId).query(Long.class).single();
        for (String spelling : new String[] {original, variant}) {
            db.sql("""
                            INSERT INTO entry (event_id, car_number, class_name, team_name, team_id)
                            VALUES (:event, :number, 'PRO', :team, :teamId)
                            """)
                    .param("event", eventId)
                    .param("number", spelling.equals(original) ? "85" : "5")
                    .param("team", spelling)
                    .param("teamId", resolver.resolveOrCreate(spelling))
                    .update();
        }

        assertEquals(2, db.sql("SELECT count(*) FROM entry WHERE event_id = :e AND team_id = :t")
                .param("e", eventId).param("t", teamId).query(Integer.class).single());
        assertEquals(2, db.sql("SELECT count(DISTINCT team_name) FROM entry WHERE event_id = :e")
                .param("e", eventId).query(Integer.class).single());
    }
}
