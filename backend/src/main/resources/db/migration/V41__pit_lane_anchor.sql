-- GPS anchor points along an event's pit lane, captured by an admin standing
-- at the CENTER of the named box ("mark box N here"). Boxes between two
-- anchors are estimated by linear interpolation on box number, so a handful
-- of anchors (minimum two) describes the whole lane; extra anchors around the
-- physical breaks (S/F stand, tunnel gap) absorb the non-uniform spacing
-- without modeling it. Per-event, not per-circuit, by user decision — track
-- configurations change. Phone GPS is ±3-10 m against a 25-foot box, so the
-- product promise is "within a box or two", never "this exact spot".
CREATE TABLE pit_lane_anchor (
    event_id    BIGINT NOT NULL REFERENCES event (id) ON DELETE CASCADE,
    box_number  INT    NOT NULL,
    lat         DOUBLE PRECISION NOT NULL,
    lng         DOUBLE PRECISION NOT NULL,
    -- Reported GPS accuracy at capture, display-only ("±5 m").
    accuracy_m  REAL,
    captured_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (event_id, box_number),
    CHECK (box_number >= 1),
    CHECK (lat BETWEEN -90 AND 90),
    CHECK (lng BETWEEN -180 AND 180)
);
