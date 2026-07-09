-- Baseline schema: just enough for the Phase 0 vertical slice.
-- The full domain model (seasons, championships, events, sessions, entries,
-- results, standings) arrives as Phase 1 migrations.

CREATE TABLE series (
    id           BIGSERIAL PRIMARY KEY,
    name         TEXT        NOT NULL UNIQUE,
    abbreviation TEXT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
