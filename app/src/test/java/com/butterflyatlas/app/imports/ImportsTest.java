package com.butterflyatlas.app.imports;

import com.butterflyatlas.app.AtlasException;
import com.butterflyatlas.app.imaging.ImageIoPhotoProcessor;
import com.butterflyatlas.app.library.Library;
import com.butterflyatlas.app.photos.PhotoFacts;
import com.butterflyatlas.app.photos.PhotoIngest;
import com.butterflyatlas.app.photos.PhotoProcessor;
import com.butterflyatlas.app.photos.PhotoStore;
import com.butterflyatlas.app.photos.Thumbnails;
import com.butterflyatlas.app.search.SearchService;
import com.butterflyatlas.app.testing.TestBundles;
import com.butterflyatlas.app.testing.TestImages;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assumptions.assumeThat;

class ImportsTest {

    private static final Duration WAIT = Duration.ofSeconds(20);

    @TempDir
    Path dir;

    private Path root;
    private Path data;
    private Library library;
    private Thumbnails thumbnails;
    private ImageIoPhotoProcessor processor;
    private PhotoStore photos;
    private Imports imports;

    @BeforeEach
    void setUp() throws IOException {
        root = Files.createDirectories(dir.resolve("Butterflies 2024"));
        data = dir.resolve("Atlas");
        library = TestBundles.fixtureLibrary(data);
        processor = new ImageIoPhotoProcessor();
        thumbnails = new Thumbnails(() -> library, processor, TestBundles.CLOCK);
        photos = new PhotoStore(() -> library, processor, TestBundles.CLOCK, thumbnails::request);
        imports = new Imports(() -> library, dir, new SearchService(() -> library.database()), photos, processor, thumbnails,
                TestBundles.CLOCK);
    }

    @AfterEach
    void close() {
        imports.close();
        thumbnails.close();
    }

    private void write(String relPath, byte[] bytes) throws IOException {
        Path file = root.resolve(relPath);
        Files.createDirectories(file.getParent());
        Files.write(file, bytes);
    }

    private long started() throws Exception {
        long id = imports.start(root.toString()).summary().id();
        assertThat(imports.awaitIdle(WAIT)).isTrue();
        return id;
    }

    private Map<String, ImportStore.Item> items(long id) {
        return imports.items(id, null).items().stream().collect(Collectors.toMap(ImportStore.Item::path, Function.identity()));
    }

    @Test
    void choosingAFolderStartsAnImportAndChoosingItAgainContinuesIt() throws Exception {
        write("Plain tiger/IMG_1.JPG", TestImages.jpeg(64, 48, 1));
        write("IMG_2.JPG", TestImages.jpeg(64, 48, 2));

        Imports.Started first = imports.start(root.toString());

        assertThat(first.continued()).isFalse();
        assertThat(first.summary().rootName()).isEqualTo("Butterflies 2024");
        assertThat(first.summary().state()).isEqualTo("open");
        assertThat(first.summary().folderMissing()).isFalse();
        assertThat(imports.awaitIdle(WAIT)).isTrue();
        Imports.Summary read = imports.summary(first.summary().id());
        assertThat(read.phase()).isEqualTo("idle");
        assertThat(read.progress()).isNull();
        assertThat(read.counts()).isEqualTo(new ImportStore.Counts(2, 2, 0, 1, 1, 0, 0, 0, 0, 0));

        write("IMG_3.JPG", TestImages.jpeg(64, 48, 3));
        Imports.Started again = imports.start(root.resolve("x").resolve("..").toString());
        assertThat(again.continued()).isTrue();
        assertThat(again.summary().id()).isEqualTo(first.summary().id());
        assertThat(imports.awaitIdle(WAIT)).isTrue();
        assertThat(imports.summary(first.summary().id()).counts().total()).as("walked again").isEqualTo(3);
        assertThat(imports.list()).hasSize(1);
    }

    @Test
    void foldersThatCannotBeImportedAreRefused() throws Exception {
        assertThatThrownBy(() -> imports.start(dir.resolve("nope").toString())).isInstanceOfSatisfying(AtlasException.class,
                e -> assertThat(e.code()).isEqualTo("folder_not_found"));
        assertThatThrownBy(() -> imports.start("relative")).isInstanceOfSatisfying(AtlasException.class,
                e -> assertThat(e.code()).isEqualTo("bad_path"));
        assertThatThrownBy(() -> imports.start(data.resolve("images").toString().replace("images", ""))).isInstanceOfSatisfying(
                AtlasException.class, e -> assertThat(e.code()).isEqualTo("library_folder"));
        assertThatThrownBy(() -> imports.summary(404)).isInstanceOfSatisfying(AtlasException.class,
                e -> assertThat(e.code()).isEqualTo("import_not_found"));
        assertThat(imports.list()).isEmpty();
    }

