-- Stable session identity. race_session was keyed (event_id, name) — a free-text
-- string from the source file — so a renamed session ("Race" vs "Race 1") added a
-- duplicate on re-import instead of overwriting, and multi-race weekends leaned
-- entirely on the name to tell Race 1 from Race 2. Replace the string key with the
-- structured (session_type, ordinal) key from the domain model (PLAN §3); `name`
-- stays as a display label only.
ALTER TABLE race_session ADD COLUMN ordinal INT NOT NULL DEFAULT 1;

-- Backfill the ordinal from the trailing number in the existing name
-- ("Race 2" -> 2, "Qualifying" -> 1); no number means the sole session of its
-- type, so 1.
UPDATE race_session
SET ordinal = COALESCE((substring(name from '(\d+)\s*$'))::int, 1);

ALTER TABLE race_session DROP CONSTRAINT race_session_event_id_name_key;
ALTER TABLE race_session ADD CONSTRAINT race_session_event_type_ordinal_key
    UNIQUE (event_id, session_type, ordinal);
