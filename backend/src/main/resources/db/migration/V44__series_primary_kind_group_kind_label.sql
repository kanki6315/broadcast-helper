-- Two knobs that were hard-coded as "Teams first, called Teams":
--
-- series.primary_kind: which championship kind is the series' headline. The
-- season hub's kind chips, the recap modal and the sheet's champ column all
-- ranked TEAMS ahead of DRIVERS unconditionally — right for WeatherTech, wrong
-- for a one-make series like Mustang Challenge where the drivers' title is the
-- story. NULL keeps the historical Teams-first default, so every existing
-- series renders exactly as before.
--
-- championship_group.kind_label: what THIS series calls the kind. Kind stays a
-- closed set (DRIVERS | TEAMS | MANUFACTURERS) because it decides how standings
-- rows match entries; the wording is cosmetic. Mustang Challenge prints
-- "Entrants" for what IWSC calls "Teams". NULL means the title-cased kind.
ALTER TABLE series
    ADD COLUMN primary_kind TEXT CHECK (primary_kind IN ('DRIVERS', 'TEAMS', 'MANUFACTURERS'));

ALTER TABLE championship_group
    ADD COLUMN kind_label TEXT;

-- Groups created before the kind set was closed could carry ENTRANTS, which no
-- code path recognises (the Teams pages join on kind = 'TEAMS', the hub sorts
-- it last). It IS a Teams championship with different wording — store it that
-- way.
UPDATE championship_group
SET kind = 'TEAMS', kind_label = 'Entrants', label = family || ' — Entrants'
WHERE kind = 'ENTRANTS';
