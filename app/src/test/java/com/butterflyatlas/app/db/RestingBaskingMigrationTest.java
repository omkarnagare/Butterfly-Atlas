package com.butterflyatlas.app.db;

import com.butterflyatlas.app.photos.TagService;
import com.butterflyatlas.app.taxonomy.BundleLoader;
import com.butterflyatlas.app.testing.TestBundles;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V18 (subspecies names and import ticks design, addendum §4 and §6): the built-in tags resting and basking between
 * mating and nectaring (nectaring, puddling, predated and unconfirmed move down two), added as V17 added its four; and
 * {@code tag.last_used_at}, empty until a tag is used. A photo keeps every tag it had: only sort orders change.
 */
class RestingBaskingMigrationTest {

    private static final String TAGS = "SELECT id, key, label, source, sort_order FROM tag ORDER BY id";

    @TempDir
    Path dir;

    private Path file() {
        return dir.resolve(Database.FILE_NAME);
    }

    /** A version 17 library with the fixture taxa and the 14 built-ins (ids 1-10, 11-14), photos 1-2 of A5, an import item. */
    private void version17Library(String... statements) {
        Database v17 = Database.open(file(), "17");
        BundleLoader.load(v17, TestBundles.fixture().newest(), TestBundles.CLOCK);
        TestBundles.addImage(v17, "A5");
        TestBundles.addImage(v17, "A5");
        v17.write(c -> {
            try (Statement st = c.createStatement()) {
                st.executeUpdate("INSERT INTO import_run (root_path, state, created_at, updated_at, next_seq) VALUES"
                        + " ('/cards/open', 'open', '2026-10-20T08:00:00Z', '2026-10-20T08:00:00Z', 10)");
                st.executeUpdate("INSERT INTO import_item (run_id, rel_path, file_size, modified_at, read_state, seq) VALUES"
                        + " (1, 'a.jpg', 10, '2026-10-20T07:00:00Z', 'read', 3)");
                for (String statement : statements) {
                    st.executeUpdate(statement);
                }
            }
            return null;
        });
        assertThat(rows(v17, "SELECT id, key, sort_order FROM tag WHERE id BETWEEN 10 AND 14 ORDER BY id")).containsExactly(
                "10|unconfirmed|14", "11|mating|10", "12|nectaring|11", "13|puddling|12", "14|predated|13");
    }

    private Database migrate() {
        Database db = Database.open(file());
        assertThat(Database.newestSchemaVersion(file())).hasValue(18);
        return db;
    }

    private static List<String> rows(Database db, String sql) {
        return db.read(c -> {
            List<String> rows = new ArrayList<>();
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
                ResultSetMetaData meta = rs.getMetaData();
                while (rs.next()) {
                    StringBuilder row = new StringBuilder();
                    for (int i = 1; i <= meta.getColumnCount(); i++) {
                        row.append(i > 1 ? "|" : "").append(rs.getString(i));
                    }
                    rows.add(row.toString());
                }
            }
            return rows;
        });
    }

    private static List<String> ordered(Database db) {
        return rows(db, "SELECT t.key FROM tag t ORDER BY " + TagService.ORDER);
    }

    @Test
    void restingAndBaskingComeAfterMatingAndAPhotoTaggedNectaringKeepsIt() {
        version17Library("INSERT INTO tag (id, key, label, source, sort_order) VALUES (15, 'roosting', 'roosting', 'user', 1000)",
                "INSERT INTO image_tag (image_id, tag_id) VALUES (1, 12), (1, 15), (2, 11)",
                "INSERT INTO import_item_tag (item_id, tag_id) VALUES (1, 12)");

        Database db = migrate();

        assertThat(rows(db, TAGS + " LIMIT -1 OFFSET 9")).containsExactly(
                "10|unconfirmed|unconfirmed|builtin|16", "11|mating|mating|builtin|10", "12|nectaring|nectaring|builtin|13",
                "13|puddling|puddling|builtin|14", "14|predated|predated|builtin|15", "15|roosting|roosting|user|1000",
                "16|resting|resting|builtin|11", "17|basking|basking|builtin|12");
        assertThat(ordered(db)).containsExactly("egg", "larva", "pupa", "upper_wing", "under_wing", "male", "female",
                "dsf", "wsf", "mating", "resting", "basking", "nectaring", "puddling", "predated", "unconfirmed", "roosting");
        assertThat(rows(db, "SELECT image_id, tag_id FROM image_tag ORDER BY image_id, tag_id"))
                .as("nectaring (12) still on photo 1").containsExactly("1|12", "1|15", "2|11");
        assertThat(rows(db, "SELECT item_id, tag_id FROM import_item_tag")).containsExactly("1|12");
    }

    @Test
    void userTagsCalledRestingAndBaskingBecomeTheBuiltInsInPlaceAndKeepTheirPhotos() {
        version17Library("INSERT INTO tag (id, key, label, source, sort_order) VALUES (15, 'basking', 'Basking', 'user', 1000),"
                        + " (16, 'resting', 'RESTING', 'user', 1000)",
                "INSERT INTO image_tag (image_id, tag_id) VALUES (1, 15), (2, 15), (2, 16)",
                "INSERT INTO import_item_tag (item_id, tag_id) VALUES (1, 16)");

        Database db = migrate();

        assertThat(rows(db, TAGS + " LIMIT -1 OFFSET 14")).as("same rows, names kept, nothing added").containsExactly(
                "15|basking|Basking|builtin|12", "16|resting|RESTING|builtin|11");
        assertThat(rows(db, "SELECT image_id, tag_id FROM image_tag ORDER BY image_id, tag_id"))
                .containsExactly("1|15", "2|15", "2|16");
        assertThat(rows(db, "SELECT item_id, tag_id FROM import_item_tag")).containsExactly("1|16");
    }

    @Test
    void aTagRenamedAwayFromRestingGivesUpTheKeyAndANameAlreadyTakenIsNumbered() {
        // "resting" was made, then renamed "perched"; the built-in DSF was renamed "basking".
        version17Library("UPDATE tag SET label = 'basking' WHERE key = 'dsf'",
                "INSERT INTO tag (id, key, label, source, sort_order) VALUES (15, 'resting', 'perched', 'user', 1000)",
                "INSERT INTO image_tag (image_id, tag_id) VALUES (1, 15)");

        Database db = migrate();

        assertThat(rows(db, TAGS + " LIMIT -1 OFFSET 14")).containsExactly(
                "15|resting-15|perched|user|1000", "16|resting|resting|builtin|11", "17|basking|basking_2|builtin|12");
        assertThat(rows(db, "SELECT image_id, tag_id FROM image_tag")).containsExactly("1|15");
        assertThat(rows(db, "SELECT lower(label), count(*) FROM tag GROUP BY lower(label) HAVING count(*) > 1")).isEmpty();
    }

    @Test
    void everyTagHasAnEmptyLastUsedTime() {
        version17Library("INSERT INTO tag (id, key, label, source, sort_order) VALUES (15, 'roosting', 'roosting', 'user', 1000)");

        Database db = migrate();

        assertThat(rows(db, "SELECT count(*), count(last_used_at) FROM tag")).containsExactly("17|0");
    }
}
