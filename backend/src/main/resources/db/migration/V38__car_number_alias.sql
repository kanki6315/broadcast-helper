-- One championship entrant, two car numbers. A TEAMS/ENTRANTS standings row is
-- keyed by the car number the standings source printed, and the recap matches
-- result cells onto it by each entry's number — which loses the rare season
-- where one entrant raced under another number (JDC-Miller's #5 GTP ran
-- Daytona 2026 as #85; van der Steur's #19 GTD entry transferred to Car
-- Blanche's #068 mid-season, points and all). team_alias cannot express
-- either: the first is one team however it's spelled, and the second is
-- deliberately two organizations (lineage, never a stats merge) — the thing
-- that persists across both is the ENTRANT, and the entrant's identity is a
-- car number scoped to one season and class. That is this table's scope: the
-- same number is a different car in another class (Car Blanche also runs #68
-- in GTDPRO) and a different entrant in another season. Consumers resolve an
-- entry's number to canonical_number before matching standings keys
-- (SeasonViewController.recap, the sheet's championship column); per-event
-- surfaces (lineups, results, the sheet's entry list) keep printing the
-- number as raced, because "#85 at Daytona" is real history.
CREATE TABLE car_number_alias (
    id               BIGSERIAL PRIMARY KEY,
    season_id        BIGINT NOT NULL REFERENCES season (id) ON DELETE CASCADE,
    class_name       TEXT   NOT NULL,
    car_number       TEXT   NOT NULL,
    canonical_number TEXT   NOT NULL,
    note             TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (length(trim(class_name)) > 0),
    CHECK (length(trim(car_number)) > 0),
    CHECK (length(trim(canonical_number)) > 0),
    -- A number mapped to itself (after the "04" = "4" normalization the
    -- matchers use) would be a no-op row.
    CHECK (regexp_replace(trim(car_number), '^0+(?=\d)', '')
           <> regexp_replace(trim(canonical_number), '^0+(?=\d)', ''))
);

-- One meaning per number within a season and class, with "04" and "4" the
-- same number (mirrors SheetController.normalizeCarNumber).
CREATE UNIQUE INDEX car_number_alias_key
    ON car_number_alias (season_id, lower(trim(class_name)),
                         regexp_replace(trim(car_number), '^0+(?=\d)', ''));
