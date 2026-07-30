-- How this race's grid was set, when not by the car's own qualifying effort
-- ("2nd fastest qualifying lap", "Fastest lap from Race 1", "Championship
-- points — qualifying cancelled"). Free text from the reviewer at grid commit;
-- null means normal qualifying order. Editorial context for broadcast prep —
-- without it a sheet's Q column reads like a qualifying result that may never
-- have happened.
ALTER TABLE race_session ADD COLUMN grid_basis TEXT;
