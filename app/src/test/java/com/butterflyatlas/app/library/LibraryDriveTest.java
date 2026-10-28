package com.butterflyatlas.app.library;

import com.butterflyatlas.app.db.DatabaseException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteErrorCode;
import org.sqlite.SQLiteException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/** The library's drive full or gone, told apart without SQL (5b contract §7: no silent failures). */
class LibraryDriveTest {

    @TempDir
    Path dir;

    private static final SQLiteException SHMOPEN = new SQLiteException("[SQLITE_IOERR_SHMOPEN] I/O error within xShmMap while"
            + " trying to open a new shared memory segment", SQLiteErrorCode.SQLITE_IOERR_SHMOPEN);

    @Test
    void aFolderUnderOneMebibyteFreeIsFullAndAMissingOneIsGone() {
        assertThat(LibraryDrive.check(dir, p -> LibraryDrive.FULL_BELOW)).isNull();
        assertThat(LibraryDrive.check(dir, p -> LibraryDrive.FULL_BELOW - 1)).isEqualTo(new LibraryDrive.Problem("drive_full",
                "The drive with your library is full, so Butterfly Atlas can't read or save anything. Free some space, then"
                        + " try again."));
        assertThat(LibraryDrive.check(dir.resolve("unplugged"), p -> 0)).isEqualTo(new LibraryDrive.Problem("drive_gone",
                "The drive with your library can't be found. Reconnect it, then try again."));
    }

    @Test
    void theStatusChecksAtMostEveryFiveSecondsAndAFailedRequestChecksAtOnce() {
        AtomicLong free = new AtomicLong(Long.MAX_VALUE);
        AtomicLong nanos = new AtomicLong();
        LibraryDrive drive = new LibraryDrive(() -> Optional.of(dir), p -> free.get(), nanos::get);
        assertThat(drive.problem()).isNull();

        free.set(0);
        nanos.addAndGet(4_000_000_000L);
        assertThat(drive.problem()).as("checked 4 s ago").isNull();
        nanos.addAndGet(1_000_000_000L);
        assertThat(drive.problem().code()).isEqualTo("drive_full");

        free.set(Long.MAX_VALUE);
        assertThat(drive.now()).as("a failed request checks at once").isNull();
        assertThat(drive.problem()).as("and the status hears of it").isNull();
    }

    @Test
    void noLibraryNoProblem() {
        LibraryDrive drive = new LibraryDrive(Optional::empty, p -> 0, System::nanoTime);
        assertThat(drive.problem()).isNull();
        assertThat(drive.now()).isNull();
    }

    @Test
    void storageFailuresAndSqliteIoErrorsAreToldApart() throws IOException {
        Files.createDirectories(dir);
        assertThat(LibraryDrive.storageFailure(new DatabaseException("read failed", SHMOPEN))).isTrue();
        assertThat(LibraryDrive.storageFailure(new IOException("No space left on device"))).isTrue();
        assertThat(LibraryDrive.storageFailure(new IllegalStateException("a bug"))).isFalse();
        assertThat(LibraryDrive.sqliteIoError(new DatabaseException("read failed", SHMOPEN))).isTrue();
        assertThat(LibraryDrive.sqliteIoError(new DatabaseException("x", new SQLiteException("busy",
                SQLiteErrorCode.SQLITE_BUSY)))).isFalse();
    }
}