    @Test
    void filingNeedsAssignedPhotosAndTheFolderThenRunsInTheBackground() throws Exception {
        write("a.jpg", TestImages.jpeg(64, 48, 4));
        write("b.jpg", TestImages.jpeg(64, 48, 5));
        long id = started();
        Map<String, ImportStore.Item> items = items(id);

        assertThatThrownBy(() -> imports.file(id)).isInstanceOfSatisfying(AtlasException.class, e -> {
            assertThat(e.code()).isEqualTo("nothing_to_file");
            assertThat(e.getMessage()).isEqualTo("Assign a species to some photos first.");
        });
        imports.assign(id, List.of(items.get("a.jpg").id()), "A5");
        imports.file(id);
        assertThat(imports.awaitIdle(WAIT)).isTrue();

        assertThat(items(id).get("a.jpg").outcome()).isEqualTo("filed");
        assertThat(imports.result(id).filed()).isEqualTo(1);
        assertThat(imports.summary(id).paused()).isFalse();

        imports.assign(id, List.of(items.get("b.jpg").id()), "A6");
        Files.delete(root.resolve("a.jpg"));
        Files.delete(root.resolve("b.jpg"));
        Files.delete(root);
        assertThatThrownBy(() -> imports.file(id)).isInstanceOfSatisfying(AtlasException.class,
                e -> assertThat(e.code()).isEqualTo("folder_missing"));
        assertThat(imports.summary(id).folderMissing()).isTrue();
        Imports.Started continued = imports.start(root.toString());
        assertThat(continued.continued()).as("a missing folder's import is continued, not walked").isTrue();
        assertThat(imports.awaitIdle(WAIT)).isTrue();
        assertThat(items(id).get("b.jpg").state()).isEqualTo("read");
    }

    @Test
    void aPhaseTheAppsClosingCutShortIsReportedAsStoppedUntilTheUserGoesOn() throws Exception {
        write("a.jpg", TestImages.jpeg(64, 48, 6));
        long id = started();
        imports.store().setActive(id, "filing", 1200, 2140); // as the app left it when it closed mid-filing

        imports.startup();

        assertThat(imports.summary(id).stopped()).isEqualTo(new ImportStore.Stopped("filing", 1200, 2140,
                "2026-10-09T08:00:00Z"));
        assertThat(imports.stopped()).containsExactly(new Imports.Working(id, "Butterflies 2024", "filing", 1200, 2140));
        assertThat(imports.summary(id).phase()).as("nothing restarts by itself").isEqualTo("idle");
        imports.start(root.toString());
        assertThat(imports.awaitIdle(WAIT)).isTrue();
        assertThat(imports.summary(id).stopped()).as("a stopped filing waits for the user to file").isNotNull();

        imports.assign(id, List.of(items(id).get("a.jpg").id()), "A5");
        imports.file(id);
        assertThat(imports.awaitIdle(WAIT)).isTrue();
        assertThat(imports.summary(id).stopped()).isNull();
        assertThat(imports.stopped()).isEmpty();

        imports.store().setActive(id, "walking", 30, 0);
        imports.startup();
        assertThat(imports.summary(id).stopped().phase()).isEqualTo("reading");
        imports.start(root.toString());
        assertThat(imports.awaitIdle(WAIT)).isTrue();
        assertThat(imports.summary(id).stopped()).as("continuing clears a stopped reading").isNull();
    }

    @Test
    void startupRemovesPreviewsOfImportsThatAreNoLongerOpen() throws Exception {
        write("a.jpg", TestImages.jpeg(64, 48, 7));
        long open = started();
        Path stray = Files.createDirectories(ImportStore.previewFolder(data, 99));
        Files.write(stray.resolve("1-abc.jpg"), new byte[] {1});

        assertThat(imports.startup()).isEqualTo(1);

        assertThat(stray).doesNotExist();
        assertThat(ImportStore.previewFolder(data, open)).isDirectory();
    }

    @Test
    void finishingWithTheRestSkippedKeepsTheResultAndRemovesThePreviews() throws Exception {
        write("a.jpg", TestImages.jpeg(64, 48, 8));
        write("b.jpg", TestImages.jpeg(64, 48, 9));
        long id = started();
        imports.assign(id, List.of(items(id).get("a.jpg").id()), "A5");
        imports.file(id);
        assertThat(imports.awaitIdle(WAIT)).isTrue();

        Imports.Finished finished = imports.finish(id, true);

        assertThat(finished.summary().state()).isEqualTo("finished");
        assertThat(finished.result().filed()).isEqualTo(1);
        assertThat(finished.result().skipped()).isEqualTo(1);
        assertThat(finished.result().left()).isZero();
        assertThat(ImportStore.previewFolder(data, id)).doesNotExist();
        assertThat(imports.list()).extracting(Imports.Summary::state).containsExactly("finished");
        Imports.Started fresh = imports.start(root.toString());
        assertThat(fresh.continued()).as("a finished import is not continued").isFalse();
        assertThat(imports.awaitIdle(WAIT)).isTrue();
    }

