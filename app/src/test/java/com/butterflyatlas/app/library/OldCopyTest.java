package com.butterflyatlas.app.library;

import com.butterflyatlas.app.AtlasException;
import com.butterflyatlas.app.ErrorCodes;
import com.butterflyatlas.app.config.AppConfig;
import com.butterflyatlas.app.config.ConfigStore;
import com.butterflyatlas.app.db.Database;
import com.butterflyatlas.app.testing.TestBundles;
import com.butterflyatlas.app.testing.TestPlaces;
import com.butterflyatlas.app.window.InstanceLock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The old copy a move leaves (change-library-folder spec §9, contract §3): Settings names it with the last backup, and
 * Delete old copy removes only what a library is made of, refusing the library in use and one another copy holds.
 */
class OldCopyTest {

    @TempDir
    Path home;

    private final LibraryHolder holder = new LibraryHolder();
    private SetupService setup;
    private Path current;
    private Path old;

    @BeforeEach
    void moved() throws Exception {
        old = home.resolve("Old");
        Library.open(old, TestBundles.fixture(), TestPlaces.bundles(), TestBundles.CLOCK);
        for (String file : new String[] {"images/Papilionidae/Papilio_1.jpg", ".thumbs/1-480-x.jpg", ".deleted/2_x.jpg",
                ".incoming/up.part", ".atlas.lock"}) {
            Path path = old.resolve(file);
            Files.createDirectories(path.getParent());
            Files.write(path, new byte[100]);
        }
        current = home.resolve("Current");
        setup = new SetupService(config(), holder, TestBundles.fixture(), TestPlaces.bundles(), home, TestBundles.CLOCK);
        setup.setup(current.toString());
        config().save(new AppConfig(current.toString(), "dark", old.toString(), null, true));
    }

    private ConfigStore config() {
        return new ConfigStore(home.resolve("config"));
    }

    private LibraryChange change(ChangeSteps steps) {
        return new LibraryChange(holder::library, setup, steps, folder -> Long.MAX_VALUE, LibraryChange.Background.NONE,
                (kind, from, to) -> { }, Optional::empty);
    }

    private void backup(String what, String finishedAt) {
        UnfinishedWorkTest.sql(holder.library().database(), "INSERT INTO backup_run (state, what, dest_root, folder, total,"
                + " bytes_total, started_at, finished_at) VALUES ('done', '" + what + "', 'E:\\\\', 'b', 0, 0, '"
                + finishedAt + "', '" + finishedAt + "')");
    }

    @Test
    void settingsNameTheOldCopyItsSizeAndTheLastBackupThatFinished() {
        LibraryChange change = change(ChangeSteps.REAL);
        LibraryChange.OldCopy copy = change.overview().oldCopy();
        assertThat(copy.folder()).isEqualTo(old.toString());
        assertThat(copy.size()).isEqualTo(LibraryChange.size(old));
        assertThat(copy.lastBackup()).as("no backup yet").isNull();

        backup("everything", "2026-10-27T10:00:00Z");
        backup("database", "2026-10-26T10:00:00Z");
        UnfinishedWorkTest.sql(holder.library().database(), "INSERT INTO backup_run (state, what, dest_root, folder, total,"
                + " bytes_total, started_at, finished_at) VALUES ('cancelled', 'database', 'E:', 'c', 0, 0,"
                + " '2026-10-29T10:00:00Z', '2026-10-29T10:00:00Z')");

        assertThat(change.overview().oldCopy().lastBackup())
                .isEqualTo(new LibraryChange.LastBackup("everything", "2026-10-27T10:00:00Z"));

        config().save(new AppConfig(current.toString(), "dark"));
        assertThat(change.overview().oldCopy()).as("none after a switch or once deleted").isNull();
    }

