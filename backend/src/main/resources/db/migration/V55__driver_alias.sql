-- Spellings that resolve to an existing driver. A driver merge (POST
-- /api/drivers/{id}/merge) retires the absorbed row, and without a record of
-- its name the next import of that spelling — an iRacing display name like
-- "Jaden Munoz2" — would simply mint the duplicate again. Imports consult this
-- table after an exact name match fails and before creating a driver, and the
-- driver profile uses it to find standings still published under the old
-- name. Mirrors team_alias (V35), minus the display-name row: a driver's own
-- name lives on the driver.
CREATE TABLE driver_alias (
    id        BIGSERIAL PRIMARY KEY,
    driver_id BIGINT NOT NULL REFERENCES driver (id) ON DELETE CASCADE,
    alias     TEXT   NOT NULL
);

-- One owner per spelling, whitespace runs and case ignored — the same
-- normalisation the importer's whole-name match applies.
CREATE UNIQUE INDEX driver_alias_key ON driver_alias (lower(regexp_replace(trim(alias), '\s+', ' ', 'g')));
CREATE INDEX driver_alias_driver_idx ON driver_alias (driver_id);
