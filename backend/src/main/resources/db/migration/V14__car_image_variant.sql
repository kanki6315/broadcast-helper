-- Downscaled variants of a car image. The full-resolution upload stays the
-- source of truth in car_image.data; the sheet and hosted thumbnails point at a
-- small variant so the exported PDF drops from ~24MB to ~1-3MB. Composes with
-- the Phase 4 S3 move: each variant becomes its own object key.
CREATE TABLE car_image_variant (
    id           BIGSERIAL PRIMARY KEY,
    image_id     BIGINT NOT NULL REFERENCES car_image (id) ON DELETE CASCADE,
    variant      TEXT   NOT NULL,   -- e.g. 'sheet' (~400px longest side, WebP)
    content_type TEXT   NOT NULL,
    width        INT,
    data         BYTEA  NOT NULL,
    UNIQUE (image_id, variant)
);
