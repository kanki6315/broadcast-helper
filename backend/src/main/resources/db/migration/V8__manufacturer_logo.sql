-- Manufacturer logos for entry-list sheets. Keyed by the normalized
-- manufacturer name (lowercased), which is how entries store it. Global:
-- a marque's logo is the same across seasons and series. User-uploaded,
-- since the logos themselves are trademarked and not shipped with the app.

CREATE TABLE manufacturer_logo (
    id           BIGSERIAL PRIMARY KEY,
    name         TEXT        NOT NULL UNIQUE, -- lower(trim(manufacturer))
    display_name TEXT        NOT NULL,
    content_type TEXT        NOT NULL,
    data         BYTEA       NOT NULL,
    uploaded_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
