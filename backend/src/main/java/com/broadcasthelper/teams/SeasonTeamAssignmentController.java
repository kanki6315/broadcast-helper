package com.broadcasthelper.teams;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/seasons/{seasonId}/team-assignments")
public class SeasonTeamAssignmentController {

    private final JdbcClient db;
    private final TeamAssignmentService resolver;

    public SeasonTeamAssignmentController(JdbcClient db, TeamAssignmentService resolver) {
        this.db = db;
        this.resolver = resolver;
    }

    public record Assignment(long id, long driverId, String teamName, boolean privateer,
                             int effectiveFromRound) {
    }

    public record DriverRow(long id, String name, List<Assignment> assignments) {
    }

    public record Response(long seasonId, int year, int roundCount, List<String> teamNames,
                           List<DriverRow> drivers) {
    }

    public record SaveAssignment(@NotNull Long driverId, @NotBlank String teamName,
                                 boolean privateer, @Min(1) int effectiveFromRound) {
    }

    public record SaveRequest(@NotEmpty List<@Valid SaveAssignment> assignments) {
    }

    @GetMapping
    public Response get(@PathVariable long seasonId) {
        int[] header = seasonHeader(seasonId);
        List<DriverRow> drivers = drivers(seasonId);
        Map<Long, List<Assignment>> byDriver = new HashMap<>();
        db.sql("""
                        SELECT a.id, a.driver_id, st.name, st.privateer_driver_id IS NOT NULL AS privateer,
                               a.effective_from_round
                        FROM season_driver_team_assignment a
                                 JOIN season_team st ON st.id = a.team_id
                        WHERE a.season_id = :seasonId
                        ORDER BY a.driver_id, a.effective_from_round
                        """)
                .param("seasonId", seasonId)
                .query((rs, i) -> new Assignment(rs.getLong("id"), rs.getLong("driver_id"),
                        rs.getString("name"), rs.getBoolean("privateer"),
                        rs.getInt("effective_from_round")))
                .list()
                .forEach(a -> byDriver.computeIfAbsent(a.driverId(), ignored -> new ArrayList<>()).add(a));

        List<DriverRow> withAssignments = drivers.stream()
                .map(d -> new DriverRow(d.id(), d.name(), byDriver.getOrDefault(d.id(), List.of())))
                .toList();
        return new Response(seasonId, header[0], header[1], teamNames(seasonId), withAssignments);
    }

