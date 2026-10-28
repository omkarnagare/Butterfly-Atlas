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
 * V8 adds {@code taxon.family_id}, renames "My additions" and adds the built-in tag Unconfirmed: a populated version 7
 * library keeps every row and id but those, in each of the three cases of the tag (5a contract §1).
 */
class CustomAdditionsMigrationTest {

    @TempDir
    Path dir;

    /** Every table a version 7 library holds data in, with the order its rows are compared in. */
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
        TABLES.put("sqlite_sequence", "name");
    }

    /**
     * A version 7 library as 4b left it: the group and "My additions", a species with a synonym and search rows,
     * places with aliases and a user district, recently used places, live, deleted and purged photos with tags (built-in
     * and the user's), ledger rows, an open import with item tags, a stopped import, a finished one, a past export with
     * its items; then {@code tags} (SQL run last) sets up the user's tags of the case under test.
     */
    private void version7Library(String... tags) throws SQLException {
        SQLiteConfig config = new SQLiteConfig();
        config.enforceForeignKeys(true);
        SQLiteDataSource v7 = new SQLiteDataSource(config);
        v7.setUrl("jdbc:sqlite:" + dir.resolve(Database.FILE_NAME).toAbsolutePath());
        Flyway.configure().dataSource(v7).locations("classpath:db/migration").target("7").load().migrate();
        try (Connection c = v7.getConnection(); Statement st = c.createStatement()) {
            st.executeUpdate("INSERT INTO taxonomy_state (bundle_version, bundle_sha256, applied_at) VALUES ('2026-10-08',"
                    + " 'abc', '2026-10-09T08:00:00Z')");
            st.executeUpdate("INSERT INTO taxon (id, parent_id, rank, name, common_name, group_key, source, ancestor_path,"
                    + " folder_name, sort_key) VALUES"
                    + " ('G:butterflies', NULL, 'group', 'Butterflies', NULL, 'butterflies', 'system', '/G:butterflies/',"
                    + " 'Butterflies', 'butterflies'),"
                    + " ('U:butterflies', 'G:butterflies', 'additions', 'My additions', NULL, 'butterflies', 'system',"
                    + " '/G:butterflies/U:butterflies/', 'My_additions', 'my additions'),"
                    + " ('A1', 'G:butterflies', 'family', 'Nymphalidae', 'Brush-footed', 'butterflies', 'official',"
                    + " '/G:butterflies/A1/', 'Nymphalidae', 'nymphalidae'),"
                    + " ('A5', 'A1', 'species', 'Danaus chrysippus', 'Plain Tiger', 'butterflies', 'official',"
                    + " '/G:butterflies/A1/A5/', 'Danaus_chrysippus_[A5]', 'danaus chrysippus')");
            st.executeUpdate("INSERT INTO taxon_synonym (taxon_id, name, source, added_at) VALUES ('A5', 'Danaida chrysippus',"
                    + " 'bundle', NULL), ('A5', 'tiger', 'user', '2026-10-09T09:00:00Z')");
            st.executeUpdate("INSERT INTO taxon_fts (taxon_id, kind, text) VALUES ('A5', 'name', 'Danaus chrysippus'),"
                    + " ('A5', 'common', 'Plain Tiger'), ('A5', 'synonym', 'Danaida chrysippus'), ('A5', 'word', 'tiger')");
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
            st.executeUpdate("UPDATE image SET deleted_at = '2026-10-10T00:00:00Z',"
                    + " deleted_rel_path = '.deleted/4_Danaus_chrysippus_04.jpg' WHERE id = 4");
            st.executeUpdate("DELETE FROM image WHERE id = 5"); // purged: id 5 must never come back
            st.executeUpdate("INSERT INTO image_tag (image_id, tag_id) SELECT 1, id FROM tag WHERE key IN ('male', 'wsf')");
            st.executeUpdate("UPDATE taxon SET cover_image_id = 2, next_photo_number = 6 WHERE id = 'A5'");
            for (int id = 1; id <= 4; id++) {
                st.executeUpdate("INSERT INTO image_meta (image_id, json, read_at) VALUES (" + id + ", '{\"v\":1}',"
                        + " '2026-10-11T00:00:00Z')");
                st.executeUpdate("INSERT INTO image_processing (image_id, consumer, version, status, error, done_at, attempts)"
                        + " VALUES (" + id + ", 'place', 1, 'done', NULL, '2026-10-11T00:00:00Z', 0)");
            }
            st.executeUpdate("INSERT INTO change_log (at, kind, payload_json) VALUES ('2026-10-11T00:00:00Z', 'photo.purge',"
                    + " '{\"id\":5}'), ('2026-10-12T00:00:00Z', 'place.add', '{\"id\":\"PU1\"}')");
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
                    + " (2, 1, 'b.jpg', 10, '2026-10-02T08:00:00Z', 'read', 'shaB', 'JPEG', '{\"v\":1}', 'shaB', NULL, NULL,"
                    + " NULL, NULL, 2, 'P3'),"
                    + " (3, 2, 'c.jpg', 10, '2026-10-02T08:00:00Z', 'read', 'shaC', 'JPEG', '{\"v\":1}', 'shaC', 'A5', NULL,"
                    + " NULL, NULL, 1, NULL)");
            st.executeUpdate("INSERT INTO import_item_tag (item_id, tag_id) SELECT 2, id FROM tag WHERE key = 'female'");
            st.executeUpdate("INSERT INTO export_run (dest_root, folder_name, what, layout, strip_location, place_depth,"
                    + " filter_query, csv_name, species, state, total, copied, total_bytes, bytes_done, started_at, finished_at)"
                    + " VALUES ('/Volumes/Backup', 'Butterfly_Atlas_export_2026-10-13', 'both', 'species', 1, 'none', '',"
                    + " 'species-list.csv', 1, 'done', 2, 2, 2003, 2003, '2026-10-13T16:10:04Z', '2026-10-13T16:10:09Z')");
            st.executeUpdate("INSERT INTO export_item (run_id, seq, image_id, taxon_id, file_name, target_rel, bytes, state,"
                    + " written_bytes, written_mtime) VALUES"
                    + " (1, 1, 1, 'A5', 'Danaus_chrysippus_01.jpg', 'Nymphalidae/Danaus_chrysippus/Danaus_chrysippus_01.jpg',"
                    + " 1001, 'done', 900, 1760000000000),"
                    + " (1, 2, 2, 'A5', 'Danaus_chrysippus_02.jpg', 'Nymphalidae/Danaus_chrysippus/Danaus_chrysippus_02.jpg',"
                    + " 1002, 'done', 950, 1760000000000)");
            for (String sql : tags) {
                st.executeUpdate(sql);
            }
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

    /** The search index's rows (a virtual table: not in TABLES). */
    private static final String FTS = "SELECT taxon_id, kind, text FROM taxon_fts ORDER BY taxon_id, kind, text";
    /** The schema of everything but the taxon table (it gains a column) and Flyway's own. */
    private static final String SCHEMA = "SELECT type, name, tbl_name, sql FROM sqlite_master WHERE tbl_name <> 'taxon'"
            + " AND name <> 'flyway_schema_history' AND tbl_name <> 'flyway_schema_history' ORDER BY type, name";
    private static final String TAGS = "SELECT id, key, label, source, sort_order FROM tag ORDER BY id";

    /** What a version 7 library holds before V8, and the library after V8. */
    private record Migrated(Map<String, List<String>> before, Map<String, List<String>> after, List<String> schema,
                            List<String> fts, Database db) {
    }

    private Migrated migrate(String... tags) throws SQLException {
        version7Library(tags);
        Map<String, List<String>> columns = new LinkedHashMap<>();
        Database v7 = Database.open(dir.resolve(Database.FILE_NAME), "7");
        List<String> schema = rows(v7, SCHEMA);
        List<String> fts = rows(v7, FTS);
        Map<String, List<String>> before = v7.read(c -> {
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
        assertThat(before.get("import_run")).hasSize(3);
        assertThat(before.get("export_item")).hasSize(2);

        Database db = Database.open(dir.resolve(Database.FILE_NAME), "8");

        Map<String, List<String>> after = db.read(c -> dumpAll(c, columns));
        assertThat(rows(db, "PRAGMA integrity_check")).containsExactly("integrity_check=ok");
        assertThat(rows(db, "PRAGMA foreign_key_check")).isEmpty();
        assertThat(rows(db, "SELECT version, success FROM flyway_schema_history ORDER BY installed_rank")).containsExactly(
                "version=1|success=1", "version=2|success=1", "version=3|success=1", "version=4|success=1",
                "version=5|success=1", "version=6|success=1", "version=7|success=1", "version=8|success=1");
        return new Migrated(before, after, schema, fts, db);
    }

    /** Every table but {@code tag} as it was, the taxa but "My additions" too; the schema gains only family_id. */
    private static void everythingElseKept(Migrated m) {
        for (String table : TABLES.keySet()) {
            if (table.equals("tag") || table.equals("taxon")) {
                continue;
            }
            assertThat(m.after().get(table)).as(table).isEqualTo(m.before().get(table));
        }
        assertThat(m.after().get("taxon")).as("only My additions changes").hasSameSizeAs(m.before().get("taxon"));
        for (int i = 0; i < m.before().get("taxon").size(); i++) {
            String row = m.before().get("taxon").get(i);
            if (!row.contains("'U:butterflies'|parent_id")) {
                assertThat(m.after().get("taxon").get(i)).isEqualTo(row);
            }
        }
        assertThat(rows(m.db(), SCHEMA)).as("every other table, index and trigger as it was").isEqualTo(m.schema());
        assertThat(rows(m.db(), FTS)).isEqualTo(m.fts());
        assertThat(rows(m.db(), "SELECT id, family_id FROM taxon ORDER BY id")).allMatch(r -> r.endsWith("|family_id=null"));
        assertThat(rows(m.db(), "SELECT name, folder_name, sort_key, rank, parent_id, ancestor_path, source FROM taxon"
                + " WHERE id = 'U:butterflies'")).containsExactly("name=Custom additions|folder_name=Custom_additions"
                + "|sort_key=custom additions|rank=additions|parent_id=G:butterflies|ancestor_path=/G:butterflies/U:butterflies/"
                + "|source=system");
    }

    @Test
    void withoutAUserTagOfThatNameUnconfirmedIsAddedAfterWsf() throws Exception {
        Migrated m = migrate("INSERT INTO tag (key, label, source, sort_order) VALUES ('basking', 'Basking', 'user', 1000)");

        everythingElseKept(m);
        List<String> tags = new ArrayList<>(m.before().get("tag"));
        tags.add("id=11|key='unconfirmed'|label='Unconfirmed'|source='builtin'|sort_order=10");
        assertThat(m.after().get("tag")).as("every tag as it was, and the new one").containsExactlyInAnyOrderElementsOf(tags);
        assertThat(rows(m.db(), TAGS + " DESC LIMIT 1")).containsExactly(
                "id=11|key=unconfirmed|label=Unconfirmed|source=builtin|sort_order=10");
        assertThat(rows(m.db(), "SELECT t.key FROM tag t ORDER BY t.source = 'user', t.sort_order, t.label COLLATE NOCASE"))
                .extracting(r -> r.substring("key=".length())).containsExactly("egg", "larva", "pupa", "upper_wing",
                        "under_wing", "male", "female", "dsf", "wsf", "unconfirmed", "basking");
    }

    @Test
    void aUserTagCalledUnconfirmedBecomesTheBuiltInAndKeepsItsPhotosAndItems() throws Exception {
        Migrated m = migrate("INSERT INTO tag (key, label, source, sort_order) VALUES ('unconfirmed', 'unConfirmed', 'user', 1000)",
                "INSERT INTO image_tag (image_id, tag_id) VALUES (1, 10), (4, 10)",
                "INSERT INTO import_item_tag (item_id, tag_id) VALUES (3, 10)");

        everythingElseKept(m);
        assertThat(m.after().get("tag")).as("no tag added, the others as they were").hasSameSizeAs(m.before().get("tag"))
                .containsAll(m.before().get("tag").stream().filter(r -> !r.startsWith("id=10|")).toList());
        assertThat(rows(m.db(), TAGS + " DESC LIMIT 1")).as("same row, label kept").containsExactly(
                "id=10|key=unconfirmed|label=unConfirmed|source=builtin|sort_order=10");
        assertThat(rows(m.db(), "SELECT image_id FROM image_tag WHERE tag_id = 10 ORDER BY image_id"))
                .containsExactly("image_id=1", "image_id=4");
        assertThat(rows(m.db(), "SELECT item_id FROM import_item_tag WHERE tag_id = 10")).containsExactly("item_id=3");
    }

    @Test
    void aRenamedTagGivesUpTheKeyAndTheTagNowCalledUnconfirmedBecomesTheBuiltIn() throws Exception {
        // "Unconfirmed" was made (key unconfirmed), renamed "Doubtful", and "UNCONFIRMED" made again (key unconfirmed-2).
        Migrated m = migrate("INSERT INTO tag (key, label, source, sort_order) VALUES ('unconfirmed', 'Doubtful', 'user', 1000),"
                        + " ('unconfirmed-2', 'UNCONFIRMED', 'user', 1000)",
                "INSERT INTO image_tag (image_id, tag_id) VALUES (1, 10), (2, 11)");

        everythingElseKept(m);
        assertThat(m.after().get("tag")).hasSameSizeAs(m.before().get("tag"))
                .containsAll(m.before().get("tag").stream().filter(r -> !r.startsWith("id=10|") && !r.startsWith("id=11|")).toList());
        assertThat(rows(m.db(), TAGS + " DESC LIMIT 2")).containsExactly(
                "id=11|key=unconfirmed|label=UNCONFIRMED|source=builtin|sort_order=10",
                "id=10|key=unconfirmed-10|label=Doubtful|source=user|sort_order=1000");
        assertThat(rows(m.db(), "SELECT image_id, tag_id FROM image_tag WHERE tag_id >= 10 ORDER BY tag_id"))
                .containsExactly("image_id=1|tag_id=10", "image_id=2|tag_id=11");
    }

    @Test
    void aRenamedTagAloneGivesUpTheKeyAndUnconfirmedIsAdded() throws Exception {
        Migrated m = migrate("INSERT INTO tag (key, label, source, sort_order) VALUES ('unconfirmed', 'Doubtful', 'user', 1000),"
                + " ('unconfirmed-10', 'Odd', 'user', 1000)");

        everythingElseKept(m);
        assertThat(m.after().get("tag")).hasSize(m.before().get("tag").size() + 1)
                .containsAll(m.before().get("tag").stream().filter(r -> !r.startsWith("id=10|")).toList());
        assertThat(rows(m.db(), TAGS + " DESC LIMIT 3")).as("a key already taken is not reused").containsExactly(
                "id=12|key=unconfirmed|label=Unconfirmed|source=builtin|sort_order=10",
                "id=11|key=unconfirmed-10|label=Odd|source=user|sort_order=1000",
                "id=10|key=unconfirmed-10-v8|label=Doubtful|source=user|sort_order=1000");
    }

    @Test
    void aBuiltInRelabelledUnconfirmedKeepsItsLabelAndTheNewBuiltInIsNamedApart() throws Exception {
        Migrated m = migrate("UPDATE tag SET label = 'Unconfirmed' WHERE key = 'dsf'",
                "INSERT INTO image_tag (image_id, tag_id) SELECT 2, id FROM tag WHERE key = 'dsf'");

        everythingElseKept(m);
        assertThat(m.after().get("tag")).hasSize(m.before().get("tag").size() + 1).containsAll(m.before().get("tag"));
        assertThat(rows(m.db(), TAGS + " DESC LIMIT 1")).containsExactly(
                "id=10|key=unconfirmed|label=Unconfirmed (built-in)|source=builtin|sort_order=10");
        assertThat(rows(m.db(), "SELECT key, label FROM tag WHERE key = 'dsf'")).containsExactly("key=dsf|label=Unconfirmed");
        assertThat(rows(m.db(), "SELECT lower(label) AS l, COUNT(*) AS n FROM tag GROUP BY lower(label) HAVING n > 1"))
                .as("labels unique without case").isEmpty();
    }
}
