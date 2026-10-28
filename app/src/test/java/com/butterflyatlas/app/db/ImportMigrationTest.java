package com.butterflyatlas.app.db;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** V4 rebuilds {@code image} (its CHECK gains 'import') on a library that already holds photos: nothing may be lost. */
class ImportMigrationTest {

    @TempDir
    Path dir;

    /** {table, columns ("*" for all), order}: each row is compared as quote() of every column, so a value that changed storage class (1 to '1') is caught. */
    private static final String[][] TABLES = {
            {"image", "*", "id"},
            {"image_tag", "*", "image_id, tag_id"},
            {"image_meta", "*", "image_id"},
            {"image_processing", "*", "image_id, consumer"},
            {"taxon", "id, next_photo_number, cover_image_id", "id"},
            {"change_log", "*", "id"},
            {"tag", "*", "id"},
            {"sqlite_sequence", "*", "name"}};

    private static final String SCHEMA = "SELECT type, name, tbl_name, sql FROM sqlite_master WHERE tbl_name IN ('image',"
            + " 'image_tag', 'image_meta', 'image_processing') ORDER BY name";

    /** A version 3 library (as 3b-1 left it) with live, deleted and purged photos, tags, a cover, snapshots and ledger rows. */
    private SQLiteDataSource version3Library() throws SQLException {
        SQLiteDataSource v3 = emptyVersion3Library();
        try (Connection c = v3.getConnection(); Statement st = c.createStatement()) {
            st.executeUpdate("INSERT INTO taxon (id, parent_id, rank, name, common_name, group_key, source, ancestor_path,"
                    + " folder_name, sort_key) VALUES ('A5', NULL, 'species', 'Danaus chrysippus', 'Plain Tiger', 'butterflies',"
                    + " 'official', '/A5/', 'Danaus_chrysippus_[A5]', 'danaus chrysippus'),"
                    + " ('A6', NULL, 'species', 'Danaus genutia', 'Striped Tiger', 'butterflies', 'official', '/A6/',"
                    + " 'Danaus_genutia_[A6]', 'danaus genutia')");
            for (int n = 1; n <= 5; n++) {
                st.executeUpdate("INSERT INTO image (taxon_id, rel_path, file_name, number, original_name, file_size, modified_at,"
                        + " sha256, width, height, orientation, added_at, source) VALUES ('" + (n % 2 == 0 ? "A6" : "A5") + "',"
                        + " 'images/x/p_" + n + ".jpg', 'p_" + n + ".jpg', " + n + ", 'IMG_" + n + ".JPG', " + (1000 + n) + ","
                        + " '2026-10-0" + n + "T08:00:00Z', 'sha" + n + "', 400, 300, 1, '2026-10-0" + n + "T09:00:00Z', '"
                        + (n == 3 ? "rescan" : "app") + "')");
            }
            st.executeUpdate("UPDATE image SET deleted_at = '2026-10-10T00:00:00Z', deleted_rel_path = '.deleted/4_p_4.jpg'"
                    + " WHERE id = 4");
            st.executeUpdate("DELETE FROM image WHERE id = 5"); // purged: id 5 must never come back
            st.executeUpdate("INSERT INTO image_tag (image_id, tag_id) SELECT 1, id FROM tag WHERE key IN ('male', 'larva')");
            st.executeUpdate("INSERT INTO image_tag (image_id, tag_id) SELECT 4, id FROM tag WHERE key = 'female'");
            st.executeUpdate("INSERT INTO tag (key, label, source, sort_order) VALUES ('mud', 'Mud-puddling', 'user', 1000)");
            st.executeUpdate("INSERT INTO image_tag (image_id, tag_id) SELECT 2, id FROM tag WHERE key = 'mud'");
            st.executeUpdate("UPDATE taxon SET cover_image_id = 3, next_photo_number = 6 WHERE id = 'A5'");
            for (int id = 1; id <= 4; id++) {
                st.executeUpdate("INSERT INTO image_meta (image_id, json, read_at) VALUES (" + id + ", '{\"v\":1,\"format\":\"JPEG\","
                        + "\"orientation\":1}', '2026-10-11T00:00:00Z')");
                st.executeUpdate("INSERT INTO image_processing (image_id, consumer, version, status, error, done_at) VALUES ("
                        + id + ", 'dimensions', 1, 'done', NULL, '2026-10-11T00:00:00Z')");
            }
            st.executeUpdate("INSERT INTO image_processing (image_id, consumer, version, status, error, done_at) VALUES (2,"
                    + " 'thumbnail', 1, 'failed', 'java.io.IOException: broken', '2026-10-11T00:00:00Z')");
            st.executeUpdate("INSERT INTO change_log (at, kind, payload_json) VALUES ('2026-10-11T00:00:00Z', 'photo.purge',"
                    + " '{\"id\":5}')");
        }
        return v3;
    }

