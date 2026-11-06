package com.butterflyatlas.app.library.sync;

import com.butterflyatlas.app.db.Database;
import com.butterflyatlas.app.library.SetupService;
import com.butterflyatlas.app.testing.TestClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The snapshot's checksum (one-device design §3, §5): the device that publishes writes the SHA-256 of the snapshot into the
 * marker beside its generation; a device that claims the library compares it with the {@code atlas.db} it has, and a
 * mismatch (a snapshot the sync app hasn't finished downloading) is "not synced yet", never a database opened half-way. A
 * rename keeps the checksum, and a marker without one (written before this) skips the check.
 */
class ChecksumTest {

    @TempDir
    Path home;

    private final TestClock clock = new TestClock();
    private Path folderA;
    private Path folderB;
    private TestDevice a;
    private TestDevice b;

    @BeforeEach
    void devices() {
        folderA = home.resolve("A").resolve("Butterfly_Atlas");
        folderB = home.resolve("B").resolve("Butterfly_Atlas");
        a = new TestDevice(home, home.resolve("configA"), "Omkar's MacBook Air", clock);
        b = new TestDevice(home, home.resolve("configB"), "DESKTOP-PUNE", clock);
    }

    private static Marker marker(Path folder) {
        return MarkerFile.read(folder).marker();
    }

    private void publishAndQuitOnA() throws Exception {
        a.setup.setup(folderA.toString());
        a.write("newer");
        Publish.run(a.db(), folderA, a.sync.device(), clock.instant(), clock.instant(), null, millis -> { }, () -> { });
        MarkerFile.write(folderA, marker(folderA).closedAt(clock.instant()));
    }

    @Test
    void theChecksumIsTheSha256OfTheFileAndARenameKeepsIt() throws Exception {
        Path part = Files.writeString(home.resolve("atlas.db.part"), "the same bytes");
        String before = Checksum.of(part);
        Path renamed = Files.move(part, home.resolve("atlas.db"), StandardCopyOption.ATOMIC_MOVE);

        assertThat(before).hasSize(64).matches("[0-9a-f]+");
        assertThat(Checksum.of(renamed)).isEqualTo(before);
        Files.writeString(renamed, "the same byteS");
        assertThat(Checksum.of(renamed)).isNotEqualTo(before);
    }

    @Test
    void theMarkerCarriesTheChecksumAndOneWithoutItIsStillRead() throws Exception {
        Marker with = new Marker("dev-1", null, Marker.OPEN, Instant.parse("2026-11-04T10:42:00Z"),
                Instant.parse("2026-11-04T11:05:00Z"), 37, "ab".repeat(32));
        Files.createDirectories(folderA);
        MarkerFile.write(folderA, with);
        assertThat(marker(folderA)).isEqualTo(with);
        assertThat(marker(folderA).closedAt(clock.instant()).sha256()).isEqualTo(with.sha256());

        Files.writeString(folderA.resolve(MarkerFile.NAME), """
                {"device":"dev-1","state":"closed","since":"2026-11-04T10:42:00Z","seen":"2026-11-04T11:05:00Z",
                 "generation":37}
                """);
        assertThat(marker(folderA).sha256()).isNull();
    }

    @Test
    void publishingWritesTheSnapshotsChecksumIntoTheMarker() throws Exception {
        publishAndQuitOnA();

        assertThat(marker(folderA).generation()).isEqualTo(2);
        assertThat(marker(folderA).sha256()).isEqualTo(Checksum.of(folderA.resolve(Database.FILE_NAME)));
    }

    @Test
    void claimingWritesTheChecksumOfTheSnapshotItClaimed() throws Exception {
        a.setup.setup(folderA.toString());

        assertThat(marker(folderA).sha256()).isEqualTo(Checksum.of(folderA.resolve(Database.FILE_NAME)));
    }

    @Test
    void aSnapshotCutShortIsNotSyncedUntilTheWholeFileArrives() throws Exception {
        publishAndQuitOnA();
        TestDevice.syncAll(folderA, folderB);
        try (RandomAccessFile half = new RandomAccessFile(folderB.resolve(Database.FILE_NAME).toFile(), "rw")) {
            half.setLength(half.length() / 2); // the sync app is still downloading it
        }

        SetupService.Status status = b.setup.setup(folderB.toString());

        assertThat(status.notSynced()).isEqualTo(new Refusal.NotSynced.Info(folderB.toString()));
        assertThat(status.configured()).isFalse();

        TestDevice.sync(folderA, folderB, Database.FILE_NAME);
        assertThat(b.setup.retry().configured()).isTrue();
        assertThat(b.has("newer")).isTrue();
    }

    @Test
    void aSnapshotWithOneChangedByteIsNotSyncedAndOpenHereAnywayDoesNotSkipIt() throws Exception {
        publishAndQuitOnA();
        TestDevice.syncAll(folderA, folderB);
        try (RandomAccessFile file = new RandomAccessFile(folderB.resolve(Database.FILE_NAME).toFile(), "rw")) {
            file.seek(file.length() - 1);
            int last = file.read();
            file.seek(file.length() - 1);
            file.write(last ^ 0xff);
        }

        assertThat(b.setup.setup(folderB.toString()).notSynced()).isNotNull();
        assertThat(b.setup.status().notSynced()).isNotNull();
        assertThat(b.setup.retry().notSynced()).isNotNull();
    }

    @Test
    void aMarkerWithoutAChecksumSkipsTheCheck() throws Exception {
        publishAndQuitOnA();
        TestDevice.syncAll(folderA, folderB);
        Marker old = marker(folderB);
        MarkerFile.write(folderB, new Marker(old.device(), old.name(), old.state(), old.since(), old.seen(),
                old.generation()));

        assertThat(b.setup.setup(folderB.toString()).configured()).isTrue();
        assertThat(b.has("newer")).isTrue();
    }
}
