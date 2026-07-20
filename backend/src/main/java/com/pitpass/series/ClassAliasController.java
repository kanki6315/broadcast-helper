package com.pitpass.series;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
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
 * Per-series class-name aliases (see {@code class_alias}, V29): standing
 * source-spelling → canonical-class mappings the importers consult, so a
 * series fed from providers that spell its class differently (iRacing's
 * "[L] Porsche 911" vs "Hosted All Cars") lands in one class without a review
 * mapping every import. The rename operation is the durable cleanup: it fixes
 * the rows that already exist (entries, championships, class styles) and
 * records the retired spelling as an alias so re-imports stay canonical.
 */
@RestController
@RequestMapping("/api/series/{seriesId}")
public class ClassAliasController {

    private final JdbcClient db;

    public ClassAliasController(JdbcClient db) {
        this.db = db;
    }

    public record ClassAlias(long id, String alias, String className) {
    }

    /** classesInUse are the class names currently on the series' entries or
     *  championships — the rename candidates and alias-target options. */
    public record ClassAliasesResponse(List<ClassAlias> aliases, List<String> classesInUse) {
    }

    public record CreateRequest(@NotBlank String alias, @NotBlank String className) {
    }

    public record RenameRequest(@NotBlank String from, @NotBlank String to) {
    }

    public record RenameResult(int entriesRenamed, int championshipsRenamed) {
    }

    @GetMapping("/class-aliases")
    public ClassAliasesResponse list(@PathVariable long seriesId) {
        requireSeries(seriesId);
        List<ClassAlias> aliases = db.sql("""
                        SELECT id, alias, class_name FROM class_alias
                        WHERE series_id = :seriesId ORDER BY class_name, lower(alias)
                        """)
                .param("seriesId", seriesId)
                .query((rs, i) -> new ClassAlias(rs.getLong("id"), rs.getString("alias"),
                        rs.getString("class_name")))
                .list();
        return new ClassAliasesResponse(aliases, classesInUse(seriesId));
    }

    @PostMapping("/class-aliases")
    public ClassAlias create(@PathVariable long seriesId, @Valid @RequestBody CreateRequest request) {
        requireSeries(seriesId);
        String alias = request.alias().trim();
        String className = request.className().trim();
        if (norm(alias).equals(norm(className))) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "An alias that only differs from its class by case or spaces is already matched automatically");
        }
        try {
            long id = db.sql("""
                            INSERT INTO class_alias (series_id, alias, class_name)
                            VALUES (:seriesId, :alias, :className)
                            RETURNING id
                            """)
                    .param("seriesId", seriesId)
                    .param("alias", alias)
                    .param("className", className)
                    .query(Long.class)
                    .single();
            return new ClassAlias(id, alias, className);
        } catch (DuplicateKeyException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "'" + alias + "' is already an alias on this series");
        }
    }

    @DeleteMapping("/class-aliases/{aliasId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long seriesId, @PathVariable long aliasId) {
        int deleted = db.sql("DELETE FROM class_alias WHERE id = :id AND series_id = :seriesId")
                .param("id", aliasId)
                .param("seriesId", seriesId)
                .update();
        if (deleted == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such class alias");
        }
    }

    /**
     * Rename a class across the whole series — every season's entries and
     * championships, plus its class-style row — and record the old spelling as
     * an alias so future imports resolve to the new name by themselves. This is
     * the one write path that touches historical rows: renames and merges
     * (renaming onto an existing class) are the user consolidating spellings,
     * not the importers' business.
     */
    @PostMapping("/classes/rename")
    @Transactional
    public RenameResult rename(@PathVariable long seriesId, @Valid @RequestBody RenameRequest request) {
        requireSeries(seriesId);
        String from = request.from().trim();
        String to = request.to().trim();
        if (from.equals(to)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "The new class name is the same as the old one");
        }
        int entries = db.sql("""
                        UPDATE entry SET class_name = :to
                        WHERE class_name = :from
                          AND event_id IN (SELECT e.id FROM event e
                                               JOIN season s ON s.id = e.season_id
                                           WHERE s.series_id = :seriesId)
                        """)
                .param("to", to).param("from", from).param("seriesId", seriesId)
                .update();
        int championships = db.sql("""
                        UPDATE championship SET class_name = :to
                        WHERE class_name = :from
                          AND season_id IN (SELECT id FROM season WHERE series_id = :seriesId)
                        """)
                .param("to", to).param("from", from).param("seriesId", seriesId)
                .update();
        if (entries == 0 && championships == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "No entries or championships in this series have class '" + from + "'");
        }
        // The sheet styles classes by exact name. Carry the style over unless the
        // target already has one (a merge keeps the survivor's style).
        Integer toStyled = db.sql("""
                        SELECT 1 FROM class_style WHERE series_id = :seriesId AND class_code = :to
                        """)
                .param("seriesId", seriesId).param("to", to)
                .query(Integer.class).optional().orElse(null);
        if (toStyled == null) {
            db.sql("UPDATE class_style SET class_code = :to WHERE series_id = :seriesId AND class_code = :from")
                    .param("to", to).param("seriesId", seriesId).param("from", from)
                    .update();
        } else {
            db.sql("DELETE FROM class_style WHERE series_id = :seriesId AND class_code = :from")
                    .param("seriesId", seriesId).param("from", from)
                    .update();
        }
        // Aliases that resolved to the old name must follow it, or they'd
        // reintroduce a spelling this rename just retired.
        db.sql("UPDATE class_alias SET class_name = :to WHERE series_id = :seriesId AND class_name = :from")
                .param("to", to).param("seriesId", seriesId).param("from", from)
                .update();
        // Record the retired spelling — the point of renaming over raw SQL —
        // unless it only differs by case/spaces (auto-matched anyway).
        if (!norm(from).equals(norm(to))) {
            db.sql("""
                            INSERT INTO class_alias (series_id, alias, class_name)
                            VALUES (:seriesId, :from, :to)
                            ON CONFLICT (series_id, lower(alias)) DO UPDATE SET class_name = EXCLUDED.class_name
                            """)
                    .param("seriesId", seriesId).param("from", from).param("to", to)
                    .update();
        }
        return new RenameResult(entries, championships);
    }

    private List<String> classesInUse(long seriesId) {
        return db.sql("""
                        SELECT DISTINCT class_name FROM (
                            SELECT en.class_name
                            FROM entry en
                                     JOIN event e ON e.id = en.event_id
                                     JOIN season s ON s.id = e.season_id
                            WHERE s.series_id = :seriesId
                            UNION
                            SELECT c.class_name
                            FROM championship c
                                     JOIN season s ON s.id = c.season_id
                            WHERE s.series_id = :seriesId
                        ) x
                        WHERE class_name IS NOT NULL
                        ORDER BY class_name
                        """)
                .param("seriesId", seriesId)
                .query(String.class)
                .list();
    }

    /** Case/space-insensitive comparison key, mirroring the importer's normClass. */
    private static String norm(String s) {
        return s.toLowerCase().replace(" ", "");
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
