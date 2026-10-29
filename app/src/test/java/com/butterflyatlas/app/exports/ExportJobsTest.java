package com.butterflyatlas.app.exports;

import com.butterflyatlas.app.AtlasException;
import com.butterflyatlas.app.db.TestDatabases;
import com.butterflyatlas.app.filters.FilterQuery;
import com.butterflyatlas.app.imaging.CommonsMetadataStripper;
import com.butterflyatlas.app.testing.TestBundles;
import com.butterflyatlas.app.testing.TestImages;
import com.butterflyatlas.app.testing.TestLocations;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** The copy in the background (4b contract §3): per photo, pause, resume, cancel, problems, a restart. */
class ExportJobsTest {

    private static final String TIGER = "Nymphalidae/Danainae/Danaini/Danaus/Danaus_chrysippus/";
    private static final String MORMON = "Papilionidae/Papilioninae/Papilio/Papilio_polytes/";

    @TempDir
    Path home;

    private ExportFixture f;
    private final CommonsMetadataStripper stripper = new CommonsMetadataStripper();
    private byte[] located;
    private byte[] plain;
    private byte[] png;
    private long first;
    private long second;
    private long third;

    @BeforeEach
    void open() {
        f = new ExportFixture(home);
        located = TestLocations.jpeg();
        plain = TestImages.jpeg(16, 12, 5);
        png = TestLocations.png();
        first = f.photo("A5", "Danaus_chrysippus_01.jpg", located, "P4");
        second = f.photo("A5", "Danaus_chrysippus_02.png", png, null);
        third = f.photo("A12", "Papilio_polytes_01.jpg", plain, "P8");
    }

    @AfterEach
    void close() {
        f.exports.close();
    }

    private Exports.Export idle(long id) throws InterruptedException {
        assertThat(f.exports.awaitIdle(Duration.ofSeconds(20))).isTrue();
        return Exports.Export.of(f.exports.store().run(id));
    }

    private Path folder() {
        return f.dest.resolve("Butterfly_Atlas_export_2026-10-09");
    }

    private static List<String> files(Path folder) throws IOException {
        if (!Files.exists(folder)) {
            return List.of();
        }
        try (Stream<Path> all = Files.walk(folder)) {
            return all.filter(Files::isRegularFile).map(p -> folder.relativize(p).toString().replace('\\', '/')).sorted().toList();
        }
    }

    private List<String> reasons(long id) {
        return f.exports.store().problems(id, 100).stream().map(p -> p.kind() + " " + p.imageId() + " " + p.reason()).toList();
    }

    @Test
    void everyPhotoIsCopiedWithItsLocationRemovedAndTheExportFinishes() throws Exception {
        Exports.Export start = f.exports.start(f.request("", "photos"));

        Exports.Export done = idle(start.id());

        assertThat(done.state()).isEqualTo("done");
        assertThat(List.of(done.total(), done.done(), done.copied(), done.skipped(), done.failed())).containsExactly(3, 3, 3, 0, 0);
        assertThat(done.finishedAt()).isEqualTo("2026-10-09T08:00:00Z");
        assertThat(files(folder())).containsExactly(TIGER + "Danaus_chrysippus_01.jpg", TIGER + "Danaus_chrysippus_02.png",
                MORMON + "Papilio_polytes_01.jpg");
        Path jpeg = folder().resolve(TIGER + "Danaus_chrysippus_01.jpg");
        assertThat(stripper.hasLocation(jpeg)).isFalse();
        assertThat(stripper.hasLocation(folder().resolve(TIGER + "Danaus_chrysippus_02.png"))).isFalse();
        assertThat(Files.readAllBytes(jpeg)).isEqualTo(stripper.strip(located));
        assertThat(Files.readAllBytes(folder().resolve(MORMON + "Papilio_polytes_01.jpg"))).as("nothing to remove: a byte copy")
                .isEqualTo(plain);
        assertThat(done.bytesDone()).as("the originals' sizes, as bytesTotal").isEqualTo(located.length + png.length + plain.length)
                .isEqualTo(done.bytesTotal());
        assertThat(Files.readAllBytes(f.file(first))).as("the original untouched").isEqualTo(located);
    }

    @Test
    void onlyRenamedPhotosThatWereCopiedCountAsRenamed() throws Exception {
        String longName = "Danaus_chrysippus_" + "x".repeat(80);
        long a = f.photo("A6", longName + "_01.jpg", TestImages.jpeg(8, 6, 21), null);
        long b = f.photo("A6", longName + "_02.jpg", TestImages.jpeg(8, 6, 22), null);
        f.photo("A6", longName + "_03.jpg", TestImages.jpeg(8, 6, 23), null);
        Path deep = f.dest;
        while (deep.toString().length() < 190) {
            deep = deep.resolve("long_folder_name_x");
        }
        Files.createDirectories(deep);
        f.exports.beforePhoto(item -> {
            if (item.seq() == 2) {
                f.exports.pause(f.exports.current().orElseThrow().id());
            }
        });
        Exports.Export started = f.exports.start(new Exports.Request("taxa=A6", "photos", deep.toString(), "flat", false, null));
        assertThat(started.renamed()).as("nothing copied yet").isZero();
        assertThat(f.exports.store().targets(started.id())).as("all three shortened at the start").hasSize(3)
                .allMatch(t -> !t.startsWith(longName));

        Exports.Export paused = idle(started.id());
        f.exports.beforePhoto(item -> { });
        Exports.Export cancelled = f.exports.cancel(started.id(), false);

        assertThat(List.of(paused.state(), String.valueOf(paused.copied()))).containsExactly("paused", "2");
        assertThat(cancelled.renamed()).as("3 renamed at the start, 2 of them copied").isEqualTo(2);
        assertThat(f.exports.store().problems(started.id(), 10)).extracting(ExportStore.Problem::imageId)
                .containsExactly(a, b);
        assertThat(f.exports.store().problemCount(started.id())).isEqualTo(2);
    }

    @Test
    void withRemoveLocationOffEveryCopyIsByteIdentical() throws Exception {
        long id = f.exports.start(new Exports.Request("", "photos", f.dest.toString(), "flat", false, null)).id();

        idle(id);

        assertThat(Files.readAllBytes(folder().resolve("Danaus_chrysippus_01.jpg"))).isEqualTo(located);
        assertThat(Files.readAllBytes(folder().resolve("Danaus_chrysippus_02.png"))).isEqualTo(png);
    }

    @Test
    void pauseStopsAfterTheCurrentFileAndResumeCarriesOn() throws Exception {
        f.exports.beforePhoto(item -> {
            if (item.seq() == 2) {
                f.exports.pause(f.exports.current().orElseThrow().id());
            }
        });
        long id = f.exports.start(f.request("", "photos")).id();

        Exports.Export paused = idle(id);

        assertThat(List.of(paused.state(), String.valueOf(paused.pausedBy()), String.valueOf(paused.notice())))
                .containsExactly("paused", "you", "null");
        assertThat(paused.done()).as("the current file is finished first").isEqualTo(2);
        assertThat(f.exports.pause(id).state()).as("paused already: as it is").isEqualTo("paused");
        f.exports.beforePhoto(item -> { });

        assertThat(f.exports.resume(id).state()).isEqualTo("running");
        Exports.Export done = idle(id);

        assertThat(done.state()).isEqualTo("done");
        assertThat(done.copied()).isEqualTo(3);
        assertThatThrownBy(() -> f.exports.resume(id)).hasMessage("This export has already finished.");
        assertThatThrownBy(() -> f.exports.pause(id)).hasMessage("This export has already finished.");
        assertThatThrownBy(() -> f.exports.cancel(id, true)).hasMessage("This export has already finished.");
    }

