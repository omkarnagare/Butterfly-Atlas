-- Butterfly Atlas Phase 6: backups and per-library settings. See docs/superpowers/specs/2026-10-21-phase-6-contract.md §1.
-- Tables are only added (no table rebuild), so no row of a library can be lost here.

-- Small per-library settings (key, value). Phase 6 keys:
--   backup.reminder    'remind' | 'less' | 'none'   (absent: never asked)
--   backup.last_at     the ISO instant of the last backup that finished (set by that backup only)
--   backup.last_what   'database' | 'everything'
--   backup.last_unread photos that backup could not copy (skipped + failed)
CREATE TABLE library_setting (
    key   TEXT PRIMARY KEY,
    value TEXT NOT NULL
);

-- One backup: what was chosen, where it went, and how far it is (the states are an export's).
CREATE TABLE backup_run (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    state         TEXT NOT NULL CHECK (state IN ('running', 'paused', 'done', 'cancelled')),
    what          TEXT NOT NULL CHECK (what IN ('database', 'everything')),
    dest_root     TEXT NOT NULL,                      -- the chosen folder, as the computer writes paths
    folder        TEXT NOT NULL,                      -- the folder this backup creates in it (Butterfly_Atlas_backup_<date>[_n])
    total         INTEGER NOT NULL,                   -- photos to copy (0 for the database alone)
    done          INTEGER NOT NULL DEFAULT 0,         -- photos dealt with: copied, skipped or failed
    copied        INTEGER NOT NULL DEFAULT 0,
    skipped       INTEGER NOT NULL DEFAULT 0,
    failed        INTEGER NOT NULL DEFAULT 0,
    bytes_total   INTEGER NOT NULL,
    bytes_done    INTEGER NOT NULL DEFAULT 0,
    paused_by     TEXT,
    notice        TEXT,                               -- the plain words for paused_by
    cancel        TEXT CHECK (cancel IN ('keep', 'remove')),
    not_removed   INTEGER NOT NULL DEFAULT 0,         -- files Remove could not delete
    last_image_id INTEGER NOT NULL DEFAULT 0,         -- photos are copied in id order: a resume goes on after this one
    started_at    TEXT NOT NULL,
    finished_at   TEXT
);
-- One backup at a time: at most one row running or paused (every such row has the same key, 1).
CREATE UNIQUE INDEX backup_run_one ON backup_run ((1)) WHERE state IN ('running', 'paused');

-- What a backup wrote: Remove deletes only these, and only while they still match.
CREATE TABLE backup_file (
    run_id   INTEGER NOT NULL REFERENCES backup_run (id) ON DELETE CASCADE,
    rel      TEXT NOT NULL,                           -- relative to the backup folder, '/' separators
    size     INTEGER NOT NULL,
    mtime_ms INTEGER NOT NULL,
    PRIMARY KEY (run_id, rel)
);

-- Photos a backup could not copy, with the reason in plain words (skipped: gone or removed meanwhile; failed: could not
-- be read or written, or came out the wrong size).
CREATE TABLE backup_problem (
    run_id INTEGER NOT NULL REFERENCES backup_run (id) ON DELETE CASCADE,
    rel    TEXT NOT NULL,
    reason TEXT NOT NULL,
    kind   TEXT NOT NULL DEFAULT 'failed' CHECK (kind IN ('skipped', 'failed')),
    PRIMARY KEY (run_id, rel)
);
