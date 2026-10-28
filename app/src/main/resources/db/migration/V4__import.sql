-- Butterfly Atlas Phase 3b-2: Import folder. See docs/superpowers/specs/2026-10-12-phase-3b-bulk-import-design.md §4, §5.

-- 1. image.source gains 'import'. SQLite cannot alter a CHECK, so image is rebuilt, and libraries hold real
-- photos now: every row keeps its id. This runs inside Flyway's transaction (all or nothing; a failure leaves the
-- library exactly as it was and is tried again at the next start), where foreign keys stay on, so DROP TABLE image
-- empties the tables that cascade from it (image_tag, image_meta, image_processing): they are saved first and put
-- back after. Flyway's SQLite parser cannot run a script outside its transaction with its own BEGIN … COMMIT, and a
-- failed script outside a transaction would leave a failed migration that blocks the library for good.
CREATE TEMP TABLE v4_image AS SELECT * FROM image;
CREATE TEMP TABLE v4_image_tag AS SELECT * FROM image_tag;
CREATE TEMP TABLE v4_image_meta AS SELECT * FROM image_meta;
CREATE TEMP TABLE v4_image_processing AS SELECT * FROM image_processing;
-- AUTOINCREMENT's high-water mark: a purged photo's id must stay used.
CREATE TEMP TABLE v4_sequence AS SELECT seq FROM sqlite_sequence WHERE name = 'image';

DROP TABLE image;

CREATE TABLE image (
    id               INTEGER PRIMARY KEY AUTOINCREMENT,   -- never reused, so a purged id cannot meet an old cache file or link
    taxon_id         TEXT NOT NULL REFERENCES taxon (id),
    rel_path         TEXT NOT NULL,                  -- relative to the data folder, '/' separators
    file_name        TEXT NOT NULL,
    number           INTEGER,                        -- the NN in Danaus_chrysippus_NN.jpg; null for files copied in by hand
    original_name    TEXT,                           -- the name the file had on the user's computer
    file_size        INTEGER NOT NULL,
    modified_at      TEXT NOT NULL,
    sha256           TEXT NOT NULL,
    width            INTEGER,                        -- as displayed, EXIF orientation applied
    height           INTEGER,
    orientation      INTEGER,                        -- EXIF 1..8
    added_at         TEXT NOT NULL,
    source           TEXT NOT NULL CHECK (source IN ('app', 'rescan', 'import')),
    deleted_at       TEXT,                           -- set while the photo is in Recently deleted
    deleted_rel_path TEXT                            -- where it sits meanwhile, under .deleted/
);

INSERT INTO image (id, taxon_id, rel_path, file_name, number, original_name, file_size, modified_at, sha256, width,
                   height, orientation, added_at, source, deleted_at, deleted_rel_path)
SELECT id, taxon_id, rel_path, file_name, number, original_name, file_size, modified_at, sha256, width, height,
       orientation, added_at, source, deleted_at, deleted_rel_path
FROM v4_image ORDER BY id;

-- Put back the high-water mark. In a library that never had a photo there was no 'image' row here, and the INSERT …
-- SELECT above (even of no rows) leaves one at 0: harmless, the next photo still gets id 1.
UPDATE sqlite_sequence SET seq = max(seq, coalesce((SELECT seq FROM v4_sequence), 0)) WHERE name = 'image';
INSERT INTO sqlite_sequence (name, seq)
SELECT 'image', seq FROM v4_sequence WHERE NOT EXISTS (SELECT 1 FROM sqlite_sequence WHERE name = 'image');

CREATE UNIQUE INDEX image_live_path ON image (rel_path) WHERE deleted_at IS NULL;
CREATE INDEX image_live_taxon ON image (taxon_id) WHERE deleted_at IS NULL;
CREATE INDEX image_live_added ON image (added_at DESC, id DESC) WHERE deleted_at IS NULL;
CREATE INDEX image_sha ON image (sha256);

INSERT INTO image_tag (image_id, tag_id) SELECT image_id, tag_id FROM v4_image_tag;
INSERT INTO image_meta (image_id, json, read_at) SELECT image_id, json, read_at FROM v4_image_meta;
INSERT INTO image_processing (image_id, consumer, version, status, error, done_at)
SELECT image_id, consumer, version, status, error, done_at FROM v4_image_processing;

DROP TABLE v4_image;
DROP TABLE v4_image_tag;
DROP TABLE v4_image_meta;
DROP TABLE v4_image_processing;
DROP TABLE v4_sequence;

