-- Butterfly Atlas: one device at a time (docs/superpowers/specs/2026-11-04-one-device-library-design.md §5).
-- Only a table is added, so no row of a library can be lost here. Raising the schema version also makes an older app
-- refuse a converted library, so it can't write into the snapshot.

-- The library's sync facts (key, value):
--   library_id    the library's random ID; it names the working copy's folder in the config folder
--   generation    in the snapshot (the library's atlas.db), its own number; in a working copy, the number of the snapshot
--                 it was last in step with
--   published_at  in a working copy, the ISO instant it was last in step with the library
--   dirty         '1' in a working copy written since then (every write transaction sets it); a snapshot carries '0'
CREATE TABLE sync_state (
    key   TEXT PRIMARY KEY,
    value TEXT
);

INSERT INTO sync_state (key, value) VALUES ('dirty', '0');
