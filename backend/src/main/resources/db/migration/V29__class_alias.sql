-- Per-series class-name aliases: map a source file's class spelling to the
-- series' canonical class, the same way series_alias maps title drift to a
-- series. One series imported from different providers can spell its class
-- differently per source (iRacing's official-series payloads say
-- "[L] Porsche 911" where hosted-session payloads say "Hosted All Cars"),
-- which splits a driver's all-time stats across two class rows. An alias
-- resolves the spelling at import time (ImportService.canonicalizeClass and
-- the class review), so re-imports stay canonical without a review mapping
-- every time. Rows come from the Series page's class-name editor; its rename
-- operation records the old spelling here after fixing existing rows.
CREATE TABLE class_alias (
    id         BIGSERIAL PRIMARY KEY,
    series_id  BIGINT NOT NULL REFERENCES series (id) ON DELETE CASCADE,
    alias      TEXT   NOT NULL,
    class_name TEXT   NOT NULL
);

CREATE UNIQUE INDEX class_alias_series_alias_key ON class_alias (series_id, lower(alias));
