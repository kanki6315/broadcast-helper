-- Refuse cleanup unless every stored file has completed its R2 migration.
-- Flyway runs this transactionally: a failed check leaves all bytes untouched.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM car_image WHERE nullif(trim(original_object_key), '') IS NULL OR nullif(trim(sheet_object_key), '') IS NULL)
       OR EXISTS (SELECT 1 FROM manufacturer_logo WHERE nullif(trim(object_key), '') IS NULL)
       OR EXISTS (SELECT 1 FROM series_logo WHERE nullif(trim(object_key), '') IS NULL)
       OR EXISTS (SELECT 1 FROM driver_photo WHERE nullif(trim(object_key), '') IS NULL)
       OR EXISTS (SELECT 1 FROM event_document WHERE nullif(trim(object_key), '') IS NULL) THEN
        RAISE EXCEPTION 'R2 cleanup blocked: some files are not migrated. Run migration using the previous release before deploying this release.';
    END IF;
END $$;

ALTER TABLE car_image ALTER COLUMN original_object_key SET NOT NULL;
ALTER TABLE car_image ALTER COLUMN sheet_object_key SET NOT NULL;
ALTER TABLE manufacturer_logo ALTER COLUMN object_key SET NOT NULL;
ALTER TABLE series_logo ALTER COLUMN object_key SET NOT NULL;
ALTER TABLE driver_photo ALTER COLUMN object_key SET NOT NULL;
ALTER TABLE event_document ALTER COLUMN object_key SET NOT NULL;

-- Clear values first so PostgreSQL can reclaim the old TOAST data through vacuum.
UPDATE car_image SET data = NULL WHERE data IS NOT NULL;
UPDATE manufacturer_logo SET data = NULL WHERE data IS NOT NULL;
UPDATE series_logo SET data = NULL WHERE data IS NOT NULL;
UPDATE driver_photo SET data = NULL WHERE data IS NOT NULL;
UPDATE event_document SET data = NULL WHERE data IS NOT NULL;
ALTER TABLE car_image DROP COLUMN data;
ALTER TABLE manufacturer_logo DROP COLUMN data;
ALTER TABLE series_logo DROP COLUMN data;
ALTER TABLE driver_photo DROP COLUMN data;
ALTER TABLE event_document DROP COLUMN data;
DROP TABLE car_image_variant;
DELETE FROM car_image_upload WHERE migration_image_id IS NOT NULL;
DELETE FROM logo_upload WHERE source_uploaded_at IS NOT NULL;
ALTER TABLE car_image_upload DROP COLUMN migration_image_id;
ALTER TABLE car_image_upload DROP COLUMN source_uploaded_at;
ALTER TABLE logo_upload DROP COLUMN source_uploaded_at;
