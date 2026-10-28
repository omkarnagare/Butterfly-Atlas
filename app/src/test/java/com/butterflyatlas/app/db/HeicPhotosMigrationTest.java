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
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V14 (Phase 7b contract §7) only adds two columns: a populated version 13 library keeps every row of every table but
 * the new columns' values, the details failures it moves to the new version and the import items it reads again; open
 * imports read their 7a HEIC refusals again.
 */
class HeicPhotosMigrationTest {

    @TempDir
    Path dir;

    private static final String HEIC_REFUSAL = "HEIC photos aren''t supported yet. On an iPhone choose Settings → Camera"
            + " → Formats → Most Compatible, or export them as JPEG.";

    private void version13Library() throws SQLException {
        SQLiteConfig config = new SQLiteConfig();
        config.enforceForeignKeys(true);
        SQLiteDataSource v13 = new SQLiteDataSource(config);
        v13.setUrl("jdbc:sqlite:" + dir.resolve(Database.FILE_NAME).toAbsolutePath());
        Flyway.configure().dataSource(v13).locations("classpath:db/migration").target("13").load().migrate();
        try (Connection c = v13.getConnection(); Statement st = c.createStatement()) {
            st.executeUpdate("INSERT INTO taxon (id, parent_id, rank, name, common_name, group_key, source, ancestor_path,"
                    + " folder_name, sort_key, family_id, next_photo_number) VALUES"
                    + " ('G:butterflies', NULL, 'group', 'Butterflies', NULL, 'butterflies', 'system', '/G:butterflies/',"
                    + " 'Butterflies', 'butterflies', NULL, 1),"
                    + " ('A1', 'G:butterflies', 'family', 'Nymphalidae', 'Brush-footed', 'butterflies', 'official',"
                    + " '/G:butterflies/A1/', 'Nymphalidae', 'nymphalidae', NULL, 1),"
                    + " ('A5', 'A1', 'species', 'Danaus chrysippus', 'Plain Tiger', 'butterflies', 'official',"
                    + " '/G:butterflies/A1/A5/', 'Danaus_chrysippus_[A5]', 'danaus chrysippus', NULL, 9)");
            String[] names = {"p1.jpg", "p2.nef", "p3.png", "p4.x"};
            String[] types = {"'jpeg'", "'raw'", "'png'", "NULL"};
            for (int n = 1; n <= names.length; n++) {
                st.executeUpdate("INSERT INTO image (taxon_id, rel_path, file_name, number, original_name, file_size,"
                        + " modified_at, sha256, width, height, orientation, added_at, source, file_type)"
                        + " VALUES ('A5', 'images/x/" + names[n - 1] + "', '" + names[n - 1] + "', " + n + ", NULL, "
                        + (1000 + n) + ", '2026-10-0" + n + "T08:00:00Z', 'sha" + n + "', 400, 300, 1,"
                        + " '2026-10-0" + n + "T09:00:00Z', 'app', " + types[n - 1] + ")");
            }
            st.executeUpdate("INSERT INTO image_processing (image_id, consumer, version, status, error, done_at, attempts)"
                    + " VALUES (1, 'details', 2, 'failed', 'x', '2026-10-01T08:00:00Z', 3),"
                    + " (2, 'details', 2, 'failed', 'x', '2026-10-02T08:00:00Z', 3),"
                    + " (4, 'details', 2, 'failed', 'x', '2026-10-04T08:00:00Z', 3),"
                    + " (3, 'place', 1, 'done', NULL, '2026-10-03T08:00:00Z', 1)");
            st.executeUpdate("INSERT INTO import_run (root_path, state, created_at, updated_at, next_seq) VALUES"
                    + " ('/cards/open', 'open', '2026-10-20T08:00:00Z', '2026-10-20T08:00:00Z', 10),"
                    + " ('/cards/done', 'finished', '2026-10-19T08:00:00Z', '2026-10-19T08:00:00Z', 4)");
            st.executeUpdate("INSERT INTO import_item (run_id, rel_path, file_size, modified_at, read_state, problem, sha256,"
                    + " format, v, seq, file_type) VALUES"
                    + " (1, 'a.HEIC', 10, '2026-10-20T07:00:00Z', 'unsupported', '" + HEIC_REFUSAL + "', NULL, NULL, 'x', 3,"
                    + " NULL),"
                    + " (1, 'b.avif', 11, '2026-10-20T07:00:00Z', 'unsupported', 'AVIF photos aren''t supported yet.', NULL,"
                    + " NULL, NULL, 4, NULL),"
                    + " (1, 'c.jpg', 12, '2026-10-20T07:00:00Z', 'read', NULL, 'sc', 'JPEG', 'v1', 5, 'jpeg'),"
                    + " (2, 'd.heic', 13, '2026-10-19T07:00:00Z', 'unsupported', '" + HEIC_REFUSAL + "', NULL, NULL, NULL, 2,"
                    + " NULL)");
            st.executeUpdate("INSERT INTO export_run (dest_root, folder_name, what, layout, strip_location, place_depth,"
                    + " filter_query, species, state, total, copied, total_bytes, bytes_done, started_at, finished_at,"
                    + " raw_with_location, raw_count) VALUES ('/x/Backup', 'Butterfly_Atlas_export_2026-10-12', 'photos',"
                    + " 'species', 1, 'none', '', 1, 'done', 1, 1, 1001, 1001, '2026-10-12T08:00:00Z',"
                    + " '2026-10-12T08:01:00Z', 1, 1)");
            st.executeUpdate("INSERT INTO export_item (run_id, seq, image_id, taxon_id, file_name, target_rel, bytes, state,"
                    + " written_bytes, written_mtime, raw) VALUES (1, 1, 2, 'A5', 'p2.nef', 'a/p2.nef', 1002, 'done',"
                    + " 1002, 5, 1)");
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

    /** New columns, and the values V14 moves on (checked below), left out of the comparison of rows. */
    private static final Set<String> CHANGED = Set.of("import_run.next_seq", "image_processing.version",
            "export_run.heic_count", "export_run.accepted_kinds", "export_item.heic", "import_item.preview_failed");

    /** Every table but Flyway's, with every row as quote() of each column V13 had, in rowid order. */
    private static Map<String, List<String>> dumpOld(Connection c) throws SQLException {
        Map<String, List<String>> all = new LinkedHashMap<>();
        for (String table : rows(c, "SELECT name FROM sqlite_master WHERE type = 'table'"
                + " AND name <> 'flyway_schema_history' AND name NOT LIKE 'taxon\\_fts\\_%' ESCAPE '\\' ORDER BY name")) {
            List<String> columns = new ArrayList<>();
            for (String info : rows(c, "SELECT name FROM pragma_table_info('" + table + "')")) {
                if (!CHANGED.contains(table + "." + info)) {
                    columns.add("quote(\"" + info + "\")");
                }
            }
            String where = table.equals("import_item") ? " WHERE id <> 1" : ""; // read again (below)
            all.put(table, rows(c, "SELECT " + String.join(", ", columns) + " FROM \"" + table + "\"" + where
                    + " ORDER BY rowid"));
        }
        return all;
    }

    private static final String SCHEMA = "SELECT type, name, tbl_name FROM sqlite_master"
            + " WHERE tbl_name <> 'flyway_schema_history' ORDER BY type, name";

    @Test
    void version14KeepsEveryRowAndAddsTheHeicColumns() throws Exception {
        version13Library();
        Database v13 = Database.open(dir.resolve(Database.FILE_NAME), "13");
        Map<String, List<String>> before = v13.read(HeicPhotosMigrationTest::dumpOld);
        List<String> schema = rows(v13, SCHEMA);

        Database db = Database.open(dir.resolve(Database.FILE_NAME), "14");

        assertThat(db.read(HeicPhotosMigrationTest::dumpOld)).isEqualTo(before);
        assertThat(rows(db, SCHEMA)).as("every table and index as it was").isEqualTo(schema);
        // the details step's failures: JPEG and RAW keep theirs at the new version; a photo of unknown type is read again
        assertThat(rows(db, "SELECT image_id, consumer, version FROM image_processing ORDER BY rowid"))
                .containsExactly("1|details|3", "2|details|3", "4|details|2", "3|place|1");
        assertThat(rows(db, "SELECT heic_count, raw_with_location, raw_count FROM export_run")).containsExactly("0|1|1");
        assertThat(rows(db, "SELECT heic, raw FROM export_item")).containsExactly("0|1");
        assertThat(rows(db, "SELECT DISTINCT preview_failed FROM import_item")).containsExactly("0");
        assertThat(rows(db, "PRAGMA integrity_check")).containsExactly("ok");
        assertThat(rows(db, "PRAGMA foreign_key_check")).isEmpty();
        assertThat(rows(db, "SELECT version, success FROM flyway_schema_history ORDER BY installed_rank"))
                .last().isEqualTo("14|1");
    }

    @Test
    void openImportsReadTheirOldHeicRefusalsAgain() throws Exception {
        version13Library();

        Database db = Database.open(dir.resolve(Database.FILE_NAME), "14");

        assertThat(rows(db, "SELECT id, read_state, problem, v, seq FROM import_item ORDER BY id")).containsExactly(
                "1|pending|null|null|10",
                "2|unsupported|AVIF photos aren't supported yet.|null|4", "3|read|null|v1|5",
                "4|unsupported|" + HEIC_REFUSAL.replace("''", "'") + "|null|2"); // a finished import stays as it was
        assertThat(rows(db, "SELECT id, next_seq FROM import_run ORDER BY id")).containsExactly("1|11", "2|4");
    }

    @Test
    void theHeicMarkHoldsOnlyZeroOrOne() throws Exception {
        version13Library();
        Database db = Database.open(dir.resolve(Database.FILE_NAME), "14");

        assertThatThrownBy(() -> db.write(c -> {
            try (Statement st = c.createStatement()) {
                return st.executeUpdate("UPDATE export_item SET heic = 2");
            }
        })).rootCause().hasMessageContaining("CHECK");
        assertThatThrownBy(() -> db.write(c -> {
            try (Statement st = c.createStatement()) {
                return st.executeUpdate("UPDATE import_item SET preview_failed = 3");
            }
        })).rootCause().hasMessageContaining("CHECK");
    }
}
