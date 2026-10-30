package com.butterflyatlas.app.library;

import com.butterflyatlas.app.AtlasException;
import com.butterflyatlas.app.ErrorCodes;
import com.butterflyatlas.app.db.Database;
import com.butterflyatlas.app.testing.TestBundles;
import com.butterflyatlas.app.testing.TestPlaces;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A move's copy and check (change-library-folder spec §5, §7, §11): a failure injected at every step, and Cancel at each
 * step before the switch. In each case the old library is byte for byte untouched and the copy is removed, or what
 * remains is named.
 */
class LibraryChangeTest {

    @TempDir
    Path dir;

    private Path old;
    private Path to;
    private Library library;
    private final List<String> background = new CopyOnWriteArrayList<>();
    private final List<Path> switched = new CopyOnWriteArrayList<>();
    private long freeSpace = Long.MAX_VALUE;

    @BeforeEach
    void library() throws Exception {
        old = dir.resolve("Old");
        to = dir.resolve("Drive").resolve("Butterfly_Atlas");
        library = Library.open(old, TestBundles.fixture(), TestPlaces.bundles(), TestBundles.CLOCK);
        write("images/Papilionidae/Papilio_1.jpg", 3000);
        write("images/Papilionidae/Papilio_2.jpg", 5000);
        write(".deleted/7_Papilio_3.jpg", 1000);
        write(".thumbs/1_480_x.jpg", 200);
        write(".thumbs/import/4/9.jpg", 100);
        write(".thumbs/.decode/work.png", 100);
        write(".incoming/upload.jpg.part", 100);
        write("images/Papilionidae/Papilio_4.jpg.part", 100);
        write(".atlas.lock", 0);
        Files.createDirectories(old.resolve("images").resolve("Empty_folder"));
        sql("INSERT INTO image (taxon_id, rel_path, file_name, file_size, modified_at, sha256, added_at, source) VALUES"
                + " ('A5', 'images/Papilionidae/Papilio_1.jpg', 'Papilio_1.jpg', 3000, 'm', 'a', 'n', 'app'),"
                + " ('A5', 'images/Papilionidae/Papilio_2.jpg', 'Papilio_2.jpg', 5000, 'm', 'b', 'n', 'app')");
        sql("INSERT INTO image (taxon_id, rel_path, file_name, file_size, modified_at, sha256, added_at, source, deleted_at,"
                + " deleted_rel_path) VALUES ('A5', 'images/Papilionidae/Papilio_3.jpg', 'Papilio_3.jpg', 1000, 'm', 'c',"
                + " 'n', 'app', 'd', '.deleted/7_Papilio_3.jpg')");
        Files.createDirectories(to.getParent());
    }

    private void write(String relative, int size) throws IOException {
        Path file = old.resolve(relative);
        Files.createDirectories(file.getParent());
        byte[] bytes = new byte[size];
        for (int i = 0; i < size; i++) {
            bytes[i] = (byte) (relative.hashCode() + i);
        }
        Files.write(file, bytes);
    }

    private void sql(String sql) {
        UnfinishedWorkTest.sql(library.database(), sql);
    }

    private LibraryChange change(ChangeSteps steps) {
        return new LibraryChange(() -> library, steps, folder -> folder.startsWith(dir.resolve("Drive")) ? freeSpace
                : Long.MAX_VALUE, new LibraryChange.Background() {
            @Override
            public void hold() {
                background.add("hold");
            }

            @Override
            public void release() {
                background.add("release");
            }
        }, (kind, from, target) -> switched.add(target));
    }

