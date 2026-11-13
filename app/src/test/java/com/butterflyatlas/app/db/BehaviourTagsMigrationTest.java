package com.butterflyatlas.app.db;

import com.butterflyatlas.app.library.Library;
import com.butterflyatlas.app.photos.TagService;
import com.butterflyatlas.app.photos.TagService.Tag;
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
 * V17 (behaviour tags design §2): the built-in tags mating, nectaring, puddling and predated after WSF, Unconfirmed
 * moved to 14. Each is added as V8 added Unconfirmed: a user tag holding the key under another name gives it up, a user
 * tag of the same name becomes the built-in in place, otherwise a new row (named {@code <key>_2} when the name is taken).
 */
class BehaviourTagsMigrationTest {

    private static final String TAGS = "SELECT id, key, label, source, sort_order FROM tag ORDER BY id";

    @TempDir
    Path dir;

    private Path file() {
        return dir.resolve(Database.FILE_NAME);
    }

    /**
     * A version 16 library with the fixture taxa and the built-ins (ids 1-10), photos 1-3 of A5 (3 in Recently deleted),
     * an open import with items 1 and 2; then each statement run.
     */
    private void version16Library(String... statements) {
        Database v16 = Database.open(file(), "16");
        BundleLoader.load(v16, TestBundles.fixture().newest(), TestBundles.CLOCK);
        for (int n = 1; n <= 3; n++) {
            TestBundles.addImage(v16, "A5");
        }
        TestBundles.markDeleted(v16, 3);
        v16.write(c -> {
            try (Statement st = c.createStatement()) {
                st.executeUpdate("INSERT INTO import_run (root_path, state, created_at, updated_at, next_seq) VALUES"
                        + " ('/cards/open', 'open', '2026-10-20T08:00:00Z', '2026-10-20T08:00:00Z', 10)");
                st.executeUpdate("INSERT INTO import_item (run_id, rel_path, file_size, modified_at, read_state, seq) VALUES"
                        + " (1, 'a.jpg', 10, '2026-10-20T07:00:00Z', 'read', 3),"
                        + " (1, 'b.jpg', 11, '2026-10-20T07:00:00Z', 'read', 4)");
                for (String statement : statements) {
                    st.executeUpdate(statement);
                }
            }
            return null;
        });
        assertThat(rows(v16, "SELECT id, key FROM tag WHERE source = 'builtin' ORDER BY id")).containsExactly(
                "1|egg", "2|larva", "3|pupa", "4|upper_wing", "5|under_wing", "6|male", "7|female", "8|dsf", "9|wsf",
                "10|unconfirmed");
    }

