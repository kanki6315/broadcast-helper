-- The track's weather station from the Al Kamel feed (weather.sessionData,
-- spec 1.0.36 §4.3): one reading a minute per session, keyed by the reading's
-- dayTime (epoch ms UTC), for the timing page's weather chart. Written by the
-- analysis writer, so only while ALKAMELV2_ANALYSIS_ENABLED is on; the latest
-- reading (weather.currentData) is read live from the feed and never stored.
--
-- Both unit systems are kept as the tower serves them: whichever the station
-- did not send is converted from the other (LiveWeather). A null reading in a
-- diff deletes its row; a null channel deletes nothing. Whether sessionData is
-- cleared at a session change is unverified against a recording.
CREATE TABLE live_weather (
    session_db_id  BIGINT NOT NULL REFERENCES live_session (session_db_id) ON DELETE CASCADE,
    day_time_ms    BIGINT NOT NULL,
    air_c          REAL,
    air_f          REAL,
    track_c        REAL,
    track_f        REAL,
    humidity_pct   REAL,
    pressure_mbar  REAL,
    pressure_inhg  REAL,
    -- degrees, as the station sends it
    wind_direction SMALLINT,
    wind_kmh       REAL,
    wind_mph       REAL,
    recorded_at    timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (session_db_id, day_time_ms)
);
