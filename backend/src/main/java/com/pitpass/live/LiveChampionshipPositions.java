package com.pitpass.live;

import com.pitpass.sheets.SheetController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Where each row of one class championship stands right now: its scoring
 * position in the running order, by the rule of the championship's kind.
 *
 * - TEAMS: the car's position in class. Rows are keyed by car number (through
 *   the season's car_number_alias), with the recap's team-name fallback.
 * - DRIVERS: the position in class of the car the driver is entered in. Every
 *   member of the crew takes the car's position.
 * - MANUFACTURERS: only the best-placed car of each make counts, and the makes
 *   are then ranked among themselves — a Porsche 1-2 ahead of a Cadillac makes
 *   Cadillac second. Only makes with a standings row take part in that
 *   ranking: a make that is not registered for the championship neither
 *   scores nor pushes anyone down.
 *
 * Positions, never points: the scales stay in the clients' calculators, which
 * apply them to these exactly as they do to positions typed in by hand.
 * The same rules rank a second order — the event's imported qualifying result
 * — because official standings omit a weekend's qualifying points until after
 * the race, so a live race projection has to add them itself.
 *
 * Pure: no socket, no database.
 */
public final class LiveChampionshipPositions {

    /** One car in an order — the live one, or imported qualifying. */
    public record Slot(int position, Long entryId, String carNumber, String competitorKey,
                       String teamName, String manufacturer, String status,
                       Integer laps, Long gapToLeaderMs, Integer gapToLeaderLaps) {
    }

    public record StandingsRow(String competitorKey, String competitorName) {
    }

    /** The car a row is scoring with, for display beside the projection. */
    public record Running(int position, String carNumber, String teamName, String status,
                          Integer laps, Long gapToLeaderMs, Integer gapToLeaderLaps) {
    }

    public record Row(String competitorKey, Running live, Integer qualifyingPosition) {
    }

    /** Scoring right now with no standings row: a late entry, an endurance-only driver. Baseline zero. */
    public record Newcomer(String name, String carNumber, int position) {
    }

    public record Result(List<Row> rows, List<Newcomer> newcomers) {
    }

    private LiveChampionshipPositions() {
    }

    /**
     * @param kind          TEAMS, DRIVERS or MANUFACTURERS (anything else scores as TEAMS)
     * @param live          the class's running order, best first; empty when not running
     * @param qualifying    the class's imported qualifying order; empty when not imported
     * @param crewByEntry   entry id → driver names, for DRIVERS
     * @param numberAliases the season's car_number_alias, keyed {@link LiveClassification#aliasKey}
     */
    public static Result build(String kind, String className, List<StandingsRow> standings,
                               List<Slot> live, List<Slot> qualifying,
                               Map<Long, List<String>> crewByEntry, Map<String, String> numberAliases) {
        Map<String, Slot> liveByUnit = units(kind, className, standings, live, crewByEntry, numberAliases);
        Map<String, Slot> qualifyingByUnit = units(kind, className, standings, qualifying, crewByEntry, numberAliases);

        List<Row> rows = new ArrayList<>();
        Map<String, Slot> unclaimed = new LinkedHashMap<>(liveByUnit);
        for (StandingsRow row : standings) {
            String unit = unitOf(kind, className, row, liveByUnit, qualifyingByUnit, numberAliases);
            Slot running = unit == null ? null : liveByUnit.get(unit);
            Slot qualified = unit == null ? null : qualifyingByUnit.get(unit);
            if (unit != null) {
                unclaimed.remove(unit);
            }
            rows.add(new Row(row.competitorKey(),
                    running == null ? null : new Running(running.position(), running.carNumber(), running.teamName(),
                            running.status(), running.laps(), running.gapToLeaderMs(), running.gapToLeaderLaps()),
                    qualified == null ? null : qualified.position()));
        }

        List<Newcomer> newcomers = new ArrayList<>();
        unclaimed.forEach((unit, slot) -> newcomers.add(new Newcomer(
                "DRIVERS".equals(kind) ? displayName(unit, slot, crewByEntry) : Objects.toString(slot.teamName(), "#" + slot.carNumber()),
                slot.carNumber(), slot.position())));
        return new Result(rows, newcomers);
    }

