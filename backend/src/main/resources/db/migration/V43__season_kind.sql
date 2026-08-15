-- A season is either the series proper (MAIN) or a qualifying stage
-- (QUALIFIER) — the public qualifying series or one of the regional
-- championships run before the main season to select its grid. Which one a
-- season is cannot be inferred from imported data: an iRacing standings
-- payload for a regional qualifier looks exactly like a real season. So the
-- kind is flipped by hand on the Series page once a stage's imports are done
-- (the importer only ever matches or creates MAIN seasons). Qualifier results
-- never count toward series all-time stats, profile career totals, or the
-- sheet's prior-year notes; their seasons render badged, never as the series'
-- current season.
--
-- Several qualifying stages share a year (four regionals feed one PESC
-- season), so one-season-per-year now applies to MAIN seasons only. label
-- names the stage ("Regional — Europe"); MAIN seasons leave it NULL.
ALTER TABLE season
    ADD COLUMN kind  TEXT NOT NULL DEFAULT 'MAIN' CHECK (kind IN ('MAIN', 'QUALIFIER')),
    ADD COLUMN label TEXT;

ALTER TABLE season DROP CONSTRAINT season_series_id_year_key;
CREATE UNIQUE INDEX season_main_one_per_year ON season (series_id, year) WHERE kind = 'MAIN';
CREATE UNIQUE INDEX season_qualifier_stage_label ON season (series_id, year, label) WHERE kind = 'QUALIFIER';
