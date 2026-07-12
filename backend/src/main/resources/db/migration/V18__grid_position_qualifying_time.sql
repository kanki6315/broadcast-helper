-- Qualifying time behind each grid slot (from grid CSVs; timing-provider JSON
-- grids don't carry it). Display-formatted text like elapsed_time.
ALTER TABLE grid_position ADD COLUMN qualifying_time TEXT;
