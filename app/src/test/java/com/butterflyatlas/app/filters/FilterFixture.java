package com.butterflyatlas.app.filters;

import com.butterflyatlas.app.db.Database;
import com.butterflyatlas.app.library.Library;
import com.butterflyatlas.app.testing.TestBundles;

import java.nio.file.Path;
import java.sql.PreparedStatement;

/**
 * The fixture library (14 taxa; places of {@code TestPlaces}: P1 Testland › P2 Northstate › P4 Alpha, P5 Beta, P6, P7;
 * P1 › P3 Leafstate; P8 Farland, P9 Westfar) with photos recorded straight into {@code image} (no files), for the filter
 * tests. Species in taxonomic order: A5 Danaus chrysippus, A6 Danaus genutia, A8 Tirumala limniace (Nymphalidae ›
 * Danainae › Danaini), then A14 Papilio clytia, A13 Papilio demoleus, A12 Papilio polytes (Papilionidae › Papilioninae,
 * no tribe).
 */
final class FilterFixture {

    final Library library;
    final Database db;
    final FilterQuery query;

    FilterFixture(Path dir) {
        library = TestBundles.fixtureLibrary(dir);
        db = library.database();
        query = new FilterQuery(library::database);
    }

    /** A live photo of {@code taxonId} at {@code placeId} (null: unknown place) with these tags; returns its id. */
    long photo(String taxonId, String placeId, String... tags) {
        long id = TestBundles.addImage(db, taxonId);
        if (placeId != null) {
            sql("UPDATE image SET place_id = '" + placeId + "', place_source = 'gps' WHERE id = " + id);
        }
        TestBundles.tagImage(db, id, tags);
        return id;
    }

    void size(long id, long bytes) {
        sql("UPDATE image SET file_size = " + bytes + " WHERE id = " + id);
    }

    /** The photo's file type ({@code jpeg} … {@code raw}; null: an older library's photo not read yet). */
    void type(long id, String fileType) {
        sql("UPDATE image SET file_type = " + (fileType == null ? "NULL" : "'" + fileType + "'") + " WHERE id = " + id);
    }

    void number(long id, Integer number) {
        sql("UPDATE image SET number = " + number + " WHERE id = " + id);
    }

    void delete(long id) {
        TestBundles.markDeleted(db, id);
    }

    void retire(String taxonId) {
        TestBundles.retire(db, taxonId);
    }

    void manual(String id, String name) {
        TestBundles.addManualSpecies(db, id, name);
    }

    /** A custom species of the family {@code familyId}. */
    void custom(String id, String name, String familyId) {
        TestBundles.addManualSpecies(db, id, name, familyId);
    }

    /** A user district under Northstate (P2). */
    void userDistrict(String id, String name) {
        sql("INSERT INTO place (id, parent_id, level, name, source, ancestor_path, sort_key, active)"
                + " VALUES ('" + id + "', 'P2', 'district', '" + name + "', 'user', '/P1/P2/" + id + "/', lower('" + name + "'), 1)");
    }

    /** A taxon added under {@code parentId} (its path follows the parent's). */
    void taxon(String id, String parentId, String rank, String name, String common) {
        sql("INSERT INTO taxon (id, parent_id, rank, name, common_name, group_key, source, ancestor_path, folder_name,"
                + " active, sort_key) SELECT '" + id + "', id, '" + rank + "', '" + name + "', "
                + (common == null ? "NULL" : "'" + common + "'") + ", 'butterflies', 'official', ancestor_path || '" + id
                + "/', '" + name.replace(' ', '_') + "_[" + id + "]', 1, lower('" + name + "') FROM taxon WHERE id = '"
                + parentId + "'");
    }

    void sql(String sql) {
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.executeUpdate();
            }
            return null;
        });
    }

    FilterQuery.FilterCount count(String filter) {
        return query.count(Filter.parse(filter));
    }

    /** "photos species bytes" of a count. */
    String numbers(String filter) {
        FilterQuery.FilterCount count = count(filter);
        return count.photos() + " " + count.species() + " " + count.bytes();
    }
}
