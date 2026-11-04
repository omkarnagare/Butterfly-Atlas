package com.butterflyatlas.app.library.sync;

import com.butterflyatlas.app.AtlasException;
import com.butterflyatlas.app.ErrorCodes;
import com.butterflyatlas.app.db.Database;
import com.butterflyatlas.app.library.SetupService;
import com.butterflyatlas.app.testing.TestClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Claiming a library at start and in setup (one-device design §4, §5, §7; contract §1), with two devices sharing a library
 * through a sync helper: a new library and a converted one become one snapshot at generation 1 with a marker; another
 * device's open marker refuses (and keeps refusing until Try again, Open here anyway or Continue changes it); Open here
 * anyway only from 15 minutes; a marker ahead of its snapshot is not synced; unsent changes after a crash are sent at the
 * next start; unsent changes another device overtook are discarded with the photo list.
 */
class ClaimTest {

    @TempDir
    Path home;

    private final TestClock clock = new TestClock();
    private Path folderA;
    private Path folderB;
    private TestDevice a;
    private TestDevice b;

    private static final String MAC = "Omkar's MacBook Air";
    private static final String PUNE = "DESKTOP-PUNE";

    @BeforeEach
    void devices() {
        folderA = home.resolve("A").resolve("Butterfly_Atlas");
        folderB = home.resolve("B").resolve("Butterfly_Atlas");
        a = new TestDevice(home, home.resolve("configA"), MAC, clock);
        b = new TestDevice(home, home.resolve("configB"), PUNE, clock);
    }

    private List<String> names(Path folder) throws IOException {
        try (Stream<Path> files = Files.list(folder)) {
            return files.map(p -> p.getFileName().toString()).filter(n -> !n.startsWith(".atlas-write")).sorted().toList();
        }
    }

    private static SyncState snapshot(Path folder) throws Exception {
        return SyncState.readFile(folder.resolve(Database.FILE_NAME));
    }

    private static Marker marker(Path folder) {
        return MarkerFile.read(folder).marker();
    }

    /** As quitting would leave the marker (J8 does it for real): closed now. */
    private void closeMarker(Path folder) throws IOException {
        MarkerFile.write(folder, marker(folder).closedAt(clock.instant()));
    }

    @Test
    void aNewLibraryIsOneSnapshotAtGeneration1WithAMarkerAndAWorkingCopy() throws Exception {
        SetupService.Status status = a.setup.setup(folderA.toString());

        assertThat(status.configured()).isTrue();
        assertThat(names(folderA)).containsExactly(MarkerFile.NAME, Database.FILE_NAME);
        SyncState snap = snapshot(folderA);
        assertThat(snap.generation()).isEqualTo(1);
        assertThat(snap.dirty()).isFalse();
        assertThat(marker(folderA)).isEqualTo(new Marker(a.sync.device().id(), MAC, Marker.OPEN, clock.instant(),
                clock.instant(), 1));
        assertThat(a.db().file()).isEqualTo(WorkingCopy.of(a.config, snap.libraryId()).file());
        assertThat(SyncState.read(a.db())).isEqualTo(new SyncState(snap.libraryId(), 1, clock.instant(), false));
    }

    @Test
    void anExistingVersion15LibraryWithItsLogFilesIsConvertedAndRefusedByAnOlderApp() throws Exception {
        Path made = home.resolve("made");
        Files.createDirectories(made);
        org.sqlite.SQLiteDataSource v15 = new org.sqlite.SQLiteDataSource();
        v15.setUrl("jdbc:sqlite:" + made.resolve(Database.FILE_NAME));
        org.flywaydb.core.Flyway.configure().dataSource(v15).locations("classpath:db/migration").target("15").load()
                .migrate();
        SQLiteConfig config = new SQLiteConfig();
        config.setJournalMode(SQLiteConfig.JournalMode.WAL);
        try (Connection open = config.createConnection("jdbc:sqlite:" + made.resolve(Database.FILE_NAME));
             Statement st = open.createStatement()) {
            st.execute("PRAGMA wal_autocheckpoint = 0");
            st.executeUpdate("INSERT INTO library_setting (key, value) VALUES ('old', 'kept')");
            Files.createDirectories(folderA);
            for (String name : List.of("atlas.db", "atlas.db-wal", "atlas.db-shm")) {
                Files.copy(made.resolve(name), folderA.resolve(name)); // copied while open: the log holds the row
            }
        }
        assertThat(names(folderA)).contains("atlas.db-wal", "atlas.db-shm");

        a.setup.setup(folderA.toString());

        assertThat(names(folderA)).containsExactly(MarkerFile.NAME, Database.FILE_NAME);
        assertThat(snapshot(folderA).generation()).isEqualTo(1);
        assertThat(marker(folderA).generation()).isEqualTo(1);
        assertThat(a.has("old")).isTrue();
        assertThat(Database.newestSchemaVersion(folderA.resolve(Database.FILE_NAME))).hasValue(16); // a V15 app refuses it
    }