    @Test
    void discardingRemovesTheImportButNeverThePhotosOrTheFolder() throws Exception {
        write("a.jpg", TestImages.jpeg(64, 48, 10));
        long id = started();
        imports.assign(id, List.of(items(id).get("a.jpg").id()), "A5");
        imports.file(id);
        assertThat(imports.awaitIdle(WAIT)).isTrue();

        imports.discard(id);

        assertThatThrownBy(() -> imports.summary(id)).isInstanceOf(AtlasException.class);
        assertThat(ImportStore.previewFolder(data, id)).doesNotExist();
        assertThat(root.resolve("a.jpg")).exists();
        assertThat(library.database().read(c -> {
            try (var ps = c.prepareStatement("SELECT COUNT(*) FROM image"); var rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }).intValue()).isEqualTo(1);
    }

    @Test
    void previewsAndTheLargeViewServeOnlyThisImportsItems() throws Exception {
        write("a.jpg", TestImages.jpeg(800, 600, 11));
        write("b.tif", TestImages.tiff(3000, 2000));
        long id = started();
        Map<String, ImportStore.Item> items = items(id);
        long a = items.get("a.jpg").id();
        long b = items.get("b.tif").id();

        Imports.Preview preview = imports.preview(id, a);
        assertThat(preview.file()).exists();
        assertThat(preview.pending()).isFalse();
        Imports.Display jpeg = imports.display(id, a);
        assertThat(jpeg.file()).isEqualTo(root.resolve("a.jpg"));
        assertThat(jpeg.contentType()).isEqualTo("image/jpeg");
        Imports.Display tiff = imports.display(id, b);
        assertThat(tiff.contentType()).isEqualTo("image/jpeg");
        BufferedImage large = ImageIO.read(tiff.file().toFile());
        assertThat(large.getWidth()).isEqualTo(2560);

        write("other/c.jpg", TestImages.jpeg(64, 48, 12));
        long other = imports.start(root.resolve("other").toString()).summary().id();
        assertThat(imports.awaitIdle(WAIT)).isTrue();
        assertThatThrownBy(() -> imports.preview(other, a)).isInstanceOfSatisfying(AtlasException.class,
                e -> assertThat(e.kind()).isEqualTo(AtlasException.Kind.NOT_FOUND));
        assertThatThrownBy(() -> imports.display(other, a)).isInstanceOf(AtlasException.class);
    }

    @Test
    void aPreviewOfAnUnknownImportIsImportNotFound() {
        assertThatThrownBy(() -> imports.preview(404, 1)).isInstanceOfSatisfying(AtlasException.class,
                e -> assertThat(e.code()).isEqualTo("import_not_found"));
    }

    @Test
    void theLargeViewOfAFinishedImportsTiffLeavesNoCopyBehind() throws Exception {
        write("b.tif", TestImages.tiff(3000, 2000));
        long id = started();
        long b = items(id).get("b.tif").id();
        imports.finish(id, false);
        Path previews = ImportStore.previewFolder(data, id);
        assertThat(previews).doesNotExist();

        Imports.Display tiff = imports.display(id, b);

        assertThat(tiff.file()).isNull();
        assertThat(ImageIO.read(new java.io.ByteArrayInputStream(tiff.bytes())).getWidth()).isEqualTo(2560);
        assertThat(previews).as("not made again for a finished import").doesNotExist();
    }

    @Test
    void savingProgressStartsWithTheFirstPhaseNotWithTheImports() throws Exception {
        long before = savers();
        Imports idle = fresh();
        try {
            assertThat(savers()).as("no thread for imports that never work").isEqualTo(before);
        } finally {
            idle.close();
        }
        write("a.jpg", TestImages.jpeg(64, 48, 42));
        started();
        assertThat(savers()).isGreaterThan(before);
        imports.close();
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (savers() > before && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertThat(savers()).as("stopped with the imports").isEqualTo(before);
    }

    private static long savers() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(t -> t.getName().equals("atlas-import-progress") && t.isAlive()).count();
    }

    /** Replaces the imports with one reading through {@code reader}, filing with {@code filer} (null: the usual one). */
    private void rebuild(PhotoProcessor reader, ImportFiler filer, Duration cancelWait) {
        imports.close();
        imports = new Imports(() -> library, dir, new SearchService(() -> library.database()), photos, reader, thumbnails,
                TestBundles.CLOCK, filer != null ? filer : new ImportFiler(() -> library, photos, processor, thumbnails,
                TestBundles.CLOCK), cancelWait);
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(20, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Reads as usual, but each preview first passes {@code entered} and waits for {@code release}. */
    private static final class Gated implements PhotoProcessor {

        private final PhotoProcessor real = new ImageIoPhotoProcessor();
        private final CountDownLatch entered;
        private final CountDownLatch release = new CountDownLatch(1);

        Gated(int held) {
            entered = new CountDownLatch(held);
        }

        @Override
        public PhotoFacts read(Path file) throws IOException {
            return real.read(file);
        }

        @Override
        public void thumbnail(Path source, int orientation, int longSide, Path target) throws IOException {
            entered.countDown();
            await(release);
            real.thumbnail(source, orientation, longSide, target);
        }

        @Override
        public String dominantColor(Path image) throws IOException {
            return real.dominantColor(image);
        }
    }

    private String active(long id) {
        return library.database().read(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT active_phase || '|' || active_done || '|' || active_total"
                    + " FROM import_run WHERE id = ?")) {
                ps.setLong(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getString(1);
                }
            }
        });
    }

