-- Butterfly Atlas core schema. See docs/PLAN.md §4 and the Phase 2 design.

CREATE TABLE taxon (
    id            TEXT PRIMARY KEY,                -- Atlas id "A339", manual "U7", roots "G:butterflies" / "U:butterflies"
    parent_id     TEXT REFERENCES taxon (id),      -- null only for the group root
    rank          TEXT NOT NULL,                   -- group | additions | family | subfamily | tribe | genus | species
    name          TEXT NOT NULL,                   -- scientific name
    common_name   TEXT,
    group_key     TEXT NOT NULL,                   -- 'butterflies'
    source        TEXT NOT NULL CHECK (source IN ('official', 'manual', 'system')),
    inat_id       INTEGER,                         -- reference only, not an identity
    ancestor_path TEXT NOT NULL,                   -- "/G:butterflies/A1/…/A339/", includes the taxon itself
    folder_name   TEXT NOT NULL,                   -- "Danaus chrysippus [A5]" for species, the name otherwise
    active        INTEGER NOT NULL DEFAULT 1 CHECK (active IN (0, 1)),
    sort_key      TEXT NOT NULL                    -- lower-cased name
);
CREATE INDEX taxon_parent ON taxon (parent_id, sort_key);
CREATE INDEX taxon_rank ON taxon (rank);

CREATE TABLE taxon_synonym (
    taxon_id TEXT NOT NULL REFERENCES taxon (id),
    name     TEXT NOT NULL COLLATE NOCASE,
    source   TEXT NOT NULL CHECK (source IN ('bundle', 'user')),
    added_at TEXT,
    PRIMARY KEY (taxon_id, name)
);

-- One row per searchable text: kind is 'name', 'common', 'synonym' (bundle) or 'word' (user).
CREATE VIRTUAL TABLE taxon_fts USING fts5 (
    taxon_id UNINDEXED,
    kind UNINDEXED,
    text,
    tokenize = 'unicode61 remove_diacritics 2'
);

CREATE TABLE image (
    id          INTEGER PRIMARY KEY,
    taxon_id    TEXT NOT NULL REFERENCES taxon (id),
    rel_path    TEXT NOT NULL UNIQUE,
    file_size   INTEGER NOT NULL,
    modified_at TEXT NOT NULL,
    sha256      TEXT,
    added_at    TEXT NOT NULL
);
CREATE INDEX image_taxon ON image (taxon_id);

CREATE TABLE taxonomy_state (
    bundle_version TEXT PRIMARY KEY,
    bundle_sha256  TEXT NOT NULL,
    applied_at     TEXT NOT NULL
);

CREATE TABLE change_log (
    id           INTEGER PRIMARY KEY,
    at           TEXT NOT NULL,
    kind         TEXT NOT NULL,
    payload_json TEXT NOT NULL
);
