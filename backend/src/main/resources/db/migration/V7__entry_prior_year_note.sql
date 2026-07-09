-- The "last year at this track" column on the pit-lane sheet is a manual
-- field for now (automated prior-year lookup with change context is a
-- Phase 3 design item — see PLAN.md).

ALTER TABLE entry
    ADD COLUMN prior_year_note TEXT;
