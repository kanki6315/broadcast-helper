-- Lift the championship "family + kind" (WeatherTech Teams, Endurance Cup Teams,
-- WeatherTech Drivers, ...) out of the loose group_title/kind strings on each
-- per-class championship into a first-class entity with explicit ordering. A
-- season's championship is one award set; its cups (Endurance, historically
-- Sprint) are sibling groups over a subset of the rounds — is_cup marks them.
-- The per-class championship rows now hang off a group.
CREATE TABLE championship_group (
    id        BIGSERIAL PRIMARY KEY,
    season_id BIGINT  NOT NULL REFERENCES season (id) ON DELETE CASCADE,
    family    TEXT    NOT NULL,          -- e.g. "IMSA WeatherTech SportsCar Championship"
    kind      TEXT,                      -- TEAMS | DRIVERS | MANUFACTURERS
    label     TEXT    NOT NULL,          -- display, e.g. "... — Teams"
    ordinal   INT     NOT NULL,          -- display order within the season
    is_cup    BOOLEAN NOT NULL DEFAULT FALSE, -- false = full-season championship
    UNIQUE (season_id, family, kind)
);

-- One group per distinct (season, family, kind) among existing championships.
-- is_cup: the family is not the series' own name (cups publish under their own
-- title, matched via a series alias). Primary groups sort before cups.
INSERT INTO championship_group (season_id, family, kind, label, ordinal, is_cup)
WITH distinct_groups AS (
    SELECT DISTINCT c.season_id,
           c.group_title AS family,
           c.kind,
           (c.group_title IS DISTINCT FROM sr.name) AS is_cup
    FROM championship c
             JOIN season s ON s.id = c.season_id
             JOIN series sr ON sr.id = s.series_id
    WHERE c.group_title IS NOT NULL
)
SELECT season_id, family, kind,
       family || ' — ' || COALESCE(initcap(lower(kind)), 'Overall') AS label,
       row_number() OVER (
           PARTITION BY season_id
           ORDER BY is_cup,
                    CASE kind WHEN 'TEAMS' THEN 0 WHEN 'DRIVERS' THEN 1
                              WHEN 'MANUFACTURERS' THEN 2 ELSE 3 END,
                    family
       ) AS ordinal,
       is_cup
FROM distinct_groups;

ALTER TABLE championship ADD COLUMN group_id BIGINT REFERENCES championship_group (id);

UPDATE championship c
SET group_id = g.id
FROM championship_group g
WHERE g.season_id = c.season_id
  AND g.family = c.group_title
  AND g.kind IS NOT DISTINCT FROM c.kind;

ALTER TABLE championship ALTER COLUMN group_id SET NOT NULL;
ALTER TABLE championship DROP COLUMN group_title;
ALTER TABLE championship DROP COLUMN kind;
