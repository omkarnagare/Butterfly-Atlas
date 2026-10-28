-- Butterfly Atlas Phase 3c-1: observation places from GPS. See docs/superpowers/specs/2026-10-13-phase-3c-1-contract.md §4.
-- Tables and columns are only added (no table rebuild), so no row of a library can be lost here.

-- The place tree (country > state > district), loaded from the bundled place data; users add districts in 3c-2.
CREATE TABLE place (
    id            TEXT PRIMARY KEY,                 -- 'P412' bundled (permanent), 'PU3' added by the user
    parent_id     TEXT REFERENCES place (id),       -- null for countries
    level         TEXT NOT NULL CHECK (level IN ('country', 'state', 'district')),
    name          TEXT NOT NULL,
    source        TEXT NOT NULL CHECK (source IN ('bundled', 'user')),
    ancestor_path TEXT NOT NULL,                    -- '/P1/P20/P412/'
    sort_key      TEXT NOT NULL,                    -- lower-cased name
    active        INTEGER NOT NULL DEFAULT 1 CHECK (active IN (0, 1))   -- 0 once retired by a later bundle (Phase 5)
);
CREATE INDEX place_parent ON place (parent_id);
CREATE INDEX place_path ON place (ancestor_path);

-- Other spellings of a place ("Poona"), for search.
CREATE TABLE place_alias (
    place_id TEXT NOT NULL REFERENCES place (id) ON DELETE CASCADE,
    name     TEXT NOT NULL,
    PRIMARY KEY (place_id, name)
);

-- Which place bundle the library holds (as taxonomy_state).
CREATE TABLE place_state (
    bundle_version TEXT NOT NULL,
    bundle_sha256  TEXT NOT NULL,
    applied_at     TEXT NOT NULL
);

-- Where a photo was observed: null = Unknown place. No foreign key: retiring or removing a place never cascades into photos.
ALTER TABLE image ADD COLUMN place_id TEXT;
-- 'gps': looked up from the photo's GPS (follows it); 'user': set or removed by the user, never overridden; null: never set.
ALTER TABLE image ADD COLUMN place_source TEXT CHECK (place_source IN ('gps', 'user'));
CREATE INDEX image_live_place ON image (place_id) WHERE deleted_at IS NULL;

-- Failures at the consumer's current version; a photo that failed 3 times is left until the version changes.
ALTER TABLE image_processing ADD COLUMN attempts INTEGER NOT NULL DEFAULT 0;
UPDATE image_processing SET attempts = 1 WHERE status = 'failed';

-- The place an import item's GPS lies in, looked up while reading the folder.
ALTER TABLE import_item ADD COLUMN gps_place_id TEXT;
