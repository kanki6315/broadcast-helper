-- 2021 IMSA WeatherTech split qualifying by purpose as well as class: "GTD
-- Position" set the GTD grid while "GTD Points" (often run with GTLM) only
-- scored championship points. A points-only result is still a real
-- classification, but it is not qualifying for pole or the sheet's Q column.
-- Set at commit from the session name; false everywhere else.
ALTER TABLE result ADD COLUMN points_only BOOLEAN NOT NULL DEFAULT false;
