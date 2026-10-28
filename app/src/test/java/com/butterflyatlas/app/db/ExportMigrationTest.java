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

/** V7 only adds the export tables: a populated version 6 library keeps every row and id. */
class ExportMigrationTest {

    @TempDir
    Path dir;

    /** Every table a version 6 library holds data in, with the order its rows are compared in. */
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
        TABLES.put("place_recent", "place_id");
        TABLES.put("sqlite_sequence", "name");
    }

    /**
     * A version 6 library as 4a left it: places with aliases and a user district, recently used places, live, deleted and
     * purged photos with GPS and user places, tags, a cover, snapshots, ledger rows with attempts, an open import whose
     * items have GPS places and a place chosen by the user, a finished import, and item tags.
     */
    private void version6Library() throws SQLException {
        SQLiteConfig config = new SQLiteConfig();
        config.enforceForeignKeys(true);
        SQLiteDataSource v6 = new SQLiteDataSource(config);
        v6.setUrl("jdbc:sqlite:" + dir.resolve(Database.FILE_NAME).toAbsolutePath());
        Flyway.configure().dataSource(v6).locations("classpath:db/migration").target("6").load().migrate();
        try (Connection c = v6.getConnection(); Statement st = c.createStatement()) {
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
            st.executeUpdate("INSERT INTO place (id, parent_id, level, name, source, ancestor_path, sort_key) VALUES ('PU1', 'P2',"
                    + " 'district', 'Tamhini', 'user', '/P1/P2/PU1/', 'tamhini')");
            st.executeUpdate("UPDATE image SET place_id = 'PU1', place_source = 'user' WHERE id = 3");
            st.executeUpdate("INSERT INTO place_recent (place_id, used_at) VALUES ('PU1', '2026-10-13T09:00:00Z'),"
                    + " ('P3', '2026-10-13T08:00:00Z')");
            st.executeUpdate("UPDATE import_item SET user_place_id = 'PU1', user_place_set = 1 WHERE id = 2");
            st.executeUpdate("INSERT INTO import_run (root_path, state, created_at, updated_at, filings) VALUES ('/photos/2023',"
                    + " 'finished', '2026-10-11T00:00:00Z', '2026-10-11T01:00:00Z', 0)");
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

    /** The schema of everything but the export tables and Flyway's own. */
    private static final String SCHEMA = "SELECT type, name, tbl_name, sql FROM sqlite_master WHERE tbl_name NOT LIKE"
            + " 'export\\_%' ESCAPE '\\' AND name <> 'flyway_schema_history' AND tbl_name <> 'flyway_schema_history'"
            + " ORDER BY type, name";

    private static void exec(Database db, String sql) {
        db.write(c -> {
            try (Statement st = c.createStatement()) {
                st.executeUpdate(sql);
            }
            return null;
        });
    }

    @Test
    void version7KeepsEveryRowAndIdOfAPopulatedVersion6Library() throws Exception {
        version6Library();
        Map<String, List<String>> columns = new LinkedHashMap<>();
        Map<String, List<String>> before;
        Database v6 = Database.open(dir.resolve(Database.FILE_NAME), "6");
        List<String> schema = rows(v6, SCHEMA);
        before = v6.read(c -> {
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
        assertThat(before.get("import_run")).hasSize(2);
        assertThat(before.get("import_item")).hasSize(3);
        assertThat(before.get("place")).hasSize(5);
        assertThat(before.get("place_recent")).hasSize(2);

        Database db = Database.open(dir.resolve(Database.FILE_NAME), "7");

        Map<String, List<String>> after = db.read(c -> dumpAll(c, columns));
        assertThat(after).isEqualTo(before);
        assertThat(rows(db, SCHEMA)).as("every table, index and trigger of version 6 as it was").isEqualTo(schema);
        assertThat(rows(db, "PRAGMA integrity_check")).containsExactly("integrity_check=ok");
        assertThat(rows(db, "SELECT version, success FROM flyway_schema_history ORDER BY installed_rank")).containsExactly(
                "version=1|success=1", "version=2|success=1", "version=3|success=1", "version=4|success=1",
                "version=5|success=1", "version=6|success=1", "version=7|success=1");
        assertThat(rows(db, "PRAGMA foreign_key_check")).isEmpty();
        List<String> tables = new ArrayList<>(TABLES.keySet().stream().filter(n -> !n.startsWith("sqlite_")).toList());
        tables.addAll(List.of("export_run", "export_item"));
        assertThat(rows(db, "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite\\_%' ESCAPE '\\'"
                + " AND name <> 'flyway_schema_history' AND name NOT LIKE 'taxon\\_fts%' ESCAPE '\\' ORDER BY name"))
                .as("V7 adds export_run and export_item only").extracting(r -> r.substring("name=".length()))
                .containsExactlyInAnyOrderElementsOf(tables);
        assertThat(rows(db, "SELECT name FROM sqlite_master WHERE type = 'index' AND name LIKE 'export%' ORDER BY name"))
                .containsExactly("name=export_item_state", "name=export_run_one", "name=export_run_state");
        assertThat(rows(db, "SELECT COUNT(*) AS n FROM export_run")).containsExactly("n=0");
        assertThat(rows(db, "SELECT COUNT(*) AS n FROM export_item")).containsExactly("n=0");
    }

    @Test
    void afterVersion7TheChecksAndTheCascadeHold() throws Exception {
        version6Library();
        Database db = Database.open(dir.resolve(Database.FILE_NAME), "7");
        String run = "INSERT INTO export_run (dest_root, folder_name, what, layout, strip_location, place_depth, filter_query,"
                + " species, state, total, total_bytes, started_at) VALUES ('/Volumes/Backup', 'Butterfly_Atlas_export_2026-10-13',"
                + " %s, 'species', 1, 'none', '', 1, %s, 2, 2002, '2026-10-13T16:10:04Z')";

        assertThatThrownBy(() -> exec(db, String.format(run, "'photos'", "'stopped'"))).isInstanceOf(DatabaseException.class);
        assertThatThrownBy(() -> exec(db, String.format(run, "'pdf'", "'running'"))).isInstanceOf(DatabaseException.class);
        for (String bad : List.of("'tree', 1, 'none'", "'species', 2, 'none'", "'species', 1, 'town'")) {
            assertThatThrownBy(() -> exec(db, String.format(run, "'photos'", "'running'").replace("'species', 1, 'none'", bad)))
                    .as(bad).isInstanceOf(DatabaseException.class).hasStackTraceContaining("CHECK constraint failed");
        }
        exec(db, String.format(run, "'photos'", "'running'"));
        assertThatThrownBy(() -> exec(db, "UPDATE export_run SET paused_by = 'tired'")).isInstanceOf(DatabaseException.class);
        assertThatThrownBy(() -> exec(db, "UPDATE export_run SET cancel_choice = 'shred'")).isInstanceOf(DatabaseException.class);
        assertThatThrownBy(() -> exec(db, "INSERT INTO export_item (run_id, seq, image_id, taxon_id, file_name, target_rel,"
                + " bytes) VALUES (9, 1, 1, 'A5', 'p_1.jpg', 'a.jpg', 1)")).isInstanceOf(DatabaseException.class);
        exec(db, "INSERT INTO export_item (run_id, seq, image_id, taxon_id, file_name, target_rel, bytes) VALUES"
                + " (1, 1, 1, 'A5', 'p_1.jpg', 'a.jpg', 1001), (1, 2, 5, 'A5', 'p_5.jpg', 'b.jpg', 1001)");
        assertThat(rows(db, "SELECT seq, image_id, state, renamed FROM export_item ORDER BY seq")).as("a purged photo's id is fine")
                .containsExactly("seq=1|image_id=1|state=pending|renamed=0", "seq=2|image_id=5|state=pending|renamed=0");
        assertThatThrownBy(() -> exec(db, "UPDATE export_item SET state = 'copied' WHERE seq = 1"))
                .isInstanceOf(DatabaseException.class);
        assertThatThrownBy(() -> exec(db, "UPDATE export_item SET renamed = 2 WHERE seq = 1"))
                .isInstanceOf(DatabaseException.class).hasStackTraceContaining("CHECK constraint failed");
        assertThatThrownBy(() -> exec(db, "INSERT INTO export_item (run_id, seq, image_id, taxon_id, file_name, target_rel,"
                + " bytes) VALUES (1, 3, 2, 'A5', 'p_2.jpg', 'A.JPG', 1)")).as("a target differing only by case")
                .isInstanceOf(DatabaseException.class).hasStackTraceContaining("export_item.target_rel");
        assertThat(rows(db, "SELECT seq FROM export_item ORDER BY seq")).containsExactly("seq=1", "seq=2");
        assertThatThrownBy(() -> exec(db, String.format(run, "'csv'", "'paused'"))).as("one export at a time")
                .isInstanceOf(DatabaseException.class).hasStackTraceContaining("export_run_one");
        assertThat(rows(db, "SELECT id, state FROM export_run")).as("the first is still current, no second row")
                .containsExactly("id=1|state=running");
        exec(db, String.format(run, "'csv'", "'done'"));
        exec(db, String.format(run, "'csv'", "'cancelled'"));
        exec(db, "DELETE FROM export_run WHERE id = 1");
        assertThat(rows(db, "SELECT COUNT(*) AS n FROM export_item")).containsExactly("n=0");
        assertThat(rows(db, "SELECT COUNT(*) AS n FROM image")).as("photos untouched").containsExactly("n=4");
    }
}
