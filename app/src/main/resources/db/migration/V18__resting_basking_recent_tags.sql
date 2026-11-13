-- Butterfly Atlas: the built-in tags resting and basking after mating, and when each tag was last used (subspecies names
-- and import ticks design, addendum §4 and §6). Photos and import items are linked to tags by id, so moving the built-ins
-- down changes only the order tags are listed in, never which photos carry them. No table is rebuilt.

-- Nectaring, puddling, predated and unconfirmed move down two to make room.
UPDATE tag SET sort_order = 13 WHERE key = 'nectaring' AND source = 'builtin';
UPDATE tag SET sort_order = 14 WHERE key = 'puddling' AND source = 'builtin';
UPDATE tag SET sort_order = 15 WHERE key = 'predated' AND source = 'builtin';
UPDATE tag SET sort_order = 16 WHERE key = 'unconfirmed' AND source = 'builtin';

-- The built-in tag resting (11), as V17 added its four. A user tag holding the key under another name (it was renamed)
-- gives the key up first: 'resting-<id>' ('-v18' added in the unlikely case a tag already has that key). A user tag named
-- "resting" keeps the key: the next step takes it over.
UPDATE tag SET key = 'resting-' || id
        || CASE WHEN EXISTS (SELECT 1 FROM tag t2 WHERE t2.key = 'resting-' || tag.id) THEN '-v18' ELSE '' END
WHERE key = 'resting' AND source = 'user' AND lower(label) <> 'resting';

-- A user tag already named "resting" (any case; at most one) becomes the built-in in place: its name and its photos' and
-- import items' tags stay.
UPDATE tag SET key = 'resting', source = 'builtin', sort_order = 11
WHERE id = (SELECT min(id) FROM tag WHERE source = 'user' AND lower(label) = 'resting');

-- Otherwise a new built-in, "resting_2" when a tag not taken over is already named "resting" (a renamed built-in).
INSERT INTO tag (key, label, source, sort_order)
SELECT 'resting',
       CASE WHEN EXISTS (SELECT 1 FROM tag WHERE lower(label) = 'resting') THEN 'resting_2' ELSE 'resting' END,
       'builtin', 11
WHERE NOT EXISTS (SELECT 1 FROM tag WHERE key = 'resting');

-- The built-in tag basking (12), as V17 added its four. A user tag holding the key under another name (it was renamed)
-- gives the key up first: 'basking-<id>' ('-v18' added in the unlikely case a tag already has that key). A user tag named
-- "basking" keeps the key: the next step takes it over.
UPDATE tag SET key = 'basking-' || id
        || CASE WHEN EXISTS (SELECT 1 FROM tag t2 WHERE t2.key = 'basking-' || tag.id) THEN '-v18' ELSE '' END
WHERE key = 'basking' AND source = 'user' AND lower(label) <> 'basking';

-- A user tag already named "basking" (any case; at most one) becomes the built-in in place: its name and its photos' and
-- import items' tags stay.
UPDATE tag SET key = 'basking', source = 'builtin', sort_order = 12
WHERE id = (SELECT min(id) FROM tag WHERE source = 'user' AND lower(label) = 'basking');

-- Otherwise a new built-in, "basking_2" when a tag not taken over is already named "basking" (a renamed built-in).
INSERT INTO tag (key, label, source, sort_order)
SELECT 'basking',
       CASE WHEN EXISTS (SELECT 1 FROM tag WHERE lower(label) = 'basking') THEN 'basking_2' ELSE 'basking' END,
       'builtin', 12
WHERE NOT EXISTS (SELECT 1 FROM tag WHERE key = 'basking');

-- When the tag was last added to a photo or an import item, or made (ISO-8601 UTC with six decimals, so the text sorts as the time): the tag pickers' Recent row lists the
-- user's own tags by it. Empty until then.
ALTER TABLE tag ADD COLUMN last_used_at TEXT;
CREATE INDEX tag_last_used ON tag (last_used_at) WHERE source = 'user' AND last_used_at IS NOT NULL;
