package com.pitpass.imports;

import java.time.LocalDate;
import java.util.List;

/**
 * Normalized form of a pre-event entry list, produced by the Python parser
 * sidecar (parser/parse_entry_list.py) as the entries.json contract — see
 * parser/SCHEMA.md. The Java side owns mapping to domain tables, dedup, and
 * persistence; the parser owns nothing but PDF -> JSON.
 */
public record EntryListImport(Event event, List<Entry> entries) {

    public record Event(
            String name,
            String circuit,
            String location,
            String series,
            LocalDate startDate,
            LocalDate endDate,
            Integer totalEntries,
            String sourceFile
    ) {
    }

    public record Entry(
            String className,
            String classCode,
            Integer classOrder,
            String carNumber,
            String team,
            String sponsor,
            String teamNationality,
            boolean bronzeCup,
            boolean dealerTrophy,
            String carType,
            String tire,
            String engine,
            String fuel,
            List<Driver> drivers
    ) {
    }

    public record Driver(
            int order,
            String rating,
            String name,
            String nationality,
            String hometown,
            List<String> markers,
            boolean isTbd,
            boolean unparsed
    ) {
    }
}
