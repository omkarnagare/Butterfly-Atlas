-- Butterfly Atlas Phase 3a: photos and tags. See docs/superpowers/specs/2026-10-11-phase-3a-photos-core-design.md §3.

-- Folder names use underscores (decision P4). No photo folders exist yet, so nothing on disk moves.
UPDATE taxon SET folder_name = replace(folder_name, ' ', '_');

ALTER TABLE taxon ADD COLUMN next_photo_number INTEGER NOT NULL DEFAULT 1;
-- cover_image_id has no foreign key (image is rebuilt below), so purging a photo must clear it
-- (PhotoStore.purge does: clearCover).
ALTER TABLE taxon ADD COLUMN cover_image_id INTEGER;   -- the user's choice; null = automatic

-- image is rebuilt rather than altered: Phase 2 never wrote to it, and the UNIQUE on rel_path has
-- to give way to a partial index that ignores photos in Recently deleted.
-- Dropping is safe only because no released library has image rows yet; any later migration
-- that changes image must copy the rows across.
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
    source           TEXT NOT NULL CHECK (source IN ('app', 'rescan')),
    deleted_at       TEXT,                           -- set while the photo is in Recently deleted
    deleted_rel_path TEXT                            -- where it sits meanwhile, under .deleted/
);
CREATE UNIQUE INDEX image_live_path ON image (rel_path) WHERE deleted_at IS NULL;
CREATE INDEX image_live_taxon ON image (taxon_id) WHERE deleted_at IS NULL;
CREATE INDEX image_live_added ON image (added_at DESC, id DESC) WHERE deleted_at IS NULL;
CREATE INDEX image_sha ON image (sha256);

CREATE TABLE tag (
    id         INTEGER PRIMARY KEY,
    key        TEXT NOT NULL UNIQUE,
    label      TEXT NOT NULL,
    source     TEXT NOT NULL CHECK (source IN ('builtin', 'user')),
    sort_order INTEGER NOT NULL
);

INSERT INTO tag (key, label, source, sort_order) VALUES
    ('egg', 'egg', 'builtin', 1),
    ('larva', 'larva', 'builtin', 2),
    ('pupa', 'pupa', 'builtin', 3),
    ('upper_wing', 'upper wing', 'builtin', 4),
    ('under_wing', 'under wing', 'builtin', 5),
    ('male', 'male', 'builtin', 6),
    ('female', 'female', 'builtin', 7),
    ('dsf', 'DSF', 'builtin', 8),
    ('wsf', 'WSF', 'builtin', 9);

CREATE TABLE image_tag (
    image_id INTEGER NOT NULL REFERENCES image (id) ON DELETE CASCADE,
    tag_id   INTEGER NOT NULL REFERENCES tag (id) ON DELETE CASCADE,
    PRIMARY KEY (image_id, tag_id)
);
CREATE INDEX image_tag_tag ON image_tag (tag_id);
