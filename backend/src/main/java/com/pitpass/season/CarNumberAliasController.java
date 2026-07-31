package com.pitpass.season;

import com.pitpass.sheets.SheetController;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * Car-number aliases (see {@code car_number_alias}, V38): the rare entrant
 * that raced under two numbers — a one-off renumbering (JDC-Miller's #5
 * running Daytona as #85), a mid-season entry transfer to a new organization
 * (van der Steur's #19 GTD becoming Car Blanche's #068), or a permanent
 * renumbering (LAP Motorsports' #30 becoming #6). The link is SYMMETRIC:
 * consumers (the recap and the sheet's championship column) resolve both the
 * entry's number and the standings key through it, so either stored
 * direction matches, and a later standings import that changes which of the
 * two numbers keys the row needs no relink. Scoped to one season and one
 * class; per-event surfaces never resolve. Managed from the Series settings
 * modal (Classes tab), which reads the series-wide listing; writes stay
 * season-scoped.
 */
@RestController
@RequestMapping("/api")
public class CarNumberAliasController {

    private final JdbcClient db;

    public CarNumberAliasController(JdbcClient db) {
        this.db = db;
    }

    public record CarNumberAlias(long id, String className, String carNumber,
                                 String canonicalNumber, String note) {
    }

    /** One row of the series-wide listing: the alias plus which year it
     *  belongs to, so the modal can group by season. */
    public record SeriesCarNumberAlias(long id, long seasonId, int year, String className,
                                       String carNumber, String canonicalNumber, String note) {
    }

    /** classesInUse mirrors ClassAliasController's list — the class picker's
     *  options, so a link can only target a class the series has seen. */
    public record SeriesCarNumberAliases(List<SeriesCarNumberAlias> aliases,
                                         List<String> classesInUse) {
    }

    public record CreateRequest(@NotBlank String className, @NotBlank String carNumber,
                                @NotBlank String canonicalNumber, String note) {
    }

    @GetMapping("/seasons/{seasonId}/car-number-aliases")
    public List<CarNumberAlias> list(@PathVariable long seasonId) {
        requireSeason(seasonId);
        return db.sql("""
                        SELECT id, class_name, car_number, canonical_number, note
                        FROM car_number_alias
                        WHERE season_id = :seasonId
                        ORDER BY lower(class_name), canonical_number, car_number
                        """)
                .param("seasonId", seasonId)
                .query((rs, i) -> new CarNumberAlias(rs.getLong("id"), rs.getString("class_name"),
                        rs.getString("car_number"), rs.getString("canonical_number"),
                        rs.getString("note")))
                .list();
    }

    @GetMapping("/series/{seriesId}/car-number-aliases")
    public SeriesCarNumberAliases listForSeries(@PathVariable long seriesId) {
        requireSeries(seriesId);
        List<SeriesCarNumberAlias> aliases = db.sql("""
                        SELECT cna.id, cna.season_id, s.year, cna.class_name,
                               cna.car_number, cna.canonical_number, cna.note
                        FROM car_number_alias cna
                                 JOIN season s ON s.id = cna.season_id
                        WHERE s.series_id = :seriesId
                        ORDER BY s.year DESC, lower(cna.class_name), cna.canonical_number, cna.car_number
                        """)
                .param("seriesId", seriesId)
                .query((rs, i) -> new SeriesCarNumberAlias(rs.getLong("id"), rs.getLong("season_id"),
                        rs.getInt("year"), rs.getString("class_name"), rs.getString("car_number"),
                        rs.getString("canonical_number"), rs.getString("note")))
                .list();
        List<String> classesInUse = db.sql("""
                        SELECT DISTINCT en.class_name
                        FROM entry en
                                 JOIN event e ON e.id = en.event_id
                                 JOIN season s ON s.id = e.season_id
                        WHERE s.series_id = :seriesId AND en.class_name IS NOT NULL
                        ORDER BY en.class_name
                        """)
                .param("seriesId", seriesId)
                .query(String.class)
                .list();
        return new SeriesCarNumberAliases(aliases, classesInUse);
    }

    @PostMapping("/seasons/{seasonId}/car-number-aliases")
    public CarNumberAlias create(@PathVariable long seasonId, @Valid @RequestBody CreateRequest request) {
        requireSeason(seasonId);
        String className = request.className().trim();
        String carNumber = request.carNumber().trim();
        String canonical = request.canonicalNumber().trim();
        String note = request.note() == null || request.note().isBlank() ? null : request.note().trim();
        if (SheetController.normalizeCarNumber(carNumber)
                .equals(SheetController.normalizeCarNumber(canonical))) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "A number that only differs from its canonical number by leading zeros is already matched automatically");
        }
        try {
            long id = db.sql("""
                            INSERT INTO car_number_alias (season_id, class_name, car_number, canonical_number, note)
                            VALUES (:seasonId, :className, :carNumber, :canonical, :note)
                            RETURNING id
                            """)
                    .param("seasonId", seasonId)
                    .param("className", className)
                    .param("carNumber", carNumber)
                    .param("canonical", canonical)
                    .param("note", note)
                    .query(Long.class)
                    .single();
            return new CarNumberAlias(id, className, carNumber, canonical, note);
        } catch (DuplicateKeyException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "#" + carNumber + " is already linked to a number in " + className + " this season");
        }
    }

    @DeleteMapping("/seasons/{seasonId}/car-number-aliases/{aliasId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long seasonId, @PathVariable long aliasId) {
        int deleted = db.sql("DELETE FROM car_number_alias WHERE id = :id AND season_id = :seasonId")
                .param("id", aliasId)
                .param("seasonId", seasonId)
                .update();
        if (deleted == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such car-number alias");
        }
    }

    private void requireSeason(long seasonId) {
        Integer found = db.sql("SELECT 1 FROM season WHERE id = :id")
                .param("id", seasonId)
                .query(Integer.class)
                .optional()
                .orElse(null);
        if (found == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such season");
        }
    }

    private void requireSeries(long seriesId) {
        Integer found = db.sql("SELECT 1 FROM series WHERE id = :id")
                .param("id", seriesId)
                .query(Integer.class)
                .optional()
                .orElse(null);
        if (found == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such series");
        }
    }
}
