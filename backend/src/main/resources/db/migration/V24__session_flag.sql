-- Flag periods and race-control messages for a session, from the provider's
-- FlagsAnalysisWithRCMessages JSON. One chronological stream per session:
-- flag records (GF / FCY / FF with lap and durations) interleaved with
-- RCMessage rows (penalties, incidents, impound lists). Provider time strings
-- stay TEXT verbatim ("14:05:35.677", "11:07.989", "-") like elapsed_time and
-- gap_first do; seq preserves source order, which is the display order.
CREATE TABLE session_flag (
    id         BIGSERIAL PRIMARY KEY,
    session_id BIGINT NOT NULL REFERENCES race_session (id) ON DELETE CASCADE,
    seq        INT    NOT NULL,
    wall_time  TEXT,
    elapsed    TEXT,
    rec_type   TEXT   NOT NULL,
    flag       TEXT,
    message    TEXT,
    flag_time  TEXT,
    accum_time TEXT,
    lap        INT,
    UNIQUE (session_id, seq)
);
