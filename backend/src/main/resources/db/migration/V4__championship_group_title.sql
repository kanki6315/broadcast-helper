-- The "family" a championship belongs to within its series: the title prefix
-- the importer matched, e.g. "IMSA WeatherTech SportsCar Championship" or
-- "IMSA Michelin Endurance Cup". Used to group championships in the UI.

ALTER TABLE championship
    ADD COLUMN group_title TEXT;

-- Backfill existing rows: every imported title ends with "<class> <Kind>"
-- (e.g. "... GT Daytona PRO Teams"), so stripping that suffix leaves the group.
UPDATE championship
SET group_title = trim(replace(title, ' ' || class_name || ' ' || initcap(lower(kind)), ''))
WHERE class_name IS NOT NULL
  AND kind IS NOT NULL;
