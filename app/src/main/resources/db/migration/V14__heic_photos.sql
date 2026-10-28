-- Butterfly Atlas Phase 7b: HEIC photos through the computer's own decoder. See
-- docs/superpowers/specs/2026-10-27-phase-7b-contract.md §7.
-- Columns are only added (no table rebuild), so no row of a library can be lost here.

-- 1. image.file_type gains 'heic' (it has no CHECK, so nothing changes here). The details step (ImageMeta.DETAILS)
-- rises to version 3, which reads HEIC photos from their boxes; its catch-up only touches photos whose file type is null
-- or heic: every other photo whose details failed keeps its failure, at the new version, so it is neither read again
-- nor shown as not checked yet.
UPDATE image_processing SET version = 3 WHERE consumer = 'details' AND version = 2
  AND image_id IN (SELECT id FROM image WHERE file_type IN ('jpeg', 'png', 'tiff', 'webp', 'raw'));

-- 2. Exports: HEIC photos are copied exactly, with any location they hold, after the same acceptance as RAW photos
-- (export_run.raw_with_location now records it for both); each item says whether it is HEIC, fixed at the start, and
-- the run counts the HEIC photos it copied.
ALTER TABLE export_run ADD COLUMN heic_count INTEGER NOT NULL DEFAULT 0;
-- The kinds the tick accepted ('raw', 'heic', comma-separated; '' for none): at copy time a photo is copied exactly only
-- when the kind its file is now is one of them (review fix J5).
ALTER TABLE export_run ADD COLUMN accepted_kinds TEXT NOT NULL DEFAULT '';
ALTER TABLE export_item ADD COLUMN heic INTEGER NOT NULL DEFAULT 0 CHECK (heic IN (0, 1));

-- 3. Imports: an item whose preview could not be made says so (audit 6, any format): 1 when the picture could not be
-- read, 2 when making it took too long or found no working copy (tried again once when the preview is asked for).
-- A HEIC item without a preview because no decoder is available now is 0: it is not a failure.
ALTER TABLE import_item ADD COLUMN preview_failed INTEGER NOT NULL DEFAULT 0 CHECK (preview_failed IN (0, 1, 2));

-- 4. Items of an open import refused under 7a as HEIC are read again by the next walk under 7b's rules: back to pending,
-- with no problem and no preview key, under a new change stamp so the page sees them.
UPDATE import_run SET next_seq = next_seq + 1 WHERE state = 'open' AND EXISTS (
    SELECT 1 FROM import_item i WHERE i.run_id = import_run.id AND i.read_state = 'unsupported'
    AND i.problem LIKE 'HEIC photos aren''t supported yet.%');
UPDATE import_item SET read_state = 'pending', problem = NULL, v = NULL,
    seq = (SELECT r.next_seq - 1 FROM import_run r WHERE r.id = import_item.run_id)
WHERE read_state = 'unsupported'
  AND problem LIKE 'HEIC photos aren''t supported yet.%'
  AND run_id IN (SELECT id FROM import_run WHERE state = 'open');
