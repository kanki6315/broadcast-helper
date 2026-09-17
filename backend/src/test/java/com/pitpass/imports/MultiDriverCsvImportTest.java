package com.pitpass.imports;

import com.pitpass.imports.ImportService.ImportTarget;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The symptom behind the multi-driver CSV fix, end to end: a 2021 WeatherTech
 * results CSV (DRIVER1_* .. DRIVER6_* blocks) used to commit its entries and
 * results with no driver_assignment rows — a blank Drivers column and races
 * missing from driver stats. Real Mid-Ohio files, staged and committed the
 * way the review screen does it.
 */
@SpringBootTest
@Transactional
class MultiDriverCsvImportTest {

    private static final String RACE = "03_Results_Race_Official.CSV";
    private static final String DPI_QUALIFYING = "03_Results_Qualifying - DPi Position - Points.CSV";

    @Autowired JdbcClient db;
    @Autowired ImportService service;

    private long seedEvent() {
        long seriesId = db.sql("INSERT INTO series (name) VALUES (:n) RETURNING id")
                .param("n", "Multi-driver CSV series " + UUID.randomUUID()).query(Long.class).single();
        long seasonId = db.sql("INSERT INTO season (series_id, year) VALUES (:s, 2099) RETURNING id")
                .param("s", seriesId).query(Long.class).single();
        return db.sql("INSERT INTO event (season_id, name) VALUES (:s, 'Mid-Ohio') RETURNING id")
                .param("s", seasonId).query(Long.class).single();
    }

    private byte[] fixture(String name) throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/fixtures/imsa-2021/" + name)) {
            assertNotNull(in, "missing fixture " + name);
            return in.readAllBytes();
        }
    }

    private void commit(long eventId, String file, String sessionType) throws IOException {
        long id = service.stage(file, fixture(file), ImportFormat.IMSA_CSV).get(0).id();
        service.commit(id, new ImportTarget(null, null, eventId, null, null, null, null, null, null,
                sessionType, 1, null, null, true, null, null, null));
    }

    private long count(String sql, long eventId) {
        return db.sql(sql).param("event", eventId).query(Long.class).single();
    }

    private long assignments(long eventId) {
        return count("""
                SELECT count(*) FROM driver_assignment da JOIN entry e ON e.id = da.entry_id
                WHERE e.event_id = :event AND da.driver_id IS NOT NULL
                """, eventId);
    }

    private List<Map<String, Object>> crew(long eventId, String car) {
        return db.sql("""
                        SELECT da.seat_order, d.first_name, d.surname, d.country, da.rating, da.rating_source
                        FROM entry e
                        JOIN driver_assignment da ON da.entry_id = e.id
                        JOIN driver d ON d.id = da.driver_id
                        WHERE e.event_id = :event AND e.car_number = :car
                        ORDER BY da.seat_order
                        """)
                .param("event", eventId).param("car", car).query().listOfRows();
    }

    @Test
    void raceCsvCommitsEveryCrewMember() throws IOException {
        long eventId = seedEvent();
        commit(eventId, RACE, "RACE");

        assertEquals(25, count("SELECT count(*) FROM entry WHERE event_id = :event", eventId));
        // Two drivers per car, for every car — not one, not none.
        assertEquals(50, assignments(eventId));
        assertEquals(0, count("""
                SELECT count(*) FROM entry e WHERE e.event_id = :event
                  AND (SELECT count(*) FROM driver_assignment da WHERE da.entry_id = e.id) <> 2
                """, eventId));

        List<Map<String, Object>> winners = crew(eventId, "10");
        assertEquals(List.of(1, 2), winners.stream().map(r -> r.get("seat_order")).toList());
        assertEquals(List.of("Taylor", "Albuquerque"), winners.stream().map(r -> r.get("surname")).toList());
        assertEquals("Ricky", winners.get(0).get("first_name"));
        assertEquals("PRT", winners.get(1).get("country"));
        assertEquals(List.of("P", "P"), winners.stream().map(r -> r.get("rating")).toList());
        assertEquals("RESULTS", winners.get(0).get("rating_source"));

        // The file names no fastest-lap driver, so nobody is credited with one.
        Map<String, Object> result = db.sql("""
                        SELECT r.fastest_lap_time, r.fastest_lap_driver_seat
                        FROM result r JOIN entry e ON e.id = r.entry_id
                        WHERE e.event_id = :event AND e.car_number = '10'
                        """)
                .param("event", eventId).query().singleRow();
        assertEquals("1:12.345", result.get("fastest_lap_time"));
        assertNull(result.get("fastest_lap_driver_seat"));
    }

    @Test
    void qualifyingCsvAndReimportKeepTheCrews() throws IOException {
        long eventId = seedEvent();
        commit(eventId, RACE, "RACE");
        commit(eventId, DPI_QUALIFYING, "QUALIFYING");
        // Re-committing the race is the documented backfill for sessions
        // imported before the fix: the crews are replaced, never doubled.
        commit(eventId, RACE, "RACE");

        assertEquals(50, assignments(eventId));
        assertEquals(List.of("Jarvis", "Tincknell"),
                crew(eventId, "55").stream().map(r -> r.get("surname")).toList());
        assertEquals(List.of(1, 2), crew(eventId, "55").stream().map(r -> r.get("seat_order")).toList());
    }
}
