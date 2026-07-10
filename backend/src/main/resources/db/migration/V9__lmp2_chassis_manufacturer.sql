-- LMP2 manufacturer is the chassis constructor (ORECA), not the shared engine
-- (Gibson). Backfill existing LMP2 entries to the first word of the car type.
UPDATE entry
SET manufacturer = split_part(vehicle, ' ', 1)
WHERE class_name = 'LMP2'
  AND vehicle IS NOT NULL
  AND vehicle <> '';
