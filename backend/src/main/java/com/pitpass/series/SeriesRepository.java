package com.pitpass.series;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SeriesRepository extends JpaRepository<Series, Long> {

    boolean existsByNameIgnoreCase(String name);
}
