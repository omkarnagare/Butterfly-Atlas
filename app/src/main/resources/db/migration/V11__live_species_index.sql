-- Butterfly Atlas, polish after 5c: the live photos' index by species also holds what the counts and covers read
-- (newest first, size, place; deleted_at too, so SQLite sees the index holds every column asked), so the filter's
-- tallies, the species counts and Home's covers read the index alone instead of every photo's row. It replaces
-- image_live_taxon (V2), whose column it starts with. Only an index is added and one dropped: no table is touched, so
-- no row of a library can change here.
CREATE INDEX image_live_species ON image (taxon_id, added_at DESC, id DESC, file_size, place_id, deleted_at)
    WHERE deleted_at IS NULL;
DROP INDEX image_live_taxon;
