-- Per-series named race formats (Sprint/Main, rallycross Heat/Consolation/
-- Feature, plain Race). code is the heuristic's stable identity; name is the
-- broadcaster-facing label and freely renamable in Manage.
CREATE TABLE race_format (
    id        BIGSERIAL PRIMARY KEY,
    series_id BIGINT NOT NULL REFERENCES series (id) ON DELETE CASCADE,
    code      TEXT   NOT NULL,
    name      TEXT   NOT NULL,
    ordinal   INT    NOT NULL DEFAULT 1,
    UNIQUE (series_id, code)
);

-- RACE sessions only; QUALIFYING/PRACTICE stay NULL by design.
-- format_source: AUTO rows are recomputed on every import; MANUAL rows
-- (set in Manage) survive re-imports untouched.
ALTER TABLE race_session ADD COLUMN format_id BIGINT REFERENCES race_format (id) ON DELETE SET NULL;
ALTER TABLE race_session ADD COLUMN format_source TEXT NOT NULL DEFAULT 'AUTO';
CREATE INDEX race_session_format_idx ON race_session (format_id);