    @Test
    void anotherDevicesOpenMarkerRefusesAndTheRefusalStaysUntilTryAgainChangesIt() throws Exception {
        a.setup.setup(folderA.toString());
        Instant seen = clock.instant();
        TestDevice.syncAll(folderA, folderB);
        clock.advance(Duration.ofMinutes(2));

        SetupService.Status status = b.setup.setup(folderB.toString());

        Refusal.OtherDevice.Info expected = new Refusal.OtherDevice.Info(MAC, seen.toString(),
                seen.plus(Duration.ofMinutes(15)).toString());
        assertThat(status.configured()).isFalse();
        assertThat(status.problem()).isNull();
        assertThat(status.otherDevice()).isEqualTo(expected);
        assertThat(status.notSynced()).isNull();
        assertThat(status.discarded()).isNull();
        assertThat(b.setup.status().otherDevice()).isEqualTo(expected); // GET /api/setup keeps answering it
        assertThat(b.setup.status().dataFolder()).isEqualTo(folderB.toString());

        assertThat(b.setup.retry().otherDevice()).isEqualTo(expected); // still open there

        closeMarker(folderA);
        TestDevice.syncAll(folderA, folderB);
        SetupService.Status opened = b.setup.retry();

        assertThat(opened.configured()).isTrue();
        assertThat(opened.otherDevice()).isNull();
        assertThat(marker(folderB).device()).isEqualTo(b.sync.device().id());
        assertThat(marker(folderB).generation()).isEqualTo(1);
    }

    @Test
    void aRememberedLibraryOpenElsewhereIsRefusedAtStart() throws Exception {
        a.setup.setup(folderA.toString());
        TestDevice.syncAll(folderA, folderB);
        closeMarker(folderA);
        TestDevice.syncAll(folderA, folderB);
        b.setup.setup(folderB.toString());
        TestDevice.syncAll(folderB, folderA); // B has it open now

        TestDevice again = a.restarted(home, MAC, clock);
        again.setup.startup(Optional.empty());

        assertThat(again.setup.status().otherDevice().name()).isEqualTo(PUNE);
        assertThat(again.holder.current()).isEmpty();
    }

    @Test
    void openHereAnywayIsTooSoonBefore15MinutesThenOpens() throws Exception {
        a.setup.setup(folderA.toString());
        TestDevice.syncAll(folderA, folderB);
        clock.advance(Duration.ofSeconds(14 * 60 + 59));
        b.setup.setup(folderB.toString());

        assertThatThrownBy(b.setup::openAnyway).isInstanceOfSatisfying(AtlasException.class, e -> {
            assertThat(e.code()).isEqualTo(ErrorCodes.TOO_SOON);
            assertThat(e.getMessage()).matches("You can open the library here from \\d\\d:\\d\\d\\.");
        });
        assertThat(b.setup.status().otherDevice()).isNotNull();

        clock.advance(Duration.ofSeconds(1));
        SetupService.Status status = b.setup.openAnyway();

        assertThat(status.configured()).isTrue();
        assertThat(marker(folderB).device()).isEqualTo(b.sync.device().id());
    }

    @Test
    void openHereAnywayWithoutAnotherDeviceIs409() {
        assertThatThrownBy(b.setup::openAnyway).isInstanceOfSatisfying(AtlasException.class,
                e -> assertThat(e.code()).isEqualTo(ErrorCodes.NOT_ON_OTHER_DEVICE));
        assertThatThrownBy(b.setup::continueDiscarded).isInstanceOfSatisfying(AtlasException.class,
                e -> assertThat(e.code()).isEqualTo(ErrorCodes.NOTHING_DISCARDED));
    }

