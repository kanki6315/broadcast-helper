-- An overall (umbrella) championship scores every driver in the field, while
-- additional class championships score only their own subclass (Carrera Cup
-- Asia's Overall vs Am/Masters/Pro-Am; Mustang Challenge's DH vs DHL). Each
-- entry carries only its subclass, so the recap's class_name filter found no
-- entries — and thus no race cells, car number, or team — for any driver whose
-- subclass differs from the championship's class_name. The flag tells the
-- recap to match entries across every class and to print overall (whole-field)
-- start/finish positions instead of in-class ones.
--
-- A NULL/blank class_name already means "no single class" (ImportService
-- derives that for overall titles), so those rows are flagged directly. A
-- championship whose class_name names a real class but scores the whole field
-- (Mustang's "DH") can't be told apart from a class championship here — those
-- need a manual backfill per series.
ALTER TABLE championship ADD COLUMN is_overall BOOLEAN NOT NULL DEFAULT false;

UPDATE championship SET is_overall = true WHERE class_name IS NULL OR class_name = '';