    private Database migrate() {
        Database db = Database.open(file(), "17"); // V18 orders the built-ins again (RestingBaskingMigrationTest)
        assertThat(Database.newestSchemaVersion(file())).hasValue(17);
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

    /** The tags in the order every list shows them (TagService.ORDER). */
    private static List<String> ordered(Database db) {
        return rows(db, "SELECT t.key FROM tag t ORDER BY " + TagService.ORDER);
    }

    private static void namesUniqueWithoutCase(Database db) {
        assertThat(rows(db, "SELECT lower(label), count(*) FROM tag GROUP BY lower(label) HAVING count(*) > 1"))
                .as("names unique without case").isEmpty();
    }

    @Test
    void aLibraryWithoutTheseTagsGainsFourBuiltInsAfterWsfAndUnconfirmedMovesLast() {
        version16Library("INSERT INTO tag (id, key, label, source, sort_order) VALUES (11, 'basking', 'basking', 'user', 1000)",
                "INSERT INTO image_tag (image_id, tag_id) VALUES (1, 11), (1, 10)");

        Database db = migrate();

        assertThat(rows(db, TAGS)).containsExactly(
                "1|egg|egg|builtin|1", "2|larva|larva|builtin|2", "3|pupa|pupa|builtin|3",
                "4|upper_wing|upper_wing|builtin|4", "5|under_wing|under_wing|builtin|5", "6|male|male|builtin|6",
                "7|female|female|builtin|7", "8|dsf|DSF|builtin|8", "9|wsf|WSF|builtin|9",
                "10|unconfirmed|unconfirmed|builtin|14", "11|basking|basking|user|1000",
                "12|mating|mating|builtin|10", "13|nectaring|nectaring|builtin|11", "14|puddling|puddling|builtin|12",
                "15|predated|predated|builtin|13");
        assertThat(ordered(db)).containsExactly("egg", "larva", "pupa", "upper_wing", "under_wing", "male", "female",
                "dsf", "wsf", "mating", "nectaring", "puddling", "predated", "unconfirmed", "basking");
        assertThat(rows(db, "SELECT image_id, tag_id FROM image_tag ORDER BY image_id, tag_id"))
                .containsExactly("1|10", "1|11");
    }

    @Test
    void aUserTagCalledMatingBecomesTheBuiltInAndKeepsItsPhotosAndImportItems() {
        version16Library("INSERT INTO tag (id, key, label, source, sort_order) VALUES (11, 'mating', 'Mating', 'user', 1000),"
                        + " (12, 'basking', 'basking', 'user', 1000)",
                "INSERT INTO image_tag (image_id, tag_id) VALUES (1, 11), (2, 11), (3, 11), (2, 12)",
                "INSERT INTO import_item_tag (item_id, tag_id) VALUES (1, 11), (2, 11), (2, 12)");

        Database db = migrate();

        assertThat(rows(db, TAGS + " LIMIT -1 OFFSET 9")).as("same row, name kept, nothing added for mating").containsExactly(
                "10|unconfirmed|unconfirmed|builtin|14", "11|mating|Mating|builtin|10", "12|basking|basking|user|1000",
                "13|nectaring|nectaring|builtin|11", "14|puddling|puddling|builtin|12", "15|predated|predated|builtin|13");
        assertThat(rows(db, "SELECT image_id, tag_id FROM image_tag ORDER BY image_id, tag_id"))
                .containsExactly("1|11", "2|11", "2|12", "3|11");
        assertThat(rows(db, "SELECT item_id, tag_id FROM import_item_tag ORDER BY item_id, tag_id"))
                .containsExactly("1|11", "2|11", "2|12");
        assertThat(ordered(db)).containsSubsequence("wsf", "mating", "nectaring", "puddling", "predated", "unconfirmed", "basking");
        namesUniqueWithoutCase(db);
    }

    @Test
    void aTagRenamedAwayFromNectaringGivesUpTheKeyAndTheBuiltInIsAdded() {
        // "nectaring" was made (key nectaring), renamed "flower_visit"; "Odd" already holds the key the first would move to.
        version16Library("INSERT INTO tag (id, key, label, source, sort_order) VALUES (11, 'nectaring', 'flower_visit', 'user', 1000),"
                        + " (12, 'nectaring-11', 'Odd', 'user', 1000)",
                "INSERT INTO image_tag (image_id, tag_id) VALUES (1, 11)",
                "INSERT INTO import_item_tag (item_id, tag_id) VALUES (1, 11)");

        Database db = migrate();

        assertThat(rows(db, TAGS + " LIMIT -1 OFFSET 10")).containsExactly(
                "11|nectaring-11-v17|flower_visit|user|1000", "12|nectaring-11|Odd|user|1000",
                "13|mating|mating|builtin|10", "14|nectaring|nectaring|builtin|11", "15|puddling|puddling|builtin|12",
                "16|predated|predated|builtin|13");
        assertThat(rows(db, "SELECT image_id, tag_id FROM image_tag")).containsExactly("1|11");
        assertThat(rows(db, "SELECT item_id, tag_id FROM import_item_tag")).containsExactly("1|11");
    }

    @Test
    void aRenamedTagGivesUpTheKeyAndTheTagNowCalledPuddlingBecomesTheBuiltIn() {
        // "puddling" was made (key puddling), renamed "wet_sand", and "PUDDLING" made again (key puddling-2).
        version16Library("INSERT INTO tag (id, key, label, source, sort_order) VALUES (11, 'puddling', 'wet_sand', 'user', 1000),"
                        + " (12, 'puddling-2', 'PUDDLING', 'user', 1000)",
                "INSERT INTO image_tag (image_id, tag_id) VALUES (1, 11), (2, 12)");

        Database db = migrate();

        assertThat(rows(db, TAGS + " LIMIT -1 OFFSET 10")).containsExactly(
                "11|puddling-11|wet_sand|user|1000", "12|puddling|PUDDLING|builtin|12",
                "13|mating|mating|builtin|10", "14|nectaring|nectaring|builtin|11", "15|predated|predated|builtin|13");
        assertThat(rows(db, "SELECT image_id, tag_id FROM image_tag ORDER BY image_id")).containsExactly("1|11", "2|12");
        namesUniqueWithoutCase(db);
    }

    @Test
    void aBuiltInRenamedPredatedKeepsItsNameAndTheNewBuiltInIsNumbered() {
        version16Library("UPDATE tag SET label = 'predated' WHERE key = 'dsf'",
                "INSERT INTO image_tag (image_id, tag_id) VALUES (1, 8)");

        Database db = migrate();

        assertThat(rows(db, "SELECT id, key, label, source, sort_order FROM tag WHERE key IN ('dsf', 'predated') ORDER BY id"))
                .containsExactly("8|dsf|predated|builtin|8", "14|predated|predated_2|builtin|13");
        assertThat(rows(db, "SELECT image_id, tag_id FROM image_tag")).containsExactly("1|8");
        namesUniqueWithoutCase(db);
    }

    @Test
    void aRenamedUnconfirmedStillMovesLastAndANameItTookIsNumbered() {
        version16Library("UPDATE tag SET label = 'Mating' WHERE key = 'unconfirmed'");

        Database db = migrate();

        assertThat(rows(db, "SELECT id, key, label, source, sort_order FROM tag WHERE key IN ('unconfirmed', 'mating') ORDER BY id"))
                .containsExactly("10|unconfirmed|Mating|builtin|14", "11|mating|mating_2|builtin|10");
        assertThat(ordered(db)).endsWith("wsf", "mating", "nectaring", "puddling", "predated", "unconfirmed");
        namesUniqueWithoutCase(db);
    }

    @Test
    void tagServiceListsTheBuiltInsInOrderThenTheUsersTags() {
        version16Library("INSERT INTO tag (key, label, source, sort_order) VALUES ('zebra', 'zebra', 'user', 1000),"
                + " ('nectaring', 'Nectaring', 'user', 1000), ('Apple', 'Apple', 'user', 1000)");

        Library library = TestBundles.fixtureLibrary(dir);

        List<Tag> list = new TagService(() -> library).list();
        assertThat(list).extracting(Tag::key).containsExactly("egg", "larva", "pupa", "upper_wing", "under_wing", "male",
                "female", "dsf", "wsf", "mating", "resting", "basking", "nectaring", "puddling", "predated", "unconfirmed", "Apple",
                "zebra"); // as V18 leaves them
        assertThat(list).extracting(Tag::label).containsExactly("egg", "larva", "pupa", "upper_wing", "under_wing", "male",
                "female", "DSF", "WSF", "mating", "resting", "basking", "Nectaring", "puddling", "predated", "unconfirmed",
                "Apple", "zebra");
        assertThat(list).extracting(Tag::source).containsExactly("builtin", "builtin", "builtin", "builtin", "builtin",
                "builtin", "builtin", "builtin", "builtin", "builtin", "builtin", "builtin", "builtin", "builtin", "builtin",
                "builtin", "user", "user");
    }
}
