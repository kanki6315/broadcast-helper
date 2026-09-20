package com.pitpass.live;

import com.pitpass.live.LiveChampionshipPositions.Row;
import com.pitpass.live.LiveChampionshipPositions.Slot;
import com.pitpass.live.LiveChampionshipPositions.StandingsRow;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A GTP running order scored three ways: by car, by crew, by make. */
class LiveChampionshipPositionsTest {

    private static Slot car(int position, long entryId, String number, String team, String make) {
        return new Slot(position, entryId, number, number, team, make, "CLASSIFIED", 72, null, null);
    }

    // Porsche 1-2, then Cadillac, an unregistered Lamborghini, Acura, the second Cadillac.
    private static final List<Slot> RACE = List.of(
            car(1, 1, "7", "Porsche Penske Motorsport", "Porsche"),
            car(2, 2, "6", "Porsche Penske Motorsport", "Porsche"),
            car(3, 3, "31", "Cadillac Whelen", "Cadillac"),
            car(4, 4, "63", "Lamborghini Squadra Corse", "Lamborghini"),
            car(5, 5, "93", "Acura Meyer Shank Racing", "Acura"),
            car(6, 6, "10", "Cadillac Wayne Taylor Racing", "Cadillac"));

    private static final List<Slot> QUALIFYING = List.of(
            car(1, 3, "31", "Cadillac Whelen", "Cadillac"),
            car(2, 5, "93", "Acura Meyer Shank Racing", "Acura"),
            car(3, 1, "7", "Porsche Penske Motorsport", "Porsche"));

    private static final Map<Long, List<String>> CREWS = Map.of(
            1L, List.of("Felipe Nasr", "Nick Tandy"),
            3L, List.of("Jack Aitken", "Earl Bamber"),
            5L, List.of("Renger van der Zande", "Endurance Only"));

    private static Map<String, Row> byKey(List<Row> rows) {
        return rows.stream().collect(Collectors.toMap(Row::competitorKey, r -> r));
    }

    @Test
    void manufacturersScoreTheirBestCarRankedAmongRegisteredMakes() {
        var standings = List.of(new StandingsRow("Cadillac", null), new StandingsRow("Porsche", null),
                new StandingsRow("Acura", null), new StandingsRow("BMW", null));
        var result = LiveChampionshipPositions.build("MANUFACTURERS", "GTP", standings, RACE, QUALIFYING, Map.of(), Map.of());
        var rows = byKey(result.rows());

        assertEquals(1, rows.get("Porsche").live().position());
        assertEquals("7", rows.get("Porsche").live().carNumber(), "scoring with its best-placed car");
        assertEquals(2, rows.get("Cadillac").live().position(), "third on the road, second make");
        assertEquals(3, rows.get("Acura").live().position(),
                "the unregistered Lamborghini ahead of it does not push it down");
        assertNull(rows.get("BMW").live(), "no car running");

        assertEquals(1, rows.get("Cadillac").qualifyingPosition());
        assertEquals(3, rows.get("Porsche").qualifyingPosition());
        assertTrue(result.newcomers().isEmpty(), "an unregistered make is not a newcomer, it just does not score");
    }

    @Test
    void everyDriverOfACarTakesTheCarsPosition() {
        var standings = List.of(new StandingsRow("Jack Aitken", null), new StandingsRow("Felipe Nasr", null),
                new StandingsRow("nick tandy", null), new StandingsRow("Renger van der Zande", null),
                new StandingsRow("Sat Out", null));
        var result = LiveChampionshipPositions.build("DRIVERS", "GTP", standings, RACE, QUALIFYING, CREWS, Map.of());
        var rows = byKey(result.rows());

        assertEquals(1, rows.get("Felipe Nasr").live().position());
        assertEquals(1, rows.get("nick tandy").live().position(), "names match without regard to case");
        assertEquals(3, rows.get("Jack Aitken").live().position());
        assertEquals("31", rows.get("Jack Aitken").live().carNumber());
        assertEquals(1, rows.get("Jack Aitken").qualifyingPosition());
        assertNull(rows.get("Sat Out").live());
        assertNull(rows.get("Sat Out").qualifyingPosition());

        // In a scoring car with no standings row: an endurance-only driver. Named, with a baseline of zero.
        assertEquals(List.of("Earl Bamber", "Endurance Only"), result.newcomers().stream().map(n -> n.name()).sorted().toList());
        assertEquals(5, result.newcomers().stream().filter(n -> n.name().equals("Endurance Only")).findFirst().orElseThrow().position());
    }

    @Test
    void teamsMatchByCarNumberThroughTheSeasonsAlias() {
        var standings = List.of(new StandingsRow("7", "Porsche Penske Motorsport"),
                new StandingsRow("031", "Cadillac Whelen"),
                new StandingsRow("5", "JDC-Miller MotorSports"));
        var order = List.of(car(1, 1, "7", "Porsche Penske Motorsport", "Porsche"),
                car(2, 9, "85", "JDC-Miller MotorSports", "Porsche"),
                car(3, 3, "31", "Cadillac Whelen", "Cadillac"),
                car(4, 8, "99", "Late Entry Racing", "Acura"));
        var result = LiveChampionshipPositions.build("TEAMS", "GTP", standings, order, List.of(), Map.of(),
                Map.of(LiveClassification.aliasKey("GTP", "85"), "5"));
        var rows = byKey(result.rows());

        assertEquals(1, rows.get("7").live().position());
        assertEquals(3, rows.get("031").live().position(), "a standings key with a leading zero still finds its car");
        assertEquals(2, rows.get("5").live().position(), "running as #85 this weekend");
        assertEquals("85", rows.get("5").live().carNumber());
        assertEquals(List.of("Late Entry Racing"), result.newcomers().stream().map(n -> n.name()).toList());
    }

    @Test
    void aChampionshipKeyedByTeamNameFallsBackToTheName() {
        var standings = List.of(new StandingsRow("Cadillac Whelen", "Cadillac Whelen"));
        var result = LiveChampionshipPositions.build("TEAMS", "GTP", standings, RACE, List.of(), Map.of(), Map.of());
        assertEquals(3, result.rows().get(0).live().position());
    }

    @Test
    void withNothingRunningRowsSimplyHaveNoPosition() {
        var standings = List.of(new StandingsRow("7", null));
        var result = LiveChampionshipPositions.build("TEAMS", "GTP", standings, List.of(), QUALIFYING, Map.of(), Map.of());
        assertNull(result.rows().get(0).live());
        assertEquals(3, result.rows().get(0).qualifyingPosition(), "imported qualifying stands on its own");
        assertTrue(result.newcomers().isEmpty());
    }
}