    /**
     * Scoring unit → the slot it scores with, its position already by this
     * kind's rule. Units are lower-cased names for DRIVERS and MANUFACTURERS,
     * resolved car numbers for TEAMS.
     */
    private static Map<String, Slot> units(String kind, String className, List<StandingsRow> standings,
                                           List<Slot> order, Map<Long, List<String>> crewByEntry,
                                           Map<String, String> numberAliases) {
        Map<String, Slot> units = new LinkedHashMap<>();
        if ("MANUFACTURERS".equals(kind)) {
            List<String> registered = standings.stream().map(r -> fold(r.competitorKey())).toList();
            int rank = 0;
            for (Slot slot : order) {
                String make = fold(slot.manufacturer());
                if (!make.isEmpty() && registered.contains(make) && !units.containsKey(make)) {
                    rank++;
                    units.put(make, new Slot(rank, slot.entryId(), slot.carNumber(), slot.competitorKey(),
                            slot.teamName(), slot.manufacturer(), slot.status(), slot.laps(),
                            slot.gapToLeaderMs(), slot.gapToLeaderLaps()));
                }
            }
        } else if ("DRIVERS".equals(kind)) {
            for (Slot slot : order) {
                for (String name : crewByEntry.getOrDefault(slot.entryId(), List.of())) {
                    if (!fold(name).isEmpty()) {
                        units.putIfAbsent(fold(name), slot);
                    }
                }
            }
        } else {
            for (Slot slot : order) {
                units.putIfAbsent(resolve(className, slot.competitorKey(), numberAliases), slot);
            }
        }
        return units;
    }

    /** The unit a standings row scores as, or null when it is not in either order. */
    private static String unitOf(String kind, String className, StandingsRow row,
                                 Map<String, Slot> live, Map<String, Slot> qualifying,
                                 Map<String, String> numberAliases) {
        if ("TEAMS".equals(kind) || !("DRIVERS".equals(kind) || "MANUFACTURERS".equals(kind))) {
            String number = resolve(className, row.competitorKey(), numberAliases);
            if (live.containsKey(number) || qualifying.containsKey(number)) {
                return number;
            }
            // The recap's fallback: a championship keyed by team name rather than car number.
            for (Map<String, Slot> order : List.of(live, qualifying)) {
                for (var unit : order.entrySet()) {
                    if (!fold(row.competitorKey()).isEmpty()
                            && fold(unit.getValue().teamName()).equals(fold(row.competitorKey()))) {
                        return unit.getKey();
                    }
                }
            }
            return null;
        }
        // DRIVERS and MANUFACTURERS rows carry the name in the key, sometimes also in the name.
        for (String candidate : List.of(fold(row.competitorKey()), fold(row.competitorName()))) {
            if (!candidate.isEmpty() && (live.containsKey(candidate) || qualifying.containsKey(candidate))) {
                return candidate;
            }
        }
        return null;
    }

    /** Both sides of a TEAMS match meet at the alias-resolved, zero-stripped number. */
    private static String resolve(String className, String number, Map<String, String> numberAliases) {
        String canonical = numberAliases.get(LiveClassification.aliasKey(className, number));
        return SheetController.normalizeCarNumber(canonical != null ? canonical : number);
    }

    private static String displayName(String unit, Slot slot, Map<Long, List<String>> crewByEntry) {
        return crewByEntry.getOrDefault(slot.entryId(), List.of()).stream()
                .filter(name -> fold(name).equals(unit)).findFirst().orElse(unit);
    }

    private static String fold(String text) {
        return Objects.toString(text, "").trim().toLowerCase(Locale.ROOT);
    }
}
