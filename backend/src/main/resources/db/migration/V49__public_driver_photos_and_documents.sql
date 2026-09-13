ALTER TABLE driver_photo ADD COLUMN object_key TEXT;
ALTER TABLE driver_photo ALTER COLUMN data DROP NOT NULL;
ALTER TABLE driver_photo ADD CONSTRAINT driver_photo_has_source CHECK (data IS NOT NULL OR object_key IS NOT NULL);
ALTER TABLE event_document ADD COLUMN object_key TEXT;
ALTER TABLE event_document ALTER COLUMN data DROP NOT NULL;
ALTER TABLE event_document ADD CONSTRAINT event_document_has_source CHECK (data IS NOT NULL OR object_key IS NOT NULL);
ALTER TABLE logo_upload DROP CONSTRAINT logo_upload_kind_check;
ALTER TABLE logo_upload ADD CONSTRAINT logo_upload_kind_check CHECK (kind IN ('MANUFACTURER', 'SERIES', 'DRIVER'));
