-- Teams become first-class global entities. entry.team_name stays exactly as
-- each source file spelled it (sponsor variants are real per-race history);
-- team_alias maps those spellings onto one organization, and entry.team_id is
-- the resolved grouping key that stats and the team profile read. Lineage
-- (an entry transferred to a genuinely new organization, e.g. Van der Steur ->
-- YRB) is a display-only predecessor link, never a stats merge.
CREATE TABLE team (
    id             BIGSERIAL PRIMARY KEY,
    name           TEXT NOT NULL,
    predecessor_id BIGINT REFERENCES team (id) ON DELETE SET NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (length(trim(name)) > 0)
);

CREATE TABLE team_alias (
    id      BIGSERIAL PRIMARY KEY,
    team_id BIGINT NOT NULL REFERENCES team (id) ON DELETE CASCADE,
    alias   TEXT NOT NULL,
    CHECK (length(trim(alias)) > 0)
);

-- Resolution is by bare name with no scope, so one spelling can only ever
-- mean one team.
CREATE UNIQUE INDEX team_alias_key ON team_alias (lower(trim(alias)));

-- Default NO ACTION on delete: a merge must repoint entries before the losing
-- team row can go away.
ALTER TABLE entry
    ADD COLUMN team_id BIGINT REFERENCES team (id);

CREATE INDEX entry_team_idx ON entry (team_id);

-- Backfill: one team per distinct normalized name, canonical casing taken from
-- the most recent entry. iRacing privateer placeholders (season_team rows with
-- privateer_driver_id) are excluded — "Privateer" is not one organization, so
-- those entries keep team_id NULL and stay out of team stats.
WITH names AS (
    SELECT DISTINCT ON (lower(trim(en.team_name)))
           trim(en.team_name) AS name
    FROM entry en
             JOIN event ev ON ev.id = en.event_id
             LEFT JOIN season_team st ON st.id = en.season_team_id
    WHERE st.privateer_driver_id IS NULL
      AND length(trim(en.team_name)) > 0
    ORDER BY lower(trim(en.team_name)), ev.event_date DESC NULLS LAST, en.id DESC
), created AS (
    INSERT INTO team (name)
        SELECT name FROM names
        RETURNING id, name
)
INSERT INTO team_alias (team_id, alias)
SELECT id, name FROM created;

UPDATE entry en
SET team_id = ta.team_id
FROM team_alias ta
WHERE lower(trim(en.team_name)) = lower(trim(ta.alias))
  AND NOT EXISTS (SELECT 1
                  FROM season_team st
                  WHERE st.id = en.season_team_id
                    AND st.privateer_driver_id IS NOT NULL);