    @Test
    void aFolderEmptiedWherePhotosWereWaitingIsTreatedAsMissing() throws Exception {
        byte[] a = TestImages.jpeg(64, 48, 30);
        write("a.jpg", a);
        write("b.jpg", TestImages.jpeg(64, 48, 31));
        long id = started();
        imports.assign(id, List.of(items(id).get("a.jpg").id()), "A5");
        Files.delete(root.resolve("a.jpg"));
        Files.delete(root.resolve("b.jpg"));

        imports.start(root.toString());
        assertThat(imports.awaitIdle(WAIT)).isTrue();

        assertThat(imports.summary(id).folderMissing()).as("an unplugged drive or an emptied folder").isTrue();
        assertThat(imports.list()).extracting(Imports.Summary::folderMissing).containsExactly(true);
        assertThat(items(id).get("a.jpg").state()).as("nothing marked missing").isEqualTo("read");
        assertThatThrownBy(() -> imports.file(id)).isInstanceOfSatisfying(AtlasException.class,
                e -> assertThat(e.code()).isEqualTo("folder_missing"));

        write("a.jpg", a);
        imports.start(root.toString());
        assertThat(imports.awaitIdle(WAIT)).isTrue();
        assertThat(imports.summary(id).folderMissing()).isFalse();
        assertThat(items(id).get("b.jpg").state()).isEqualTo("missing");
    }

    @Test
    void discardingWhileReadingIsANormalStopAndLeavesNoPreviews() throws Exception {
        Gated gated = new Gated(1);
        rebuild(gated, null, Duration.ofMillis(50)); // the held reading outlasts the discard's wait
        write("a.jpg", TestImages.jpeg(64, 48, 32));
        write("b.jpg", TestImages.jpeg(64, 48, 33));
        long id = imports.start(root.toString()).summary().id();
        await(gated.entered);

        imports.discard(id);
        gated.release.countDown();

        assertThat(imports.awaitIdle(WAIT)).isTrue();
        assertThat(imports.failedPhases()).as("not logged as a failure").isZero();
        assertThat(ImportStore.previewFolder(data, id)).as("previews made after the discard go too").doesNotExist();
        assertThatThrownBy(() -> imports.summary(id)).isInstanceOf(AtlasException.class);
        assertThat(root.resolve("a.jpg")).exists();
    }

    @Test
    void filesChangedSinceTheWalkAreWalkedAgainAndReadInTheSameReading() throws Exception {
        Gated gated = new Gated(ImportReader.AT_ONCE);
        rebuild(gated, null, Imports.CANCEL_WAIT);
        for (int n = 1; n <= ImportReader.AT_ONCE + 1; n++) {
            write("IMG_" + n + ".jpg", TestImages.jpeg(64, 48, 40 + n));
        }
        long id = imports.start(root.toString()).summary().id();
        await(gated.entered); // the first four are being read; the last is not looked at yet
        byte[] edited = TestImages.jpeg(96, 72, 49);
        write("IMG_5.jpg", edited);
        gated.release.countDown();

        assertThat(imports.awaitIdle(WAIT)).isTrue();

        ImportStore.Item last = items(id).get("IMG_5.jpg");
        assertThat(last.state()).as("not left pending until the next continue").isEqualTo("read");
        assertThat(last.size()).isEqualTo(edited.length);
        assertThat(last.width()).isEqualTo(96);
        assertThat(imports.summary(id).counts().reading()).isZero();
    }

    @Test
    void aFinishedImportIsReadOnlyButCanBeDeleted() throws Exception {
        write("a.jpg", TestImages.jpeg(64, 48, 34));
        long id = started();
        List<Long> item = List.of(items(id).get("a.jpg").id());
        imports.finish(id, false);

        for (org.assertj.core.api.ThrowableAssert.ThrowingCallable change : List.<org.assertj.core.api.ThrowableAssert.ThrowingCallable>of(
                () -> imports.assign(id, item, "A5"), () -> imports.tags(id, item, List.of("male"), List.of()),
                () -> imports.skip(id, item, true), () -> imports.acceptSuggestions(id, item), () -> imports.file(id),
                () -> imports.relocate(id, root.toString()))) {
            assertThatThrownBy(change).isInstanceOfSatisfying(AtlasException.class, e -> {
                assertThat(e.code()).isEqualTo("import_finished");
                assertThat(e.kind()).isEqualTo(AtlasException.Kind.CONFLICT);
                assertThat(e.getMessage()).isEqualTo("This import is finished.");
            });
        }
        assertThat(imports.finish(id, true).result().skipped()).as("finishing again changes nothing").isZero();
        assertThat(items(id).get("a.jpg").skipped()).isFalse();

        imports.discard(id);
        assertThatThrownBy(() -> imports.summary(id)).isInstanceOf(AtlasException.class);
    }

