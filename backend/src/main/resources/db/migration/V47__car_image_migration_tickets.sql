-- A browser migration must not replace a photo changed since it downloaded it.
ALTER TABLE car_image_upload ADD COLUMN migration_image_id BIGINT;
ALTER TABLE car_image_upload ADD COLUMN source_uploaded_at TIMESTAMPTZ;
