package com.butterflyatlas.app.photos;

import com.butterflyatlas.app.imaging.ImageIoPhotoProcessor;
import com.butterflyatlas.app.library.Library;
import com.butterflyatlas.app.photos.consumers.Dimensions;
import com.butterflyatlas.app.testing.TestBundles;
import com.butterflyatlas.app.testing.TestClock;
import com.butterflyatlas.app.testing.TestImages;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StartupWorkTest {

    @TempDir
    Path dir;

    @Test
    void rescansCatchesUpPurgesOldDeletionsAndTidiesUp() throws Exception {
        TestClock clock = new TestClock();
        Library library = TestBundles.fixtureLibrary(dir);
        ImageIoPhotoProcessor processor = new ImageIoPhotoProcessor();
        PhotoQueries queries = new PhotoQueries(() -> library, clock);
        try (Thumbnails thumbnails = new Thumbnails(() -> library, processor)) {
            PhotoStore store = new PhotoStore(() -> library, processor, clock, id -> { });
            Photo old = store.add("A5", new ByteArrayInputStream(TestImages.jpeg(30, 20, 1)), "image/jpeg", null, List.of()).photo();
            store.delete(List.of(old.id()));
            Photo upgraded = store.add("A5", new ByteArrayInputStream(TestImages.jpeg(30, 20, 3)), "image/jpeg", null, List.of()).photo();
            library.database().write(c -> {
                try (var ps = c.prepareStatement("DELETE FROM image_meta")) {
                    ps.executeUpdate(); // as after the upgrade to 3b-1
                }
                return null;
            });
            clock.advance(Duration.ofDays(31));
            Photo recent = store.add("A5", new ByteArrayInputStream(TestImages.jpeg(30, 20, 4)), "image/jpeg", null, List.of()).photo();
            store.delete(List.of(recent.id()));
            String wantedRel = library.database().read(c -> {
                try (var ps = c.prepareStatement("SELECT deleted_rel_path FROM image WHERE id = " + recent.id());
                     var rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getString(1);
                }
            });
            Path wantedFile = library.folder().resolve(wantedRel);
            Files.setLastModifiedTime(wantedFile, java.nio.file.attribute.FileTime.from(java.time.Instant.now().minusSeconds(3600)));
            Path copied = library.folder().resolve("images/Copied [A6]/mine.jpg");
            Files.createDirectories(copied.getParent());
            Files.write(copied, TestImages.jpeg(30, 20, 2));
            Path orphan = Files.createDirectories(library.folder().resolve(".thumbs")).resolve("999-480-1.jpg");
            Files.writeString(orphan, "old");

            Path crashed = Files.createDirectories(library.folder().resolve(".incoming")).resolve("4d2a.part");
            Files.writeString(crashed, "half an upload");
            Files.setLastModifiedTime(crashed, java.nio.file.attribute.FileTime.from(java.time.Instant.now().minusSeconds(3600)));
            Path leftover = library.folder().resolve(".deleted/99_lost.jpg");
            Files.writeString(leftover, "left by a failed purge");
            Files.setLastModifiedTime(leftover, java.nio.file.attribute.FileTime.from(java.time.Instant.now().minusSeconds(3600)));
            Rescan rescan = new Rescan(() -> library, processor, clock, thumbnails::request, Clock.systemUTC(), Duration.ZERO);

            ConsumerRunner processing = new ConsumerRunner(() -> library, new PhotoIngest(processor), List.of(new Dimensions()),
                    thumbnails, clock);

            new StartupWork(rescan, store, processing, thumbnails).start().join(10_000);

            assertThat(queries.photos("A6")).extracting(Photo::fileName).containsExactly("mine.jpg");
                        assertThat(orphan).doesNotExist();
            assertThat(crashed).doesNotExist();
            Photo mine = queries.photos("A6").get(0);
            assertThat(thumbnails.await(mine.id(), Duration.ofSeconds(10))).as("queued by the rescan").isPresent();
            assertThat(rescan.progress().added()).isEqualTo(1);
            boolean snapshot = library.database().read(c -> {
                try (var ps = c.prepareStatement("SELECT image_id FROM image_meta WHERE image_id = " + upgraded.id());
                     var rs = ps.executeQuery()) {
                    return rs.next();
                }
            });
            assertThat(snapshot).as("details read for the photo without a snapshot").isTrue();
            assertThat(thumbnails.await(upgraded.id(), Duration.ofSeconds(10))).as("queued by the catch-up").isPresent();
            assertThat(leftover).as("no purge is on record for it").exists();
            assertThat(wantedFile).as("its photo is in Recently deleted").exists();
            assertThat(store.deletedCount()).isEqualTo(1);
        }
    }

    @Test
    void importsMayStartOnceTheRescanAndSweepAreOverAndBeforeThumbnailsAreTidied() throws Exception {
        Library library = TestBundles.fixtureLibrary(dir);
        ImageIoPhotoProcessor processor = new ImageIoPhotoProcessor();
        PhotoQueries queries = new PhotoQueries(() -> library, TestBundles.CLOCK);
        try (Thumbnails thumbnails = new Thumbnails(() -> library, processor)) {
            PhotoStore store = new PhotoStore(() -> library, processor, TestBundles.CLOCK, id -> { });
            Path copied = library.folder().resolve("images/Copied [A6]/mine.jpg");
            Files.createDirectories(copied.getParent());
            Files.write(copied, TestImages.jpeg(30, 20, 5));
            Path orphan = Files.createDirectories(library.folder().resolve(".thumbs")).resolve("999-480-1.jpg");
            Files.writeString(orphan, "old");
            Rescan rescan = new Rescan(() -> library, processor, TestBundles.CLOCK, id -> { }, Clock.systemUTC(), Duration.ZERO);
            ConsumerRunner processing = new ConsumerRunner(() -> library, new PhotoIngest(processor), List.of(new Dimensions()),
                    thumbnails, TestBundles.CLOCK);
            List<String> seen = new java.util.ArrayList<>();

            new StartupWork(rescan, store, processing, thumbnails, () -> {
                seen.add("photos " + queries.photos("A6").size());
                seen.add("orphan " + Files.exists(orphan));
            }).start().join(10_000);

            assertThat(seen).containsExactly("photos 1", "orphan true");
            assertThat(orphan).doesNotExist();
        }
    }

    @Test
    void aTaxonomyUpdateContinuedAtStartupIsWaitedForBeforeTheRescan() throws Exception {
        Library library = TestBundles.fixtureLibrary(dir);
        ImageIoPhotoProcessor processor = new ImageIoPhotoProcessor();
        try (Thumbnails thumbnails = new Thumbnails(() -> library, processor)) {
            PhotoStore store = new PhotoStore(() -> library, processor, TestBundles.CLOCK, id -> { });
            Rescan rescan = new Rescan(() -> library, processor, TestBundles.CLOCK, id -> { }, Clock.systemUTC(), Duration.ZERO);
            ConsumerRunner processing = new ConsumerRunner(() -> library, new PhotoIngest(processor), List.of(new Dimensions()),
                    thumbnails, TestBundles.CLOCK);
            List<String> seen = new java.util.concurrent.CopyOnWriteArrayList<>();

            new StartupWork(rescan, store, processing, thumbnails, () -> seen.add("update " + (rescan.progress() == null)),
                    () -> seen.add("settled " + (rescan.progress() != null))).start().join(10_000);

            assertThat(seen).as("the update first, the rescan after it").containsExactly("update true", "settled true");
        }
    }

    @Test
    void importsMayStartEvenWhenTheStepsBeforeFail() throws Exception {
        java.util.function.Supplier<Library> none = () -> {
            throw new IllegalStateException("no library");
        };
        ImageIoPhotoProcessor processor = new ImageIoPhotoProcessor();
        try (Thumbnails thumbnails = new Thumbnails(none, processor)) {
            boolean[] settled = {false};

            new StartupWork(new Rescan(none, processor, TestBundles.CLOCK, id -> { }), new PhotoStore(none, processor,
                    TestBundles.CLOCK, id -> { }), new ConsumerRunner(none, new PhotoIngest(processor), List.of(new Dimensions()),
                    thumbnails, TestBundles.CLOCK), thumbnails, () -> settled[0] = true).run();

            assertThat(settled[0]).isTrue();
        }
    }
}
