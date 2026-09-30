-- Lap and stint history from the Al Kamel live feed (timing.analysis), kept
-- permanently for the timing page and drive-time checks. A 24-hour race is
-- ~39k laps: far too much to hold in the 256 MB heap as a JSON tree, so the
-- feed is stream-parsed straight into these rows and only small per-car
-- summaries stay in memory (see docs/LIVE_TIMING.md).
--
-- Keyed by Al Kamel's own session id (timing.session.info.sessionDbId), not
-- by anything of ours: the feed is never trusted to pick the Pit Pass event,
-- so event_id is only the event an admin had bound when the session was
-- first seen, and may be null. Car numbers are TEXT exactly as the feed
-- writes them: #04 and #4 are different cars.
CREATE TABLE live_session (
    session_db_id    BIGINT PRIMARY KEY,
    event_id         BIGINT REFERENCES event (id) ON DELETE SET NULL,
    session_mongo_id TEXT,
    feed_event_db_id BIGINT,
    name             TEXT,
    type             TEXT,
    -- timing.session.info.date, epoch ms as the feed sends it
    session_date_ms  BIGINT,
    first_seen_at    timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_at       timestamptz NOT NULL DEFAULT clock_timestamp()
);

CREATE INDEX live_session_event_idx ON live_session (event_id);

-- One row per lap (timing.analysis.laps.<car>.laps.<lap>). Every column but
-- the key is nullable: diffs arrive partial, and a lap in progress has no
-- time yet. driver_order is the feed's driver number within the car
-- ("1", "2", …), resolved to a person through live_driver. Sector arrays are
-- 1-based by sector number; loopSectors and sections are never stored.
CREATE TABLE live_lap (
    session_db_id     BIGINT  NOT NULL REFERENCES live_session (session_db_id) ON DELETE CASCADE,
    car_number        TEXT    NOT NULL,
    lap_number        INT     NOT NULL,
    driver_order      INT,
    driver_lap_number INT,
    position          INT,
    start_time_ms     BIGINT,
    lap_time_ms       INT,
    top_speed         REAL,
    is_valid          BOOLEAN,
    is_long_lap       BOOLEAN,
    is_short_lap      BOOLEAN,
    track_limits      INT,
    pit_in_time_ms    BIGINT,
    pit_out_time_ms   BIGINT,
    sector_ms         INT[],
    sector_flags      TEXT[],
    PRIMARY KEY (session_db_id, car_number, lap_number)
);

-- One row per stint (timing.analysis.stints.<car>.stints.<startTime>).
-- Al Kamel computes the stints (info.stintCalcType, "IMSA" at IMSA). The four
-- accumulators are the driver's running totals at this stint;
-- driver_accum_session_track_ms (pit lane excluded) is what the IMSA drive
-- time rule counts. How often they update is unverified until a real
-- practice session is recorded.
CREATE TABLE live_stint (
    session_db_id                 BIGINT NOT NULL REFERENCES live_session (session_db_id) ON DELETE CASCADE,
    car_number                    TEXT   NOT NULL,
    start_time_ms                 BIGINT NOT NULL,
    type                          TEXT,
    pit_type                      TEXT,
    driver_order                  INT,
    open_lap_number               INT,
    close_lap_number              INT,
    finish_time_ms                BIGINT,
    driver_accum_session_track_ms BIGINT,
    driver_accum_session_ms       BIGINT,
    driver_accum_track_ms         BIGINT,
    driver_accum_ms               BIGINT,
    PRIMARY KEY (session_db_id, car_number, start_time_ms)
);

-- Who driver_order N of a car is, from timing.session.entry.<car>.drivers,
-- and the Pit Pass driver it resolved to (surname within the matched entry).
-- An unmatched driver keeps the feed's name with driver_id null — listed,
-- never dropped. license is the feed's Bronze/Silver/Gold/Platinum, the
-- fallback when our own rating is unknown.
CREATE TABLE live_driver (
    session_db_id BIGINT NOT NULL REFERENCES live_session (session_db_id) ON DELETE CASCADE,
    car_number    TEXT   NOT NULL,
    driver_order  INT    NOT NULL,
    first_name    TEXT,
    last_name     TEXT,
    short_name    TEXT,
    license       TEXT,
    country       TEXT,
    entry_id      BIGINT REFERENCES entry (id) ON DELETE SET NULL,
    driver_id     BIGINT REFERENCES driver (id) ON DELETE SET NULL,
    rating        TEXT,
    PRIMARY KEY (session_db_id, car_number, driver_order)
);

-- A partial diff can touch one sector of a lap. The writer sends the sector
-- part of a diff as JSON, {"<n>": {"ms": …, "flag": …} | null}, and these
-- apply it to the stored arrays: a key present sets that slot (null clears
-- it), a key absent leaves it, a null sector clears both slots. A null patch
-- leaves the array alone; a JSON null patch (the diff deleted every sector)
-- clears it. Arrays grow with NULLs so slot n is always sector n.
CREATE FUNCTION live_patch_int(base INT[], patch JSONB, field TEXT) RETURNS INT[]
    LANGUAGE plpgsql IMMUTABLE AS
$$
DECLARE
    r INT[] := base;
    k TEXT;
    v JSONB;
    i INT;
BEGIN
    IF patch IS NULL THEN
        RETURN base;
    END IF;
    IF jsonb_typeof(patch) <> 'object' THEN
        RETURN NULL;
    END IF;
    FOR k, v IN SELECT * FROM jsonb_each(patch) LOOP
        i := k::INT;
        IF jsonb_typeof(v) = 'null' THEN
            IF r IS NOT NULL AND i <= cardinality(r) THEN
                r[i] := NULL;
            END IF;
        ELSIF v ? field THEN
            r := COALESCE(r, '{}');
            IF cardinality(r) < i THEN
                r := r || array_fill(NULL::INT, ARRAY[i - cardinality(r)]);
            END IF;
            r[i] := (v ->> field)::INT;
        END IF;
    END LOOP;
    RETURN r;
END
$$;

CREATE FUNCTION live_patch_text(base TEXT[], patch JSONB, field TEXT) RETURNS TEXT[]
    LANGUAGE plpgsql IMMUTABLE AS
$$
DECLARE
    r TEXT[] := base;
    k TEXT;
    v JSONB;
    i INT;
BEGIN
    IF patch IS NULL THEN
        RETURN base;
    END IF;
    IF jsonb_typeof(patch) <> 'object' THEN
        RETURN NULL;
    END IF;
    FOR k, v IN SELECT * FROM jsonb_each(patch) LOOP
        i := k::INT;
        IF jsonb_typeof(v) = 'null' THEN
            IF r IS NOT NULL AND i <= cardinality(r) THEN
                r[i] := NULL;
            END IF;
        ELSIF v ? field THEN
            r := COALESCE(r, '{}');
            IF cardinality(r) < i THEN
                r := r || array_fill(NULL::TEXT, ARRAY[i - cardinality(r)]);
            END IF;
            r[i] := v ->> field;
        END IF;
    END LOOP;
    RETURN r;
END
$$;
