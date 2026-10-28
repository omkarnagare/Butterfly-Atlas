-- Butterfly Atlas Phase 3c-2: setting and correcting places. See docs/superpowers/specs/2026-10-13-phase-3c-2-contract.md §2.
-- A table and columns are only added (no table rebuild), so no row of a library can be lost here.

-- Places applied with Place… (photos or import items), newest first by used_at; a deleted place leaves the list.
CREATE TABLE place_recent (
    place_id TEXT PRIMARY KEY REFERENCES place (id) ON DELETE CASCADE,
    used_at  TEXT NOT NULL
);

-- An import item's place chosen by the user (user_place_set 1; user_place_id NULL = removed), filed as the user's.
ALTER TABLE import_item ADD COLUMN user_place_id TEXT;
ALTER TABLE import_item ADD COLUMN user_place_set INTEGER NOT NULL DEFAULT 0 CHECK (user_place_set IN (0, 1));
