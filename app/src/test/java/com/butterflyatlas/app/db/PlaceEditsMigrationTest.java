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

/** V6 only adds a table and columns: a populated version 5 library keeps every row and id. */
class PlaceEditsMigrationTest {

    @TempDir
    Path dir;

    /** Every table a version 5 library holds data in, with the order its rows are compared in. */
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
        TABLES.put("place", "id");
        TABLES.put("place_alias", "place_id, name");
        TABLES.put("place_state", "bundle_version, bundle_sha256");
        TABLES.put("sqlite_sequence", "name");
    }

    /**
     * A version 5 library as 3c-1 left it: places with aliases, live, deleted and purged photos with GPS and user places,
     * tags, a cover, snapshots, ledger rows with attempts, an import whose items have GPS places, and item tags.
     */
    private void version5Library() throws SQLException {
        SQLiteConfig config = new SQLiteConfig();
        config.enforceForeignKeys(true);
        SQLiteDataSource v5 = new SQLiteDataSource(config);
        v5.setUrl("jdbc:sqlite:" + dir.resolve(Database.FILE_NAME).toAbsolutePath());
        Flyway.configure().dataSource(v5).locations("classpath:db/migration").target("5").load().migrate();
        try (Connection c = v5.getConnection(); Statement st = c.createStatement()) {
            st.executeUpdate("INSERT INTO taxonomy_state (bundle_version, bundle_sha256, applied_at) VALUES ('2026-10-08',"
                    + " 'abc', '2026-10-09T08:00:00Z')");
            st.executeUpdate("INSERT INTO taxon (id, parent_id, rank, name, common_name, group_key, source, ancestor_path,"
                    + " folder_name, sort_key) VALUES ('A5', NULL, 'species', 'Danaus chrysippus', 'Plain Tiger', 'butterflies',"
                    + " 'official', '/A5/', 'Danaus_chrysippus_[A5]', 'danaus chrysippus')");
            st.executeUpdate("INSERT INTO taxon_synonym (taxon_id, name, source, added_at) VALUES ('A5', 'Danaida chrysippus',"
                    + " 'bundle', NULL)");
            st.executeUpdate("INSERT INTO place (id, parent_id, level, name, source, ancestor_path, sort_key, active) VALUES"
                    + " ('P1', NULL, 'country', 'India', 'bundled', '/P1/', 'india', 1),"
                    + " ('P2', 'P1', 'state', 'Maharashtra', 'bundled', '/P1/P2/', 'maharashtra', 1),"
                    + " ('P3', 'P2', 'district', 'Pune', 'bundled', '/P1/P2/P3/', 'pune', 1),"
                    + " ('P4', 'P2', 'district', 'Satara', 'bundled', '/P1/P2/P4/', 'satara', 0)");
            st.executeUpdate("INSERT INTO place_alias (place_id, name) VALUES ('P1', 'Bharat'), ('P3', 'Poona')");
            st.executeUpdate("INSERT INTO place_state (bundle_version, bundle_sha256, applied_at) VALUES ('2026-10-13', 'def',"
                    + " '2026-10-13T08:00:00Z')");
            for (int n = 1; n <= 5; n++) {
                st.executeUpdate("INSERT INTO image (taxon_id, rel_path, file_name, number, original_name, file_size, modified_at,"
                        + " sha256, width, height, orientation, added_at, source, place_id, place_source) VALUES ('A5',"
                        + " 'images/x/p_" + n + ".jpg', 'p_" + n + ".jpg', " + n + ", 'IMG_" + n + ".JPG', " + (1000 + n) + ","
                        + " '2026-10-0" + n + "T08:00:00Z', 'sha" + n + "', 400, 300, 1, '2026-10-0" + n + "T09:00:00Z', '"
                        + (n == 2 ? "import" : "app") + "', " + (n == 1 ? "'P3', 'gps'" : n == 2 ? "'P4', 'user'"
                        : n == 3 ? "NULL, 'user'" : "NULL, NULL") + ")");
            }
            st.executeUpdate("UPDATE image SET deleted_at = '2026-10-10T00:00:00Z', deleted_rel_path = '.deleted/4_p_4.jpg'"
                    + " WHERE id = 4");
            st.executeUpdate("DELETE FROM image WHERE id = 5"); // purged: id 5 must never come back
            st.executeUpdate("INSERT INTO image_tag (image_id, tag_id) SELECT 1, id FROM tag WHERE key IN ('male', 'larva')");
            st.executeUpdate("UPDATE taxon SET cover_image_id = 2, next_photo_number = 6 WHERE id = 'A5'");
            for (int id = 1; id <= 4; id++) {
                st.executeUpdate("INSERT INTO image_meta (image_id, json, read_at) VALUES (" + id + ", '{\"v\":1,\"format\":\"JPEG\","
                        + "\"orientation\":1,\"gps\":{\"lat\":18.5,\"lon\":73.8}}', '2026-10-11T00:00:00Z')");
                st.executeUpdate("INSERT INTO image_processing (image_id, consumer, version, status, error, done_at, attempts)"
                        + " VALUES (" + id + ", 'place', 1, 'done', NULL, '2026-10-11T00:00:00Z', 0)");
            }
            st.executeUpdate("INSERT INTO image_processing (image_id, consumer, version, status, error, done_at, attempts)"
                    + " VALUES (2, 'thumbnail', 1, 'failed', 'java.io.IOException: broken', '2026-10-11T00:00:00Z', 2)");
            st.executeUpdate("INSERT INTO change_log (at, kind, payload_json) VALUES ('2026-10-11T00:00:00Z', 'photo.purge',"
                    + " '{\"id\":5}')");
            st.executeUpdate("INSERT INTO import_run (root_path, state, created_at, updated_at, filings, species_before)"
                    + " VALUES ('/photos/2024', 'open', '2026-10-12T00:00:00Z', '2026-10-12T01:00:00Z', 1, 1)");
            st.executeUpdate("INSERT INTO import_item (id, run_id, rel_path, file_size, modified_at, read_state, sha256, format,"
                    + " meta_json, v, taxon_id, outcome, image_id, filing_no, seq, gps_place_id) VALUES"
                    + " (1, 1, 'a.jpg', 1002, '2026-10-02T08:00:00Z', 'read', 'sha2', 'JPEG', '{\"v\":1}', 'sha2', 'A5',"
                    + " 'filed', 2, 1, 3, 'P4'),"
                    + " (2, 1, 'b.jpg', 10, '2026-10-02T08:00:00Z', 'read', 'shaB', 'JPEG', '{\"v\":1}', 'shaB', NULL, NULL,"
                    + " NULL, NULL, 2, 'P3'),"
                    + " (3, 1, 'c.jpg', 10, '2026-10-02T08:00:00Z', 'missing', NULL, NULL, NULL, NULL, NULL, NULL, NULL,"
                    + " NULL, 1, NULL)");
            st.executeUpdate("INSERT INTO import_item_tag (item_id, tag_id) SELECT 2, id FROM tag WHERE key = 'female'");
        }
    }

    private static List<String> rows(Connection c, String sql) throws SQLException {
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
        return db.read(c -> rows(c, sql));
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
            all.put(table.getKey(), rows(c, sql.toString()));
        }
        return all;
    }

    private static void exec(Database db, String sql) {
        db.write(c -> {
            try (Statement st = c.createStatement()) {
                st.executeUpdate(sql);
            }
            return null;
        });
    }

    @Test
    void version6KeepsEveryRowAndIdOfAPopulatedVersion5Library() throws Exception {
        version5Library();
        Map<String, List<String>> columns = new LinkedHashMap<>();
        Map<String, List<String>> before;
        Database v5 = Database.open(dir.resolve(Database.FILE_NAME), "5");
        before = v5.read(c -> {
            for (String table : TABLES.keySet()) {
                columns.put(table, columns(c, table));
            }
            // A table added to the schema but not to TABLES would escape the comparison: fail here instead.
            assertThat(rows(c, "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite\\_%' ESCAPE '\\'"
                    + " AND name <> 'flyway_schema_history' AND name NOT LIKE 'taxon\\_fts%' ESCAPE '\\' ORDER BY name"))
                    .extracting(r -> r.substring("name=".length()))
                    .containsExactlyInAnyOrderElementsOf(TABLES.keySet().stream().filter(n -> !n.startsWith("sqlite_")).toList());
            return dumpAll(c, columns);
        });
        assertThat(before.get("image")).hasSize(4);
        assertThat(before.get("import_item")).hasSize(3);
        assertThat(before.get("place")).hasSize(4);

        Database db = Database.open(dir.resolve(Database.FILE_NAME), "6");

        Map<String, List<String>> after = db.read(c -> dumpAll(c, columns));
        assertThat(after).isEqualTo(before);
        assertThat(rows(db, "SELECT version, success FROM flyway_schema_history ORDER BY installed_rank")).containsExactly(
                "version=1|success=1", "version=2|success=1", "version=3|success=1", "version=4|success=1",
                "version=5|success=1", "version=6|success=1");
        assertThat(rows(db, "PRAGMA foreign_key_check")).isEmpty();
        List<String> tables = new ArrayList<>(TABLES.keySet().stream().filter(n -> !n.startsWith("sqlite_")).toList());
        tables.add("place_recent");
        assertThat(rows(db, "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite\\_%' ESCAPE '\\'"
                + " AND name <> 'flyway_schema_history' AND name NOT LIKE 'taxon\\_fts%' ESCAPE '\\' ORDER BY name"))
                .as("V6 adds place_recent only").extracting(r -> r.substring("name=".length()))
                .containsExactlyInAnyOrderElementsOf(tables);
    }

    @Test
    void theNewColumnsStartUnsetAndPlaceRecentStartsEmpty() throws Exception {
        version5Library();

        Database db = Database.open(dir.resolve(Database.FILE_NAME));

        assertThat(rows(db, "SELECT id, quote(user_place_id) AS place, user_place_set FROM import_item ORDER BY id"))
                .containsExactly("id=1|place=NULL|user_place_set=0", "id=2|place=NULL|user_place_set=0",
                        "id=3|place=NULL|user_place_set=0");
        assertThat(rows(db, "SELECT COUNT(*) AS n FROM place_recent")).containsExactly("n=0");
    }

    @Test
    void afterVersion6TheChecksAndTheCascadeHold() throws Exception {
        version5Library();
        Database db = Database.open(dir.resolve(Database.FILE_NAME));

        assertThatThrownBy(() -> exec(db, "UPDATE import_item SET user_place_set = 2 WHERE id = 1"))
                .isInstanceOf(DatabaseException.class);
        assertThatThrownBy(() -> exec(db, "INSERT INTO place_recent (place_id, used_at) VALUES ('P404', 't')"))
                .isInstanceOf(DatabaseException.class);
        assertThatThrownBy(() -> exec(db, "INSERT INTO place_recent (place_id) VALUES ('P3')"))
                .isInstanceOf(DatabaseException.class);
        exec(db, "UPDATE import_item SET user_place_id = 'P404', user_place_set = 1 WHERE id = 2");
        assertThat(rows(db, "SELECT user_place_id, user_place_set FROM import_item WHERE id = 2"))
                .as("an item's choice needs no place row (a deleted user place is cleared by the app)")
                .containsExactly("user_place_id=P404|user_place_set=1");
        exec(db, "INSERT INTO place (id, parent_id, level, name, source, ancestor_path, sort_key) VALUES ('PU1', 'P2',"
                + " 'district', 'Tamhini', 'user', '/P1/P2/PU1/', 'tamhini')");
        exec(db, "INSERT INTO place_recent (place_id, used_at) VALUES ('PU1', '2026-10-13T09:00:00Z'),"
                + " ('P3', '2026-10-13T08:00:00Z')");
        exec(db, "DELETE FROM place WHERE id = 'PU1'");
        assertThat(rows(db, "SELECT place_id FROM place_recent")).containsExactly("place_id=P3");
    }
}
