-- Carrera Cup NA 2021–2026 Al Kamel import: run once in production BEFORE the import.
BEGIN;

-- The era folder names, so every weekend's series folder resolves (without the
-- first, a 2022 plan shows Sebring only and 2021 standings keep "Presented by
-- The Cayman Islands" in their class names).
INSERT INTO series_alias (series_id, alias)
SELECT s.id, a.alias
FROM series s
CROSS JOIN (VALUES ('Porsche Carrera Cup North America Presented by The Cayman Islands'),
                   ('Porsche Deluxe Carrera Cup North America')) AS a(alias)
WHERE s.name = 'Porsche Carrera Cup North America'
  AND NOT EXISTS (SELECT 1 FROM series_alias x WHERE lower(x.alias) = lower(a.alias));

-- The F1-weekend sheets and upper-case CSVs spell the classes their own way.
INSERT INTO class_alias (series_id, alias, class_name)
SELECT s.id, a.alias, a.class_name
FROM series s
CROSS JOIN (VALUES ('P', 'Pro'), ('PRO', 'Pro'), ('PA', 'Pro-Am'), ('PRO-AM', 'Pro-Am'),
                   ('A', 'Am'), ('M', 'Masters'), ('MAS', 'Masters')) AS a(alias, class_name)
WHERE s.name = 'Porsche Carrera Cup North America'
  AND NOT EXISTS (SELECT 1 FROM class_alias x WHERE x.series_id = s.id AND lower(x.alias) = lower(a.alias));

-- Event names an earlier import suffixed with a (day-early) date because another
-- season had an event of that name. Only renamed where nothing else in the
-- season would then share the name.
UPDATE event e
SET name = regexp_replace(e.name, ' \(\w{3} \d{1,2}\)$', '')
WHERE e.name ~ ' \(\w{3} \d{1,2}\)$'
  AND NOT EXISTS (SELECT 1 FROM event o
                  WHERE o.season_id = e.season_id AND o.id <> e.id
                    AND lower(regexp_replace(o.name, ' \(\w{3} \d{1,2}\)$', ''))
                        = lower(regexp_replace(e.name, ' \(\w{3} \d{1,2}\)$', '')));

COMMIT;
