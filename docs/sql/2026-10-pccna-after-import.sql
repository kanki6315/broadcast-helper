-- Carrera Cup NA import: run once in production AFTER the 2024 and 2026 imports.
-- The 2026 Miami make-up move is its own file: 2026-pccna-miami-makeup-repair.sql.
BEGIN;

-- The 2024 "PCCNA Pro" points JSON labels Road Atlanta's second race
-- "WeatherTech Raceway", a ninth round that shifts the recap's last columns.
UPDATE championship_session cs
SET event_name = 'Michelin Raceway'
FROM championship c
JOIN season s ON s.id = c.season_id
JOIN series se ON se.id = s.series_id
WHERE cs.championship_id = c.id
  AND se.name = 'Porsche Carrera Cup North America' AND s.year = 2024
  AND cs.event_name = 'WeatherTech Raceway';

COMMIT;
