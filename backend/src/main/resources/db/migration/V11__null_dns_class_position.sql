-- A car that did not start has no in-class finishing position. The early
-- results importer derived position_in_class by counting rows in class order,
-- which fabricated a position for DNS cars (e.g. #79 LMP2 shown "12th" at
-- Sebring though it never started). The parser no longer counts them; null out
-- the positions already stored so best/last and the season reference table read
-- them as DNS, not a finish. DNS cars sort to the bottom of the classification,
-- so removing them leaves the classified cars' 1..n positions contiguous.
UPDATE result
SET position_in_class = NULL
WHERE lower(trim(status)) IN ('not started', 'did not start', 'dns', 'dnp');
