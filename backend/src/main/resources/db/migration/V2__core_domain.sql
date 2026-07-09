-- Core domain: seasons, events, sessions, entries, drivers, results,
-- championships/standings, and the import staging table.

CREATE TABLE season (
    id        BIGSERIAL PRIMARY KEY,
    series_id BIGINT NOT NULL REFERENCES series (id),
    year      INT    NOT NULL,
    UNIQUE (series_id, year)
);

CREATE TABLE event (
    id               BIGSERIAL PRIMARY KEY,
    season_id        BIGINT NOT NULL REFERENCES season (id),
    name             TEXT   NOT NULL,
    circuit_name     TEXT,
    circuit_length_m NUMERIC,
    country          TEXT,
    event_date       DATE,
    UNIQUE (season_id, name)
);

CREATE TABLE race_session (
    id             BIGSERIAL PRIMARY KEY,
    event_id       BIGINT NOT NULL REFERENCES event (id) ON DELETE CASCADE,
    session_type   TEXT   NOT NULL, -- PRACTICE | QUALIFYING | RACE
    name           TEXT   NOT NULL,
    session_start  TIMESTAMPTZ,
    report_mark    TEXT,
    report_message TEXT,
    UNIQUE (event_id, name)
);

-- NOTE: drivers are deduplicated by name; two drivers sharing an exact full
-- name would collide. Acceptable for now, revisit if it ever happens.
CREATE TABLE driver (
    id         BIGSERIAL PRIMARY KEY,
    first_name TEXT NOT NULL,
    surname    TEXT NOT NULL,
    country    TEXT,
    hometown   TEXT,
    UNIQUE (first_name, surname)
);

-- Car numbers are TEXT on purpose: 04 <> 4 and 023 <> 23. Unique per event.
CREATE TABLE entry (
    id           BIGSERIAL PRIMARY KEY,
    event_id     BIGINT  NOT NULL REFERENCES event (id) ON DELETE CASCADE,
    car_number   TEXT    NOT NULL,
    class_name   TEXT    NOT NULL,
    team_name    TEXT    NOT NULL,
    vehicle      TEXT,
    manufacturer TEXT,
    class_group  TEXT,   -- e.g. IMSA GTD group "B"
    is_guest     BOOLEAN NOT NULL DEFAULT FALSE,
    UNIQUE (event_id, car_number)
);

-- Ratings live here, per event, because series can override a driver's FIA
-- rating for an event or season (derogations).
CREATE TABLE driver_assignment (
    id         BIGSERIAL PRIMARY KEY,
    entry_id   BIGINT NOT NULL REFERENCES entry (id) ON DELETE CASCADE,
    driver_id  BIGINT NOT NULL REFERENCES driver (id),
    seat_order INT    NOT NULL,
    rating     TEXT,
    UNIQUE (entry_id, seat_order)
);

CREATE TABLE result (
    id                      BIGSERIAL PRIMARY KEY,
    session_id              BIGINT  NOT NULL REFERENCES race_session (id) ON DELETE CASCADE,
    entry_id                BIGINT  NOT NULL REFERENCES entry (id) ON DELETE CASCADE,
    position_overall        INT,
    position_in_class       INT,
    status                  TEXT,
    not_finished            BOOLEAN NOT NULL DEFAULT FALSE,
    not_finished_cause      TEXT,
    laps                    INT,
    elapsed_time            TEXT,
    gap_first               TEXT,
    gap_previous            TEXT,
    fastest_lap_time        TEXT,
    fastest_lap_number      INT,
    fastest_lap_kph         NUMERIC,
    fastest_lap_driver_seat INT,
    pit_stops               INT,
    UNIQUE (session_id, entry_id)
);

CREATE TABLE championship (
    id         BIGSERIAL PRIMARY KEY,
    season_id  BIGINT NOT NULL REFERENCES season (id),
    name       TEXT   NOT NULL, -- source name, e.g. "IWSC GTP TEAMS"
    title      TEXT,            -- e.g. "IMSA WeatherTech SportsCar Championship GTP Teams"
    class_name TEXT,            -- e.g. "GTP"
    kind       TEXT,            -- TEAMS | DRIVERS | MANUFACTURERS | CUP
    UNIQUE (season_id, name)
);

-- The season calendar as published inside a standings file: one row per
-- points-scoring session (Qualifying and Race per round, in order).
CREATE TABLE championship_session (
    id              BIGSERIAL PRIMARY KEY,
    championship_id BIGINT NOT NULL REFERENCES championship (id) ON DELETE CASCADE,
    session_index   INT    NOT NULL,
    event_name      TEXT   NOT NULL,
    session_name    TEXT   NOT NULL,
    UNIQUE (championship_id, session_index)
);

CREATE TABLE standings_row (
    id               BIGSERIAL PRIMARY KEY,
    championship_id  BIGINT  NOT NULL REFERENCES championship (id) ON DELETE CASCADE,
    position         INT     NOT NULL,
    competitor_key   TEXT    NOT NULL, -- car number for team championships
    competitor_name  TEXT,
    total_points     NUMERIC NOT NULL,
    net_position     INT,
    total_net_points NUMERIC,
    provenance       TEXT    NOT NULL DEFAULT 'imported', -- imported | manual
    UNIQUE (championship_id, competitor_key)
);

CREATE TABLE standings_session_points (
    id                 BIGSERIAL PRIMARY KEY,
    standings_row_id   BIGINT  NOT NULL REFERENCES standings_row (id) ON DELETE CASCADE,
    session_index      INT     NOT NULL,
    total_points       NUMERIC NOT NULL DEFAULT 0,
    race_points        NUMERIC NOT NULL DEFAULT 0,
    pole_points        NUMERIC NOT NULL DEFAULT 0,
    fastest_lap_points NUMERIC NOT NULL DEFAULT 0,
    penalty_points     NUMERIC NOT NULL DEFAULT 0,
    status             TEXT, -- '', did_not_race, not_classified
    UNIQUE (standings_row_id, session_index)
);

-- Every import lands here first; nothing touches domain tables until commit.
CREATE TABLE import_batch (
    id           BIGSERIAL PRIMARY KEY,
    kind         TEXT        NOT NULL, -- RACE_RESULTS | STANDINGS
    filename     TEXT        NOT NULL,
    status       TEXT        NOT NULL DEFAULT 'STAGED', -- STAGED | COMMITTED | DISCARDED
    payload      JSONB       NOT NULL, -- normalized parsed content
    summary      TEXT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    committed_at TIMESTAMPTZ
);
