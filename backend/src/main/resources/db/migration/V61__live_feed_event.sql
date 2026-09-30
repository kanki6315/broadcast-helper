-- Filing by series weekend, not by session (docs/LIVE_TIMING_ALL_SERIES_PLAN.md,
-- slice 3). Al Kamel's feed event (timing.session.info.eventDbId) is one
-- championship at one weekend and has carried over between that series'
-- sessions, so it is what gets bound to a Pit Pass event: bind it once and
-- every session of the weekend follows, including ones not yet run.
--
-- bound_by: NULL = not bound yet (filing keeps trying); AUTO = filed by
-- championship name, an inherited binding or the connection's event, each
-- confirmed where it can be; ADMIN = an admin chose event_id; ADMIN_NONE = an
-- admin said "not in Pit Pass". Nothing automatic overrides an ADMIN value.
-- AUTO with event_id NULL (the event was deleted) counts as not bound.
CREATE TABLE live_feed_event (
    feed_event_db_id BIGINT PRIMARY KEY,
    event_id         BIGINT REFERENCES event (id) ON DELETE SET NULL,
    bound_by         TEXT CHECK (bound_by IN ('AUTO', 'ADMIN', 'ADMIN_NONE')),
    bound_by_email   TEXT,
    bound_at         timestamptz,
    first_seen_at    timestamptz NOT NULL DEFAULT clock_timestamp()
);

-- What is already filed carries over as AUTO.
INSERT INTO live_feed_event (feed_event_db_id, event_id, bound_by, bound_at)
SELECT feed_event_db_id,
       (array_agg(event_id ORDER BY first_seen_at) FILTER (WHERE event_id IS NOT NULL))[1],
       CASE WHEN bool_or(event_id IS NOT NULL) THEN 'AUTO' END,
       CASE WHEN bool_or(event_id IS NOT NULL) THEN clock_timestamp() END
FROM live_session
WHERE feed_event_db_id IS NOT NULL
GROUP BY feed_event_db_id;

-- A single session filed somewhere other than its weekend (an admin's
-- exception). live_session.event_id stays the resolved value every reader
-- uses: COALESCE(event_override, the feed event's event_id).
ALTER TABLE live_session
    ADD COLUMN event_override BIGINT REFERENCES event (id) ON DELETE SET NULL;

-- Each car as the feed describes it (timing.session.entry), kept so filing
-- can be checked again after the session is over — by number and class, as
-- LiveEventMatch does live — and so a session filed nowhere can still be
-- grouped by class.
CREATE TABLE live_car (
    session_db_id BIGINT NOT NULL REFERENCES live_session (session_db_id) ON DELETE CASCADE,
    car_number    TEXT   NOT NULL,
    feed_class    TEXT,
    team          TEXT,
    vehicle       TEXT,
    manufacturer  TEXT,
    PRIMARY KEY (session_db_id, car_number)
);
