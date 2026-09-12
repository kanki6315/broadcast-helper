-- Long-lived bearer tokens for the native iPad app (see docs/IOS.md). The web
-- app signs in with a Google session cookie, but Google refuses OAuth inside an
-- embedded web view and the system browser's cookies never reach a native app,
-- so the app completes the same Google login in the system browser and is then
-- handed a one-time code, which it exchanges for one of these tokens. Only the
-- SHA-256 of the secret is stored; the secret itself lives in the iPad's
-- Keychain and is shown to nobody. Authorization stays request-time and
-- roster-driven (LiveAuthorization): the token only says WHO is calling,
-- app_user still says what they may do, and removing someone from the roster
-- cuts their devices off on the next request exactly like their sessions.
-- Revocation keeps the row (revoked_at) so Manage → Sessions can show what a
-- device was; only active rows are listed.
CREATE TABLE device_token (
    id            BIGSERIAL PRIMARY KEY,
    token_hash    TEXT        NOT NULL,
    owner_email   TEXT        NOT NULL,
    device_name   TEXT        NOT NULL,
    created_at    timestamptz NOT NULL DEFAULT now(),
    last_used_at  timestamptz,
    revoked_at    timestamptz
);

CREATE UNIQUE INDEX device_token_hash_idx ON device_token (token_hash);
CREATE INDEX device_token_owner_idx ON device_token (lower(owner_email));