    @Test
    void whileACopyRunsTheStatusAndTheListOpenNoConnection() throws Exception {
        CountDownLatch reached = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        f.exports.saveEvery(1);
        f.exports.beforePhoto(item -> {
            if (item.seq() == 2) {
                reached.countDown();
                try {
                    release.await(20, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        long past = f.exports.start(f.request("", "csv")).id();
        long id = f.exports.start(f.request("", "photos")).id();
        assertThat(reached.await(20, TimeUnit.SECONDS)).isTrue();
        AtomicInteger opened = TestDatabases.countConnections(f.db);
        try {
            for (int i = 0; i < 20; i++) {
                assertThat(f.exports.status()).map(Exports.Status::state).contains("running");
                assertThat(f.exports.status()).map(Exports.Status::done).contains(1);
                Exports.Exported list = f.exports.list();
                assertThat(list.current().id()).isEqualTo(id);
                assertThat(list.current().done()).isEqualTo(1);
                assertThat(list.past()).extracting(Exports.Export::id).containsExactly(past);
                assertThat(f.exports.current()).map(Exports.Export::id).contains(id);
            }
            assertThat(opened.get()).as("connections opened by the status and the list").isZero();
        } finally {
            release.countDown();
        }
        Exports.Export done = idle(id);
        assertThat(done.state()).isEqualTo("done");
        assertThat(f.exports.status()).isEmpty();
        assertThat(f.exports.list().current()).isNull();
        assertThat(f.exports.list().past()).extracting(Exports.Export::id).containsExactly(id, past);
        assertThat(f.exports.list().past().get(0)).isEqualTo(done);
    }

    @Test
    void afterAQuitTheExportsAreReadAgainFromTheLibraryWithTheStartupsMarking() throws Exception {
        long list = f.exports.start(f.request("", "csv")).id();
        f.exports.holdForStartup(); // nothing copies: as if the app were killed before
        long id = f.exports.start(f.request("", "photos")).id();
        Exports.Exported before = f.exports.list();
        assertThat(before.current().state()).isEqualTo("running");
        f.exports.close();

        Exports again = new Exports(() -> f.library, new FilterQuery(f.library::database), stripper, TestBundles.CLOCK,
                ZoneOffset.UTC, p -> Long.MAX_VALUE);
        try {
            assertThat(again.list()).as("read from the library").isEqualTo(before);
            assertThat(again.startup()).isEqualTo(1);

            Exports.Exported after = again.list();
            assertThat(after.current().id()).isEqualTo(id);
            assertThat(List.of(after.current().state(), after.current().pausedBy()))
                    .containsExactly("paused", "closing");
            assertThat(after.past()).extracting(Exports.Export::id).containsExactly(list);
            assertThat(again.status()).map(Exports.Status::pausedBy).contains("closing");
            Exports third = new Exports(() -> f.library, new FilterQuery(f.library::database), stripper,
                    TestBundles.CLOCK, ZoneOffset.UTC, p -> Long.MAX_VALUE);
            try {
                assertThat(third.list()).as("as the library records it").isEqualTo(after);
            } finally {
                third.close();
            }
        } finally {
            again.close();
        }
    }

    @Test
    void cancelKeepingTheFilesLeavesThemWhereTheyAre() throws Exception {
        pauseBefore(2);
        long id = f.exports.start(f.request("", "photos")).id();
        idle(id);

        Exports.Export cancelled = f.exports.cancel(id, false);

        assertThat(List.of(cancelled.state(), cancelled.cancel())).containsExactly("cancelled", "keep");
        assertThat(cancelled.done()).isEqualTo(2);
        assertThat(files(folder())).hasSize(2);
    }

    @Test
    void cancelRemovingDeletesOnlyWhatThisExportWroteThenItsEmptyFolders() throws Exception {
        pauseBefore(2);
        long id = f.exports.start(new Exports.Request("", "both", f.dest.toString(), null, null, null)).id();
        idle(id);
        Path mine = Files.writeString(folder().resolve(TIGER + "my_notes.txt"), "mine");
        Files.createDirectories(folder().resolve(MORMON));
        Files.writeString(folder().resolve(MORMON + "Papilio_polytes_01.jpg.part"), "half");

        Exports.Export cancelled = f.exports.cancel(id, true);

        assertThat(List.of(cancelled.state(), cancelled.cancel())).containsExactly("cancelled", "remove");
        assertThat(cancelled.notRemoved()).isZero();
        assertThat(files(folder())).as("the user's file and its folders stay").containsExactly(TIGER + "my_notes.txt");
        assertThat(mine).hasContent("mine");
        assertThat(folder().resolve("Papilionidae")).doesNotExist();
    }

    @Test
    void pausingAtTheLastPhotoFinishesTheExport() throws Exception {
        pauseBefore(3);
        long id = f.exports.start(f.request("", "photos")).id();

        assertThat(idle(id).state()).as("the current file was the last").isEqualTo("done");
    }

    @Test
    void cancelRemovingEverythingRemovesTheExportFolderToo() throws Exception {
        pauseBefore(2);
        long id = f.exports.start(f.request("", "photos")).id();
        idle(id);

        f.exports.cancel(id, true);

        assertThat(folder()).doesNotExist();
        assertThat(f.dest).isDirectory();
    }

    /**
     * As a crash leaves it: the first photo's final file written but not recorded ({@code leftover}), a .part of the
     * second; then a new start of the app.
     */
    private Exports restartedWith(long id, byte[] leftover) throws Exception {
        f.exports.close();
        Files.createDirectories(folder().resolve(TIGER));
        Files.write(folder().resolve(TIGER + "Danaus_chrysippus_01.jpg"), leftover);
        Files.writeString(folder().resolve(TIGER + "Danaus_chrysippus_02.png.part"), "cut short");
        Exports again = new Exports(() -> f.library, new FilterQuery(f.library::database),
                stripper, TestBundles.CLOCK, ZoneOffset.UTC, p -> Long.MAX_VALUE);
        assertThat(again.startup()).isEqualTo(1);
        Exports.Export paused = Exports.Export.of(again.store().run(id));
        assertThat(List.of(paused.state(), paused.pausedBy(), paused.notice())).containsExactly("paused", "closing",
                "This export stopped when Butterfly Atlas closed.");
        assertThat(again.awaitIdle(Duration.ofMillis(200))).as("nothing restarts by itself").isTrue();
        again.resume(id);
        assertThat(again.awaitIdle(Duration.ofSeconds(20))).isTrue();
        return again;
    }

    @Test
    void quittingWhileACopyRunsSavesItsProgressAndTheNextStartContinuesItWithoutDuplicates() throws Exception {
        f.exports.beforePhoto(item -> {
            if (item.seq() == 2) {
                f.exports.beginClose(); // the app quits while the second photo is being copied
            }
        });
        long id = f.exports.start(f.request("", "photos")).id();
        assertThat(f.exports.awaitIdle(Duration.ofSeconds(20))).isTrue();
        f.exports.close();
        ExportStore.Run cut = f.exports.store().run(id);
        assertThat(List.of(cut.state(), String.valueOf(cut.copied()))).as("the current file finished and was saved")
                .containsExactly("running", "2");

        Exports again = new Exports(() -> f.library, new FilterQuery(f.library::database),
                stripper, TestBundles.CLOCK, ZoneOffset.UTC, p -> Long.MAX_VALUE);
        try {
            again.startup();
            assertThat(again.store().run(id).pausedBy()).isEqualTo("closing");
            again.resume(id);
            assertThat(again.awaitIdle(Duration.ofSeconds(20))).isTrue();
            ExportStore.Run done = again.store().run(id);
            assertThat(List.of(done.state(), String.valueOf(done.copied()), String.valueOf(done.failed())))
                    .containsExactly("done", "3", "0");
            assertThat(files(folder())).containsExactly(TIGER + "Danaus_chrysippus_01.jpg", TIGER + "Danaus_chrysippus_02.png",
                    MORMON + "Papilio_polytes_01.jpg");
        } finally {
            again.close();
        }
    }

    @Test
    void cancelRemovingWhileACopyRunsStopsAfterTheCurrentFileThenRemovesEverything() throws Exception {
        Thread[] canceller = new Thread[1];
        long[] id = new long[1];
        Exports.Export[] cancelled = new Exports.Export[1];
        f.exports.beforePhoto(item -> {
            if (item.seq() == 2) {
                canceller[0] = new Thread(() -> cancelled[0] = f.exports.cancel(id[0], true));
                canceller[0].start();
                long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
                while (!f.exports.stopAsked(id[0]) && System.nanoTime() < deadline) {
                    Thread.onSpinWait(); // until the cancel has asked the copy to stop (it then waits for this file)
                }
                assertThat(f.exports.stopAsked(id[0])).isTrue();
            }
        });
        id[0] = f.exports.start(f.request("", "photos")).id();

        assertThat(f.exports.awaitIdle(Duration.ofSeconds(20))).isTrue();
        canceller[0].join(20_000);
        assertThat(canceller[0].isAlive()).as("the cancel returned").isFalse();

        assertThat(List.of(cancelled[0].state(), cancelled[0].cancel())).containsExactly("cancelled", "remove");
        assertThat(cancelled[0].done()).isEqualTo(2);
        assertThat(cancelled[0].notRemoved()).isZero();
        assertThat(folder()).doesNotExist();
    }

    @Test
    void theExportFolderVanishingDuringAWritePausesWithDriveGone() throws Exception {
        f.exports.opener(part -> {
            if (part.getFileName().toString().startsWith("Danaus_chrysippus_02")) {
                deleteTree(folder());
                throw new IOException("No such device");
            }
            return Files.newOutputStream(part, StandardOpenOption.CREATE_NEW);
        });
        long id = f.exports.start(f.request("", "photos")).id();

        Exports.Export paused = idle(id);

        assertThat(List.of(paused.state(), paused.pausedBy())).containsExactly("paused", "drive_gone");
        assertThat(List.of(paused.copied(), paused.failed())).containsExactly(1, 0);
        assertThat(folder()).as("never made again").doesNotExist();
    }

    @Test
    void aResumeRightAfterAPauseWhileAFileCopiesTakesThePauseBack() throws Exception {
        f.exports.beforePhoto(item -> {
            if (item.seq() == 2) {
                long id = f.exports.current().orElseThrow().id();
                assertThat(f.exports.pause(id).state()).as("pausing: still running").isEqualTo("running");
                assertThat(f.exports.resume(id).state()).isEqualTo("running");
            }
        });
        long id = f.exports.start(f.request("", "photos")).id();

        Exports.Export done = idle(id);

        assertThat(List.of(done.state(), String.valueOf(done.copied()))).containsExactly("done", "3");
    }

    @Test
    void aResumeWhileAStoppingCopyOutlastsTheWaitAnswersBusy() throws Exception {
        CountDownLatch inFile = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        f.exports.beforePhoto(item -> {
            if (item.seq() == 2) {
                inFile.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        long id = f.exports.start(f.request("", "photos")).id();
        assertThat(inFile.await(20, TimeUnit.SECONDS)).isTrue();
        f.exports.pause(id); // the copy stops after this file
        f.exports.store().pause(id, "you", null); // recorded paused while its copy is still in a file
        f.exports.stopWait = Duration.ofMillis(100);

        assertThatThrownBy(() -> f.exports.resume(id)).isInstanceOfSatisfying(AtlasException.class,
                e -> assertThat(e.kind()).isEqualTo(AtlasException.Kind.BUSY));
        release.countDown();
        Exports.Export paused = idle(id);

        assertThat(List.of(paused.state(), String.valueOf(paused.done()))).containsExactly("paused", "2");
        f.exports.beforePhoto(item -> { });
        assertThat(f.exports.resume(id).state()).isEqualTo("running");
        assertThat(idle(id).state()).isEqualTo("done");
    }

    @Test
    void aCancelThatOutlastsItsWaitLeavesTheExportPausedAndASecondCancelSucceeds() throws Exception {
        CountDownLatch inFile = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        f.exports.beforePhoto(item -> {
            if (item.seq() == 2) {
                inFile.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        long id = f.exports.start(f.request("", "photos")).id();
        assertThat(inFile.await(20, TimeUnit.SECONDS)).isTrue();
        f.exports.stopWait = Duration.ofMillis(100);

        assertThatThrownBy(() -> f.exports.cancel(id, true)).isInstanceOfSatisfying(AtlasException.class,
                e -> assertThat(e.kind()).isEqualTo(AtlasException.Kind.BUSY));
        release.countDown();
        Exports.Export paused = idle(id);

        assertThat(List.of(paused.state(), paused.pausedBy(), String.valueOf(paused.done())))
                .as("never left running with no copy").containsExactly("paused", "you", "2");
        Exports.Export cancelled = f.exports.cancel(id, true);
        assertThat(List.of(cancelled.state(), cancelled.cancel())).containsExactly("cancelled", "remove");
        assertThat(cancelled.notRemoved()).isZero();
        assertThat(folder()).doesNotExist();
    }

    @Test
    void aResumeAfterACancelGaveUpWaitingTakesTheCancelBackAtOnce() throws Exception {
        CountDownLatch inFile = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        f.exports.beforePhoto(item -> {
            if (item.seq() == 2) {
                inFile.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        long id = f.exports.start(f.request("", "photos")).id();
        assertThat(inFile.await(20, TimeUnit.SECONDS)).isTrue();
        f.exports.stopWait = Duration.ofMillis(100);
        assertThatThrownBy(() -> f.exports.cancel(id, false)).isInstanceOf(AtlasException.class);
        f.exports.stopWait = Duration.ofSeconds(60);

        long asked = System.nanoTime();
        Exports.Export resumed = f.exports.resume(id);

        assertThat(Duration.ofNanos(System.nanoTime() - asked)).as("nothing waited for").isLessThan(Duration.ofSeconds(5));
        assertThat(resumed.state()).isEqualTo("running");
        release.countDown();
        Exports.Export done = idle(id);
        assertThat(List.of(done.state(), String.valueOf(done.copied()))).containsExactly("done", "3");
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void cancelKeepingTheFilesRemovesOnlyThisExportsPartFiles() throws Exception {
        f.exports.partDeleter(part -> {
            if (part.getFileName().toString().startsWith("Danaus_chrysippus_01")) {
                throw new IOException("Operation not permitted");
            }
            Files.delete(part);
        });
        pauseBefore(2);
        long id = f.exports.start(f.request("", "photos")).id();
        idle(id);
        Path copy = folder().resolve(TIGER + "Danaus_chrysippus_01.jpg");
        assertThat(ExportCopier.part(copy)).exists();
        Path theirs = Files.writeString(folder().resolve(TIGER + "notes.txt.part"), "the user's own");

        Exports.Export cancelled = f.exports.cancel(id, false);

        assertThat(cancelled.cancel()).isEqualTo("keep");
        assertThat(ExportCopier.part(copy)).doesNotExist();
        assertThat(theirs).hasContent("the user's own");
        assertThat(copy).hasBinaryContent(stripper.strip(located));
        assertThat(Files.getAttribute(copy, "unix:nlink")).isEqualTo(1);
        assertThat(files(folder())).containsExactly(TIGER + "Danaus_chrysippus_01.jpg", TIGER + "Danaus_chrysippus_02.png",
                TIGER + "notes.txt.part");
    }

    @Test
    void aPhotoMovedInTheLibraryMidCopyIsCopiedFromWhereItIsNow() throws Exception {
        boolean[] moved = {false};
        f.exports.opener(part -> {
            if (!moved[0] && part.getFileName().toString().startsWith("Papilio_polytes_01")) {
                moved[0] = true; // the library files the photo elsewhere while it is being copied
                Path now = f.library.folder().resolve("images/A12/Papilio_polytes_01_moved.jpg");
                Files.move(f.file(third), now);
                f.sql("UPDATE image SET rel_path = 'images/A12/Papilio_polytes_01_moved.jpg' WHERE id = " + third);
            }
            return Files.newOutputStream(part, StandardOpenOption.CREATE_NEW);
        });
        long id = f.exports.start(new Exports.Request("", "photos", f.dest.toString(), "flat", false, null)).id();

        Exports.Export done = idle(id);

        assertThat(List.of(done.state(), String.valueOf(done.copied()), String.valueOf(done.skipped())))
                .containsExactly("done", "3", "0");
        assertThat(folder().resolve("Papilio_polytes_01.jpg")).hasBinaryContent(plain);
    }

    @Test
    void progressIsSavedInBatchesOrEverySecondAndAtOnceOnAPause() throws Exception {
        for (int n = 2; n <= 6; n++) {
            f.photo("A12", "Papilio_polytes_0" + n + ".jpg", TestImages.jpeg(8, 6, n), null);
        }
        f.exports.nanos(() -> 0L); // the clock stands still: only the count saves
        f.exports.saveEvery(3);
        List<Integer> seen = new ArrayList<>();
        f.exports.beforePhoto(item -> {
            long id = f.exports.current().orElseThrow().id();
            seen.add(f.exports.store().run(id).done());
            if (item.seq() == 5) {
                f.exports.pause(id);
            }
        });
        long id = f.exports.start(f.request("", "photos")).id();

        Exports.Export paused = idle(id);

        assertThat(seen).as("saved after 3 photos, not before").containsExactly(0, 0, 0, 3, 3);
        assertThat(List.of(paused.state(), String.valueOf(paused.done()))).as("saved at once on the pause")
                .containsExactly("paused", "5");
        AtomicLong now = new AtomicLong();
        f.exports.nanos(() -> now.addAndGet(Duration.ofSeconds(1).toNanos())); // a second between two looks
        f.exports.saveEvery(ExportCopier.SAVE_EVERY);
        seen.clear();
        f.exports.resume(id);
        assertThat(idle(id).state()).isEqualTo("done");
        assertThat(seen).as("saved every second").containsExactly(5, 6, 7);
    }

    @Test
    void aPauseWhileHeldAtStartupPausesAtOnceAndNothingCopiesAfterwards() throws Exception {
        f.exports.holdForStartup();
        long id = f.exports.start(f.request("", "photos")).id();

        Exports.Export paused = f.exports.pause(id);
        f.exports.startupFilesSettled();

        assertThat(List.of(paused.state(), paused.pausedBy())).containsExactly("paused", "you");
        assertThat(f.exports.awaitIdle(Duration.ofSeconds(20))).isTrue();
        Thread.sleep(200);
        assertThat(f.exports.store().run(id).done()).isZero();
        assertThat(files(folder())).isEmpty();
    }

    @Test
    void aSourceThatVanishesOnceOpenIsStillCopiedWhole() throws Exception {
        // Phase 7b review fix J5: the copy reads from the channel its kind was read from, so a file deleted once it is
        // open is copied as it was (before J5 the copy opened it a second time and found it gone)
        byte[] original = Files.readAllBytes(f.file(third));
        f.exports.opener(part -> {
            if (part.getFileName().toString().startsWith("Papilio_polytes_01")) {
                Files.delete(f.file(third));
            }
            return Files.newOutputStream(part, StandardOpenOption.CREATE_NEW);
        });
        long id = f.exports.start(new Exports.Request("", "photos", f.dest.toString(), "flat", false, null)).id();

        Exports.Export done = idle(id);

        assertThat(List.of(done.state(), String.valueOf(done.copied()), String.valueOf(done.skipped())))
                .containsExactly("done", "3", "0");
        assertThat(Files.readAllBytes(folder().resolve(f.file(third).getFileName().toString()))).isEqualTo(original);
    }

    @Test
    void inOneFolderAVanishedExportFolderIsNeverMadeAgainEither() throws Exception {
        Path other = home.resolve("other");
        AtomicInteger strips = new AtomicInteger();
        ExportFixture flat = new ExportFixture(other, new MetadataStripper() {
            @Override
            public byte[] strip(byte[] photo) throws IOException {
                if (strips.incrementAndGet() == 2) {
                    deleteTree(other.resolve("Backup").resolve("Butterfly_Atlas_export_2026-10-09"));
                }
                return stripper.strip(photo);
            }

            @Override
            public boolean hasLocation(Path file) throws IOException {
                return stripper.hasLocation(file);
            }
        });
        flat.photo("A5", "Danaus_chrysippus_01.jpg", located, null);
        flat.photo("A5", "Danaus_chrysippus_02.jpg", located, null);
        try {
            long id = flat.exports.start(new Exports.Request("", "photos", flat.dest.toString(), "flat", true, null)).id();
            assertThat(flat.exports.awaitIdle(Duration.ofSeconds(20))).isTrue();

            assertThat(flat.exports.store().run(id).pausedBy()).isEqualTo("drive_gone");
            assertThat(flat.dest.resolve("Butterfly_Atlas_export_2026-10-09")).doesNotExist();
        } finally {
            flat.exports.close();
        }
    }

    @Test
    void anIdenticalFileAlreadyAtATargetIsDoneButRemoveKeepsIt() throws Exception {
        f.exports.holdForStartup();
        long id = f.exports.start(f.request("", "photos")).id();
        Path theirs = Files.write(Files.createDirectories(folder().resolve(TIGER)).resolve("Danaus_chrysippus_01.jpg"),
                stripper.strip(located));
        pauseBefore(2);
        f.exports.startupFilesSettled();
        assertThat(idle(id).copied()).as("the same bytes: done").isEqualTo(2);

        Exports.Export cancelled = f.exports.cancel(id, true);

        assertThat(theirs).exists().hasBinaryContent(stripper.strip(located));
        assertThat(cancelled.notRemoved()).as("not known to be this export's").isEqualTo(1);
    }

    @Test
    void aFolderThatReadsAsOtherButIsItselfIsWrittenIn() throws Exception {
        f.exports.attributes(path -> {
            BasicFileAttributes real = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            return real.isDirectory() ? other(real) : real; // as an OneDrive folder on Windows reads
        });
        long id = f.exports.start(f.request("", "photos")).id();

        Exports.Export done = idle(id);

        assertThat(List.of(done.state(), String.valueOf(done.copied()), String.valueOf(done.failed())))
                .containsExactly("done", "3", "0");
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void aFolderThatReadsAsOtherAndLeadsElsewhereIsNeverWrittenThrough() throws Exception {
        Path elsewhere = Files.createDirectories(home.resolve("Elsewhere"));
        f.exports.holdForStartup();
        long id = f.exports.start(f.request("", "photos")).id();
        Files.createDirectories(folder().resolve(TIGER).getParent());
        try {
            Files.createSymbolicLink(folder().resolve(TIGER), elsewhere);
        } catch (UnsupportedOperationException | IOException e) {
            assumeTrue(false, "no symbolic links here");
        }
        f.exports.attributes(path -> {
            BasicFileAttributes real = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            // as a junction reads: "other", not a link
            return real.isSymbolicLink() ? other(Files.readAttributes(path, BasicFileAttributes.class)) : real;
        });
        f.exports.startupFilesSettled();

        Exports.Export done = idle(id);

        assertThat(List.of(done.state(), String.valueOf(done.copied()), String.valueOf(done.failed())))
                .containsExactly("done", "1", "2");
        assertThat(elsewhere.toFile().list()).isEmpty();
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void aJunctionWhereAFolderShouldBeIsNeverWrittenThrough() throws Exception {
        Path elsewhere = Files.createDirectories(home.resolve("Elsewhere"));
        f.exports.holdForStartup();
        long id = f.exports.start(f.request("", "photos")).id();
        Files.createDirectories(folder().resolve(TIGER).getParent());
        Process mklink = new ProcessBuilder("cmd", "/c", "mklink", "/J", folder().resolve(TIGER).toString(),
                elsewhere.toString()).redirectErrorStream(true).start();
        assumeTrue(mklink.waitFor() == 0, "no junctions here");
        f.exports.startupFilesSettled();

        Exports.Export done = idle(id);

        assertThat(List.of(done.state(), String.valueOf(done.copied()), String.valueOf(done.failed())))
                .containsExactly("done", "1", "2");
        assertThat(elsewhere.toFile().list()).isEmpty();
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void anExportFolderSwappedForALinkWhilePausedIsGoneAndNothingIsWrittenThroughIt() throws Exception {
        pauseBefore(2);
        long id = f.exports.start(f.request("", "photos")).id();
        idle(id);
        Path moved = Files.move(folder(), home.resolve("Moved"));
        try {
            Files.createSymbolicLink(folder(), moved);
        } catch (UnsupportedOperationException | IOException e) {
            assumeTrue(false, "no symbolic links here");
        }
        f.exports.beforePhoto(item -> { });

        Exports.Export resumed = f.exports.resume(id);
        idle(id);

        assertThat(List.of(resumed.state(), resumed.pausedBy())).containsExactly("paused", "drive_gone");
        assertThat(files(moved)).as("nothing more written through the link")
                .containsExactly(TIGER + "Danaus_chrysippus_01.jpg", TIGER + "Danaus_chrysippus_02.png");
        assertThat(f.exports.store().run(id).done()).isEqualTo(2);
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void aFinishedExportLeavesNoPartEvenWhenOneCouldNotBeRemoved() throws Exception {
        f.exports.partDeleter(part -> {
            if (part.getFileName().toString().startsWith("Papilio_polytes_01")) {
                throw new IOException("Operation not permitted");
            }
            Files.delete(part);
        });
        long id = f.exports.start(f.request("", "photos")).id();

        Exports.Export done = idle(id);

        assertThat(done.state()).isEqualTo("done");
        try (Stream<Path> all = Files.walk(folder())) {
            assertThat(all.filter(p -> p.toString().endsWith(".part")).toList()).isEmpty();
        }
        assertThat(Files.getAttribute(folder().resolve(MORMON + "Papilio_polytes_01.jpg"), "unix:nlink")).isEqualTo(1);
    }

    @Test
    void aFullDriveCountsThePhotosStillToCopyByTheirSizesAtTheStart() throws Exception {
        ExportFixture small = new ExportFixture(home.resolve("other"), new MetadataStripper() {
            @Override
            public byte[] strip(byte[] photo) {
                return new byte[] {1}; // a copy far smaller than its original
            }

            @Override
            public boolean hasLocation(Path file) {
                return false;
            }
        });
        small.photo("A5", "Danaus_chrysippus_01.jpg", new byte[300_000], null);
        small.photo("A5", "Danaus_chrysippus_02.jpg", new byte[2_000], null);
        small.photo("A5", "Danaus_chrysippus_03.jpg", new byte[3_000], null);
        small.exports.nanos(() -> 0L);
        small.exports.opener(part -> {
            if (part.getFileName().toString().startsWith("Danaus_chrysippus_02")) {
                // The drive fills as this photo is written (set here, not after start: the export may get here first).
                small.free.set(10);
                throw new IOException("No space left on device");
            }
            return Files.newOutputStream(part, StandardOpenOption.CREATE_NEW);
        });
        try {
            long id = small.exports.start(small.request("", "photos")).id();
            assertThat(small.exports.awaitIdle(Duration.ofSeconds(20))).isTrue();

            assertThat(small.exports.store().run(id).notice()).as("5 KB still to copy, not the first photo's 298 KB")
                    .isEqualTo(ExportErrors.driveFull(5_000 - 10)).isEqualTo("The drive is full: about 5 KB more is needed.");
        } finally {
            small.exports.close();
        }
    }

    /** Attributes as a reparse point (an OneDrive folder, a junction) reads on Windows: an "other" directory. */
    private static BasicFileAttributes other(BasicFileAttributes real) {
        return new BasicFileAttributes() {
            @Override
            public FileTime lastModifiedTime() {
                return real.lastModifiedTime();
            }

            @Override
            public FileTime lastAccessTime() {
                return real.lastAccessTime();
            }

            @Override
            public FileTime creationTime() {
                return real.creationTime();
            }

            @Override
            public boolean isRegularFile() {
                return false;
            }

            @Override
            public boolean isDirectory() {
                return true;
            }

            @Override
            public boolean isSymbolicLink() {
                return false;
            }

            @Override
            public boolean isOther() {
                return true;
            }

            @Override
            public long size() {
                return real.size();
            }

            @Override
            public Object fileKey() {
                return real.fileKey();
            }
        };
    }

    @Test
    void anUnexpectedErrorPausesTheExportWithWhatWasCopiedRecorded() throws Exception {
        for (Throwable problem : List.of(new IllegalStateException("a bug"), new Error("worse"))) {
            f.exports.beforePhoto(item -> {
                if (item.seq() == 2) {
                    if (problem instanceof Error error) {
                        throw error;
                    }
                    throw (RuntimeException) problem;
                }
            });
            long id = f.exports.start(f.request("", "photos")).id();

            Exports.Export paused = idle(id);

            assertThat(List.of(paused.state(), paused.pausedBy())).as(problem.toString()).containsExactly("paused",
                    "write_errors");
            assertThat(paused.copied()).isEqualTo(1);
            f.exports.beforePhoto(item -> { });
            f.exports.resume(id);
            assertThat(idle(id).state()).as("the worker carries on").isEqualTo("done");
            assertThat(idle(id).copied()).isEqualTo(3);
            deleteTree(folder());
        }
    }

    @Test
    void removeKeepsAWrittenFileThatChangedSince() throws Exception {
        pauseBefore(2);
        long id = f.exports.start(f.request("", "photos")).id();
        idle(id);
        Path changed = folder().resolve(TIGER + "Danaus_chrysippus_01.jpg");
        Files.writeString(changed, "edited by the user after the export");

        Exports.Export cancelled = f.exports.cancel(id, true);

        assertThat(changed).hasContent("edited by the user after the export");
        assertThat(cancelled.notRemoved()).isEqualTo(1);
        assertThat(files(folder())).containsExactly(TIGER + "Danaus_chrysippus_01.jpg");
    }

    @Test
    void removeKeepsASpeciesListEditedSinceTheStart() throws Exception {
        pauseBefore(2);
        long id = f.exports.start(new Exports.Request("", "both", f.dest.toString(), null, null, null)).id();
        idle(id);
        Path csv = folder().resolve("species-list.csv");
        Files.writeString(csv, "my own notes in the list", StandardOpenOption.APPEND);

        Exports.Export cancelled = f.exports.cancel(id, true);

        assertThat(cancelled.notRemoved()).isEqualTo(1);
        assertThat(Files.readString(csv)).endsWith("my own notes in the list");
        assertThat(files(folder())).containsExactly("species-list.csv");
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void removeNeverFollowsALinkOutOfTheExportFolder() throws Exception {
        pauseBefore(2);
        long id = f.exports.start(f.request("", "photos")).id();
        idle(id);
        Path species = folder().resolve(TIGER);
        Path pictures = Files.createDirectories(home.resolve("Pictures"));
        for (String name : List.of("Danaus_chrysippus_01.jpg", "Danaus_chrysippus_02.png")) {
            Files.copy(species.resolve(name), pictures.resolve(name), StandardCopyOption.COPY_ATTRIBUTES);
        }
        deleteTree(species);
        try {
            Files.createSymbolicLink(species, pictures);
        } catch (UnsupportedOperationException | IOException e) {
            assumeTrue(false, "no symbolic links here");
        }

        Exports.Export cancelled = f.exports.cancel(id, true);

        assertThat(pictures.resolve("Danaus_chrysippus_01.jpg")).exists();
        assertThat(pictures.resolve("Danaus_chrysippus_02.png")).exists();
        assertThat(cancelled.notRemoved()).as("both copies behind the link").isEqualTo(2);
        assertThat(Files.isSymbolicLink(species)).as("the link itself is not deleted either").isTrue();
    }

    @Test
    void removeWithTheExportFolderUnreachableRemovesNothingAndCountsEveryWrittenFile() throws Exception {
        pauseBefore(2);
        long id = f.exports.start(f.request("", "photos")).id();
        idle(id);
        Path moved = Files.move(folder(), home.resolve("elsewhere"));

        Exports.Export cancelled = f.exports.cancel(id, true);

        assertThat(cancelled.notRemoved()).isEqualTo(2);
        assertThat(files(moved)).hasSize(2);
    }

    @Test
    void removeKeepsAFileAtTheTargetOfAPhotoStillToCopy() throws Exception {
        pauseBefore(2);
        long id = f.exports.start(f.request("", "photos")).id();
        idle(id);
        Files.createDirectories(folder().resolve(MORMON));
        Path mine = Files.writeString(folder().resolve(MORMON + "Papilio_polytes_01.jpg"), "the user's own");

        Exports.Export cancelled = f.exports.cancel(id, true);

        assertThat(mine).hasContent("the user's own");
        assertThat(cancelled.notRemoved()).isEqualTo(1);
        assertThat(files(folder())).containsExactly(MORMON + "Papilio_polytes_01.jpg");
    }

    @Test
    void aRunningExportCutShortByClosingIsPausedAtStartupAndContinuesWhenResumed() throws Exception {
        f.exports.holdForStartup(); // nothing copies: as if the app were killed before
        long id = f.exports.start(f.request("", "photos")).id();
        byte[] copy = stripper.strip(located);

        Exports again = restartedWith(id, copy);
        try {
            assertThat(again.store().run(id).state()).isEqualTo("done");
            assertThat(again.store().run(id).copied()).isEqualTo(3);
            assertThat(files(folder())).containsExactly(TIGER + "Danaus_chrysippus_01.jpg", TIGER + "Danaus_chrysippus_02.png",
                    MORMON + "Papilio_polytes_01.jpg");
            assertThat(Files.readAllBytes(folder().resolve(TIGER + "Danaus_chrysippus_01.jpg"))).as("the same copy: kept")
                    .isEqualTo(copy);
        } finally {
            again.close();
        }
    }

    @Test
    void aFileLeftAtATargetThatIsNotTheSameCopyIsKeptAndThatPhotoFails() throws Exception {
        f.exports.holdForStartup();
        long id = f.exports.start(f.request("", "photos")).id();

        Exports again = restartedWith(id, "something else".getBytes());
        try {
            assertThat(again.store().run(id).state()).isEqualTo("done");
            assertThat(folder().resolve(TIGER + "Danaus_chrysippus_01.jpg")).hasContent("something else");
            assertThat(again.store().problems(id, 10)).extracting(ExportStore.Problem::imageId, ExportStore.Problem::reason)
                    .containsExactly(tuple(first, "The copy could not be written."));
        } finally {
            again.close();
        }
    }

    @Test
    void aFilePutAtATargetWhilePausedIsKeptOnResume() throws Exception {
        pauseBefore(2);
        long id = f.exports.start(f.request("", "photos")).id();
        idle(id);
        f.exports.beforePhoto(item -> { });
        Files.createDirectories(folder().resolve(MORMON));
        Files.writeString(folder().resolve(MORMON + "Papilio_polytes_01.jpg"), "the user's own");

        f.exports.resume(id);
        Exports.Export done = idle(id);

        assertThat(folder().resolve(MORMON + "Papilio_polytes_01.jpg")).hasContent("the user's own");
        assertThat(List.of(done.state(), String.valueOf(done.copied()), String.valueOf(done.failed())))
                .containsExactly("done", "2", "1");
        assertThat(reasons(id)).containsExactly("failed " + third + " The copy could not be written.");
    }

    @Test
    void aFileMadeAtTheTargetJustBeforeTheMoveIsKept() throws Exception {
        f.exports.beforeMove(target -> {
            if (target.getFileName().toString().equals("Danaus_chrysippus_02.png")) {
                try {
                    Files.writeString(target, "made in the gap");
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        });
        long id = f.exports.start(f.request("", "photos")).id();

        Exports.Export done = idle(id);

        assertThat(folder().resolve(TIGER + "Danaus_chrysippus_02.png")).hasContent("made in the gap");
        assertThat(folder().resolve(TIGER + "Danaus_chrysippus_02.png.part")).doesNotExist();
        assertThat(List.of(done.state(), String.valueOf(done.copied()), String.valueOf(done.failed())))
                .containsExactly("done", "2", "1");
        assertThat(reasons(id)).containsExactly("failed " + second + " The copy could not be written.");
    }

    @Test
    void aFileWhereAFolderShouldBeFailsThosePhotosAndTheExportFinishes() throws Exception {
        f.exports.holdForStartup();
        long id = f.exports.start(f.request("", "photos")).id();
        Files.writeString(folder().resolve("Papilionidae"), "a file, not a folder");
        f.exports.startupFilesSettled();

        Exports.Export done = idle(id);

        assertThat(List.of(done.state(), String.valueOf(done.copied()), String.valueOf(done.failed())))
                .containsExactly("done", "2", "1");
        assertThat(reasons(id)).containsExactly("failed " + third + " The copy could not be written.");
        assertThat(folder().resolve("Papilionidae")).hasContent("a file, not a folder");
    }

    @Test
    void sevenPhotosUnderAFileWhereTheirFolderShouldBeFailAndTheExportFinishes() throws Exception {
        for (int n = 2; n <= 7; n++) {
            f.photo("A12", "Papilio_polytes_0" + n + ".jpg", TestImages.jpeg(8, 6, n), null);
        }
        f.exports.holdForStartup();
        long id = f.exports.start(f.request("", "photos")).id();
        Files.writeString(folder().resolve("Papilionidae"), "a file, not a folder");
        f.exports.startupFilesSettled();

        Exports.Export done = idle(id);

        assertThat(List.of(done.state(), String.valueOf(done.copied()), String.valueOf(done.failed())))
                .as("photo failures, not write errors").containsExactly("done", "2", "7");
    }

    @Test
    void aVanishedExportFolderIsNeverMadeAgain() throws Exception {
        f.exports.beforePhoto(item -> {
            if (item.seq() == 2) {
                deleteTree(folder());
            }
        });
        long id = f.exports.start(f.request("", "photos")).id();

        assertThat(idle(id).pausedBy()).isEqualTo("drive_gone");
        assertThat(folder()).doesNotExist();
        f.exports.beforePhoto(item -> { });
        assertThat(f.exports.resume(id).pausedBy()).isEqualTo("drive_gone");
        assertThat(folder()).doesNotExist();
    }

    @Test
    void anythingButARegularFileAtATargetIsKeptUnopenedAndThatPhotoFails() throws Exception {
        Path pictures = Files.createDirectories(home.resolve("Pictures"));
        Path same = Files.write(pictures.resolve("same.jpg"), stripper.strip(located));
        f.exports.holdForStartup();
        long id = f.exports.start(f.request("", "photos")).id();
        Files.createDirectories(folder().resolve(TIGER));
        Files.createDirectories(folder().resolve(TIGER + "Danaus_chrysippus_02.png"));
        boolean linked = true;
        try {
            Files.createSymbolicLink(folder().resolve(TIGER + "Danaus_chrysippus_01.jpg"), same);
        } catch (UnsupportedOperationException | IOException e) {
            linked = false;
        }
        f.exports.startupFilesSettled();

        Exports.Export done = idle(id);

        assertThat(done.state()).isEqualTo("done");
        assertThat(folder().resolve(TIGER + "Danaus_chrysippus_02.png")).isDirectory();
        assertThat(reasons(id)).contains("failed " + second + " The copy could not be written.");
        if (linked) {
            assertThat(Files.isSymbolicLink(folder().resolve(TIGER + "Danaus_chrysippus_01.jpg"))).isTrue();
            assertThat(reasons(id)).as("a link to the same bytes is not this export's copy")
                    .contains("failed " + first + " The copy could not be written.");
        }
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void aFifoAtATargetFailsThatPhotoWithoutBlocking() throws Exception {
        f.exports.holdForStartup();
        long id = f.exports.start(f.request("", "photos")).id();
        Path fifo = folder().resolve(MORMON + "Papilio_polytes_01.jpg");
        Files.createDirectories(fifo.getParent());
        boolean made;
        try {
            made = new ProcessBuilder("mkfifo", fifo.toString()).start().waitFor() == 0;
        } catch (IOException e) {
            made = false;
        }
        assumeTrue(made && Files.exists(fifo), "no mkfifo here");
        f.exports.startupFilesSettled();

        Exports.Export done = idle(id);

        assertThat(done.state()).isEqualTo("done");
        assertThat(reasons(id)).containsExactly("failed " + third + " The copy could not be written.");
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void aCopyWhosePartCouldNotBeRemovedIsDoneAndThePartGoesAtTheNextStart() throws Exception {
        f.exports.partDeleter(part -> {
            if (part.getFileName().toString().startsWith("Danaus_chrysippus_01")) {
                throw new IOException("Operation not permitted");
            }
            Files.delete(part);
        });
        pauseBefore(2);
        long id = f.exports.start(f.request("", "photos")).id();
        idle(id);
        Path target = folder().resolve(TIGER + "Danaus_chrysippus_01.jpg");
        assertThat(f.exports.store().run(id).copied()).isEqualTo(2);
        assertThat(ExportCopier.part(target)).exists();
        assertThat(Files.getAttribute(target, "unix:nlink")).isEqualTo(2);
        f.exports.beforePhoto(item -> { });

        f.exports.resume(id);
        idle(id);

        assertThat(ExportCopier.part(target)).doesNotExist();
        assertThat(Files.getAttribute(target, "unix:nlink")).isEqualTo(1);
        assertThat(Files.readAllBytes(target)).isEqualTo(stripper.strip(located));
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void removeAlsoTakesAPartThatCouldNotBeRemovedAfterItsCopyWasPlaced() throws Exception {
        f.exports.partDeleter(part -> {
            throw new IOException("Operation not permitted");
        });
        pauseBefore(2);
        long id = f.exports.start(f.request("", "photos")).id();
        idle(id);

        Exports.Export cancelled = f.exports.cancel(id, true);

        assertThat(cancelled.notRemoved()).isZero();
        assertThat(folder()).doesNotExist();
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void aCopyHardLinkedFromItsPartBeforeACrashIsDoneAfterTheRestartWithOneLink() throws Exception {
        f.exports.holdForStartup();
        long id = f.exports.start(f.request("", "photos")).id();
        f.exports.close();
        Path target = folder().resolve(TIGER + "Danaus_chrysippus_01.jpg");
        Files.createDirectories(target.getParent());
        Files.write(ExportCopier.part(target), stripper.strip(located));
        Files.createLink(target, ExportCopier.part(target)); // the crash came between the link and removing the .part
        Exports again = new Exports(() -> f.library, new FilterQuery(f.library::database),
                stripper, TestBundles.CLOCK, ZoneOffset.UTC, p -> Long.MAX_VALUE);
        try {
            again.startup();
            again.resume(id);
            assertThat(again.awaitIdle(Duration.ofSeconds(20))).isTrue();

            assertThat(again.store().run(id).state()).isEqualTo("done");
            assertThat(again.store().run(id).copied()).isEqualTo(3);
            assertThat(ExportCopier.part(target)).doesNotExist();
            assertThat(Files.getAttribute(target, "unix:nlink")).isEqualTo(1);
        } finally {
            again.close();
        }
    }

    @Test
    void withoutHardLinksAMoveWithoutOptionsPlacesCopiesAndRefusesAnExistingTarget() throws Exception {
        f.exports.linker((target, existing) -> {
            throw new FileSystemException(target.toString(), null, "Operation not supported");
        });
        f.exports.beforeMove(target -> {
            if (target.getFileName().toString().equals("Danaus_chrysippus_02.png")) {
                try {
                    Files.writeString(target, "made in the gap");
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        });
        long id = f.exports.start(f.request("", "photos")).id();

        Exports.Export done = idle(id);

        assertThat(List.of(done.state(), String.valueOf(done.copied()), String.valueOf(done.failed())))
                .containsExactly("done", "2", "1");
        assertThat(folder().resolve(TIGER + "Danaus_chrysippus_02.png")).hasContent("made in the gap");
        assertThat(Files.readAllBytes(folder().resolve(MORMON + "Papilio_polytes_01.jpg"))).isEqualTo(plain);
        assertThat(files(folder())).doesNotContain(TIGER + "Danaus_chrysippus_02.png.part");
    }

    @Test
    void runningOutOfMemoryWhileRemovingALocationFailsOnlyThatPhoto() throws Exception {
        ExportFixture big = new ExportFixture(home.resolve("other"), new MetadataStripper() {
            @Override
            public byte[] strip(byte[] photo) {
                throw new OutOfMemoryError("Java heap space");
            }

            @Override
            public boolean hasLocation(Path file) {
                return false;
            }
        });
        big.photo("A5", "Danaus_chrysippus_01.jpg", located, null);
        try {
            long id = big.exports.start(big.request("", "photos")).id();
            assertThat(big.exports.awaitIdle(Duration.ofSeconds(20))).isTrue();
            assertThat(big.exports.store().run(id).state()).isEqualTo("done");
            assertThat(big.exports.store().problems(id, 10)).extracting(ExportStore.Problem::reason)
                    .containsExactly("Its location could not be removed.");
        } finally {
            big.exports.close();
        }
    }

    @Test
    void aCopyAskedForWhileHeldWaitsForTheStartup() throws Exception {
        f.exports.holdForStartup();
        long id = f.exports.start(f.request("", "photos")).id();

        Thread.sleep(200);
        assertThat(f.exports.store().run(id).done()).isZero();
        f.exports.startupFilesSettled();

        assertThat(idle(id).state()).isEqualTo("done");
    }

    @Test
    void aPhotoDeletedOrWhoseFileIsMissingIsSkippedWithItsReason() throws Exception {
        Files.delete(f.file(third));
        f.exports.beforePhoto(item -> {
            if (item.imageId() == second) {
                f.sql("UPDATE image SET deleted_at = '2026-10-09T09:00:00Z' WHERE id = " + second);
            }
        });
        long id = f.exports.start(f.request("", "photos")).id();

        Exports.Export done = idle(id);

        assertThat(List.of(done.state(), String.valueOf(done.copied()), String.valueOf(done.skipped())))
                .containsExactly("done", "1", "2");
        assertThat(reasons(id)).containsExactly("skipped " + second + " The photo was deleted after the export started.",
                "skipped " + third + " The photo's file is missing.");
    }

    @Test
    void aFullDrivePausesWithHowMuchMoreIsNeededAndBlamesNoPhoto() throws Exception {
        f.exports.opener(part -> {
            if (part.getFileName().toString().startsWith("Danaus_chrysippus_02")) {
                throw new IOException("No space left on device");
            }
            return Files.newOutputStream(part, StandardOpenOption.CREATE_NEW);
        });
        f.exports.nanos(() -> 0L); // the first photo is not saved yet when the second fails
        long id = f.exports.start(f.request("", "photos")).id();
        f.free.set(10);

        Exports.Export paused = idle(id);

        assertThat(List.of(paused.state(), paused.pausedBy())).containsExactly("paused", "drive_full");
        assertThat(paused.notice()).as("the sizes of the photos still to copy, less the free space")
                .isEqualTo(ExportErrors.driveFull(png.length + plain.length - 10));
        assertThat(List.of(paused.copied(), paused.failed())).containsExactly(1, 0);
        assertThat(files(folder())).containsExactly(TIGER + "Danaus_chrysippus_01.jpg");

        f.free.set(Long.MAX_VALUE);
        f.exports.opener(part -> Files.newOutputStream(part, StandardOpenOption.CREATE_NEW));
        f.exports.resume(id);
        assertThat(idle(id).copied()).isEqualTo(3);
    }

    @Test
    void anExportFolderThatVanishesPausesUntilItIsBack() throws Exception {
        f.exports.beforePhoto(item -> {
            if (item.seq() == 2) {
                deleteTree(folder());
            }
        });
        long id = f.exports.start(f.request("", "photos")).id();

        Exports.Export paused = idle(id);

        assertThat(List.of(paused.state(), paused.pausedBy(), paused.notice())).containsExactly("paused", "drive_gone",
                "The export folder can't be reached. Reconnect the drive, then resume.");
        assertThat(paused.done()).isEqualTo(1);
        f.exports.beforePhoto(item -> { });

        Exports.Export still = f.exports.resume(id);
        assertThat(List.of(still.state(), still.pausedBy())).containsExactly("paused", "drive_gone");

        Files.createDirectories(folder());
        f.exports.resume(id);
        assertThat(idle(id).state()).isEqualTo("done");
        assertThat(files(folder())).hasSize(2);
    }

    @Test
    void fiveFailedWritesInARowPauseAndThosePhotosStayToCopy() throws Exception {
        for (int n = 2; n <= 6; n++) {
            f.photo("A12", "Papilio_polytes_0" + n + ".jpg", TestImages.jpeg(8, 6, n), null);
        }
        AtomicInteger opened = new AtomicInteger();
        f.exports.opener(part -> {
            if (opened.incrementAndGet() > 1) {
                throw new IOException("I/O error");
            }
            return Files.newOutputStream(part, StandardOpenOption.CREATE_NEW);
        });
        long id = f.exports.start(f.request("", "photos")).id();

        Exports.Export paused = idle(id);

        assertThat(List.of(paused.state(), paused.pausedBy(), paused.notice())).containsExactly("paused", "write_errors",
                "Several photos in a row could not be written. Check the drive, then resume.");
        assertThat(List.of(paused.copied(), paused.failed(), paused.done())).containsExactly(1, 0, 1);
        f.exports.opener(part -> Files.newOutputStream(part, StandardOpenOption.CREATE_NEW));
        f.exports.resume(id);
        assertThat(idle(id).copied()).isEqualTo(8);
    }

    @Test
    void aWriteThatFailsOnceIsThatPhotosFailureWhenTheNextOneWorks() throws Exception {
        f.exports.opener(part -> {
            if (part.getFileName().toString().startsWith("Danaus_chrysippus_02")) {
                throw new IOException("I/O error");
            }
            return Files.newOutputStream(part, StandardOpenOption.CREATE_NEW);
        });
        long id = f.exports.start(f.request("", "photos")).id();

        Exports.Export done = idle(id);

        assertThat(List.of(done.state(), String.valueOf(done.copied()), String.valueOf(done.failed())))
                .containsExactly("done", "2", "1");
        assertThat(reasons(id)).containsExactly("failed " + second + " The copy could not be written.");
    }

    @Test
    void aFileAlreadyAtATargetIsNeverOverwritten() throws Exception {
        f.exports.beforePhoto(item -> {
            if (item.seq() == 2) {
                try {
                    Files.writeString(folder().resolve(TIGER + "Danaus_chrysippus_02.png"), "the user's own");
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        });
        long id = f.exports.start(f.request("", "photos")).id();

        idle(id);

        assertThat(folder().resolve(TIGER + "Danaus_chrysippus_02.png")).hasContent("the user's own");
        assertThat(reasons(id)).containsExactly("failed " + second + " The copy could not be written.");
        assertThat(folder().resolve(TIGER + "Danaus_chrysippus_02.png.part")).doesNotExist();
    }

    @Test
    void aPhotoWhoseLocationCannotBeRemovedOrStaysIsFailedAndNoCopyIsLeft() throws Exception {
        f.photo("A12", "Papilio_polytes_02.jpg", new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 1, 2, 3}, null);
        ExportFixture leaky = new ExportFixture(home.resolve("other"), new MetadataStripper() {
            @Override
            public byte[] strip(byte[] photo) {
                return photo;
            }

            @Override
            public boolean hasLocation(Path file) throws IOException {
                return stripper.hasLocation(file);
            }
        });
        leaky.photo("A5", "Danaus_chrysippus_01.jpg", located, null);
        try {
            long id = leaky.exports.start(leaky.request("", "photos")).id();
            assertThat(leaky.exports.awaitIdle(Duration.ofSeconds(20))).isTrue();
            assertThat(leaky.exports.store().problems(id, 10)).extracting(ExportStore.Problem::reason)
                    .containsExactly("It still had a location after removing it, so the copy was deleted.");
            assertThat(files(leaky.dest)).isEmpty();
        } finally {
            leaky.exports.close();
        }

        long id = f.exports.start(f.request("taxa=A12", "photos")).id();
        idle(id);
        assertThat(f.exports.store().problems(id, 10)).extracting(ExportStore.Problem::reason)
                .containsExactly("Its location could not be removed.");
    }

    private void pauseBefore(int seq) {
        f.exports.beforePhoto(item -> {
            if (item.seq() == seq) {
                f.exports.pause(f.exports.current().orElseThrow().id());
            }
        });
    }

    private static void deleteTree(Path folder) {
        try (Stream<Path> all = Files.walk(folder)) {
            for (Path p : all.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