    @Test
    void theItemsAndTheirRevisionAreReadUnderTheImportsLockSoTheyAlwaysMatch() throws Exception {
        write("a.jpg", TestImages.jpeg(64, 48, 36));
        long id = started();
        long a = items(id).get("a.jpg").id();
        long before = imports.items(id, null).revision();
        CountDownLatch inEdit = new CountDownLatch(1);
        CountDownLatch letGo = new CountDownLatch(1);
        imports.duringEdit = () -> {
            inEdit.countDown();
            await(letGo);
        };
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> assigning = pool.submit(() -> imports.assign(id, List.of(a), "A5"));
            await(inEdit); // the assignment holds the lock and has not changed anything yet
            imports.duringEdit = () -> { };
            Future<ImportStore.ItemsPage> reading = pool.submit(() -> imports.items(id, null));
            long deadline = System.nanoTime() + WAIT.toNanos();
            while (!imports.waitingFor(id)) {
                assertThat(System.nanoTime()).as("the read waits for the change").isLessThan(deadline);
                Thread.onSpinWait();
            }
            assertThat(reading).isNotDone();

            letGo.countDown();
            assigning.get(20, TimeUnit.SECONDS);
            ImportStore.ItemsPage page = reading.get(20, TimeUnit.SECONDS);
            assertThat(page.revision()).isEqualTo(before + 1);
            assertThat(page.items()).singleElement().satisfies(item -> assertThat(item.taxonId()).isEqualTo("A5"));
        } finally {
            letGo.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void filingCannotStartInTheMiddleOfAChange() throws Exception {
        write("a.jpg", TestImages.jpeg(64, 48, 35));
        long id = started();
        long a = items(id).get("a.jpg").id();
        CountDownLatch inEdit = new CountDownLatch(1);
        CountDownLatch letGo = new CountDownLatch(1);
        imports.duringEdit = () -> {
            inEdit.countDown();
            await(letGo);
        };
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> assigning = pool.submit(() -> imports.assign(id, List.of(a), "A5"));
            await(inEdit); // the assignment has passed its checks and has not changed anything yet
            imports.duringEdit = () -> { };
            Future<Imports.Summary> filing = pool.submit(() -> imports.file(id));
            long deadline = System.nanoTime() + WAIT.toNanos();
            while (!imports.waitingFor(id)) {
                assertThat(System.nanoTime()).as("filing waits for the change").isLessThan(deadline);
                Thread.onSpinWait();
            }
            assertThat(filing).isNotDone();

            letGo.countDown();
            assigning.get(20, TimeUnit.SECONDS);
            filing.get(20, TimeUnit.SECONDS); // had it not waited, there would have been nothing to file
        } finally {
            letGo.countDown();
            pool.shutdownNow();
        }
        assertThat(imports.awaitIdle(WAIT)).isTrue();
        assertThat(items(id).get("a.jpg").outcome()).isEqualTo("filed");
    }

    @Test
    void aFullLibraryDriveEndsFilingWithANoticeThatClearsWhenFilingStartsAgain() throws Exception {
        AtomicLong space = new AtomicLong(0);
        rebuild(processor, new ImportFiler(() -> library, photos, processor, thumbnails, TestBundles.CLOCK, ids -> { },
                ImportFiler.Opener.NIO, new PhotoIngest(processor, photos.maxBytes())::ingest, folder -> space.get()),
                Imports.CANCEL_WAIT);
        write("a.jpg", TestImages.jpeg(64, 48, 36));
        long id = started();
        imports.assign(id, List.of(items(id).get("a.jpg").id()), "A5");

        imports.file(id);
        assertThat(imports.awaitIdle(WAIT)).isTrue();

        Imports.Summary stoppedFiling = imports.summary(id);
        assertThat(stoppedFiling.notice()).isEqualTo("Your library's drive is full. Free up some space, then continue filing.");
        assertThat(stoppedFiling.phase()).isEqualTo("idle");
        assertThat(stoppedFiling.paused()).isFalse();
        assertThat(stoppedFiling.stopped()).isNull();
        assertThat(imports.working()).as("/api/status import").isEmpty();
        assertThat(imports.failedPhases()).isZero();
        assertThat(items(id).get("a.jpg").outcome()).as("left as it was").isNull();
        imports.startup();
        assertThat(imports.summary(id).stopped()).as("ended, not cut short").isNull();

        space.set(Long.MAX_VALUE);
        assertThat(imports.file(id).notice()).isNull();
        assertThat(imports.awaitIdle(WAIT)).isTrue();
        assertThat(items(id).get("a.jpg").outcome()).isEqualTo("filed");
        assertThat(imports.summary(id).notice()).isNull();
    }

    @Test
    void theRunningPhasesProgressIsSavedApartFromTheReports() throws Exception {
        Gated gated = new Gated(1);
        rebuild(gated, null, Imports.CANCEL_WAIT);
        write("a.jpg", TestImages.jpeg(64, 48, 37));
        write("b.jpg", TestImages.jpeg(64, 48, 38));
        long id = imports.start(root.toString()).summary().id();
        await(gated.entered);

        imports.saveProgress();
        assertThat(active(id)).isEqualTo("reading|0|2");

        gated.release.countDown();
        assertThat(imports.awaitIdle(WAIT)).isTrue();
        imports.saveProgress();
        assertThat(active(id)).as("cleared when the phase ended, and a later save leaves it so").isNull();
    }

