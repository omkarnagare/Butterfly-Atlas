package com.butterflyatlas.app.db;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V15 (tag-names design §3): every tag's name in its one form; tags that become the same name merged into the built-in
 * one or the oldest, their photos and import items keeping the tag; two built-ins numbered apart; what the owner should
 * know saved in {@code tag_name_note}.
 */
class TagNamesMigrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path dir;

    /** A version 14 library with the built-ins (ids 1-10) and then each statement run. */
    private void version14Library(String... statements) throws SQLException {
        SQLiteConfig config = new SQLiteConfig();
        config.enforceForeignKeys(true);
        SQLiteDataSource v14 = new SQLiteDataSource(config);
        v14.setUrl("jdbc:sqlite:" + dir.resolve(Database.FILE_NAME).toAbsolutePath());
        Flyway.configure().dataSource(v14).locations("classpath:db/migration").target("14").load().migrate();
        try (Connection c = v14.getConnection(); Statement st = c.createStatement()) {
            st.executeUpdate("INSERT INTO taxon (id, parent_id, rank, name, common_name, group_key, source, ancestor_path,"
                    + " folder_name, sort_key, family_id, next_photo_number) VALUES"
                    + " ('G:butterflies', NULL, 'group', 'Butterflies', NULL, 'butterflies', 'system', '/G:butterflies/',"
                    + " 'Butterflies', 'butterflies', NULL, 1),"
                    + " ('A1', 'G:butterflies', 'family', 'Nymphalidae', 'Brush-footed', 'butterflies', 'official',"
                    + " '/G:butterflies/A1/', 'Nymphalidae', 'nymphalidae', NULL, 1),"
                    + " ('A5', 'A1', 'species', 'Danaus chrysippus', 'Plain Tiger', 'butterflies', 'official',"
                    + " '/G:butterflies/A1/A5/', 'Danaus_chrysippus_[A5]', 'danaus chrysippus', NULL, 9)");
            for (int n = 1; n <= 4; n++) {
                st.executeUpdate("INSERT INTO image (taxon_id, rel_path, file_name, number, original_name, file_size,"
                        + " modified_at, sha256, width, height, orientation, added_at, source, file_type, deleted_at)"
                        + " VALUES ('A5', 'images/x/p" + n + ".jpg', 'p" + n + ".jpg', " + n + ", NULL, 1000,"
                        + " '2026-10-01T08:00:00Z', 'sha" + n + "', 400, 300, 1, '2026-10-01T09:00:00Z', 'app', 'jpeg', "
                        + (n == 4 ? "'2026-10-02T08:00:00Z'" : "NULL") + ")");
            }
            st.executeUpdate("INSERT INTO import_run (root_path, state, created_at, updated_at, next_seq) VALUES"
                    + " ('/cards/open', 'open', '2026-10-20T08:00:00Z', '2026-10-20T08:00:00Z', 10)");
            st.executeUpdate("INSERT INTO import_item (run_id, rel_path, file_size, modified_at, read_state, seq) VALUES"
                    + " (1, 'a.jpg', 10, '2026-10-20T07:00:00Z', 'read', 3),"
                    + " (1, 'b.jpg', 11, '2026-10-20T07:00:00Z', 'read', 4)");
            for (String statement : statements) {
                st.executeUpdate(statement);
            }
        }
    }

    private Database migrate() {
        return Database.open(dir.resolve(Database.FILE_NAME));
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

    @Test
    void aNewLibraryHasTheBuiltInsInTheirFormAndNoNote() throws Exception {
        version14Library();

        Database db = migrate();

        assertThat(rows(db, "SELECT id, key, label, source FROM tag ORDER BY id")).containsExactly(
                "1|egg|egg|builtin", "2|larva|larva|builtin", "3|pupa|pupa|builtin",
                "4|upper_wing|upper_wing|builtin", "5|under_wing|under_wing|builtin", "6|male|male|builtin",
                "7|female|female|builtin", "8|dsf|DSF|builtin", "9|wsf|WSF|builtin",
                "10|unconfirmed|unconfirmed|builtin");
        assertThat(rows(db, "SELECT count(*) FROM tag_name_note")).containsExactly("0");
    }

    @Test
    void namesTakeTheirFormAndLookAlikesAreMerged() throws Exception {
        version14Library(
                "UPDATE tag SET label = 'Dry season' WHERE key = 'dsf'",
                "UPDATE tag SET label = 'Under-wing' WHERE key = 'wsf'",
                "INSERT INTO tag (id, key, label, source, sort_order) VALUES"
                        + " (11, 'mud_puddling', 'Mud puddling', 'user', 1000),"
                        + " (12, 'mud_puddling-2', 'mud-puddling', 'user', 1000),"
                        + " (13, 'upper_wing-2', 'Upper-Wing!', 'user', 1000),"
                        + " (14, 'mud_puddling_on_wet_sand', 'Mud puddling on wet sand', 'user', 1000),"
                        + " (15, 'uv_light', 'UV  light', 'user', 1000)",
                // photo 1: the newer look-alike only; photo 2: both; photo 3: the user's upper wing; photo 4 deleted
                "INSERT INTO image_tag (image_id, tag_id) VALUES (1, 12), (2, 11), (2, 12), (3, 13), (3, 9), (4, 12)",
                "INSERT INTO import_item_tag (item_id, tag_id) VALUES (1, 12), (2, 11), (2, 13)");

        Database db = migrate();

        assertThat(rows(db, "SELECT id, key, label, source FROM tag ORDER BY id")).containsExactly(
                "1|egg|egg|builtin", "2|larva|larva|builtin", "3|pupa|pupa|builtin",
                "4|upper_wing|upper_wing|builtin", "5|under_wing|under_wing|builtin", "6|male|male|builtin",
                "7|female|female|builtin", "8|dsf|dry_season|builtin", "9|wsf|under_wing_2|builtin",
                "10|unconfirmed|unconfirmed|builtin", "11|mud_puddling|mud_puddling|user",
                "14|mud_puddling_on_wet_sand|mud_puddling_on_wet_sand|user", "15|uv_light|UV_light|user");
        assertThat(rows(db, "SELECT image_id, tag_id FROM image_tag ORDER BY image_id, tag_id"))
                .containsExactly("1|11", "2|11", "3|4", "3|9", "4|11");
        assertThat(rows(db, "SELECT item_id, tag_id FROM import_item_tag ORDER BY item_id, tag_id"))
                .containsExactly("1|11", "2|4", "2|11");

        JsonNode note = JSON.readTree(rows(db, "SELECT payload_json FROM tag_name_note WHERE id = 1").get(0));
        assertThat(note.get("merged").toString()).isEqualTo("[{\"from\":\"mud-puddling\",\"into\":\"mud_puddling\",\"photos\":2},"
                + "{\"from\":\"Upper-Wing!\",\"into\":\"upper_wing\",\"photos\":1}]");
        assertThat(note.get("numbered").toString()).isEqualTo("[{\"from\":\"Under-wing\",\"to\":\"under_wing_2\"}]");
        assertThat(rows(db, "SELECT dismissed_at IS NULL FROM tag_name_note")).containsExactly("1");
    }

    @Test
    void numberingSkipsANameAlreadyTaken() throws Exception {
        version14Library(
                "UPDATE tag SET label = 'upper-wing' WHERE key = 'dsf'",
                "UPDATE tag SET label = 'Upper_Wing' WHERE key = 'wsf'",
                "INSERT INTO tag (id, key, label, source, sort_order) VALUES (11, 'upper_wing_2', 'upper wing 2', 'user', 1000)");

        Database db = migrate();

        assertThat(rows(db, "SELECT key, label FROM tag WHERE id IN (4, 8, 9, 11) ORDER BY id")).containsExactly(
                "upper_wing|upper_wing", "dsf|upper_wing_3", "wsf|upper_wing_4", "upper_wing_2|upper_wing_2");
    }

    @Test
    void renamesAloneLeaveNoNote() throws Exception {
        version14Library("INSERT INTO tag (id, key, label, source, sort_order) VALUES (11, 'basking', 'Basking  spot', 'user', 1000)");

        Database db = migrate();

        assertThat(rows(db, "SELECT label FROM tag WHERE id = 11")).containsExactly("basking_spot");
        assertThat(rows(db, "SELECT count(*) FROM tag_name_note")).containsExactly("0");
    }
}
