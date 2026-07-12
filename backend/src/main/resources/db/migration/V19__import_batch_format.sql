-- Which parser family produced this batch (source family x medium).
-- Existing batches were all timing-provider JSON. Values: IMSA_JSON |
-- IMSA_PDF | IMSA_CSV; future: MANUAL, per-provider PDFs (e.g. CCA_PDF).
ALTER TABLE import_batch ADD COLUMN format TEXT NOT NULL DEFAULT 'IMSA_JSON';
