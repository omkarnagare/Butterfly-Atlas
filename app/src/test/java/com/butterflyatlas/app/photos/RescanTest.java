package com.butterflyatlas.app.photos;

import com.butterflyatlas.app.imaging.ImageIoPhotoProcessor;
import com.butterflyatlas.app.library.Library;
import com.butterflyatlas.app.taxa.TaxonService;
import com.butterflyatlas.app.taxa.TaxonViews.Count;
import com.butterflyatlas.app.photos.consumers.Dimensions;
import com.butterflyatlas.app.photos.consumers.Place;
import com.butterflyatlas.app.testing.TestBundles;
import com.butterflyatlas.app.testing.TestClock;
import com.butterflyatlas.app.testing.TestExif;
import com.butterflyatlas.app.testing.TestImages;
import com.butterflyatlas.app.testing.TestRaw;
import com.butterflyatlas.app.testing.TestPlaces;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class RescanTest {

    private static final String PLAIN = "images/Nymphalidae/Danainae/Danaini/Danaus/Danaus_chrysippus_[A5]/";

    @TempDir
    Path dir;

    private final TestClock clock = new TestClock();
    private final ImageIoPhotoProcessor processor = new ImageIoPhotoProcessor();
    private final List<Long> found = new ArrayList<>();
    private Library library;
    private Rescan rescan;
    private PhotoQueries queries;

    @BeforeEach
    void setUp() {
        library = TestBundles.fixtureLibrary(dir);
        rescan = quick(processor);
        queries = new PhotoQueries(() -> library, clock);
    }

    /** A rescan that does not wait for files to settle (tests write and rescan at once). */
    private Rescan quick(PhotoProcessor with) {
        return new Rescan(() -> library, with, clock, found::add, Clock.systemUTC(), Duration.ZERO);
    }

    private Path put(String relPath, byte[] bytes) throws Exception {
        Path file = library.folder().resolve(relPath);
        Files.createDirectories(file.getParent());
        return Files.write(file, bytes);
    }

    private List<String> strings(String sql) {
        return library.database().read(c -> {
            List<String> values = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    values.add(rs.getString(1));
                }
            }
            return values;
        });
    }

    private static RuntimeException busy() {
        return new com.butterflyatlas.app.db.DatabaseException("write failed", new org.sqlite.SQLiteException(
                "[SQLITE_BUSY] The database file is locked (database is locked)", org.sqlite.SQLiteErrorCode.SQLITE_BUSY));
    }

    @Test
    void aPhotoTheDatabaseIsBusyForIsTriedAgainAndOneItStaysBusyForIsListedWithTheReason() throws Exception {
        put(PLAIN + "IMG_1.JPG", TestImages.jpeg(60, 40, 1));
        put(PLAIN + "IMG_2.JPG", TestImages.jpeg(60, 40, 2));
        java.util.Map<String, Integer> tries = new java.util.concurrent.ConcurrentHashMap<>();
        rescan.writes().beforeInsert = photos -> {
            if (photos.size() > 1) {
                throw busy(); // the batch, then each photo alone
            }
            String name = photos.get(0).file().file().getFileName().toString();
            int n = tries.merge(name, 1, Integer::sum);
            if (name.equals("IMG_1.JPG") && n <= 2 || name.equals("IMG_2.JPG")) {
                throw busy();
            }
        };

        assertThat(rescan.run().added()).isEqualTo(1);

        assertThat(queries.photos("A5")).extracting(Photo::fileName).containsExactly("IMG_1.JPG");
        assertThat(tries).containsEntry("IMG_1.JPG", 3).containsEntry("IMG_2.JPG", 4);
        assertThat(rescan.left()).singleElement().satisfies(left -> {
            assertThat(left.reason()).isEqualTo("Photos that couldn't be added while another window was saving changes");
            assertThat(left.next()).isEqualTo("They're added the next time Butterfly Atlas opens.");
            assertThat(left.count()).isEqualTo(1);
            assertThat(left.examples()).containsExactly("Danaus_chrysippus_[A5]/IMG_2.JPG");
        });
    }

    @Test
    void registersPhotosCopiedIntoASpeciesFolder() throws Exception {
        byte[] jpeg = TestImages.jpeg(60, 40);
        put(PLAIN + "IMG_7.JPG", jpeg);

        assertThat(rescan.run()).isEqualTo(new Rescan.Result(1, 0));

        Photo photo = queries.photos("A5").get(0);
        assertThat(photo.fileName()).isEqualTo("IMG_7.JPG");
        assertThat(photo.relPath()).isEqualTo(PLAIN + "IMG_7.JPG");
        assertThat(photo.tags()).isEmpty();
        assertThat(photo.width()).isEqualTo(60);
        assertThat(strings("SELECT source || '|' || coalesce(number, '-') || '|' || file_size FROM image"))
                .containsExactly("rescan|-|" + jpeg.length);
        assertThat(new TaxonService(library::database).taxon("A5").species()).isEqualTo(new Count(1, 1));
        assertThat(rescan.running()).isFalse();
        assertThat(found).as("its thumbnail is queued").containsExactly(photo.id());
    }

    @Test
    void registersRawPhotosByTheirNamesAndThoseWithoutAPreviewAsUnreadable() throws Exception {
        byte[] nef = TestRaw.tiff(new TestRaw.Ifd().shortValue(0x0112, 8).jpeg(TestRaw.preview()));
        put(PLAIN + "DSC_0042.NEF", nef);
        byte[] bare = TestRaw.tiff(new TestRaw.Ifd().shortValue(0x0112, 1)); // no preview
        put(PLAIN + "DSC_0043.NEF", bare);

        assertThat(rescan.run().added()).isEqualTo(2);

        List<Photo> photos = queries.photos("A5").stream().sorted(java.util.Comparator.comparing(Photo::fileName)).toList();
        Photo photo = photos.get(0);
        assertThat(photo.fileName()).as("hand-copied files keep their names").isEqualTo("DSC_0042.NEF");
        assertThat(photo.width()).isEqualTo(768);
        assertThat(photo.height()).isEqualTo(1024);
        assertThat(photo.unreadable()).isFalse();
        assertThat(strings("SELECT file_type || '|' || orientation FROM image WHERE file_name = 'DSC_0042.NEF'"))
                .containsExactly("raw|8");
        assertThat(strings("SELECT json FROM image_meta")).singleElement().asString().contains("\"rawPreview\":{");

        Photo noPreview = photos.get(1);
        assertThat(noPreview.fileName()).isEqualTo("DSC_0043.NEF");
        assertThat(noPreview.fileType()).isEqualTo("raw");
        assertThat(noPreview.width()).isNull();
        assertThat(noPreview.height()).isNull();
        assertThat(noPreview.rawPreview()).isNull();
        assertThat(noPreview.unreadable()).as("the gallery says it can't be shown").isTrue();
        assertThat(strings("SELECT file_size || '|' || source FROM image WHERE file_name = 'DSC_0043.NEF'"))
                .containsExactly(bare.length + "|rescan");
        assertThat(found).as("both get their thumbnails tried").hasSize(2);
        assertThat(rescan.run()).as("registered once").isEqualTo(new Rescan.Result(0, 0));
    }

    @Test
    void aRenamedFolderIsRecognisedByItsId() throws Exception {
        put("images/My trip/Plain tigers at Goa [A5]/tiger.png", TestImages.png(30, 20));

        assertThat(rescan.run().added()).isEqualTo(1);
        assertThat(queries.photos("A5").get(0).relPath()).isEqualTo("images/My trip/Plain tigers at Goa [A5]/tiger.png");
    }

    @Test
    void otherFilesAreLeftAlone() throws Exception {
        byte[] jpeg = TestImages.jpeg(30, 20);
        put("images/stray.jpg", jpeg);
        put("images/Nymphalidae/not-a-species.jpg", jpeg);
        put(PLAIN + "notes.txt", "field notes".getBytes());
        put(PLAIN + "pretend.jpg", "not really a photo".getBytes());
        put(PLAIN + "sub/deeper.jpg", jpeg);
        put(PLAIN + ".hidden.jpg", jpeg);
        put("images/Gone [A999]/x.jpg", jpeg);
        put("images/Nymphalidae/Danainae/Danaini/Danaus [A4]/genus.jpg", jpeg);

        assertThat(rescan.run()).isEqualTo(new Rescan.Result(0, 0));
        assertThat(strings("SELECT COUNT(*) FROM image")).containsExactly("0");
    }

    @Test
    void aSecondRunFindsNothingNew() throws Exception {
        put(PLAIN + "a.jpg", TestImages.jpeg(30, 20));
        PhotoStore store = new PhotoStore(() -> library, processor, clock, id -> { });
        store.add("A5", new ByteArrayInputStream(TestImages.jpeg(30, 20, 5)), "image/jpeg", null, List.of());

        assertThat(rescan.run()).isEqualTo(new Rescan.Result(1, 0));
        assertThat(rescan.run()).isEqualTo(new Rescan.Result(0, 0));
        assertThat(queries.photos("A5")).hasSize(2);
    }

    @Test
    void recordsWhoseFileIsGoneAreRemoved() throws Exception {
        PhotoStore store = new PhotoStore(() -> library, processor, clock, id -> { });
        Photo gone = store.add("A5", new ByteArrayInputStream(TestImages.jpeg(30, 20, 1)), "image/jpeg", null,
                List.of("male")).photo();
        Photo parked = store.add("A5", new ByteArrayInputStream(TestImages.jpeg(30, 20, 2)), "image/jpeg", null,
                List.of()).photo();
        store.setCover("A5", gone.id());
        store.delete(List.of(parked.id()));
        Files.delete(library.folder().resolve(gone.relPath()));

        assertThat(rescan.run()).isEqualTo(new Rescan.Result(0, 1));

        assertThat(queries.photos("A5")).isEmpty();
        assertThat(store.deletedCount()).as("Recently deleted is not touched").isEqualTo(1);
        assertThat(strings("SELECT coalesce(cover_image_id, '-') FROM taxon WHERE id = 'A5'")).containsExactly("-");
        assertThat(strings("SELECT COUNT(*) FROM image_tag")).containsExactly("0");
        assertThat(strings("SELECT kind FROM change_log ORDER BY id DESC LIMIT 1")).containsExactly("photo.missing");
    }

    @Test
    void aFileAnUndoCouldNotPutBackIsMovedBackFromRecentlyDeleted() throws Exception {
        PhotoStore store = new PhotoStore(() -> library, processor, clock, id -> { });
        Photo photo = store.add("A5", new ByteArrayInputStream(TestImages.jpeg(30, 20, 1)), "image/jpeg", null,
                List.of()).photo();
        Path live = library.folder().resolve(photo.relPath());
        Path parked = library.folder().resolve(".deleted/" + photo.id() + "_" + photo.fileName());
        Files.createDirectories(parked.getParent());
        Files.move(live, parked); // as after a delete whose undo failed
        Files.setLastModifiedTime(parked, FileTime.from(Instant.now().minusSeconds(3600)));

        assertThat(rescan.run()).isEqualTo(new Rescan.Result(0, 0));

        assertThat(live).exists();
        assertThat(parked).doesNotExist();
        assertThat(queries.photos("A5")).extracting(Photo::id).containsExactly(photo.id());
        assertThat(strings("SELECT kind FROM change_log ORDER BY id DESC LIMIT 1")).containsExactly("photo.restore");
        assertThat(strings("SELECT payload_json FROM change_log ORDER BY id DESC LIMIT 1").get(0)).contains("\"recovered\":true");
    }

    @Test
    void anUndoFileIsNotMovedOverAFileThatIsNowThere() throws Exception {
        PhotoStore store = new PhotoStore(() -> library, processor, clock, id -> { });
        Photo photo = store.add("A5", new ByteArrayInputStream(TestImages.jpeg(30, 20, 1)), "image/jpeg", null,
                List.of()).photo();
        Path live = library.folder().resolve(photo.relPath());
        Path parked = library.folder().resolve(".deleted/" + photo.id() + "_" + photo.fileName());
        Files.createDirectories(parked.getParent());
        Files.move(live, parked);
        Files.createDirectory(live); // something that is not a file now sits at the path, so the record looks missing

        rescan.run();

        assertThat(parked).as("kept").exists();
        assertThat(live).isDirectory();
    }

    @Test
    void aLibraryWithoutAnImagesFolderIsFine() {
        assertThat(library.folder().resolve("images")).doesNotExist();

        assertThat(rescan.run()).isEqualTo(new Rescan.Result(0, 0));
    }

    @Test
    void progressIsNullBeforeTheFirstRunThenKeepsTheLastSummary() throws Exception {
        assertThat(rescan.progress()).isNull();
        PhotoStore store = new PhotoStore(() -> library, processor, clock, id -> { });
        store.add("A5", new ByteArrayInputStream(TestImages.jpeg(30, 20, 1)), "image/jpeg", null, List.of());
        Photo gone = store.add("A5", new ByteArrayInputStream(TestImages.jpeg(30, 20, 2)), "image/jpeg", null, List.of()).photo();
        Files.delete(library.folder().resolve(gone.relPath()));
        put(PLAIN + "a.jpg", TestImages.jpeg(30, 20, 3));
        put("images/Elsewhere [A6]/b.png", TestImages.png(30, 20));
        put(PLAIN + "notes.txt", "not counted".getBytes());

        rescan.run();

        assertThat(rescan.progress()).isEqualTo(new Rescan.Progress(3, 3, 2, 1, 0, "2026-10-11T09:00:00Z"));
        assertThat(found).hasSize(2);
        clock.advance(Duration.ofHours(1));
        rescan.run();
        assertThat(rescan.progress()).isEqualTo(new Rescan.Progress(3, 3, 0, 0, 0, "2026-10-11T10:00:00Z"));
    }

    @Test
    void progressIsVisibleWhileARunIsGoing() throws Exception {
        CountDownLatch inspecting = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        PhotoProcessor slow = new PhotoProcessor() {
            @Override
            public PhotoFacts read(Path file) throws IOException {
                inspecting.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return processor.read(file);
            }

            @Override
            public void thumbnail(Path source, int orientation, int longSide, Path target) throws IOException {
                processor.thumbnail(source, orientation, longSide, target);
            }

            @Override
            public String dominantColor(Path image) throws IOException {
                return processor.dominantColor(image);
            }
        };
        put(PLAIN + "a.jpg", TestImages.jpeg(30, 20, 1));
        put(PLAIN + "b.jpg", TestImages.jpeg(30, 20, 2));
        Rescan slowRescan = quick(slow);
        Thread run = new Thread(slowRescan::run);
        run.start();

        assertThat(inspecting.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(slowRescan.running()).isTrue();
        assertThat(slowRescan.progress()).isEqualTo(new Rescan.Progress(0, 2, 0, 0, 0, null));
        release.countDown();
        run.join(10_000);

        assertThat(slowRescan.running()).isFalse();
        assertThat(slowRescan.progress()).isEqualTo(new Rescan.Progress(2, 2, 2, 0, 0, "2026-10-11T09:00:00Z"));
    }

    private PhotoStore store() {
        return new PhotoStore(() -> library, processor, clock, id -> { });
    }

    private void exec(String sql) {
        library.database().write(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.executeUpdate();
            }
            return null;
        });
    }

    private static void deleteTree(Path root) throws IOException {
        try (var paths = Files.walk(root)) {
            for (Path p : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }

    @Test
    void aMissingImagesFolderRemovesNothing() throws Exception {
        Photo photo = store().add("A5", new ByteArrayInputStream(TestImages.jpeg(30, 20)), "image/jpeg", null, List.of()).photo();
        deleteTree(library.folder().resolve("images")); // an unplugged drive looks like this

        assertThat(rescan.run()).isEqualTo(new Rescan.Result(0, 0));

        assertThat(queries.photos("A5")).extracting(Photo::id).containsExactly(photo.id());
        assertThat(rescan.progress().finishedAt()).isNotNull();
    }

    @Test
    void aMassDisappearanceRemovesNothingButAFewDo() throws Exception {
        PhotoStore store = store();
        List<Photo> photos = new ArrayList<>();
        for (int i = 1; i <= 22; i++) {
            photos.add(store.add("A5", new ByteArrayInputStream(TestImages.jpeg(30, 20, i)), "image/jpeg", null, List.of()).photo());
        }
        for (int i = 0; i < 21; i++) {
            Files.delete(library.folder().resolve(photos.get(i).relPath()));
        }

        assertThat(rescan.run()).as("21 of 22 and at least 20: something is wrong, not the user").isEqualTo(new Rescan.Result(0, 0));
        assertThat(queries.photos("A5")).hasSize(22);

        // A few missing files are removed as usual (the guard needs 20 and more than half).
        for (int i = 0; i < 21; i++) {
            Files.write(library.folder().resolve(photos.get(i).relPath()), TestImages.jpeg(30, 20, i + 1));
        }
        Files.delete(library.folder().resolve(photos.get(0).relPath()));
        assertThat(rescan.run()).isEqualTo(new Rescan.Result(0, 1));
        assertThat(queries.photos("A5")).hasSize(21);
    }

    @Test
    void anUnreadableFolderDoesNotAbortOrRemoveAnything() throws Exception {
        Photo photo = store().add("A5", new ByteArrayInputStream(TestImages.jpeg(30, 20)), "image/jpeg", null, List.of()).photo();
        put("images/Elsewhere [A6]/b.png", TestImages.png(30, 20));
        Path locked = library.folder().resolve(photo.relPath()).getParent();
        try {
            Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("---------"));
        } catch (UnsupportedOperationException e) {
            assumeTrue(false, "no POSIX permissions here");
        }
        try {
            assumeTrue(!Files.isReadable(locked), "running as a user who can read anything");

            assertThat(rescan.run()).as("the readable folder is still done; nothing is removed").isEqualTo(new Rescan.Result(1, 0));
        } finally {
            Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rwxr-xr-x"));
        }

        assertThat(queries.photos("A5")).hasSize(1);
        assertThat(queries.photos("A6")).hasSize(1);
        assertThat(rescan.progress().finishedAt()).isNotNull();
    }

    @Test
    void aFailingListenerDoesNotAbortTheRunAndTheRunStillFinishes() throws Exception {
        put(PLAIN + "a.jpg", TestImages.jpeg(30, 20, 1));
        put(PLAIN + "b.jpg", TestImages.jpeg(30, 20, 2));
        Rescan failing = new Rescan(() -> library, processor, clock, id -> {
            throw new IllegalStateException("the thumbnail queue is closed");
        }, Clock.systemUTC(), Duration.ZERO);

        assertThat(failing.run()).isEqualTo(new Rescan.Result(2, 0));

        assertThat(failing.progress()).isEqualTo(new Rescan.Progress(2, 2, 2, 0, 0, "2026-10-11T09:00:00Z"));
        assertThat(failing.running()).isFalse();
    }

    @Test
    void aRenamedSpeciesFolderKeepsTagsNumberAndCover() throws Exception {
        Photo photo = store().add("A5", new ByteArrayInputStream(TestImages.jpeg(30, 20)), "image/jpeg", null,
                List.of("male")).photo();
        store().setCover("A5", photo.id());
        Path from = library.folder().resolve(photo.relPath()).getParent();
        Path to = library.folder().resolve("images/My butterflies/Plain tiger [A5]");
        Files.createDirectories(to.getParent());
        Files.move(from, to);

        assertThat(rescan.run()).as("a rename is neither new nor missing").isEqualTo(new Rescan.Result(0, 0));
        assertThat(rescan.progress().updated()).isEqualTo(1);

        Photo after = queries.photos("A5").get(0);
        assertThat(after.id()).isEqualTo(photo.id());
        assertThat(after.relPath()).isEqualTo("images/My butterflies/Plain tiger [A5]/" + photo.fileName());
        assertThat(after.tags()).containsExactly("male");
        assertThat(after.isCover()).isTrue();
        assertThat(strings("SELECT number FROM image")).containsExactly("1");
        assertThat(strings("SELECT kind FROM change_log ORDER BY id DESC LIMIT 1")).containsExactly("photo.rename");
        assertThat(found).containsExactly(photo.id());
    }

    @Test
    void aRenamedFileKeepsItsRecord() throws Exception {
        Photo photo = store().add("A5", new ByteArrayInputStream(TestImages.jpeg(30, 20)), "image/jpeg", null,
                List.of("female")).photo();
        Path file = library.folder().resolve(photo.relPath());
        Files.move(file, file.resolveSibling("my best one.jpg"));

        assertThat(rescan.run()).isEqualTo(new Rescan.Result(0, 0));

        Photo after = queries.photos("A5").get(0);
        assertThat(after.id()).isEqualTo(photo.id());
        assertThat(after.fileName()).isEqualTo("my best one.jpg");
        assertThat(after.tags()).containsExactly("female");
        assertThat(strings("SELECT COUNT(*) FROM image")).containsExactly("1");
    }

    @Test
    void aCaseOnlyRenameIsOneRecordNotTwo() throws Exception {
        Photo photo = store().add("A5", new ByteArrayInputStream(TestImages.jpeg(30, 20)), "image/jpeg", null, List.of()).photo();
        Path file = library.folder().resolve(photo.relPath());
        // Two steps: on a case-insensitive file system Files.move to a same-file name does nothing.
        Files.move(file, file.resolveSibling("tmp.part"));
        Files.move(file.resolveSibling("tmp.part"), file.resolveSibling(photo.fileName().toUpperCase()));

        assertThat(rescan.run()).isEqualTo(new Rescan.Result(0, 0));

        assertThat(strings("SELECT COUNT(*) FROM image")).containsExactly("1");
        assertThat(queries.photos("A5").get(0).fileName()).isEqualTo(photo.fileName().toUpperCase());
        assertThat(queries.photos("A5").get(0).id()).isEqualTo(photo.id());
    }

    @Test
    void aFileEditedInPlaceGetsANewCacheKey() throws Exception {
        Photo photo = store().add("A5", new ByteArrayInputStream(TestImages.jpeg(30, 20)), "image/jpeg", null, List.of()).photo();
        Path file = library.folder().resolve(photo.relPath());
        String before = strings("SELECT modified_at FROM image").get(0);
        found.clear();

        Files.setLastModifiedTime(file, FileTime.from(Instant.parse(before).plusSeconds(1)));
        assertThat(rescan.run()).isEqualTo(new Rescan.Result(0, 0));
        assertThat(strings("SELECT modified_at FROM image")).as("1 second is within the tolerance").containsExactly(before);
        assertThat(found).isEmpty();

        Files.write(file, TestImages.jpeg(60, 40, 9));
        Files.setLastModifiedTime(file, FileTime.from(Instant.parse(before).plusSeconds(3600)));
        assertThat(rescan.run()).isEqualTo(new Rescan.Result(0, 0));

        String after = strings("SELECT modified_at FROM image").get(0);
        assertThat(after).isNotEqualTo(before);
        assertThat(Thumbnails.fileName(photo.id(), after, 1)).isNotEqualTo(Thumbnails.fileName(photo.id(), before, 1));
        assertThat(Thumbnails.fileName(photo.id(), after, 1)).as("a different size is a different file")
                .isNotEqualTo(Thumbnails.fileName(photo.id(), after, 2));
        assertThat(queries.photos("A5").get(0).width()).isEqualTo(60);
        assertThat(strings("SELECT file_size FROM image")).containsExactly(String.valueOf(Files.size(file)));
        assertThat(strings("SELECT sha256 FROM image")).containsExactly(PhotoFiles.sha256(file));
        assertThat(found).as("so its thumbnail is made again").containsExactly(photo.id());
        assertThat(rescan.progress().updated()).isEqualTo(1);
    }

    @Test
    void anEditedFileIsProcessedAgainFromItsNewSnapshot() throws Exception {
        Photo photo = store().add("A5", new ByteArrayInputStream(TestImages.jpeg(30, 20)), "image/jpeg", null, List.of()).photo();
        Path file = library.folder().resolve(photo.relPath());
        exec("INSERT INTO image_processing (image_id, consumer, version, status, done_at)"
                + " VALUES (" + photo.id() + ", 'thumbnail', 1, 'done', '2026-10-11T09:00:00Z')");

        Files.write(file, TestExif.camera().taken("2024:05:01 10:22:33", "+05:30").jpeg(TestImages.jpeg(60, 40, 9)));
        Files.setLastModifiedTime(file, FileTime.from(Instant.now().minusSeconds(3600)));
        rescan.run();

        assertThat(strings("SELECT json FROM image_meta")).singleElement().asString()
                .contains("\"width\":60", "\"capturedAt\":\"2024-05-01T04:52:33Z\"", "\"Make\":\"Canon\"");
        assertThat(strings("SELECT consumer FROM image_processing")).as("the thumbnail is made again; dimensions are current")
                .containsExactly("dimensions");

        Files.write(file, Arrays.copyOf(TestImages.jpeg(60, 40, 10), 30));
        Files.setLastModifiedTime(file, FileTime.from(Instant.now().minusSeconds(1800)));
        rescan.run();
        assertThat(strings("SELECT COUNT(*) FROM image_meta")).as("an unreadable edit drops the snapshot").containsExactly("0");
        assertThat(queries.photos("A5").get(0).width()).isNull();
    }

    @Test
    void newAndEditedPhotosGetTheirPlaceFromGpsButAPlaceSetByTheUserStays() throws Exception {
        Rescan placing = new Rescan(() -> library, new PhotoIngest(processor), clock, found::add, Clock.systemUTC(),
                Duration.ZERO, List.of(new Dimensions(), new Place(TestPlaces.bundles().index())));
        Path a = put(PLAIN + "a.jpg", TestExif.camera().gps(18.5, 73.5, 600).jpeg(TestImages.jpeg(30, 20, 1)));
        Path b = put(PLAIN + "b.jpg", TestExif.camera().gps(18.5, 74.5, 600).jpeg(TestImages.jpeg(30, 20, 2)));
        Files.setLastModifiedTime(a, FileTime.from(Instant.now().minusSeconds(7200)));
        Files.setLastModifiedTime(b, FileTime.from(Instant.now().minusSeconds(7200)));

        placing.run();

        assertThat(strings("SELECT file_name || ' ' || place_id || ' ' || place_source FROM image ORDER BY file_name"))
                .containsExactly("a.jpg P4 gps", "b.jpg P5 gps");
        assertThat(strings("SELECT COUNT(*) FROM image_processing WHERE consumer = 'place' AND status = 'done'"))
                .containsExactly("2");

        exec("UPDATE image SET place_id = 'P8', place_source = 'user' WHERE file_name = 'b.jpg'");
        Files.write(a, TestExif.camera().gps(18.5, 74.5, 600).jpeg(TestImages.jpeg(30, 20, 3)));
        Files.write(b, TestImages.jpeg(30, 20, 4));
        Files.setLastModifiedTime(a, FileTime.from(Instant.now().minusSeconds(3600)));
        Files.setLastModifiedTime(b, FileTime.from(Instant.now().minusSeconds(3600)));
        placing.run();

        assertThat(strings("SELECT file_name || ' ' || place_id || ' ' || place_source FROM image ORDER BY file_name"))
                .as("a's GPS moved to Beta; b's GPS is gone but its place is the user's").containsExactly("a.jpg P5 gps",
                        "b.jpg P8 user");

        Files.write(a, TestImages.jpeg(30, 20, 5));
        Files.setLastModifiedTime(a, FileTime.from(Instant.now().minusSeconds(1800)));
        placing.run();
        assertThat(strings("SELECT coalesce(place_id, '-') || ' ' || coalesce(place_source, '-') FROM image"
                + " WHERE file_name = 'a.jpg'")).as("its GPS removed by an edit: unknown place").containsExactly("- -");
    }

    @Test
    void newPhotosAreRegisteredInBatchesWithTheirSnapshots() throws Exception {
        for (int i = 0; i < 250; i++) {
            put(PLAIN + "p" + i + ".jpg", TestImages.jpeg(8, 8, i));
        }
        put(PLAIN + "unreadable.jpg", Arrays.copyOf(TestImages.jpeg(8, 8, 999), 30));
        List<Integer> registeredWhenFound = new ArrayList<>();
        Rescan counting = new Rescan(() -> library, processor, clock,
                id -> registeredWhenFound.add(Integer.parseInt(strings("SELECT COUNT(*) FROM image").get(0))),
                Clock.systemUTC(), Duration.ZERO);

        assertThat(counting.run()).isEqualTo(new Rescan.Result(251, 0));

        assertThat(registeredWhenFound).hasSize(251);
        assertThat(registeredWhenFound.get(0)).as("the first 200 are saved together").isEqualTo(200);
        assertThat(registeredWhenFound.get(199)).isEqualTo(200);
        assertThat(registeredWhenFound.get(200)).isEqualTo(251);
        assertThat(strings("SELECT COUNT(*) FROM image_meta")).as("all but the unreadable one").containsExactly("250");
        assertThat(strings("SELECT COUNT(*) FROM image_processing WHERE consumer = 'dimensions'")).containsExactly("250");
        assertThat(strings("SELECT COUNT(*) FROM image WHERE width IS NULL")).containsExactly("1");
    }

    @Test
    void oneBadPhotoInABatchDoesNotHoldBackTheOthers() throws Exception {
        for (String name : List.of("a.jpg", "bad.jpg", "c.jpg", "d.jpg")) {
            put(PLAIN + name, TestImages.jpeg(8, 8, name.hashCode()));
        }
        exec("CREATE TRIGGER t BEFORE INSERT ON image WHEN NEW.file_name = 'bad.jpg' BEGIN SELECT RAISE(ABORT, 'x'); END");
        List<Long> notified = new ArrayList<>();

        Rescan counting = new Rescan(() -> library, processor, clock, notified::add, Clock.systemUTC(), Duration.ZERO);
        assertThat(counting.run()).isEqualTo(new Rescan.Result(3, 0));

        assertThat(strings("SELECT file_name FROM image ORDER BY file_name")).containsExactly("a.jpg", "c.jpg", "d.jpg");
        assertThat(strings("SELECT COUNT(*) FROM image_meta")).containsExactly("3");
        assertThat(strings("SELECT COUNT(*) FROM image_processing WHERE consumer = 'dimensions'")).containsExactly("3");
        assertThat(notified).hasSize(3).doesNotHaveDuplicates();
    }

    @Test
    void aPhotoWhoseSnapshotCannotBeSavedIsStillRegistered() throws Exception {
        put(PLAIN + "a.jpg", TestImages.jpeg(8, 8, 1));
        put(PLAIN + "odd.jpg", TestImages.jpeg(8, 8, 2));
        put(PLAIN + "c.jpg", TestImages.jpeg(8, 8, 3));
        exec("CREATE TRIGGER t BEFORE INSERT ON image_meta WHEN NEW.image_id = (SELECT id FROM image WHERE file_name = 'odd.jpg')"
                + " BEGIN SELECT RAISE(ABORT, 'x'); END");
        List<Long> notified = new ArrayList<>();

        Rescan counting = new Rescan(() -> library, processor, clock, notified::add, Clock.systemUTC(), Duration.ZERO);
        assertThat(counting.run()).isEqualTo(new Rescan.Result(3, 0));

        assertThat(strings("SELECT file_name FROM image ORDER BY file_name")).containsExactly("a.jpg", "c.jpg", "odd.jpg");
        assertThat(strings("SELECT file_name FROM image WHERE width IS NULL")).containsExactly("odd.jpg");
        assertThat(strings("SELECT COUNT(*) FROM image_meta")).containsExactly("2");
        assertThat(strings("SELECT COUNT(*) FROM image_processing WHERE consumer = 'dimensions'")).containsExactly("2");
        assertThat(notified).hasSize(3).doesNotHaveDuplicates();
    }

    @Test
    void aFileWhoseSizeChangesWhileItIsReadIsLeftForTheNextRun() throws Exception {
        Photo gone = store().add("A5", new ByteArrayInputStream(TestImages.jpeg(30, 20)), "image/jpeg", null, List.of()).photo();
        Files.delete(library.folder().resolve(gone.relPath()));
        put(PLAIN + "new.jpg", TestImages.jpeg(8, 8, 5));
        found.clear();
        PhotoIngest growing = new PhotoIngest(processor, PhotoStore.MAX_BYTES, file -> {
            byte[] bytes = Files.readAllBytes(file);
            byte[] more = Arrays.copyOf(bytes, bytes.length + 10);
            return new ByteArrayInputStream(more);
        });
        Rescan unsettled = new Rescan(() -> library, growing, clock, found::add, Clock.systemUTC(), Duration.ZERO);

        assertThat(unsettled.run()).as("nothing registered, and the missing record is kept meanwhile")
                .isEqualTo(new Rescan.Result(0, 0));
        assertThat(strings("SELECT COUNT(*) FROM image")).containsExactly("1");
        assertThat(found).isEmpty();

        assertThat(quick(processor).run()).as("the next run, with the file steady").isEqualTo(new Rescan.Result(1, 1));
    }

    @Test
    void aKnownPhotoOverwrittenWithSomethingUnsupportedKeepsItsRecordWithoutDetails() throws Exception {
        byte[][] replacements = {
                // a HEIC image sequence (Phase 7b: still refused; a damaged still HEIC is registered without details)
                {0, 0, 0, 24, 'f', 't', 'y', 'p', 'm', 's', 'f', '1', 0, 0, 0, 0, 'm', 's', 'f', '1', 'h', 'e', 'i', 'c'},
                "just some text, not a photo".getBytes(java.nio.charset.StandardCharsets.UTF_8)};
        int round = 0;
        for (byte[] replacement : replacements) {
            round++;
            Photo photo = store().add("A5", new ByteArrayInputStream(TestImages.jpeg(30 + round, 20)), "image/jpeg", null, List.of()).photo();
            Path file = library.folder().resolve(photo.relPath());
            exec("INSERT INTO image_processing (image_id, consumer, version, status, done_at)"
                    + " VALUES (" + photo.id() + ", 'thumbnail', 1, 'done', '2026-10-11T09:00:00Z')");
            Files.write(file, replacement);
            Files.setLastModifiedTime(file, FileTime.from(Instant.now().minusSeconds(3600)));

            assertThat(rescan.run()).isEqualTo(new Rescan.Result(0, 0));

            assertThat(strings("SELECT file_size FROM image WHERE id = " + photo.id())).containsExactly(String.valueOf(replacement.length));
            assertThat(strings("SELECT COUNT(*) FROM image WHERE id = " + photo.id() + " AND width IS NULL AND height IS NULL"))
                    .containsExactly("1");
            assertThat(strings("SELECT COUNT(*) FROM image_meta WHERE image_id = " + photo.id())).containsExactly("0");
            assertThat(strings("SELECT COUNT(*) FROM image_processing WHERE image_id = " + photo.id())).containsExactly("0");
            exec("DELETE FROM image WHERE id = " + photo.id());
        }
    }

    @Test
    void filesStillBeingCopiedAreLeftForTheNextRun() throws Exception {
        Rescan patient = new Rescan(() -> library, processor, clock, found::add);
        Path file = put(PLAIN + "arriving.jpg", TestImages.jpeg(30, 20));

        assertThat(patient.run()).as("modified just now").isEqualTo(new Rescan.Result(0, 0));
        assertThat(patient.progress().total()).isZero();

        Files.setLastModifiedTime(file, FileTime.from(Instant.now().minusSeconds(60)));
        assertThat(patient.run().added()).isEqualTo(1);
    }

    @Test
    void trashAndMetadataFoldersAreSkipped() throws Exception {
        byte[] jpeg = TestImages.jpeg(30, 20);
        put("images/.Trashes/Danaus [A5]/a.jpg", jpeg);
        put("images/@eaDir/Danaus [A5]/b.jpg", jpeg);
        put("images/Nymphalidae/@eaDir/Plain [A5]/c.jpg", jpeg);

        assertThat(rescan.run()).isEqualTo(new Rescan.Result(0, 0));
        assertThat(rescan.progress().total()).isZero();
    }

    @Test
    void nothingIsRemovedWhileAFileIsStillArriving() throws Exception {
        Photo photo = store().add("A5", new ByteArrayInputStream(TestImages.jpeg(30, 20)), "image/jpeg", null,
                List.of("male")).photo();
        Path file = library.folder().resolve(photo.relPath());
        byte[] bytes = Files.readAllBytes(file);
        Files.delete(file);
        Rescan patient = new Rescan(() -> library, processor, clock, found::add);
        // The same photo, being copied in under a new name right now.
        Path arriving = put(PLAIN + "renamed.jpg", bytes);

        assertThat(patient.run()).as("the record waits for the copy to settle").isEqualTo(new Rescan.Result(0, 0));
        assertThat(queries.photos("A5")).hasSize(1);

        Files.setLastModifiedTime(arriving, FileTime.from(Instant.now().minusSeconds(60)));
        assertThat(patient.run()).isEqualTo(new Rescan.Result(0, 0));
        Photo after = queries.photos("A5").get(0);
        assertThat(after.id()).isEqualTo(photo.id());
        assertThat(after.fileName()).isEqualTo("renamed.jpg");
        assertThat(after.tags()).containsExactly("male");
    }
}
