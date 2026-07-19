-- The sign-in allowlist and admin roster, moved from env vars
-- (AUTH_ALLOWED_EMAILS / ADMIN_ALLOWED_EMAILS) into the database so admins
-- manage access from the Manage → Users page without a redeploy. Every row may
-- sign in; role ADMIN additionally allows writes (LiveAuthorization checks per
-- request against UserDirectory's cached snapshot, which reloads on every
-- Users-page mutation — a direct SQL edit here needs an app restart to be
-- seen). No seed rows: an empty table admits nobody, the safe default — the
-- first admin is bootstrapped manually via psql (see docs/DEPLOY.md).
-- Uniqueness is on lower(email) because Google emails are case-insensitive.
CREATE TABLE app_user (
    id         BIGSERIAL PRIMARY KEY,
    email      TEXT NOT NULL,
    role       TEXT NOT NULL CHECK (role IN ('ADMIN', 'VIEWER')),
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX app_user_email_key ON app_user (lower(email));
