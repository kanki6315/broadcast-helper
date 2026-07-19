-- Driver-based championships (notably iRacing leagues) often publish no team
-- identity. Keep a season-scoped team catalogue and effective-from-round
-- assignments, while materialising the resolved display name back onto entry
-- so every existing browse/sheet query continues to work unchanged.
CREATE TABLE season_team (
    id                    BIGSERIAL PRIMARY KEY,
    season_id             BIGINT NOT NULL REFERENCES season (id) ON DELETE CASCADE,
    name                  TEXT   NOT NULL,
    privateer_driver_id   BIGINT REFERENCES driver (id) ON DELETE CASCADE,
    CHECK (length(trim(name)) > 0),
    CHECK (privateer_driver_id IS NULL OR lower(trim(name)) = 'privateer')
);

CREATE UNIQUE INDEX season_team_name_unique
    ON season_team (season_id, lower(trim(name)))
    WHERE privateer_driver_id IS NULL;

CREATE UNIQUE INDEX season_team_privateer_unique
    ON season_team (season_id, privateer_driver_id)
    WHERE privateer_driver_id IS NOT NULL;

CREATE TABLE season_driver_team_assignment (
    id                    BIGSERIAL PRIMARY KEY,
    season_id             BIGINT NOT NULL REFERENCES season (id) ON DELETE CASCADE,
    driver_id             BIGINT NOT NULL REFERENCES driver (id) ON DELETE CASCADE,
    team_id               BIGINT NOT NULL REFERENCES season_team (id) ON DELETE CASCADE,
    effective_from_round  INT    NOT NULL CHECK (effective_from_round >= 1),
    UNIQUE (season_id, driver_id, effective_from_round)
);

CREATE INDEX season_driver_team_lookup
    ON season_driver_team_assignment (season_id, driver_id, effective_from_round DESC);

ALTER TABLE entry
    ADD COLUMN season_team_id BIGINT REFERENCES season_team (id) ON DELETE SET NULL;
