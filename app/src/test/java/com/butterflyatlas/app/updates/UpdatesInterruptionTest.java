package com.butterflyatlas.app.updates;

import com.butterflyatlas.app.AtlasException;
import com.butterflyatlas.app.imaging.ImageIoPhotoProcessor;
import com.butterflyatlas.app.photos.Rescan;
import com.butterflyatlas.app.photos.SpeciesFiles;
import com.butterflyatlas.app.taxa.TaxonSnapshot;
import com.butterflyatlas.app.taxonomy.TaxonomyBundles;
import com.butterflyatlas.app.testing.UpdateBundles;
import com.butterflyatlas.app.updates.UpdateViews.Update;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.ToLongFunction;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * An update stopped by the app closing continues at the next start; one blocked by the drive, a locked file, a taken
 * target or a photo in an odd state pauses with a notice and Retry (5b contract §4). Never a photo overwritten or lost.
 */
class UpdatesInterruptionTest {

    private static final String DANAUS = "images/Nymphalidae/Danainae/Danaini/Danaus/";
    private static final String AGESTOR = "images/Papilionidae/Papilioninae/Papilio/Papilio_agestor_[A15]/";

    @TempDir
    Path dir;

    private UpdateFixture f;
    private final TaxonomyBundles chain = UpdateBundles.bundles(UpdateFixture.v1(), UpdateFixture.v2());

    @BeforeEach
    void setUp() {
        f = new UpdateFixture(dir);
    }

    private Updates updates(UpdateMoves.Mover mover, ToLongFunction<Path> space) {
        Updates updates = new Updates(() -> f.library, chain, f.clock, mover, space);
        updates.startup();
        return updates;
    }

    private Updates updates() {
        return updates(SpeciesFiles::moveFile, path -> Long.MAX_VALUE);
    }

    private static void idle(Updates updates) throws InterruptedException {
        assertThat(updates.awaitIdle(Duration.ofSeconds(60))).isTrue();
    }

    /** A mover that runs {@code before} ahead of its {@code n}th move (1-based), then moves as the app does. */
    private static UpdateMoves.Mover before(int n, Runnable before) {
        AtomicInteger calls = new AtomicInteger();
        return (from, to) -> {
            if (calls.incrementAndGet() == n) {
                before.run();
            }
            SpeciesFiles.moveFile(from, to);
        };
    }

    /** A mover that runs {@code after} once its {@code n}th move (1-based) is done. */
    private static UpdateMoves.Mover after(int n, Runnable after) {
        AtomicInteger calls = new AtomicInteger();
        return (from, to) -> {
            SpeciesFiles.moveFile(from, to);
            if (calls.incrementAndGet() == n) {
                after.run();
            }
        };
    }

    /** A mover that fails its {@code n}th move with {@code failure}, the first time only. */
    private static UpdateMoves.Mover failing(int n, IOException failure) {
        AtomicInteger calls = new AtomicInteger();
        return (from, to) -> {
            if (calls.incrementAndGet() == n) {
                throw failure;
            }
            SpeciesFiles.moveFile(from, to);
        };
    }

    /** Each live photo's row and file, keyed by id. */
    private Map<String, String> photos() {
        Map<String, String> photos = new HashMap<>();
        for (String row : f.rows("SELECT id, taxon_id, rel_path, file_name, number FROM image WHERE deleted_at IS NULL")) {
            photos.put(row.substring(0, row.indexOf('|')), row);
            String rel = row.split("\\|")[2];
            assertThat(f.file(rel)).as(row).isRegularFile();
        }
        return photos;
    }

    /** The photos and taxa as an update that was never interrupted leaves them (worked out in another library). */
    private Map<String, String> uninterrupted() throws Exception {
        UpdateFixture other = new UpdateFixture(dir.resolve("other"));
        other.populated();
        Updates updates = new Updates(() -> other.library, chain, other.clock);
        updates.startup();
        updates.apply();
        idle(updates);
        UpdateFixture saved = f;
        f = other;
        Map<String, String> photos = photos();
        f = saved;
        return photos;
    }