    /** Every file of the old library with its digest (the shared-memory index changes as SQLite reads). */
    private Map<String, String> contents() throws Exception {
        Map<String, String> out = new TreeMap<>();
        try (Stream<Path> walk = Files.walk(old)) {
            for (Path path : walk.filter(Files::isRegularFile).toList()) {
                String name = old.relativize(path).toString();
                if (!name.equals("atlas.db-shm")) {
                    out.put(name, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                            .digest(Files.readAllBytes(path))));
                }
            }
        }
        return out;
    }

    private static List<String> files(Path folder) throws IOException {
        try (Stream<Path> walk = Files.walk(folder)) {
            return walk.filter(Files::isRegularFile).map(p -> folder.relativize(p).toString().replace('\\', '/')).sorted()
                    .toList();
        }
    }

    private static LibraryChange.Changing done(LibraryChange change) throws InterruptedException {
        assertThat(change.await(Duration.ofSeconds(20))).isTrue();
        return change.changing().orElse(null);
    }

    /** The real steps, with what a test changes. */
    private static class Steps implements ChangeSteps {
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
            REAL.delete(path);
        }
    }

    @Test
    void aMoveCopiesTheLibraryButItsDatabaseFilesLockPartFilesAndTheDecodersFolderThenTheDatabaseAndChecksIt()
            throws Exception {
        Map<String, String> before = contents();
        LibraryChange change = change(new Steps());

        LibraryChange.Changing started = change.copyTo(to);
        assertThat(started.kind()).isEqualTo(LibraryChange.MOVE);
        assertThat(started.step()).isEqualTo(LibraryChange.COPYING);
        assertThat(started.canCancel()).isTrue();
        LibraryChange.Changing end = done(change);

        assertThat(end.step()).isEqualTo(LibraryChange.SWITCHING);
        assertThat(end.canCancel()).isFalse();
        assertThat(end.problem()).isNull();
        assertThat(end.files()).isEqualTo(new LibraryChange.Count(5, 5));
        assertThat(end.bytes()).isEqualTo(new LibraryChange.Count(9300, 9300));
        assertThat(switched).containsExactly(to);
        assertThat(files(to)).containsExactly(".deleted/7_Papilio_3.jpg", ".thumbs/1_480_x.jpg", ".thumbs/import/4/9.jpg",
                "atlas.db", "images/Papilionidae/Papilio_1.jpg", "images/Papilionidae/Papilio_2.jpg");
        assertThat(to.resolve("images").resolve("Empty_folder")).isDirectory();
        assertThat(Files.readAllBytes(to.resolve("images/Papilionidae/Papilio_2.jpg")))
                .isEqualTo(Files.readAllBytes(old.resolve("images/Papilionidae/Papilio_2.jpg")));
        assertThat(Files.getLastModifiedTime(to.resolve("images/Papilionidae/Papilio_1.jpg")))
                .isEqualTo(Files.getLastModifiedTime(old.resolve("images/Papilionidae/Papilio_1.jpg")));
        assertThat(UnfinishedWorkTest.rows(Database.open(to.resolve("atlas.db")), "SELECT rel_path FROM image ORDER BY id"))
                .containsExactly("images/Papilionidae/Papilio_1.jpg", "images/Papilionidae/Papilio_2.jpg",
                        "images/Papilionidae/Papilio_3.jpg");
        assertThat(contents()).isEqualTo(before);
        assertThat(background).containsExactly("hold");
        assertThatThrownBy(change::cancel).isInstanceOfSatisfying(AtlasException.class,
                e -> assertThat(e.code()).isEqualTo(ErrorCodes.CANNOT_CANCEL));
    }

    /** A failure at a step: the change failed with {@code problem}, the copy gone, the old library unchanged. */
    private LibraryChange.Changing failsWith(ChangeSteps steps, String problem) throws Exception {
        Map<String, String> before = contents();
        LibraryChange change = change(steps);

        change.copyTo(to);
        LibraryChange.Changing end = done(change);

        assertThat(end.step()).isEqualTo(LibraryChange.FAILED);
        assertThat(end.canCancel()).isFalse();
        assertThat(end.problem()).contains(problem);
        assertThat(end.left()).isNull();
        assertThat(to).doesNotExist();
        assertThat(to.getParent()).as("nothing but the new library folder is removed").isDirectory();
        assertThat(contents()).isEqualTo(before);
        assertThat(switched).isEmpty();
        assertThat(background).containsExactly("hold", "release");
        assertThat(change.blocks()).as("until it is dismissed").isTrue();
        change.dismiss();
        assertThat(change.changing()).isEmpty();
        return end;
    }

    @Test
    void aFullDriveStopsTheCopyAndSaysSo() throws Exception {
        AtomicInteger copies = new AtomicInteger();
        failsWith(new Steps() {
            @Override
            public void copyFile(Path source, Path target) throws IOException {
                if (copies.incrementAndGet() == 2) {
                    freeSpace = 0;
                    throw new IOException("No space left on device");
                }
                super.copyFile(source, target);
            }
        }, "Your library couldn't be copied to " + to + ". " + LibraryChange.FULL_THERE);
    }

    @Test
    void aFileOfTheLibraryThatCannotBeReadIsNamed() throws Exception {
        failsWith(new Steps() {
            @Override
            public void copyFile(Path source, Path target) throws IOException {
                if (source.getFileName().toString().equals("Papilio_2.jpg")) {
                    throw new AccessDeniedException(source.toString());
                }
                super.copyFile(source, target);
            }
        }, "A file in your library couldn't be read (images/Papilionidae/Papilio_2.jpg)");
    }

    @Test
    void aFolderThatCannotBeWrittenIsSaid() throws Exception {
        failsWith(new Steps() {
            @Override
            public void copyFile(Path source, Path target) throws IOException {
                throw new AccessDeniedException(target.toString() + ".part");
            }
        }, LibraryChange.NOT_WRITABLE_THERE);
    }

    @Test
    void aDestinationThatGoesAwayIsADriveThatCannotBeFound() throws Exception {
        AtomicInteger copies = new AtomicInteger();
        failsWith(new Steps() {
            @Override
            public void copyFile(Path source, Path target) throws IOException {
                if (copies.incrementAndGet() == 3) {
                    deleteTree(to);
                    throw new NoSuchFileException(target.toString());
                }
                super.copyFile(source, target);
            }
        }, LibraryChange.GONE_THERE);
    }

    @Test
    void aCopyOfTheWrongSizeFailsTheCheckNamingTheFile() throws Exception {
        failsWith(new Steps() {
            @Override
            public void copyFile(Path source, Path target) throws IOException {
                super.copyFile(source, target);
                if (source.getFileName().toString().equals("Papilio_1.jpg")) {
                    Files.write(target, new byte[10]);
                }
            }
        }, "The copy at " + to + " didn't match your library (1 file: images/Papilionidae/Papilio_1.jpg). "
                + LibraryChange.CHECK_THERE);
    }

    @Test
    void aPhotoTheCopyLacksFailsTheCheck() throws Exception {
        failsWith(new Steps() {
            @Override
            public void copyFile(Path source, Path target) throws IOException {
                super.copyFile(source, target);
                if (source.getFileName().toString().startsWith("7_Papilio_3")) {
                    Files.delete(target); // gone once copied: the size check and the photos' check both see it
                }
            }
        }, "(1 file: .deleted/7_Papilio_3.jpg)");
    }

    @Test
    void aDatabaseThatCannotBeCopiedIsSaid() throws Exception {
        failsWith(new Steps() {
            @Override
            public void copyDatabase(Database database, Path target) throws IOException {
                throw new IOException("disk I/O error");
            }
        }, "Your library's database couldn't be copied to " + to + ". ");
    }

    @Test
    void aCopiedDatabaseThatFailsItsCheckIsSaid() throws Exception {
        failsWith(new Steps() {
            @Override
            public String integrity(Path database) {
                return "*** in database main ***\nPage 3 is never used";
            }
        }, "The copy of your library's database at " + to + " didn't pass its check.");
    }

    @Test
    void aDamagedDatabaseCopyFailsItsCheck() throws Exception {
        failsWith(new Steps() {
            @Override
            public void copyDatabase(Database database, Path target) throws IOException {
                Files.write(target, "not a database at all, just some bytes in a file".repeat(100).getBytes());
            }
        }, "didn't pass its check");
    }

    @Test
    void settingsThatCannotBeSavedAreAFailureBeforeTheSwitch() throws Exception {
        Map<String, String> before = contents();
        LibraryChange change = new LibraryChange(() -> library, ChangeSteps.REAL, folder -> Long.MAX_VALUE,
                LibraryChange.Background.NONE, (kind, from, target) -> {
                    throw new java.io.UncheckedIOException(new IOException("read-only settings"));
                });

        change.copyTo(to);
        LibraryChange.Changing end = done(change);

        assertThat(end.step()).isEqualTo(LibraryChange.FAILED);
        assertThat(end.problem()).startsWith("Butterfly Atlas couldn't save where your library is, so it wasn't moved.");
        assertThat(to).doesNotExist();
        assertThat(contents()).isEqualTo(before);
    }

    @Test
    void whatCannotBeRemovedOfTheCopyIsNamedWithTheProblem() throws Exception {
        Map<String, String> before = contents();
        LibraryChange change = change(new Steps() {
            @Override
            public String integrity(Path database) {
                return "corrupt";
            }

            @Override
            public void delete(Path path) throws IOException {
                if (path.getFileName().toString().equals("Papilio_2.jpg")) {
                    throw new IOException("in use");
                }
                super.delete(path);
            }
        });

        change.copyTo(to);
        LibraryChange.Changing end = done(change);

        assertThat(end.step()).isEqualTo(LibraryChange.FAILED);
        assertThat(end.problem()).contains("didn't pass its check");
        assertThat(end.left()).isEqualTo(new LibraryChange.Left(to.toString(), 1));
        assertThat(files(to)).containsExactly("images/Papilionidae/Papilio_2.jpg");
        assertThat(contents()).isEqualTo(before);
    }

    /** Steps that stop at {@code step} until the test lets them go. */
    private static final class Blocking extends Steps {
        final String step;
        final CountDownLatch reached = new CountDownLatch(1);
        final CountDownLatch go = new CountDownLatch(1);

        Blocking(String step) {
            this.step = step;
        }

        private void at(String here) {
            if (here.equals(step)) {
                reached.countDown();
                try {
                    go.await(20, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        @Override
        public void copyFile(Path source, Path target) throws IOException {
            super.copyFile(source, target);
            if (source.getFileName().toString().equals("Papilio_1.jpg")) {
                at(LibraryChange.COPYING);
            }
        }

        @Override
        public void copyDatabase(Database database, Path target) throws IOException {
            super.copyDatabase(database, target);
            at(LibraryChange.COPYING_DATABASE);
        }

        @Override
        public String integrity(Path database) {
            String answer = super.integrity(database);
            at(LibraryChange.CHECKING);
            return answer;
        }
    }

    @Test
    void cancelAtEachStepBeforeTheSwitchRemovesTheCopyAndTheAppGoesOn() throws Exception {
        for (String step : List.of(LibraryChange.COPYING, LibraryChange.COPYING_DATABASE, LibraryChange.CHECKING)) {
            background.clear();
            Map<String, String> before = contents();
            Blocking steps = new Blocking(step);
            LibraryChange change = change(steps);
            change.copyTo(to);
            assertThat(steps.reached.await(20, TimeUnit.SECONDS)).isTrue();
            assertThat(change.changing().orElseThrow().step()).as(step).isEqualTo(step);

            LibraryChange.Changing cancelling = change.cancel();
            assertThat(cancelling.step()).isEqualTo(LibraryChange.CANCELLING);
            assertThat(cancelling.canCancel()).isFalse();
            steps.go.countDown();

            assertThat(done(change)).as(step).isNull();
            assertThat(change.blocks()).isFalse();
            assertThat(to).as(step).doesNotExist();
            assertThat(contents()).as(step).isEqualTo(before);
            assertThat(switched).as(step).isEmpty();
            assertThat(background).as(step).containsExactly("hold", "release");
        }
    }

    @Test
    void aCancelThatLeavesPartOfTheCopyWaitsWithTheNoteUntilDismissed() throws Exception {
        Blocking steps = new Blocking(LibraryChange.COPYING);
        LibraryChange change = change(new Steps() {
            @Override
            public void copyFile(Path source, Path target) throws IOException {
                steps.copyFile(source, target);
            }

            @Override
            public void delete(Path path) throws IOException {
                if (path.getFileName().toString().equals("Papilio_1.jpg")) {
                    throw new IOException("in use");
                }
                super.delete(path);
            }
        });
        change.copyTo(to);
        assertThat(steps.reached.await(20, TimeUnit.SECONDS)).isTrue();

        change.cancel();
        steps.go.countDown();
        LibraryChange.Changing end = done(change);

        assertThat(end.step()).isEqualTo(LibraryChange.FAILED);
        assertThat(end.problem()).isNull();
        assertThat(end.left()).isEqualTo(new LibraryChange.Left(to.toString(), 1));
        assertThat(change.blocks()).isTrue();
        change.dismiss();
        assertThat(change.blocks()).isFalse();
    }

    @Test
    void nothingToCancelOrARunningChangeIsRefusedInItsOwnWords() throws Exception {
        LibraryChange idle = change(new Steps());
        assertThatThrownBy(idle::cancel).isInstanceOfSatisfying(AtlasException.class, e -> {
            assertThat(e.kind()).isEqualTo(AtlasException.Kind.CONFLICT);
            assertThat(e.code()).isEqualTo(ErrorCodes.CANNOT_CANCEL);
        });
        idle.dismiss(); // nothing to put away is fine

        Blocking steps = new Blocking(LibraryChange.COPYING);
        LibraryChange change = change(steps);
        change.copyTo(to);
        assertThat(steps.reached.await(20, TimeUnit.SECONDS)).isTrue();
        try {
            assertThatThrownBy(change::dismiss).isInstanceOfSatisfying(AtlasException.class,
                    e -> assertThat(e.code()).isEqualTo(ErrorCodes.CANNOT_CANCEL));
            assertThatThrownBy(() -> change.copyTo(dir.resolve("Other"))).isInstanceOfSatisfying(AtlasException.class,
                    e -> assertThat(e.code()).isEqualTo(ErrorCodes.LIBRARY_CHANGING));
        } finally {
            steps.go.countDown();
            done(change);
        }
    }

    private static void deleteTree(Path folder) throws IOException {
        if (!Files.exists(folder)) {
            return;
        }
        List<Path> paths;
        try (Stream<Path> walk = Files.walk(folder)) {
            paths = new ArrayList<>(walk.sorted(java.util.Comparator.reverseOrder()).toList());
        }
        for (Path path : paths) {
            Files.delete(path);
        }
    }
}
