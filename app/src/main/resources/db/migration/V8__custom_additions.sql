-- Butterfly Atlas Phase 5a: custom additions and the built-in tag Unconfirmed. See
-- docs/superpowers/specs/2026-10-15-phase-5a-contract.md §1. Only a column is added and rows updated (no table rebuild),
-- so no row of a library can be lost here.

-- A custom species' family: the id of an official family. Set only on source = 'manual' species; their place in the
-- tree stays under the group's Custom additions (counts follow ancestor_path, so a family never counts them).
ALTER TABLE taxon ADD COLUMN family_id TEXT;

-- "My additions" becomes "Custom additions". No species can be under it before 5a, so no file moves; an empty
-- images/My_additions/ left on disk is not touched.
UPDATE taxon SET name = 'Custom additions', folder_name = 'Custom_additions', sort_key = 'custom additions'
WHERE rank = 'additions';

-- The built-in tag Unconfirmed (key 'unconfirmed'). A user tag holding that key under another label (it was renamed)
-- gives the key up first: 'unconfirmed-<id>' ('-v8' added in the unlikely case a tag already has that key).
UPDATE tag SET key = 'unconfirmed-' || id
        || CASE WHEN EXISTS (SELECT 1 FROM tag t2 WHERE t2.key = 'unconfirmed-' || tag.id) THEN '-v8' ELSE '' END
WHERE key = 'unconfirmed' AND source = 'user' AND lower(label) <> 'unconfirmed';

-- A user tag already called "unconfirmed" (any case; labels are unique without case, so at most one, the lowest id
-- taken all the same) becomes the built-in in place: its label and its photos' and import items' tags stay.
UPDATE tag SET key = 'unconfirmed', source = 'builtin', sort_order = 10
WHERE id = (SELECT min(id) FROM tag WHERE source = 'user' AND lower(label) = 'unconfirmed');

-- Otherwise a new built-in after WSF. Labels stay unique without case: when a tag not taken over is already called
-- "unconfirmed" (a built-in the user relabelled, e.g. DSF), the new one is "Unconfirmed (built-in)".
INSERT INTO tag (key, label, source, sort_order)
SELECT 'unconfirmed',
       CASE WHEN EXISTS (SELECT 1 FROM tag WHERE lower(label) = 'unconfirmed') THEN 'Unconfirmed (built-in)'
            ELSE 'Unconfirmed' END,
       'builtin', 10
WHERE NOT EXISTS (SELECT 1 FROM tag WHERE key = 'unconfirmed');
