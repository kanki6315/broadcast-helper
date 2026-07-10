-- Canonicalize championship class names to the entry-list spelling, which is
-- the authority for a season (Phase 2 decision). The IMSA Michelin Endurance
-- Cup standings spell classes long-form ("GT Daytona PRO", "GT Daytona") while
-- entries — and the results files and sheet that join on them — use the short
-- codes ("GTDPRO", "GTD"). Rewrite each championship's class to the matching
-- entry class in the same season, comparing case- and space-insensitively, with
-- a small alias list for the spellings that differ by more than whitespace.
WITH alias (variant, canonical_norm) AS (
    VALUES ('gtdaytonapro', 'gtdpro'),
           ('gtdaytona', 'gtd')
),
champ_target AS (
    SELECT c.id,
           c.season_id,
           COALESCE(a.canonical_norm, lower(replace(c.class_name, ' ', ''))) AS target_norm
    FROM championship c
    LEFT JOIN alias a ON a.variant = lower(replace(c.class_name, ' ', ''))
    WHERE c.class_name IS NOT NULL
),
entry_class AS (
    SELECT DISTINCT ev.season_id,
           lower(replace(e.class_name, ' ', '')) AS norm,
           e.class_name                          AS spelling
    FROM entry e
    JOIN event ev ON ev.id = e.event_id
    WHERE e.class_name IS NOT NULL
)
UPDATE championship c
SET class_name = ec.spelling
FROM champ_target ct
JOIN entry_class ec ON ec.season_id = ct.season_id AND ec.norm = ct.target_norm
WHERE c.id = ct.id
  AND c.class_name <> ec.spelling;
