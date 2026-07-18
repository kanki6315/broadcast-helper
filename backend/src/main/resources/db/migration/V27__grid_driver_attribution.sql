-- Who qualified the car and who takes the start, as the grid publishes it
-- (IMSA/Mustang JSON grids: 1-based seat indexes + a per-car driver roster;
-- IMSA grid CSVs: full names resolved against the DRIVER_1..6 columns).
-- Seats/names resolve to driver FKs at commit time — driver.id is stable
-- across re-imports (find-or-create on UNIQUE (first_name, surname)), where
-- driver_assignment rows are not (delete-and-reinsert). NULL means the source
-- named no one or the name could not be resolved; readers fall back to the
-- entry's sole crew member where one exists, else attribute no one.
ALTER TABLE grid_position
    ADD COLUMN qualifying_driver_id BIGINT REFERENCES driver (id) ON DELETE SET NULL,
    ADD COLUMN starting_driver_id   BIGINT REFERENCES driver (id) ON DELETE SET NULL;

-- Postgres does not index FK columns automatically; these keep driver deletes
-- and the stats attribution lookups from scanning grid_position.
CREATE INDEX grid_position_qualifying_driver_idx ON grid_position (qualifying_driver_id);
CREATE INDEX grid_position_starting_driver_idx ON grid_position (starting_driver_id);
