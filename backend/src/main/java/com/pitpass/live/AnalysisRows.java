package com.pitpass.live;

import java.util.Map;
import java.util.TreeMap;

/**
 * What the analysis router hands the writer. Diffs are partial, so a patch
 * says which fields it carries: a field that is present is written (a JSON
 * null writes NULL), and a field that is absent leaves the stored value
 * alone. These are the only analysis objects that ever exist in memory, and
 * only while they queue for the database.
 */
final class AnalysisRows {

    private AnalysisRows() {
    }

    /**
     * timing.session.info, as far as the session key and its label go. Which
     * Pit Pass event it belongs to is decided separately, by entry-list match
     * ({@link LiveEventMatch}), never by whatever happened to be bound.
     */
    record SessionInfo(long sessionDbId, String mongoId, Long feedEventDbId, String name, String type,
                       Long dateMs, Long champDbId, String champName, String feedEventName,
                       String feedEventShortName, boolean closed) {
    }

    /** Anything the writer is asked to do, in arrival order. */
    sealed interface Op permits LapPatch, StintPatch, LapDeleted, StintDeleted, SessionSeen, EntriesChanged, EnergyLap {
    }

    /** IMSA telemetry: energy at the line after {@code lap} (see TelemetryRunner). */
    record EnergyLap(long sessionDbId, String car, int lap, double energyPct, Boolean pitLane) implements Op {
    }

    record SessionSeen(SessionInfo session) implements Op {
    }

    /** timing.session.entry changed: resolve the session's drivers again (off the socket thread). */
    record EntriesChanged(long sessionDbId) implements Op {
    }

    record LapDeleted(long sessionDbId, String car, int lap) implements Op {
    }

    record StintDeleted(long sessionDbId, String car, long startTimeMs) implements Op {
    }

    /** One lap's diff. Fields are public-by-package and set by the router as it reads them. */
    static final class LapPatch implements Op {
        static final int DRIVER = 1, DRIVER_LAP = 1 << 1, POSITION = 1 << 2, START = 1 << 3, TIME = 1 << 4,
                TOP_SPEED = 1 << 5, VALID = 1 << 6, LONG_LAP = 1 << 7, SHORT_LAP = 1 << 8, TRACK_LIMITS = 1 << 9,
                PIT_IN = 1 << 10, PIT_OUT = 1 << 11;

        final long sessionDbId;
        final String car;
        final int lap;
        int present;
        Integer driverOrder;
        Integer driverLapNumber;
        Integer position;
        Long startTimeMs;
        Integer lapTimeMs;
        Double topSpeed;
        Boolean valid;
        Boolean longLap;
        Boolean shortLap;
        Integer trackLimits;
        Long pitInMs;
        Long pitOutMs;
        /** Sector number → its diff (null = the sector was deleted). Null map = sectors untouched. */
        Map<Integer, Sector> sectors;
        /** The diff set "sectors": null. */
        boolean sectorsCleared;

        LapPatch(long sessionDbId, String car, int lap) {
            this.sessionDbId = sessionDbId;
            this.car = car;
            this.lap = lap;
        }

        boolean has(int field) {
            return (present & field) != 0;
        }

        Map<Integer, Sector> sectors() {
            if (sectors == null) {
                sectors = new TreeMap<>();
            }
            return sectors;
        }
    }

    /** One sector's diff; hasTime/hasFlag distinguish "set to null" from "not mentioned". */
    static final class Sector {
        boolean hasTime;
        Integer timeMs;
        boolean hasFlag;
        String flag;
    }

    static final class StintPatch implements Op {
        static final int TYPE = 1, PIT_TYPE = 1 << 1, DRIVER = 1 << 2, OPEN_LAP = 1 << 3, CLOSE_LAP = 1 << 4,
                FINISH = 1 << 5, ACCUM_SESSION_TRACK = 1 << 6, ACCUM_SESSION = 1 << 7, ACCUM_TRACK = 1 << 8,
                ACCUM = 1 << 9;

        final long sessionDbId;
        final String car;
        final long startTimeMs;
        int present;
        String type;
        String pitType;
        Integer driverOrder;
        Integer openLap;
        Integer closeLap;
        Long finishTimeMs;
        Long accumSessionTrackMs;
        Long accumSessionMs;
        Long accumTrackMs;
        Long accumMs;

        StintPatch(long sessionDbId, String car, long startTimeMs) {
            this.sessionDbId = sessionDbId;
            this.car = car;
            this.startTimeMs = startTimeMs;
        }

        boolean has(int field) {
            return (present & field) != 0;
        }
    }
}
