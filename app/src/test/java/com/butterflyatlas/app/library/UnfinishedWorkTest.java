package com.butterflyatlas.app.library;

import com.butterflyatlas.app.db.Database;
import com.butterflyatlas.app.testing.TestBundles;
import com.butterflyatlas.app.testing.TestPlaces;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class UnfinishedWorkTest {

    @TempDir
    Path dir;

    /**
     * Leaves the work a library is left with where it was used before: import 1 open (with an item, and a photo it filed),
     * import 2 finished; export 1 paused, export 2 done; backup 1 running.
     */
    static void leaveUnfinished(Database database) {
        sql(database, "INSERT INTO import_run (id, root_path, state, created_at, updated_at) VALUES"
                + " (1, 'C:\\Users\\old\\Pictures', 'open', '2026-10-01T00:00:00Z', '2026-10-01T00:00:00Z'),"
                + " (2, 'C:\\Users\\old\\Trip', 'finished', '2026-10-01T00:00:00Z', '2026-10-01T00:00:00Z')");
        sql(database, "INSERT INTO import_item (id, run_id, rel_path, file_size, modified_at, read_state, seq)"
                + " VALUES (1, 1, 'a.jpg', 1, '2026-10-01T00:00:00Z', 'read', 1)");
        sql(database, "INSERT INTO image (taxon_id, rel_path, file_name, file_size, modified_at, sha256, added_at, source)"
                + " VALUES ('A5', 'images/a/p_1.jpg', 'p_1.jpg', 1, '2026-10-01T00:00:00Z', 'one', '2026-10-01T00:00:00Z',"
                + " 'import')");
        sql(database, "INSERT INTO export_run (id, dest_root, folder_name, what, layout, strip_location, place_depth,"
                + " filter_query, species, state, paused_by, notice, total, total_bytes, started_at) VALUES"
                + " (1, 'D:\\', 'Butterfly_Atlas_export', 'photos', 'species', 1, 'none', '', 1, 'paused', 'closing',"
                + " 'Stopped by closing the app.', 1, 1, '2026-10-01T00:00:00Z'),"
                + " (2, 'D:\\', 'Butterfly_Atlas_export_2', 'photos', 'species', 1, 'none', '', 1, 'done', NULL, NULL, 1, 1,"
                + " '2026-10-01T00:00:00Z')");
        sql(database, "INSERT INTO backup_run (id, state, what, dest_root, folder, total, bytes_total, started_at) VALUES"
                + " (1, 'running', 'everything', 'E:\\', 'Butterfly_Atlas_backup_2026-10-01', 1, 1, '2026-10-01T00:00:00Z')");
    }

    static void sql(Database database, String sql) {
        database.write(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.executeUpdate();
            }
            return null;
        });
    }

    static List<String> rows(Database database, String sql) {
        return database.read(c -> {
            List<String> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
                int columns = rs.getMetaData().getColumnCount();
                while (rs.next()) {
                    List<String> row = new ArrayList<>();
                    for (int i = 1; i <= columns; i++) {
                        row.add(rs.getString(i));
                    }
                    out.add(String.join("|", row));
                }
            }
            return out;
        });
    }

    private Library library(String name) {
        return Library.open(dir.resolve(name), TestBundles.fixture(), TestPlaces.bundles(), TestBundles.CLOCK);
    }

    @Test
    void countsWhatIsUnfinishedWithoutChangingIt() {
        Library library = library("Atlas");
        leaveUnfinished(library.database());

        assertThat(UnfinishedWork.count(library.database().file())).isEqualTo(new UnfinishedWork.Counts(1, 1, 1));
        assertThat(rows(library.database(), "SELECT id, state FROM import_run ORDER BY id")).containsExactly("1|open", "2|finished");
        assertThat(rows(library.database(), "SELECT state FROM export_run ORDER BY id")).containsExactly("paused", "done");
    }

    @Test
    void aNewLibraryHasNothingUnfinished() {
        Library library = library("Atlas");

        assertThat(UnfinishedWork.count(library.database().file())).isEqualTo(new UnfinishedWork.Counts(0, 0, 0));
        assertThat(new UnfinishedWork.Counts(0, 0, 0).none()).isTrue();
        assertThat(new UnfinishedWork.Counts(0, 1, 0).none()).isFalse();
    }

    @Test
    void anOlderLibraryWithoutTheBackupTablesCountsNoBackups() {
        Library library = library("Atlas");
        leaveUnfinished(library.database());
        sql(library.database(), "DROP TABLE backup_problem");
        sql(library.database(), "DROP TABLE backup_file");
        sql(library.database(), "DROP TABLE backup_run");

        assertThat(UnfinishedWork.count(library.database().file())).isEqualTo(new UnfinishedWork.Counts(1, 1, 0));
    }

    @Test
    void aFileThatIsNotADatabaseHasNoCounts() throws Exception {
        Path file = Files.writeString(dir.resolve("atlas.db"), "not a database, just text that is long enough to read");

        assertThat(UnfinishedWork.count(file)).isNull();
        assertThat(UnfinishedWork.count(dir.resolve("missing.db"))).isNull();
        assertThat(dir.resolve("missing.db")).doesNotExist();
    }

    @Test
    void clearingDropsOpenImportsAndCancelsRunningAndPausedRunsKeepingTheirFiles() {
        Library library = library("Atlas");
        leaveUnfinished(library.database());
        UnfinishedWork.Counts cleared = UnfinishedWork.clear(library, TestBundles.CLOCK);

        assertThat(cleared).isEqualTo(new UnfinishedWork.Counts(1, 1, 1));
        Database db = library.database();
        assertThat(rows(db, "SELECT id, state FROM import_run")).containsExactly("2|finished");
        assertThat(rows(db, "SELECT COUNT(*) FROM import_item")).containsExactly("0");
        assertThat(rows(db, "SELECT rel_path FROM image")).as("photos already filed stay").containsExactly("images/a/p_1.jpg");
        String now = TestBundles.CLOCK.instant().toString();
        assertThat(rows(db, "SELECT id, state, cancel_choice, paused_by, notice, finished_at FROM export_run ORDER BY id"))
                .containsExactly("1|cancelled|keep|null|null|" + now, "2|done|null|null|null|null");
        assertThat(rows(db, "SELECT state, cancel, finished_at FROM backup_run")).containsExactly("cancelled|keep|" + now);
        assertThat(UnfinishedWork.count(db.file())).isEqualTo(new UnfinishedWork.Counts(0, 0, 0));
    }

    @Test
    void clearingANewLibraryChangesNothing() {
        Library library = library("Atlas");

        assertThat(UnfinishedWork.clear(library, TestBundles.CLOCK)).isEqualTo(new UnfinishedWork.Counts(0, 0, 0));
    }
}
