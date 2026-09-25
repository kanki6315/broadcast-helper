-- The round number a results source publishes for an event, recorded when a
-- source that knows it (the IMSA Esports correction from artifactracing.com)
-- is applied. Rounds are numbered by date, and the recap matches standings
-- round N to the season's Nth event — so two rounds held the same day (GTP at
-- Long Beach while GTD raced VIR, both started 2025-11-23 17:00Z) fell back to
-- creation order and could swap. This is only the tiebreak after event_date;
-- null for every event no such source has touched.
ALTER TABLE event ADD COLUMN source_round INT;