    @PutMapping
    @Transactional
    public Response save(@PathVariable long seasonId, @Valid @RequestBody SaveRequest request) {
        int[] header = seasonHeader(seasonId);
        List<DriverRow> drivers = drivers(seasonId);
        Set<Long> seasonDrivers = drivers.stream().map(DriverRow::id).collect(java.util.stream.Collectors.toSet());
        Set<Long> requestedDrivers = request.assignments().stream().map(SaveAssignment::driverId)
                .collect(java.util.stream.Collectors.toSet());
        if (!requestedDrivers.equals(seasonDrivers)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Save one team assignment for every driver in this season");
        }

        Map<Long, Set<Integer>> roundsByDriver = new HashMap<>();
        for (SaveAssignment assignment : request.assignments()) {
            Set<Integer> rounds = roundsByDriver.computeIfAbsent(assignment.driverId(), ignored -> new HashSet<>());
            if (!rounds.add(assignment.effectiveFromRound())) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "A driver cannot have two team assignments beginning in the same round");
            }
            if (assignment.effectiveFromRound() > Math.max(header[1], 1)) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "An assignment cannot begin after the last imported round");
            }
            if (!assignment.privateer() && assignment.teamName().trim().equalsIgnoreCase("Privateer")) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "Choose the Privateer option instead of creating a team named Privateer");
            }
        }
        if (roundsByDriver.values().stream().anyMatch(rounds -> !rounds.contains(1))) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Every driver needs an assignment beginning in round 1");
        }

        // entry keeps the display snapshot, but its identity reference must be
        // released before the old season teams are replaced.
        db.sql("""
                        UPDATE entry SET season_team_id = NULL
                        WHERE event_id IN (SELECT id FROM event WHERE season_id = :seasonId)
                        """)
                .param("seasonId", seasonId).update();
        db.sql("DELETE FROM season_team WHERE season_id = :seasonId")
                .param("seasonId", seasonId).update();

        Map<String, Long> regularTeams = new LinkedHashMap<>();
        Map<Long, Long> privateerTeams = new HashMap<>();
        for (SaveAssignment assignment : request.assignments().stream()
                .sorted(java.util.Comparator.comparing(SaveAssignment::driverId)
                        .thenComparingInt(SaveAssignment::effectiveFromRound)).toList()) {
            long teamId;
            String teamName;
            if (assignment.privateer()) {
                teamName = "Privateer";
                Long existing = privateerTeams.get(assignment.driverId());
                if (existing == null) {
                    existing = db.sql("""
                                    INSERT INTO season_team (season_id, name, privateer_driver_id)
                                    VALUES (:seasonId, 'Privateer', :driverId) RETURNING id
                                    """)
                            .param("seasonId", seasonId)
                            .param("driverId", assignment.driverId())
                            .query(Long.class).single();
                    privateerTeams.put(assignment.driverId(), existing);
                }
                teamId = existing;
            } else {
                teamName = assignment.teamName().trim();
                String key = teamName.toLowerCase(Locale.ROOT);
                Long existing = regularTeams.get(key);
                if (existing == null) {
                    existing = db.sql("""
                                    INSERT INTO season_team (season_id, name)
                                    VALUES (:seasonId, :name) RETURNING id
                                    """)
                            .param("seasonId", seasonId).param("name", teamName)
                            .query(Long.class).single();
                    regularTeams.put(key, existing);
                }
                teamId = existing;
            }
            db.sql("""
                            INSERT INTO season_driver_team_assignment
                                (season_id, driver_id, team_id, effective_from_round)
                            VALUES (:seasonId, :driverId, :teamId, :round)
                            """)
                    .param("seasonId", seasonId)
                    .param("driverId", assignment.driverId())
                    .param("teamId", teamId)
                    .param("round", assignment.effectiveFromRound())
                    .update();
        }
        resolver.applySeason(seasonId);
        return get(seasonId);
    }

    private int[] seasonHeader(long seasonId) {
        return db.sql("""
                        SELECT s.year, COALESCE(max(e.round_ordinal), 0) AS round_count
                        FROM season s LEFT JOIN event e ON e.season_id = s.id
                        WHERE s.id = :seasonId GROUP BY s.id, s.year
                        """)
                .param("seasonId", seasonId)
                .query((rs, i) -> new int[]{rs.getInt("year"), rs.getInt("round_count")})
                .optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such season"));
    }

    private List<DriverRow> drivers(long seasonId) {
        return db.sql("""
                        SELECT DISTINCT d.id, d.first_name, d.surname
                        FROM driver d
                                 JOIN driver_assignment da ON da.driver_id = d.id
                                 JOIN entry en ON en.id = da.entry_id
                                 JOIN event ev ON ev.id = en.event_id
                        WHERE ev.season_id = :seasonId
                        ORDER BY d.surname, d.first_name
                        """)
                .param("seasonId", seasonId)
                .query((rs, i) -> new DriverRow(rs.getLong("id"),
                        (rs.getString("first_name") + " " + rs.getString("surname")).trim(), List.of()))
                .list();
    }

    private List<String> teamNames(long seasonId) {
        return db.sql("""
                        SELECT name FROM (
                            SELECT st.name
                            FROM season_team st
                            WHERE st.season_id = :seasonId AND st.privateer_driver_id IS NULL
                            UNION
                            SELECT DISTINCT en.team_name AS name
                            FROM entry en JOIN event ev ON ev.id = en.event_id
                            WHERE ev.season_id = :seasonId
                              AND lower(trim(en.team_name)) <> 'privateer'
                              AND NOT EXISTS (
                                  SELECT 1 FROM driver_assignment da JOIN driver d ON d.id = da.driver_id
                                  WHERE da.entry_id = en.id
                                    AND lower(trim(d.first_name || ' ' || d.surname)) = lower(trim(en.team_name))
                              )
                        ) names
                        WHERE name IS NOT NULL AND length(trim(name)) > 0
                        ORDER BY lower(name)
                        """)
                .param("seasonId", seasonId)
                .query(String.class)
                .list();
    }
}
