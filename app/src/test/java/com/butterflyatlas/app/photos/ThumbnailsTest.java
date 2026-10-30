package com.butterflyatlas.app.photos;

import com.butterflyatlas.app.AtlasException;
import com.butterflyatlas.app.imaging.ImageIoPhotoProcessor;
import com.butterflyatlas.app.library.Library;
import com.butterflyatlas.app.testing.TestBundles;
import com.butterflyatlas.app.testing.TestClock;
import com.butterflyatlas.app.testing.TestImages;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.Semaphore;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ThumbnailsTest {

    private static final Duration WAIT = Duration.ofSeconds(10);

    @TempDir
    Path dir;

    private final TestClock clock = new TestClock();
    private final ImageIoPhotoProcessor processor = new ImageIoPhotoProcessor();
    private Library library;
    private Thumbnails thumbnails;
    private PhotoStore store;

    @BeforeEach
    void setUp() {
        library = TestBundles.fixtureLibrary(dir);
        thumbnails = new Thumbnails(() -> library, processor);
        store = new PhotoStore(() -> library, processor, clock, thumbnails::request);
    }

    @AfterEach
    void tearDown() {
        thumbnails.close();
    }

    private Photo add(byte[] bytes) {
        return store.add("A5", new ByteArrayInputStream(bytes), "image/jpeg", null, List.of()).photo();
    }

    private String modifiedAt(Photo photo) throws IOException {
        return Files.getLastModifiedTime(library.folder().resolve(photo.relPath())).toInstant().toString();
    }

    private static BufferedImage read(Path file) throws IOException {
        return ImageIO.read(file.toFile());
    }

    @Test
    void whileALibraryChangeHoldsTheBackgroundWorkNoThumbnailIsMadeUntilItLetsGo() throws Exception {
        library.workHold().hold();
        Photo photo = add(TestImages.jpeg(240, 160));

        assertThat(thumbnails.await(photo.id(), Duration.ofMillis(300))).as("held").isEmpty();
        assertThat(thumbnails.progress().pending()).isEqualTo(1);

        library.workHold().release();

        assertThat(thumbnails.await(photo.id(), WAIT)).isPresent();
        assertThat(thumbnails.progress().pending()).isZero();
    }

    @Test
    void addingAPhotoQueuesItsThumbnail() throws Exception {
        Photo photo = add(TestImages.jpeg(2400, 1600));

        Path thumb = thumbnails.await(photo.id(), WAIT).orElseThrow();

        long millis = Instant.parse(modifiedAt(photo)).toEpochMilli();
        long bytes = Files.size(library.folder().resolve(photo.relPath()));
        assertThat(thumb).isEqualTo(library.folder().resolve(".thumbs").resolve(photo.id() + "-480-" + millis + "-" + bytes + ".jpg"));
        assertThat(read(thumb).getWidth()).isEqualTo(480);
        assertThat(read(thumb).getHeight()).isEqualTo(320);
        assertThat(thumbnails.await(photo.id(), Duration.ZERO)).contains(thumb);
    }

    @Test
    void aPhotoWithoutAThumbnailGetsOneOnFirstRequest() throws Exception {
        long id = new PhotoStore(() -> library, processor, clock, x -> { })
                .add("A5", new ByteArrayInputStream(TestImages.jpeg(600, 300)), "image/jpeg", null, List.of()).photo().id();
        assertThat(library.folder().resolve(".thumbs")).doesNotExist();

        assertThat(read(thumbnails.await(id, WAIT).orElseThrow()).getWidth()).isEqualTo(480);
    }

    @Test
    void thumbnailsAreUpright() throws Exception {
        Photo photo = add(TestImages.withOrientation(TestImages.jpeg(400, 200), 6));

        BufferedImage thumb = read(thumbnails.await(photo.id(), WAIT).orElseThrow());

        assertThat(thumb.getWidth()).isEqualTo(200);
        assertThat(thumb.getHeight()).isEqualTo(400);
    }

    @Test
    void photosInRecentlyDeletedStillHaveThumbnails() throws Exception {
        Photo photo = add(TestImages.jpeg(100, 50));
        store.delete(List.of(photo.id()));

        assertThat(thumbnails.await(photo.id(), WAIT)).isPresent();
        assertThatThrownBy(() -> thumbnails.await(999, WAIT)).isInstanceOfSatisfying(AtlasException.class,
                e -> assertThat(e.kind()).isEqualTo(AtlasException.Kind.NOT_FOUND));
    }

    @Test
    void aDeletedPhotoWhoseFileIsMissingIsNotFound() throws Exception {
        Photo photo = add(TestImages.jpeg(100, 50));
        store.delete(List.of(photo.id()));
        library.database().write(c -> {
            try (var ps = c.prepareStatement("UPDATE image SET deleted_rel_path = NULL")) {
                ps.executeUpdate();
            }
            return null;
        });

        assertThatThrownBy(() -> thumbnails.await(photo.id(), WAIT)).isInstanceOfSatisfying(AtlasException.class,
                e -> assertThat(e.kind()).isEqualTo(AtlasException.Kind.NOT_FOUND));
        assertThatThrownBy(() -> thumbnails.display(photo.id())).isInstanceOfSatisfying(AtlasException.class,
                e -> assertThat(e.kind()).isEqualTo(AtlasException.Kind.NOT_FOUND));
        thumbnails.request(photo.id());
    }

    @Test
    void aBrokenFileGetsAPlaceholderInstead() throws Exception {
        Photo photo = new PhotoStore(() -> library, processor, clock, x -> { })
                .add("A5", new ByteArrayInputStream(TestImages.jpeg(100, 50)), "image/jpeg", null, List.of()).photo();
        Files.write(library.folder().resolve(photo.relPath()), new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0});

        assertThat(thumbnails.await(photo.id(), WAIT)).isEmpty();
        assertThat(thumbnails.await(photo.id(), WAIT)).as("not tried again").isEmpty();

        BufferedImage placeholder = ImageIO.read(new ByteArrayInputStream(thumbnails.placeholder(photo.id())));
        assertThat(placeholder.getWidth()).isEqualTo(1);
        assertThat(placeholder.getHeight()).isEqualTo(1);
        assertThat(placeholder.getRGB(0, 0) & 0xFFFFFF).isEqualTo(0xD9D9D9);
    }

    @Test
    void theWaitIsBoundedAndTheThumbnailFollowsLater() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        PhotoProcessor slow = new PhotoProcessor() {
            @Override
            public PhotoFacts read(Path file) throws IOException {
                return processor.read(file);
            }

            @Override
            public void thumbnail(Path source, int orientation, int longSide, Path target) throws IOException {
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                processor.thumbnail(source, orientation, longSide, target);
            }

            @Override
            public String dominantColor(Path image) {
                return "#336699";
            }
        };
        try (Thumbnails waiting = new Thumbnails(() -> library, slow)) {
            long id = new PhotoStore(() -> library, slow, clock, x -> { })
                    .add("A5", new ByteArrayInputStream(TestImages.jpeg(100, 50)), "image/jpeg", null, List.of()).photo().id();

            assertThat(waiting.await(id, Duration.ofMillis(50))).isEmpty();
            release.countDown();
            assertThat(waiting.await(id, WAIT)).isPresent();
            BufferedImage placeholder = ImageIO.read(new ByteArrayInputStream(waiting.placeholder(id)));
            assertThat(placeholder.getRGB(0, 0) & 0xFFFFFF).isEqualTo(0x336699);
        }
    }

    @Test
    void previewProgressCountsPendingAndDoneUntilTheQueueEmpties() throws Exception {
        Semaphore permits = new Semaphore(0);
        PhotoProcessor gated = new PhotoProcessor() {
            @Override
            public PhotoFacts read(Path file) throws IOException {
                return processor.read(file);
            }

            @Override
            public void thumbnail(Path source, int orientation, int longSide, Path target) throws IOException {
                try {
                    permits.tryAcquire(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                processor.thumbnail(source, orientation, longSide, target);
            }

            @Override
            public String dominantColor(Path image) throws IOException {
                return processor.dominantColor(image);
            }
        };
        try (Thumbnails gatedThumbnails = new Thumbnails(() -> library, gated)) {
            PhotoStore quiet = new PhotoStore(() -> library, gated, clock, x -> { });
            long a = quiet.add("A5", new ByteArrayInputStream(TestImages.jpeg(100, 50, 1)), "image/jpeg", null, List.of()).photo().id();
            long b = quiet.add("A5", new ByteArrayInputStream(TestImages.jpeg(100, 50, 2)), "image/jpeg", null, List.of()).photo().id();
            assertThat(gatedThumbnails.progress()).isEqualTo(new Thumbnails.Progress(0, 0));

            gatedThumbnails.request(a);
            gatedThumbnails.request(b);
            gatedThumbnails.request(a);
            assertThat(gatedThumbnails.progress()).as("queued once each").isEqualTo(new Thumbnails.Progress(2, 0));

            permits.release();
            waitFor(() -> gatedThumbnails.progress().equals(new Thumbnails.Progress(1, 1)));
            permits.release();
            assertThat(gatedThumbnails.await(a, WAIT)).isPresent();
            assertThat(gatedThumbnails.await(b, WAIT)).isPresent();
            // The file is visible a moment before the worker books it as finished, so wait for the counts.
            waitFor(() -> gatedThumbnails.progress().equals(new Thumbnails.Progress(0, 0)));
        }
    }

    private static void waitFor(BooleanSupplier condition) throws InterruptedException {
        waitFor(condition, WAIT);
    }

    private static void waitFor(BooleanSupplier condition, Duration budget) throws InterruptedException {
        long until = System.nanoTime() + budget.toNanos();
        while (!condition.getAsBoolean()) {
            assertThat(System.nanoTime()).as("condition not met in time").isLessThan(until);
            Thread.sleep(10);
        }
    }

    @Test
    void displayCopiesAreLargeUprightAndMadeOnce() throws Exception {
        Photo tiff = store.add("A5", new ByteArrayInputStream(TestImages.tiff(3000, 1500)), "image/tiff", null, List.of()).photo();
        Photo turned = add(TestImages.withOrientation(TestImages.jpeg(400, 200), 6));

        Path copy = thumbnails.display(tiff.id());

        long millis = Instant.parse(modifiedAt(tiff)).toEpochMilli();
        long bytes = Files.size(library.folder().resolve(tiff.relPath()));
        assertThat(copy).isEqualTo(library.folder().resolve(".thumbs").resolve(tiff.id() + "-2560-" + millis + "-" + bytes + ".jpg"));
        assertThat(read(copy).getWidth()).isEqualTo(2560);
        assertThat(read(copy).getHeight()).isEqualTo(1280);
        long made = Files.getLastModifiedTime(copy).toMillis();
        assertThat(thumbnails.display(tiff.id())).isEqualTo(copy);
        assertThat(Files.getLastModifiedTime(copy).toMillis()).as("not made again").isEqualTo(made);
        BufferedImage upright = read(thumbnails.display(turned.id()));
        assertThat(upright.getWidth()).as("never enlarged").isEqualTo(200);
        assertThat(upright.getHeight()).isEqualTo(400);

        Photo broken = new PhotoStore(() -> library, processor, clock, x -> { })
                .add("A5", new ByteArrayInputStream(TestImages.jpeg(100, 50, 7)), "image/jpeg", null, List.of()).photo();
        Files.write(library.folder().resolve(broken.relPath()), new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0});
        assertThatThrownBy(() -> thumbnails.display(broken.id())).isInstanceOfSatisfying(AtlasException.class,
                e -> assertThat(e.kind()).isEqualTo(AtlasException.Kind.UNSUPPORTED));
    }

    @Test
    void cleaningRemovesCachedFilesNoPhotoNeeds() throws Exception {
        Photo keep = add(TestImages.jpeg(100, 50, 1));
        Photo purged = add(TestImages.jpeg(100, 50, 2));
        Photo parked = add(TestImages.jpeg(100, 50, 3));
        Path kept = thumbnails.await(keep.id(), WAIT).orElseThrow();
        Path keptDisplay = thumbnails.display(keep.id());
        thumbnails.await(purged.id(), WAIT).orElseThrow();
        thumbnails.display(purged.id());
        Path parkedThumb = thumbnails.await(parked.id(), WAIT).orElseThrow();
        Path parkedDisplay = thumbnails.display(parked.id());
        store.delete(List.of(purged.id(), parked.id()));
        store.purge(List.of(purged.id()));
        Path folder = library.folder().resolve(".thumbs");
        Files.writeString(folder.resolve(keep.id() + "-480-1.jpg"), "an older version");
        Files.writeString(folder.resolve(keep.id() + "-2560-1.jpg"), "an older display copy");
        Files.writeString(folder.resolve("77-480-5.jpg.part"), "half written");

        assertThat(thumbnails.cleanOrphans()).isEqualTo(5);

        try (var files = Files.list(folder)) {
            assertThat(files.toList()).as("Recently deleted keeps its thumbnail and display copy")
                    .containsExactlyInAnyOrder(kept, keptDisplay, parkedThumb, parkedDisplay);
        }
    }

    /** A processor that delegates, with a hook before each thumbnail is made. */
    private PhotoProcessor hooked(ThumbnailHook hook) {
        return new PhotoProcessor() {
            @Override
            public PhotoFacts read(Path file) throws IOException {
                return processor.read(file);
            }

            @Override
            public void thumbnail(Path source, int orientation, int longSide, Path target) throws IOException {
                hook.before(longSide);
                processor.thumbnail(source, orientation, longSide, target);
            }

            @Override
            public String dominantColor(Path image) throws IOException {
                return processor.dominantColor(image);
            }
        };
    }

    private interface ThumbnailHook {
        void before(int longSide) throws IOException;
    }

    private Photo addQuietly(PhotoProcessor with, int seed) {
        return new PhotoStore(() -> library, with, clock, x -> { })
                .add("A5", new ByteArrayInputStream(TestImages.jpeg(100, 50, seed)), "image/jpeg", null, List.of()).photo();
    }

    @Test
    void anErrorWhileMakingADisplayCopyReleasesWhoeverWaits() throws Exception {
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch go = new CountDownLatch(1);
        PhotoProcessor failing = hooked(size -> {
            inside.countDown();
            try {
                go.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            throw new OutOfMemoryError("a huge TIFF");
        });
        try (Thumbnails t = new Thumbnails(() -> library, failing)) {
            long id = addQuietly(failing, 1).id();
            CompletableFuture<Throwable> maker = CompletableFuture.supplyAsync(() -> thrown(() -> t.display(id)));
            assertThat(inside.await(10, TimeUnit.SECONDS)).isTrue();
            CompletableFuture<Throwable> waiter = CompletableFuture.supplyAsync(() -> thrown(() -> t.display(id)));
            go.countDown();

            assertThat(maker.get(10, TimeUnit.SECONDS)).isInstanceOf(OutOfMemoryError.class);
            assertThat(waiter.get(10, TimeUnit.SECONDS)).isInstanceOfSatisfying(AtlasException.class,
                    e -> assertThat(e.kind()).isEqualTo(AtlasException.Kind.UNSUPPORTED));
        }
    }

    private static Throwable thrown(Runnable work) {
        try {
            work.run();
            return null;
        } catch (Throwable e) {
            return e;
        }
    }

    @Test
    void aWaiterGivesUpOnASlowDisplayCopy() throws Exception {
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        PhotoProcessor slow = hooked(size -> {
            inside.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        try (Thumbnails t = new Thumbnails(() -> library, slow, System::nanoTime, Duration.ofMinutes(10), Duration.ofMillis(100))) {
            long id = addQuietly(slow, 1).id();
            CompletableFuture<Path> maker = CompletableFuture.supplyAsync(() -> t.display(id));
            assertThat(inside.await(10, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() -> t.display(id)).isInstanceOfSatisfying(AtlasException.class,
                    e -> {
                        assertThat(e).hasMessage("This photo is taking too long to open. Try again.");
                        assertThat(e.kind()).isEqualTo(AtlasException.Kind.BUSY);
                    });
            release.countDown();
            assertThat(maker.get(10, TimeUnit.SECONDS)).exists();
        }
    }

    @Test
    void onlyTwoDisplayCopiesAreMadeAtOnce() throws Exception {
        AtomicInteger now = new AtomicInteger();
        AtomicInteger most = new AtomicInteger();
        PhotoProcessor counting = hooked(size -> {
            most.accumulateAndGet(now.incrementAndGet(), Math::max);
            try {
                Thread.sleep(80);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            now.decrementAndGet();
        });
        try (Thumbnails t = new Thumbnails(() -> library, counting)) {
            List<Long> ids = List.of(addQuietly(counting, 1).id(), addQuietly(counting, 2).id(),
                    addQuietly(counting, 3).id(), addQuietly(counting, 4).id());
            List<CompletableFuture<Path>> all = ids.stream().map(id -> CompletableFuture.supplyAsync(() -> t.display(id))).toList();
            for (CompletableFuture<Path> f : all) {
                f.get(20, TimeUnit.SECONDS);
            }
        }
        assertThat(most.get()).isBetween(1, 2);
    }

    @Test
    void aFailureIsRememberedForAWhileThenTriedAgain() throws Exception {
        AtomicLong nanos = new AtomicLong();
        try (Thumbnails t = new Thumbnails(() -> library, processor, nanos::get, Duration.ofMinutes(10), Duration.ofSeconds(30))) {
            Photo photo = addQuietly(processor, 1);
            Path file = library.folder().resolve(photo.relPath());
            byte[] good = Files.readAllBytes(file);
            Files.write(file, new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0});
            assertThat(t.await(photo.id(), WAIT)).isEmpty();

            Files.write(file, good);
            assertThat(t.await(photo.id(), WAIT)).as("remembered").isEmpty();
            t.request(photo.id());
            assertThat(t.progress()).as("request() honours it too").isEqualTo(new Thumbnails.Progress(0, 0));

            nanos.addAndGet(Duration.ofMinutes(11).toNanos());
            assertThat(t.await(photo.id(), WAIT)).as("expired: tried again").isPresent();
        }
    }

    private List<String> thumbnailLedger() {
        return library.database().read(c -> {
            List<String> rows = new java.util.ArrayList<>();
            try (var ps = c.prepareStatement("SELECT image_id || ' ' || status FROM image_processing"
                    + " WHERE consumer = 'thumbnail' ORDER BY image_id"); var rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(rs.getString(1));
                }
            }
            return rows;
        });
    }

    @Test
    void aThumbnailOfContentReplacedWhileItWasMadeIsNotRecordedDone() throws Exception {
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch go = new CountDownLatch(1);
        PhotoProcessor held = hooked(size -> {
            inside.countDown();
            try {
                go.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        try (Thumbnails t = new Thumbnails(() -> library, held)) {
            Photo photo = addQuietly(processor, 1);
            CompletableFuture<java.util.Optional<Path>> old = CompletableFuture.supplyAsync(() -> t.await(photo.id(), WAIT));
            assertThat(inside.await(10, TimeUnit.SECONDS)).isTrue();
            // As a rescan does for an edited file: new version, and every consumer forgets the photo.
            library.database().write(c -> {
                try (var ps = c.prepareStatement("UPDATE image SET modified_at = '2030-01-01T00:00:00Z', file_size = file_size + 1"
                        + " WHERE id = ?")) {
                    ps.setLong(1, photo.id());
                    ps.executeUpdate();
                }
                new ProcessingLedger(clock).forget(c, photo.id());
                return null;
            });
            go.countDown();

            assertThat(old.get(10, TimeUnit.SECONDS)).as("the old content's thumbnail was made").isPresent();
            waitFor(() -> t.progress().equals(new Thumbnails.Progress(0, 0)));
            assertThat(thumbnailLedger()).as("no done row for content the photo no longer has").isEmpty();
            assertThat(t.catchUp()).as("still pending for catch-up").isEqualTo(1);
        }
    }

    @Test
    void madeAndFailedThumbnailsAreRecordedInTheLedger() throws Exception {
        Photo good = addQuietly(processor, 1);
        Photo broken = addQuietly(processor, 2);
        Files.write(library.folder().resolve(broken.relPath()), new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0});

        assertThat(thumbnails.await(good.id(), WAIT)).isPresent();
        assertThat(thumbnails.await(broken.id(), WAIT)).isEmpty();

        // The row follows the file into place: wait for the worker to finish, not just for the file.
        waitFor(() -> thumbnails.progress().equals(new Thumbnails.Progress(0, 0)));
        assertThat(thumbnailLedger()).containsExactly(good.id() + " done", broken.id() + " failed");
    }

    @Test
    void awaitReturnsOnlyOnceTheWorkerHasBookedTheThumbnail() throws Exception {
        // The worker's first library lookup is the background-work hold (a library change), its second record(), right
        // after the file is moved into place: hold it there.
        CountDownLatch recording = new CountDownLatch(1);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger lookups = new AtomicInteger();
        Supplier<Library> gatedLibrary = () -> {
            if (Thread.currentThread().getName().startsWith("atlas-thumbnails-") && go.getCount() > 0
                    && lookups.incrementAndGet() >= 2) {
                recording.countDown();
                try {
                    go.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return library;
        };
        try (Thumbnails t = new Thumbnails(gatedLibrary, processor)) {
            Photo photo = addQuietly(processor, 1);
            t.request(photo.id());
            assertThat(recording.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(library.folder().resolve(".thumbs")).isNotEmptyDirectory(); // the file is visible already

            CompletableFuture<String> seen = new CompletableFuture<>();
            Thread waiter = new Thread(() -> {
                try {
                    boolean present = t.await(photo.id(), WAIT).isPresent();
                    seen.complete(present + " " + t.progress() + " " + thumbnailLedger());
                } catch (Throwable e) {
                    seen.completeExceptionally(e);
                }
            });
            waiter.start();
            // Release the worker only once the waiter has answered or is waiting for it.
            waitFor(() -> seen.isDone() || waiter.getState() == Thread.State.TIMED_WAITING
                    || waiter.getState() == Thread.State.WAITING);
            go.countDown();

            assertThat(seen.get(10, TimeUnit.SECONDS))
                    .isEqualTo("true " + new Thumbnails.Progress(0, 0) + " " + List.of(photo.id() + " done"));
        }
    }

    @Test
    void aPhotoDeletedBeforeItsThumbnailIsMadeStillGetsOne() throws Exception {
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch go = new CountDownLatch(1);
        PhotoProcessor held = hooked(size -> {
            inside.countDown();
            try {
                go.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        try (Thumbnails t = new Thumbnails(() -> library, held)) {
            Photo photo = addQuietly(processor, 1);
            t.request(photo.id()); // the job holds the photo's live path
            assertThat(inside.await(10, TimeUnit.SECONDS)).isTrue();
            store.delete(List.of(photo.id()));
            go.countDown();

            assertThat(t.await(photo.id(), WAIT)).isPresent();
            assertThat(t.lookup(photo.id()).file()).isNotNull();
            assertThat(thumbnailLedger()).containsExactly(photo.id() + " done");
        }
    }

    @Test
    void aPhotoWhoseFileVanishesBeforeItsThumbnailIsMadeFails() throws Exception {
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch go = new CountDownLatch(1);
        PhotoProcessor held = hooked(size -> {
            inside.countDown();
            try {
                go.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        try (Thumbnails t = new Thumbnails(() -> library, held)) {
            Photo photo = addQuietly(processor, 1);
            t.request(photo.id());
            assertThat(inside.await(10, TimeUnit.SECONDS)).isTrue();
            Files.delete(library.folder().resolve(photo.relPath()));
            go.countDown();

            assertThat(t.await(photo.id(), WAIT)).isEmpty();
            assertThat(t.lookup(photo.id())).isEqualTo(new Thumbnails.Lookup(null, false));
            assertThat(thumbnailLedger()).containsExactly(photo.id() + " failed");
        }
    }

    @Test
    void queueingFromManyThreadsWhileWorkersFinishNeverHangs() throws Exception {
        // Workers finishing (pending.remove) race with requests queueing (pending.computeIfAbsent) across thousands of
        // keys, so map bins and resizes are shared: neither may hold a lock the other waits for.
        PhotoProcessor fast = new PhotoProcessor() {
            @Override
            public PhotoFacts read(Path file) throws IOException {
                return processor.read(file);
            }

            @Override
            public void thumbnail(Path source, int orientation, int longSide, Path target) throws IOException {
                Files.write(target, new byte[] {1});
            }

            @Override
            public String dominantColor(Path image) {
                return "#000000";
            }
        };
        int many = 2000;
        Photo first = addQuietly(processor, 1);
        library.database().write(c -> {
            try (var ps = c.prepareStatement("WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM n WHERE i < ?)"
                    + " INSERT INTO image (taxon_id, rel_path, file_name, file_size, modified_at, sha256, added_at, source)"
                    + " SELECT taxon_id, rel_path || '-' || i, file_name, file_size, modified_at, sha256, added_at, source"
                    + " FROM image, n WHERE id = ?")) {
                ps.setInt(1, many - 1);
                ps.setLong(2, first.id());
                ps.executeUpdate();
            }
            return null;
        });
        long last = first.id() + many - 1;
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(5, r -> {
            Thread thread = new Thread(r);
            thread.setDaemon(true); // a hang must fail this test, not keep the JVM alive
            return thread;
        });
        try (Thumbnails t = new Thumbnails(() -> library, fast)) {
            List<CompletableFuture<Void>> callers = new java.util.ArrayList<>();
            callers.add(CompletableFuture.runAsync(t::catchUp, pool));
            for (int k = 0; k < 4; k++) {
                int offset = k;
                callers.add(CompletableFuture.runAsync(() -> {
                    for (long id = last - offset; id >= first.id(); id -= 4) {
                        switch ((int) (id % 3)) {
                            case 0 -> t.request(id);
                            case 1 -> t.lookup(id);
                            default -> t.await(id, Duration.ZERO);
                        }
                        t.progress();
                    }
                }, pool));
            }

            // The hang detector: callers that do not return within 60 s are stuck, not slow.
            CompletableFuture.allOf(callers.toArray(CompletableFuture[]::new)).get(60, TimeUnit.SECONDS);
            // Throughput: the workers making every thumbnail on a slow machine gets a generous budget of its own.
            Duration throughput = Duration.ofMinutes(2);
            assertThat(t.await(last, throughput)).isPresent();
            waitFor(() -> t.progress().equals(new Thumbnails.Progress(0, 0)), throughput);
            try (var files = Files.list(library.folder().resolve(".thumbs"))) {
                assertThat(files.filter(f -> f.toString().endsWith(".jpg")).count()).isEqualTo(many);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void catchUpDoesNotCountAPhotoAlreadyQueuedAsNewlyQueued() throws Exception {
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch go = new CountDownLatch(1);
        PhotoProcessor held = hooked(size -> {
            inside.countDown();
            try {
                go.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        try (Thumbnails t = new Thumbnails(() -> library, held)) {
            Photo inFlight = addQuietly(processor, 1);
            Photo missing = addQuietly(processor, 2);
            t.request(inFlight.id());
            assertThat(inside.await(10, TimeUnit.SECONDS)).isTrue();
            try {
                assertThat(t.catchUp()).as("only the photo not queued yet").isEqualTo(1);
            } finally {
                go.countDown();
            }
            assertThat(t.await(missing.id(), WAIT)).isPresent();
            assertThat(t.await(inFlight.id(), WAIT)).isPresent();
        }
    }

    @Test
    void catchUpMarksThumbnailsAlreadyThereAndQueuesTheRest() throws Exception {
        AtomicInteger made = new AtomicInteger();
        PhotoProcessor counting = hooked(size -> made.incrementAndGet());
        try (Thumbnails t = new Thumbnails(() -> library, counting)) {
            Photo there = addQuietly(counting, 1);
            Photo missing = addQuietly(counting, 2);
            Photo deleted = addQuietly(counting, 3);
            t.await(there.id(), WAIT).orElseThrow();
            store.delete(List.of(deleted.id()));
            library.database().write(c -> {
                try (var ps = c.prepareStatement("DELETE FROM image_processing WHERE consumer = 'thumbnail'")) {
                    ps.executeUpdate();
                }
                return null;
            });
            made.set(0);

            assertThat(t.catchUp()).as("queued").isEqualTo(1);
            assertThat(t.await(missing.id(), WAIT)).isPresent();

            waitFor(() -> t.progress().equals(new Thumbnails.Progress(0, 0))); // the row follows the file
            assertThat(made).as("the thumbnail already there is not made again").hasValue(1);
            assertThat(thumbnailLedger()).containsExactly(there.id() + " done", missing.id() + " done");
            assertThat(t.catchUp()).isZero();
        }
    }

    @Test
    void aLookupNeverWaitsAndSaysWhetherTheThumbnailIsOnItsWay() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        PhotoProcessor slow = hooked(size -> {
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        try (Thumbnails t = new Thumbnails(() -> library, slow)) {
            Photo photo = addQuietly(slow, 1);
            Photo broken = addQuietly(slow, 2);
            Files.write(library.folder().resolve(broken.relPath()), new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0});

            long started = System.nanoTime();
            assertThat(t.lookup(photo.id())).isEqualTo(new Thumbnails.Lookup(null, true));
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
            release.countDown();
            Path made = t.await(photo.id(), WAIT).orElseThrow();
            assertThat(t.lookup(photo.id())).isEqualTo(new Thumbnails.Lookup(made, false));

            assertThat(t.await(broken.id(), WAIT)).isEmpty();
            assertThat(t.lookup(broken.id())).as("failed: no longer on its way").isEqualTo(new Thumbnails.Lookup(null, false));
        }
    }

    @Test
    void aLookedUpPhotoJumpsTheQueue() throws Exception {
        List<Long> order = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        Semaphore permits = new Semaphore(0);
        List<Photo> photos = new java.util.ArrayList<>();
        PhotoProcessor gated = new PhotoProcessor() {
            @Override
            public PhotoFacts read(Path file) throws IOException {
                return processor.read(file);
            }

            @Override
            public void thumbnail(Path source, int orientation, int longSide, Path target) throws IOException {
                photos.stream().filter(p -> source.endsWith(p.fileName())).findFirst().ifPresent(p -> order.add(p.id()));
                try {
                    permits.tryAcquire(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                processor.thumbnail(source, orientation, longSide, target);
            }

            @Override
            public String dominantColor(Path image) throws IOException {
                return processor.dominantColor(image);
            }
        };
        try (Thumbnails t = new Thumbnails(() -> library, gated)) {
            for (int i = 1; i <= 5; i++) {
                photos.add(addQuietly(gated, i));
            }
            t.request(photos.get(0).id());
            t.request(photos.get(1).id());
            waitFor(() -> order.size() == 2); // both workers busy
            t.request(photos.get(2).id());
            t.request(photos.get(3).id());
            t.request(photos.get(4).id());

            assertThat(t.lookup(photos.get(4).id()).pending()).isTrue();
            permits.release(); // one worker finishes and takes the next in line
            waitFor(() -> order.size() == 3);

            assertThat(order.get(2)).as("the photo on screen goes first").isEqualTo(photos.get(4).id());
            permits.release(10);
            for (Photo photo : photos) {
                assertThat(t.await(photo.id(), WAIT)).isPresent();
            }
            waitFor(() -> t.progress().equals(new Thumbnails.Progress(0, 0)));
        }
    }

    /** The thumbnail ledger rows as {@code <id> <status> v<version> <done_at>}. */
    private List<String> thumbnailRows() {
        return library.database().read(c -> {
            List<String> rows = new java.util.ArrayList<>();
            try (var ps = c.prepareStatement("SELECT image_id || ' ' || status || ' v' || version || ' ' || done_at"
                    + " FROM image_processing WHERE consumer = 'thumbnail' ORDER BY image_id"); var rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(rs.getString(1));
                }
            }
            return rows;
        });
    }

    @Test
    void theVersionIsInTheNameAndCacheKeyOnceRaised() {
        String at = "2026-10-11T09:00:00Z";
        long millis = Instant.parse(at).toEpochMilli();
        assertThat(Thumbnails.cacheKey(at, 345, 1)).as("as in 3a").isEqualTo(millis + "-345");
        assertThat(Thumbnails.cacheKey(at, 345, 2)).isEqualTo(millis + "-345-t2");
        assertThat(Thumbnails.cacheKey(at, 345)).isEqualTo(Thumbnails.cacheKey(at, 345, Thumbnails.VERSION));
        assertThat(PhotoQueries.cacheKey(at, 345)).as("the photo's v").isEqualTo(Thumbnails.cacheKey(at, 345));
        assertThat(Thumbnails.fileName(12, Thumbnails.SIZE, at, 345, 1)).isEqualTo("12-480-" + millis + "-345.jpg");
        assertThat(Thumbnails.fileName(12, Thumbnails.SIZE, at, 345, 2)).isEqualTo("12-480-" + millis + "-345-t2.jpg");
        assertThat(Thumbnails.fileName(12, Thumbnails.DISPLAY_SIZE, at, 345, 2)).as("display copies keep their name")
                .isEqualTo("12-2560-" + millis + "-345.jpg");
    }

    @Test
    void raisingTheVersionMakesThumbnailsAgain() throws Exception {
        Photo old = addQuietly(processor, 1);
        Photo failedOne = addQuietly(processor, 2);
        Path oldThumb = thumbnails.await(old.id(), WAIT).orElseThrow();
        Path failedThumb = thumbnails.await(failedOne.id(), WAIT).orElseThrow();
        int raised = Thumbnails.VERSION + 1;
        library.database().write(c -> {
            // As if the thumbnail failed at the new version once (an older one is on disk).
            try (var ps = c.prepareStatement("UPDATE image_processing SET status = 'failed', error = 'x', version = ?"
                    + " WHERE consumer = 'thumbnail' AND image_id = ?")) {
                ps.setInt(1, raised);
                ps.setLong(2, failedOne.id());
                ps.executeUpdate();
            }
            return null;
        });
        AtomicInteger made = new AtomicInteger();
        PhotoProcessor counting = hooked(size -> made.incrementAndGet());
        TestClock later = new TestClock();
        later.advance(Duration.ofDays(1));

        try (Thumbnails t = new Thumbnails(() -> library, counting, later, raised, System::nanoTime,
                Thumbnails.RETRY_FAILED_AFTER, Thumbnails.DISPLAY_WAIT)) {
            assertThat(t.catchUp()).as("both queued").isEqualTo(2);
            Path newThumb = t.await(old.id(), WAIT).orElseThrow();
            Path newFailedThumb = t.await(failedOne.id(), WAIT).orElseThrow();
            waitFor(() -> t.progress().equals(new Thumbnails.Progress(0, 0)));

            assertThat(made).hasValue(2);
            assertThat(newThumb.getFileName().toString()).isEqualTo(Thumbnails.fileName(old.id(), Thumbnails.SIZE,
                    modifiedAt(old), Files.size(library.folder().resolve(old.relPath())), raised)).endsWith("-t" + raised + ".jpg");
            assertThat(newFailedThumb.getFileName().toString()).endsWith("-t" + raised + ".jpg");
            assertThat(read(newThumb)).isNotNull();
            assertThat(thumbnailRows()).containsExactly(old.id() + " done v" + raised + " " + later.instant(),
                    failedOne.id() + " done v" + raised + " " + later.instant());
            assertThat(t.catchUp()).isZero();

            assertThat(t.cleanOrphans()).as("the old-version thumbnails").isEqualTo(2);
            assertThat(oldThumb).doesNotExist();
            assertThat(failedThumb).doesNotExist();
            assertThat(newThumb).exists();
            assertThat(newFailedThumb).exists();
        }
    }

    @Test
    void theLedgerRowIsWrittenOnceTheThumbnailIsInPlace() throws Exception {
        Photo photo = addQuietly(processor, 1);
        Path thumb = library.folder().resolve(Thumbnails.FOLDER).resolve(Thumbnails.fileName(photo.id(), modifiedAt(photo),
                Files.size(library.folder().resolve(photo.relPath()))));
        // The ledger write is the last thing that asks for the library: note whether the file was there by then.
        List<Boolean> thereAtEachCall = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        try (Thumbnails t = new Thumbnails(() -> {
            thereAtEachCall.add(Files.exists(thumb));
            return library;
        }, processor)) {
            t.request(photo.id());
            waitFor(() -> t.progress().equals(new Thumbnails.Progress(0, 0)));

            assertThat(thumbnailLedger()).containsExactly(photo.id() + " done");
            assertThat(thereAtEachCall.get(thereAtEachCall.size() - 1)).as("file moved into place before the row").isTrue();
        }
    }

    @Test
    void catchUpLeavesAloneAPhotoThatFailedRecently() throws Exception {
        Photo broken = addQuietly(processor, 1);
        Files.write(library.folder().resolve(broken.relPath()), new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0});
        assertThat(thumbnails.await(broken.id(), WAIT)).isEmpty();
        assertThat(thumbnailLedger()).containsExactly(broken.id() + " failed");

        assertThat(thumbnails.catchUp()).isZero();
        assertThat(thumbnails.progress()).isEqualTo(new Thumbnails.Progress(0, 0));
    }

    @Test
    void aThumbnailFoundAlreadyMadeIsRecorded() throws Exception {
        CountDownLatch busy = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger made = new AtomicInteger();
        PhotoProcessor gated = hooked(size -> {
            made.incrementAndGet();
            busy.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        try (Thumbnails t = new Thumbnails(() -> library, gated)) {
            Photo a = addQuietly(gated, 1);
            Photo b = addQuietly(gated, 2);
            Photo c = addQuietly(gated, 3);
            t.request(a.id());
            t.request(b.id());
            assertThat(busy.await(10, TimeUnit.SECONDS)).as("both workers busy").isTrue();
            t.request(c.id()); // waits in the queue
            Path target = library.folder().resolve(Thumbnails.FOLDER).resolve(Thumbnails.fileName(c.id(), modifiedAt(c),
                    Files.size(library.folder().resolve(c.relPath()))));
            Files.write(target, TestImages.jpeg(48, 24)); // made meanwhile

            release.countDown();
            waitFor(() -> t.progress().equals(new Thumbnails.Progress(0, 0)));

            assertThat(made).as("c is not made again").hasValue(2);
            assertThat(thumbnailLedger()).containsExactly(a.id() + " done", b.id() + " done", c.id() + " done");
        }
    }

    @Test
    void requestsAfterCloseDoNotCountAsQueued() throws Exception {
        Photo photo = addQuietly(processor, 1);
        Thumbnails closed = new Thumbnails(() -> library, processor);
        closed.close();

        closed.request(photo.id());
        assertThat(closed.await(photo.id(), Duration.ofMillis(10))).isEmpty();

        assertThat(closed.progress()).isEqualTo(new Thumbnails.Progress(0, 0));
    }
}