    @Test
    void aPhotoLibraryOrAppPackageIsNotAnImportFolder() throws Exception {
        Path library = Files.createDirectories(dir.resolve("Photos Library.photoslibrary"));
        Path originals = Files.createDirectories(library.resolve("originals").resolve("0"));
        Files.write(originals.resolve("a.jpg"), TestImages.jpeg(64, 48, 40));
        for (Path folder : List.of(library, originals)) {
            assertThatThrownBy(() -> imports.start(folder.toString())).isInstanceOfSatisfying(AtlasException.class, e -> {
                assertThat(e.code()).isEqualTo("package_folder");
                assertThat(e.kind()).isEqualTo(AtlasException.Kind.INVALID);
                assertThat(e.getMessage()).isEqualTo(
                        "That's a photo library or app package, not a folder of photos. Choose a normal folder.");
            });
        }
        write("a.jpg", TestImages.jpeg(64, 48, 41));
        long id = started();
        assertThatThrownBy(() -> imports.relocate(id, originals.toString())).isInstanceOfSatisfying(AtlasException.class,
                e -> assertThat(e.code()).isEqualTo("package_folder"));
        assertThat(imports.summary(id).rootPath()).isEqualTo(root.toString());
        assertThat(imports.list()).hasSize(1);
    }

    @Test
    void aRelocateStopsWhenTheAppCloses() throws Exception {
        write("a.jpg", TestImages.jpeg(64, 48, 39));
        long id = started();
        Path moved = Files.createDirectories(dir.resolve("moved"));
        Files.copy(root.resolve("a.jpg"), moved.resolve("a.jpg"));

        imports.close();

        assertThatThrownBy(() -> imports.relocate(id, moved.toString())).isInstanceOfSatisfying(AtlasException.class,
                e -> assertThat(e.kind()).isEqualTo(AtlasException.Kind.BUSY));
        assertThat(imports.summary(id).rootPath()).isEqualTo(root.toString());
    }

    private Imports fresh() {
        return new Imports(() -> library, dir, new SearchService(() -> library.database()), photos, processor, thumbnails,
                TestBundles.CLOCK);
    }

    /** Closes the imports, letting {@code release} go only once the closing has begun. */
    private void closeReleasing(CountDownLatch release) throws InterruptedException {
        Imports closing = imports;
        Thread releaser = new Thread(() -> {
            long deadline = System.nanoTime() + WAIT.toNanos();
            while (!closing.closing() && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            release.countDown();
        });
        releaser.start();
        closing.close();
        releaser.join(WAIT.toMillis());
    }

    private static void spinUntil(java.util.function.BooleanSupplier condition, String what) {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (!condition.getAsBoolean()) {
            assertThat(System.nanoTime()).as(what).isLessThan(deadline);
            Thread.onSpinWait();
        }
    }

    @Test
    void closingTheAppMidReadingIsReportedAsStoppedAtTheNextStart() throws Exception {
        Gated gated = new Gated(2);
        rebuild(gated, null, Imports.CANCEL_WAIT);
        write("a.jpg", TestImages.jpeg(64, 48, 50));
        write("b.jpg", TestImages.jpeg(64, 48, 51));
        long id = imports.start(root.toString()).summary().id();
        await(gated.entered);

        closeReleasing(gated.release);

        try (Imports next = fresh()) {
            next.startup();
            assertThat(next.summary(id).stopped()).isEqualTo(new ImportStore.Stopped("reading", 2, 2, "2026-10-09T08:00:00Z"));
            assertThat(next.stopped()).extracting(Imports.Working::id).containsExactly(id);
        }
    }

    @Test
    void closingTheAppMidFilingIsReportedAsStoppedAtTheNextStart() throws Exception {
        CountDownLatch inBatch = new CountDownLatch(1);
        CountDownLatch letGo = new CountDownLatch(1);
        rebuild(processor, new ImportFiler(() -> library, photos, processor, thumbnails, TestBundles.CLOCK, ids -> {
            inBatch.countDown();
            await(letGo);
        }), Imports.CANCEL_WAIT);
        write("a.jpg", TestImages.jpeg(64, 48, 52));
        long id = started();
        imports.assign(id, List.of(items(id).get("a.jpg").id()), "A5");
        imports.file(id);
        await(inBatch);

        closeReleasing(letGo);

        try (Imports next = fresh()) {
            next.startup();
            assertThat(next.summary(id).stopped()).isEqualTo(new ImportStore.Stopped("filing", 1, 1, "2026-10-09T08:00:00Z"));
        }
    }

    @Test
    void finishingOrRelocatingWhileTheLastStepOutlastsTheWaitIsRefusedForNow() throws Exception {
        Gated gated = new Gated(1);
        rebuild(gated, null, Duration.ofMillis(20));
        write("a.jpg", TestImages.jpeg(64, 48, 53));
        long id = imports.start(root.toString()).summary().id();
        await(gated.entered);

        for (org.assertj.core.api.ThrowableAssert.ThrowingCallable call : List.<org.assertj.core.api.ThrowableAssert.ThrowingCallable>of(
                () -> imports.finish(id, true), () -> imports.relocate(id, root.toString()))) {
            assertThatThrownBy(call).isInstanceOfSatisfying(AtlasException.class, e -> {
                assertThat(e.kind()).isEqualTo(AtlasException.Kind.BUSY);
                assertThat(e.getMessage()).isEqualTo("Butterfly Atlas is still finishing the last step. Try again in a moment.");
            });
        }
        assertThat(imports.summary(id).state()).isEqualTo("open");
        assertThat(items(id).get("a.jpg").skipped()).isFalse();

        gated.release.countDown();
        assertThat(imports.awaitIdle(WAIT)).isTrue();
        assertThat(imports.finish(id, false).summary().state()).isEqualTo("finished");
    }

    @Test
    void twoStartsOfOneFolderAtOnceMakeOneImport() throws Exception {
        write("a.jpg", TestImages.jpeg(64, 48, 54));
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch letGo = new CountDownLatch(1);
        imports.beforeCreate = () -> {
            inside.countDown();
            await(letGo);
        };
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Imports.Started> first = pool.submit(() -> imports.start(root.toString()));
            await(inside);
            imports.beforeCreate = () -> { };
            Future<Imports.Started> second = pool.submit(() -> imports.start(root.toString()));
            spinUntil(imports::startWaiting, "the second start waits for the first");
            letGo.countDown();

            Imports.Started one = first.get(20, TimeUnit.SECONDS);
            Imports.Started two = second.get(20, TimeUnit.SECONDS);
            assertThat(one.continued()).isFalse();
            assertThat(two.continued()).isTrue();
            assertThat(two.summary().id()).isEqualTo(one.summary().id());
        } finally {
            letGo.countDown();
            pool.shutdownNow();
        }
        assertThat(imports.awaitIdle(WAIT)).isTrue();
        assertThat(imports.list()).hasSize(1);
    }

