-- Rejected sign-in attempts, so "it won't let me in" is diagnosable from the
-- Manage → Users page (usually the teammate used a different Google account
-- than the one added) and the admin can add the attempted email in one click.
-- This is the app's only write reachable before any authorization — any real
-- Google account can trigger it — so rows are deduped per email (the unique
-- index below; repeat attempts bump attempt_count) and DeniedLogins caps the
-- table at 200 distinct emails in code. Uniqueness is on lower(email) because
-- Google emails are case-insensitive.
CREATE TABLE denied_login (
    id               BIGSERIAL PRIMARY KEY,
    email            TEXT NOT NULL,
    attempt_count    INT NOT NULL DEFAULT 1,
    first_attempt_at timestamptz NOT NULL DEFAULT now(),
    last_attempt_at  timestamptz NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX denied_login_email_key ON denied_login (lower(email));
