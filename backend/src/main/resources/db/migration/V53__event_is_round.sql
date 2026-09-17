-- Whether an event counts as a championship round. Round numbers are handed
-- out by date to the events with is_round set; the others (the Roar Before
-- the 24, a test day, a prologue) sit on the calendar without a number. The
-- flag replaces two inferences the renumbering used to make — the event name
-- containing "roar", and "has sessions but none of them a race" — the second
-- of which un-numbered every real round between its qualifying import and its
-- race import. Seeded from the Al Kamel planner's pre-season verdict at
-- commit; existing Roar events keep the exclusion the name rule gave them.
ALTER TABLE event ADD COLUMN is_round BOOLEAN NOT NULL DEFAULT TRUE;
UPDATE event SET is_round = FALSE WHERE name ~* '\yroar\y';

-- The planner's pre-season verdict for a fetched batch's weekend, carried to
-- the commit so the event it creates can be marked as not a round.
ALTER TABLE import_batch ADD COLUMN source_preseason BOOLEAN NOT NULL DEFAULT FALSE;
