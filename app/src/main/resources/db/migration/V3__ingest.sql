-- Butterfly Atlas Phase 3b-1: the photo pipeline. See docs/superpowers/specs/2026-10-12-phase-3b-bulk-import-design.md §3, §5.

-- What one read of a photo yielded (PhotoFacts.metaJson): format, size, orientation, capture time,
-- GPS, standard EXIF and basic XMP. Fact consumers work from this, never from the file.
CREATE TABLE image_meta (
    image_id INTEGER PRIMARY KEY REFERENCES image (id) ON DELETE CASCADE,
    json     TEXT NOT NULL,
    read_at  TEXT NOT NULL
);

-- Which consumer has processed which photo, at which version. A photo with no row at a consumer's
-- current version (or a failed one) is caught up at the next start.
CREATE TABLE image_processing (
    image_id INTEGER NOT NULL REFERENCES image (id) ON DELETE CASCADE,
    consumer TEXT NOT NULL,
    version  INTEGER NOT NULL,
    status   TEXT NOT NULL CHECK (status IN ('done', 'failed')),
    error    TEXT,
    done_at  TEXT NOT NULL,
    PRIMARY KEY (image_id, consumer)
);
