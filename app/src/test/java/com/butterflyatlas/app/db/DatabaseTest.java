package com.butterflyatlas.app.db;

import com.butterflyatlas.app.AtlasException;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatabaseTest {

    @TempDir
    Path dir;

    private Database open() {
        return Database.open(dir.resolve(Database.FILE_NAME));
    }

    private static void insertTaxon(Connection c, String id) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.executeUpdate("INSERT INTO taxon (id, parent_id, rank, name, group_key, source, ancestor_path, folder_name, sort_key)"
                    + " VALUES ('" + id + "', NULL, 'group', 'Butterflies', 'butterflies', 'system', '/" + id + "/', 'Butterflies', 'butterflies')");
        }
    }

    private static List<String> strings(Connection c, String sql) throws SQLException {
        List<String> values = new ArrayList<>();
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                values.add(rs.getString(1));
            }
        }
        return values;
    }

    private static List<String> query(Database db, String sql) {
        return db.read(c -> strings(c, sql));
    }

    @Test
    void aSnapshotSeesOneStateWhileAnotherConnectionCommits() {
        Database db = open();
        db.write(c -> {
            insertTaxon(c, "G1");
            return null;
        });

        List<List<String>> seen = db.snapshot(c -> {
            List<String> before = strings(c, "SELECT id FROM taxon ORDER BY id");
            db.write(other -> {
                insertTaxon(other, "G2");
                return null;
            });
            return List.of(before, strings(c, "SELECT id FROM taxon ORDER BY id"));
        });

        assertThat(seen.get(1)).as("the second statement sees the state of the first").isEqualTo(seen.get(0))
                .containsExactly("G1");
        assertThat(query(db, "SELECT id FROM taxon ORDER BY id")).containsExactly("G1", "G2");
    }

    @Test
    void aSnapshotDoesNotTakeTheWriteLock() {
        Database db = open();
        boolean[] writable = {false};

        db.snapshot(c -> {
            strings(c, "SELECT id FROM taxon");
            try (Connection other = java.sql.DriverManager.getConnection("jdbc:sqlite:" + dir.resolve(Database.FILE_NAME));
                 Statement st = other.createStatement()) {
                st.execute("PRAGMA busy_timeout = 0");
                st.execute("BEGIN IMMEDIATE");
                st.execute("ROLLBACK");
                writable[0] = true;
            }
            return null;
        });

        assertThat(writable[0]).as("another connection could take the write lock during the snapshot").isTrue();
    }

    @Test
    void aFailedSnapshotRethrowsWhatItsWorkThrew() {
        Database db = open();

        assertThatThrownBy(() -> db.snapshot(c -> {
            strings(c, "SELECT id FROM taxon");
            throw new IllegalStateException("fails");
        })).isInstanceOf(IllegalStateException.class);
        db.write(c -> {
            insertTaxon(c, "G1");
            return null;
        });

        assertThat(query(db, "SELECT id FROM taxon")).containsExactly("G1");
    }

    @Test
    void undoRunsWhileTheFailedTransactionStillHoldsTheWriteLock() throws Exception {
        Database db = open();
        boolean[] lockedDuringUndo = {false};

        assertThatThrownBy(() -> db.write(c -> {
            insertTaxon(c, "G1");
            throw new IllegalStateException("fails");
        }, () -> {
            try (Connection other = java.sql.DriverManager.getConnection("jdbc:sqlite:" + dir.resolve(Database.FILE_NAME));
                 Statement st = other.createStatement()) {
                st.execute("PRAGMA busy_timeout = 0");
                st.execute("BEGIN IMMEDIATE");
            } catch (SQLException e) {
                lockedDuringUndo[0] = true;
            }
        })).isInstanceOf(IllegalStateException.class);

        assertThat(lockedDuringUndo[0]).as("undo ran before the lock was released").isTrue();
    }

    @Test
    void createsTheSchema() {
        Database db = open();

        List<String> tables = db.read(c -> strings(c, "SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name"));

        assertThat(tables).contains("taxon", "taxon_synonym", "taxon_fts", "image", "tag", "image_tag", "taxonomy_state",
                "change_log", "image_meta", "image_processing", "flyway_schema_history");
    }

    @Test
    void fullTextSearchMatchesPrefixesAndIgnoresAccents() {
        Database db = open();
        db.write(c -> {
            try (Statement st = c.createStatement()) {
                st.executeUpdate("INSERT INTO taxon_fts (taxon_id, kind, text) VALUES ('A1', 'common', 'Common Peacock')");
                st.executeUpdate("INSERT INTO taxon_fts (taxon_id, kind, text) VALUES ('A2', 'common', 'Café Oakblue')");
            }
            return null;
        });

        assertThat(query(db, "SELECT taxon_id FROM taxon_fts WHERE taxon_fts MATCH '\"peaco\"*'"))
                .containsExactly("A1");
        assertThat(query(db, "SELECT taxon_id FROM taxon_fts WHERE taxon_fts MATCH '\"cafe\"*'"))
                .containsExactly("A2");
    }

    @Test
    void enforcesForeignKeys() {
        Database db = open();

        assertThatThrownBy(() -> db.write(c -> {
            insertTaxon(c, "G:butterflies");
            try (Statement st = c.createStatement()) {
                st.executeUpdate("INSERT INTO image (taxon_id, rel_path, file_name, file_size, modified_at, sha256, added_at, source)"
                        + " VALUES ('A404', 'x.jpg', 'x.jpg', 1, '2026-10-09T00:00:00Z', 'abc', '2026-10-09T00:00:00Z', 'app')");
            }
            return null;
        })).isInstanceOf(DatabaseException.class);

        assertThat(query(db, "SELECT id FROM taxon")).isEmpty();
    }

    @Test
    void coreErrorsFromTheWorkArePassedThroughUnwrapped() {
        Database db = open();
        AtlasException problem = AtlasException.notFound("No such taxon.");

        assertThatThrownBy(() -> db.write(c -> {
            throw problem;
        })).isSameAs(problem);
    }

    @Test
    void reopeningKeepsTheDataAndAppliesEachMigrationOnce() {
        open().write(c -> {
            insertTaxon(c, "G:butterflies");
            return null;
        });

        Database again = open();

        assertThat(query(again, "SELECT id FROM taxon")).containsExactly("G:butterflies");
        assertThat(query(again, "SELECT version FROM flyway_schema_history WHERE success = 1"))
                .containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12", "13", "14");
    }

    @Test
    void seedsTheBuiltInTagsInOrder() {
        Database db = open();

        assertThat(query(db, "SELECT key || '|' || label || '|' || source FROM tag ORDER BY sort_order")).containsExactly(
                "egg|egg|builtin", "larva|larva|builtin", "pupa|pupa|builtin", "upper_wing|upper wing|builtin",
                "under_wing|under wing|builtin", "male|male|builtin", "female|female|builtin", "dsf|DSF|builtin",
                "wsf|WSF|builtin", "unconfirmed|Unconfirmed|builtin");
    }

    @Test
    void aDeletedPhotoFreesItsPathForANewOne() {
        Database db = open();
        String insert = "INSERT INTO image (taxon_id, rel_path, file_name, file_size, modified_at, sha256, added_at, source,"
                + " deleted_at) VALUES ('G:butterflies', 'images/a.jpg', 'a.jpg', 1, 't', 's', 't', 'app', %s)";
        db.write(c -> {
            insertTaxon(c, "G:butterflies");
            try (Statement st = c.createStatement()) {
                st.executeUpdate(String.format(insert, "'2026-10-11T00:00:00Z'"));
                st.executeUpdate(String.format(insert, "NULL"));
            }
            return null;
        });

        assertThatThrownBy(() -> db.write(c -> {
            try (Statement st = c.createStatement()) {
                st.executeUpdate(String.format(insert, "NULL"));
            }
            return null;
        })).isInstanceOf(DatabaseException.class);
        assertThat(query(db, "SELECT COUNT(*) FROM image")).containsExactly("2");
    }

    @Test
    void version2UnderscoresTheFolderNamesOfAnExistingLibrary() {
        Path file = dir.resolve(Database.FILE_NAME);
        SQLiteDataSource v1 = new SQLiteDataSource();
        v1.setUrl("jdbc:sqlite:" + file.toAbsolutePath());
        Flyway.configure().dataSource(v1).locations("classpath:db/migration").target("1").load().migrate();
        try (Connection c = v1.getConnection(); Statement st = c.createStatement()) {
            st.executeUpdate("INSERT INTO taxon (id, parent_id, rank, name, group_key, source, ancestor_path, folder_name, sort_key)"
                    + " VALUES ('A5', NULL, 'species', 'Danaus chrysippus', 'butterflies', 'official', '/A5/',"
                    + " 'Danaus chrysippus [A5]', 'danaus chrysippus')");
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }

        Database db = Database.open(file);

        assertThat(query(db, "SELECT folder_name || '|' || next_photo_number || '|' || coalesce(cover_image_id, '-')"
                + " FROM taxon")).containsExactly("Danaus_chrysippus_[A5]|1|-");
    }

    @Test
    void writeRollsBackWhenTheWorkFails() {
        Database db = open();

        assertThatThrownBy(() -> db.write(c -> {
            insertTaxon(c, "G:butterflies");
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(query(db, "SELECT id FROM taxon")).isEmpty();
    }

    @Test
    void leavesOnlyDatabaseFilesInTheDataFolder() throws Exception {
        Database db = open();
        db.write(c -> {
            insertTaxon(c, "G:butterflies");
            return null;
        });

        try (Stream<Path> files = Files.list(dir)) {
            assertThat(files.map(f -> f.getFileName().toString()))
                    .isSubsetOf("atlas.db", "atlas.db-wal", "atlas.db-shm")
                    .contains("atlas.db");
        }
    }
}
