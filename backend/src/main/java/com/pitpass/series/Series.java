package com.pitpass.series;

import java.time.OffsetDateTime;

public class Series {

    private Long id;

    private String name;

    private String abbreviation;

    private OffsetDateTime createdAt;

    public Series(Long id, String name, String abbreviation, OffsetDateTime createdAt) {
        this.id = id;
        this.name = name;
        this.abbreviation = abbreviation;
        this.createdAt = createdAt;
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

    public void updateIdentity(String name, String abbreviation) {
        this.name = name;
        this.abbreviation = abbreviation;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
