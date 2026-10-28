package com.butterflyatlas.app.photos;

import com.butterflyatlas.app.imaging.ImageIoPhotoProcessor;
import com.butterflyatlas.app.library.Library;
import com.butterflyatlas.app.testing.TestBundles;
import com.butterflyatlas.app.testing.TestImages;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/** An import's preview becomes the photo's thumbnail without a second decode (design §4.4 step 3). */
class ThumbnailsAdoptTest {

    @TempDir
    Path dir;

    private final ImageIoPhotoProcessor processor = new ImageIoPhotoProcessor();
    private Library library;
    private Thumbnails thumbnails;
    private PhotoStore store;

    @BeforeEach
    void setUp() {
        library = TestBundles.fixtureLibrary(dir);
        thumbnails = new Thumbnails(() -> library, processor, TestBundles.CLOCK);
        store = new PhotoStore(() -> library, processor, TestBundles.CLOCK, id -> { }); // nothing queued on its own
    }

    @AfterEach
    void tearDown() {
        thumbnails.close();
    }

    private Photo add(int seed) {
        return store.add("A5", new ByteArrayInputStream(TestImages.jpeg(900, 600, seed)), "image/jpeg", null, List.of()).photo();
    }

    private Path preview(String name) throws Exception {
        return Files.write(Files.createDirectories(dir.resolve(".thumbs").resolve("import").resolve("1")).resolve(name),
                TestImages.jpeg(480, 320));
    }

