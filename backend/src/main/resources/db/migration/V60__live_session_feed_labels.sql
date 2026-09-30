-- What the feed says a session is, kept with the session. Until now a
-- recorded session knew only its own name ("Practice 2"), so one that was
-- never filed under a Pit Pass event — a series Pit Pass does not follow, run
-- while the connection was up for another — could not be told apart once the
-- connection closed (docs/LIVE_TIMING_ALL_SERIES_PLAN.md).
--
-- All from timing.session.info. champ_db_id and feed_event_db_id are Al
-- Kamel's internal ids: champ_db_id is one per championship (IMPC 612, VP
-- Racing 613 on 2026-09-30), feed_event_db_id one per championship per
-- weekend, seen carrying over between a series' sessions. champ_name has
-- matched our series.name exactly so far. closed is the feed's own "session
-- closed" flag.
ALTER TABLE live_session
    ADD COLUMN champ_db_id           BIGINT,
    ADD COLUMN champ_name            TEXT,
    ADD COLUMN feed_event_name       TEXT,
    ADD COLUMN feed_event_short_name TEXT,
    ADD COLUMN closed                BOOLEAN NOT NULL DEFAULT false;

CREATE INDEX live_session_feed_event_idx ON live_session (feed_event_db_id);
