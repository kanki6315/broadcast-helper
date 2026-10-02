-- A pit box can be shared by two cars of the same series (Petit Le Mans 2026:
-- IMPC #6 and #99 in box 1), so a box no longer identifies one assignment.
-- slot is the car's top-to-bottom position within its box on the PDF; the
-- existing single-car rows are all slot 0.
ALTER TABLE pit_box_assignment ADD COLUMN slot INT NOT NULL DEFAULT 0;
ALTER TABLE pit_box_assignment DROP CONSTRAINT pit_box_assignment_pkey;
ALTER TABLE pit_box_assignment ADD PRIMARY KEY (event_id, box_number, slot);
ALTER TABLE pit_box_assignment ADD CHECK (slot >= 0);
