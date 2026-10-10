-- A grid sheet can name a car the event doesn't know yet: Carrera Cup Asia's
-- grids print no class (and some no entrant), so a grid committed before the
-- weekend's qualifying/results has nothing to give a never-seen car. Instead of
-- failing, such a commit parks the batch as status 'WAITING' with the target
-- the reviewer chose; a later results/qualifying/entry-list commit to the same
-- event finishes it. waiting_for names the cars it is waiting on.
ALTER TABLE import_batch
    ADD COLUMN waiting_target JSONB,
    ADD COLUMN waiting_for TEXT;
