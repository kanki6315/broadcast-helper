-- The shareable timing link (docs/LIVE_TIMING_ALL_SERIES_PLAN.md, slice 5):
-- one secret that opens the timing pages, and nothing else of Pit Pass, to
-- whoever holds it. Arjuna's decisions: one link at a time, no expiry; an
-- admin revokes it or replaces it with a new one, and the old one stops
-- working at once. Only the SHA-256 of the secret is stored (as with
-- device_token), so a database read cannot open the link. Revoked rows stay
-- as history.
CREATE TABLE live_share_token (
    id           BIGSERIAL PRIMARY KEY,
    token_hash   TEXT        NOT NULL UNIQUE,
    created_by   TEXT,
    created_at   timestamptz NOT NULL DEFAULT clock_timestamp(),
    revoked_at   timestamptz,
    last_used_at timestamptz
);

-- At most one link works at a time.
CREATE UNIQUE INDEX live_share_token_one_active ON live_share_token ((true)) WHERE revoked_at IS NULL;
