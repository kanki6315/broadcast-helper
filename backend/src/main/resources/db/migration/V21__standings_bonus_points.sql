-- Bonus points whose origin is unknown.
--
-- The standings JSON splits a session's extras into pole_points and
-- fastest_lap_points. A championship-points PDF prints them added together in a
-- single "Extra" column: a 20 is pole + fastest lap, but a 10 could be either,
-- and the sheet never says which. Series that publish a JSON should be imported
-- from it and keep using the split columns; this column is for the series that
-- only ever publish the PDF, where recording the total as-is is the honest
-- option and inventing a split is not.
ALTER TABLE standings_session_points
    ADD COLUMN bonus_points NUMERIC NOT NULL DEFAULT 0;
