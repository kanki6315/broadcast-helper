-- Driver profile fields for the driver info modal. Imports only ever touch
-- country/hometown (COALESCE-upsert in ImportService), so everything added
-- here is broadcaster-owned and survives re-imports.

ALTER TABLE driver
    ADD COLUMN date_of_birth  DATE,
    ADD COLUMN place_of_birth TEXT,
    ADD COLUMN pronunciation  TEXT,
    ADD COLUMN notes          TEXT;

-- Headshot, one per driver, same BYTEA storage model as car_image /
-- manufacturer_logo (user-uploaded, served from the DB).
CREATE TABLE driver_photo (
    driver_id       BIGINT      PRIMARY KEY REFERENCES driver (id) ON DELETE CASCADE,
    content_type    TEXT        NOT NULL,
    source_filename TEXT,
    data            BYTEA       NOT NULL,
    uploaded_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
