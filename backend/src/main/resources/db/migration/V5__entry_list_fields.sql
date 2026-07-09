-- Fields sourced from the pre-event entry list PDF.

ALTER TABLE entry
    ADD COLUMN sponsor TEXT,
    ADD COLUMN tire    TEXT,
    ADD COLUMN fuel    TEXT;

-- Entry lists can announce a seat before the driver ("(?) TBD"), and ratings
-- carry provenance: per the derogation rule, a rating from the event's entry
-- list is authoritative over the license field in a results file.
ALTER TABLE driver_assignment
    ALTER COLUMN driver_id DROP NOT NULL,
    ADD COLUMN is_tbd        BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN rating_source TEXT;

-- Normalize ratings to single letters (results files spell them out:
-- "Platinum" -> "P") and mark existing rows as results-sourced.
UPDATE driver_assignment
SET rating = upper(left(rating, 1))
WHERE rating IS NOT NULL;

UPDATE driver_assignment
SET rating_source = 'RESULTS'
WHERE rating IS NOT NULL;
