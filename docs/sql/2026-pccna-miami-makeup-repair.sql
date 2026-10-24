-- Carrera Cup NA 2026: Miami's Race 2 was postponed and run at Road America
-- on 1 Aug as "Miami Make-Up - Race 2". The organiser's standings count it as
-- Miami's Round 6, but the Al Kamel import files it under Road America (a third
-- race there) and leaves Miami's Race 2 with the planned grid and no results.
-- This moves the make-up's results, grid and flags onto Miami's Race 2, which
-- keeps its name and ordinal; the make-up's own grid replaces the planned one.
-- Run once after the 2026 import (and again after any re-import of Road America).
BEGIN;

CREATE TEMP TABLE mk ON COMMIT DROP AS
SELECT src.id AS src_session, src.event_id AS src_event, dst.id AS dst_session, dst.event_id AS dst_event
FROM race_session src
JOIN event ra ON ra.id = src.event_id
JOIN season s ON s.id = ra.season_id
JOIN series se ON se.id = s.series_id
JOIN event mia ON mia.season_id = s.id AND mia.name ILIKE 'Miami%'
JOIN race_session dst ON dst.event_id = mia.id AND dst.session_type = 'RACE' AND dst.ordinal = 2
WHERE se.name = 'Porsche Carrera Cup North America' AND s.year = 2026 AND s.kind = 'MAIN'
  AND src.name = 'Miami Make-Up - Race 2';

DO $$ BEGIN
    IF (SELECT count(*) FROM mk) <> 1 THEN
        RAISE EXCEPTION 'expected exactly one make-up session and one Miami Race 2, found %', (SELECT count(*) FROM mk);
    END IF;
END $$;

-- A make-up car with no Miami entry would have nowhere to go.
DO $$ BEGIN
    IF EXISTS (SELECT 1 FROM result r JOIN entry ra ON ra.id = r.entry_id, mk
               WHERE r.session_id = mk.src_session
                 AND NOT EXISTS (SELECT 1 FROM entry m WHERE m.event_id = mk.dst_event AND m.car_number = ra.car_number)) THEN
        RAISE EXCEPTION 'a make-up car has no Miami entry';
    END IF;
END $$;

DELETE FROM grid_position g USING mk WHERE g.session_id = mk.dst_session;
DELETE FROM session_flag f USING mk WHERE f.session_id = mk.dst_session;

UPDATE result r
SET session_id = mk.dst_session,
    entry_id = (SELECT m.id FROM entry m WHERE m.event_id = mk.dst_event AND m.car_number = ra.car_number)
FROM mk, entry ra
WHERE r.session_id = mk.src_session AND ra.id = r.entry_id;

UPDATE grid_position g
SET session_id = mk.dst_session,
    entry_id = (SELECT m.id FROM entry m WHERE m.event_id = mk.dst_event AND m.car_number = ra.car_number)
FROM mk, entry ra
WHERE g.session_id = mk.src_session AND ra.id = g.entry_id;

UPDATE session_flag f SET session_id = mk.dst_session FROM mk WHERE f.session_id = mk.src_session;

UPDATE race_session dst
SET session_start = src.session_start,
    report_mark = src.report_mark,
    report_message = concat_ws(E'\n', 'Run at Road America on 1 August as the Miami make-up.',
                               nullif(src.report_message, ''))
FROM mk, race_session src
WHERE dst.id = mk.dst_session AND src.id = mk.src_session;

DELETE FROM race_session rs USING mk WHERE rs.id = mk.src_session;

COMMIT;