    /** Every file under {@code images/} an hour old, so the rescan does not take it for one still being copied. */
    private void ageFiles() throws IOException {
        FileTime hourAgo = FileTime.from(Instant.now().minusSeconds(3600));
        try (Stream<Path> files = Files.walk(f.library.folder().resolve("images"))) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                Files.setLastModifiedTime(file, hourAgo);
            }
        }
    }

    private void everyPhotoIsInItsSpeciesFolder() {
        Map<String, String> folders = f.db.read(c -> {
            TaxonSnapshot snapshot = TaxonSnapshot.load(c);
            Map<String, String> byId = new HashMap<>();
            for (String id : f.rows("SELECT DISTINCT taxon_id FROM image")) {
                byId.put(id, snapshot.folder(snapshot.find(id).orElseThrow()));
            }
            return byId;
        });
        for (String row : f.rows("SELECT taxon_id, rel_path, file_name FROM image WHERE deleted_at IS NULL")) {
            String[] r = row.split("\\|");
            assertThat(r[1]).as(row).isEqualTo(folders.get(r[0]) + r[2]);
        }
    }

    @Test
    void closingMidMovesStopsAfterTheCurrentFileAndTheNextStartContinuesBeforeAnythingElse() throws Exception {
        f.populated();
        Map<String, String> expected = uninterrupted();
        Updates[] first = new Updates[1];
        first[0] = updates(before(2, () -> first[0].beginClose()), path -> Long.MAX_VALUE);

        first[0].apply();
        idle(first[0]);

        assertThat(f.rows("SELECT state, stage, done, total FROM taxonomy_update")).as("left running for the next start")
                .containsExactly("running|moves|2|5");
        assertThat(f.rows("SELECT COUNT(*) FROM taxonomy_move WHERE done = 1")).containsExactly("2");
        photos(); // every row points at its file, moved or not

        CountDownLatch release = new CountDownLatch(1);
        Updates next = updates((from, to) -> {
            try {
                assertThat(release.await(30, TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException e) {
                throw new IOException(e);
            }
            SpeciesFiles.moveFile(from, to);
        }, path -> Long.MAX_VALUE);
        assertThat(next.status().orElseThrow().continuing()).isTrue();
        assertThat(next.update().progress().continuing()).isTrue();
        release.countDown();
        next.awaitContinuation();

        assertThat(next.update().state()).isEqualTo("done");
        assertThat(next.status().orElseThrow().continuing()).isFalse();
        assertThat(photos()).isEqualTo(expected);
        everyPhotoIsInItsSpeciesFolder();
    }

    @Test
    void interruptedAfterEveryMoveThenContinuedEndsAsAnUninterruptedUpdate() throws Exception {
        f.populated();
        Map<String, String> expected = uninterrupted();
        for (int n = 1; n <= 5; n++) {
            UpdateFixture at = new UpdateFixture(dir.resolve("after-" + n));
            at.populated();
            Updates[] first = new Updates[1];
            first[0] = new Updates(() -> at.library, chain, at.clock, before(n, () -> first[0].beginClose()), path -> Long.MAX_VALUE);
            first[0].startup();
            first[0].apply();
            assertThat(first[0].awaitIdle(Duration.ofSeconds(60))).isTrue();
            assertThat(at.rows("SELECT done FROM taxonomy_update")).containsExactly(String.valueOf(n));

            Updates next = new Updates(() -> at.library, chain, at.clock);
            next.startup();
            next.awaitContinuation();

            assertThat(next.update().state()).as("after move " + n).isEqualTo("done");
            UpdateFixture saved = f;
            f = at;
            assertThat(photos()).as("after move " + n).isEqualTo(expected);
            f = saved;
        }
    }

    @Test
    void movesAreRecordedInBatchesOfTwoHundredAndAClosingSavesThePartBatch() throws Exception {
        f.add("A6", 205);
        Updates[] first = new Updates[1];
        first[0] = updates(before(203, () -> first[0].beginClose()), path -> Long.MAX_VALUE);

        first[0].apply();
        idle(first[0]);

        assertThat(f.rows("SELECT done, total FROM taxonomy_update")).containsExactly("203|205");
        assertThat(f.rows("SELECT COUNT(*) FROM image WHERE rel_path LIKE '%Danaus_melanippus_[A6]%'")).containsExactly("203");
        Updates next = updates();
        next.awaitContinuation();
        assertThat(f.rows("SELECT state, done, total FROM taxonomy_update")).containsExactly("done|205|205");
        assertThat(f.file(DANAUS + "Danaus_genutia_[A6]")).doesNotExist();
        assertThat(f.file(DANAUS + "Danaus_melanippus_[A6]/Danaus_melanippus_205.jpg")).exists();
    }

    /** An update planned and recorded running at its moves, as if the app had stopped before moving anything. */
    private void plannedThenStopped() {
        f.db.write(c -> {
            long id = UpdateStore.create(c, UpdateStore.BUNDLE, "2026-01-01", "2026-02-01", "2026-02-01", null, null,
                    f.clock.instant());
            UpdatePlanner.Plan plan = UpdatePlanner.plan(c, f.library.folder(), id, UpdateFixture.v2(), 1);
            UpdateStore.addTotal(c, id, plan.moves().size());
            UpdateStore.stage(c, id, UpdateStore.MOVES, "2026-02-01");
            return null;
        });
    }

    @Test
    void theContinuationRulesMarkAMoveDoneByItselfAndPauseForBothPresentAndMissing() throws Exception {
        f.populated();
        plannedThenStopped();
        List<String[]> moves = f.rows("SELECT old_rel, new_rel FROM taxonomy_move ORDER BY seq").stream()
                .map(r -> r.split("\\|")).toList();
        assertThat(moves).hasSize(5);
        // 1: moved before the app stopped, not recorded; 2: copied, so in both folders; 3: gone from both.
        Files.createDirectories(f.file(moves.get(0)[1]).getParent());
        Files.move(f.file(moves.get(0)[0]), f.file(moves.get(0)[1]));
        Files.createDirectories(f.file(moves.get(1)[1]).getParent());
        Files.copy(f.file(moves.get(1)[0]), f.file(moves.get(1)[1]));
        Files.delete(f.file(moves.get(2)[0]));
        String second = moves.get(1)[0].substring(moves.get(1)[0].lastIndexOf('/') + 1);
        String third = moves.get(2)[0].substring(moves.get(2)[0].lastIndexOf('/') + 1);

        Updates updates = updates();
        updates.awaitContinuation();

        Update paused = updates.update();
        assertThat(paused.state()).isEqualTo("paused");
        assertThat(paused.notice().code()).isEqualTo("both_present");
        assertThat(paused.notice().message()).isEqualTo(second + " is in both its old and its new folder. Keep one, then"
                + " choose Retry.");
        assertThat(paused.progress().done()).isEqualTo(1);
        assertThat(updates.status().orElseThrow().notice().code()).isEqualTo("both_present");
        assertThat(f.rows("SELECT state, notice_code FROM taxonomy_update")).containsExactly("paused|both_present");
        assertThatThrownBy(() -> updates.apply()).isInstanceOfSatisfying(AtlasException.class,
                e -> assertThat(e.code()).isEqualTo("update_running"));

        Files.delete(f.file(moves.get(1)[0])); // the user keeps the new one
        updates.retry();
        idle(updates);

        assertThat(updates.update().notice()).isEqualTo(new UpdateViews.Notice("missing", third + " can't be found in its old"
                + " or new folder. Put it back, or choose Retry to carry on without it."));
        updates.retry();
        idle(updates);
        assertThat(updates.update().state()).isEqualTo("done");
        assertThat(f.rows("SELECT done, total FROM taxonomy_update")).containsExactly("5|5");

        // The photo gone from both folders: its record is left to the next start's rescan.
        ageFiles();
        new Rescan(() -> f.library, new ImageIoPhotoProcessor(), f.clock, id -> { }).run();
        assertThat(f.rows("SELECT COUNT(*) FROM image WHERE deleted_at IS NULL")).containsExactly("10");
        photos();
        everyPhotoIsInItsSpeciesFolder();
    }

    @Test
    void aDriveThatGoesAwayPausesTheUpdateAndRetryCarriesOnOnceItIsBack() throws Exception {
        f.populated();
        Path images = f.library.folder().resolve("images");
        Path away = f.library.folder().resolve("images_away");
        Updates updates = updates(after(2, () -> {
            try {
                Files.move(images, away);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }), path -> Long.MAX_VALUE);

        updates.apply();
        idle(updates);

        assertThat(updates.update().notice()).isEqualTo(new UpdateViews.Notice("drive_gone",
                "The drive with your library can't be found. Reconnect it, then choose Retry."));
        assertThat(updates.status().orElseThrow().state()).isEqualTo("paused");
        assertThat(f.rows("SELECT state, done FROM taxonomy_update")).containsExactly("paused|2");

        updates.retry();
        idle(updates);
        assertThat(updates.update().notice().code()).as("still gone").isEqualTo("drive_gone");

        Files.move(away, images);
        updates.retry();
        idle(updates);
        assertThat(updates.update().state()).isEqualTo("done");
        photos();
        everyPhotoIsInItsSpeciesFolder();
    }

    @Test
    void aFullDrivePausesTheUpdate() throws Exception {
        f.populated();
        long[] free = {0};
        Updates updates = updates(failing(2, new FileSystemException("Danaus_genutia_02.jpg", null, "No space left on device")),
                path -> free[0]);

        updates.apply();
        idle(updates);

        assertThat(updates.update().notice()).isEqualTo(new UpdateViews.Notice("drive_full",
                "The drive with your library is full. Free some space, then choose Retry."));
        free[0] = Long.MAX_VALUE;
        updates.retry();
        idle(updates);
        assertThat(updates.update().state()).isEqualTo("done");
        photos();
    }

    @Test
    void aFileTheComputerWillNotLetGoPausesTheUpdateAsLockedAndAnyOtherErrorAsFailed() throws Exception {
        f.populated();
        Updates locked = updates(failing(1, new AccessDeniedException("Danaus_genutia_01.jpg")), path -> Long.MAX_VALUE);
        locked.windows = true;
        locked.apply();
        idle(locked);

        assertThat(locked.update().notice()).isEqualTo(new UpdateViews.Notice("locked", "Danaus_genutia_01.jpg is open in"
                + " another program. Close it, then choose Retry."));
        locked.windows = false;
        locked.retry();
        idle(locked);
        assertThat(locked.update().state()).isEqualTo("done");

        UpdateFixture other = new UpdateFixture(dir.resolve("other"));
        other.populated();
        Updates failed = new Updates(() -> other.library, chain, other.clock, failing(4, new IOException("disk said no")),
                path -> Long.MAX_VALUE);
        failed.startup();
        failed.apply();
        assertThat(failed.awaitIdle(Duration.ofSeconds(30))).isTrue();
        assertThat(failed.update().notice()).isEqualTo(new UpdateViews.Notice("failed",
                "The taxonomy update stopped: disk said no. Choose Retry to try again."));
        failed.retry();
        assertThat(failed.awaitIdle(Duration.ofSeconds(30))).isTrue();
        assertThat(failed.update().state()).isEqualTo("done");
    }

    @Test
    void elsewhereAFileThatCannotBeChangedIsNoAccessAndARaceWithTheTargetIsNotAFullDrive() throws Exception {
        f.populated();
        Updates denied = updates(failing(1, new AccessDeniedException("Danaus_genutia_01.jpg")), path -> Long.MAX_VALUE);
        denied.windows = false;
        denied.apply();
        idle(denied);

        assertThat(denied.update().notice()).isEqualTo(new UpdateViews.Notice("no_access", "Butterfly Atlas can't change"
                + " Danaus_genutia_01.jpg (no permission, or the drive is read-only). Fix that, then choose Retry."));

        UpdateFixture other = new UpdateFixture(dir.resolve("other"));
        other.populated();
        Updates raced = new Updates(() -> other.library, chain, other.clock,
                failing(1, new java.nio.file.FileAlreadyExistsException("Danaus_melanippus_01.jpg")), path -> 0L);
        raced.startup();
        raced.apply();
        assertThat(raced.awaitIdle(Duration.ofSeconds(30))).isTrue();
        assertThat(raced.update().notice().code()).as("checked before the free space").isEqualTo("both_present");
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void aPhotoOpenInAnotherProgramPausesTheUpdateAsLocked() throws Exception {
        f.populated();
        Path open = f.file(DANAUS + "Danaus_genutia_[A6]/Danaus_genutia_01.jpg");
        Updates updates = updates();
        // java.io opens a file on Windows without sharing its deletion: it cannot be renamed while it is open.
        try (FileInputStream held = new FileInputStream(open.toFile())) {
            assertThat(held.read()).isNotNegative();
            updates.apply();
            idle(updates);
            assertThat(updates.update().notice()).isEqualTo(new UpdateViews.Notice("locked", "Danaus_genutia_01.jpg is open"
                    + " in another program. Close it, then choose Retry."));
        }
        updates.retry();
        idle(updates);
        assertThat(updates.update().state()).isEqualTo("done");
    }

    @Test
    void aTargetAlreadyThereIsNeverOverwrittenTheUpdatePausesBeforeAnythingMoves() throws Exception {
        f.populated();
        Files.createDirectories(f.file(AGESTOR));
        Files.writeString(f.file(AGESTOR + "Papilio_agestor_01.jpg"), "someone else's");
        // The merge's numbering skips a name taken on disk: the taken target here is a rename's.
        Files.createDirectories(f.file(DANAUS + "Danaus_melanippus_[A6]"));
        Files.writeString(f.file(DANAUS + "Danaus_melanippus_[A6]/Danaus_melanippus_01.jpg"), "also someone else's");
        Map<String, String> before = photos();
        Updates updates = updates();

        updates.apply();
        idle(updates);

        assertThat(updates.update().notice()).isEqualTo(new UpdateViews.Notice("target_exists", "A file named"
                + " Danaus_melanippus_01.jpg is already in Danaus_melanippus_[A6]. Move it out of the way, then choose Retry."));
        assertThat(f.rows("SELECT state, stage, total FROM taxonomy_update")).containsExactly("paused|plan|0");
        assertThat(photos()).as("nothing moved").isEqualTo(before);
        assertThat(f.file(DANAUS + "Danaus_melanippus_[A6]/Danaus_melanippus_01.jpg")).hasContent("also someone else's");

        Files.move(f.file(DANAUS + "Danaus_melanippus_[A6]/Danaus_melanippus_01.jpg"), f.file("images/kept.jpg"));
        updates.retry();
        idle(updates);
        assertThat(updates.update().state()).isEqualTo("done");
        assertThat(f.file(AGESTOR + "Papilio_agestor_01.jpg")).hasContent("someone else's");
        assertThat(f.rows("SELECT file_name FROM image WHERE taxon_id = 'A15' ORDER BY number"))
                .containsExactly("Papilio_agestor_02.jpg", "Papilio_agestor_03.jpg");
        photos();
    }

    @Test
    void aRescanDuringAnUnfinishedUpdateLeavesItsPhotosAlone() throws Exception {
        f.populated();
        Updates updates = updates(failing(3, new FileSystemException("x", null, "No space left on device")), path -> 0L);
        updates.apply();
        idle(updates);
        assertThat(updates.update().notice().code()).isEqualTo("drive_full");
        List<String> before = f.rows("SELECT id, taxon_id, rel_path, file_name, number, source FROM image ORDER BY id");
        // Moves not done yet: the third's file moved by hand to its new place (as if the app had stopped between the move
        // and its record), the fourth's copied there (in both folders). Neither is taken for a new photo or a rename.
        String[] third = f.rows("SELECT old_rel, new_rel FROM taxonomy_move WHERE seq = 3").getFirst().split("\\|");
        String[] fourth = f.rows("SELECT old_rel, new_rel FROM taxonomy_move WHERE seq = 4").getFirst().split("\\|");
        Files.createDirectories(f.file(third[1]).getParent());
        Files.move(f.file(third[0]), f.file(third[1]));
        Files.createDirectories(f.file(fourth[1]).getParent());
        Files.copy(f.file(fourth[0]), f.file(fourth[1]));
        ageFiles();

        Rescan.Result result = new Rescan(() -> f.library, new ImageIoPhotoProcessor(), f.clock, id -> { }).run();

        assertThat(result).isEqualTo(new Rescan.Result(0, 0));
        assertThat(f.rows("SELECT id, taxon_id, rel_path, file_name, number, source FROM image ORDER BY id")).isEqualTo(before);
        Files.delete(f.file(fourth[1]));
        updates.retry();
        idle(updates);
        assertThat(updates.update().state()).isEqualTo("done");
    }

    @Test
    void retryIsOnlyForAPausedUpdate() throws Exception {
        f.populated();
        Updates updates = updates();
        assertThatThrownBy(updates::retry).isInstanceOfSatisfying(AtlasException.class, e -> {
            assertThat(e.code()).isEqualTo("not_paused");
            assertThat(e.getMessage()).isEqualTo("The taxonomy update is not paused.");
        });
        updates.apply();
        idle(updates);
        assertThatThrownBy(updates::retry).isInstanceOfSatisfying(AtlasException.class,
                e -> assertThat(e.code()).isEqualTo("not_paused"));
    }

    @Test
    void anUpdatePausedForAProblemWaitsForRetryAtTheNextStart() throws Exception {
        f.populated();
        Updates first = updates(failing(2, new AccessDeniedException("x")), path -> Long.MAX_VALUE);
        first.windows = true;
        first.apply();
        idle(first);

        Updates next = updates();
        next.awaitContinuation();

        Update update = next.update();
        assertThat(update.state()).isEqualTo("paused");
        assertThat(update.notice().code()).isEqualTo("locked");
        assertThat(update.progress()).isEqualTo(new UpdateViews.Progress(1, 1, "moves", 1, 5, false, null));
        next.retry();
        idle(next);
        assertThat(next.update().state()).isEqualTo("done");
        assertThat(next.update().result().photos()).isEqualTo(9);
    }

    @Test
    void anUpdateLeftRunningByAQuitContinuesAtTheNextStartAndAPausedOneWaitsForRetry() throws Exception {
        f.populated();
        plannedThenStopped();
        f.sql("UPDATE taxonomy_update SET state = 'paused', notice_code = 'locked', notice = 'x is open in another program.'");

        Updates paused = updates();
        paused.awaitContinuation();
        assertThat(paused.update().state()).isEqualTo("paused");

        f.sql("UPDATE taxonomy_update SET state = 'running', notice_code = NULL, notice = NULL");
        Updates next = updates();
        next.awaitContinuation();

        assertThat(next.update().state()).isEqualTo("done");
        photos();
    }

    @Test
    void theStartupContinuationIsWaitedForUntilItPauses() throws Exception {
        f.populated();
        plannedThenStopped();
        Files.delete(f.file(f.rows("SELECT old_rel FROM taxonomy_move WHERE seq = 1").getFirst()));

        Updates next = updates();
        next.awaitContinuation();

        assertThat(next.update().state()).isEqualTo("paused");
        assertThat(next.status().orElseThrow().continuing()).isFalse();
    }
}
