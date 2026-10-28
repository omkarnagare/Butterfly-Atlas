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
 * V10 adds {@code place.replaced_by}, the table {@code place_update} and the index {@code change_log_kind}, nothing
 * else: a populated version 9 library keeps every row and id, its schema as it was but those, {@code replaced_by} is NULL
 * everywhere and {@code place_update} starts empty (5c contract §1, §10).
 */
class PlaceUpdatesMigrationTest {

    @TempDir
    Path dir;

    /** Every table a version 9 library holds data in, with the order its rows are compared in. */
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
        TABLES.put("export_run", "id");
        TABLES.put("export_item", "run_id, seq");
        TABLES.put("taxonomy_update", "id");
        TABLES.put("taxonomy_move", "update_id, step_version, seq");
        TABLES.put("custom_match", "custom_id, official_id, offered_version");
        TABLES.put("split_photo", "image_id");
        TABLES.put("sqlite_sequence", "name");
    }

    /**
     * A version 9 library: the V9 test's library (official and custom species, live, deleted and purged photos with
     * tags, places of either source, ledger rows, imports, a past export) plus a user place with photos looked up from
     * GPS and set by hand, import items with a GPS place, a user's choice of place and a removed one, two recent places,
     * an export filtered by places, an import's update note and a row in each of the taxonomy update tables.
     */
    private void version9Library() throws SQLException {
        SQLiteConfig config = new SQLiteConfig();
        config.enforceForeignKeys(true);
        SQLiteDataSource v9 = new SQLiteDataSource(config);
        v9.setUrl("jdbc:sqlite:" + dir.resolve(Database.FILE_NAME).toAbsolutePath());
        Flyway.configure().dataSource(v9).locations("classpath:db/migration").target("9").load().migrate();
        try (Connection c = v9.getConnection(); Statement st = c.createStatement()) {
            st.executeUpdate("INSERT INTO taxonomy_state (bundle_version, bundle_sha256, applied_at) VALUES ('2026-10-08',"
                    + " 'abc', '2026-10-09T08:00:00Z')");
            st.executeUpdate("INSERT INTO taxon (id, parent_id, rank, name, common_name, group_key, source, ancestor_path,"
                    + " folder_name, sort_key, family_id, next_photo_number) VALUES"
                    + " ('G:butterflies', NULL, 'group', 'Butterflies', NULL, 'butterflies', 'system', '/G:butterflies/',"
                    + " 'Butterflies', 'butterflies', NULL, 1),"
                    + " ('U:butterflies', 'G:butterflies', 'additions', 'Custom additions', NULL, 'butterflies', 'system',"
                    + " '/G:butterflies/U:butterflies/', 'Custom_additions', 'custom additions', NULL, 1),"
                    + " ('A1', 'G:butterflies', 'family', 'Nymphalidae', 'Brush-footed', 'butterflies', 'official',"
                    + " '/G:butterflies/A1/', 'Nymphalidae', 'nymphalidae', NULL, 1),"
                    + " ('A5', 'A1', 'species', 'Danaus chrysippus', 'Plain Tiger', 'butterflies', 'official',"
                    + " '/G:butterflies/A1/A5/', 'Danaus_chrysippus_[A5]', 'danaus chrysippus', NULL, 6),"
                    + " ('U1', 'U:butterflies', 'species', 'Neptis sankara', 'Broad-banded Sailer', 'butterflies', 'manual',"
                    + " '/G:butterflies/U:butterflies/U1/', 'Neptis_sankara_[U1]', 'neptis sankara', 'A1', 3)");
            st.executeUpdate("INSERT INTO taxon_synonym (taxon_id, name, source, added_at) VALUES ('A5', 'Danaida chrysippus',"
                    + " 'bundle', NULL), ('A5', 'tiger', 'user', '2026-10-09T09:00:00Z'),"
                    + " ('U1', 'sailer', 'user', '2026-10-14T09:00:00Z')");
            st.executeUpdate("INSERT INTO taxon_fts (taxon_id, kind, text) VALUES ('A5', 'name', 'Danaus chrysippus'),"
                    + " ('A5', 'common', 'Plain Tiger'), ('A5', 'synonym', 'Danaida chrysippus'), ('A5', 'word', 'tiger'),"
                    + " ('U1', 'name', 'Neptis sankara'), ('U1', 'common', 'Broad-banded Sailer'), ('U1', 'word', 'sailer')");
            st.executeUpdate("INSERT INTO place (id, parent_id, level, name, source, ancestor_path, sort_key, active) VALUES"
                    + " ('P1', NULL, 'country', 'India', 'bundled', '/P1/', 'india', 1),"
                    + " ('P2', 'P1', 'state', 'Maharashtra', 'bundled', '/P1/P2/', 'maharashtra', 1),"
                    + " ('P3', 'P2', 'district', 'Pune', 'bundled', '/P1/P2/P3/', 'pune', 1),"
                    + " ('PU1', 'P2', 'district', 'Tamhini', 'user', '/P1/P2/PU1/', 'tamhini', 1)");
            st.executeUpdate("INSERT INTO place_alias (place_id, name) VALUES ('P1', 'Bharat'), ('P3', 'Poona')");
            st.executeUpdate("INSERT INTO place_state (bundle_version, bundle_sha256, applied_at) VALUES ('2026-10-13', 'def',"
                    + " '2026-10-13T08:00:00Z')");
            st.executeUpdate("INSERT INTO place_recent (place_id, used_at) VALUES ('PU1', '2026-10-13T09:00:00Z')");
            for (int n = 1; n <= 5; n++) {
                st.executeUpdate("INSERT INTO image (taxon_id, rel_path, file_name, number, original_name, file_size, modified_at,"
                        + " sha256, width, height, orientation, added_at, source, place_id, place_source) VALUES ('A5',"
                        + " 'images/Nymphalidae/Danaus_chrysippus_[A5]/Danaus_chrysippus_0" + n + ".jpg',"
                        + " 'Danaus_chrysippus_0" + n + ".jpg', " + n + ", 'IMG_" + n + ".JPG', " + (1000 + n) + ","
                        + " '2026-10-0" + n + "T08:00:00Z', 'sha" + n + "', 400, 300, 1, '2026-10-0" + n + "T09:00:00Z', '"
                        + (n == 2 ? "import" : "app") + "', " + (n == 1 ? "'P3', 'gps'" : n == 3 ? "'PU1', 'user'"
                        : "NULL, NULL") + ")");
            }
            for (int n = 1; n <= 2; n++) {
                st.executeUpdate("INSERT INTO image (taxon_id, rel_path, file_name, number, original_name, file_size, modified_at,"
                        + " sha256, width, height, orientation, added_at, source) VALUES ('U1',"
                        + " 'images/Custom_additions/Neptis_sankara_[U1]/Neptis_sankara_0" + n + ".jpg',"
                        + " 'Neptis_sankara_0" + n + ".jpg', " + n + ", 'DSC_" + n + ".JPG', " + (2000 + n) + ","
                        + " '2026-10-1" + n + "T08:00:00Z', 'shaU" + n + "', 400, 300, 1, '2026-10-1" + n + "T09:00:00Z', 'app')");
            }
            st.executeUpdate("UPDATE image SET deleted_at = '2026-10-10T00:00:00Z',"
                    + " deleted_rel_path = '.deleted/4_Danaus_chrysippus_04.jpg' WHERE id = 4");
            st.executeUpdate("UPDATE image SET deleted_at = '2026-10-14T00:00:00Z',"
                    + " deleted_rel_path = '.deleted/7_Neptis_sankara_02.jpg' WHERE id = 7");
            st.executeUpdate("DELETE FROM image WHERE id = 5"); // purged: id 5 must never come back
            st.executeUpdate("INSERT INTO tag (key, label, source, sort_order) VALUES ('basking', 'Basking', 'user', 1000)");
            st.executeUpdate("INSERT INTO image_tag (image_id, tag_id) SELECT 1, id FROM tag WHERE key IN ('male', 'wsf')");
            st.executeUpdate("INSERT INTO image_tag (image_id, tag_id) SELECT 6, id FROM tag WHERE key IN ('unconfirmed', 'basking')");
            st.executeUpdate("UPDATE taxon SET cover_image_id = 2 WHERE id = 'A5'");
            st.executeUpdate("UPDATE taxon SET cover_image_id = 6 WHERE id = 'U1'");
            for (int id : new int[] {1, 2, 3, 4, 6, 7}) {
                st.executeUpdate("INSERT INTO image_meta (image_id, json, read_at) VALUES (" + id + ", '{\"v\":1}',"
                        + " '2026-10-11T00:00:00Z')");
                st.executeUpdate("INSERT INTO image_processing (image_id, consumer, version, status, error, done_at, attempts)"
                        + " VALUES (" + id + ", 'place', 1, 'done', NULL, '2026-10-11T00:00:00Z', 0)");
            }
            st.executeUpdate("INSERT INTO change_log (at, kind, payload_json) VALUES ('2026-10-11T00:00:00Z', 'photo.purge',"
                    + " '{\"id\":5}'), ('2026-10-12T00:00:00Z', 'place.add', '{\"id\":\"PU1\"}'),"
                    + " ('2026-10-14T00:00:00Z', 'taxon.add', '{\"id\":\"U1\",\"name\":\"Neptis sankara\"}')");
            st.executeUpdate("INSERT INTO import_run (root_path, state, created_at, updated_at, filings, species_before)"
                    + " VALUES ('/photos/2024', 'open', '2026-10-12T00:00:00Z', '2026-10-12T01:00:00Z', 1, 1)");
            st.executeUpdate("INSERT INTO import_run (root_path, state, created_at, updated_at, filings, stopped_phase,"
                    + " stopped_done, stopped_total, stopped_at) VALUES ('/photos/2025', 'open', '2026-10-13T00:00:00Z',"
                    + " '2026-10-13T01:00:00Z', 1, 'filing', 3, 9, '2026-10-13T02:00:00Z')");
            st.executeUpdate("INSERT INTO import_run (root_path, state, created_at, updated_at, filings) VALUES ('/photos/2023',"
                    + " 'finished', '2026-10-11T00:00:00Z', '2026-10-11T01:00:00Z', 0)");
            st.executeUpdate("INSERT INTO import_item (id, run_id, rel_path, file_size, modified_at, read_state, sha256, format,"
                    + " meta_json, v, taxon_id, outcome, image_id, filing_no, seq, gps_place_id) VALUES"
                    + " (1, 1, 'a.jpg', 1002, '2026-10-02T08:00:00Z', 'read', 'sha2', 'JPEG', '{\"v\":1}', 'sha2', 'A5',"
                    + " 'filed', 2, 1, 3, 'P3'),"
                    + " (2, 1, 'b.jpg', 10, '2026-10-02T08:00:00Z', 'read', 'shaB', 'JPEG', '{\"v\":1}', 'shaB', 'U1', NULL,"
                    + " NULL, NULL, 2, 'P3'),"
                    + " (3, 2, 'c.jpg', 10, '2026-10-02T08:00:00Z', 'read', 'shaC', 'JPEG', '{\"v\":1}', 'shaC', 'A5', NULL,"
                    + " NULL, NULL, 1, NULL)");
            st.executeUpdate("INSERT INTO import_item_tag (item_id, tag_id) SELECT 2, id FROM tag WHERE key = 'unconfirmed'");
            st.executeUpdate("INSERT INTO export_run (dest_root, folder_name, what, layout, strip_location, place_depth,"
                    + " filter_query, csv_name, species, state, total, copied, total_bytes, bytes_done, started_at, finished_at)"
                    + " VALUES ('/Volumes/Backup', 'Butterfly_Atlas_export_2026-10-14', 'both', 'species', 1, 'none', '',"
                    + " 'species-list.csv', 2, 'done', 2, 2, 3003, 3003, '2026-10-14T16:10:04Z', '2026-10-14T16:10:09Z')");
            st.executeUpdate("INSERT INTO export_item (run_id, seq, image_id, taxon_id, file_name, target_rel, bytes, state,"
                    + " written_bytes, written_mtime) VALUES"
                    + " (1, 1, 1, 'A5', 'Danaus_chrysippus_01.jpg', 'Nymphalidae/Danaus_chrysippus/Danaus_chrysippus_01.jpg',"
                    + " 1001, 'done', 900, 1760000000000),"
                    + " (1, 2, 6, 'U1', 'Neptis_sankara_01.jpg', 'Nymphalidae/Custom_additions/Neptis_sankara/Neptis_sankara_01.jpg',"
                    + " 2001, 'done', 1900, 1760000000000)");
            // What 5c must keep besides: a GPS place on an import item and a user's choice of place, a second recent
            // place, an export filtered by places, a user place with photos of either source, and the 5b tables.
            st.executeUpdate("UPDATE import_item SET user_place_id = 'PU1', user_place_set = 1 WHERE id = 3");
            st.executeUpdate("UPDATE import_item SET user_place_set = 1 WHERE id = 2"); // place removed by the user
            st.executeUpdate("UPDATE image SET place_id = 'PU1', place_source = 'gps' WHERE id = 6");
            st.executeUpdate("INSERT INTO place_recent (place_id, used_at) VALUES ('P3', '2026-10-14T09:00:00Z')");
            st.executeUpdate("INSERT INTO export_run (dest_root, folder_name, what, layout, strip_location, place_depth,"
                    + " filter_query, csv_name, species, state, total, copied, total_bytes, bytes_done, started_at, finished_at)"
                    + " VALUES ('/Volumes/Backup', 'Butterfly_Atlas_export_2026-10-15', 'photos', 'species', 0, 'district',"
                    + " 'places=P3,PU1,unknown', NULL, 1, 'done', 1, 1, 1001, 1001, '2026-10-15T16:10:04Z',"
                    + " '2026-10-15T16:10:09Z')");
            st.executeUpdate("UPDATE import_run SET update_note = '1 photo was assigned to Danaus chrysippus, which was split.'"
                    + " WHERE id = 2");
            st.executeUpdate("INSERT INTO taxonomy_update (kind, from_version, to_version, step_version, stage, state, total,"
                    + " done, started_at, finished_at) VALUES ('bundle', '2026-10-08', '2026-12-01', '2026-12-01', 'switch',"
                    + " 'done', 2, 2, '2026-12-02T08:00:00Z', '2026-12-02T08:01:00Z')");
            st.executeUpdate("INSERT INTO taxonomy_move (update_id, step_version, seq, image_id, old_rel, new_rel, new_file_name,"
                    + " done) VALUES (1, '2026-12-01', 1, 1, 'a', 'b', 'b', 1)");
            st.executeUpdate("INSERT INTO custom_match (custom_id, official_id, offered_version, state) VALUES ('U1', 'A5',"
                    + " '2026-12-01', 'declined')");
            st.executeUpdate("INSERT INTO split_photo (image_id, taxon_id, version) VALUES (1, 'A5', '2026-12-01')");
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


    private static final String FTS = "SELECT taxon_id, kind, text FROM taxon_fts ORDER BY taxon_id, kind, text";
    /** The schema of everything but place_update, place (it gains replaced_by), change_log's new index and Flyway's own. */
    private static final String SCHEMA = "SELECT type, name, tbl_name, sql FROM sqlite_master WHERE tbl_name NOT IN"
            + " ('place_update', 'place') AND name NOT IN ('flyway_schema_history', 'change_log_kind')"
            + " AND tbl_name <> 'flyway_schema_history' ORDER BY type, name";
    /** Everything of place's but its table (its indexes): V10 adds a column only. */
    private static final String PLACE_SCHEMA = "SELECT type, name, sql FROM sqlite_master WHERE tbl_name = 'place'"
            + " AND type <> 'table' ORDER BY type, name";
    private static final String TABLE_NAMES = "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite\\_%'"
            + " ESCAPE '\\' AND name <> 'flyway_schema_history' AND name NOT LIKE 'taxon\\_fts%' ESCAPE '\\' ORDER BY name";

    @Test
    void version10KeepsEveryRowAndIdOfAPopulatedVersion9LibraryAndAddsAnEmptyPlaceUpdateTable() throws Exception {
        version9Library();
        Map<String, List<String>> columns = new LinkedHashMap<>();
        Database v9 = Database.open(dir.resolve(Database.FILE_NAME), "9");
        List<String> schema = rows(v9, SCHEMA);
        List<String> placeSchema = rows(v9, PLACE_SCHEMA);
        List<String> fts = rows(v9, FTS);
        Map<String, List<String>> before = v9.read(c -> {
            for (String table : TABLES.keySet()) {
                columns.put(table, columns(c, table));
            }
            // A table added to the schema but not to TABLES would escape the comparison: fail here instead.
            assertThat(rows(c, TABLE_NAMES)).extracting(r -> r.substring("name=".length()))
                    .containsExactlyInAnyOrderElementsOf(TABLES.keySet().stream().filter(n -> !n.startsWith("sqlite_")).toList());
            return dumpAll(c, columns);
        });
        assertThat(before.get("image")).hasSize(6);
        assertThat(before.get("place")).hasSize(4);
        assertThat(before.get("place_recent")).hasSize(2);
        assertThat(before.get("import_item")).hasSize(3);
        assertThat(before.get("export_run")).hasSize(2);
        assertThat(before.get("split_photo")).hasSize(1);

        Database db = Database.open(dir.resolve(Database.FILE_NAME), "10");

        Map<String, List<String>> after = db.read(c -> dumpAll(c, columns));
        assertThat(after).isEqualTo(before);
        assertThat(rows(db, SCHEMA)).as("every table, index and trigger of version 9 as it was").isEqualTo(schema);
        assertThat(rows(db, PLACE_SCHEMA)).as("place's indexes as they were").isEqualTo(placeSchema);
        assertThat(rows(db, FTS)).isEqualTo(fts);
        List<String> place = db.read(c -> columns(c, "place"));
        List<String> expected = new ArrayList<>(columns.get("place"));
        expected.add("replaced_by");
        assertThat(place).as("place gains replaced_by, last").isEqualTo(expected);
        assertThat(rows(db, "SELECT COUNT(*) AS n FROM place WHERE replaced_by IS NOT NULL")).containsExactly("n=0");
        assertThat(rows(db, "SELECT COUNT(*) AS n FROM place_update")).containsExactly("n=0");
        List<String> updateColumns = db.read(c -> columns(c, "place_update"));
        assertThat(updateColumns).containsExactly("version", "from_version", "applied_at",
                "touches", "summary_json", "dismissed_at", "rechecked_at");
        assertThat(rows(db, "SELECT name, sql FROM sqlite_master WHERE type = 'index' AND name = 'change_log_kind'"))
                .containsExactly("name=change_log_kind|sql=CREATE INDEX change_log_kind ON change_log (kind)");
        assertThat(rows(db, "PRAGMA integrity_check")).containsExactly("integrity_check=ok");
        assertThat(rows(db, "PRAGMA foreign_key_check")).isEmpty();
        assertThat(rows(db, "SELECT version, success FROM flyway_schema_history ORDER BY installed_rank")).last()
                .isEqualTo("version=10|success=1");
        List<String> tables = new ArrayList<>(TABLES.keySet().stream().filter(n -> !n.startsWith("sqlite_")).toList());
        tables.add("place_update");
        assertThat(rows(db, TABLE_NAMES)).as("V10 adds place_update only").extracting(r -> r.substring("name=".length()))
                .containsExactlyInAnyOrderElementsOf(tables);
    }

    @Test
    void afterVersion10AStepIsRecordedOncePerVersionWithItsChecks() throws Exception {
        version9Library();
        Database db = Database.open(dir.resolve(Database.FILE_NAME));
        String step = "INSERT INTO place_update (version, from_version, applied_at, touches, summary_json) VALUES"
                + " (%s, '2026-10-13', '2026-10-20T08:00:00Z', %s, '{}')";

        exec(db, String.format(step, "'2026-10-20'", "1"));
        assertThatThrownBy(() -> exec(db, String.format(step, "'2026-10-20'", "0"))).as("one row per version")
                .isInstanceOf(DatabaseException.class);
        assertThatThrownBy(() -> exec(db, String.format(step, "'2026-10-21'", "2"))).isInstanceOf(DatabaseException.class);
        assertThatThrownBy(() -> exec(db, "INSERT INTO place_update (version, from_version, applied_at, touches) VALUES"
                + " ('2026-10-22', '2026-10-20', '2026-10-22T08:00:00Z', 0)")).as("a summary is required")
                .isInstanceOf(DatabaseException.class);
        exec(db, "UPDATE place SET active = 0, replaced_by = 'P2' WHERE id = 'P3'");
        exec(db, "UPDATE place SET replaced_by = 'P99' WHERE id = 'PU1'");
        assertThat(rows(db, "SELECT id, replaced_by FROM place WHERE replaced_by IS NOT NULL ORDER BY id"))
                .as("no foreign key: a survivor is any id").containsExactly("id=P3|replaced_by=P2", "id=PU1|replaced_by=P99");
        assertThat(rows(db, "PRAGMA foreign_key_check")).isEmpty();
    }
}
