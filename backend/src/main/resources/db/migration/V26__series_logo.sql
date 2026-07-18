-- Series logos for the landing directory and season surfaces. One logo per
-- series (the mark is the same across seasons), keyed by series_id. Stored
-- in-row like manufacturer_logo since the logos are user-uploaded and
-- trademarked — not shipped with the app. Cascades with the series.

CREATE TABLE series_logo (
    series_id    BIGINT      PRIMARY KEY REFERENCES series (id) ON DELETE CASCADE,
    content_type TEXT        NOT NULL,
    data         BYTEA       NOT NULL,
    uploaded_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
