-- Existing bytes remain readable. New direct uploads store object keys only.
ALTER TABLE car_image ADD COLUMN original_object_key TEXT;
ALTER TABLE car_image ADD COLUMN sheet_object_key TEXT;
ALTER TABLE car_image ALTER COLUMN data DROP NOT NULL;
ALTER TABLE car_image ADD CONSTRAINT car_image_has_source
    CHECK (data IS NOT NULL OR (original_object_key IS NOT NULL AND sheet_object_key IS NOT NULL));

-- Durable upload tickets: no per-upload heap state and retries survive restarts.
CREATE TABLE car_image_upload (
    id UUID PRIMARY KEY,
    season_id BIGINT NOT NULL REFERENCES season(id) ON DELETE CASCADE,
    car_number TEXT NOT NULL,
    filename TEXT NOT NULL,
    content_type TEXT NOT NULL,
    original_size BIGINT NOT NULL,
    sheet_size BIGINT NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    image_id BIGINT REFERENCES car_image(id) ON DELETE SET NULL,
    completed BOOLEAN NOT NULL DEFAULT false
);
CREATE INDEX car_image_upload_expiry ON car_image_upload(expires_at);
