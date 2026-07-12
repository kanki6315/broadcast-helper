-- Reference documents attached to a single event — currently the series'
-- team-sheets PDF (team/driver bio pages, one section per car). One document
-- per (event, kind); re-uploading an updated PDF replaces it in place.

CREATE TABLE event_document (
    id              BIGSERIAL PRIMARY KEY,
    event_id        BIGINT      NOT NULL REFERENCES event (id) ON DELETE CASCADE,
    kind            TEXT        NOT NULL DEFAULT 'TEAM_SHEETS',
    source_filename TEXT,
    content_type    TEXT        NOT NULL,
    data            BYTEA       NOT NULL,
    page_count      INT,
    uploaded_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (event_id, kind)
);

-- Car-number -> first page of that team's section, extracted by the Python
-- sidecar (parser/extract_team_sheet_pages.py) at upload and manually
-- correctable afterwards. car_number is the string as printed in the PDF
-- (leading zeros preserved); consumers match it against entry.car_number
-- with zeros normalised. team_name is display-only context for review.
CREATE TABLE event_document_page (
    document_id BIGINT NOT NULL REFERENCES event_document (id) ON DELETE CASCADE,
    car_number  TEXT   NOT NULL,
    page        INT    NOT NULL,
    team_name   TEXT,
    PRIMARY KEY (document_id, car_number)
);
