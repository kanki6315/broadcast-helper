package com.pitpass.live;

/**
 * Whether a car's energy reaches the chequered flag, and how many caution
 * laps it would take. Pure: {@link LiveAnalysisService} gathers the clock,
 * the crossings and each car's {@link EnergyModel} averages; the Energy view
 * shows the answer.
 *
 * Timed race: the flag falls on the overall leader's first crossing after
 * the clock runs out, and a car finishes on its first crossing after that.
 * Laps are counted from the car's last crossing at its green lap time; the
 * lap it is on now is part-run (lapFraction), and the energy reading is now,
 * so only the rest of it is still to use. A caution lap takes longer than a
 * green one, so each one also leaves less of the clock to run: x caution laps
 * plus however many green laps fit in what is left. The leader's flag is
 * projected at green pace and held there; a caution moves it by under a lap.
 *
 * Fixed-lap race: the leader's laps left, less the laps this car is down. A
 * caution lap replaces a green lap and only uses less.
 *
 * Not modelled, on the cautious side: the time a stop takes (a car that
 * stops now is given the laps as if it had not), and pit-lane laps under
 * caution using less than a caution lap on track.
 */
public final class EnergyFinish {

    /** Above this many caution laps the answer is "will not make it", whatever comes. */
    static final int MAX_CAUTION_LAPS = 200;

    /**
     * lapsToFlag: laps still to run from now at green pace (the part-run lap
     * counted as its remainder). needPct: energy those use. marginPct: start
     * less reserve less need — over 0 it makes it green. cautionLaps: the
     * fewest caution laps that get it there (0 when it makes it green), null
     * when there is no caution figure to work it out, or when no number of
     * caution laps would do (makesIt false).
     */
    public record Result(double lapsToFlag, double needPct, double marginPct, Integer cautionLaps, boolean makesIt) {
    }

    /** One car's figures. Caution figures may be null (none known). */
    record Car(double startPct, double greenUsePct, double greenLapMs, Double cautionUsePct, Double cautionLapMs,
               long lastCrossingMs, int lapsBehindLeader) {
    }

    private EnergyFinish() {
    }

    /** The leader's first crossing at or after the clock runs out, at its green lap time. */
    static long leaderFlagMs(long clockEndMs, long leaderLastCrossingMs, double leaderLapMs) {
        if (leaderLastCrossingMs >= clockEndMs) {
            return leaderLastCrossingMs;
        }
        long laps = (long) Math.ceil((clockEndMs - leaderLastCrossingMs) / leaderLapMs);
        return leaderLastCrossingMs + Math.round(laps * leaderLapMs);
    }

    /** A timed race: the flag at flagMs, now at nowMs (both feed time). */
    static Result timed(Car car, long nowMs, long flagMs, double reservePct) {
        double fraction = lapFraction(car, nowMs);
        double available = car.startPct() - reservePct;
        double greenLaps = Math.max(0, Math.ceil((flagMs - car.lastCrossingMs()) / car.greenLapMs()) - fraction);
        double need = greenLaps * car.greenUsePct();
        if (need <= available) {
            return new Result(greenLaps, need, available - need, 0, true);
        }
        if (car.cautionUsePct() == null || car.cautionLapMs() == null) {
            return new Result(greenLaps, need, available - need, null, true);
        }
        for (int x = 1; x <= MAX_CAUTION_LAPS; x++) {
            double clockLeft = flagMs - car.lastCrossingMs() - x * car.cautionLapMs();
            double green = Math.max(0, Math.ceil(clockLeft / car.greenLapMs()) - fraction);
            if (x * car.cautionUsePct() + green * car.greenUsePct() <= available) {
                return new Result(greenLaps, need, available - need, x, true);
            }
            if (clockLeft <= 0) {
                break; // all caution from here: more caution laps only use more
            }
        }
        return new Result(greenLaps, need, available - need, null, false);
    }

    /** A fixed-lap race: the leader has leaderLapsLeft to run. */
    static Result laps(Car car, long nowMs, int leaderLapsLeft, double reservePct) {
        double fraction = lapFraction(car, nowMs);
        double laps = Math.max(0, leaderLapsLeft - car.lapsBehindLeader() - fraction);
        double available = car.startPct() - reservePct;
        double need = laps * car.greenUsePct();
        if (need <= available) {
            return new Result(laps, need, available - need, 0, true);
        }
        if (car.cautionUsePct() == null || car.cautionUsePct() >= car.greenUsePct()) {
            return new Result(laps, need, available - need, null, car.cautionUsePct() == null);
        }
        // Each caution lap saves the difference between a green lap's use and a caution lap's.
        double saving = car.greenUsePct() - car.cautionUsePct();
        int x = (int) Math.ceil((need - available) / saving - 1e-9);
        return x <= Math.ceil(laps) ? new Result(laps, need, available - need, x, true)
                : new Result(laps, need, available - need, null, false);
    }

    /** How much of the lap it is on the car has run: 0 at the line, under 1 until it crosses again. */
    static double lapFraction(Car car, long nowMs) {
        double f = (nowMs - car.lastCrossingMs()) / car.greenLapMs();
        return Math.max(0, Math.min(0.99, f));
    }
}
