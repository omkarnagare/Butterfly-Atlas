-- Butterfly Atlas Phase 7a: RAW photos through their embedded previews. See
-- docs/superpowers/specs/2026-10-26-phase-7a-contract.md §6.
-- Columns and an index are only added (no table rebuild), so no row of a library can be lost here.

-- 1. Every photo's file type (jpeg | png | tiff | webp | raw), for the File type filter. Filled from the file name's
-- extension (LIKE ignores ASCII case), else from the stored details' format; anything else stays null and is set from
-- the bytes when the details catch-up reads the photo.
ALTER TABLE image ADD COLUMN file_type TEXT;
UPDATE image SET file_type = CASE
    WHEN rel_path LIKE '%.jpg' OR rel_path LIKE '%.jpeg' THEN 'jpeg'
    WHEN rel_path LIKE '%.png' THEN 'png'
    WHEN rel_path LIKE '%.tif' OR rel_path LIKE '%.tiff' THEN 'tiff'
    WHEN rel_path LIKE '%.webp' THEN 'webp'
END;
UPDATE image SET file_type = (
    SELECT CASE WHEN json_valid(m.json) AND json_extract(m.json, '$.format') IN ('JPEG', 'PNG', 'TIFF', 'WEBP')
                THEN lower(json_extract(m.json, '$.format')) END
    FROM image_meta m WHERE m.image_id = image.id)
WHERE file_type IS NULL;
CREATE INDEX image_live_type ON image (file_type, taxon_id) WHERE deleted_at IS NULL;

-- The details step (ImageMeta.DETAILS) rises to version 2, which reads RAW photos through their preview. Its catch-up
-- only touches photos whose file type is null or raw: every other photo whose details failed keeps its failure, at
-- the new version, so it is neither read again nor shown as not checked yet.
UPDATE image_processing SET version = 2 WHERE consumer = 'details' AND version = 1
  AND image_id IN (SELECT id FROM image WHERE file_type IN ('jpeg', 'png', 'tiff', 'webp'));

-- 2. Imports: how RAW + JPEG pairs are filed (null until chosen), each item's file type and its pair's other item.
-- import_item.outcome cannot gain 'pair_left' (SQLite cannot alter a CHECK, and rebuilding import_item would mean
-- rebuilding the tables that refer to it), so the half of a pair the choice leaves out is marked in its own column:
-- pair_left = 1 with outcome null; the API answers it as the outcome 'pair_left'.
ALTER TABLE import_run ADD COLUMN pair_choice TEXT CHECK (pair_choice IN ('both', 'jpeg', 'raw'));
ALTER TABLE import_item ADD COLUMN file_type TEXT;
ALTER TABLE import_item ADD COLUMN pair_item_id INTEGER REFERENCES import_item (id) ON DELETE SET NULL;
ALTER TABLE import_item ADD COLUMN pair_left INTEGER NOT NULL DEFAULT 0 CHECK (pair_left IN (0, 1));
UPDATE import_item SET file_type = lower(format) WHERE format IN ('JPEG', 'PNG', 'TIFF', 'WEBP');
CREATE INDEX import_item_pair ON import_item (pair_item_id);

-- Items of an open import that were refused before 7a as RAW or HEIC are read again by the next walk under 7a's rules
-- (audit 1): back to pending, with no problem and no preview key, under a new change stamp so the page sees them.
UPDATE import_run SET next_seq = next_seq + 1 WHERE state = 'open' AND EXISTS (
    SELECT 1 FROM import_item i WHERE i.run_id = import_run.id AND i.read_state = 'unsupported'
    AND i.problem IN ('RAW photos aren''t supported yet.', 'HEIC photos aren''t supported yet.'));
UPDATE import_item SET read_state = 'pending', problem = NULL, v = NULL,
    seq = (SELECT r.next_seq - 1 FROM import_run r WHERE r.id = import_item.run_id)
WHERE read_state = 'unsupported'
  AND problem IN ('RAW photos aren''t supported yet.', 'HEIC photos aren''t supported yet.')
  AND run_id IN (SELECT id FROM import_run WHERE state = 'open');

-- 3. Exports: RAW photos are copied exactly, with any location they hold, only after the user accepts that; the
-- acceptance is kept on the run (resume never asks again) and each item says whether it is RAW, fixed at the start.
ALTER TABLE export_run ADD COLUMN raw_with_location INTEGER NOT NULL DEFAULT 0 CHECK (raw_with_location IN (0, 1));
ALTER TABLE export_run ADD COLUMN raw_count INTEGER NOT NULL DEFAULT 0;
ALTER TABLE export_item ADD COLUMN raw INTEGER NOT NULL DEFAULT 0 CHECK (raw IN (0, 1));
