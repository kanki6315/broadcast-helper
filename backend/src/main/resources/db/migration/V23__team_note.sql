-- Broadcaster notes for teams. Teams are not first-class rows — they exist as
-- entry.team_name strings — so notes key on the normalized name, the same
-- convention manufacturer_logo uses. Global on purpose: a team that runs two
-- series (Winward in WTSC and Pilot Challenge) is one organization with one
-- set of booth notes.

CREATE TABLE team_note (
    name       TEXT        PRIMARY KEY, -- lower(trim(team_name))
    notes      TEXT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
