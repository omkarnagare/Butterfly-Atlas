package com.butterflyatlas.app.library;

import com.butterflyatlas.app.config.ConfigStore;
import com.butterflyatlas.app.db.Database;
import com.butterflyatlas.app.sync.LibrarySync;
import com.butterflyatlas.app.sync.Marker;
import com.butterflyatlas.app.sync.MarkerFile;
import com.butterflyatlas.app.sync.SyncState;
import com.butterflyatlas.app.sync.WorkingCopy;
import com.butterflyatlas.app.testing.TestBundles;
import com.butterflyatlas.app.testing.TestClock;
import com.butterflyatlas.app.testing.TestPlaces;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Change library folder with one device at a time (one-device design §9): Move publishes first, copies the working
 * copy's database (not the old folder's marker), marks the old folder closed, and the restarted app claims the new folder
 * with the same working copy; Use a different library marks the current one closed (after a publish) before the restart.
 */
class OneDeviceChangeTest {

    @TempDir
    Path home;

    private final TestClock clock = new TestClock();
    private final LibraryHolder holder = new LibraryHolder();
    private LibrarySync sync;
    private SetupService setup;
    private Path old;

    @BeforeEach
    void open() {
        old = home.resolve("Old");
        sync = sync();
        holder.whenOpened(sync::start);
        setup = new SetupService(new ConfigStore(home.resolve("config")), holder, TestBundles.fixture(),
                TestPlaces.bundles(), home, clock, null, sync);
        setup.setup(old.toString());
        UnfinishedWorkTest.sql(holder.get(), "INSERT INTO library_setting (key, value) VALUES ('unsent', 'x')");
    }

    private LibrarySync sync() {
        return new LibrarySync(home.resolve("config"), () -> "Mac", clock, millis -> { });
    }

    private LibraryChange change() {
        LibraryChange change = new LibraryChange(holder::library, setup, ChangeSteps.REAL, folder -> Long.MAX_VALUE,
                LibraryChange.Background.NONE, setup::rememberChange, Optional::empty);
        change.syncWith(sync::publishNow, sync::close);
        return change;
    }

    @Test
    void moveCopiesThePublishedWorkingCopyMarksTheOldFolderClosedAndTheNewFolderIsClaimedWithTheSameCopy() throws Exception {
        String id = SyncState.read(holder.get()).libraryId();
        LibraryChange change = change();
        clock.advance(Duration.ofSeconds(3));

        change.move(Files.createDirectories(home.resolve("Drive")).toString());
        assertThat(change.await(Duration.ofSeconds(20))).isTrue();

        Path moved = home.resolve("Drive").resolve("Butterfly_Atlas");
        assertThat(SyncState.readFile(old.resolve(Database.FILE_NAME)).generation()).isEqualTo(2); // published first
        Marker closed = MarkerFile.read(old).marker();
        assertThat(closed.open()).isFalse();
        assertThat(moved.resolve(MarkerFile.NAME)).doesNotExist();
        SyncState copied = SyncState.readFile(moved.resolve(Database.FILE_NAME));
        assertThat(copied.libraryId()).isEqualTo(id);
        assertThat(copied.generation()).isEqualTo(2);

        LibraryHolder next = new LibraryHolder();
        LibrarySync again = sync();
        next.whenOpened(again::start);
        SetupService restarted = new SetupService(new ConfigStore(home.resolve("config")), next, TestBundles.fixture(),
                TestPlaces.bundles(), home, clock, null, again);
        restarted.startup(Optional.empty());

        assertThat(next.library().folder()).isEqualTo(moved);
        assertThat(next.get().file()).isEqualTo(WorkingCopy.of(home.resolve("config"), id).file());
        assertThat(MarkerFile.read(moved).marker().open()).isTrue();
        int kept = next.get().read(c -> {
            try (var st = c.createStatement();
                 var rs = st.executeQuery("SELECT COUNT(*) FROM library_setting WHERE key = 'unsent'")) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        });
        assertThat(kept).isEqualTo(1);
    }

    @Test
    void useADifferentLibraryMarksTheCurrentOneClosedAfterAPublish() throws Exception {
        LibraryChange change = change();

        change.switchTo(Files.createDirectories(home.resolve("Other")).toString());
        assertThat(change.await(Duration.ofSeconds(20))).isTrue();

        assertThat(SyncState.readFile(old.resolve(Database.FILE_NAME)).generation()).isEqualTo(2);
        assertThat(MarkerFile.read(old).marker().open()).isFalse();
    }
}
