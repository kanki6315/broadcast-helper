-- The Al Kamel live timing feed (AKS V2) allows ONE concurrent login for our
-- account, and the connection lives in a server process that Railway replaces
-- on every deploy — briefly running the old and the new side by side. So what
-- an admin asked for ("be connected, for this event") cannot live in memory:
-- the new process must pick it up, and the two must never both log in.
--
-- One row, by construction. desired_* is the admin's request, set from the
-- app; holder/lease_expires_at is a lease the connecting process renews every
-- few seconds. A lease rather than pg_advisory_lock because a session-level
-- lock pins a pooled connection for the length of a race, and the pool is 5.
-- A process that dies without releasing simply stops renewing, and the next
-- one takes over when the lease lapses.
CREATE TABLE live_timing (
    id                SMALLINT PRIMARY KEY CHECK (id = 1),
    desired_connected BOOLEAN NOT NULL DEFAULT FALSE,
    -- The Pit Pass event the live session is scored against. Chosen by the
    -- admin at connect time; the feed's own event name is never trusted to
    -- find it.
    event_id          BIGINT REFERENCES event (id) ON DELETE SET NULL,
    requested_by      TEXT,
    requested_at      timestamptz,
    holder            TEXT,
    lease_expires_at  timestamptz
);

INSERT INTO live_timing (id) VALUES (1);