    @Test
    void aMarkerThatArrivesBeforeItsSnapshotIsNotSyncedUntilTheSnapshotComes() throws Exception {
        a.setup.setup(folderA.toString());
        TestDevice.syncAll(folderA, folderB);
        a.write("newer");
        Publish.run(a.db(), folderA, a.sync.device(), clock.instant(), clock.instant(), null, millis -> { }, () -> { });
        closeMarker(folderA);
        TestDevice.sync(folderA, folderB, MarkerFile.NAME); // the marker first

        SetupService.Status status = b.setup.setup(folderB.toString());

        assertThat(status.notSynced()).isEqualTo(new Refusal.NotSynced.Info(folderB.toString()));
        assertThat(status.configured()).isFalse();
        assertThat(b.setup.status().notSynced()).isNotNull();
        assertThat(b.setup.retry().notSynced()).isNotNull();

        TestDevice.sync(folderA, folderB, Database.FILE_NAME);
        assertThat(b.setup.retry().configured()).isTrue();
        assertThat(b.has("newer")).isTrue();
    }

    @Test
    void unsentChangesAfterACrashAreSentAtTheNextStartOnTheSameDevice() throws Exception {
        a.setup.setup(folderA.toString());
        a.write("unsent");
        assertThat(snapshot(folderA).generation()).isEqualTo(1); // crashed before any publish

        TestDevice again = a.restarted(home, MAC, clock);
        again.setup.startup(Optional.empty());

        assertThat(again.holder.current()).isPresent();
        assertThat(snapshot(folderA).generation()).isEqualTo(2);
        assertThat(SyncState.read(again.db()).dirty()).isFalse();
        assertThat(again.sync.sentLastSession()).isTrue();
        TestDevice.syncAll(folderA, folderB);
        closeMarker(folderB);
        b.setup.setup(folderB.toString());
        assertThat(b.has("unsent")).isTrue();
    }

    @Test
    void aWorkingCopyInStepWithNothingUnsentSendsNothing() throws Exception {
        a.setup.setup(folderA.toString());

        TestDevice again = a.restarted(home, MAC, clock);
        again.setup.startup(Optional.empty());

        assertThat(snapshot(folderA).generation()).isEqualTo(1);
        assertThat(again.sync.sentLastSession()).isFalse();
    }

    @Test
    void unsentChangesAnotherDeviceOvertookAreDiscardedWithThePhotosLeftInTheFolder() throws Exception {
        a.setup.setup(folderA.toString());
        Instant inStep = clock.instant();
        TestDevice.syncAll(folderA, folderB);
        a.write("lost");
        String species = a.db().read(c -> {
            try (Statement st = c.createStatement();
                 var rs = st.executeQuery("SELECT id FROM taxon WHERE rank = 'species' ORDER BY id LIMIT 1")) {
                rs.next();
                return rs.getString(1);
            }
        });
        a.db().write(c -> {
            try (Statement st = c.createStatement()) {
                st.executeUpdate("INSERT INTO image (taxon_id, rel_path, file_name, number, original_name, file_size,"
                        + " modified_at, sha256, width, height, orientation, added_at, source) VALUES ('" + species
                        + "', 'images/x/IMG_2041.jpg', 'IMG_2041.jpg', 1, 'IMG_2041.jpg', 10, '2026-10-01T08:00:00Z',"
                        + " 'sha', 4, 3, 1, '2026-10-01T09:00:00Z', 'app')");
            }
            return null;
        });
        Files.createDirectories(folderA.resolve("images/x"));
        Files.writeString(folderA.resolve("images/x/IMG_2041.jpg"), "a photo");
        // A crashes; 20 minutes later B opens it anyway, changes it and quits
        clock.advance(Duration.ofMinutes(20));
        b.setup.setup(folderB.toString());
        b.setup.openAnyway();
        b.write("theirs");
        Publish.run(b.db(), folderB, b.sync.device(), clock.instant(), clock.instant(), null, millis -> { }, () -> { });
        Instant closed = clock.instant();
        closeMarker(folderB);
        TestDevice.syncAll(folderB, folderA);

        TestDevice again = a.restarted(home, MAC, clock);
        again.setup.startup(Optional.empty());

        SetupService.Status status = again.setup.status();
        assertThat(status.discarded()).isEqualTo(new Refusal.Discarded.Info(PUNE, closed.toString(), inStep.toString(),
                List.of("images/x/IMG_2041.jpg"), 0));
        assertThat(again.setup.status().discarded()).isNotNull(); // still there until Continue
        assertThat(again.setup.retry().discarded()).isNotNull();

        SetupService.Status opened = again.setup.continueDiscarded();

        assertThat(opened.configured()).isTrue();
        assertThat(again.has("theirs")).isTrue();
        assertThat(again.has("lost")).isFalse();
        assertThat(marker(folderA).device()).isEqualTo(again.sync.device().id());
    }
}
