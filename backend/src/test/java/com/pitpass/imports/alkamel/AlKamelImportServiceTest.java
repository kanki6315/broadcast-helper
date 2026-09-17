package com.pitpass.imports.alkamel;

import com.pitpass.imports.ImportService;
import com.pitpass.imports.alkamel.AlKamelImportService.AlKamelImport;
import com.pitpass.imports.alkamel.AlKamelImportService.FileRef;
import com.pitpass.imports.alkamel.AlKamelImportService.PlanFile;
import com.pitpass.imports.alkamel.AlKamelImportService.PlanSession;
import com.pitpass.imports.alkamel.AlKamelImportService.PlanWeekend;
import com.pitpass.imports.alkamel.AlKamelImportService.StageRequest;
import com.pitpass.imports.alkamel.AlKamelImportService.YearPlan;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The season planner and the weekend stager against the recorded 2017
 * listings, trimmed to two weekends (Daytona, Long Beach). Runs against the
 * local dev Postgres like the other @SpringBootTest classes; every write rolls
 * back. The site is the fixture stub, so nothing here touches the network.
 */
@SpringBootTest
@Transactional
class AlKamelImportServiceTest {

    private static final String WTSC = "IMSA WeatherTech SportsCar Championship";
    private static final String LB = "17_2017/06_Long Beach Street Circuit";
    private static final String LB_WTSC = "17_2017/06_Long%20Beach%20Street%20Circuit/"
            + "01_IMSA%20WeatherTech%20SportsCar%20Championship/";

    @Autowired JdbcClient db;
    @Autowired ImportService imports;

    private FixtureSite site;
    private AlKamelImportService service;

    @BeforeEach
    void start() throws IOException {
        site = new FixtureSite();
        // A two-weekend 2017: only the rows for Daytona and Long Beach survive.
        String year = FixtureSite.text("year-2017.html");
        String trimmed = year.lines()
                .filter(l -> !l.startsWith("<tr><td valign=\"top\"><img src=\"/icons/folder.gif\"")
                        || l.contains("02_Daytona") || l.contains("06_Long"))
                .collect(Collectors.joining("\n"));
        site.put("17_2017/", trimmed);
        AlKamelClient client = site.client();
        service = new AlKamelImportService(new AlKamelIndex(client), client, imports, db);
    }

    @AfterEach
    void stop() {
        site.close();
    }

    /** The series the folder name resolves to — reused if the dev DB already
     *  records it (its name and aliases are unique), created otherwise. */
    private long weatherTech() {
        return service.resolveSeries(WTSC).map(s -> s.id()).orElseGet(() ->
                db.sql("INSERT INTO series (name) VALUES (:n) RETURNING id").param("n", WTSC)
                        .query(Long.class).single());
    }

    @Test
    void plansASeasonWeekendBySeriesAndReportsUnmatchedFolders() {
        long seriesId = weatherTech();
        YearPlan plan = service.planYear(2017, null);

        assertEquals(List.of("IMSA Continental Tire SportsCar Challenge"), plan.unmatchedSeriesFolders());
        assertEquals(List.of("Daytona International Speedway", "Long Beach Street Circuit"),
                plan.weekends().stream().map(PlanWeekend::eventName).toList());
        PlanWeekend daytona = plan.weekends().get(0);
        PlanWeekend longBeach = plan.weekends().get(1);
        assertEquals(seriesId, daytona.seriesId());
        assertEquals(WTSC, daytona.seriesName());
        assertEquals("17_2017/02_Daytona International Speedway", daytona.sourceEvent());
        assertEquals(LB, longBeach.sourceEvent());
        assertNull(daytona.error());
        assertFalse(daytona.loose());
        assertFalse(daytona.f1Weekend());
        assertNull(daytona.existingEventId());

        // Long Beach: qualifying + race, CSV results, PDF grid, no flags.
        assertEquals(List.of("Qualifying", "Race"), longBeach.sessions().stream().map(PlanSession::label).toList());
        PlanSession race = longBeach.sessions().get(1);
        assertEquals(LocalDateTime.of(2017, 4, 8, 13, 5), race.start());
        assertEquals("RACE", race.type());
        assertEquals("05_Results.CSV", race.results().name());
        assertEquals("IMSA_CSV", race.results().format());
        assertEquals("01_Starting Grid.PDF", race.grid().name());
        assertEquals("IMSA_GRID_PDF", race.grid().format());
        assertNull(race.flags());
        assertNull(longBeach.sessions().get(0).grid(), "qualifying has no grid");
        assertNull(longBeach.entryList());

        // Daytona: results from the last hour folder, the unmarked grid, the
        // pre-race entry list, and two standings sheets (the points and the
        // endurance-cup points), each recommended once — not the award.
        PlanSession rolex = daytona.sessions().get(1);
        assertEquals("05_Results by Hour.CSV", rolex.results().name());
        assertEquals("03_Starting Grid.PDF", rolex.grid().name());
        assertEquals("02_Pre-Race Entry List.pdf", daytona.entryList().name());
        assertEquals("IMSA_PDF", daytona.entryList().format());
        assertEquals(List.of("00_Championship Points - Revised Official.pdf", "02_TPNAEC Points - Official.pdf"),
                daytona.standings().stream().filter(PlanFile::recommended).map(PlanFile::name).toList());
        assertTrue(daytona.standings().stream().allMatch(f -> "IMSA_POINTS_PDF".equals(f.format())));

        // Final standings: the later weekend of the two.
        assertFalse(daytona.finalStandings());
        assertTrue(longBeach.finalStandings());
        assertEquals(1, longBeach.standings().size());
    }

