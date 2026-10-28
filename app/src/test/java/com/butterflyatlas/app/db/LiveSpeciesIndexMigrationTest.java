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

/**
 * V11 swaps one index, nothing else: image_live_taxon goes and image_live_species comes. A populated version 10 library
 * keeps every row of every table (compared as quote() of every column, so a changed storage class is caught too), its
 * schema as it was but those two indexes, and the queries the index is for read it alone.
 */
class LiveSpeciesIndexMigrationTest {

    @TempDir
    Path dir;

    private void version10Library() throws SQLException {
        SQLiteConfig config = new SQLiteConfig();
        config.enforceForeignKeys(true);
        SQLiteDataSource v10 = new SQLiteDataSource(config);
        v10.setUrl("jdbc:sqlite:" + dir.resolve(Database.FILE_NAME).toAbsolutePath());
        Flyway.configure().dataSource(v10).locations("classpath:db/migration").target("10").load().migrate();
        try (Connection c = v10.getConnection(); Statement st = c.createStatement()) {
            st.executeUpdate("INSERT INTO taxon (id, parent_id, rank, name, common_name, group_key, source, ancestor_path,"
                    + " folder_name, sort_key, family_id, next_photo_number) VALUES"
                    + " ('G:butterflies', NULL, 'group', 'Butterflies', NULL, 'butterflies', 'system', '/G:butterflies/',"
                    + " 'Butterflies', 'butterflies', NULL, 1),"
                    + " ('A1', 'G:butterflies', 'family', 'Nymphalidae', 'Brush-footed', 'butterflies', 'official',"
                    + " '/G:butterflies/A1/', 'Nymphalidae', 'nymphalidae', NULL, 1),"
                    + " ('A5', 'A1', 'species', 'Danaus chrysippus', 'Plain Tiger', 'butterflies', 'official',"
                    + " '/G:butterflies/A1/A5/', 'Danaus_chrysippus_[A5]', 'danaus chrysippus', NULL, 6),"
                    + " ('A6', 'A1', 'species', 'Danaus genutia', 'Striped Tiger', 'butterflies', 'official',"
                    + " '/G:butterflies/A1/A6/', 'Danaus_genutia_[A6]', 'danaus genutia', NULL, 3)");
            st.executeUpdate("INSERT INTO place (id, parent_id, level, name, source, ancestor_path, sort_key, active,"
                    + " replaced_by) VALUES ('P1', NULL, 'country', 'India', 'bundled', '/P1/', 'india', 1, NULL),"
                    + " ('P2', 'P1', 'state', 'Maharashtra', 'bundled', '/P1/P2/', 'maharashtra', 1, NULL),"
                    + " ('P3', 'P2', 'district', 'Pune', 'bundled', '/P1/P2/P3/', 'pune', 0, 'P2')");
            for (int n = 1; n <= 8; n++) {
                String taxon = n <= 5 ? "A5" : "A6";
                st.executeUpdate("INSERT INTO image (taxon_id, rel_path, file_name, number, original_name, file_size,"
                        + " modified_at, sha256, width, height, orientation, added_at, source, place_id, place_source)"
                        + " VALUES ('" + taxon + "', 'images/x/p" + n + ".jpg', 'p" + n + ".jpg', " + n + ", 'IMG_" + n
                        + ".JPG', " + (1000 + n) + ", '2026-10-0" + n + "T08:00:00Z', 'sha" + n + "', 400, 300, 1,"
                        + " '2026-10-0" + (n % 3 + 1) + "T09:00:00Z', 'app', " + (n % 2 == 0 ? "'P3', 'gps'" : "NULL, NULL")
                        + ")");
            }
            st.executeUpdate("UPDATE image SET deleted_at = '2026-10-10T00:00:00Z', deleted_rel_path = '.deleted/4_p4.jpg'"
                    + " WHERE id = 4");
            st.executeUpdate("DELETE FROM image WHERE id = 7"); // purged: id 7 must never come back
            st.executeUpdate("INSERT INTO image_tag (image_id, tag_id) SELECT 1, id FROM tag WHERE key IN ('male', 'larva')");
            st.executeUpdate("UPDATE taxon SET cover_image_id = 2 WHERE id = 'A5'");
            st.executeUpdate("INSERT INTO change_log (at, kind, payload_json) VALUES ('2026-10-11T00:00:00Z', 'photo.purge',"
                    + " '{\"id\":7}')");
        }
    }

