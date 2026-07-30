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
 * The season's car-number aliases (see {@code car_number_alias}, V38): the
 * rare entrant that raced under a second number — a one-off renumbering
 * (JDC-Miller's #5 running Daytona as #85) or a mid-season entry transfer to
 * a new organization (van der Steur's #19 GTD becoming Car Blanche's #068).
 * The alias maps the other number onto the one the standings source keys the
 * row by, scoped to this season and one class; the recap and the sheet's
 * championship column resolve through it, per-event surfaces never do.
 */
@RestController
@RequestMapping("/api/seasons/{seasonId}/car-number-aliases")
public class CarNumberAliasController {

    private final JdbcClient db;

    public CarNumberAliasController(JdbcClient db) {
        this.db = db;
    }

    public record CarNumberAlias(long id, String className, String carNumber,
                                 String canonicalNumber, String note) {
    }

    public record CreateRequest(@NotBlank String className, @NotBlank String carNumber,
                                @NotBlank String canonicalNumber, String note) {
    }

    @GetMapping
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

    @PostMapping
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

    @DeleteMapping("/{aliasId}")
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
}
