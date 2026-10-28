package com.butterflyatlas.app.updates;

import com.butterflyatlas.app.AtlasException;
import com.butterflyatlas.app.db.TestDatabases;
import com.butterflyatlas.app.photos.SpeciesFiles;
import com.butterflyatlas.app.testing.UpdateBundles;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteErrorCode;
import org.sqlite.SQLiteException;

import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A drive filled to the last byte: SQLite cannot even map its shared memory and answers {@code SQLITE_IOERR_*}, not
 * {@code SQLITE_FULL}. With under 1 MiB free that is a full drive, in the drive's words, never SQLite's (5b §7).
 */
class UpdatesDriveIoErrorTest {

    private static final String DRIVE_FULL = "The drive with your library is full, so Butterfly Atlas can't read or save"
            + " anything. Free some space, then try again.";

    @TempDir
    Path dir;

    private UpdateFixture f;
    private final AtomicLong free = new AtomicLong(0);
    private final AtomicBoolean failing = new AtomicBoolean();
    private Updates updates;

    @BeforeEach
    void setUp() {
        f = new UpdateFixture(dir);
        f.populated();
        TestDatabases.failCommits(f.db, () -> failing.get() ? new SQLiteException("[SQLITE_IOERR_SHMOPEN] I/O error within"
                + " xShmMap while trying to open a new shared memory segment", SQLiteErrorCode.SQLITE_IOERR_SHMOPEN) : null);
        updates = new Updates(() -> f.library, UpdateBundles.bundles(UpdateFixture.v1(), UpdateFixture.v2()), f.clock,
                (from, to) -> {
                    SpeciesFiles.moveFile(from, to);
                    failing.set(true); // the drive fills up after the first move
                }, path -> free.get());
        updates.startup();
    }

    @Test
    void anApplyThatCannotBeRecordedForAnIoErrorOnAFullDriveIsDriveFull() {
        failing.set(true);
        assertThatThrownBy(updates::apply).isInstanceOfSatisfying(AtlasException.class, e -> {
            assertThat(e.code()).isEqualTo("drive_full");
            assertThat(e.getMessage()).isEqualTo("The drive with your library is full. Free some space, then choose Apply now"
                    + " again.");
        });
        assertThat(f.library.gate().isClosed()).isFalse();
    }

    @Test
    void anIoErrorWithSpaceToSpareIsNotDriveFull() {
        failing.set(true);
        free.set(Long.MAX_VALUE);
        assertThatThrownBy(updates::apply).satisfies(e -> assertThat(e).isNotInstanceOf(AtlasException.class));
    }

    @Test
    void aBatchThatCannotBeWrittenForAnIoErrorOnAFullDrivePausesDriveFull() throws Exception {
        updates.apply();
        assertThat(updates.awaitIdle(Duration.ofSeconds(30))).isTrue();

        assertThat(updates.update().state()).isEqualTo("paused");
        assertThat(updates.update().notice()).isEqualTo(new UpdateViews.Notice("drive_full", "The drive with your library is"
                + " full. Free some space, then choose Retry."));
    }

    @Test
    void aSummaryThatCannotBeReadOnAFullDriveSaysSoInTheDrivesWords() {
        TestDatabases.failStatements(f.db, () -> true);

        assertThat(updates.update().summaryProblem()).isEqualTo("The list of changes couldn't be worked out. " + DRIVE_FULL);
    }
}
