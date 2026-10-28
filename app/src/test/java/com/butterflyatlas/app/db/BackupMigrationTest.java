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

/**
 * V12 only adds the backup tables: a populated version 11 library (the V11 test's, plus a finished export) keeps every row
 * of every table (compared as quote() of every column), its schema as it was, and gains four empty tables that hold what
 * the contract says.
 */
class BackupMigrationTest {

    @TempDir
    Path dir;

    private void version11Library() throws SQLException {
        SQLiteConfig config = new SQLiteConfig();
        config.enforceForeignKeys(true);
        SQLiteDataSource v11 = new SQLiteDataSource(config);
        v11.setUrl("jdbc:sqlite:" + dir.resolve(Database.FILE_NAME).toAbsolutePath());
        Flyway.configure().dataSource(v11).locations("classpath:db/migration").target("11").load().migrate();
        try (Connection c = v11.getConnection(); Statement st = c.createStatement()) {
            st.executeUpdate("INSERT INTO taxon (id, parent_id, rank, name, common_name, group_key, source, ancestor_path,"
                    + " folder_name, sort_key, family_id, next_photo_number) VALUES"
                    + " ('G:butterflies', NULL, 'group', 'Butterflies', NULL, 'butterflies', 'system', '/G:butterflies/',"
                    + " 'Butterflies', 'butterflies', NULL, 1),"
                    + " ('A1', 'G:butterflies', 'family', 'Nymphalidae', 'Brush-footed', 'butterflies', 'official',"
                    + " '/G:butterflies/A1/', 'Nymphalidae', 'nymphalidae', NULL, 1),"
                    + " ('A5', 'A1', 'species', 'Danaus chrysippus', 'Plain Tiger', 'butterflies', 'official',"
                    + " '/G:butterflies/A1/A5/', 'Danaus_chrysippus_[A5]', 'danaus chrysippus', NULL, 6)");
            st.executeUpdate("INSERT INTO place (id, parent_id, level, name, source, ancestor_path, sort_key, active,"
                    + " replaced_by) VALUES ('P1', NULL, 'country', 'India', 'bundled', '/P1/', 'india', 1, NULL),"
                    + " ('P2', 'P1', 'state', 'Maharashtra', 'bundled', '/P1/P2/', 'maharashtra', 1, NULL)");
            for (int n = 1; n <= 6; n++) {
                st.executeUpdate("INSERT INTO image (taxon_id, rel_path, file_name, number, original_name, file_size,"
                        + " modified_at, sha256, width, height, orientation, added_at, source, place_id, place_source)"
                        + " VALUES ('A5', 'images/x/p" + n + ".jpg', 'p" + n + ".jpg', " + n + ", 'IMG_" + n + ".JPG', "
                        + (1000 + n) + ", '2026-10-0" + n + "T08:00:00Z', 'sha" + n + "', 400, 300, 1,"
                        + " '2026-10-0" + n + "T09:00:00Z', 'app', " + (n % 2 == 0 ? "'P2', 'gps'" : "NULL, NULL") + ")");
            }
            st.executeUpdate("UPDATE image SET deleted_at = '2026-10-10T00:00:00Z', deleted_rel_path = '.deleted/4_p4.jpg'"
                    + " WHERE id = 4");
            st.executeUpdate("INSERT INTO image_tag (image_id, tag_id) SELECT 1, id FROM tag WHERE key IN ('male', 'larva')");
            st.executeUpdate("INSERT INTO change_log (at, kind, payload_json) VALUES ('2026-10-11T00:00:00Z', 'photo.purge',"
                    + " '{\"id\":7}')");
            st.executeUpdate("INSERT INTO export_run (dest_root, folder_name, what, layout, strip_location, place_depth,"
                    + " filter_query, species, state, total, copied, total_bytes, bytes_done, started_at, finished_at)"
                    + " VALUES ('/x/Backup', 'Butterfly_Atlas_export_2026-10-12', 'photos', 'species', 1, 'none', '', 1,"
                    + " 'done', 2, 2, 2007, 2007, '2026-10-12T08:00:00Z', '2026-10-12T08:01:00Z')");
            st.executeUpdate("INSERT INTO export_item (run_id, seq, image_id, taxon_id, file_name, target_rel, bytes, state,"
                    + " written_bytes, written_mtime) VALUES (1, 1, 1, 'A5', 'p1.jpg', 'a/p1.jpg', 1001, 'done', 1001, 5),"
                    + " (1, 2, 2, 'A5', 'p2.jpg', 'a/p2.jpg', 1002, 'done', 1002, 6)");
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

    private static final String NEW_TABLES = "'library_setting', 'backup_run', 'backup_file', 'backup_problem'";
    private static final String SCHEMA = "SELECT type, name, tbl_name, sql FROM sqlite_master"
            + " WHERE tbl_name <> 'flyway_schema_history' AND tbl_name NOT IN (" + NEW_TABLES + ")"
            + " AND name NOT LIKE 'backup\\_%' ESCAPE '\\' ORDER BY type, name";

    @Test
    void version12KeepsEveryRowAndOnlyAddsTheBackupTables() throws Exception {
        version11Library();
        Database v11 = Database.open(dir.resolve(Database.FILE_NAME), "11");
        Map<String, List<String>> before = v11.read(BackupMigrationTest::dumpAll);
        List<String> schema = rows(v11, SCHEMA);
        assertThat(before.get("image")).hasSize(6);
        assertThat(before.get("export_item")).hasSize(2);
        assertThat(before).doesNotContainKeys("library_setting", "backup_run", "backup_file", "backup_problem");

        Database db = Database.open(dir.resolve(Database.FILE_NAME), "12");

        Map<String, List<String>> after = db.read(BackupMigrationTest::dumpAll);
        Map<String, List<String>> kept = new LinkedHashMap<>(after);
        kept.keySet().removeAll(List.of("library_setting", "backup_run", "backup_file", "backup_problem"));
        assertThat(kept).isEqualTo(before);
        assertThat(after.get("library_setting")).isEmpty();
        assertThat(after.get("backup_run")).isEmpty();
        assertThat(after.get("backup_file")).isEmpty();
        assertThat(after.get("backup_problem")).isEmpty();
        assertThat(rows(db, SCHEMA)).as("every older table, index and trigger as it was").isEqualTo(schema);
        assertThat(rows(db, "PRAGMA integrity_check")).containsExactly("ok");
        assertThat(rows(db, "PRAGMA foreign_key_check")).isEmpty();
        assertThat(rows(db, "SELECT version, success FROM flyway_schema_history ORDER BY installed_rank"))
                .last().isEqualTo("12|1");
    }

    @Test
    void theBackupTablesHoldWhatTheContractSays() throws Exception {
        version11Library();
        Database db = Database.open(dir.resolve(Database.FILE_NAME));
        db.write(c -> {
            try (Statement st = c.createStatement()) {
                st.executeUpdate("INSERT INTO library_setting (key, value) VALUES ('backup.reminder', 'less')");
                st.executeUpdate("INSERT INTO backup_run (state, what, dest_root, folder, total, bytes_total, started_at)"
                        + " VALUES ('running', 'everything', '/x', 'Butterfly_Atlas_backup_2026-10-21', 3, 3000,"
                        + " '2026-10-21T08:00:00Z')");
                st.executeUpdate("INSERT INTO backup_file (run_id, rel, size, mtime_ms) VALUES (1, 'atlas.db', 10, 5)");
                st.executeUpdate("INSERT INTO backup_problem (run_id, rel, reason, kind) VALUES (1, 'images/x/p1.jpg', 'gone',"
                        + " 'skipped')");
            }
            return null;
        });

        // one running or paused backup at a time, the states and kinds are fixed, and a run's rows go with it
        assertThatThrownBy(() -> db.write(c -> {
            try (Statement st = c.createStatement()) {
                st.executeUpdate("INSERT INTO backup_run (state, what, dest_root, folder, total, bytes_total, started_at)"
                        + " VALUES ('paused', 'database', '/x', 'b', 0, 0, '2026-10-21T08:00:00Z')");
            }
            return null;
        })).isInstanceOf(DatabaseException.class);
        assertThatThrownBy(() -> db.write(c -> {
            try (Statement st = c.createStatement()) {
                st.executeUpdate("INSERT INTO backup_run (state, what, dest_root, folder, total, bytes_total, started_at)"
                        + " VALUES ('done', 'photos', '/x', 'b', 0, 0, '2026-10-21T08:00:00Z')");
            }
            return null;
        })).isInstanceOf(DatabaseException.class);
        db.write(c -> {
            try (Statement st = c.createStatement()) {
                st.executeUpdate("UPDATE backup_run SET state = 'done'");
                st.executeUpdate("DELETE FROM backup_run");
            }
            return null;
        });
        assertThat(rows(db, "SELECT COUNT(*) FROM backup_file")).containsExactly("0");
        assertThat(rows(db, "SELECT COUNT(*) FROM backup_problem")).containsExactly("0");
        assertThat(rows(db, "SELECT value FROM library_setting WHERE key = 'backup.reminder'")).containsExactly("less");
    }
}
