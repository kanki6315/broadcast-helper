package com.pitpass.teams;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@Transactional
class TeamAdminControllerTest {

    @Autowired JdbcClient db;
    @Autowired TeamAdminController controller;
    @Autowired TeamResolver resolver;

    @Test
    void mergeMovesEntriesAliasesAndLineageThenDeletesTheSource() {
        String suffix = UUID.randomUUID().toString();
        long target = resolver.resolveOrCreate("Penske Motorsport " + suffix);
        long source = resolver.resolveOrCreate("Team Penske " + suffix);
        long ancestor = resolver.resolveOrCreate("Penske Racing " + suffix);
        long successor = resolver.resolveOrCreate("Porsche Penske " + suffix);
        db.sql("UPDATE team SET predecessor_id = :pred WHERE id = :id")
                .param("pred", ancestor).param("id", source).update();
        db.sql("UPDATE team SET predecessor_id = :pred WHERE id = :id")
                .param("pred", source).param("id", successor).update();

        long seriesId = db.sql("INSERT INTO series (name) VALUES (:n) RETURNING id")
                .param("n", "Merge series " + suffix).query(Long.class).single();
        long seasonId = db.sql("INSERT INTO season (series_id, year) VALUES (:s, 2099) RETURNING id")
                .param("s", seriesId).query(Long.class).single();
        long eventId = db.sql("INSERT INTO event (season_id, name) VALUES (:s, 'Round') RETURNING id")
                .param("s", seasonId).query(Long.class).single();
        db.sql("""
                        INSERT INTO entry (event_id, car_number, class_name, team_name, team_id)
                        VALUES (:event, '6', 'GTP', 'Team Penske', :teamId)
                        """)
                .param("event", eventId).param("teamId", source).update();

        TeamAdminController.MergeResult result = controller.merge(target,
                new TeamAdminController.MergeRequest(source));

        assertEquals(1, result.entriesMoved());
        assertEquals(1, result.aliasesMoved());
        assertEquals(0, db.sql("SELECT count(*) FROM team WHERE id = :id")
                .param("id", source).query(Integer.class).single());
        assertEquals(target, db.sql("SELECT team_id FROM entry WHERE event_id = :e AND car_number = '6'")
                .param("e", eventId).query(Long.class).single());
        // The retired spelling still resolves — to the surviving team.
        assertEquals(target, resolver.resolveOrCreate("Team Penske " + suffix));
        // Lineage followed the merge: successor now points at the target, and
        // the target inherited the source's own predecessor.
        assertEquals(target, db.sql("SELECT predecessor_id FROM team WHERE id = :id")
                .param("id", successor).query(Long.class).single());
        assertEquals(ancestor, db.sql("SELECT predecessor_id FROM team WHERE id = :id")
                .param("id", target).query(Long.class).single());
    }

    @Test
    void mergeIntoItselfIsRejected() {
        long team = resolver.resolveOrCreate("Self merge " + UUID.randomUUID());
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> controller.merge(team, new TeamAdminController.MergeRequest(team)));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, e.getStatusCode());
    }

    @Test
    void anAliasSpellingIsGloballyUnique() {
        String suffix = UUID.randomUUID().toString();
        long a = resolver.resolveOrCreate("Alias holder " + suffix);
        long b = resolver.resolveOrCreate("Alias wanter " + suffix);
        controller.addAlias(a, new TeamAdminController.AliasRequest("VS Racing " + suffix));

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> controller.addAlias(b, new TeamAdminController.AliasRequest("vs racing " + suffix)));
        assertEquals(HttpStatus.CONFLICT, e.getStatusCode());
    }

    @Test
    void theLastAliasCannotBeDeleted() {
        long team = resolver.resolveOrCreate("Last alias " + UUID.randomUUID());
        long aliasId = db.sql("SELECT id FROM team_alias WHERE team_id = :id")
                .param("id", team).query(Long.class).single();

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> controller.deleteAlias(team, aliasId));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, e.getStatusCode());
    }

    @Test
    void renameKeepsOldSpellingResolvingAndPredecessorSetsAndClears() {
        String suffix = UUID.randomUUID().toString();
        long team = resolver.resolveOrCreate("Van der Steur Racing " + suffix);
        long predecessor = resolver.resolveOrCreate("YRB Racing " + suffix);

        controller.update(team, new TeamAdminController.UpdateRequest(
                "VDS Racing " + suffix, null, null));
        assertEquals("VDS Racing " + suffix, db.sql("SELECT name FROM team WHERE id = :id")
                .param("id", team).query(String.class).single());
        assertEquals(team, resolver.resolveOrCreate("Van der Steur Racing " + suffix));
        assertEquals(team, resolver.resolveOrCreate("VDS Racing " + suffix));

        controller.update(team, new TeamAdminController.UpdateRequest(null, predecessor, null));
        assertEquals(predecessor, db.sql("SELECT predecessor_id FROM team WHERE id = :id")
                .param("id", team).query(Long.class).single());

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> controller.update(team, new TeamAdminController.UpdateRequest(null, team, null)));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, e.getStatusCode());

        controller.update(team, new TeamAdminController.UpdateRequest(null, null, true));
        record PredRow(Long predecessorId) {
        }
        assertNull(db.sql("SELECT predecessor_id FROM team WHERE id = :id")
                .param("id", team)
                .query((rs, i) -> new PredRow(rs.getObject("predecessor_id", Long.class)))
                .single().predecessorId());

        assertTrue(controller.manage("vds racing " + suffix).stream().anyMatch(t -> t.id() == team));
    }
}
