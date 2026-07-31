-- Freehand drawing pad, one per (event, signed-in user): the broadcaster's
-- private scribble surface next to the sheet, drawn with an Apple Pencil (or
-- mouse) in the SPA. The whole pad is a single JSONB document — an array of
-- strokes — because the client always loads and saves it atomically and the
-- server never queries into individual strokes; a document swap keeps the API
-- one GET + one PUT. Ownership is keyed by email, not app_user(id): app_user
-- rows are a roster whose ids do not survive remove/re-add, while
-- lower(email) is the stable identity the whole auth layer already keys on
-- (spring_session.principal_name, LiveAuthorization). revision is an
-- optimistic lock: an iPad left open overnight must not clobber strokes drawn
-- elsewhere since — the PUT carries the revision it loaded and loses with 409.
CREATE TABLE event_scratchpad (
    id          BIGSERIAL PRIMARY KEY,
    event_id    BIGINT NOT NULL REFERENCES event (id) ON DELETE CASCADE,
    owner_email TEXT   NOT NULL,
    strokes     JSONB  NOT NULL DEFAULT '[]'::jsonb,
    -- Drawable height in logical px (the pad's coordinate space is a fixed
    -- 800-wide column); the user extends the page as it fills.
    page_height INT    NOT NULL DEFAULT 2000,
    revision    BIGINT NOT NULL DEFAULT 1,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (length(trim(owner_email)) > 0),
    CHECK (page_height BETWEEN 500 AND 50000),
    CHECK (jsonb_typeof(strokes) = 'array')
);

-- One pad per event per person, case-insensitive like every other email key.
CREATE UNIQUE INDEX event_scratchpad_key
    ON event_scratchpad (event_id, lower(owner_email));