    /** The library's file, foreign keys on, as Database opens it (but without migrating). */
    private SQLiteDataSource library() {
        SQLiteConfig config = new SQLiteConfig();
        config.enforceForeignKeys(true);
        SQLiteDataSource ds = new SQLiteDataSource(config);
        ds.setUrl("jdbc:sqlite:" + dir.resolve(Database.FILE_NAME).toAbsolutePath());
        return ds;
    }

    /** A version 3 library with no photos and no taxa. */
    private SQLiteDataSource emptyVersion3Library() {
        SQLiteDataSource v3 = library();
        Flyway.configure().dataSource(v3).locations("classpath:db/migration").target("3").load().migrate();
        return v3;
    }

    private static void insertSpeciesA5(SQLiteDataSource ds) throws SQLException {
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.executeUpdate("INSERT INTO taxon (id, parent_id, rank, name, common_name, group_key, source, ancestor_path,"
                    + " folder_name, sort_key) VALUES ('A5', NULL, 'species', 'Danaus chrysippus', 'Plain Tiger', 'butterflies',"
                    + " 'official', '/A5/', 'Danaus_chrysippus_[A5]', 'danaus chrysippus')");
        }
    }

    /** Files a new photo of A5 and returns the id it was given. */
    private static long insertPhoto(Connection c, String name, String source) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.executeUpdate("INSERT INTO image (taxon_id, rel_path, file_name, file_size, modified_at, sha256, added_at, source)"
                    + " VALUES ('A5', 'images/x/" + name + "', '" + name + "', 1, 't', 'sha-" + name + "', 't', '" + source + "')");
            try (ResultSet rs = st.executeQuery("SELECT last_insert_rowid()")) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private static List<String> dump(Connection c, String sql) throws SQLException {
        List<String> rows = new ArrayList<>();
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            ResultSetMetaData meta = rs.getMetaData();
            while (rs.next()) {
                StringBuilder row = new StringBuilder();
                for (int i = 1; i <= meta.getColumnCount(); i++) {
                    row.append(meta.getColumnName(i)).append('=').append(rs.getString(i)).append(i < meta.getColumnCount() ? "|" : "");
                }
                rows.add(row.toString());
            }
        }
        return rows;
    }

    private static List<String> rows(Database db, String sql) {
        return db.read(c -> dump(c, sql));
    }

    /** Every row of the table as {@code col=quote(col)|...}, so storage classes are compared as well as values. */
    private static List<String> quotedDump(Connection c, String[] table) throws SQLException {
        List<String> columns = new ArrayList<>();
        if (table[1].equals("*")) {
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("PRAGMA table_info(" + table[0] + ")")) {
                while (rs.next()) {
                    columns.add(rs.getString("name"));
                }
            }
        } else {
            for (String column : table[1].split(",")) {
                columns.add(column.trim());
            }
        }
        StringBuilder sql = new StringBuilder("SELECT ");
        for (int i = 0; i < columns.size(); i++) {
            sql.append(i > 0 ? ", " : "").append("quote(\"").append(columns.get(i)).append("\") AS \"").append(columns.get(i))
                    .append('"');
        }
        sql.append(" FROM ").append(table[0]).append(" ORDER BY ").append(table[2]);
        return dump(c, sql.toString());
    }

    /** The quoted rows of each of {@link #TABLES}, then the schema text of image and the tables that cascade from it. */
    private static List<List<String>> dumpAll(Connection c) throws SQLException {
        List<List<String>> all = new ArrayList<>();
        for (String[] table : TABLES) {
            all.add(quotedDump(c, table));
        }
        all.add(dump(c, SCHEMA));
        return all;
    }

    @Test
    void version4KeepsEveryPhotoTagCoverSnapshotLedgerRowAndIndex() throws Exception {
        SQLiteDataSource v3 = version3Library();
        List<List<String>> before;
        try (Connection c = v3.getConnection()) {
            before = dumpAll(c);
        }
        assertThat(before.get(0)).hasSize(4);
        assertThat(before.get(1)).hasSize(4);

        Database db = Database.open(dir.resolve(Database.FILE_NAME), "4");

        List<List<String>> after = db.read(ImportMigrationTest::dumpAll);
        for (int i = 0; i < TABLES.length; i++) {
            assertThat(after.get(i)).as(TABLES[i][0]).isEqualTo(before.get(i));
        }
        // The indexes and the other tables' definitions are the same; only image's CHECK changed.
        List<String> schemaBefore = before.get(TABLES.length);
        List<String> schemaAfter = after.get(TABLES.length);
        assertThat(schemaAfter).hasSameSizeAs(schemaBefore);
        for (int i = 0; i < schemaBefore.size(); i++) {
            if (schemaBefore.get(i).startsWith("type=table|name=image|")) {
                assertThat(schemaAfter.get(i)).isEqualTo(schemaBefore.get(i)
                        .replace("CHECK (source IN ('app', 'rescan'))", "CHECK (source IN ('app', 'rescan', 'import'))"));
            } else {
                assertThat(schemaAfter.get(i)).isEqualTo(schemaBefore.get(i));
            }
        }
        assertThat(rows(db, "SELECT version, success FROM flyway_schema_history ORDER BY installed_rank"))
                .containsExactly("version=1|success=1", "version=2|success=1", "version=3|success=1", "version=4|success=1");
        assertThat(rows(db, "SELECT name FROM sqlite_temp_master")).isEmpty();
    }

    @Test
    void afterVersion4ForeignKeysAreOnAndStillCascadeAndPurgedIdsStayUsed() throws Exception {
        version3Library();

        Database db = Database.open(dir.resolve(Database.FILE_NAME));

        assertThat(rows(db, "PRAGMA foreign_keys")).containsExactly("foreign_keys=1");
        assertThat(rows(db, "PRAGMA foreign_key_check")).isEmpty();
        long id = db.write(c -> {
            try (Statement st = c.createStatement()) {
                st.executeUpdate("INSERT INTO image (taxon_id, rel_path, file_name, file_size, modified_at, sha256, added_at,"
                        + " source) VALUES ('A5', 'images/x/new.jpg', 'new.jpg', 1, 't', 'shaN', 't', 'import')");
                try (ResultSet rs = st.executeQuery("SELECT last_insert_rowid()")) {
                    rs.next();
                    return rs.getLong(1);
                }
            }
        });
        assertThat(id).as("the purged photo's id 5 is not reused").isEqualTo(6);
        db.write(c -> {
            try (Statement st = c.createStatement()) {
                st.executeUpdate("DELETE FROM image WHERE id = 1");
            }
            return null;
        });
        assertThat(rows(db, "SELECT COUNT(*) AS n FROM image_tag WHERE image_id = 1")).containsExactly("n=0");
        assertThat(rows(db, "SELECT COUNT(*) AS n FROM image_meta WHERE image_id = 1")).containsExactly("n=0");
        assertThat(rows(db, "SELECT COUNT(*) AS n FROM image_processing WHERE image_id = 1")).containsExactly("n=0");
        assertThatThrownBy(() -> db.write(c -> {
            try (Statement st = c.createStatement()) {
                st.executeUpdate("INSERT INTO image_tag (image_id, tag_id) VALUES (404, 1)");
            }
            return null;
        })).isInstanceOf(DatabaseException.class);
        assertThatThrownBy(() -> db.write(c -> {
            try (Statement st = c.createStatement()) {
                st.executeUpdate("INSERT INTO image (taxon_id, rel_path, file_name, file_size, modified_at, sha256, added_at,"
                        + " source) VALUES ('A5', 'images/x/odd.jpg', 'odd.jpg', 1, 't', 'shaO', 't', 'upload')");
            }
            return null;
        })).isInstanceOf(DatabaseException.class);
    }

    @Test
    void aNewLibraryGetsTheImportTables() {
        Database db = Database.open(dir.resolve(Database.FILE_NAME));

        assertThat(rows(db, "SELECT name FROM sqlite_master WHERE type = 'table' AND name LIKE 'import%'"
                + " ORDER BY name")).containsExactly("name=import_item", "name=import_item_tag", "name=import_run");
        assertThat(rows(db, "SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = 'import_item'"
                + " AND sql IS NOT NULL ORDER BY name")).containsExactly("name=import_item_copy", "name=import_item_image",
                "name=import_item_pair", "name=import_item_seq", "name=import_item_sha", "name=import_item_state");
    }

    @Test
    void discardingAnImportRemovesItsItemsAndTagsButNeverItsPhotos() throws Exception {
        version3Library();
        Database db = Database.open(dir.resolve(Database.FILE_NAME));

        db.write(c -> {
            try (Statement st = c.createStatement()) {
                st.executeUpdate("INSERT INTO import_run (id, root_path, state, created_at, updated_at) VALUES (1, '/p', 'open',"
                        + " 't', 't')");
                st.executeUpdate("INSERT INTO import_item (id, run_id, rel_path, file_size, modified_at, read_state, seq,"
                        + " image_id, outcome) VALUES (1, 1, 'a.jpg', 1, 't', 'read', 1, 1, 'filed')");
                st.executeUpdate("INSERT INTO import_item (id, run_id, rel_path, file_size, modified_at, read_state, seq,"
                        + " copy_of_item_id) VALUES (2, 1, 'b.jpg', 1, 't', 'read', 2, 1)");
                st.executeUpdate("INSERT INTO import_item_tag (item_id, tag_id) SELECT 2, id FROM tag WHERE key = 'male'");
                st.executeUpdate("DELETE FROM import_run WHERE id = 1");
            }
            return null;
        });

        assertThat(rows(db, "SELECT (SELECT COUNT(*) FROM import_item) || '/' || (SELECT COUNT(*) FROM"
                + " import_item_tag) || '/' || (SELECT COUNT(*) FROM image) AS n")).containsExactly("n=0/0/4");
    }

    @Test
    void aVersion3LibraryThatNeverHadPhotosGivesTheFirstPhotoIdOne() throws Exception {
        SQLiteDataSource v3 = emptyVersion3Library();
        insertSpeciesA5(v3);
        try (Connection c = v3.getConnection()) {
            assertThat(dump(c, "SELECT seq FROM sqlite_sequence WHERE name = 'image'")).isEmpty();
        }

        Database db = Database.open(dir.resolve(Database.FILE_NAME));
        // The rebuild's INSERT ... SELECT of no rows leaves a harmless 'image' row at 0 (see V4__import.sql).
        assertThat(rows(db, "SELECT seq FROM sqlite_sequence WHERE name = 'image'")).containsExactly("seq=0");

        long id = db.write(c -> insertPhoto(c, "first.jpg", "import"));
        assertThat(id).isEqualTo(1);
    }

    @Test
    void aVersion3LibraryWhosePhotosWereAllPurgedNeverReusesTheirIds() throws Exception {
        SQLiteDataSource v3 = emptyVersion3Library();
        insertSpeciesA5(v3);
        try (Connection c = v3.getConnection()) {
            for (int n = 1; n <= 3; n++) {
                insertPhoto(c, "p_" + n + ".jpg", "app");
            }
            try (Statement st = c.createStatement()) {
                st.executeUpdate("DELETE FROM image");
            }
            assertThat(dump(c, "SELECT seq FROM sqlite_sequence WHERE name = 'image'")).containsExactly("seq=3");
        }

        Database db = Database.open(dir.resolve(Database.FILE_NAME));

        long id = db.write(c -> insertPhoto(c, "after.jpg", "import"));
        assertThat(id).isEqualTo(4);
    }

    @Test
    void aVersion4ThatFailsPartWayLeavesTheVersion3LibraryUntouchedAndTheRealOneAppliesNextStart() throws Exception {
        SQLiteDataSource v3 = version3Library();
        List<List<String>> before;
        try (Connection c = v3.getConnection()) {
            before = dumpAll(c);
        }
        // A migration folder holding the real V1 to V3 and a V4 that does all the real work, then fails on its last statement.
        Path failing = Files.createDirectories(dir.resolve("failing-migrations"));
        for (String name : new String[] {"V1__core_schema.sql", "V2__photos.sql", "V3__ingest.sql"}) {
            Files.writeString(failing.resolve(name), resource(name));
        }
        Files.writeString(failing.resolve("V4__import.sql"), resource("V4__import.sql")
                + "\nINSERT INTO no_such_table (x) VALUES (1);\n");

        assertThatThrownBy(() -> Flyway.configure().dataSource(library())
                .locations("filesystem:" + failing.toAbsolutePath()).load().migrate()).isInstanceOf(FlywayException.class)
                .hasMessageContaining("no_such_table");

        try (Connection c = v3.getConnection()) {
            List<List<String>> afterFailure = dumpAll(c);
            assertThat(afterFailure).isEqualTo(before);
            assertThat(dump(c, "SELECT version, success FROM flyway_schema_history ORDER BY installed_rank"))
                    .containsExactly("version=1|success=1", "version=2|success=1", "version=3|success=1");
            assertThat(dump(c, "SELECT name FROM sqlite_master WHERE name LIKE 'import%' OR name LIKE 'v4_%'")).isEmpty();
        }

        Database db = Database.open(dir.resolve(Database.FILE_NAME), "4");

        assertThat(rows(db, "SELECT version, success FROM flyway_schema_history ORDER BY installed_rank"))
                .containsExactly("version=1|success=1", "version=2|success=1", "version=3|success=1", "version=4|success=1");
        List<List<String>> after = db.read(ImportMigrationTest::dumpAll);
        assertThat(after.subList(0, TABLES.length)).isEqualTo(before.subList(0, TABLES.length));
        long id = db.write(c -> insertPhoto(c, "new.jpg", "import"));
        assertThat(id).isEqualTo(6);
    }

    private static String resource(String name) throws IOException {
        try (InputStream in = ImportMigrationTest.class.getResourceAsStream("/db/migration/" + name)) {
            assertThat(in).as(name).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
