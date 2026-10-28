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
 * V13 (contract §6) only adds columns and two indexes: a populated version 12 library keeps every row of every table but
 * the new columns' values and the import items it reads again, every photo gets its file type from its name (or its
 * stored details), and open imports read their old RAW and HEIC refusals again.
 */
class RawPhotosMigrationTest {

    @TempDir
    Path dir;

    private static final String RAW_REFUSAL = "RAW photos aren''t supported yet.";
    private static final String HEIC_REFUSAL = "HEIC photos aren''t supported yet.";

    private void version12Library() throws SQLException {
        SQLiteConfig config = new SQLiteConfig();
        config.enforceForeignKeys(true);
        SQLiteDataSource v12 = new SQLiteDataSource(config);
        v12.setUrl("jdbc:sqlite:" + dir.resolve(Database.FILE_NAME).toAbsolutePath());
        Flyway.configure().dataSource(v12).locations("classpath:db/migration").target("12").load().migrate();
        try (Connection c = v12.getConnection(); Statement st = c.createStatement()) {
            st.executeUpdate("INSERT INTO taxon (id, parent_id, rank, name, common_name, group_key, source, ancestor_path,"
                    + " folder_name, sort_key, family_id, next_photo_number) VALUES"
                    + " ('G:butterflies', NULL, 'group', 'Butterflies', NULL, 'butterflies', 'system', '/G:butterflies/',"
                    + " 'Butterflies', 'butterflies', NULL, 1),"
                    + " ('A1', 'G:butterflies', 'family', 'Nymphalidae', 'Brush-footed', 'butterflies', 'official',"
                    + " '/G:butterflies/A1/', 'Nymphalidae', 'nymphalidae', NULL, 1),"
                    + " ('A5', 'A1', 'species', 'Danaus chrysippus', 'Plain Tiger', 'butterflies', 'official',"
                    + " '/G:butterflies/A1/A5/', 'Danaus_chrysippus_[A5]', 'danaus chrysippus', NULL, 9)");
            String[] names = {"p1.jpg", "p2.JPEG", "p3.png", "p4.TIF", "p5.tiff", "p6.webp", "p7.bmp", "p8.dat", "p9.x"};
            for (int n = 1; n <= names.length; n++) {
                st.executeUpdate("INSERT INTO image (taxon_id, rel_path, file_name, number, original_name, file_size,"
                        + " modified_at, sha256, width, height, orientation, added_at, source)"
                        + " VALUES ('A5', 'images/x/" + names[n - 1] + "', '" + names[n - 1] + "', " + n + ", NULL, "
                        + (1000 + n) + ", '2026-10-0" + n + "T08:00:00Z', 'sha" + n + "', 400, 300, 1,"
                        + " '2026-10-0" + n + "T09:00:00Z', 'app')");
            }
            st.executeUpdate("UPDATE image SET deleted_at = '2026-10-10T00:00:00Z', deleted_rel_path = '.deleted/5_p5.tiff'"
                    + " WHERE id = 5");
            // p7.bmp's details say PNG; p8.dat's details cannot be parsed; p9.x has none
            st.executeUpdate("INSERT INTO image_meta (image_id, json, read_at) VALUES"
                    + " (1, '{\"v\":1,\"format\":\"JPEG\"}', '2026-10-01T08:00:00Z'),"
                    + " (7, '{\"v\":1,\"format\":\"PNG\"}', '2026-10-07T08:00:00Z'),"
                    + " (8, 'not json', '2026-10-08T08:00:00Z')");
            st.executeUpdate("INSERT INTO image_processing (image_id, consumer, version, status, error, done_at, attempts)"
                    + " VALUES (3, 'details', 1, 'failed', 'x', '2026-10-03T08:00:00Z', 3),"
                    + " (9, 'details', 1, 'failed', 'x', '2026-10-09T08:00:00Z', 3),"
                    + " (3, 'place', 1, 'done', NULL, '2026-10-03T08:00:00Z', 1)");
            st.executeUpdate("INSERT INTO import_run (root_path, state, created_at, updated_at, next_seq) VALUES"
                    + " ('/cards/open', 'open', '2026-10-20T08:00:00Z', '2026-10-20T08:00:00Z', 10),"
                    + " ('/cards/done', 'finished', '2026-10-19T08:00:00Z', '2026-10-19T08:00:00Z', 4),"
                    + " ('/cards/other', 'open', '2026-10-21T08:00:00Z', '2026-10-21T08:00:00Z', 3)");
            st.executeUpdate("INSERT INTO import_item (run_id, rel_path, file_size, modified_at, read_state, problem, sha256,"
                    + " format, v, seq) VALUES"
                    + " (1, 'a.NEF', 10, '2026-10-20T07:00:00Z', 'unsupported', '" + RAW_REFUSAL + "', NULL, NULL, NULL, 3),"
                    + " (1, 'b.HEIC', 11, '2026-10-20T07:00:00Z', 'unsupported', '" + HEIC_REFUSAL + "', NULL, NULL, 'x1', 4),"
                    + " (1, 'c.mp4', 12, '2026-10-20T07:00:00Z', 'unsupported', 'Videos can''t be imported.', NULL, NULL,"
                    + " NULL, 5),"
                    + " (1, 'd.jpg', 13, '2026-10-20T07:00:00Z', 'read', NULL, 'sd', 'JPEG', 'v1', 6),"
                    + " (1, 'e.tif', 14, '2026-10-20T07:00:00Z', 'read', NULL, 'se', 'TIFF', 'v2', 7),"
                    + " (2, 'f.CR2', 15, '2026-10-19T07:00:00Z', 'unsupported', '" + RAW_REFUSAL + "', NULL, NULL, NULL, 2),"
                    + " (3, 'g.png', 16, '2026-10-21T07:00:00Z', 'read', NULL, 'sg', 'PNG', 'v3', 2)");
            st.executeUpdate("INSERT INTO export_run (dest_root, folder_name, what, layout, strip_location, place_depth,"
                    + " filter_query, species, state, total, copied, total_bytes, bytes_done, started_at, finished_at)"
                    + " VALUES ('/x/Backup', 'Butterfly_Atlas_export_2026-10-12', 'photos', 'species', 1, 'none', '', 1,"
                    + " 'done', 1, 1, 1001, 1001, '2026-10-12T08:00:00Z', '2026-10-12T08:01:00Z')");
            st.executeUpdate("INSERT INTO export_item (run_id, seq, image_id, taxon_id, file_name, target_rel, bytes, state,"
                    + " written_bytes, written_mtime) VALUES (1, 1, 1, 'A5', 'p1.jpg', 'a/p1.jpg', 1001, 'done', 1001, 5)");
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

    /** New columns, and the change stamp V13 moves on (checked below), left out of the comparison of rows. */
    private static final Set<String> NEW_COLUMNS = Set.of("import_run.next_seq", "image_processing.version", "image.file_type", "import_run.pair_choice", "import_item.file_type",
            "import_item.pair_item_id", "import_item.pair_left", "export_run.raw_with_location", "export_run.raw_count",
            "export_item.raw");

    /** Every table but Flyway's, with every row as quote() of each column V12 had, in rowid order. */
    private static Map<String, List<String>> dumpOld(Connection c) throws SQLException {
        Map<String, List<String>> all = new LinkedHashMap<>();
        for (String table : rows(c, "SELECT name FROM sqlite_master WHERE type = 'table'"
                + " AND name <> 'flyway_schema_history' AND name NOT LIKE 'taxon\\_fts\\_%' ESCAPE '\\' ORDER BY name")) {
            List<String> columns = new ArrayList<>();
            for (String info : rows(c, "SELECT name FROM pragma_table_info('" + table + "')")) {
                if (!NEW_COLUMNS.contains(table + "." + info)) {
                    columns.add("quote(\"" + info + "\")");
                }
            }
            String where = table.equals("import_item") ? " WHERE id NOT IN (1, 2)" : ""; // read again (below)
            all.put(table, rows(c, "SELECT " + String.join(", ", columns) + " FROM \"" + table + "\"" + where
                    + " ORDER BY rowid"));
        }
        return all;
    }

    private static final String SCHEMA = "SELECT type, name, tbl_name FROM sqlite_master"
            + " WHERE tbl_name <> 'flyway_schema_history' AND name NOT IN ('image_live_type', 'import_item_pair')"
            + " ORDER BY type, name";

    @Test
    void version13KeepsEveryRowAndAddsTheFileTypes() throws Exception {
        version12Library();
        Database v12 = Database.open(dir.resolve(Database.FILE_NAME), "12");
        Map<String, List<String>> before = v12.read(RawPhotosMigrationTest::dumpOld);
        List<String> schema = rows(v12, SCHEMA);

        Database db = Database.open(dir.resolve(Database.FILE_NAME), "13");

        assertThat(db.read(RawPhotosMigrationTest::dumpOld)).isEqualTo(before);
        assertThat(rows(db, SCHEMA)).as("every table and index as it was, but the two new indexes").isEqualTo(schema);
        assertThat(rows(db, "SELECT id, file_type FROM image ORDER BY id")).containsExactly(
                "1|jpeg", "2|jpeg", "3|png", "4|tiff", "5|tiff", "6|webp", "7|png", "8|null", "9|null");
        // the details step's failures: a JPEG keeps its failure at the new version, a photo of unknown type is read again
        assertThat(rows(db, "SELECT image_id, consumer, version FROM image_processing ORDER BY rowid"))
                .containsExactly("3|details|2", "9|details|1", "3|place|1");
        assertThat(rows(db, "SELECT sql FROM sqlite_master WHERE name = 'image_live_type'")).containsExactly(
                "CREATE INDEX image_live_type ON image (file_type, taxon_id) WHERE deleted_at IS NULL");
        assertThat(rows(db, "SELECT id, file_type, pair_item_id, pair_left FROM import_item ORDER BY id")).containsExactly(
                "1|null|null|0", "2|null|null|0", "3|null|null|0", "4|jpeg|null|0", "5|tiff|null|0", "6|null|null|0",
                "7|png|null|0");
        assertThat(rows(db, "SELECT id, pair_choice FROM import_run ORDER BY id"))
                .containsExactly("1|null", "2|null", "3|null");
        assertThat(rows(db, "SELECT raw_with_location, raw_count FROM export_run")).containsExactly("0|0");
        assertThat(rows(db, "SELECT raw FROM export_item")).containsExactly("0");
        assertThat(rows(db, "PRAGMA integrity_check")).containsExactly("ok");
        assertThat(rows(db, "PRAGMA foreign_key_check")).isEmpty();
        assertThat(rows(db, "SELECT version, success FROM flyway_schema_history ORDER BY installed_rank"))
                .last().isEqualTo("13|1");
    }

    @Test
    void openImportsReadTheirOldRawAndHeicRefusalsAgain() throws Exception {
        version12Library();

        Database db = Database.open(dir.resolve(Database.FILE_NAME), "13");

        // pending again, with no problem and no preview key, stamped with a new change of their import
        assertThat(rows(db, "SELECT id, read_state, problem, v, seq FROM import_item ORDER BY id")).containsExactly(
                "1|pending|null|null|10", "2|pending|null|null|10",
                "3|unsupported|Videos can't be imported.|null|5", "4|read|null|v1|6", "5|read|null|v2|7",
                "6|unsupported|RAW photos aren't supported yet.|null|2", // a finished import stays as it was
                "7|read|null|v3|2");
        assertThat(rows(db, "SELECT id, next_seq FROM import_run ORDER BY id")).containsExactly("1|11", "2|4", "3|3");
    }

    @Test
    void theNewColumnsHoldOnlyWhatTheContractSays() throws Exception {
        version12Library();
        Database db = Database.open(dir.resolve(Database.FILE_NAME), "13");

        assertThatThrownBy(() -> db.write(c -> {
            try (Statement st = c.createStatement()) {
                return st.executeUpdate("UPDATE import_run SET pair_choice = 'jpg' WHERE id = 1");
            }
        })).rootCause().hasMessageContaining("CHECK");
        assertThatThrownBy(() -> db.write(c -> {
            try (Statement st = c.createStatement()) {
                return st.executeUpdate("UPDATE import_item SET pair_left = 2 WHERE id = 4");
            }
        })).rootCause().hasMessageContaining("CHECK");
        assertThatThrownBy(() -> db.write(c -> {
            try (Statement st = c.createStatement()) {
                return st.executeUpdate("UPDATE export_run SET raw_with_location = 2");
            }
        })).rootCause().hasMessageContaining("CHECK");
        // a pair's other item going away clears the link
        db.write(c -> {
            try (Statement st = c.createStatement()) {
                st.executeUpdate("UPDATE import_item SET pair_item_id = 5 WHERE id = 4");
                st.executeUpdate("UPDATE import_item SET pair_item_id = 4 WHERE id = 5");
                return st.executeUpdate("DELETE FROM import_item WHERE id = 5");
            }
        });
        assertThat(rows(db, "SELECT pair_item_id FROM import_item WHERE id = 4")).containsExactly("null");
    }
}
