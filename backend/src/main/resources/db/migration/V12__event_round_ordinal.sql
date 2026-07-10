-- Round number of an event within its season (Rolex 24 = 1, ...). It's the
-- axis for pre-round standings snapshots (standings as they stood going into a
-- round) and, later, the season reference table and championship trend graph.
-- Backfilled by calendar order; the importer keeps it current by renumbering
-- the season whenever an event is created.
ALTER TABLE event ADD COLUMN round_ordinal INT;

WITH ranked AS (
    SELECT id,
           row_number() OVER (PARTITION BY season_id ORDER BY event_date NULLS LAST, id) AS rn
    FROM event
)
UPDATE event e
SET round_ordinal = ranked.rn
FROM ranked
WHERE ranked.id = e.id;
