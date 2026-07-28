package com.pitpass.teams;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Curation of the global team catalogue (see {@code team}/{@code team_alias},
 * V35): aliases collapse sponsorship-era spellings onto one organization,
 * merge absorbs the duplicate teams the importers auto-create, and the
 * predecessor link records an entry transfer (a genuinely new organization)
 * without merging the two records. Raw entry.team_name spellings are never
 * touched here — only which team they resolve to.
 */
@RestController
@RequestMapping("/api/teams")
public class TeamAdminController {

    private final JdbcClient db;

    public TeamAdminController(JdbcClient db) {
        this.db = db;
    }

    public record AliasRow(long id, String alias) {
    }

    public record ManagedTeam(long id, String name, List<AliasRow> aliases,
                              Long predecessorId, String predecessorName,
                              int entryCount, Integer lastYear) {
    }

    public record AliasRequest(@NotBlank String alias) {
    }

    public record MergeRequest(long sourceTeamId) {
    }

    public record MergeResult(int entriesMoved, int aliasesMoved) {
    }

    /** name renames the canonical display name; predecessorId sets the lineage
     *  link, clearPredecessor removes it (a PATCH body can't distinguish an
     *  absent predecessorId from an explicit null). */
    public record UpdateRequest(String name, Long predecessorId, Boolean clearPredecessor) {
    }

    @GetMapping("/manage")
    public List<ManagedTeam> manage(@RequestParam(required = false) String q) {
        String needle = q == null ? "" : q.trim().toLowerCase();
        record TeamRow(long id, String name, Long predecessorId, String predecessorName,
                       int entryCount, Integer lastYear) {
        }
        List<TeamRow> teams = db.sql("""
                        SELECT t.id, t.name, t.predecessor_id, p.name AS predecessor_name,
                               (SELECT count(*) FROM entry en WHERE en.team_id = t.id) AS entry_count,
                               (SELECT max(s.year)
                                FROM entry en
                                         JOIN event ev ON ev.id = en.event_id
                                         JOIN season s ON s.id = ev.season_id
                                WHERE en.team_id = t.id) AS last_year
                        FROM team t
                                 LEFT JOIN team p ON p.id = t.predecessor_id
                        WHERE :needle = ''
                           OR lower(t.name) LIKE :contains
                           OR EXISTS (SELECT 1 FROM team_alias ta
                                      WHERE ta.team_id = t.id AND lower(ta.alias) LIKE :contains)
                        ORDER BY lower(t.name)
                        LIMIT 50
                        """)
                .param("needle", needle)
                .param("contains", "%" + needle + "%")
                .query((rs, i) -> new TeamRow(rs.getLong("id"), rs.getString("name"),
                        rs.getObject("predecessor_id", Long.class), rs.getString("predecessor_name"),
                        rs.getInt("entry_count"), rs.getObject("last_year", Integer.class)))
                .list();
        if (teams.isEmpty()) {
            return List.of();
        }
        Map<Long, List<AliasRow>> aliases = new LinkedHashMap<>();
        db.sql("""
                        SELECT id, team_id, alias FROM team_alias
                        WHERE team_id IN (:teamIds)
                        ORDER BY lower(alias)
                        """)
                .param("teamIds", teams.stream().map(TeamRow::id).toList())
                .query((rs, i) -> aliases
                        .computeIfAbsent(rs.getLong("team_id"), k -> new ArrayList<>())
                        .add(new AliasRow(rs.getLong("id"), rs.getString("alias"))))
                .list();
        return teams.stream()
                .map(t -> new ManagedTeam(t.id(), t.name(), aliases.getOrDefault(t.id(), List.of()),
                        t.predecessorId(), t.predecessorName(), t.entryCount(), t.lastYear()))
                .toList();
    }

