-- Pit-lane box assignments for an event, from IMSA's pit-lane-assignments PDF
-- (the bytes live in event_document under kind PIT_ASSIGNMENTS; the sidecar
-- parser/parse_pit_assignments.py proposes the mapping and an admin confirms
-- it, so rows here are always reviewed). Only the event's own series' column
-- of the shared lane is stored — neighbours from other series are display
-- noise the broadcaster chose not to keep.

-- The PDF's date + VERSION stamp ("7/28/26 · VERSION 3"): revised sheets drop
-- mid-weekend, so the UI must always show which revision it is displaying.
-- Generic to event_document because it is a fact about the uploaded file.
ALTER TABLE event_document ADD COLUMN note TEXT;

CREATE TABLE pit_box_assignment (
    event_id   BIGINT NOT NULL REFERENCES event (id) ON DELETE CASCADE,
    box_number INT    NOT NULL,
    -- As printed in the PDF (leading zeros preserved); kept even when matched
    -- so an entry-list re-import that renumbers entry ids stays diagnosable.
    car_number TEXT   NOT NULL,
    -- The PDF's team-name text, display context for unmatched rows.
    team_name  TEXT,
    entry_id   BIGINT REFERENCES entry (id) ON DELETE SET NULL,
    PRIMARY KEY (event_id, box_number),
    CHECK (box_number > 0),
    CHECK (length(trim(car_number)) > 0)
);

-- Physical-orientation rows between boxes (penalty box, breaks, the S/F line,
-- pit in/out): after_box 0 sits before box 1. ordinal preserves the PDF's
-- top-to-bottom order for landmarks sharing an after_box.
CREATE TABLE pit_lane_landmark (
    event_id  BIGINT NOT NULL REFERENCES event (id) ON DELETE CASCADE,
    ordinal   INT    NOT NULL,
    after_box INT    NOT NULL,
    label     TEXT   NOT NULL,
    PRIMARY KEY (event_id, ordinal),
    CHECK (after_box >= 0),
    CHECK (length(trim(label)) > 0)
);
