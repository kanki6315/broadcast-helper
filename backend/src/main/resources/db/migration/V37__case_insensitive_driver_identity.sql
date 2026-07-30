-- Driver identity becomes case-insensitive. Results CSVs/PDFs shout names
-- ("CHAD GILSINGER"), and UNIQUE (first_name, surname) let that spelling mint
-- a second row for an existing "Chad Gilsinger"; the entry-list commit's
-- lower()-ed full-name lookup then matched both rows and failed the whole
-- commit ("Incorrect result size: expected 1, actual 2"). Merge the existing
-- case-twins first (keeping the properly cased row), then replace the
-- constraint with a unique index on the lower()-ed pair, which the
-- ON CONFLICT targets in ImportService infer.

-- Each duplicate row mapped to the row that survives it: prefer a row that
-- isn't all-caps, then the oldest.
CREATE TEMP TABLE case_twin ON COMMIT DROP AS
WITH ranked AS (
    SELECT id,
           lower(first_name) AS lf,
           lower(surname)    AS ls,
           row_number() OVER (
               PARTITION BY lower(first_name), lower(surname)
               ORDER BY (first_name || ' ' || surname) = upper(first_name || ' ' || surname), id
           ) AS rn
    FROM driver
)
SELECT d.id AS dup_id, k.id AS keeper_id
FROM ranked d
         JOIN ranked k ON k.lf = d.lf AND k.ls = d.ls AND k.rn = 1
WHERE d.rn > 1;

-- Bio fields the keeper is missing but a twin has.
UPDATE driver k
SET country        = COALESCE(k.country, agg.country),
    hometown       = COALESCE(k.hometown, agg.hometown),
    date_of_birth  = COALESCE(k.date_of_birth, agg.date_of_birth),
    place_of_birth = COALESCE(k.place_of_birth, agg.place_of_birth),
    pronunciation  = COALESCE(k.pronunciation, agg.pronunciation),
    notes          = COALESCE(k.notes, agg.notes)
FROM (SELECT m.keeper_id,
             min(d.country)        AS country,
             min(d.hometown)       AS hometown,
             min(d.date_of_birth)  AS date_of_birth,
             min(d.place_of_birth) AS place_of_birth,
             min(d.pronunciation)  AS pronunciation,
             min(d.notes)          AS notes
      FROM case_twin m
               JOIN driver d ON d.id = m.dup_id
      GROUP BY m.keeper_id) agg
WHERE k.id = agg.keeper_id;

-- One photo per driver (PK): the keeper's wins, a twin's fills the gap.
DELETE
FROM driver_photo p USING case_twin m
WHERE p.driver_id = m.dup_id
  AND EXISTS (SELECT 1 FROM driver_photo kp WHERE kp.driver_id = m.keeper_id);
UPDATE driver_photo p
SET driver_id = m.keeper_id
FROM case_twin m
WHERE p.driver_id = m.dup_id;

-- Seat rows: both twins on one entry is the same human twice, drop the dup's.
DELETE
FROM driver_assignment da USING case_twin m
WHERE da.driver_id = m.dup_id
  AND EXISTS (SELECT 1
              FROM driver_assignment ka
              WHERE ka.entry_id = da.entry_id AND ka.driver_id = m.keeper_id);
UPDATE driver_assignment da
SET driver_id = m.keeper_id
FROM case_twin m
WHERE da.driver_id = m.dup_id;

UPDATE grid_position g
SET qualifying_driver_id = m.keeper_id
FROM case_twin m
WHERE g.qualifying_driver_id = m.dup_id;
UPDATE grid_position g
SET starting_driver_id = m.keeper_id
FROM case_twin m
WHERE g.starting_driver_id = m.dup_id;

-- UNIQUE (season_id, driver_id, effective_from_round): keeper's row wins.
DELETE
FROM season_driver_team_assignment s USING case_twin m
WHERE s.driver_id = m.dup_id
  AND EXISTS (SELECT 1
              FROM season_driver_team_assignment ks
              WHERE ks.season_id = s.season_id
                AND ks.driver_id = m.keeper_id
                AND ks.effective_from_round = s.effective_from_round);
UPDATE season_driver_team_assignment s
SET driver_id = m.keeper_id
FROM case_twin m
WHERE s.driver_id = m.dup_id;

-- Privateer placeholder teams are unique per (season, driver): where both
-- twins have one in a season, fold the dup's team row into the keeper's
-- before repointing the rest.
CREATE TEMP TABLE privateer_team_merge ON COMMIT DROP AS
SELECT dt.id AS dup_team_id, kt.id AS keeper_team_id
FROM case_twin m
         JOIN season_team dt ON dt.privateer_driver_id = m.dup_id
         JOIN season_team kt ON kt.privateer_driver_id = m.keeper_id AND kt.season_id = dt.season_id;
UPDATE entry e
SET season_team_id = p.keeper_team_id
FROM privateer_team_merge p
WHERE e.season_team_id = p.dup_team_id;
UPDATE season_driver_team_assignment s
SET team_id = p.keeper_team_id
FROM privateer_team_merge p
WHERE s.team_id = p.dup_team_id;
DELETE
FROM season_team st USING privateer_team_merge p
WHERE st.id = p.dup_team_id;
UPDATE season_team st
SET privateer_driver_id = m.keeper_id
FROM case_twin m
WHERE st.privateer_driver_id = m.dup_id;

DELETE
FROM driver d USING case_twin m
WHERE d.id = m.dup_id;

ALTER TABLE driver
    DROP CONSTRAINT driver_first_name_surname_key;
CREATE UNIQUE INDEX driver_identity_key ON driver (lower(first_name), lower(surname));
