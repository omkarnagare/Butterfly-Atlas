package com.butterflyatlas.app.library.sync;

import com.butterflyatlas.app.db.Database;
import com.butterflyatlas.app.testing.TestClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Quitting (one-device design §8): what is unsent is published and the marker closed; when that fails the marker stays
 * open and the next start here sends it. Home's one-time notes (§5 case 2, §8; contract §2), and the 30-day cleanup of
 * working copies (§5 Disk space). With Open here anyway, the old device's unsent changes are sent at its next start when
 * the other device changed nothing (§6).
 */
class QuitTest {

    @TempDir
    Path home;

    private final TestClock clock = new TestClock();
    private Path folderA;
    private Path folderB;
    private TestDevice a;
    private TestDevice b;

    @BeforeEach
    void devices() {
        folderA = home.resolve("A");
        folderB = home.resolve("B");
        a = new TestDevice(home, home.resolve("configA"), "Omkar's MacBook Air", clock);
        b = new TestDevice(home, home.resolve("configB"), "DESKTOP-PUNE", clock);
    }

    @AfterEach
    void writable() throws Exception {
        if (Files.isDirectory(folderA)) {
            Files.setPosixFilePermissions(folderA, PosixFilePermissions.fromString("rwxr-xr-x"));
        }
    }

    private long generation(Path folder) throws Exception {
        return SyncState.readFile(folder.resolve(Database.FILE_NAME)).generation();
    }

    @Test
    void quittingPublishesWhatIsUnsentAndClosesTheMarker() throws Exception {
        a.setup.setup(folderA.toString());
        Instant since = clock.instant();
        a.write("last");
        clock.advance(Duration.ofSeconds(2));

        a.sync.close();

        assertThat(generation(folderA)).isEqualTo(2);
        assertThat(MarkerFile.read(folderA).marker()).isEqualTo(new Marker(a.sync.device().id(), "Omkar's MacBook Air",
                Marker.CLOSED, since, clock.instant(), 2, Checksum.of(folderA.resolve(Database.FILE_NAME))));
        a.write("after");
        clock.advance(Duration.ofMinutes(5));
        a.ticker.runDue();
        assertThat(generation(folderA)).isEqualTo(2); // nothing after the quit
    }

    @Test
    void quittingWithNothingUnsentOnlyClosesTheMarker() throws Exception {
        a.setup.setup(folderA.toString());

        a.sync.close();

        assertThat(generation(folderA)).isEqualTo(1);
        assertThat(MarkerFile.read(folderA).marker().open()).isFalse();
    }

    @Test
    void aQuitThatCantPublishLeavesTheMarkerOpenAndTheNextStartSendsIt() throws Exception {
        a.setup.setup(folderA.toString());
        a.write("unsent");
        Files.setPosixFilePermissions(folderA, PosixFilePermissions.fromString("r-xr-xr-x"));

        a.sync.close();

        assertThat(MarkerFile.read(folderA).marker().open()).isTrue();
        assertThat(SyncState.read(a.db()).dirty()).isTrue();
        Files.setPosixFilePermissions(folderA, PosixFilePermissions.fromString("rwxr-xr-x"));
        TestDevice again = a.restarted(home, "Omkar's MacBook Air", clock);
        again.setup.startup(Optional.empty());
        assertThat(generation(folderA)).isEqualTo(2);
        assertThat(again.sync.syncNote()).isEqualTo(LibrarySync.SENT_LAST_SESSION);
    }

    @Test
    void quittingAfterTheLibraryWasOpenedElsewhereLeavesTheOtherDevicesMarker() throws Exception {
        a.setup.setup(folderA.toString());
        Marker other = new Marker("other", "DESKTOP-PUNE", Marker.OPEN, clock.instant(), clock.instant(), 1);
        MarkerFile.write(folderA, other);
        clock.advance(LibrarySync.HEARTBEAT);
        a.ticker.runDue();
        a.write("here");

        a.sync.close();

        assertThat(MarkerFile.read(folderA).marker()).isEqualTo(other);
        assertThat(generation(folderA)).isEqualTo(1);
    }