    @PostMapping("/{id}/aliases")
    public AliasRow addAlias(@PathVariable long id, @Valid @RequestBody AliasRequest request) {
        requireTeam(id);
        String alias = request.alias().trim();
        try {
            long aliasId = db.sql("""
                            INSERT INTO team_alias (team_id, alias)
                            VALUES (:teamId, :alias)
                            RETURNING id
                            """)
                    .param("teamId", id)
                    .param("alias", alias)
                    .query(Long.class)
                    .single();
            return new AliasRow(aliasId, alias);
        } catch (DuplicateKeyException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "'" + alias + "' already resolves to a team");
        }
    }

    @DeleteMapping("/{id}/aliases/{aliasId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteAlias(@PathVariable long id, @PathVariable long aliasId) {
        Integer count = db.sql("SELECT count(*) FROM team_alias WHERE team_id = :teamId")
                .param("teamId", id)
                .query(Integer.class)
                .single();
        if (count == 1) {
            // A team with no alias could never be resolved again; new imports of
            // its spelling would silently mint a duplicate team.
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "A team must keep at least one alias");
        }
        int deleted = db.sql("DELETE FROM team_alias WHERE id = :id AND team_id = :teamId")
                .param("id", aliasId)
                .param("teamId", id)
                .update();
        if (deleted == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such alias");
        }
    }

    /**
     * Absorb {@code sourceTeamId} into {@code id}: every alias and entry
     * repoints, lineage links follow, the source row goes away. This is for
     * spelling-variant duplicates — an entry transfer to a new organization is
     * a predecessor link, not a merge.
     */
    @PostMapping("/{id}/merge")
    @Transactional
    public MergeResult merge(@PathVariable long id, @RequestBody MergeRequest request) {
        long src = request.sourceTeamId();
        if (src == id) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "A team cannot be merged into itself");
        }
        requireTeam(id);
        // Wrapped in a record so a NULL predecessor doesn't read as "no row".
        record SourceRow(Long predecessorId) {
        }
        Long srcPredecessor = db.sql("SELECT predecessor_id FROM team WHERE id = :id")
                .param("id", src)
                .query((rs, i) -> new SourceRow(rs.getObject("predecessor_id", Long.class)))
                .optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such team"))
                .predecessorId();

        // If the target listed the source as its predecessor, the merge absorbs
        // that hop: the source's own predecessor (if any) becomes the target's.
        db.sql("UPDATE team SET predecessor_id = NULL WHERE id = :id AND predecessor_id = :src")
                .param("id", id).param("src", src)
                .update();
        db.sql("UPDATE team SET predecessor_id = :id WHERE predecessor_id = :src")
                .param("id", id).param("src", src)
                .update();
        if (srcPredecessor != null && srcPredecessor != id) {
            db.sql("UPDATE team SET predecessor_id = :pred WHERE id = :id AND predecessor_id IS NULL")
                    .param("pred", srcPredecessor).param("id", id)
                    .update();
        }

        int aliasesMoved = db.sql("UPDATE team_alias SET team_id = :id WHERE team_id = :src")
                .param("id", id).param("src", src)
                .update();
        int entriesMoved = db.sql("UPDATE entry SET team_id = :id WHERE team_id = :src")
                .param("id", id).param("src", src)
                .update();
        db.sql("DELETE FROM team WHERE id = :src").param("src", src).update();
        return new MergeResult(entriesMoved, aliasesMoved);
    }

    @PatchMapping("/{id}")
    @Transactional
    public void update(@PathVariable long id, @RequestBody UpdateRequest request) {
        requireTeam(id);
        if (request.name() != null) {
            String name = request.name().trim();
            if (name.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "Team name cannot be blank");
            }
            Long owner = db.sql("SELECT team_id FROM team_alias WHERE lower(trim(alias)) = lower(:name)")
                    .param("name", name)
                    .query(Long.class)
                    .optional()
                    .orElse(null);
            if (owner != null && owner != id) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "'" + name + "' already resolves to another team");
            }
            db.sql("UPDATE team SET name = :name WHERE id = :id")
                    .param("name", name).param("id", id)
                    .update();
            if (owner == null) {
                // The new display name must resolve back to this team; the old
                // name keeps its alias row, so history still matches.
                db.sql("INSERT INTO team_alias (team_id, alias) VALUES (:id, :name)")
                        .param("id", id).param("name", name)
                        .update();
            }
        }
        if (Boolean.TRUE.equals(request.clearPredecessor())) {
            db.sql("UPDATE team SET predecessor_id = NULL WHERE id = :id").param("id", id).update();
        } else if (request.predecessorId() != null) {
            if (request.predecessorId() == id) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "A team cannot be its own predecessor");
            }
            requireTeam(request.predecessorId());
            db.sql("UPDATE team SET predecessor_id = :pred WHERE id = :id")
                    .param("pred", request.predecessorId()).param("id", id)
                    .update();
        }
    }

    private void requireTeam(long id) {
        db.sql("SELECT 1 FROM team WHERE id = :id")
                .param("id", id)
                .query(Integer.class)
                .optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such team"));
    }
}
