package com.butterflyatlas.app.sync;

import com.butterflyatlas.app.AtlasException;
import com.butterflyatlas.app.ErrorCodes;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The publisher (one-device design §5, §10; contract §2): 5 seconds after the last write, at once when work ends, and
 * when publishing fails, once a minute 5 times (edits meanwhile start no extra tries), then stopped until Try again, which
 * makes one attempt and starts another round when it fails; a publish that works clears it all.
 */
class PublisherTest {

    @TempDir
    Path home;

    private final TestClock clock = new TestClock();
    private Path folder;
    private TestDevice a;

    @BeforeEach
    void open() {
        folder = home.resolve("Atlas");
        a = new TestDevice(home, home.resolve("config"), "Mac", clock);
        a.setup.setup(folder.toString());
    }

    @AfterEach
    void writable() throws Exception {
        Files.setPosixFilePermissions(folder, PosixFilePermissions.fromString("rwxr-xr-x"));
    }

    private long generation() throws Exception {
        return SyncState.readFile(folder.resolve(Database.FILE_NAME)).generation();
    }

    private void after(Duration wait) {
        clock.advance(wait);
        a.ticker.runDue();
    }

    @Test
    void aPublishFollows5SecondsAfterTheLastWrite() throws Exception {
        a.write("one");
        after(Duration.ofSeconds(4));
        a.write("two"); // the wait starts again
        after(Duration.ofSeconds(4));
        assertThat(generation()).isEqualTo(1);

        after(Duration.ofSeconds(1));

        assertThat(generation()).isEqualTo(2);
        assertThat(SyncState.read(a.db()).dirty()).isFalse();
        assertThat(MarkerFile.read(folder).marker().generation()).isEqualTo(2);
        assertThat(a.sync.publishing()).isNull();
    }

    @Test
    void theEndOfWorkPublishesAtOnceWhenThereIsSomethingToSend() throws Exception {
        a.sync.workEnded();
        a.ticker.runDue();
        assertThat(generation()).isEqualTo(1); // nothing unsent: nothing published

        a.write("filed");
        a.sync.workEnded();
        a.ticker.runDue();

        assertThat(generation()).isEqualTo(2);
    }

    @Test
    void aFailingPublishIsTriedOnceAMinute5TimesThenStopsUntilTryAgain() throws Exception {
        Files.setPosixFilePermissions(folder, PosixFilePermissions.fromString("r-xr-xr-x"));
        a.write("unsent");
        after(LibrarySync.DEBOUNCE);

        LibrarySync.Publishing first = a.sync.publishing();
        assertThat(first.state()).isEqualTo(LibrarySync.RETRYING);
        assertThat(first.tries()).isEqualTo(1);
        assertThat(first.reason()).isNotBlank().doesNotContain("Exception");
        assertThat(a.sync.status().publish()).isEqualTo(first);
        a.write("more");
        assertThat(a.ticker.waiting()).isEqualTo(2); // the next try and the heartbeat: an edit starts no extra try
        assertThatThrownBy(a.sync::tryAgain).isInstanceOfSatisfying(AtlasException.class,
                e -> assertThat(e.code()).isEqualTo(ErrorCodes.NOT_STOPPED));

        for (int tries = 2; tries <= 4; tries++) {
            after(LibrarySync.RETRY);
            assertThat(a.sync.publishing()).isEqualTo(new LibrarySync.Publishing(LibrarySync.RETRYING, first.reason(),
                    tries));
        }
        after(LibrarySync.RETRY);

        assertThat(a.sync.publishing()).isEqualTo(new LibrarySync.Publishing(LibrarySync.STOPPED, first.reason(), 5));
        assertThat(a.ticker.waiting()).isEqualTo(1); // the heartbeat only
        after(Duration.ofHours(1));
        assertThat(a.sync.publishing().state()).isEqualTo(LibrarySync.STOPPED);

        assertThat(a.sync.tryAgain()).isEqualTo(new LibrarySync.Publishing(LibrarySync.RETRYING, first.reason(), 1));

        Files.setPosixFilePermissions(folder, PosixFilePermissions.fromString("rwxr-xr-x"));
        after(LibrarySync.RETRY);

        assertThat(a.sync.publishing()).isNull();
        assertThat(generation()).isEqualTo(2);
        assertThat(SyncState.read(a.db()).dirty()).isFalse();
    }

    @Test
    void tryAgainThatWorksClearsTheBar() throws Exception {
        Files.setPosixFilePermissions(folder, PosixFilePermissions.fromString("r-xr-xr-x"));
        a.write("unsent");
        after(LibrarySync.DEBOUNCE);
        for (int i = 0; i < 4; i++) {
            after(LibrarySync.RETRY);
        }
        assertThat(a.sync.publishing().state()).isEqualTo(LibrarySync.STOPPED);
        Files.setPosixFilePermissions(folder, PosixFilePermissions.fromString("rwxr-xr-x"));

        assertThat(a.sync.tryAgain()).isNull();
        assertThat(generation()).isEqualTo(2);
    }

    @Test
    void aMarkerThatCantBeReadIsAFailedTrySayingTheFolderCouldntBeRead() throws Exception {
        Files.writeString(folder.resolve(MarkerFile.NAME), "half a marker");
        a.write("unsent");

        after(LibrarySync.DEBOUNCE);

        assertThat(a.sync.publishing()).isEqualTo(new LibrarySync.Publishing(LibrarySync.RETRYING,
                LibrarySync.FOLDER_UNREADABLE, 1));
        assertThat(generation()).isEqualTo(1);
    }
}
