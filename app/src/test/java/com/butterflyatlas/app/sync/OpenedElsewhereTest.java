package com.butterflyatlas.app.sync;

import com.butterflyatlas.app.AtlasException;
import com.butterflyatlas.app.ErrorCodes;
import com.butterflyatlas.app.db.Database;
import com.butterflyatlas.app.testing.TestClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * While running (one-device design §5 Heartbeat, §6): every minute the marker is read again and this device's
 * {@code seen} written; a marker naming another device (Open here anyway while this one ran, or both claiming within the
 * sync delay) stops all work and every publish here, and the status says so; Quit quits 1.5 seconds later.
 */
class OpenedElsewhereTest {

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

    private void minute(TestDevice device) {
        clock.advance(LibrarySync.HEARTBEAT);
        device.ticker.runDue();
    }

    @Test
    void theHeartbeatWritesANewSeenEveryMinuteAndAMissingMarkerAgain() throws Exception {
        a.setup.setup(folderA.toString());
        Instant since = clock.instant();

        minute(a);

        Marker marker = MarkerFile.read(folderA).marker();
        assertThat(marker.seen()).isEqualTo(clock.instant());
        assertThat(marker.since()).isEqualTo(since);
        assertThat(marker.generation()).isEqualTo(1);

        Files.delete(folderA.resolve(MarkerFile.NAME));
        minute(a);

        assertThat(MarkerFile.read(folderA).marker().seen()).isEqualTo(clock.instant());
        assertThat(a.sync.status().openedElsewhere()).isNull();
    }

    @Test
    void openHereAnywayWhileThisDeviceRunsIsFoundAtItsNextHeartbeat() throws Exception {
        AtomicInteger stopped = new AtomicInteger();
        a.sync.stopWorkWith(stopped::incrementAndGet);
        a.setup.setup(folderA.toString());
        Instant inStep = clock.instant();
        TestDevice.syncAll(folderA, folderB);
        // A's heartbeats stop reaching B (its sync app stopped) for 15 minutes; A changes something meanwhile
        clock.advance(Duration.ofMinutes(15));
        b.setup.setup(folderB.toString());
        b.setup.openAnyway();
        Instant opened = clock.instant();
        a.write("here");
        a.sync.status(); // nothing yet
        TestDevice.sync(folderB, folderA, MarkerFile.NAME);

        minute(a);

        assertThat(a.sync.status().openedElsewhere()).isEqualTo(new LibrarySync.OpenedElsewhere("DESKTOP-PUNE",
                opened.toString(), inStep.toString()));
        assertThat(stopped).hasValue(1);
        assertThat(a.sync.status().publish()).isNull();
        assertThat(a.ticker.waiting()).isZero();
        a.write("more");
        clock.advance(Duration.ofMinutes(10));
        a.ticker.runDue();
        assertThat(SyncState.readFile(folderA.resolve(Database.FILE_NAME)).generation()).isEqualTo(1); // never published
        assertThat(MarkerFile.read(folderA).marker().name()).isEqualTo("DESKTOP-PUNE");
    }

    @Test
    void withNothingUnsentThePageSaysNoTime() throws Exception {
        a.setup.setup(folderA.toString());
        TestDevice.syncAll(folderA, folderB);
        clock.advance(Duration.ofMinutes(15));
        b.setup.setup(folderB.toString());
        b.setup.openAnyway();
        TestDevice.sync(folderB, folderA, MarkerFile.NAME);

        minute(a);

        assertThat(a.sync.status().openedElsewhere().unsentSince()).isNull();
    }

    @Test
    void twoDevicesClaimingWithinTheSyncDelayFindOutAtTheNextHeartbeat() throws Exception {
        a.setup.setup(folderA.toString());
        TestDevice.syncAll(folderA, folderB);
        MarkerFile.write(folderB, MarkerFile.read(folderB).marker().closedAt(clock.instant()));
        b.setup.setup(folderB.toString()); // A's open marker hadn't reached B yet
        TestDevice.sync(folderB, folderA, MarkerFile.NAME); // the service keeps B's

        minute(a);
        minute(b);

        assertThat(a.sync.openedElsewhere()).isTrue();
        assertThat(b.sync.openedElsewhere()).isFalse();
    }

    @Test
    void aPublishThatFindsAnotherDevicesMarkerStopsToo() throws Exception {
        a.setup.setup(folderA.toString());
        MarkerFile.write(folderA, new Marker("someone", null, Marker.OPEN, clock.instant(), clock.instant(), 1));
        a.write("here");

        clock.advance(LibrarySync.DEBOUNCE);
        a.ticker.runDue();

        assertThat(a.sync.status().openedElsewhere().name()).isNull(); // "another device"
        assertThat(folderA.resolve(Publish.PART)).doesNotExist();
    }

    @Test
    void quitIsRefusedUnlessOpenedElsewhereThenQuitsAfterAMoment() throws Exception {
        AtomicInteger quits = new AtomicInteger();
        a.sync.quitWith(quits::incrementAndGet);
        a.setup.setup(folderA.toString());
        assertThatThrownBy(a.sync::quit).isInstanceOfSatisfying(AtlasException.class,
                e -> assertThat(e.code()).isEqualTo(ErrorCodes.NOT_OPENED_ELSEWHERE));
        MarkerFile.write(folderA, new Marker("someone", "DESKTOP-PUNE", Marker.OPEN, clock.instant(), clock.instant(), 1));
        minute(a);

        a.sync.quit();
        a.ticker.runDue();
        assertThat(quits).hasValue(0);
        clock.advance(LibrarySync.QUIT_WAIT);
        a.ticker.runDue();

        assertThat(quits).hasValue(1);
    }
}
