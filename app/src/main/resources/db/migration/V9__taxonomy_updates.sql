-- Butterfly Atlas Phase 5b: taxonomy updates. See docs/superpowers/specs/2026-10-15-phase-5b-contract.md §1, §7. Tables
-- and one column are only added (no table rebuilt, no column dropped), so no row of a library can be lost here.

-- What a taxonomy update changed in an import's items (a split species' assignments and suggestions cleared), shown on
-- the import as its own note; kept apart from notice (why filing stopped).
ALTER TABLE import_run ADD COLUMN update_note TEXT;

-- One update applied to the library: a newer bundle chain ('bundle'), or a custom species merged into the official
-- species it now matches ('match'). Each bundle step goes through plan, moves and switch; at most one is running or
-- paused at a time.
CREATE TABLE taxonomy_update (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    kind          TEXT NOT NULL CHECK (kind IN ('bundle', 'match')),
    from_version  TEXT NOT NULL,          -- the library's version when Apply was chosen
    to_version    TEXT NOT NULL,          -- the newest bundle (a match: the library's version)
    step_version  TEXT NOT NULL,          -- the bundle being applied now (a match: the library's version)
    custom_id     TEXT,                   -- a match: the custom species (no FK: it is deleted by the merge)
    official_id   TEXT,                   -- a match: the official species
    stage         TEXT NOT NULL CHECK (stage IN ('plan', 'moves', 'switch')),
    state         TEXT NOT NULL CHECK (state IN ('running', 'paused', 'done')),
    notice_code   TEXT,                   -- while paused: why (drive_gone, drive_full, locked, target_exists, …)
    notice        TEXT,                   -- the plain words
    total         INTEGER NOT NULL DEFAULT 0,   -- moves of the whole update so far planned
    done          INTEGER NOT NULL DEFAULT 0,
    started_at    TEXT NOT NULL,
    finished_at   TEXT
);
CREATE UNIQUE INDEX taxonomy_update_one ON taxonomy_update ((1)) WHERE state IN ('running', 'paused');

-- The file moves a step planned, in order; each is marked done as its photo's row follows its file.
CREATE TABLE taxonomy_move (
    update_id   INTEGER NOT NULL REFERENCES taxonomy_update (id),
    step_version TEXT NOT NULL,
    seq         INTEGER NOT NULL,
    image_id    INTEGER NOT NULL,         -- no FK: the record of what was planned
    old_rel     TEXT NOT NULL,
    new_rel     TEXT NOT NULL,
    new_file_name TEXT NOT NULL,
    new_number  INTEGER,                  -- null: the photo keeps its number (or has none)
    done        INTEGER NOT NULL DEFAULT 0 CHECK (done IN (0, 1)),
    PRIMARY KEY (update_id, step_version, seq)
);
CREATE INDEX taxonomy_move_open ON taxonomy_move (update_id, done, seq);

-- A custom species offered for merging into an official species of the same name, at the bundle version it was offered.
CREATE TABLE custom_match (
    custom_id       TEXT NOT NULL,        -- no FK: kept after the merge deletes it
    official_id     TEXT NOT NULL,
    offered_version TEXT NOT NULL,
    state           TEXT NOT NULL CHECK (state IN ('open', 'merged', 'declined')),
    decided_at      TEXT,
    PRIMARY KEY (custom_id, official_id, offered_version)
);

-- A live photo of a split species still to sort into one of the split's species.
CREATE TABLE split_photo (
    image_id  INTEGER PRIMARY KEY REFERENCES image (id) ON DELETE CASCADE,
    taxon_id  TEXT NOT NULL REFERENCES taxon (id),   -- the split species the photo is on
    version   TEXT NOT NULL                          -- the bundle that split it
);