    @Test
    void openHereAnywayThenTheOtherDeviceChangedNothingSoTheOldDevicesChangesAreSentAtItsNextStart() throws Exception {
        a.setup.setup(folderA.toString());
        TestDevice.syncAll(folderA, folderB);
        clock.advance(Duration.ofMinutes(15));
        b.setup.setup(folderB.toString());
        b.setup.openAnyway();
        a.write("mine");
        TestDevice.sync(folderB, folderA, MarkerFile.NAME);
        clock.advance(LibrarySync.HEARTBEAT);
        a.ticker.runDue();
        assertThat(a.sync.openedElsewhere()).isTrue();
        a.sync.close(); // Quit here
        b.sync.close(); // B quits having changed nothing
        TestDevice.syncAll(folderB, folderA);

        TestDevice again = a.restarted(home, "Omkar's MacBook Air", clock);
        again.setup.startup(Optional.empty());

        assertThat(again.holder.current()).isPresent();
        assertThat(again.sync.syncNote()).isEqualTo(LibrarySync.SENT_LAST_SESSION);
        assertThat(generation(folderA)).isEqualTo(2);
        assertThat(again.has("mine")).isTrue();
    }

    @Test
    void theNotesComeOneAfterTheOtherAndTheSecondStaysDismissedOnThisDevice() throws Exception {
        a.setup.setup(folderA.toString());
        assertThat(a.sync.syncNote()).isEqualTo(LibrarySync.BEFORE_OTHER_DEVICE);
        a.write("unsent"); // then a crash

        TestDevice again = a.restarted(home, "Omkar's MacBook Air", clock);
        again.setup.startup(Optional.empty());
        assertThat(again.sync.syncNote()).isEqualTo(LibrarySync.SENT_LAST_SESSION);
        assertThat(again.sync.status().syncNote()).isEqualTo(LibrarySync.SENT_LAST_SESSION);

        again.sync.dismissNote();
        assertThat(again.sync.syncNote()).isEqualTo(LibrarySync.BEFORE_OTHER_DEVICE);
        again.sync.dismissNote();
        assertThat(again.sync.syncNote()).isNull();
        again.sync.close();

        TestDevice third = a.restarted(home, "Omkar's MacBook Air", clock);
        third.setup.startup(Optional.empty());
        assertThat(third.sync.syncNote()).isNull();

        // another device sees its own note (the dismissal is this device's)
        third.sync.close();
        TestDevice.syncAll(folderA, folderB);
        b.setup.setup(folderB.toString());
        assertThat(b.sync.syncNote()).isEqualTo(LibrarySync.BEFORE_OTHER_DEVICE);
    }

    @Test
    void workingCopiesUnclaimedFor30DaysAreDeletedUnlessTheyHoldUnsentChanges() throws Exception {
        a.setup.setup(folderA.toString());
        String mine = SyncState.read(a.db()).libraryId();
        a.sync.close();
        Path old = oldCopy("old", false, Duration.ofDays(31));
        Path recent = oldCopy("recent", false, Duration.ofDays(29));
        Path unsent = oldCopy("unsent", true, Duration.ofDays(31));

        TestDevice again = a.restarted(home, "Omkar's MacBook Air", clock);
        again.setup.startup(Optional.empty());

        assertThat(old).doesNotExist();
        assertThat(recent).exists();
        assertThat(unsent).exists();
        assertThat(again.config.resolve(WorkingCopy.FOLDER).resolve(mine)).exists();
    }

    private Path oldCopy(String id, boolean dirty, Duration age) throws Exception {
        WorkingCopy copy = WorkingCopy.of(a.config, id);
        Files.createDirectories(copy.folder());
        Database db = Database.open(copy.file());
        db.writeUntracked(c -> {
            SyncState.set(c, SyncState.LIBRARY_ID, id);
            SyncState.set(c, SyncState.DIRTY, dirty ? "1" : "0");
            return null;
        });
        copy.claimed(clock.instant().minus(age));
        return copy.folder();
    }
}
