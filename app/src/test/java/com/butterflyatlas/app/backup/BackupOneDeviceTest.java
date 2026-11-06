package com.butterflyatlas.app.backup;

import com.butterflyatlas.app.config.ConfigStore;
import com.butterflyatlas.app.db.Database;
import com.butterflyatlas.app.library.LibraryHolder;
import com.butterflyatlas.app.library.SetupService;
import com.butterflyatlas.app.library.sync.LibrarySync;
import com.butterflyatlas.app.library.sync.SyncState;
import com.butterflyatlas.app.testing.TestBundles;
import com.butterflyatlas.app.testing.TestClock;
import com.butterflyatlas.app.testing.TestPlaces;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Backups with one device at a time (one-device design §9): the database is copied from the open working copy, as before;
 * the copy keeps no sync facts but {@code dirty = 0}, so a restored backup opens as a library of its own (a new ID,
 * generation 1) and never stands in for the library it came from.
 */
class BackupOneDeviceTest {

    @TempDir
    Path home;

    @Test
    void aBackedUpDatabaseIsALibraryOfItsOwnWhenRestored() throws Exception {
        TestClock clock = new TestClock();
        LibraryHolder holder = new LibraryHolder();
        LibrarySync sync = new LibrarySync(home.resolve("config"), () -> "Mac", clock, millis -> { });
        new SetupService(new ConfigStore(home.resolve("config")), holder, TestBundles.fixture(), TestPlaces.bundles(),
                home, clock, null, sync).setup(home.resolve("Atlas").toString());
        String id = SyncState.read(holder.get()).libraryId();
        holder.get().write(c -> {
            try (var st = c.createStatement()) {
                st.executeUpdate("INSERT INTO library_setting (key, value) VALUES ('unsent', 'x')");
            }
            return null;
        });
        Path restored = Files.createDirectories(home.resolve("Restored"));
        Path copy = restored.resolve(Database.FILE_NAME + ".part");

        DatabaseCopy.copy(holder.get(), copy);
        DatabaseCopy.sanitise(copy, -1);
        Files.move(copy, restored.resolve(Database.FILE_NAME));

        SyncState backedUp = SyncState.readFile(restored.resolve(Database.FILE_NAME));
        assertThat(backedUp.libraryId()).isNull();
        assertThat(backedUp.dirty()).isFalse();
        LibraryHolder other = new LibraryHolder();
        new SetupService(new ConfigStore(home.resolve("config2")), other, TestBundles.fixture(), TestPlaces.bundles(),
                home, clock, null, new LibrarySync(home.resolve("config"), () -> "Mac", clock, millis -> { }))
                .setup(restored.toString());
        SyncState opened = SyncState.read(other.get());
        assertThat(opened.libraryId()).isNotNull().isNotEqualTo(id);
        assertThat(opened.generation()).isEqualTo(1);
        int kept = other.get().read(c -> {
            try (var st = c.createStatement();
                 var rs = st.executeQuery("SELECT COUNT(*) FROM library_setting WHERE key = 'unsent'")) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        });
        assertThat(kept).isEqualTo(1);
    }
}