    private static List<String> rows(Connection c, String sql) throws SQLException {
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
    }

    private static List<String> rows(Database db, String sql) {
        return db.read(c -> rows(c, sql));
    }

    /** Every table but Flyway's, with every row as quote() of each column, in rowid order. */
    private static Map<String, List<String>> dumpAll(Connection c) throws SQLException {
        Map<String, List<String>> all = new LinkedHashMap<>();
        for (String table : rows(c, "SELECT name FROM sqlite_master WHERE type = 'table'"
                + " AND name <> 'flyway_schema_history' AND name NOT LIKE 'taxon\\_fts\\_%' ESCAPE '\\' ORDER BY name")) {
            List<String> columns = new ArrayList<>();
            for (String info : rows(c, "SELECT name FROM pragma_table_info('" + table + "')")) {
                columns.add("quote(\"" + info + "\")");
            }
            all.put(table, rows(c, "SELECT " + String.join(", ", columns) + " FROM \"" + table + "\" ORDER BY rowid"));
        }
        return all;
    }

    /** The schema of everything but the two indexes V11 swaps and Flyway's own. */
    private static final String SCHEMA = "SELECT type, name, tbl_name, sql FROM sqlite_master"
            + " WHERE name NOT IN ('image_live_taxon', 'image_live_species') AND tbl_name <> 'flyway_schema_history'"
            + " AND tbl_name NOT IN ('library_setting', 'backup_run', 'backup_file', 'backup_problem')"
            + " AND name NOT LIKE 'backup\\_%' ESCAPE '\\'"
            + " ORDER BY type, name";

    @Test
    void version11KeepsEveryRowAndSwapsOneIndexOnly() throws Exception {
        version10Library();
        Database v10 = Database.open(dir.resolve(Database.FILE_NAME), "10");
        Map<String, List<String>> before = v10.read(LiveSpeciesIndexMigrationTest::dumpAll);
        List<String> schema = rows(v10, SCHEMA);
        assertThat(before.get("image")).hasSize(7);
        assertThat(before.get("taxon")).hasSize(4);
        assertThat(rows(v10, "SELECT name FROM sqlite_master WHERE name = 'image_live_taxon'"))
                .containsExactly("image_live_taxon");

        Database db = Database.open(dir.resolve(Database.FILE_NAME), "12");

        Map<String, List<String>> after = new LinkedHashMap<>(db.read(LiveSpeciesIndexMigrationTest::dumpAll));
        after.keySet().removeAll(List.of("library_setting", "backup_run", "backup_file", "backup_problem")); // V12's
        assertThat(after).isEqualTo(before);
        assertThat(rows(db, SCHEMA)).as("every table, index and trigger as it was").isEqualTo(schema);
        assertThat(rows(db, "SELECT name, sql FROM sqlite_master WHERE name LIKE 'image_live_%'"
                + " ORDER BY name")).containsExactly(
                "image_live_added|CREATE INDEX image_live_added ON image (added_at DESC, id DESC) WHERE deleted_at IS NULL",
                "image_live_path|CREATE UNIQUE INDEX image_live_path ON image (rel_path) WHERE deleted_at IS NULL",
                "image_live_place|CREATE INDEX image_live_place ON image (place_id) WHERE deleted_at IS NULL",
                "image_live_species|CREATE INDEX image_live_species ON image (taxon_id, added_at DESC, id DESC, file_size,"
                        + " place_id, deleted_at)\n    WHERE deleted_at IS NULL");
        assertThat(rows(db, "PRAGMA integrity_check")).containsExactly("ok");
        assertThat(rows(db, "PRAGMA foreign_key_check")).isEmpty();
        assertThat(rows(db, "SELECT version, success FROM flyway_schema_history ORDER BY installed_rank"))
                .contains("11|1");
        // what the index is for: the live photos by species, read from the index alone
        assertThat(rows(db, "EXPLAIN QUERY PLAN SELECT taxon_id, COUNT(*), SUM(file_size) FROM image"
                + " WHERE deleted_at IS NULL GROUP BY taxon_id")).singleElement().asString()
                .endsWith("SCAN image USING COVERING INDEX image_live_species");
        assertThat(rows(db, "SELECT taxon_id, COUNT(*), SUM(file_size) FROM image WHERE deleted_at IS NULL"
                + " GROUP BY taxon_id")).containsExactly("A5|4|4011", "A6|2|2014");
    }
}
