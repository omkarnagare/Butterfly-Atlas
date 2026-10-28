package com.butterflyatlas.app.db;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** V5 only adds tables and columns: a populated version 4 library keeps every row and id. */
class PlaceMigrationTest {

    @TempDir
    Path dir;

    /** Every table a version 4 library holds data in, with the order its rows are compared in. */
    private static final Map<String, String> TABLES = new LinkedHashMap<>();

    static {
        TABLES.put("taxon", "id");
        TABLES.put("taxon_synonym", "taxon_id, name");
        TABLES.put("taxonomy_state", "bundle_version");
        TABLES.put("image", "id");
        TABLES.put("image_tag", "image_id, tag_id");
        TABLES.put("image_meta", "image_id");
        TABLES.put("image_processing", "image_id, consumer");
        TABLES.put("tag", "id");
        TABLES.put("change_log", "id");
        TABLES.put("import_run", "id");
        TABLES.put("import_item", "id");
        TABLES.put("import_item_tag", "item_id, tag_id");
        TABLES.put("sqlite_sequence", "name");
    }

    private SQLiteDataSource library() {
        SQLiteConfig config = new SQLiteConfig();
        config.enforceForeignKeys(true);
        SQLiteDataSource ds = new SQLiteDataSource(config);
        ds.setUrl("jdbc:sqlite:" + dir.resolve(Database.FILE_NAME).toAbsolutePath());
        return ds;
    }

