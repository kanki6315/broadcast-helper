package com.pitpass.live;

import com.pitpass.live.DriveTimeRuleController.Rule;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Each driver's drive time against the event's rules. A pure function of
 * the session's stints and drivers, so it is tested without a database.
 *
 * Drive time is Al Kamel's {@code driverAccumSessionTrackTime}: the IMSA rule
 * counts track time only, and that accumulator is the one that leaves out
 * the pit lane. How it behaves on an open stint has not been seen on real
 * bytes yet, and that single question lives in {@link #driveMs}.
 */
public final class DriveTime {

    /** One stint row, as much of it as drive time needs. */
    public record Stint(String car, long startMs, String type, Integer driverOrder, Long finishMs,
                        Long accumSessionTrackMs) {
        boolean open() {
            return finishMs == null || finishMs <= 0;
        }

        boolean onTrack() {
            return type == null || type.equalsIgnoreCase("TRACK");
        }
    }

    /** A crew seat of the session: live_driver joined to our entry's class. */
    public record Driver(String car, int order, String name, String rating, Long driverId, String className) {
    }

    public enum Status { OK, UNDER_MIN, OVER_MAX, NO_RULE }

    /**
     * owedMs: still to drive to reach the minimum. remainingMs: left before
     * the maximum (negative once over it, when overMs says by how much).
     */
    public record Result(String car, int driverOrder, String name, String rating, Long driverId, String className,
                         long driveMs, boolean inCar, Long minMs, Long maxMs, Status status,
                         Long owedMs, Long remainingMs, Long overMs) {
    }

    private DriveTime() {
    }

    public static List<Result> compute(List<Stint> stints, List<Driver> drivers, List<Rule> rules, long nowMs) {
        Map<String, Driver> seats = new LinkedHashMap<>();
        drivers.forEach(d -> seats.put(d.car() + "#" + d.order(), d));
        Map<String, List<Stint>> byDriver = new LinkedHashMap<>();
        for (Stint s : stints) {
            if (s.driverOrder() == null) {
                continue;
            }
            String key = s.car() + "#" + s.driverOrder();
            byDriver.computeIfAbsent(key, k -> new ArrayList<>()).add(s);
            // A driver the entry data has not named: still counted, never dropped.
            seats.putIfAbsent(key, new Driver(s.car(), s.driverOrder(), null, null, null,
                    drivers.stream().filter(d -> d.car().equals(s.car())).map(Driver::className)
                            .filter(Objects::nonNull).findFirst().orElse(null)));
        }
        Map<String, Stint> latestByCar = new LinkedHashMap<>();
        for (Stint s : stints) {
            latestByCar.merge(s.car(), s, (a, b) -> b.startMs() > a.startMs() ? b : a);
        }

        List<Result> out = new ArrayList<>();
        for (var seat : seats.entrySet()) {
            Driver d = seat.getValue();
            List<Stint> own = byDriver.getOrDefault(seat.getKey(), List.of());
            long drive = driveMs(own, nowMs);
            Stint latest = latestByCar.get(d.car());
            boolean inCar = latest != null && latest.open() && Objects.equals(latest.driverOrder(), d.order());
            // Driver 0 is the feed's "nobody identified yet" (a stint before the
            // car's driver is known): a row only once it holds some time.
            if (d.order() == 0 && drive == 0 && !inCar) {
                continue;
            }
            Rule rule = rule(rules, d.className(), d.rating());
            Long min = rule == null ? null : rule.minMs();
            Long max = rule == null ? null : rule.maxMs();
            Status status = rule == null ? Status.NO_RULE
                    : max != null && drive > max ? Status.OVER_MAX
                    : min != null && drive < min ? Status.UNDER_MIN
                    : Status.OK;
            out.add(new Result(d.car(), d.order(), d.name(), d.rating(), d.driverId(), d.className(), drive, inCar,
                    min, max, status,
                    min != null && drive < min ? min - drive : null,
                    max != null ? max - drive : null,
                    max != null && drive > max ? drive - max : null));
        }
        out.sort(Comparator.comparing(Result::car).thenComparingInt(Result::driverOrder));
        return out;
    }

    /**
     * One driver's track time so far: the accumulator on their latest stint
     * that carries one. If their latest stint is still open on track and
     * carries no accumulator of its own, the accumulator is taken to update
     * only when a stint closes, and the open stint's elapsed time is added.
     * An open stint that does carry one is taken as updated live.
     * <p>
     * Unverified until a practice session is recorded with analysis on. If
     * the feed turns out to stamp the accumulator at stint start, this is the
     * one place to change.
     */
    static long driveMs(List<Stint> driverStints, long nowMs) {
        if (driverStints.isEmpty()) {
            return 0;
        }
        List<Stint> ordered = driverStints.stream().sorted(Comparator.comparingLong(Stint::startMs)).toList();
        long base = 0;
        for (Stint s : ordered) {
            if (s.accumSessionTrackMs() != null) {
                base = s.accumSessionTrackMs();
            }
        }
        Stint last = ordered.getLast();
        if (last.open() && last.onTrack() && last.accumSessionTrackMs() == null) {
            return base + Math.max(0, nowMs - last.startMs());
        }
        return base;
    }

    /**
     * The rule for a driver: the rating's own rule, with any bound it leaves
     * blank taken from the class-wide rule — a Bronze minimum still sits
     * under everyone's maximum. Class names match ignoring case and spaces.
     */
    static Rule rule(List<Rule> rules, String className, String rating) {
        if (className == null) {
            return null;
        }
        String cls = className.replaceAll("\\s+", "").toLowerCase();
        Rule general = null;
        Rule own = null;
        for (Rule r : rules) {
            if (!r.className().replaceAll("\\s+", "").toLowerCase().equals(cls)) {
                continue;
            }
            if (r.rating() == null) {
                general = r;
            } else if (rating != null && !rating.isBlank() && r.rating().equalsIgnoreCase(rating.trim().substring(0, 1))) {
                own = r;
            }
        }
        if (own == null || general == null) {
            return own != null ? own : general;
        }
        return new Rule(own.className(), own.rating(),
                own.minMs() != null ? own.minMs() : general.minMs(),
                own.maxMs() != null ? own.maxMs() : general.maxMs(),
                own.note() != null ? own.note() : general.note());
    }
}
