-- Per-series class display config for the sheet: the order classes appear in and
-- the header colour, previously hardcoded to IMSA's classes in SheetController /
-- sheet.css. Now data-driven so a new series (Mustang Challenge's DH/DHL, ...)
-- renders correctly without code changes. Seeded by series abbreviation; a class
-- with no row falls back to a neutral colour sorted last.
CREATE TABLE class_style (
    id         BIGSERIAL PRIMARY KEY,
    series_id  BIGINT NOT NULL REFERENCES series (id) ON DELETE CASCADE,
    class_code TEXT   NOT NULL,
    ordinal    INT    NOT NULL,   -- display order within the sheet
    color      TEXT   NOT NULL,   -- header background (hex); zebra tint derived
    UNIQUE (series_id, class_code)
);

INSERT INTO class_style (series_id, class_code, ordinal, color)
SELECT s.id, v.class_code, v.ordinal, v.color
FROM series s,
     (VALUES ('GTP', 0, '#000000'),
             ('LMP2', 1, '#17468f'),
             ('LMP3', 2, '#4a4a4a'),
             ('GTDPRO', 3, '#b3261e'),
             ('GTD', 4, '#1f7a34')) AS v (class_code, ordinal, color)
WHERE s.abbreviation = 'IMSA';

INSERT INTO class_style (series_id, class_code, ordinal, color)
SELECT s.id, v.class_code, v.ordinal, v.color
FROM series s,
     (VALUES ('DH', 0, '#14213d'),
             ('DHL', 1, '#7a5230')) AS v (class_code, ordinal, color)
WHERE s.abbreviation = 'MC';
