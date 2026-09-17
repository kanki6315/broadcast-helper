package com.pitpass.imports;

import com.pitpass.browse.SeasonStatsController;
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
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 2021 IMSA WeatherTech at Mid-Ohio qualified in four class-split sessions, two
 * of them for GTD alone ("GTD Position" set the grid, "GTD Points" only scored
 * points). Every session used to land on (event, QUALIFYING, 1), each commit
 * wiping the last. Real CSVs, committed the way the review screen does it (the
 * reviewer's ordinal box left at its default of 1).
 */
@SpringBootTest
@Transactional
class SplitQualifyingImportTest {

    private static final List<String> FILES = List.of(
            "03_Results_Qualifying - GTD Position.CSV",
            "03_Results_Qualifying - GTD Points.CSV",
            "03_Results_Qualifying - LMP3 Position - Points.CSV",
            "03_Results_Qualifying - DPi Position - Points.CSV");

    @Autowired JdbcClient db;
    @Autowired ImportService service;
    @Autowired SeasonStatsController stats;

    private record Seeded(long seasonId, long eventId) {
    }

    private Seeded seed() {
        long seriesId = db.sql("INSERT INTO series (name) VALUES (:n) RETURNING id")
                .param("n", "Split quali series " + UUID.randomUUID()).query(Long.class).single();
        long seasonId = db.sql("INSERT INTO season (series_id, year) VALUES (:s, 2099) RETURNING id")
                .param("s", seriesId).query(Long.class).single();
        long eventId = db.sql("INSERT INTO event (season_id, name) VALUES (:s, 'Mid-Ohio') RETURNING id")
                .param("s", seasonId).query(Long.class).single();
        // The season's classes, as its entry list would have seeded them.
        for (String cls : List.of("GTD", "LMP3", "DPi", "Pro")) {
            db.sql("""
                            INSERT INTO entry (event_id, car_number, class_name, team_name)
                            VALUES (:event, :number, :cls, 'Entry list')
                            """)
                    .param("event", eventId).param("number", "9" + cls.length() + cls.charAt(0))
                    .param("cls", cls).update();
        }
        return new Seeded(seasonId, eventId);
    }

    private byte[] fixture(String path) throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/fixtures/" + path)) {
            return in.readAllBytes();
        }
    }

    private long stage(String filename, byte[] content) {
        return service.stage(filename, content, ImportFormat.IMSA_CSV).get(0).id();
    }

    private static ImportTarget target(long eventId, String sessionType, int ordinal) {
        return new ImportTarget(null, null, eventId, null, null, null, null, null, null,
                sessionType, ordinal, null, null, true, null, null, null);
    }

    private void commitAll(long eventId) throws IOException {
        for (String f : FILES) {
            long id = stage(f, fixture("imsa-2021/" + f));
            service.commit(id, target(eventId, "QUALIFYING", 1));
        }
    }

    private List<Map<String, Object>> sessions(long eventId) {
        return db.sql("""
                        SELECT rs.ordinal, rs.name,
                               (SELECT count(*) FROM result r WHERE r.session_id = rs.id) AS results,
                               (SELECT count(*) FROM result r WHERE r.session_id = rs.id AND r.points_only) AS points_only
                        FROM race_session rs
                        WHERE rs.event_id = :event AND rs.session_type = 'QUALIFYING'
                        ORDER BY rs.ordinal
                        """)
                .param("event", eventId).query().listOfRows();
    }

    @Test
    void reviewNamesTheSplitSession() throws IOException {
        Seeded s = seed();
        long id = stage(FILES.get(1), fixture("imsa-2021/" + FILES.get(1)));
        ImportService.ImportReview review = service.reviewTarget(id, s.eventId(), null);
        assertEquals("QUALIFYING", review.sessionTypeHint());
        assertEquals("Qualifying - GTD Points", review.sessionNameHint());
        assertEquals("Qualifying results CSV (Qualifying - GTD Points) — 13 entries", service.get(id).summary());
    }

    @Test
    void eachSplitSessionKeepsItsOwnResults() throws IOException {
        Seeded s = seed();
        commitAll(s.eventId());

        List<Map<String, Object>> rows = sessions(s.eventId());
        assertEquals(4, rows.size());
        assertEquals(List.of(1, 2, 3, 4), rows.stream().map(r -> r.get("ordinal")).toList());
        assertEquals(List.of(
                "Qualifying - GTD Position", "Qualifying - GTD Points",
                "Qualifying - LMP3 Position - Points", "Qualifying - DPi Position - Points"),
                rows.stream().map(r -> r.get("name")).toList());
        assertEquals(List.of(13L, 13L, 6L, 6L), rows.stream().map(r -> r.get("results")).toList());
        // Only the GTD Points session is points-only, and only for GTD.
        assertEquals(List.of(0L, 13L, 0L, 0L), rows.stream().map(r -> r.get("points_only")).toList());
    }

    @Test
    void reimportUpdatesTheSameSplitSession() throws IOException {
        Seeded s = seed();
        commitAll(s.eventId());
        commitAll(s.eventId());

        List<Map<String, Object>> rows = sessions(s.eventId());
        assertEquals(4, rows.size());
        assertEquals(List.of(13L, 13L, 6L, 6L), rows.stream().map(r -> r.get("results")).toList());
    }

    @Test
    void polesComeOnlyFromGridSettingSessions() throws IOException {
        Seeded s = seed();
        commitAll(s.eventId());

        // One pole per class: #14 won GTD Position; #16 topped GTD Points,
        // which set no grid and so is no pole.
        List<SeasonStatsController.TeamStatsRow> rows = stats.seasonTeamStats(s.seasonId()).rows();
        assertEquals(3, rows.stream().mapToInt(r -> r.quali().poles()).sum());
        assertEquals(1, teamOf(rows, "14").quali().poles());
        SeasonStatsController.TeamStatsRow sixteen = teamOf(rows, "16");
        assertEquals(List.of("16"), List.of(sixteen.carNumbers().split(" ")));
        assertEquals(0, sixteen.quali().poles());

        // The sheet's Q column reads the same rule: #16's GTD place is its
        // Position-session result, not its Points-session P1.
        Integer q16 = db.sql("""
                        SELECT min(r.position_in_class) FROM result r
                        JOIN race_session rs ON rs.id = r.session_id
                        JOIN entry e ON e.id = r.entry_id
                        WHERE rs.event_id = :event AND rs.session_type = 'QUALIFYING'
                          AND NOT r.points_only AND e.car_number = '16'
                        """)
                .param("event", s.eventId()).query(Integer.class).single();
        assertEquals(true, q16 != null && q16 > 1);
    }

    private static SeasonStatsController.TeamStatsRow teamOf(List<SeasonStatsController.TeamStatsRow> rows,
                                                             String car) {
        return rows.stream().filter(r -> List.of(r.carNumbers().split(" ")).contains(car))
                .findFirst().orElseThrow();
    }

    @Test
    void plainNamesStillShareTheirOrdinalAndAMakeUpRaceIsItsOwn() throws IOException {
        Seeded s = seed();
        byte[] race = fixture("imsa-2021/03_Results_Race_Official.CSV");
        service.commit(stage("03_Results_Race 2_Official.CSV", race), target(s.eventId(), "RACE", 2));
        service.commit(stage("03_Results_Race 2_Provisional.CSV", race), target(s.eventId(), "RACE", 2));
        service.commit(stage("03_Results_Miami Make-Up - Race 2_Official.CSV", race),
                target(s.eventId(), "RACE", 2));

        List<Map<String, Object>> rows = db.sql("""
                        SELECT ordinal, name FROM race_session
                        WHERE event_id = :event AND session_type = 'RACE' ORDER BY ordinal
                        """)
                .param("event", s.eventId()).query().listOfRows();
        assertEquals(List.of(2, 3), rows.stream().map(r -> r.get("ordinal")).toList());
        assertEquals(List.of("Race 2", "Miami Make-Up - Race 2"), rows.stream().map(r -> r.get("name")).toList());
    }

    @Test
    void aFileWithoutAnAlKamelNameKeepsTheReviewerOrdinal() throws IOException {
        Seeded s = seed();
        long id = stage("gtd-points.csv", fixture("imsa-2021/" + FILES.get(1)));
        assertNull(service.reviewTarget(id, s.eventId(), null).sessionNameHint());
        service.commit(id, target(s.eventId(), "QUALIFYING", 3));
        assertEquals(List.of(3), sessions(s.eventId()).stream().map(r -> r.get("ordinal")).toList());
        assertEquals("Qualifying 3", sessions(s.eventId()).get(0).get("name"));
    }
}
