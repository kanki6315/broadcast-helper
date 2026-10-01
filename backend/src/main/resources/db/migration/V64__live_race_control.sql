-- Race control's messages from the Al Kamel feed (raceControl.messages, spec
-- 1.0.36 §4.2), kept per session for the timing page's log. Written by the
-- analysis writer, so only while ALKAMELV2_ANALYSIS_ENABLED is on; what race
-- control's screen shows right now (raceControl.currentMessages) is read live
-- from the feed and never stored.
--
-- message_key is the feed's own key (its "showTime"), exactly as sent. A null
-- message in a diff deletes its row, as a null lap does; a null channel
-- deletes nothing. The spec says the channel holds "messages sent by race
-- control during current session" — unverified against a recording.
CREATE TABLE live_race_control (
    session_db_id    BIGINT NOT NULL REFERENCES live_session (session_db_id) ON DELETE CASCADE,
    message_key      TEXT   NOT NULL,
    -- Al Kamel's database id for the message ("internal")
    feed_id          BIGINT,
    -- when it was shown, epoch ms UTC
    day_time_ms      BIGINT,
    text             TEXT,
    -- usually a class name, when race control addresses one
    group_text       TEXT,
    line             INT,
    -- #rrggbb as race control set them
    foreground_color TEXT,
    background_color TEXT,
    blink            BOOLEAN,
    is_null          BOOLEAN,
    recorded_at      timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (session_db_id, message_key)
);
