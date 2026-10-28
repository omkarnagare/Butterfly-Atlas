-- Butterfly Atlas Phase 4b: exports. See docs/superpowers/specs/2026-10-13-phase-4b-contract.md §2, §3.
-- Tables are only added (no table rebuild), so no row of a library can be lost here.

-- One export: what was chosen, where it went, and how far it is. The photo list is fixed at the start (export_item);
-- progress is saved in batches as the copy goes, so an export the app's closing cut short continues where it was.
CREATE TABLE export_run (
    id             INTEGER PRIMARY KEY AUTOINCREMENT,
    dest_root      TEXT NOT NULL,                      -- the chosen folder, as the computer writes paths
    folder_name    TEXT NOT NULL,                      -- the export folder created in it, or the CSV file for CSV only
    what           TEXT NOT NULL CHECK (what IN ('photos', 'csv', 'both')),
    layout         TEXT NOT NULL CHECK (layout IN ('species', 'flat')),
    strip_location INTEGER NOT NULL CHECK (strip_location IN (0, 1)),
    place_depth    TEXT NOT NULL CHECK (place_depth IN ('none', 'country', 'state', 'district')),
    filter_query   TEXT NOT NULL,                      -- the filter as used, normalised
    csv_name       TEXT,                               -- the species list's file name; null for photos only
    csv_bytes      INTEGER,                            -- its size and time (ms) as written: Remove deletes it only
    csv_mtime      INTEGER,                            -- while it still matches both
    species        INTEGER NOT NULL,                   -- the species count at the start
    state          TEXT NOT NULL CHECK (state IN ('running', 'paused', 'done', 'cancelled')),
    paused_by      TEXT CHECK (paused_by IN ('you', 'closing', 'drive_full', 'drive_gone', 'write_errors')),
    notice         TEXT,                               -- the plain words for paused_by
    cancel_choice  TEXT CHECK (cancel_choice IN ('keep', 'remove')),
    not_removed    INTEGER NOT NULL DEFAULT 0,         -- files Remove could not delete
    total          INTEGER NOT NULL,                   -- photos (export_item rows)
    copied         INTEGER NOT NULL DEFAULT 0,
    skipped        INTEGER NOT NULL DEFAULT 0,
    failed         INTEGER NOT NULL DEFAULT 0,
    renamed        INTEGER NOT NULL DEFAULT 0,         -- photos copied under a name shortened to fit the path
    total_bytes    INTEGER NOT NULL,
    bytes_done     INTEGER NOT NULL DEFAULT 0,         -- the originals' sizes of the photos dealt with
    started_at     TEXT NOT NULL,
    finished_at    TEXT
);
CREATE INDEX export_run_state ON export_run (state, id);
-- One export at a time: at most one row running or paused (every such row has the same key, 1).
CREATE UNIQUE INDEX export_run_one ON export_run ((1)) WHERE state IN ('running', 'paused');

-- The photos of an export in order, with the target names fixed at the start. image_id has no foreign key: a photo
-- purged after the start is skipped, and its row stays as the record of what was asked. taxon_id has none either: the
-- problems list reads the species' name with a LEFT JOIN, so a species gone since still lists its photos.
CREATE TABLE export_item (
    run_id     INTEGER NOT NULL REFERENCES export_run (id) ON DELETE CASCADE,
    seq        INTEGER NOT NULL,                       -- 1, 2, … in the export's order
    image_id   INTEGER NOT NULL,
    taxon_id   TEXT NOT NULL,                          -- the photo's species at the start (for the problems list)
    file_name  TEXT NOT NULL,                          -- the photo's library file name at the start
    target_rel TEXT NOT NULL,                          -- relative to the export folder, '/' separators
    renamed    INTEGER NOT NULL DEFAULT 0 CHECK (renamed IN (0, 1)),
    bytes      INTEGER NOT NULL,                       -- the photo's size at the start
    state      TEXT NOT NULL DEFAULT 'pending' CHECK (state IN ('pending', 'done', 'failed', 'skipped')),
    reason     TEXT,                                   -- why skipped or failed, in plain words
    written_bytes INTEGER,                             -- done: the size of the file written, and its time (ms since
    written_mtime INTEGER,                             -- 1970); Remove deletes it only while it still matches both
    PRIMARY KEY (run_id, seq),
    UNIQUE (run_id, target_rel COLLATE NOCASE)             -- no two targets of one export differ only by ASCII case
    -- (NOCASE folds A-Z only; the names themselves are merged without case in full Unicode before they get here)
);
CREATE INDEX export_item_state ON export_item (run_id, state, seq);
