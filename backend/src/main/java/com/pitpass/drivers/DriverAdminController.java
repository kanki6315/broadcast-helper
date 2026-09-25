package com.pitpass.drivers;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Consolidation of duplicate drivers — the same human imported under two
 * spellings (an iRacing display name with a trailing digit, a typo, a source
 * that drops accents). Name is the only driver identity, so nothing merges
 * these automatically; the broadcaster confirms each one. The surviving row
 * keeps its own name and the absorbed spelling becomes a {@code driver_alias},
 * so re-importing it lands on the survivor instead of re-creating the
 * duplicate. The repointing follows V37's case-twin merge, which touched the
 * same six references.
 */
@RestController
@RequestMapping("/api/drivers")
public class DriverAdminController {

    private final JdbcClient db;

    public DriverAdminController(JdbcClient db) {
        this.db = db;
    }

    public record MergeRequest(long sourceDriverId) {
    }

    public record MergeResult(int seatsMoved, int seatsDropped, int aliasesMoved) {
    }

    /**
     * Absorb {@code sourceDriverId} into {@code id}: seats, grid attribution,
     * season team assignments, privateer teams, photo and aliases repoint; bio
     * fields the target lacks are filled from the source; the source's name is
     * kept as an alias; the source row goes away.
     */
    @PostMapping("/{id}/merge")
    @Transactional
    public MergeResult merge(@PathVariable long id, @RequestBody MergeRequest request) {
        long src = request.sourceDriverId();
        if (src == id) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "A driver cannot be merged into itself");
        }
        String targetName = requireDriver(id);
        String sourceName = requireDriver(src);

        db.sql("""
                        UPDATE driver k
                        SET country        = COALESCE(k.country, d.country),
                            hometown       = COALESCE(k.hometown, d.hometown),
                            date_of_birth  = COALESCE(k.date_of_birth, d.date_of_birth),
                            place_of_birth = COALESCE(k.place_of_birth, d.place_of_birth),
                            pronunciation  = COALESCE(k.pronunciation, d.pronunciation),
                            notes          = COALESCE(k.notes, d.notes)
                        FROM driver d
                        WHERE k.id = :id AND d.id = :src
                        """)
                .param("id", id).param("src", src)
                .update();

        // One photo per driver: the target's wins, the source's fills a gap.
        db.sql("""
                        DELETE FROM driver_photo
                        WHERE driver_id = :src
                          AND EXISTS (SELECT 1 FROM driver_photo WHERE driver_id = :id)
                        """)
                .param("id", id).param("src", src)
                .update();
        db.sql("UPDATE driver_photo SET driver_id = :id WHERE driver_id = :src")
                .param("id", id).param("src", src)
                .update();

        // Both on one entry is the same human listed twice: drop the source's seat.
        int seatsDropped = db.sql("""
                        DELETE FROM driver_assignment da
                        WHERE da.driver_id = :src
                          AND EXISTS (SELECT 1 FROM driver_assignment ka
                                      WHERE ka.entry_id = da.entry_id AND ka.driver_id = :id)
                        """)
                .param("id", id).param("src", src)
                .update();
        int seatsMoved = db.sql("UPDATE driver_assignment SET driver_id = :id WHERE driver_id = :src")
                .param("id", id).param("src", src)
                .update();

        db.sql("UPDATE grid_position SET qualifying_driver_id = :id WHERE qualifying_driver_id = :src")
                .param("id", id).param("src", src)
                .update();
        db.sql("UPDATE grid_position SET starting_driver_id = :id WHERE starting_driver_id = :src")
                .param("id", id).param("src", src)
                .update();

        // UNIQUE (season_id, driver_id, effective_from_round): the target's row wins.
        db.sql("""
                        DELETE FROM season_driver_team_assignment s
                        WHERE s.driver_id = :src
                          AND EXISTS (SELECT 1 FROM season_driver_team_assignment ks
                                      WHERE ks.season_id = s.season_id
                                        AND ks.driver_id = :id
                                        AND ks.effective_from_round = s.effective_from_round)
                        """)
                .param("id", id).param("src", src)
                .update();
        db.sql("UPDATE season_driver_team_assignment SET driver_id = :id WHERE driver_id = :src")
                .param("id", id).param("src", src)
                .update();

        // Privateer placeholder teams are unique per (season, driver): where
        // both have one in a season, fold the source's into the target's first.
        record TeamPair(long sourceTeam, long targetTeam) {
        }
        var pairs = db.sql("""
                        SELECT dt.id AS source_team, kt.id AS target_team
                        FROM season_team dt
                                 JOIN season_team kt ON kt.season_id = dt.season_id
                                                    AND kt.privateer_driver_id = :id
                        WHERE dt.privateer_driver_id = :src
                        """)
                .param("id", id).param("src", src)
                .query((rs, i) -> new TeamPair(rs.getLong("source_team"), rs.getLong("target_team")))
                .list();
        for (TeamPair p : pairs) {
            db.sql("UPDATE entry SET season_team_id = :keep WHERE season_team_id = :drop")
                    .param("keep", p.targetTeam()).param("drop", p.sourceTeam())
                    .update();
            db.sql("UPDATE season_driver_team_assignment SET team_id = :keep WHERE team_id = :drop")
                    .param("keep", p.targetTeam()).param("drop", p.sourceTeam())
                    .update();
            db.sql("DELETE FROM season_team WHERE id = :drop")
                    .param("drop", p.sourceTeam())
                    .update();
        }
        db.sql("UPDATE season_team SET privateer_driver_id = :id WHERE privateer_driver_id = :src")
                .param("id", id).param("src", src)
                .update();

        int aliasesMoved = db.sql("UPDATE driver_alias SET driver_id = :id WHERE driver_id = :src")
                .param("id", id).param("src", src)
                .update();
        db.sql("DELETE FROM driver WHERE id = :src").param("src", src).update();
        // A spelling that only differs from the target's own name by case or
        // spacing already resolves to it by name; anything else needs the alias.
        if (!normalize(sourceName).equals(normalize(targetName))) {
            db.sql("INSERT INTO driver_alias (driver_id, alias) VALUES (:id, :alias)")
                    .param("id", id).param("alias", sourceName)
                    .update();
        }
        return new MergeResult(seatsMoved, seatsDropped, aliasesMoved);
    }

    /**
     * Make {@code driverId} answer to {@code preferredName}, the spelling a
     * more authoritative source uses. When another driver already carries that
     * name (or it is another driver's alias) the two are the same person and
     * {@code driverId} merges into that one; otherwise the driver is renamed,
     * its old spelling kept as an alias so the source that used it still
     * resolves here. Returns the surviving driver's id.
     */
    @Transactional
    public long consolidate(long driverId, String preferredName) {
        String current = requireDriver(driverId);
        String wanted = preferredName == null ? "" : preferredName.trim().replaceAll("\\s+", " ");
        if (wanted.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Driver name cannot be blank");
        }
        if (normalize(current).equals(normalize(wanted))) {
            return driverId;
        }
        Long owner = db.sql("""
                        SELECT id FROM driver
                        WHERE lower(regexp_replace(trim(first_name || ' ' || surname), '\\s+', ' ', 'g')) = :name
                          AND id <> :id
                        UNION ALL
                        SELECT driver_id FROM driver_alias
                        WHERE lower(regexp_replace(trim(alias), '\\s+', ' ', 'g')) = :name
                          AND driver_id <> :id
                        LIMIT 1
                        """)
                .param("name", normalize(wanted))
                .param("id", driverId)
                .query(Long.class)
                .optional()
                .orElse(null);
        if (owner != null) {
            merge(owner, new MergeRequest(driverId));
            return owner;
        }
        // Taking back a spelling this driver once had: it stops being an alias.
        db.sql("""
                        DELETE FROM driver_alias
                        WHERE driver_id = :id AND lower(regexp_replace(trim(alias), '\\s+', ' ', 'g')) = :name
                        """)
                .param("id", driverId).param("name", normalize(wanted))
                .update();
        // Surname is the last word, as the iRacing parser splits display names.
        int cut = wanted.lastIndexOf(' ');
        db.sql("UPDATE driver SET first_name = :first, surname = :surname WHERE id = :id")
                .param("first", cut > 0 ? wanted.substring(0, cut) : wanted)
                .param("surname", cut > 0 ? wanted.substring(cut + 1) : "")
                .param("id", driverId)
                .update();
        db.sql("INSERT INTO driver_alias (driver_id, alias) VALUES (:id, :alias)")
                .param("id", driverId).param("alias", current)
                .update();
        return driverId;
    }

    /** Same normalisation as the driver_alias_key index and the importer's whole-name match. */
    static String normalize(String name) {
        return name.trim().replaceAll("\\s+", " ").toLowerCase(java.util.Locale.ROOT);
    }

    private String requireDriver(long id) {
        return db.sql("SELECT first_name || ' ' || surname FROM driver WHERE id = :id")
                .param("id", id)
                .query(String.class)
                .optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such driver"));
    }
}
