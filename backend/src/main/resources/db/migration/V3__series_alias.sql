-- Alternate title prefixes that map standings files to a series. Example: the
-- Michelin Endurance Cup is a championship within the IMSA WeatherTech
-- SportsCar Championship, but its standings files are titled
-- "IMSA Michelin Endurance Cup ..." — an alias on the IMSA series makes the
-- importer attach them to the right season.

CREATE TABLE series_alias (
    id        BIGSERIAL PRIMARY KEY,
    series_id BIGINT NOT NULL REFERENCES series (id) ON DELETE CASCADE,
    alias     TEXT   NOT NULL UNIQUE
);
