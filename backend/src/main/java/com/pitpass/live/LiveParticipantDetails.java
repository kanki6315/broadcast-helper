package com.pitpass.live;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * timing.session.standings.overall.participantDetails (spec 1.0.36, pp. 22–25),
 * read for the tower: each car's sectors as they are run, the sector it is in,
 * its best sectors and its track status. Pure; the tower hands it the car's
 * node from the state tree.
 *
 * Built from the spec alone: no recording holds this channel yet. The one
 * reading that matters, and is a guess, is {@code lastSectors}: "last
 * completed sector times", taken to keep each sector's newest time, so a
 * sector at or past {@code currentSector} is still the previous lap's.
 */
final class LiveParticipantDetails {

    private LiveParticipantDetails() {
    }

    /**
     * One sector cell. currentLap: run on the lap the car is on now (before
     * its current sector); false = the previous lap's, still showing until
     * the car runs that sector again.
     */
    public record SectorTime(Integer ms, Boolean valid, boolean currentLap) {
    }

    /**
     * One car. trackStatus is BOX, OUT_LAP, TRACK or STOPPED. sectors and
     * bestSectorMs are indexed from sector 1 and padded to the tower's sector
     * count; idealMs is the sum of the car's own best sectors, null until it
     * has one in every sector. checkered is hasSeenCheckered (null when the
     * server leaves it out); bestLapDriver the driver order bestLap.driver
     * names.
     */
    record Car(String trackStatus, Integer currentSector, Integer pitStops, List<SectorTime> sectors,
               List<Integer> bestSectorMs, Long idealMs, Boolean checkered, Integer bestLapDriver) {

        boolean inBox() {
            return "BOX".equalsIgnoreCase(trackStatus);
        }
    }

    /**
     * A class's fastest time in one sector, the car that set it and — filled
     * in by the tower, from the laps — the surname of the driver who did.
     */
    public record ClassSector(Integer ms, String car, String driver) {

        ClassSector withDriver(String name) {
            return new ClassSector(ms, car, name);
        }
    }

    /** The highest sector number any car reports: the tower's sector count. */
    static int sectorCount(JsonNode details) {
        int count = 0;
        if (details == null) {
            return 0;
        }
        for (JsonNode car : details) {
            for (String field : List.of("lastSectors", "bestSectors")) {
                for (JsonNode s : car.path(field)) {
                    count = Math.max(count, s.path("number").asInt(0));
                }
            }
        }
        return Math.min(count, 64);
    }

    static Car car(JsonNode node, int sectors) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        Integer current = node.hasNonNull("currentSector") ? node.path("currentSector").asInt() : null;
        List<SectorTime> last = new ArrayList<>();
        List<Integer> best = new ArrayList<>();
        long ideal = 0;
        boolean complete = sectors > 0;
        for (int n = 1; n <= sectors; n++) {
            JsonNode s = node.path("lastSectors").path(String.valueOf(n));
            Integer ms = positive(s.path("time"));
            Boolean valid = s.hasNonNull("isValid") ? s.path("isValid").asBoolean() : null;
            last.add(ms == null ? null : new SectorTime(ms, valid, current != null && n < current));
            Integer b = positive(node.path("bestSectors").path(String.valueOf(n)).path("time"));
            best.add(b);
            if (b == null) {
                complete = false;
            } else {
                ideal += b;
            }
        }
        return new Car(node.hasNonNull("status") ? node.path("status").asText() : null, current,
                node.hasNonNull("pitStops") ? node.path("pitStops").asInt() : null,
                last, best, complete ? ideal : null,
                node.hasNonNull("hasSeenCheckered") ? node.path("hasSeenCheckered").asBoolean() : null,
                positive(node.path("bestLap").path("driver")));
    }

    /** Per sector, the class's fastest best and who holds it (the earlier car in the list on a tie). */
    static List<ClassSector> classBests(Collection<String> cars, java.util.function.Function<String, Car> byCar, int sectors) {
        List<ClassSector> out = new ArrayList<>();
        for (int i = 0; i < sectors; i++) {
            Integer ms = null;
            String holder = null;
            for (String number : cars) {
                Car c = byCar.apply(number);
                Integer b = c == null ? null : c.bestSectorMs().get(i);
                if (b != null && (ms == null || b < ms)) {
                    ms = b;
                    holder = number;
                }
            }
            out.add(new ClassSector(ms, holder, null));
        }
        return out;
    }

    /** The class's ideal lap: its best sectors summed, null unless every sector has one. */
    static Long idealLap(List<ClassSector> bests) {
        if (bests.isEmpty() || bests.stream().anyMatch(b -> b.ms() == null)) {
            return null;
        }
        return bests.stream().mapToLong(ClassSector::ms).sum();
    }

    private static Integer positive(JsonNode n) {
        int v = n.asInt(0);
        return v > 0 ? v : null;
    }
}
