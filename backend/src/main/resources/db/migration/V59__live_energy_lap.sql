-- Energy remaining per car per lap, from IMSA's telemetry (imsa.com/telemetry,
-- an AppSync Events websocket — unofficial, see docs/LIVE_TIMING.md). The
-- stream is ~1 Hz per car; only the reading at each lap crossing is kept: the
-- first reading after scoring.lapNumber goes up, stored against the lap just
-- completed. Energy used on lap n is then energy(n-1) - energy(n).
--
-- Keyed to the Al Kamel session being fed at the time (live_session), since
-- that is what laps and stints are keyed by. car_number is IMSA's
-- scoring.number exactly as sent (#04 is not #4). Whether IMSA's lap count
-- matches Al Kamel's or runs one off is unverified until a live weekend.
CREATE TABLE live_energy_lap (
    session_db_id BIGINT  NOT NULL REFERENCES live_session (session_db_id) ON DELETE CASCADE,
    car_number    TEXT    NOT NULL,
    lap_number    INT     NOT NULL,
    energy_pct    REAL    NOT NULL,
    pit_lane      BOOLEAN,
    recorded_at   timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (session_db_id, car_number, lap_number)
);
