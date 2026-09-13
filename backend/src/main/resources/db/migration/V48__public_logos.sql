ALTER TABLE manufacturer_logo ADD COLUMN object_key TEXT;
ALTER TABLE manufacturer_logo ALTER COLUMN data DROP NOT NULL;
ALTER TABLE manufacturer_logo ADD CONSTRAINT manufacturer_logo_has_source CHECK (data IS NOT NULL OR object_key IS NOT NULL);
ALTER TABLE series_logo ADD COLUMN object_key TEXT;
ALTER TABLE series_logo ALTER COLUMN data DROP NOT NULL;
ALTER TABLE series_logo ADD CONSTRAINT series_logo_has_source CHECK (data IS NOT NULL OR object_key IS NOT NULL);

CREATE TABLE logo_upload (
    id UUID PRIMARY KEY,
    kind TEXT NOT NULL CHECK (kind IN ('MANUFACTURER', 'SERIES')),
    target TEXT NOT NULL,
    content_type TEXT NOT NULL,
    size BIGINT NOT NULL CHECK (size BETWEEN 1 AND 26214400),
    expires_at TIMESTAMPTZ NOT NULL,
    source_uploaded_at TIMESTAMPTZ,
    completed BOOLEAN NOT NULL DEFAULT false
);
CREATE INDEX logo_upload_expiry ON logo_upload(expires_at);
