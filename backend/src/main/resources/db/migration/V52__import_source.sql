-- Where a staged batch came from when it was fetched from the Al Kamel results
-- site rather than uploaded (docs/ALKAMEL_IMPORT.md). source_url is the file;
-- source_modified is the site's own last-modified cell for it, compared only to
-- itself when an event refresh asks what changed; source_event is the
-- "YY_YYYY/NN_Event" folder key that groups one weekend's batches. All null for
-- uploads and iRacing fetches.
ALTER TABLE import_batch ADD COLUMN source_url TEXT;
ALTER TABLE import_batch ADD COLUMN source_modified TEXT;
ALTER TABLE import_batch ADD COLUMN source_event TEXT;

-- The same folder key on the event a fetched batch created or was attached to.
-- CSV-era files name no circuit, so this — not the circuit-and-date match — is
-- how a later fetch of the same weekend finds its event. Stamped on commit,
-- never overwritten once set.
ALTER TABLE event ADD COLUMN source_ref TEXT;
CREATE INDEX event_source_ref_idx ON event (season_id, source_ref);
