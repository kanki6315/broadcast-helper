-- Drive-time limits per event and class, entered by an admin. Endurance
-- series cap and floor how long each driver may be in the car, by rating:
-- IMSA sets a minimum for Bronze drivers in some classes and a maximum for
-- everyone, and counts only track time (pit lane excluded). The regulations
-- change per event, so the numbers are typed in per event rather than
-- modelled.
--
-- rating NULL is the class's rule for every rating; a rule for a specific
-- rating (B/S/G/P, as driver_assignment.rating) takes precedence over it for
-- that rating. At least one of min_ms / max_ms is set.
CREATE TABLE drive_time_rule (
    id         BIGSERIAL PRIMARY KEY,
    event_id   BIGINT NOT NULL REFERENCES event (id) ON DELETE CASCADE,
    class_name TEXT   NOT NULL,
    rating     TEXT CHECK (rating IN ('B', 'S', 'G', 'P')),
    min_ms     BIGINT CHECK (min_ms >= 0),
    max_ms     BIGINT CHECK (max_ms >= 0),
    note       TEXT,
    CHECK (min_ms IS NOT NULL OR max_ms IS NOT NULL),
    CHECK (min_ms IS NULL OR max_ms IS NULL OR min_ms <= max_ms)
);

CREATE UNIQUE INDEX drive_time_rule_key ON drive_time_rule (event_id, lower(class_name), COALESCE(rating, ''));