    @Test
    void singleSeriesPlansOnlyThatSeriesAndKnowsImportedWeekends() {
        long seriesId = weatherTech();
        long seasonId = db.sql("INSERT INTO season (series_id, year) VALUES (:s, 2017) RETURNING id")
                .param("s", seriesId).query(Long.class).single();
        db.sql("INSERT INTO event (season_id, name, source_ref) VALUES (:s, 'Long Beach', :ref)")
                .param("s", seasonId).param("ref", LB).update();

        YearPlan plan = service.planYear(2017, seriesId);
        assertTrue(plan.unmatchedSeriesFolders().isEmpty(), "unmatched folders are an all-series concern");
        assertEquals(2, plan.weekends().size());
        assertNotNull(plan.weekends().get(1).existingEventId());
        assertNull(plan.weekends().get(0).existingEventId());

        long other = db.sql("INSERT INTO series (name) VALUES ('Other series for plan test') RETURNING id")
                .query(Long.class).single();
        assertTrue(service.planYear(2017, other).weekends().isEmpty());
    }

    @Test
    void stagesAWeekendsFilesInOrderAndReportsTheOnesThatFail() throws IOException {
        long seriesId = weatherTech();
        String quali = LB_WTSC + "201704071720_Qualifying/03_Results.CSV";
        String race = LB_WTSC + "201704081305_Race/05_Results.CSV";
        site.put(quali, fixture("03_Results_Qualifying - GTD Position.CSV"));
        site.put(race, fixture("03_Results_Race_Official.CSV"));

        AlKamelImport result = service.stage(new StageRequest(2017, LB, "Long Beach Street Circuit", seriesId, List.of(
                new FileRef(race, "RESULTS", "IMSA_CSV", "2017-04-08 20:00", LocalDateTime.of(2017, 4, 8, 13, 5), "Race"),
                new FileRef(LB_WTSC + "201704081305_Race/01_Starting%20Grid.PDF", "GRID", "IMSA_GRID_PDF", null,
                        LocalDateTime.of(2017, 4, 8, 13, 5), "Race"),
                new FileRef(quali, "RESULTS", "IMSA_CSV", "2017-04-07 19:00", LocalDateTime.of(2017, 4, 7, 17, 20),
                        "Qualifying"))));

        assertEquals(3, result.requested());
        assertEquals(2, result.staged());
        assertEquals(1, result.failures().size());
        assertEquals("01_Starting Grid.PDF", result.failures().get(0).name());
        assertTrue(result.failures().get(0).reason().contains("404"), result.failures().get(0).reason());
        // Qualifying before the race, whatever order the request came in.
        assertEquals(List.of("RACE_RESULTS", "RACE_RESULTS"), result.batches().stream().map(b -> b.kind()).toList());
        assertEquals("06_Long Beach Street Circuit/01_IMSA WeatherTech SportsCar Championship/"
                + "201704071720_Qualifying/03_Results.CSV", result.batches().get(0).filename());
        assertEquals(LB, result.batches().get(0).sourceEvent());
        assertEquals(site.baseUrl() + quali, result.batches().get(0).sourceUrl());
        ImportService.ImportReview review = imports.reviewTarget(result.batches().get(0).id(), null, null, null);
        assertFalse(review.needsSession());
        assertEquals(seriesId, review.guess().seriesId());
        assertEquals("Long Beach Street Circuit", review.guess().eventName());
        assertEquals("2017-04-07", review.guess().eventDate());
    }

    private static byte[] fixture(String name) throws IOException {
        try (InputStream in = AlKamelImportServiceTest.class.getClassLoader()
                .getResourceAsStream("fixtures/imsa-2021/" + name)) {
            if (in == null) {
                throw new IOException("Missing fixture " + name);
            }
            return in.readAllBytes();
        }
    }
}
