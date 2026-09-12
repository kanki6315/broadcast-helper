package com.pitpass.series;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public class SeriesRepository {

    private static final RowMapper<Series> ROW = (rs, i) -> new Series(
            rs.getLong("id"), rs.getString("name"), rs.getString("abbreviation"),
            rs.getObject("created_at", OffsetDateTime.class));

    private final JdbcClient db;

    public SeriesRepository(JdbcClient db) {
        this.db = db;
    }

    public List<Series> findAllByOrderByName() {
        return db.sql("SELECT id, name, abbreviation, created_at FROM series ORDER BY name")
                .query(ROW).list();
    }

    public Optional<Series> findById(long id) {
        return db.sql("SELECT id, name, abbreviation, created_at FROM series WHERE id = :id")
                .param("id", id).query(ROW).optional();
    }

    public boolean existsByNameIgnoreCase(String name) {
        return db.sql("SELECT EXISTS (SELECT 1 FROM series WHERE upper(name) = upper(:name))")
                .param("name", name).query(Boolean.class).single();
    }

    public Series save(Series series) {
        if (series.getId() == null) {
            return db.sql("""
                            INSERT INTO series (name, abbreviation, created_at)
                            VALUES (:name, :abbreviation, :createdAt)
                            RETURNING id, name, abbreviation, created_at
                            """)
                    .param("name", series.getName()).param("abbreviation", series.getAbbreviation())
                    .param("createdAt", series.getCreatedAt()).query(ROW).single();
        }
        return db.sql("""
                        UPDATE series SET name = :name, abbreviation = :abbreviation WHERE id = :id
                        RETURNING id, name, abbreviation, created_at
                        """)
                .param("id", series.getId()).param("name", series.getName())
                .param("abbreviation", series.getAbbreviation()).query(ROW).single();
    }
}
