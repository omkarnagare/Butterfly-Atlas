package com.butterflyatlas.app.photos;

import com.butterflyatlas.app.AtlasException;
import com.butterflyatlas.app.imaging.ImageIoPhotoProcessor;
import com.butterflyatlas.app.library.Library;
import com.butterflyatlas.app.testing.TestBundles;
import com.butterflyatlas.app.testing.TestClock;
import com.butterflyatlas.app.testing.TestImages;
import com.butterflyatlas.app.testing.TestPlaces;
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
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SharpCopiesTest {

    private static final Duration WAIT = Duration.ofSeconds(10);

    @TempDir
    Path dir;

    private final TestClock clock = new TestClock();
    private final ImageIoPhotoProcessor processor = new ImageIoPhotoProcessor();
    private final AtomicInteger thumbnailsPending = new AtomicInteger();
    private final AtomicLong nanos = new AtomicLong();
    private Library library;
    private Path folder;
    private PhotoStore store;
    private SharpCopies sharp;

    @BeforeEach
    void setUp() {
        library = TestBundles.fixtureLibrary(dir.resolve("Atlas"));
        folder = dir.resolve("config").resolve("libraries").resolve("abc").resolve(SharpCopies.FOLDER);
        store = new PhotoStore(() -> library, processor, clock, id -> { });
        sharp = copies(SharpCopies.LIMIT);
    }

    @AfterEach
    void tearDown() {
        sharp.close();
    }

    private SharpCopies copies(long limit) {
        return new SharpCopies(() -> library, processor, lib -> folder, thumbnailsPending::get, limit, clock, nanos::get,
                Duration.ofMinutes(10), Duration.ofMillis(20));
    }

    private Photo add(byte[] bytes) {
        return store.add("A5", new ByteArrayInputStream(bytes), "image/jpeg", null, List.of()).photo();
    }

    /** Asks until the copy is there (as the UI does), or fails after {@link #WAIT}. */
    private static Path await(SharpCopies copies, long id) throws InterruptedException {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (System.nanoTime() < deadline) {
            SharpCopies.Lookup lookup = copies.lookup(id);
            if (lookup.file() != null) {
                return lookup.file();
            }
            assertThat(lookup.pending()).as("on its way").isTrue();
            Thread.sleep(20);
        }
        throw new AssertionError("the sharp copy of photo " + id + " never came");
    }

    private static BufferedImage read(Path file) throws IOException {
        return ImageIO.read(file.toFile());
    }

    private List<Path> files() throws IOException {
        if (!Files.isDirectory(folder)) {
            return List.of();
        }
        try (Stream<Path> list = Files.list(folder)) {
            return list.sorted().toList();
        }
    }

    @Test
    void aCopyIsPendingAtFirstThenServedOnceMade() throws Exception {
        Photo photo = add(TestImages.jpeg(2400, 1600));

        SharpCopies.Lookup first = sharp.lookup(photo.id());
        assertThat(first.file()).isNull();
        assertThat(first.pending()).isTrue();

        Path copy = await(sharp, photo.id());
        long bytes = Files.size(library.folder().resolve(photo.relPath()));
        String modified = Files.getLastModifiedTime(library.folder().resolve(photo.relPath())).toInstant().toString();
        assertThat(copy).isEqualTo(folder.resolve(Thumbnails.fileName(photo.id(), 960, modified, bytes)));
        assertThat(read(copy).getWidth()).isEqualTo(960);
        assertThat(read(copy).getHeight()).isEqualTo(640);
        assertThat(files()).containsExactly(copy).as("no part file left behind");
    }

    @Test
    void copiesAreUpright() throws Exception {
        Photo photo = add(TestImages.withOrientation(TestImages.jpeg(1600, 800), 6));

        BufferedImage copy = read(await(sharp, photo.id()));

        assertThat(copy.getWidth()).isEqualTo(480);
        assertThat(copy.getHeight()).isEqualTo(960);
    }

    @Test
    void aPhotoNoBiggerThanItsThumbnailHasNoSharpCopy() throws Exception {
        Photo photo = add(TestImages.jpeg(480, 300));

        SharpCopies.Lookup lookup = sharp.lookup(photo.id());

        assertThat(lookup.file()).isNull();
        assertThat(lookup.pending()).as("never made").isFalse();
        Thread.sleep(100);
        assertThat(files()).isEmpty();
    }

    @Test
    void aCopyIsNeverEnlarged() throws Exception {
        Photo photo = add(TestImages.jpeg(700, 350));

        BufferedImage copy = read(await(sharp, photo.id()));

        assertThat(copy.getWidth()).isEqualTo(700);
        assertThat(copy.getHeight()).isEqualTo(350);
    }

    @Test
    void anUnknownPhotoIsNotFound() {
        assertThatThrownBy(() -> sharp.lookup(999)).isInstanceOfSatisfying(AtlasException.class,
                e -> assertThat(e.kind()).isEqualTo(AtlasException.Kind.NOT_FOUND));
    }

    @Test
    void aChangedFileGetsANewCopyAndTheTidyRemovesTheOldOne() throws Exception {
        Photo photo = add(TestImages.jpeg(1200, 800, 1));
        Path old = await(sharp, photo.id());
        // As a rescan records a file edited outside the app: a new size and time.
        Path file = library.folder().resolve(photo.relPath());
        Files.write(file, TestImages.jpeg(1200, 800, 2));
        String modified = "2026-11-01T10:00:00Z";
        long size = Files.size(file);
        library.database().write(c -> {
            try (var ps = c.prepareStatement("UPDATE image SET modified_at = ?, file_size = ? WHERE id = ?")) {
                ps.setString(1, modified);
                ps.setLong(2, size);
                ps.setLong(3, photo.id());
                ps.executeUpdate();
            }
            return null;
        });

        Path fresh = await(sharp, photo.id());

        assertThat(fresh).isNotEqualTo(old).isEqualTo(folder.resolve(Thumbnails.fileName(photo.id(), 960, modified, size)));
        assertThat(sharp.cleanOrphans()).isEqualTo(1);
        assertThat(files()).containsExactly(fresh);
    }

    @Test
    void theTidyRemovesCopiesOfPhotosDeletedForGoodAndHalfWrittenOnesButKeepsRecentlyDeleted() throws Exception {
        Photo gone = add(TestImages.jpeg(1200, 800, 1));
        Photo binned = add(TestImages.jpeg(1200, 800, 2));
        Path goneCopy = await(sharp, gone.id());
        Path binnedCopy = await(sharp, binned.id());
        Files.writeString(folder.resolve("7-960-1-2.jpg" + Library.PART), "crashed");
        store.delete(List.of(gone.id(), binned.id()));
        store.purge(List.of(gone.id()));

        assertThat(sharp.cleanOrphans()).isEqualTo(2);

        assertThat(goneCopy).doesNotExist();
        assertThat(files()).containsExactly(binnedCopy);
    }

    @Test
    void startTidiesInTheBackground() throws Exception {
        Photo photo = add(TestImages.jpeg(1200, 800));
        Path copy = await(sharp, photo.id());
        store.delete(List.of(photo.id()));
        store.purge(List.of(photo.id()));

        sharp.start();

        long deadline = System.nanoTime() + WAIT.toNanos();
        while (Files.exists(copy) && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertThat(copy).doesNotExist();
    }

    @Test
    void overTheLimitTheLeastRecentlyUsedCopiesGoFirst() throws Exception {
        Photo a = add(TestImages.jpeg(1200, 800, 1));
        Photo b = add(TestImages.jpeg(1200, 800, 2));
        Photo c = add(TestImages.jpeg(1200, 800, 3));
        Path copyA = await(sharp, a.id());
        long each = Files.size(copyA);
        sharp.close();
        sharp = copies(each * 5 / 2); // room for two copies, not three
        clock.advance(Duration.ofMinutes(1));
        Path copyB = await(sharp, b.id());
        clock.advance(Duration.ofMinutes(1));
        assertThat(sharp.lookup(a.id()).file()).as("a used again: now newer than b").isEqualTo(copyA);
        clock.advance(Duration.ofMinutes(1));

        Path copyC = await(sharp, c.id());

        assertThat(copyB).doesNotExist();
        assertThat(files()).containsExactlyInAnyOrder(copyA, copyC);
    }

    @Test
    void noCopyIsMadeWhileThumbnailsArePending() throws Exception {
        thumbnailsPending.set(3);
        Photo photo = add(TestImages.jpeg(1200, 800));

        assertThat(sharp.lookup(photo.id()).pending()).isTrue();
        Thread.sleep(300);
        assertThat(files()).as("waits for the thumbnails").isEmpty();
        assertThat(sharp.lookup(photo.id()).pending()).isTrue();

        thumbnailsPending.set(0);

        assertThat(read(await(sharp, photo.id())).getWidth()).isEqualTo(960);
    }

    @Test
    void noCopyIsMadeWhileALibraryChangeHoldsTheBackgroundWork() throws Exception {
        library.workHold().hold();
        Photo photo = add(TestImages.jpeg(1200, 800));

        assertThat(sharp.lookup(photo.id()).pending()).isTrue();
        Thread.sleep(300);
        assertThat(files()).as("held").isEmpty();

        library.workHold().release();

        assertThat(await(sharp, photo.id())).exists();
    }

    @Test
    void aFailureIsRememberedForAWhileThenTriedAgain() throws Exception {
        Photo photo = add(TestImages.jpeg(1200, 800));
        Files.write(library.folder().resolve(photo.relPath()), new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0});

        assertThat(sharp.lookup(photo.id()).pending()).isTrue();
        long deadline = System.nanoTime() + WAIT.toNanos();
        SharpCopies.Lookup lookup = sharp.lookup(photo.id());
        while (lookup.pending() && System.nanoTime() < deadline) {
            Thread.sleep(20);
            lookup = sharp.lookup(photo.id());
        }
        assertThat(lookup.file()).isNull();
        assertThat(lookup.pending()).as("failed: not queued again").isFalse();
        assertThat(files()).isEmpty();

        nanos.addAndGet(Duration.ofMinutes(11).toNanos());

        assertThat(sharp.lookup(photo.id()).pending()).as("tried again").isTrue();
    }

    @Test
    void withNoFolderOnThisDeviceNoCopyIsMade() throws Exception {
        Photo photo = add(TestImages.jpeg(1200, 800));
        try (SharpCopies none = new SharpCopies(() -> library, processor, lib -> null, () -> 0)) {
            SharpCopies.Lookup lookup = none.lookup(photo.id());

            assertThat(lookup.file()).isNull();
            assertThat(lookup.pending()).isFalse();
            assertThat(none.cleanOrphans()).isZero();
        }
    }

    @Test
    void theFolderIsBesideThisDevicesWorkingCopyInTheConfigFolderNotInTheLibrary() throws IOException {
        Path config = dir.resolve("device-config");
        Path working = config.resolve("libraries").resolve("lib-1").resolve("atlas.db");
        Path data = dir.resolve("Shared");
        Files.createDirectories(working.getParent());
        Files.createDirectories(data);
        Library claimed = Library.open(data, working, TestBundles.fixture(), TestPlaces.bundles(), TestBundles.CLOCK);
        Path sharpFolder = SharpCopies.onThisDevice(config).apply(claimed);

        assertThat(sharpFolder).isEqualTo(config.resolve("libraries").resolve("lib-1").resolve("sharp"));
        assertThat(sharpFolder.toAbsolutePath().startsWith(data.toAbsolutePath())).isFalse();
        assertThat(SharpCopies.onThisDevice(config).apply(library)).as("a library opened in place").isNull();
    }
}