    @Test
    void deletingTakesOnlyWhatALibraryIsMadeOfAndListsWhatElseIsLeft() throws Exception {
        Files.writeString(old.resolve("notes.txt"), "mine");
        Files.createDirectories(old.resolve("Mine"));

        LibraryChange.Deleted deleted = change(ChangeSteps.REAL).deleteOldCopy();

        assertThat(deleted.deleted()).isTrue();
        assertThat(deleted.left()).containsExactlyInAnyOrder(old.resolve("Mine").toString(), old.resolve("notes.txt").toString());
        assertThat(deleted.leftCount()).isEqualTo(2);
        try (var entries = Files.list(old)) {
            assertThat(entries.map(p -> p.getFileName().toString()).sorted().toList()).containsExactly("Mine", "notes.txt");
        }
        assertThat(config().load()).contains(new AppConfig(current.toString(), "dark"));
        assertThat(current.resolve("atlas.db")).as("the library in use is not touched").isRegularFile();
    }

    @Test
    void anOldCopyWithNothingElseInItGoesWithItsFolder() {
        LibraryChange.Deleted deleted = change(ChangeSteps.REAL).deleteOldCopy();

        assertThat(deleted).isEqualTo(new LibraryChange.Deleted(true, java.util.List.of(), 0));
        assertThat(old).doesNotExist();
    }

    @Test
    void filesThatCannotBeDeletedAreCountedAndTheOldCopyIsKeptToTryAgain() {
        LibraryChange.Deleted first = change(new ChangeSteps() {
            @Override
            public void createFolder(Path folder) throws IOException {
                REAL.createFolder(folder);
            }

            @Override
            public void copyFile(Path source, Path target) throws IOException {
                REAL.copyFile(source, target);
            }

            @Override
            public void copyDatabase(Database database, Path target) throws IOException {
                REAL.copyDatabase(database, target);
            }

            @Override
            public String integrity(Path database) {
                return REAL.integrity(database);
            }

            @Override
            public void delete(Path path) throws IOException {
                if (path.getFileName().toString().equals("Papilio_1.jpg")) {
                    throw new IOException("in use");
                }
                REAL.delete(path);
            }
        }).deleteOldCopy();

        assertThat(first.deleted()).isFalse();
        assertThat(first.left()).containsExactly(old.resolve("images/Papilionidae/Papilio_1.jpg").toString());
        assertThat(first.leftCount()).isEqualTo(1);
        assertThat(old.resolve("atlas.db")).doesNotExist();
        assertThat(config().load().orElseThrow().hasOldCopy()).isTrue();

        LibraryChange.Deleted again = change(ChangeSteps.REAL).deleteOldCopy();

        assertThat(again.deleted()).isTrue();
        assertThat(old).doesNotExist();
        assertThat(config().load().orElseThrow().hasOldCopy()).isFalse();
    }

    @Test
    void itRefusesWhenThereIsNoOldCopyOrItIsTheLibraryInUseOrAnotherCopyHoldsIt() throws Exception {
        InstanceLock.Result other = InstanceLock.acquire(old);
        try {
            assertThatThrownBy(() -> change(ChangeSteps.REAL).deleteOldCopy()).isInstanceOfSatisfying(AtlasException.class,
                    e -> {
                        assertThat(e.code()).isEqualTo(ErrorCodes.LIBRARY_IN_USE);
                        assertThat(e.getMessage()).isEqualTo("Another copy of Butterfly Atlas is using this library.");
                    });
            assertThat(old.resolve("atlas.db")).isRegularFile();
        } finally {
            other.lock().close();
        }

        config().save(new AppConfig(current.toString(), "dark", current.toString(), null, true));
        assertThatThrownBy(() -> change(ChangeSteps.REAL).deleteOldCopy()).isInstanceOfSatisfying(AtlasException.class,
                e -> assertThat(e.code()).isEqualTo(ErrorCodes.LIBRARY_IN_USE));
        assertThat(current.resolve("atlas.db")).isRegularFile();

        config().save(new AppConfig(current.toString(), "dark"));
        assertThatThrownBy(() -> change(ChangeSteps.REAL).deleteOldCopy()).isInstanceOfSatisfying(AtlasException.class,
                e -> {
                    assertThat(e.kind()).isEqualTo(AtlasException.Kind.CONFLICT);
                    assertThat(e.code()).isEqualTo(ErrorCodes.NO_OLD_COPY);
                });
    }
}
