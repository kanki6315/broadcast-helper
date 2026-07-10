-- Starting grid per race session. Published as its own file (the grid reflects
-- fastest-two-laps carry-over + penalties, so it is imported, never computed),
-- and it is what lets a sheet show start -> finish — the true race story, where
-- qualifying alone wouldn't. One row per (session, entry); position_in_class is
-- derived from the overall grid order at import (gaps in the grid are skipped).
CREATE TABLE grid_position (
    id                BIGSERIAL PRIMARY KEY,
    session_id        BIGINT NOT NULL REFERENCES race_session (id) ON DELETE CASCADE,
    entry_id          BIGINT NOT NULL REFERENCES entry (id) ON DELETE CASCADE,
    position_overall  INT,
    position_in_class INT,
    UNIQUE (session_id, entry_id)
);
