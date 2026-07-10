package com.broadcasthelper.series;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * Per-series class display config for the sheet: the order classes appear in and
 * the header colour (see {@code class_style}, seeded in V15). The sheet reads
 * these; this endpoint lets the user edit them instead of hand-writing SQL, so a
 * new series renders correctly with no code or migration change.
 *
 * <p>A class code must match the entry list's {@code class_name} exactly — the
 * sheet looks classes up by exact string — so the GET also returns class codes
 * seen in the series' entries that have no style yet, as ready-to-use options.
 */
@RestController
@RequestMapping("/api/series/{seriesId}/class-styles")
public class ClassStyleController {

    private final JdbcClient db;

    public ClassStyleController(JdbcClient db) {
        this.db = db;
    }

    public record ClassStyle(String classCode, int ordinal, String color) {
    }

    public record ClassStylesResponse(List<ClassStyle> styles, List<String> unconfiguredClasses) {
    }

    public record UpsertRequest(@NotNull Integer ordinal,
                                @Pattern(regexp = "#[0-9a-fA-F]{6}", message = "color must be a #rrggbb hex string")
                                String color) {
    }

    @GetMapping
    public ClassStylesResponse list(@PathVariable long seriesId) {
        requireSeries(seriesId);
        List<ClassStyle> styles = db.sql("""
                        SELECT class_code, ordinal, color FROM class_style
                        WHERE series_id = :seriesId ORDER BY ordinal, class_code
                        """)
                .param("seriesId", seriesId)
                .query((rs, i) -> new ClassStyle(rs.getString("class_code"), rs.getInt("ordinal"),
                        rs.getString("color")))
                .list();
        // Classes seen in this series' entries but not yet styled — offered as
        // options so codes carry the exact casing the sheet matches on.
        List<String> unconfigured = db.sql("""
                        SELECT DISTINCT en.class_name
                        FROM entry en JOIN event e ON e.id = en.event_id JOIN season s ON s.id = e.season_id
                        WHERE s.series_id = :seriesId
                          AND en.class_name NOT IN (SELECT class_code FROM class_style WHERE series_id = :seriesId)
                        ORDER BY en.class_name
                        """)
                .param("seriesId", seriesId)
                .query(String.class)
                .list();
        return new ClassStylesResponse(styles, unconfigured);
    }

    @PutMapping("/{classCode}")
    public ClassStyle upsert(@PathVariable long seriesId, @PathVariable String classCode,
                             @Valid @RequestBody UpsertRequest request) {
        requireSeries(seriesId);
        String code = classCode.trim();
        if (code.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "class code must not be blank");
        }
        String color = request.color() == null ? "#1a1a1a" : request.color().toLowerCase();
        db.sql("""
                        INSERT INTO class_style (series_id, class_code, ordinal, color)
                        VALUES (:seriesId, :classCode, :ordinal, :color)
                        ON CONFLICT (series_id, class_code)
                        DO UPDATE SET ordinal = EXCLUDED.ordinal, color = EXCLUDED.color
                        """)
                .param("seriesId", seriesId)
                .param("classCode", code)
                .param("ordinal", request.ordinal())
                .param("color", color)
                .update();
        return new ClassStyle(code, request.ordinal(), color);
    }

    @DeleteMapping("/{classCode}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long seriesId, @PathVariable String classCode) {
        int deleted = db.sql("DELETE FROM class_style WHERE series_id = :seriesId AND class_code = :classCode")
                .param("seriesId", seriesId)
                .param("classCode", classCode.trim())
                .update();
        if (deleted == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such class style");
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
