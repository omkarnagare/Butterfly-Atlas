-- Butterfly Atlas: the built-in tags mating, nectaring, puddling and predated (behaviour tags design §2). Only rows are
-- updated or added (no table rebuild), so no row of a library can be lost here.

-- Unconfirmed stays the last built-in tag: it moves after the four new ones (a built-in added later moves it again).
UPDATE tag SET sort_order = 14 WHERE key = 'unconfirmed';

-- The built-in tag mating (10), as V8 added Unconfirmed. A user tag holding the key under another name (it was renamed)
-- gives the key up first: 'mating-<id>' ('-v17' added in the unlikely case a tag already has that key). A user tag
-- named "mating" keeps the key: step 2 takes it over.
UPDATE tag SET key = 'mating-' || id
        || CASE WHEN EXISTS (SELECT 1 FROM tag t2 WHERE t2.key = 'mating-' || tag.id) THEN '-v17' ELSE '' END
WHERE key = 'mating' AND source = 'user' AND lower(label) <> 'mating';

-- A user tag already named "mating" (any case; names are unique without case, so at most one, the lowest id taken all
-- the same) becomes the built-in in place: its name and its photos' and import items' tags stay.
UPDATE tag SET key = 'mating', source = 'builtin', sort_order = 10
WHERE id = (SELECT min(id) FROM tag WHERE source = 'user' AND lower(label) = 'mating');

-- Otherwise a new built-in. When a tag not taken over is already named "mating" (a built-in the user renamed, e.g. DSF),
-- the new one is "mating_2", as V15 numbers two built-ins apart.
INSERT INTO tag (key, label, source, sort_order)
SELECT 'mating',
       CASE WHEN EXISTS (SELECT 1 FROM tag WHERE lower(label) = 'mating') THEN 'mating_2' ELSE 'mating' END,
       'builtin', 10
WHERE NOT EXISTS (SELECT 1 FROM tag WHERE key = 'mating');

-- The built-in tag nectaring (11), as V8 added Unconfirmed. A user tag holding the key under another name (it was renamed)
-- gives the key up first: 'nectaring-<id>' ('-v17' added in the unlikely case a tag already has that key). A user tag
-- named "nectaring" keeps the key: step 2 takes it over.
UPDATE tag SET key = 'nectaring-' || id
        || CASE WHEN EXISTS (SELECT 1 FROM tag t2 WHERE t2.key = 'nectaring-' || tag.id) THEN '-v17' ELSE '' END
WHERE key = 'nectaring' AND source = 'user' AND lower(label) <> 'nectaring';

-- A user tag already named "nectaring" (any case; names are unique without case, so at most one, the lowest id taken all
-- the same) becomes the built-in in place: its name and its photos' and import items' tags stay.
UPDATE tag SET key = 'nectaring', source = 'builtin', sort_order = 11
WHERE id = (SELECT min(id) FROM tag WHERE source = 'user' AND lower(label) = 'nectaring');

-- Otherwise a new built-in. When a tag not taken over is already named "nectaring" (a built-in the user renamed, e.g. DSF),
-- the new one is "nectaring_2", as V15 numbers two built-ins apart.
INSERT INTO tag (key, label, source, sort_order)
SELECT 'nectaring',
       CASE WHEN EXISTS (SELECT 1 FROM tag WHERE lower(label) = 'nectaring') THEN 'nectaring_2' ELSE 'nectaring' END,
       'builtin', 11
WHERE NOT EXISTS (SELECT 1 FROM tag WHERE key = 'nectaring');

-- The built-in tag puddling (12), as V8 added Unconfirmed. A user tag holding the key under another name (it was renamed)
-- gives the key up first: 'puddling-<id>' ('-v17' added in the unlikely case a tag already has that key). A user tag
-- named "puddling" keeps the key: step 2 takes it over.
UPDATE tag SET key = 'puddling-' || id
        || CASE WHEN EXISTS (SELECT 1 FROM tag t2 WHERE t2.key = 'puddling-' || tag.id) THEN '-v17' ELSE '' END
WHERE key = 'puddling' AND source = 'user' AND lower(label) <> 'puddling';

-- A user tag already named "puddling" (any case; names are unique without case, so at most one, the lowest id taken all
-- the same) becomes the built-in in place: its name and its photos' and import items' tags stay.
UPDATE tag SET key = 'puddling', source = 'builtin', sort_order = 12
WHERE id = (SELECT min(id) FROM tag WHERE source = 'user' AND lower(label) = 'puddling');

-- Otherwise a new built-in. When a tag not taken over is already named "puddling" (a built-in the user renamed, e.g. DSF),
-- the new one is "puddling_2", as V15 numbers two built-ins apart.
INSERT INTO tag (key, label, source, sort_order)
SELECT 'puddling',
       CASE WHEN EXISTS (SELECT 1 FROM tag WHERE lower(label) = 'puddling') THEN 'puddling_2' ELSE 'puddling' END,
       'builtin', 12
WHERE NOT EXISTS (SELECT 1 FROM tag WHERE key = 'puddling');

-- The built-in tag predated (13), as V8 added Unconfirmed. A user tag holding the key under another name (it was renamed)
-- gives the key up first: 'predated-<id>' ('-v17' added in the unlikely case a tag already has that key). A user tag
-- named "predated" keeps the key: step 2 takes it over.
UPDATE tag SET key = 'predated-' || id
        || CASE WHEN EXISTS (SELECT 1 FROM tag t2 WHERE t2.key = 'predated-' || tag.id) THEN '-v17' ELSE '' END
WHERE key = 'predated' AND source = 'user' AND lower(label) <> 'predated';

-- A user tag already named "predated" (any case; names are unique without case, so at most one, the lowest id taken all
-- the same) becomes the built-in in place: its name and its photos' and import items' tags stay.
UPDATE tag SET key = 'predated', source = 'builtin', sort_order = 13
WHERE id = (SELECT min(id) FROM tag WHERE source = 'user' AND lower(label) = 'predated');

-- Otherwise a new built-in. When a tag not taken over is already named "predated" (a built-in the user renamed, e.g. DSF),
-- the new one is "predated_2", as V15 numbers two built-ins apart.
INSERT INTO tag (key, label, source, sort_order)
SELECT 'predated',
       CASE WHEN EXISTS (SELECT 1 FROM tag WHERE lower(label) = 'predated') THEN 'predated_2' ELSE 'predated' END,
       'builtin', 13
WHERE NOT EXISTS (SELECT 1 FROM tag WHERE key = 'predated');