    @Test
    void aContinueThatLosesToFinishQueuesNoWorkForTheFinishedImport() throws Exception {
        write("a.jpg", TestImages.jpeg(64, 48, 55));
        long id = started();
        String walkedAt = imports.store().run(id).lastWalkAt();
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch letGo = new CountDownLatch(1);
        imports.beforeContinue = () -> {
            inside.countDown();
            await(letGo);
        };
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Imports.Started> again = pool.submit(() -> imports.start(root.toString()));
            await(inside); // found the open import, has not taken its lock yet
            imports.beforeContinue = () -> { };
            imports.finish(id, false);
            letGo.countDown();

            Imports.Started started = again.get(20, TimeUnit.SECONDS);
            assertThat(started.continued()).as("a finished import is not continued").isFalse();
            assertThat(started.summary().id()).isNotEqualTo(id);
        } finally {
            letGo.countDown();
            pool.shutdownNow();
        }
        assertThat(imports.awaitIdle(WAIT)).isTrue();
        assertThat(imports.store().run(id).lastWalkAt()).as("the finished import was not walked").isEqualTo(walkedAt);
        assertThat(imports.summary(id).state()).isEqualTo("finished");
    }

    @Test
    void filingFromAFolderLeftWithNoPhotosIsRefusedEvenBeforeItIsWalkedAgain() throws Exception {
        write("a.jpg", TestImages.jpeg(64, 48, 56));
        long id = started();
        imports.assign(id, List.of(items(id).get("a.jpg").id()), "A5");
        Files.delete(root.resolve("a.jpg")); // as after a restart: no walk has seen it yet

        assertThatThrownBy(() -> imports.file(id)).isInstanceOfSatisfying(AtlasException.class,
                e -> assertThat(e.code()).isEqualTo("folder_missing"));
    }

    @Test
    void pausingWhenNothingFilesLeavesTheImportUnpaused() throws Exception {
        write("a.jpg", TestImages.jpeg(64, 48, 57));
        long id = started();

        assertThat(imports.pause(id).paused()).isFalse();
        assertThat(imports.store().run(id).paused()).isFalse();
    }

    @Test
    void anUnexpectedFilingErrorLeavesANotice() throws Exception {
        rebuild(processor, new ImportFiler(() -> library, photos, processor, thumbnails, TestBundles.CLOCK, ids -> { },
                ImportFiler.Opener.NIO, new PhotoIngest(processor, photos.maxBytes())::ingest, folder -> {
                    throw new IllegalStateException("disk error");
                }), Imports.CANCEL_WAIT);
        write("a.jpg", TestImages.jpeg(64, 48, 58));
        long id = started();
        imports.assign(id, List.of(items(id).get("a.jpg").id()), "A5");

        imports.file(id);
        assertThat(imports.awaitIdle(WAIT)).isTrue();

        assertThat(imports.summary(id).notice())
                .isEqualTo("Filing stopped because of an unexpected problem. Try continuing filing.");
        assertThat(imports.failedPhases()).as("still logged as a failure").isEqualTo(1);
        assertThat(imports.summary(id).phase()).isEqualTo("idle");
    }

    @Test
    void theLargeViewShowsACloudPlaceholderOnWindowsButNeverAPipeElsewhere() throws Exception {
        write("a.jpg", TestImages.jpeg(64, 48, 60));
        long id = started();
        long a = items(id).get("a.jpg").id();
        Files.delete(root.resolve("a.jpg"));
        try {
            // A FIFO's attributes are "other", as a OneDrive placeholder's are on Windows.
            Process made = new ProcessBuilder("mkfifo", root.resolve("a.jpg").toString()).start();
            assumeThat(made.waitFor()).as("mkfifo is available").isZero();
        } catch (IOException e) {
            assumeThat(false).as("mkfifo is available").isTrue();
        }
        // On Windows, mkfifo may make no file or an ordinary one; the test needs a real "other" entry.
        java.nio.file.Path fifo = root.resolve("a.jpg");
        assumeThat(java.nio.file.Files.exists(fifo, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                && java.nio.file.Files.readAttributes(fifo, java.nio.file.attribute.BasicFileAttributes.class,
                        java.nio.file.LinkOption.NOFOLLOW_LINKS).isOther())
                .as("mkfifo made a special file").isTrue();

        assertThatThrownBy(() -> imports.display(id, a)).isInstanceOfSatisfying(AtlasException.class,
                e -> assertThat(e.kind()).isEqualTo(AtlasException.Kind.NOT_FOUND));
        imports.windows = true;
        Imports.Display placeholder = imports.display(id, a);
        assertThat(placeholder.file()).isEqualTo(root.resolve("a.jpg"));
        assertThat(placeholder.contentType()).isEqualTo("image/jpeg");
    }

    @Test
    void filingAskedWhileReadingStillRunsAnswersFilingAndCountsTheFiling() throws Exception {
        write("a.jpg", TestImages.jpeg(64, 48, 61));
        long id = started();
        imports.assign(id, List.of(items(id).get("a.jpg").id()), "A5");
        assertThat(imports.summary(id).filings()).isZero();
        Gated gated = new Gated(1);
        rebuild(gated, null, Imports.CANCEL_WAIT);
        write("b.jpg", TestImages.jpeg(64, 48, 62));
        imports.start(root.toString());
        await(gated.entered); // reading b.jpg, which gives way only once its preview is made

        Imports.Summary filing = imports.file(id);

        assertThat(filing.phase()).as("the answer says filing, though reading has not given way yet").isEqualTo("filing");
        assertThat(filing.progress()).isEqualTo(new Imports.Progress(0, 0));
        assertThat(imports.summary(id).phase()).isEqualTo("filing");
        gated.release.countDown();
        assertThat(imports.awaitIdle(WAIT)).isTrue();
        assertThat(imports.summary(id).filings()).as("the UI tells a filing that ended between polls by this").isEqualTo(1);
        assertThat(imports.summary(id).counts().filed()).isEqualTo(1);
    }

    @Test
    void continuingWhenTheFolderIsMissingKeepsAStoppedReading() throws Exception {
        write("a.jpg", TestImages.jpeg(64, 48, 63));
        long id = started();
        imports.store().setActive(id, "reading", 1, 2);
        imports.startup();
        Files.delete(root.resolve("a.jpg"));
        Files.delete(root);

        Imports.Started again = imports.start(root.toString());

        assertThat(again.continued()).isTrue();
        assertThat(imports.awaitIdle(WAIT)).isTrue();
        assertThat(imports.summary(id).stopped()).as("nothing was continued").isEqualTo(
                new ImportStore.Stopped("reading", 1, 2, "2026-10-09T08:00:00Z"));
    }

    @Test
    void aStartOfTheNewFolderDuringARelocatesWalkWinsAndTheRelocateIsRefused() throws Exception {
        write("a.jpg", TestImages.jpeg(64, 48, 64));
        long id = started();
        Path moved = Files.createDirectories(dir.resolve("moved"));
        Files.copy(root.resolve("a.jpg"), moved.resolve("a.jpg"));
        AtomicLong other = new AtomicLong();
        imports.beforeRelocateApply = () -> other.set(imports.start(moved.toString()).summary().id()); // another tab

        assertThatThrownBy(() -> imports.relocate(id, moved.toString())).isInstanceOfSatisfying(AtlasException.class,
                e -> assertThat(e.code()).isEqualTo("folder_in_use"));

        imports.beforeRelocateApply = () -> { };
        assertThat(imports.awaitIdle(WAIT)).isTrue();
        assertThat(imports.summary(id).rootPath()).isEqualTo(root.toString());
        assertThat(imports.list()).extracting(Imports.Summary::id).containsExactlyInAnyOrder(id, other.get());
        assertThat(imports.summary(other.get()).state()).isEqualTo("open");
    }
}
