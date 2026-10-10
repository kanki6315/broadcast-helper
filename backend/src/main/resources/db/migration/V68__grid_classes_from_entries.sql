-- A grid sheet that prints no class (Carrera Cup Asia's Time Service Asia,
-- Fuji and Zhuhai grids) can't number cars within their class itself: the
-- mapper counted the whole field as one blank class, so position_in_class
-- came out equal to position_overall. Such a grid now takes its classes from
-- the event's entries, flagged here so later results/entry-list commits that
-- correct a car's class renumber it again.
ALTER TABLE race_session ADD COLUMN grid_classes_from_entries BOOLEAN NOT NULL DEFAULT FALSE;

-- Repair grids already imported that way. A grid whose in-class number equals
-- its overall position on every row while its cars span two or more classes
-- can't be a real per-class count (the first car of the second class would
-- read 1), so it is one of these.
WITH affected AS (
    SELECT g.session_id
    FROM grid_position g JOIN entry e ON e.id = g.entry_id
    GROUP BY g.session_id
    HAVING bool_and(g.position_in_class IS NOT DISTINCT FROM g.position_overall)
       AND count(DISTINCT e.class_name) >= 2
)
UPDATE race_session SET grid_classes_from_entries = TRUE
WHERE id IN (SELECT session_id FROM affected);

UPDATE grid_position g
SET position_in_class = ranked.rn
FROM (
    SELECT g2.id, row_number() OVER (PARTITION BY g2.session_id, e.class_name ORDER BY g2.position_overall) AS rn
    FROM grid_position g2
             JOIN entry e ON e.id = g2.entry_id
             JOIN race_session rs ON rs.id = g2.session_id
    WHERE rs.grid_classes_from_entries
) ranked
WHERE g.id = ranked.id;
