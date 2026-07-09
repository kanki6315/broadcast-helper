-- Car livery images for entry-list sheets. Keyed by (season, car_number):
-- car numbers repeat across series, so the season scope (season -> series)
-- disambiguates them, while one image still carries over across all events
-- of a season until replaced.

CREATE TABLE car_image (
    id              BIGSERIAL PRIMARY KEY,
    season_id       BIGINT      NOT NULL REFERENCES season (id) ON DELETE CASCADE,
    car_number      TEXT        NOT NULL,
    content_type    TEXT        NOT NULL,
    source_filename TEXT,
    data            BYTEA       NOT NULL,
    uploaded_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (season_id, car_number)
);