    /**
     * A version 4 library as 3b-2 left it: live, deleted and purged photos, tags, a cover, snapshots, ledger rows (done
     * and failed), an import with items (read, filed, missing) and item tags.
     */
    private SQLiteDataSource version4Library() throws SQLException {
        SQLiteDataSource v4 = library();
        Flyway.configure().dataSource(v4).locations("classpath:db/migration").target("4").load().migrate();
        try (Connection c = v4.getConnection(); Statement st = c.createStatement()) {
            st.executeUpdate("INSERT INTO taxonomy_state (bundle_version, bundle_sha256, applied_at) VALUES ('2026-10-08',"
                    + " 'abc', '2026-10-09T08:00:00Z')");
            st.executeUpdate("INSERT INTO taxon (id, parent_id, rank, name, common_name, group_key, source, ancestor_path,"
                    + " folder_name, sort_key) VALUES ('A5', NULL, 'species', 'Danaus chrysippus', 'Plain Tiger', 'butterflies',"
                    + " 'official', '/A5/', 'Danaus_chrysippus_[A5]', 'danaus chrysippus'),"
                    + " ('A6', NULL, 'species', 'Danaus genutia', 'Striped Tiger', 'butterflies', 'official', '/A6/',"
                    + " 'Danaus_genutia_[A6]', 'danaus genutia')");
            st.executeUpdate("INSERT INTO taxon_synonym (taxon_id, name, source, added_at) VALUES ('A5', 'Danaida chrysippus',"
                    + " 'bundle', NULL), ('A6', 'Papilio genutia', 'user', '2026-10-09T08:00:00Z')");
            for (int n = 1; n <= 5; n++) {
                st.executeUpdate("INSERT INTO image (taxon_id, rel_path, file_name, number, original_name, file_size, modified_at,"
                        + " sha256, width, height, orientation, added_at, source) VALUES ('" + (n % 2 == 0 ? "A6" : "A5") + "',"
                        + " 'images/x/p_" + n + ".jpg', 'p_" + n + ".jpg', " + n + ", 'IMG_" + n + ".JPG', " + (1000 + n) + ","
                        + " '2026-10-0" + n + "T08:00:00Z', 'sha" + n + "', 400, 300, 1, '2026-10-0" + n + "T09:00:00Z', '"
                        + (n == 3 ? "rescan" : n == 2 ? "import" : "app") + "')");
            }
            st.executeUpdate("UPDATE image SET deleted_at = '2026-10-10T00:00:00Z', deleted_rel_path = '.deleted/4_p_4.jpg'"
                    + " WHERE id = 4");
            st.executeUpdate("DELETE FROM image WHERE id = 5"); // purged: id 5 must never come back
            st.executeUpdate("INSERT INTO image_tag (image_id, tag_id) SELECT 1, id FROM tag WHERE key IN ('male', 'larva')");
            st.executeUpdate("INSERT INTO tag (key, label, source, sort_order) VALUES ('mud', 'Mud-puddling', 'user', 1000)");
            st.executeUpdate("INSERT INTO image_tag (image_id, tag_id) SELECT 2, id FROM tag WHERE key = 'mud'");
            st.executeUpdate("UPDATE taxon SET cover_image_id = 3, next_photo_number = 6 WHERE id = 'A5'");
            for (int id = 1; id <= 4; id++) {
                st.executeUpdate("INSERT INTO image_meta (image_id, json, read_at) VALUES (" + id + ", '{\"v\":1,\"format\":\"JPEG\","
                        + "\"orientation\":1,\"gps\":{\"lat\":18.5,\"lon\":73.8}}', '2026-10-11T00:00:00Z')");
                st.executeUpdate("INSERT INTO image_processing (image_id, consumer, version, status, error, done_at) VALUES ("
                        + id + ", 'dimensions', 1, 'done', NULL, '2026-10-11T00:00:00Z')");
            }
            st.executeUpdate("INSERT INTO image_processing (image_id, consumer, version, status, error, done_at) VALUES (2,"
                    + " 'thumbnail', 1, 'failed', 'java.io.IOException: broken', '2026-10-11T00:00:00Z'),"
                    + " (3, 'details', 1, 'failed', 'NoSuchFileException: p_3.jpg', '2026-10-11T00:00:00Z'),"
                    + " (1, 'thumbnail', 1, 'done', NULL, '2026-10-11T00:00:00Z')");
            st.executeUpdate("INSERT INTO change_log (at, kind, payload_json) VALUES ('2026-10-11T00:00:00Z', 'photo.purge',"
                    + " '{\"id\":5}')");
            st.executeUpdate("INSERT INTO import_run (root_path, state, created_at, updated_at, filings, species_before)"
                    + " VALUES ('/photos/2024', 'open', '2026-10-12T00:00:00Z', '2026-10-12T01:00:00Z', 1, 1)");
            st.executeUpdate("INSERT INTO import_item (id, run_id, rel_path, file_size, modified_at, read_state, sha256, format,"
                    + " meta_json, v, taxon_id, outcome, image_id, filing_no, seq) VALUES"
                    + " (1, 1, 'a.jpg', 1002, '2026-10-02T08:00:00Z', 'read', 'sha2', 'JPEG', '{\"v\":1}', 'sha2', 'A6',"
                    + " 'filed', 2, 1, 3),"
                    + " (2, 1, 'b.jpg', 10, '2026-10-02T08:00:00Z', 'read', 'shaB', 'JPEG', '{\"v\":1}', 'shaB', NULL, NULL,"
                    + " NULL, NULL, 2),"
                    + " (3, 1, 'c.jpg', 10, '2026-10-02T08:00:00Z', 'missing', NULL, NULL, NULL, NULL, NULL, NULL, NULL,"
                    + " NULL, 1)");
            st.executeUpdate("INSERT INTO import_item_tag (item_id, tag_id) SELECT 2, id FROM tag WHERE key = 'female'");
        }
        return v4;
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

    private static List<String> columns(Connection c, String table) throws SQLException {
        List<String> columns = new ArrayList<>();
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) {
                columns.add(rs.getString("name"));
            }
        }
        return columns;
    }

    /** The rows of each table, as quote() of each of the given columns, so a changed storage class is caught too. */
    private static Map<String, List<String>> dumpAll(Connection c, Map<String, List<String>> columns) throws SQLException {
        Map<String, List<String>> all = new LinkedHashMap<>();
        for (Map.Entry<String, String> table : TABLES.entrySet()) {
            StringBuilder sql = new StringBuilder("SELECT ");
            List<String> names = columns.get(table.getKey());
            for (int i = 0; i < names.size(); i++) {
                sql.append(i > 0 ? ", " : "").append("quote(\"").append(names.get(i)).append("\") AS \"")
                        .append(names.get(i)).append('"');
            }
            sql.append(" FROM ").append(table.getKey()).append(" ORDER BY ").append(table.getValue());
            all.put(table.getKey(), dump(c, sql.toString()));
        }
        return all;
    }

    private static List<String> rows(Connection c, String sql) throws SQLException {
        return dump(c, sql);
    }

    private static List<String> rows(Database db, String sql) {
        return db.read(c -> dump(c, sql));
    }

    @Test
    void version5KeepsEveryRowAndIdOfAPopulatedVersion4Library() throws Exception {
        SQLiteDataSource v4 = version4Library();
        Map<String, List<String>> columns = new LinkedHashMap<>();
        Map<String, List<String>> before;
        try (Connection c = v4.getConnection()) {
            for (String table : TABLES.keySet()) {
                columns.put(table, columns(c, table));
            }
            before = dumpAll(c, columns);
            // A table added to the schema but not to TABLES would escape the comparison: fail here instead.
            // (taxon_fts and its shadow tables are the full-text index, rebuilt from taxon and taxon_synonym.)
            assertThat(rows(c, "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite\\_%' ESCAPE '\\'"
                    + " AND name <> 'flyway_schema_history' AND name NOT LIKE 'taxon\\_fts%' ESCAPE '\\' ORDER BY name"))
                    .extracting(r -> r.substring("name=".length()))
                    .containsExactlyInAnyOrderElementsOf(TABLES.keySet().stream().filter(n -> !n.startsWith("sqlite_")).toList());
        }
        assertThat(before.get("image")).hasSize(4);
        assertThat(before.get("import_item")).hasSize(3);

        Database db = Database.open(dir.resolve(Database.FILE_NAME), "5");

        Map<String, List<String>> after = db.read(c -> dumpAll(c, columns));
        assertThat(after).isEqualTo(before);
        assertThat(rows(db, "SELECT version, success FROM flyway_schema_history ORDER BY installed_rank")).containsExactly(
                "version=1|success=1", "version=2|success=1", "version=3|success=1", "version=4|success=1",
                "version=5|success=1");
        assertThat(rows(db, "PRAGMA foreign_key_check")).isEmpty();
    }

    @Test
    void theNewColumnsStartEmptyAndFailedLedgerRowsCountOneAttempt() throws Exception {
        version4Library();

        Database db = Database.open(dir.resolve(Database.FILE_NAME));

        assertThat(rows(db, "SELECT id, quote(place_id) AS place_id, quote(place_source) AS place_source FROM image"
                + " ORDER BY id")).containsExactly("id=1|place_id=NULL|place_source=NULL",
                "id=2|place_id=NULL|place_source=NULL", "id=3|place_id=NULL|place_source=NULL",
                "id=4|place_id=NULL|place_source=NULL");
        assertThat(rows(db, "SELECT image_id, consumer, status, attempts FROM image_processing ORDER BY image_id, consumer"))
                .containsExactly("image_id=1|consumer=dimensions|status=done|attempts=0",
                        "image_id=1|consumer=thumbnail|status=done|attempts=0",
                        "image_id=2|consumer=dimensions|status=done|attempts=0",
                        "image_id=2|consumer=thumbnail|status=failed|attempts=1",
                        "image_id=3|consumer=details|status=failed|attempts=1",
                        "image_id=3|consumer=dimensions|status=done|attempts=0",
                        "image_id=4|consumer=dimensions|status=done|attempts=0");
        assertThat(rows(db, "SELECT id, quote(gps_place_id) AS gps FROM import_item ORDER BY id"))
                .containsExactly("id=1|gps=NULL", "id=2|gps=NULL", "id=3|gps=NULL");
        assertThat(rows(db, "SELECT (SELECT COUNT(*) FROM place) || '/' || (SELECT COUNT(*) FROM place_alias) || '/'"
                + " || (SELECT COUNT(*) FROM place_state) AS n")).containsExactly("n=0/0/0");
        assertThat(rows(db, "SELECT name FROM sqlite_master WHERE type = 'index' AND name IN ('image_live_place',"
                + " 'place_parent', 'place_path') ORDER BY name")).containsExactly("name=image_live_place",
                "name=place_parent", "name=place_path");
        assertThat(rows(db, "SELECT sql FROM sqlite_master WHERE name = 'image_live_place'").get(0))
                .contains("WHERE deleted_at IS NULL");
    }

    @Test
    void afterVersion5PurgedIdsStayUsedAndTheNewChecksHold() throws Exception {
        version4Library();
        Database db = Database.open(dir.resolve(Database.FILE_NAME));

        long id = db.write(c -> {
            try (Statement st = c.createStatement()) {
                st.executeUpdate("INSERT INTO image (taxon_id, rel_path, file_name, file_size, modified_at, sha256, added_at,"
                        + " source, place_id, place_source) VALUES ('A5', 'images/x/new.jpg', 'new.jpg', 1, 't', 'shaN', 't',"
                        + " 'app', 'P404', 'gps')");
                try (ResultSet rs = st.executeQuery("SELECT last_insert_rowid()")) {
                    rs.next();
                    return rs.getLong(1);
                }
            }
        });
        assertThat(id).as("the purged photo's id 5 is not reused; place_id needs no place row").isEqualTo(6);
        assertThatThrownBy(() -> exec(db, "UPDATE image SET place_source = 'guess' WHERE id = 1"))
                .isInstanceOf(DatabaseException.class);
        assertThatThrownBy(() -> exec(db, "INSERT INTO place (id, level, name, source, ancestor_path, sort_key)"
                + " VALUES ('P1', 'town', 'X', 'bundled', '/P1/', 'x')")).isInstanceOf(DatabaseException.class);
        assertThatThrownBy(() -> exec(db, "INSERT INTO place (id, level, name, source, ancestor_path, sort_key)"
                + " VALUES ('P1', 'country', 'X', 'shipped', '/P1/', 'x')")).isInstanceOf(DatabaseException.class);
        assertThatThrownBy(() -> exec(db, "INSERT INTO place (id, parent_id, level, name, source, ancestor_path, sort_key)"
                + " VALUES ('P2', 'P404', 'state', 'X', 'bundled', '/P404/P2/', 'x')")).isInstanceOf(DatabaseException.class);
        exec(db, "INSERT INTO place (id, level, name, source, ancestor_path, sort_key) VALUES ('P1', 'country', 'India',"
                + " 'bundled', '/P1/', 'india')");
        exec(db, "INSERT INTO place_alias (place_id, name) VALUES ('P1', 'Bharat')");
        assertThat(rows(db, "SELECT active FROM place")).containsExactly("active=1");
        exec(db, "DELETE FROM place WHERE id = 'P1'");
        assertThat(rows(db, "SELECT COUNT(*) AS n FROM place_alias")).containsExactly("n=0");
    }

    private static void exec(Database db, String sql) {
        db.write(c -> {
            try (Statement st = c.createStatement()) {
                st.executeUpdate(sql);
            }
            return null;
        });
    }
}