-- 2. Imports (design §5, contract §4). An import's choices are saved as they are made, so it continues where it
-- stopped; filing a photo and marking its item filed happen in one transaction.
CREATE TABLE import_run (
    id             INTEGER PRIMARY KEY AUTOINCREMENT,  -- never reused: preview folders .thumbs/import/<id> are named by it
    root_path      TEXT NOT NULL,                      -- the chosen folder, as the computer writes paths
    state          TEXT NOT NULL CHECK (state IN ('open', 'finished')),   -- a discarded import is deleted
    created_at     TEXT NOT NULL,
    updated_at     TEXT NOT NULL,
    last_walk_at   TEXT,
    next_seq       INTEGER NOT NULL DEFAULT 1,         -- the next change stamp for import_item.seq
    species_before INTEGER,                            -- species photographed when this import first filed (milestones)
    filings        INTEGER NOT NULL DEFAULT 0,         -- how many times filing was started (import_item.filing_no)
    last_species_before INTEGER,                       -- species photographed when the latest filing began
    -- The phase running now ('walking', 'reading', 'filing') and its progress, kept as it goes: a run still marked
    -- active at the next start was cut short by the app closing, and becomes stopped until the user continues it.
    active_phase   TEXT CHECK (active_phase IN ('walking', 'reading', 'filing')),
    active_done    INTEGER,
    active_total   INTEGER,
    stopped_phase  TEXT CHECK (stopped_phase IN ('reading', 'filing')),
    stopped_done   INTEGER,
    stopped_total  INTEGER,
    stopped_at     TEXT,
    paused         INTEGER NOT NULL DEFAULT 0 CHECK (paused IN (0, 1)),  -- the user paused filing
    notice         TEXT                                -- why filing last stopped for a library reason (drive full, not writable)
);

CREATE TABLE import_item (
    id                 INTEGER PRIMARY KEY,
    run_id             INTEGER NOT NULL REFERENCES import_run (id) ON DELETE CASCADE,
    rel_path           TEXT NOT NULL,                  -- relative to the root, '/' separators
    file_size          INTEGER NOT NULL,
    modified_at        TEXT NOT NULL,
    read_state         TEXT NOT NULL CHECK (read_state IN ('pending', 'read', 'unsupported', 'failed', 'missing')),
    problem            TEXT,                           -- why unsupported or failed, in plain words
    sha256             TEXT,
    format             TEXT,
    captured_at        TEXT,
    meta_json          TEXT,                           -- PhotoFacts.metaJson, stored with the photo when it is filed
    v                  TEXT,                           -- the preview's cache key; changes when the file is read again
    suggested_taxon_id TEXT REFERENCES taxon (id),
    library_taxon_id   TEXT REFERENCES taxon (id),     -- the same bytes are already a live photo of this species
    copy_of_item_id    INTEGER REFERENCES import_item (id) ON DELETE SET NULL,
    taxon_id           TEXT REFERENCES taxon (id),     -- the user's assignment
    skipped            INTEGER NOT NULL DEFAULT 0 CHECK (skipped IN (0, 1)),
    outcome            TEXT CHECK (outcome IN ('filed', 'duplicate', 'also_elsewhere', 'copy_of', 'failed')),
    image_id           INTEGER REFERENCES image (id) ON DELETE SET NULL,
    error              TEXT,
    first_for_species  INTEGER NOT NULL DEFAULT 0 CHECK (first_for_species IN (0, 1)),
    filing_no          INTEGER,                        -- which filing of the run gave the outcome (import_run.filings)
    seq                INTEGER NOT NULL,               -- the import's change stamp when this row last changed
    UNIQUE (run_id, rel_path)
);
CREATE INDEX import_item_seq ON import_item (run_id, seq);
CREATE INDEX import_item_state ON import_item (run_id, read_state);
CREATE INDEX import_item_sha ON import_item (run_id, sha256);
-- Foreign-key lookups when a photo is purged or an import discarded.
CREATE INDEX import_item_image ON import_item (image_id);
CREATE INDEX import_item_copy ON import_item (copy_of_item_id);

CREATE TABLE import_item_tag (
    item_id INTEGER NOT NULL REFERENCES import_item (id) ON DELETE CASCADE,
    tag_id  INTEGER NOT NULL REFERENCES tag (id) ON DELETE CASCADE,
    PRIMARY KEY (item_id, tag_id)
);
CREATE INDEX import_item_tag_tag ON import_item_tag (tag_id);
