package com.broadcasthelper.series;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

@Entity
@Table(name = "series")
public class Series {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;

    private String abbreviation;

    private OffsetDateTime createdAt;

    protected Series() {
        // for JPA
    }

    public Series(String name, String abbreviation) {
        this.name = name;
        this.abbreviation = abbreviation;
        this.createdAt = OffsetDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getAbbreviation() {
        return abbreviation;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
