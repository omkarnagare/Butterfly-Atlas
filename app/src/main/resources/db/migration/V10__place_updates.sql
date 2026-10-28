-- Butterfly Atlas Phase 5c: place-data updates. See docs/superpowers/specs/2026-10-16-phase-5c-contract.md §1, §10. A
-- column, a table and an index are only added (no table rebuilt, no column dropped), so no row of a library can be lost
-- here.

-- A merged place: the place it became part of (no foreign key, like image.place_id; a chain of merges is followed).
ALTER TABLE place ADD COLUMN replaced_by TEXT;

-- One place list the library was brought up to by an update step (never the first list a library loads), with what
-- the step changed for the Home note.
CREATE TABLE place_update (
    version       TEXT PRIMARY KEY,                -- the place list this step brought the library to
    from_version  TEXT NOT NULL,
    applied_at    TEXT NOT NULL,
    touches       INTEGER NOT NULL CHECK (touches IN (0, 1)),   -- the step changed something of the library's (shown)
    summary_json  TEXT NOT NULL,                   -- the step's summary: counts, the changes that touch, merged user places
    dismissed_at  TEXT,                            -- the Home note's Dismiss (stamped on every row each time)
    rechecked_at  TEXT                             -- when no live photo was left for the GPS re-check at this list
);

-- The Home note reads change_log by kind (place.recheck, the steps' entries).
CREATE INDEX change_log_kind ON change_log (kind);
