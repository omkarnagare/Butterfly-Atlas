package com.butterflyatlas.app.backup;

import com.butterflyatlas.app.AtlasException;
import com.butterflyatlas.app.exports.ExportStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Backups (contract §3–§5): the check, the start, the copy and its verification, pause, resume, cancel, history. */
class BackupsTest {

    @TempDir
    Path home;

    private BackupFixture fx;
    private Backups backups;

    @BeforeEach
    void fixture() {
        fx = new BackupFixture(home);
        backups = fx.backups;
    }

    @AfterEach
    void close() {
        backups.close();
    }

    private static void refused(Runnable action, AtlasException.Kind kind, String code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(AtlasException.class, e -> {
            assertThat(e.kind()).isEqualTo(kind);
            assertThat(e.code()).isEqualTo(code);
        });
    }

    private static List<String> names(Path folder) throws IOException {
        try (Stream<Path> all = Files.walk(folder)) {
            return all.filter(Files::isRegularFile).map(p -> folder.relativize(p).toString().replace('\\', '/')).sorted().toList();
        }
    }

    private long tableCount(Path dbFile, String table) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + dbFile); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + table)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    // ---- the check ----

    @Test
    void theCheckOfTheDatabaseAloneSaysItsSizeTwiceOverAndTheName() throws Exception {
        fx.photos(3);

        Backups.Check check = backups.check("database", fx.dest.toString());

        long size = Files.size(fx.db.file());
        assertThat(check.problem()).isNull();
        assertThat(check.photos()).isZero();
        assertThat(check.bytes()).isEqualTo(size);
        assertThat(check.neededBytes()).isEqualTo(2 * size);
        assertThat(check.freeBytes()).isEqualTo(Long.MAX_VALUE);
        assertThat(check.name()).isEqualTo("Butterfly_Atlas_backup_2026-10-11");
        assertThat(check.sameDrive()).isTrue();
    }

    @Test
    void theCheckOfEverythingCountsTheLivePhotosOnly() throws Exception {
        fx.photos(3);
        long deleted = fx.photo("A5", "gone.jpg", new byte[500]);
        fx.sql("UPDATE image SET deleted_at = '2026-10-10T00:00:00Z', deleted_rel_path = '.deleted/gone.jpg' WHERE id = "
                + deleted);
        long photoBytes = fx.db.read(c -> {
            try (var ps = c.prepareStatement("SELECT SUM(file_size) FROM image WHERE deleted_at IS NULL");
                 ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        });

        Backups.Check check = backups.check("everything", fx.dest.toString());

        long size = Files.size(fx.db.file());
        assertThat(check.photos()).isEqualTo(3);
        assertThat(check.bytes()).isEqualTo(photoBytes + size);
        assertThat(check.neededBytes()).isEqualTo(photoBytes + 2 * size);
    }

    @Test
    void theCheckNamesEachProblemInWords() throws Exception {
        assertThat(backups.check("database", null).problem().code()).isEqualTo("invalid");
        assertThat(backups.check("database", "relative/folder").problem().code()).isEqualTo("invalid");
        assertThat(backups.check("database", home.resolve("nowhere").toString()).problem())
                .isEqualTo(new Backups.Refusal("not_found", "That folder can't be found."));
        Path file = Files.writeString(home.resolve("a_file"), "x");
        assertThat(backups.check("database", file.toString()).problem().code()).isEqualTo("not_folder");
        assertThat(backups.check("database", fx.library.folder().toString()).problem().code()).isEqualTo("library_folder");
    }

    @Test
    void aFolderInsideTheLibraryIsRefused() throws Exception {
        Path inside = Files.createDirectories(fx.library.folder().resolve("images"));

        assertThat(backups.check("database", inside.toString()).problem().code()).isEqualTo("library_folder");
    }

    @Test
    void aFolderThatCannotBeWrittenIsRefused() throws Exception {
        assumeTrue(home.getFileSystem().supportedFileAttributeViews().contains("posix")
                && !"root".equals(System.getProperty("user.name")));
        Path locked = Files.createDirectories(home.resolve("locked"));
        Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("r-xr-xr-x"));
        try {
            assertThat(backups.check("database", locked.toString()).problem().code()).isEqualTo("not_writable");
        } finally {
            Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rwxr-xr-x"));
        }
    }

    @Test
    void notEnoughSpaceIsSaidWithTheSizes() {
        fx.photos(2);
        fx.free.set(100);

        Backups.Refusal problem = backups.check("everything", fx.dest.toString()).problem();

        assertThat(problem.code()).isEqualTo("no_space");
        assertThat(problem.message()).startsWith("There isn't enough space: ").contains("needed").contains("100 B free");
    }

    @Test
    void aPathTooLongForWindowsIsRefusedAsExportDoes() throws Exception {
        fx.photos(1);
        Path deep = home.resolve("Backup");
        for (int i = 0; i < 3; i++) {
            deep = Files.createDirectory(deep.resolve("d".repeat(100) + i));
        }

        Backups.Refusal problem = backups.check("everything", deep.toString()).problem();

        assertThat(problem.code()).isEqualTo("path_too_long");
        assertThat(problem.message()).contains("too long");
        assertThat(backups.check("database", deep.toString()).problem()).isNotNull(); // atlas.db.part is in it too
    }

    @Test
    void anUnknownKindIs400Invalid() {
        refused(() -> backups.check("photos", fx.dest.toString()), AtlasException.Kind.INVALID, "invalid");
        refused(() -> backups.start(new Backups.Request(null, fx.dest.toString())), AtlasException.Kind.INVALID, "invalid");
    }

    @Test
    void theNameTakesTheNextNumberWhenTheDayIsTaken() throws Exception {
        Files.createDirectory(fx.dest.resolve("Butterfly_Atlas_backup_2026-10-11"));
        Files.createDirectory(fx.dest.resolve("BUTTERFLY_ATLAS_BACKUP_2026-10-11_2"));

        assertThat(backups.check("database", fx.dest.toString()).name()).isEqualTo("Butterfly_Atlas_backup_2026-10-11_3");
        Backups.Backup made = backups.start(fx.request("database"));
        assertThat(made.folder()).isEqualTo("Butterfly_Atlas_backup_2026-10-11_3");
    }

    // ---- a backup of the database alone ----

    @Test
    void aDatabaseBackupIsDoneInTheAnswerWithAVerifiedCopyAndTheLastBackupRecorded() throws Exception {
        fx.photos(4);
        assertThat(backups.list().asked()).isFalse();
        assertThat(backups.list().due()).isTrue();

        Backups.Backup made = backups.start(fx.request("database"));

        assertThat(made.state()).isEqualTo("done");
        assertThat(made.what()).isEqualTo("database");
        assertThat(made.folder()).isEqualTo("Butterfly_Atlas_backup_2026-10-11");
        assertThat(made.path()).isEqualTo(fx.dest.resolve("Butterfly_Atlas_backup_2026-10-11").toString());
        assertThat(made.finishedAt()).isNotNull();
        assertThat(names(fx.folder(made))).containsExactly("atlas.db");
        assertThat(tableCount(fx.folder(made).resolve("atlas.db"), "image")).isEqualTo(4);
        assertThat(tableCount(fx.folder(made).resolve("atlas.db"), "library_setting")).isZero();
        Backups.Listing list = backups.list();
        assertThat(list.lastWhat()).isEqualTo("database");
        assertThat(list.lastAt()).isEqualTo("2026-10-11T09:00:00Z");
        assertThat(list.lastUnread()).isZero();
        assertThat(list.daysSince()).isZero();
        assertThat(list.due()).isFalse();
        assertThat(list.current()).isNull();
        assertThat(list.past()).extracting(Backups.Backup::id).containsExactly(made.id());
        assertThat(backups.status()).isEmpty();
    }

    @Test
    void theLastBackupIsOnlyEverSetByABackupThatIsDone() throws Exception {
        fx.photos(3);
        Backups.Backup made = backups.start(fx.request("everything"));
        assertThat(fx.await(made.id(), "done")).isTrue();
        String firstAt = backups.list().lastAt();

        fx.clock.advance(Duration.ofDays(8));
        assertThat(backups.list().daysSince()).isEqualTo(8);
        assertThat(backups.list().due()).isTrue();
        // Held inside its first photo and paused there, so the cancel cannot arrive after it has already finished.
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        backups.beforePhoto(id -> {
            if (inside.getCount() > 0) {
                inside.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        Backups.Backup cancelled = backups.start(fx.request("everything"));
        assertThat(inside.await(10, TimeUnit.SECONDS)).isTrue();
        backups.pause(cancelled.id());
        release.countDown();
        assertThat(fx.await(cancelled.id(), "paused")).isTrue();
        backups.cancel(cancelled.id(), false);

        assertThat(backups.list().lastAt()).isEqualTo(firstAt);
        assertThat(backups.list().due()).as("a cancelled backup is not a backup").isTrue();
    }

    // ---- a backup of everything ----

    @Test
    void everythingCopiesTheLivePhotosExactlyInTheLibrarysStructureAndNotTheCachesOrRecentlyDeleted() throws Exception {
        long one = fx.photo("A5", "first.jpg", "first photo bytes".repeat(300).getBytes());
        long two = fx.photo("A12", "second.jpg", "second photo bytes".repeat(500).getBytes());
        long deleted = fx.photo("A5", "deleted.jpg", "deleted".repeat(100).getBytes());
        fx.sql("UPDATE image SET deleted_at = '2026-10-10T00:00:00Z', deleted_rel_path = '.deleted/deleted.jpg' WHERE id = "
                + deleted);
        Files.createDirectories(fx.library.folder().resolve(".thumbs"));
        Files.writeString(fx.library.folder().resolve(".thumbs").resolve("t.jpg"), "thumbnail");
        Files.createDirectories(fx.library.folder().resolve(".deleted"));
        Files.writeString(fx.library.folder().resolve(".deleted").resolve("deleted.jpg"), "deleted");

        Backups.Backup made = backups.start(fx.request("everything"));
        assertThat(fx.await(made.id(), "done")).isTrue();

        Backups.Backup done = backups.list().past().get(0);
        assertThat(names(fx.folder(done))).containsExactly("atlas.db", "images/A12/second.jpg", "images/A5/first.jpg");
        assertThat(Files.readAllBytes(fx.folder(done).resolve("images/A5/first.jpg")))
                .isEqualTo(Files.readAllBytes(fx.file(one)));
        assertThat(Files.readAllBytes(fx.folder(done).resolve("images/A12/second.jpg")))
                .isEqualTo(Files.readAllBytes(fx.file(two)));
        assertThat(done.total()).isEqualTo(2);
        assertThat(done.done()).isEqualTo(2);
        assertThat(done.copied()).isEqualTo(2);
        assertThat(done.skipped()).isZero();
        assertThat(done.failed()).isZero();
        assertThat(done.bytesDone()).as("the photos' sizes").isEqualTo(17 * 300 + 18 * 500);
        assertThat(done.bytesTotal()).isEqualTo(17 * 300 + 18 * 500 + Files.size(fx.db.file()));
        assertThat(backups.list().lastWhat()).isEqualTo("everything");
        assertThat(backups.list().lastUnread()).isZero();
        try (Stream<Path> all = Files.walk(fx.folder(done))) {
            assertThat(all.map(p -> p.getFileName().toString())).noneMatch(n -> n.endsWith(".part"));
        }
        assertThat(Files.exists(fx.library.folder().resolve("images/A5/first.jpg"))).as("originals are only read").isTrue();
    }

    @Test
    void aPhotoThatIsMissingOrVanishesIsSkippedWithItsReasonAndTheBackupStillCounts() throws Exception {
        long one = fx.photo("A5", "one.jpg", "one".repeat(100).getBytes());
        long two = fx.photo("A5", "two.jpg", "two".repeat(100).getBytes());
        long three = fx.photo("A5", "three.jpg", "three".repeat(100).getBytes());
        Files.delete(fx.file(one)); // gone before the backup begins
        backups.beforePhoto(id -> {
            if (id == three) {
                try {
                    Files.delete(fx.file(three)); // gone while the backup is under way
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            }
        });

        Backups.Backup made = backups.start(fx.request("everything"));
        assertThat(fx.await(made.id(), "done")).isTrue();

        Backups.Detail detail = backups.detail(made.id());
        assertThat(detail.backup().copied()).isEqualTo(1);
        assertThat(detail.backup().skipped()).isEqualTo(2);
        assertThat(detail.problems()).extracting(BackupStore.Problem::kind).containsOnly("skipped");
        assertThat(detail.problems()).extracting(BackupStore.Problem::file).containsExactlyInAnyOrder("one.jpg", "three.jpg");
        assertThat(detail.problems()).extracting(BackupStore.Problem::reason)
                .containsOnly("The photo was removed while it was being copied.");
        assertThat(detail.problems()).extracting(BackupStore.Problem::rel).contains("images/A5/one.jpg");
        assertThat(detail.moreProblems()).isZero();
        assertThat(backups.list().lastUnread()).isEqualTo(2);
        assertThat(names(fx.folder(detail.backup()))).containsExactly("atlas.db", "images/A5/two.jpg");
        assertThat(two).isPositive();
    }

    @Test
    void aPhotoThatCannotBeReadIsFailedWithItsReason() throws Exception {
        assumeTrue(home.getFileSystem().supportedFileAttributeViews().contains("posix")
                && !"root".equals(System.getProperty("user.name")));
        long one = fx.photo("A5", "one.jpg", "one".repeat(100).getBytes());
        fx.photo("A5", "two.jpg", "two".repeat(100).getBytes());
        Files.setPosixFilePermissions(fx.file(one), PosixFilePermissions.fromString("---------"));
        try {
            Backups.Backup made = backups.start(fx.request("everything"));
            assertThat(fx.await(made.id(), "done")).isTrue();

            Backups.Detail detail = backups.detail(made.id());
            assertThat(detail.backup().failed()).isEqualTo(1);
            assertThat(detail.problems()).singleElement().satisfies(p -> {
                assertThat(p.kind()).isEqualTo("failed");
                assertThat(p.reason()).isEqualTo("The photo could not be read.");
            });
            assertThat(backups.list().lastUnread()).isEqualTo(1);
        } finally {
            Files.setPosixFilePermissions(fx.file(one), PosixFilePermissions.fromString("rw-r--r--"));
        }
    }

    @Test
    void aCopyWhoseSizeIsNotTheOriginalsIsDeletedAndFailed() throws Exception {
        fx.photo("A5", "one.jpg", "one".repeat(100).getBytes());
        fx.photo("A5", "two.jpg", "two".repeat(100).getBytes());
        AtomicInteger writes = new AtomicInteger();
        backups.afterWrite(part -> {
            if (writes.incrementAndGet() == 1) {
                try {
                    Files.write(part, new byte[10]); // the drive wrote less than it was given
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            }
        });

        Backups.Backup made = backups.start(fx.request("everything"));
        assertThat(fx.await(made.id(), "done")).isTrue();

        Backups.Detail detail = backups.detail(made.id());
        assertThat(detail.backup().failed()).isEqualTo(1);
        assertThat(detail.problems()).singleElement().satisfies(p -> {
            assertThat(p.kind()).isEqualTo("failed");
            assertThat(p.reason()).isEqualTo("The copy was not the same size as the original, so it was deleted.");
        });
        assertThat(names(fx.folder(detail.backup()))).containsExactly("atlas.db", "images/A5/two.jpg");
        assertThat(backups.list().lastUnread()).isEqualTo(1);
    }

    @Test
    void aDatabaseCopyThatCannotBeReadBackPausesTheBackupAndSetsNoDate() throws Exception {
        fx.photos(2);
        backups.afterDatabase(copy -> {
            try {
                Files.writeString(copy, "this is no longer a database ".repeat(500));
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        });

        Backups.Backup made = backups.start(fx.request("everything"));

        assertThat(fx.await(made.id(), "paused")).isTrue();
        BackupStore.Run run = backups.store().run(made.id());
        assertThat(run.pausedBy()).isEqualTo("write_errors");
        assertThat(run.notice()).contains("could not be read back").endsWith("Check the drive, then resume.");
        assertThat(names(fx.folder(Backups.Backup.of(run)))).as("the photos, not the copy that failed").hasSize(2);
        assertThat(backups.list().lastAt()).isNull();
        backups.afterDatabase(copy -> { });
        backups.resume(made.id());
        assertThat(fx.await(made.id(), "done")).isTrue();
        assertThat(names(fx.folder(Backups.Backup.of(backups.store().run(made.id()))))).hasSize(3);
    }

    // ---- the database copy: last, clean, and never replacing a file ----

    @Test
    void theDatabaseIsCopiedAfterThePhotosSoItMatchesThePhotoTreeAsItEnded() throws Exception {
        fx.photos(3);
        AtomicInteger added = new AtomicInteger();
        backups.beforePhoto(id -> {
            if (added.getAndIncrement() == 0) {
                fx.photo("A5", "late.jpg", "added while the backup copies ".repeat(30).getBytes());
            }
        });
        List<String> order = new java.util.concurrent.CopyOnWriteArrayList<>();
        backups.afterWrite(part -> order.add("photo"));
        backups.afterDatabase(copy -> order.add("database"));

        Backups.Backup made = backups.start(fx.request("everything"));
        assertThat(fx.await(made.id(), "done")).isTrue();

        assertThat(order).containsExactly("photo", "photo", "photo", "photo", "database");
        Path copy = fx.folder(made).resolve("atlas.db");
        assertThat(tableCount(copy, "image")).as("the photo added meanwhile is in the copy of the database").isEqualTo(4);
        assertThat(names(fx.folder(made))).contains("images/A5/late.jpg", "atlas.db");
    }

    @Test
    void theCopyOfTheDatabaseHoldsNoRecordOfThisBackupNorOfTheLastOne() throws Exception {
        fx.photos(2);
        backups.start(fx.request("database"));
        assertThat(backups.list().lastAt()).isNotNull();

        Backups.Backup made = backups.start(fx.request("everything"));
        assertThat(fx.await(made.id(), "done")).isTrue();

        Path copy = fx.folder(made).resolve("atlas.db");
        assertThat(tableCount(copy, "backup_run WHERE id = " + made.id())).isZero();
        assertThat(tableCount(copy, "backup_run WHERE state IN ('running', 'paused')")).isZero();
        assertThat(tableCount(copy, "backup_file WHERE run_id = " + made.id())).isZero();
        assertThat(tableCount(copy, "backup_problem WHERE run_id = " + made.id())).isZero();
        assertThat(tableCount(copy, "library_setting WHERE key LIKE 'backup.last_%'")).isZero();
        assertThat(tableCount(copy, "image")).isEqualTo(2);
        assertThat(tableCount(fx.dbFile(), "library_setting WHERE key LIKE 'backup.last_%'")).as("the library itself keeps them")
                .isEqualTo(3);
    }

    @Test
    void aDatabaseCopyCutShortAfterThePhotosIsMadeAgainWithoutCopyingThePhotosTwice() throws Exception {
        fx.photos(2);
        backups.afterDatabase(copy -> {
            try {
                Files.writeString(copy, "this is no longer a database ".repeat(500));
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        });
        Backups.Backup made = backups.start(fx.request("everything"));
        assertThat(fx.await(made.id(), "paused")).isTrue();
        assertThat(backups.store().run(made.id()).copied()).as("the photos came first").isEqualTo(2);
        assertThat(names(fx.folder(made))).containsExactly("images/A5/p1.jpg", "images/A5/p2.jpg");
        backups.afterDatabase(copy -> { });

        backups.resume(made.id());

        assertThat(fx.await(made.id(), "done")).isTrue();
        BackupStore.Run done = backups.store().run(made.id());
        assertThat(done.copied()).isEqualTo(2);
        assertThat(done.total()).isEqualTo(2);
        assertThat(names(fx.folder(made))).containsExactly("atlas.db", "images/A5/p1.jpg", "images/A5/p2.jpg");
    }

    @Test
    void aBackupPausedBetweenPhotosFinishesWithTheDatabaseLastWhenResumed() throws Exception {
        Backups.Backup paused = pausedBackup();
        assertThat(names(fx.folder(paused))).as("no database yet: it is copied last").doesNotContain("atlas.db");
        backups.beforePhoto(id -> { });

        backups.resume(paused.id());

        assertThat(fx.await(paused.id(), "done")).isTrue();
        assertThat(names(fx.folder(paused))).hasSize(5).contains("atlas.db");
    }

    @Test
    void aFileAlreadyNamedAtlasDbInTheBackupFolderIsNeverReplacedTheBackupPausesInWords() throws Exception {
        fx.photos(2);
        backups.beforePhoto(id -> {
            try {
                Files.writeString(fx.dest.resolve("Butterfly_Atlas_backup_2026-10-11").resolve("atlas.db"), "not ours");
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        });
        Backups.Backup made = backups.start(fx.request("everything"));

        assertThat(fx.await(made.id(), "paused")).isTrue();

        BackupStore.Run run = backups.store().run(made.id());
        assertThat(run.pausedBy()).isEqualTo("write_errors");
        assertThat(run.notice()).contains("atlas.db").contains("already").contains("resume");
        assertThat(Files.readString(fx.folder(made).resolve("atlas.db"))).isEqualTo("not ours");
        assertThat(backups.list().lastAt()).isNull();

        Files.delete(fx.folder(made).resolve("atlas.db"));
        backups.resume(made.id());

        assertThat(fx.await(made.id(), "done")).isTrue();
        assertThat(tableCount(fx.folder(made).resolve("atlas.db"), "image")).isEqualTo(2);
    }

    // ---- a resume trusts nothing it recorded ----

    @Test
    void aCopiedPhotoThatWentMissingBetweenPauseAndResumeIsCopiedAgain() throws Exception {
        Backups.Backup paused = pausedBackup();
        Path gone = fx.folder(paused).resolve("images/A5/p1.jpg");
        Files.delete(gone);
        backups.beforePhoto(id -> { });

        backups.resume(paused.id());

        assertThat(fx.await(paused.id(), "done")).isTrue();
        BackupStore.Run done = backups.store().run(paused.id());
        assertThat(done.copied()).as("each photo once").isEqualTo(4);
        assertThat(done.failed()).isZero();
        assertThat(done.total()).isEqualTo(4);
        assertThat(Files.readAllBytes(gone)).isEqualTo(Files.readAllBytes(fx.library.folder().resolve("images/A5/p1.jpg")));
        assertThat(names(fx.folder(paused))).hasSize(5);
    }

    @Test
    void aCopiedPhotoThatWentShortBetweenPauseAndResumeIsFailedWithItsReasonNotCalledCopied() throws Exception {
        Backups.Backup paused = pausedBackup();
        Path short_ = fx.folder(paused).resolve("images/A5/p2.jpg");
        Files.write(short_, new byte[] {1, 2, 3});
        backups.beforePhoto(id -> { });

        backups.resume(paused.id());

        assertThat(fx.await(paused.id(), "done")).isTrue();
        BackupStore.Run done = backups.store().run(paused.id());
        assertThat(done.copied()).isEqualTo(3);
        assertThat(done.failed()).isEqualTo(1);
        BackupStore.Details details = backups.store().details(paused.id(), 10);
        assertThat(details.problems()).singleElement().satisfies(problem -> {
            assertThat(problem.rel()).isEqualTo("images/A5/p2.jpg");
            assertThat(problem.reason()).isEqualTo("The copy in the backup folder was changed afterwards, so it was left as it is.");
        });
        assertThat(backups.list().lastUnread()).isEqualTo(1);
        assertThat(Files.size(short_)).as("a changed file is left alone").isEqualTo(3);
    }

    @Test
    void aDatabaseCopyRecordedButNotThereIsMadeAgainBeforeTheBackupCanBeDone() throws Exception {
        Backups.Backup paused = pausedBackup();
        backups.store().addFile(paused.id(), "atlas.db", 10, 0); // recorded, but no such file
        backups.beforePhoto(id -> { });

        backups.resume(paused.id());

        assertThat(fx.await(paused.id(), "done")).isTrue();
        assertThat(tableCount(fx.folder(paused).resolve("atlas.db"), "image")).isEqualTo(4);
    }

    // ---- smaller truths ----

    @Test
    void aFolderThatIsALinkInsideTheBackupFolderIsNeverWrittenThroughTheirPhotosFailInWords() throws Exception {
        assumeTrue(home.getFileSystem().supportedFileAttributeViews().contains("posix"));
        fx.photos(2);
        Path outside = Files.createDirectories(home.resolve("Elsewhere"));
        AtomicInteger seen = new AtomicInteger();
        backups.beforePhoto(id -> {
            if (seen.getAndIncrement() == 0) {
                try {
                    Files.createSymbolicLink(fx.dest.resolve("Butterfly_Atlas_backup_2026-10-11").resolve("images"), outside);
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            }
        });

        Backups.Backup made = backups.start(fx.request("everything"));
        assertThat(fx.await(made.id(), "done")).isTrue();

        BackupStore.Run done = backups.store().run(made.id());
        assertThat(done.failed()).isEqualTo(2);
        assertThat(done.copied()).isZero();
        assertThat(backups.store().details(made.id(), 10).problems()).extracting(BackupStore.Problem::reason)
                .containsOnly(BackupCopier.NOT_A_FOLDER);
        try (Stream<Path> all = Files.walk(outside)) {
            assertThat(all.filter(Files::isRegularFile)).as("nothing was written through the link").isEmpty();
        }
    }

    @Test
    void removeAlsoRemovesTheEmptyFoldersTheBackupMadeSoItReportsTruthfully() throws Exception {
        Backups.Backup paused = pausedBackup();
        Path folder = fx.folder(paused);
        Files.createDirectories(folder.resolve("images/A12/Danaus_[A12]")); // made for a photo that failed to write
        backups.beforePhoto(id -> { });

        Backups.Backup cancelled = backups.cancel(paused.id(), true);

        assertThat(cancelled.notRemoved()).isZero();
        assertThat(folder).as("nothing left but what is not ours: nothing").doesNotExist();
    }

    @Test
    void removeKeepsAFolderThatHoldsSomeoneElsesFile() throws Exception {
        Backups.Backup paused = pausedBackup();
        Path folder = fx.folder(paused);
        Files.createDirectories(folder.resolve("images/A12"));
        Files.writeString(folder.resolve("images/A12/notes.txt"), "someone else's file");

        Backups.Backup cancelled = backups.cancel(paused.id(), true);

        assertThat(cancelled.state()).isEqualTo("cancelled");
        assertThat(names(folder)).containsExactly("images/A12/notes.txt");
    }

    // ---- one at a time, the gate, history ----

    @Test
    void onlyOneBackupAtATimeAndTheCheckSaysSo() throws Exception {
        fx.photos(3);
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        backups.beforePhoto(id -> {
            inside.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        Backups.Backup first = backups.start(fx.request("everything"));
        assertThat(inside.await(10, TimeUnit.SECONDS)).isTrue();

        refused(() -> backups.start(fx.request("database")), AtlasException.Kind.CONFLICT, "backup_running");
        assertThat(backups.check("database", fx.dest.toString()).problem().code()).isEqualTo("backup_running");
        refused(() -> backups.delete(first.id()), AtlasException.Kind.CONFLICT, "backup_running");
        assertThat(backups.copying()).isTrue();
        assertThat(backups.current()).hasValueSatisfying(b -> assertThat(b.id()).isEqualTo(first.id()));
        release.countDown();
        assertThat(fx.await(first.id(), "done")).isTrue();
        assertThat(backups.start(fx.request("database")).state()).isEqualTo("done");
    }

    @Test
    void aTaxonomyUpdateHoldingTheGateRefusesAStartAndAResume() throws Exception {
        fx.library.gate().close();
        try {
            refused(() -> backups.start(fx.request("database")), AtlasException.Kind.CONFLICT, "taxonomy_updating");
        } finally {
            fx.library.gate().open();
        }
        Backups.Backup paused = pausedBackup();
        fx.library.gate().close();
        try {
            refused(() -> backups.resume(paused.id()), AtlasException.Kind.CONFLICT, "taxonomy_updating");
        } finally {
            fx.library.gate().open();
        }
    }

    /** A backup of everything paused by the user after its first photo. */
    private Backups.Backup pausedBackup() throws Exception {
        fx.photos(4);
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger seen = new AtomicInteger();
        backups.beforePhoto(id -> {
            if (seen.incrementAndGet() == 2) {
                inside.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        Backups.Backup made = backups.start(fx.request("everything"));
        assertThat(inside.await(10, TimeUnit.SECONDS)).isTrue();
        backups.pause(made.id());
        release.countDown();
        assertThat(fx.await(made.id(), "paused")).isTrue();
        return Backups.Backup.of(backups.store().run(made.id()));
    }

    @Test
    void pausingStopsAfterTheCurrentPhotoAndResumingGoesOnWhereItStopped() throws Exception {
        Backups.Backup paused = pausedBackup();

        assertThat(paused.pausedBy()).isEqualTo("user");
        assertThat(paused.copied()).isEqualTo(2); // the photo being copied when the pause came is finished
        assertThat(paused.done()).isEqualTo(2);
        assertThat(names(fx.folder(paused))).hasSize(2); // two photos: the database is copied last
        assertThat(backups.status()).hasValueSatisfying(s -> assertThat(s.state()).isEqualTo("paused"));
        assertThat(backups.pause(paused.id()).state()).as("pausing a paused one leaves it").isEqualTo("paused");
        backups.beforePhoto(id -> { });

        backups.resume(paused.id());
        assertThat(fx.await(paused.id(), "done")).isTrue();

        Backups.Backup done = Backups.Backup.of(backups.store().run(paused.id()));
        assertThat(done.copied()).isEqualTo(4);
        assertThat(done.total()).isEqualTo(4);
        assertThat(names(fx.folder(done))).hasSize(5);
        assertThat(backups.list().lastWhat()).isEqualTo("everything");
        refused(() -> backups.pause(paused.id()), AtlasException.Kind.CONFLICT, "backup_finished");
        refused(() -> backups.resume(paused.id()), AtlasException.Kind.CONFLICT, "backup_finished");
        refused(() -> backups.cancel(paused.id(), true), AtlasException.Kind.CONFLICT, "backup_finished");
    }

    @Test
    void cancelWithKeepLeavesTheCopiesAndSetsNoDate() throws Exception {
        Backups.Backup paused = pausedBackup();

        Backups.Backup cancelled = backups.cancel(paused.id(), false);

        assertThat(cancelled.state()).isEqualTo("cancelled");
        assertThat(cancelled.cancel()).isEqualTo("keep");
        assertThat(names(fx.folder(cancelled))).hasSize(2);
        assertThat(backups.list().lastAt()).isNull();
        assertThat(backups.list().current()).isNull();
    }

    @Test
    void cancelWithRemoveDeletesOnlyTheFilesItWroteThatAreUnchangedAndCountsTheRest() throws Exception {
        Backups.Backup paused = pausedBackup();
        Path folder = fx.folder(paused);
        Path stranger = Files.writeString(folder.resolve("notes.txt"), "someone else's file");
        Path copy = folder.resolve("images/A5/p1.jpg");
        Files.writeString(copy, "edited afterwards");
        Files.setLastModifiedTime(copy, java.nio.file.attribute.FileTime.fromMillis(1_000_000));

        Backups.Backup cancelled = backups.cancel(paused.id(), true);

        assertThat(cancelled.cancel()).isEqualTo("remove");
        assertThat(cancelled.notRemoved()).isEqualTo(1);
        assertThat(names(folder)).containsExactlyInAnyOrder("notes.txt", "images/A5/p1.jpg");
        assertThat(stranger).exists();
        assertThat(Files.exists(folder.resolve("atlas.db"))).isFalse();
    }

    @Test
    void cancelWithRemoveOfACleanBackupRemovesTheFolderToo() throws Exception {
        Backups.Backup paused = pausedBackup();

        backups.cancel(paused.id(), true);

        assertThat(fx.folder(paused)).doesNotExist();
        assertThat(fx.dest).isDirectory();
    }

    @Test
    void theHistoryCanBeForgottenOneByOneOrAllAtOnceButNeverTheFiles() throws Exception {
        Backups.Backup a = backups.start(fx.request("database"));
        Backups.Backup b = backups.start(fx.request("database"));
        Backups.Backup c = backups.start(fx.request("database"));

        backups.delete(a.id());
        assertThat(backups.list().past()).extracting(Backups.Backup::id).containsExactly(c.id(), b.id());
        assertThat(fx.folder(a)).isDirectory();
        refused(() -> backups.detail(a.id()), AtlasException.Kind.NOT_FOUND, "not_found");
        refused(() -> backups.delete(a.id()), AtlasException.Kind.NOT_FOUND, "not_found");

        assertThat(backups.clear()).isEqualTo(2);
        assertThat(backups.list().past()).isEmpty();
        assertThat(fx.folder(b)).isDirectory();
        assertThat(backups.list().lastWhat()).as("forgetting the list keeps the last backup's date").isEqualTo("database");
    }

    // ---- the drive ----

    @Test
    void aFullDrivePausesWithItsNoticeAndResumingContinuesAfterThePhotosAlreadyCopied() throws Exception {
        fx.photos(6);
        AtomicInteger writes = new AtomicInteger();
        backups.opener(part -> {
            if (writes.incrementAndGet() >= 3) {
                fx.free.set(10);
                throw new IOException("No space left on device");
            }
            return Files.newOutputStream(part, java.nio.file.StandardOpenOption.CREATE_NEW,
                    java.nio.file.StandardOpenOption.WRITE);
        });

        Backups.Backup made = backups.start(fx.request("everything"));
        assertThat(fx.await(made.id(), "paused")).isTrue();

        BackupStore.Run run = backups.store().run(made.id());
        assertThat(run.pausedBy()).isEqualTo("drive");
        assertThat(run.notice()).startsWith("The drive is full: about ").endsWith("more is needed.");
        assertThat(run.copied()).isEqualTo(2);
        assertThat(run.failed()).as("the photo whose write failed is not blamed").isZero();
        assertThat(names(fx.folder(made))).hasSize(2);
        assertThat(backups.status()).hasValueSatisfying(s -> assertThat(s.pausedBy()).isEqualTo("drive"));

        fx.free.set(Long.MAX_VALUE);
        backups.opener(part -> Files.newOutputStream(part, java.nio.file.StandardOpenOption.CREATE_NEW,
                java.nio.file.StandardOpenOption.WRITE));
        backups.resume(made.id());
        assertThat(fx.await(made.id(), "done")).isTrue();
        Backups.Backup done = Backups.Backup.of(backups.store().run(made.id()));
        assertThat(done.copied()).isEqualTo(6);
        assertThat(done.failed()).isZero();
        assertThat(names(fx.folder(done))).hasSize(7);
    }

    @Test
    void aFolderThatGoesAwayPausesAsDriveAndStaysPausedUntilItIsBack() throws Exception {
        fx.photos(5);
        Path away = home.resolve("moved_away");
        backups.beforePhoto(id -> {
            try {
                if (id == 3 && Files.isDirectory(fx.dest.resolve("Butterfly_Atlas_backup_2026-10-11"))) {
                    Files.move(fx.dest.resolve("Butterfly_Atlas_backup_2026-10-11"), away);
                }
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        });

        Backups.Backup made = backups.start(fx.request("everything"));
        assertThat(fx.await(made.id(), "paused")).isTrue();

        BackupStore.Run run = backups.store().run(made.id());
        assertThat(run.pausedBy()).isEqualTo("drive");
        assertThat(run.notice()).isEqualTo("The backup folder can't be reached. Reconnect the drive, then resume.");
        assertThat(backups.resume(made.id()).state()).as("still gone: stays paused").isEqualTo("paused");
        assertThat(backups.store().run(made.id()).pausedBy()).isEqualTo("drive");

        Files.move(away, fx.dest.resolve("Butterfly_Atlas_backup_2026-10-11"));
        backups.beforePhoto(id -> { });
        backups.resume(made.id());
        assertThat(fx.await(made.id(), "done")).isTrue();
        assertThat(backups.store().run(made.id()).copied()).isEqualTo(5);
    }

    @Test
    void fiveWritesInARowThatFailPauseTheBackupWithoutBlamingThePhotos() throws Exception {
        fx.photos(8);
        backups.opener(part -> {
            throw new IOException("Input/output error");
        });

        Backups.Backup made = backups.start(fx.request("everything"));
        assertThat(fx.await(made.id(), "paused")).isTrue();

        BackupStore.Run run = backups.store().run(made.id());
        assertThat(run.pausedBy()).isEqualTo("write_errors");
        assertThat(run.notice()).isEqualTo("Several photos in a row could not be written. Check the drive, then resume.");
        assertThat(run.failed()).isZero();
        assertThat(run.done()).isZero();
    }

    // ---- closing the app ----

    @Test
    void closingTheAppMidBackupLeavesItPausedAsClosedAtTheNextStartAndItContinuesWhenResumed() throws Exception {
        fx.photos(4);
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger seen = new AtomicInteger();
        backups.beforePhoto(id -> {
            if (seen.incrementAndGet() == 2) {
                inside.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        Backups.Backup made = backups.start(fx.request("everything"));
        assertThat(inside.await(10, TimeUnit.SECONDS)).isTrue();

        backups.beginClose();
        release.countDown();
        backups.close();

        BackupStore.Run left = backups.store().run(made.id());
        assertThat(left.state()).as("left running: the next start says why").isEqualTo("running");
        assertThat(left.copied()).isEqualTo(2);

        Backups next = new Backups(() -> fx.library, fx.clock, java.time.ZoneOffset.UTC, p -> Long.MAX_VALUE);
        try {
            assertThat(next.startup()).isEqualTo(1);
            BackupStore.Run closed = next.store().run(made.id());
            assertThat(closed.state()).isEqualTo("paused");
            assertThat(closed.pausedBy()).isEqualTo("closed");
            assertThat(closed.notice()).isEqualTo("This backup stopped when Butterfly Atlas closed.");
            assertThat(next.copying()).as("nothing starts by itself").isFalse();
            assertThat(next.status()).hasValueSatisfying(s -> assertThat(s.pausedBy()).isEqualTo("closed"));
            assertThat(next.photoRefusal()).as("the card's buttons here: Continue backup, Discard…").contains("A paused"
                    + " backup still holds your photos, so they can't be changed yet. Continue the backup and let it finish,"
                    + " or discard it, change your photos, then start a new backup.");

            next.resume(made.id());
            assertThat(BackupFixture.await(() -> "done".equals(next.store().run(made.id()).state()))).isTrue();
            assertThat(next.store().run(made.id()).copied()).isEqualTo(4);
        } finally {
            next.close();
        }
    }

    @Test
    void discardingAClosedBackupIsACancelThatKeepsTheFiles() throws Exception {
        fx.photos(2);
        Backups.Backup made = backups.start(fx.request("everything"));
        assertThat(fx.await(made.id(), "done")).isTrue();
        Backups.Backup second = backups.start(fx.request("database"));
        assertThat(second.state()).isEqualTo("done");

        fx.sql("UPDATE backup_run SET state = 'running' WHERE id = " + second.id());
        // a fresh service sees a backup the app's closing cut short
        Backups next = new Backups(() -> fx.library, fx.clock, java.time.ZoneOffset.UTC, p -> Long.MAX_VALUE);
        try {
            next.startup();
            Backups.Backup cancelled = next.cancel(second.id(), false);
            assertThat(cancelled.cancel()).isEqualTo("keep");
            assertThat(fx.folder(second).resolve("atlas.db")).exists();
        } finally {
            next.close();
        }
    }

    // ---- the status poll ----

    @Test
    void theStatusPollReadsNothingFromTheDatabase() throws Exception {
        assertThat(backups.statusKept()).as("not in memory before the library's runs were read").isEmpty();
        assertThat(backups.status()).isEmpty();
        var opened = com.butterflyatlas.app.db.TestDatabases.countConnections(fx.db);

        assertThat(backups.statusKept()).hasValueSatisfying(part -> assertThat(part.backup()).isNull());
        assertThat(backups.statusKept()).isPresent();
        assertThat(opened.get()).isZero();

        fx.photos(3);
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        backups.beforePhoto(id -> {
            inside.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        Backups.Backup made = backups.start(fx.request("everything"));
        assertThat(inside.await(10, TimeUnit.SECONDS)).isTrue();
        int before = opened.get();

        Backups.StatusPart part = backups.statusKept().orElseThrow();

        assertThat(part.backup().id()).isEqualTo(made.id());
        assertThat(part.backup().state()).isEqualTo("running");
        assertThat(part.backup().total()).isEqualTo(3);
        assertThat(part.backup().path()).isEqualTo(made.path());
        assertThat(opened.get()).isEqualTo(before);
        release.countDown();
        assertThat(fx.await(made.id(), "done")).isTrue();
        assertThat(backups.statusKept().orElseThrow().backup()).isNull();
    }

    // ---- the photos a backup of Everything holds (multi-window contract §4, audit A6) ----

    @Test
    void aBackupOfEverythingHoldsThePhotosWhileItRunsAndWhilePausedBeforeItsPhotosAreAllCopied() throws Exception {
        assertThat(backups.photosHeldBy()).isEmpty();
        Backups.Backup paused = pausedBackup();

        assertThat(backups.photosHeldBy()).contains("paused");
        assertThat(backups.statusKept().orElseThrow().backup().holdsPhotos()).isTrue();

        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        backups.beforePhoto(id -> {
            inside.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        backups.resume(paused.id());
        assertThat(inside.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(backups.photosHeldBy()).contains("running");
        assertThat(backups.statusKept().orElseThrow().backup().holdsPhotos()).isTrue();
        release.countDown();
        assertThat(fx.await(paused.id(), "done")).isTrue();
        assertThat(backups.photosHeldBy()).isEmpty();
    }

    @Test
    void aBackupPausedAtItsDatabaseStillHoldsThePhotosUntilFinishedAndOneOfTheDatabaseAloneNever() throws Exception {
        fx.photos(2);
        backups.afterDatabase(copy -> {
            try {
                Files.writeString(copy, "this is no longer a database ".repeat(500));
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        });
        Backups.Backup made = backups.start(fx.request("everything"));
        assertThat(fx.await(made.id(), "paused")).isTrue();

        assertThat(backups.store().run(made.id()).done()).isEqualTo(backups.store().run(made.id()).total());
        assertThat(backups.photosHeldBy()).as("paused at the database, its photos all copied: still unfinished")
                .contains("paused");
        assertThat(backups.status()).hasValueSatisfying(s -> assertThat(s.holdsPhotos()).isTrue());

        backups.cancel(made.id(), false);
        assertThat(backups.photosHeldBy()).as("discarded").isEmpty();
        backups.afterDatabase(copy -> { });
        backups.beforePhoto(id -> { });
        assertThat(backups.start(fx.request("database")).state()).isEqualTo("done");
        assertThat(backups.photosHeldBy()).isEmpty();
    }

    @Test
    void aWrittenFileIsRecordedWithItsSizeAndTimeSoRemoveKnowsWhatIsOurs() throws Exception {
        fx.photos(2);
        Backups.Backup made = backups.start(fx.request("everything"));
        assertThat(fx.await(made.id(), "done")).isTrue();

        List<ExportStore.Written> written = backups.store().writtenFiles(made.id());

        assertThat(written).extracting(ExportStore.Written::targetRel).containsExactly("atlas.db", "images/A5/p1.jpg",
                "images/A5/p2.jpg");
        for (ExportStore.Written file : written) {
            assertThat(file.bytes()).isEqualTo(Files.size(fx.folder(Backups.Backup.of(backups.store().run(made.id())))
                    .resolve(file.targetRel())));
            assertThat(file.mtime()).isPositive();
        }
    }
}