    private String ledger(long id) {
        return library.database().read(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT status FROM image_processing WHERE image_id = ?"
                    + " AND consumer = 'thumbnail'")) {
                ps.setLong(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getString(1) : null;
                }
            }
        });
    }

    private Integer ledgerVersion(long id) {
        return library.database().read(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT version FROM image_processing WHERE image_id = ?"
                    + " AND consumer = 'thumbnail' AND status = 'done'")) {
                ps.setLong(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : null;
                }
            }
        });
    }

    /** A processor that delegates, with a hook before each thumbnail is made (and a count of them). */
    private PhotoProcessor hooked(AtomicInteger made, Runnable before) {
        return new PhotoProcessor() {
            @Override
            public PhotoFacts read(Path file) throws java.io.IOException {
                return processor.read(file);
            }

            @Override
            public void thumbnail(Path source, int orientation, int longSide, Path target) throws java.io.IOException {
                made.incrementAndGet();
                before.run();
                processor.thumbnail(source, orientation, longSide, target);
            }

            @Override
            public String dominantColor(Path image) throws java.io.IOException {
                return processor.dominantColor(image);
            }
        };
    }

    private static void waitFor(BooleanSupplier condition) throws InterruptedException {
        long until = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!condition.getAsBoolean()) {
            assertThat(System.nanoTime()).as("condition not met in time").isLessThan(until);
            Thread.sleep(10);
        }
    }

    @Test
    void thePreviewIsMovedIntoPlaceAndRecordedInOneGo() throws Exception {
        Photo first = add(1);
        Photo second = add(2);
        Path a = preview("1-aaaa.jpg");
        Path b = preview("2-bbbb.jpg");

        int adopted = thumbnails.adopt(Map.of(first.id(), a, second.id(), b));

        assertThat(adopted).isEqualTo(2);
        Thumbnails.Lookup lookup = thumbnails.lookup(first.id());
        assertThat(lookup.file()).isNotNull();
        assertThat(lookup.file().getParent()).isEqualTo(dir.resolve(".thumbs"));
        assertThat(lookup.file().getFileName().toString()).startsWith(first.id() + "-480-");
        assertThat(a).doesNotExist();
        assertThat(ledger(first.id())).isEqualTo("done");
        assertThat(ledger(second.id())).isEqualTo("done");
        assertThat(thumbnails.progress().pending()).as("nothing was decoded").isZero();
    }

    @Test
    void aPreviewThatCannotBeMovedIsMadeByTheQueueInstead() throws Exception {
        Photo photo = add(3);

        int adopted = thumbnails.adopt(Map.of(photo.id(), dir.resolve("no-such-preview.jpg")));

        assertThat(adopted).isZero();
        assertThat(thumbnails.await(photo.id(), Duration.ofSeconds(10))).isPresent();
        assertThat(ledger(photo.id())).isEqualTo("done");
    }

    @Test
    void aPhotoNoLongerLiveIsLeftAlone() throws Exception {
        Photo photo = add(4);
        store.delete(List.of(photo.id()));
        Path a = preview("4-dddd.jpg");

        assertThat(thumbnails.adopt(Map.of(photo.id(), a))).isZero();

        assertThat(a).as("the import tidies its own previews").exists();
        assertThat(ledger(photo.id())).isNull();
    }

    @Test
    void theThumbnailIsThePreviewItselfAndNothingIsDecoded() throws Exception {
        AtomicInteger made = new AtomicInteger();
        try (Thumbnails counting = new Thumbnails(() -> library, hooked(made, () -> { }), TestBundles.CLOCK)) {
            Photo photo = add(5);
            Path a = preview("5-eeee.jpg");
            byte[] bytes = Files.readAllBytes(a);

            assertThat(counting.adopt(Map.of(photo.id(), a))).isEqualTo(1);

            Path thumb = counting.lookup(photo.id()).file();
            assertThat(thumb).isNotNull();
            assertThat(java.util.Arrays.equals(Files.readAllBytes(thumb), bytes)).as("the thumbnail is the preview").isTrue();
            assertThat(made.get()).as("processor.thumbnail() calls").isZero();
        }
    }

    @Test
    void aRaisedVersionAdoptsUnderTheNewNameAndRecordsThatVersion() throws Exception {
        try (Thumbnails raised = new Thumbnails(() -> library, processor, TestBundles.CLOCK, 2, System::nanoTime,
                Duration.ofMinutes(10), Duration.ofSeconds(30))) {
            Photo photo = add(6);
            Path a = preview("6-ffff.jpg");

            assertThat(raised.adopt(Map.of(photo.id(), a))).isEqualTo(1);

            Path thumb = raised.lookup(photo.id()).file();
            assertThat(thumb).isNotNull();
            assertThat(thumb.getFileName().toString()).startsWith(photo.id() + "-480-").endsWith("-t2.jpg");
            assertThat(ledgerVersion(photo.id())).isEqualTo(2);
        }
    }

    @Test
    void aPhotoChangedBeforeTheCommitKeepsTheFileButGetsNoDoneRow() throws Exception {
        Photo photo = add(7);
        Path a = preview("7-gggg.jpg");
        // Read after adopt has read the photo and before it commits: as a rescan does for an edited file.
        Map<Long, Path> previews = new HashMap<>(Map.of(photo.id(), a)) {
            @Override
            public Path get(Object key) {
                library.database().write(c -> {
                    try (PreparedStatement ps = c.prepareStatement(
                            "UPDATE image SET modified_at = '2030-01-01T00:00:00Z', file_size = file_size + 1 WHERE id = ?")) {
                        ps.setLong(1, photo.id());
                        ps.executeUpdate();
                    }
                    return null;
                });
                return super.get(key);
            }
        };

        assertThat(thumbnails.adopt(previews)).as("files moved").isEqualTo(1);

        assertThat(a).doesNotExist();
        try (var files = Files.list(dir.resolve(".thumbs"))) {
            assertThat(files.map(f -> f.getFileName().toString())).anyMatch(n -> n.startsWith(photo.id() + "-480-"));
        }
        assertThat(ledger(photo.id())).isNull();
    }

    @Test
    void aWorkerAlreadyMakingThePhotoKeepsTheAdoptedThumbnailAndRecordsItDone() throws Exception {
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger made = new AtomicInteger();
        PhotoProcessor held = hooked(made, () -> {
            inside.countDown();
            try {
                go.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        try (Thumbnails t = new Thumbnails(() -> library, held, TestBundles.CLOCK)) {
            Photo photo = add(8);
            Path a = preview("8-hhhh.jpg");
            byte[] bytes = Files.readAllBytes(a);
            t.request(photo.id());
            assertThat(inside.await(10, TimeUnit.SECONDS)).isTrue();

            assertThat(t.adopt(Map.of(photo.id(), a))).isEqualTo(1);
            go.countDown();

            waitFor(() -> t.progress().equals(new Thumbnails.Progress(0, 0)));
            Path thumb = t.lookup(photo.id()).file();
            assertThat(thumb).isNotNull();
            assertThat(java.util.Arrays.equals(Files.readAllBytes(thumb), bytes)).as("the adopted file is kept").isTrue();
            assertThat(ledger(photo.id())).isEqualTo("done");
            try (var files = Files.list(dir.resolve(".thumbs"))) {
                assertThat(files.map(f -> f.getFileName().toString())).noneMatch(n -> n.endsWith(".part"));
            }
        }
    }
}
