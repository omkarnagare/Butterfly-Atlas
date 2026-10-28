package com.butterflyatlas.app.photos;

import com.butterflyatlas.app.AtlasException;
import com.butterflyatlas.app.imaging.ImageIoPhotoProcessor;
import com.butterflyatlas.app.library.Library;
import com.butterflyatlas.app.photos.consumers.Dimensions;
import com.butterflyatlas.app.photos.consumers.Place;
import com.butterflyatlas.app.testing.TestBundles;
import com.butterflyatlas.app.testing.TestClock;
import com.butterflyatlas.app.testing.TestExif;
import com.butterflyatlas.app.testing.TestImages;
import com.butterflyatlas.app.testing.TestPlaces;
import com.butterflyatlas.app.testing.TestRaw;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.io.InputStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PhotoStoreAddTest {

    private static final String PLAIN_TIGER_FOLDER = "images/Nymphalidae/Danainae/Danaini/Danaus/Danaus_chrysippus_[A5]/";

    @TempDir
    Path dir;

    private final TestClock clock = new TestClock();
    private final List<Long> queued = new ArrayList<>();
    private Library library;
    private PhotoStore store;

    @BeforeEach
    void setUp() {
        library = TestBundles.fixtureLibrary(dir.resolve("Atlas"));
        store = new PhotoStore(() -> library, new ImageIoPhotoProcessor(), clock, queued::add);
    }

    @Test
    void anAddedPhotoGetsEveryFactConsumerIncludingItsPlaceFromGpsInTheSameTransaction() {
        PhotoStore placing = new PhotoStore(() -> library, new ImageIoPhotoProcessor(), clock, queued::add,
                List.of(new Dimensions(), new Place(TestPlaces.bundles().index())));

        Photo inBeta = placing.add("A5", new ByteArrayInputStream(TestExif.camera().gps(18.5, 74.5, 600)
                .jpeg(TestImages.jpeg(40, 20, 1))), "image/jpeg", null, List.of()).photo();
        Photo noGps = placing.add("A5", new ByteArrayInputStream(TestImages.jpeg(40, 20, 2)), "image/jpeg", null, List.of())
                .photo();

        assertThat(inBeta.place()).isEqualTo(new com.butterflyatlas.app.places.PlaceRef("P5",
                List.of("Beta", "Northstate", "Testland"), List.of("P5", "P2", "P1"), "gps", false, "district"));
        assertThat(noGps.place()).isNull();
        assertThat(one("SELECT group_concat(consumer || ' ' || status, ', ') FROM (SELECT * FROM image_processing"
                + " WHERE image_id = " + noGps.id() + " ORDER BY consumer)")).isEqualTo("dimensions done, place done");
    }

    private AddResult add(String taxonId, byte[] bytes, String... tags) {
        return store.add(taxonId, new ByteArrayInputStream(bytes), "image/jpeg", "IMG_0001.JPG", List.of(tags));
    }

    private String one(String sql) {
        return library.database().read(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        });
    }

    private List<String> filesUnder(String relative) throws Exception {
        Path root = library.folder().resolve(relative);
        if (!Files.exists(root)) {
            return List.of();
        }
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(Files::isRegularFile).map(f -> root.relativize(f).toString().replace('\\', '/')).sorted().toList();
        }
    }

    @Test
    void aRawPhotoIsAddedExactlyUnderItsOwnExtension() throws Exception {
        byte[] cr3 = TestRaw.cr3(TestImages.jpeg(1600, 1200), null,
                TestRaw.cmt(new TestRaw.Ifd().shortValue(0x0112, 6)), null, null);

        AddResult result = store.add("A5", new ByteArrayInputStream(cr3), "image/x-raw", "IMG_0042.CR3", List.of());

        Photo photo = result.photo();
        assertThat(photo.fileName()).isEqualTo("Danaus_chrysippus_01.cr3");
        assertThat(Files.readAllBytes(library.folder().resolve(photo.relPath()))).isEqualTo(cr3);
        assertThat(photo.width()).as("the preview as displayed").isEqualTo(1200);
        assertThat(photo.height()).isEqualTo(1600);
        assertThat(one("SELECT file_type || ' ' || orientation FROM image WHERE id = " + photo.id())).isEqualTo("raw 6");
        assertThat(one("SELECT json FROM image_meta WHERE image_id = " + photo.id()))
                .contains("\"format\":\"RAW\"", "\"rawPreview\":{");
        assertThat(one("SELECT file_type FROM image WHERE id = " + add("A5", TestImages.jpeg(40, 30)).photo().id()))
                .isEqualTo("jpeg");
    }

    @Test
    void addsAPhotoUnderTheSpeciesName() throws Exception {
        byte[] jpeg = TestImages.jpeg(400, 300);

        AddResult result = add("A5", jpeg);

        assertThat(result.status()).isEqualTo("added");
        assertThat(result.otherTaxon()).isNull();
        assertThat(result.firstForSpecies()).isTrue();
        assertThat(result.milestone()).isNull();
        assertThat(result.speciesCount()).isEqualTo(1);
        Photo photo = result.photo();
        assertThat(photo.taxonId()).isEqualTo("A5");
        assertThat(photo.fileName()).isEqualTo("Danaus_chrysippus_01.jpg");
        assertThat(photo.relPath()).isEqualTo(PLAIN_TIGER_FOLDER + "Danaus_chrysippus_01.jpg");
        assertThat(photo.width()).isEqualTo(400);
        assertThat(photo.height()).isEqualTo(300);
        assertThat(photo.tags()).isEmpty();
        assertThat(photo.addedAt()).isEqualTo("2026-10-11T09:00:00Z");
        assertThat(photo.isCover()).isTrue();
        assertThat(photo.v()).isEqualTo(Files.getLastModifiedTime(library.folder().resolve(photo.relPath())).toMillis()
                + "-" + jpeg.length);
        assertThat(Files.readAllBytes(library.folder().resolve(photo.relPath()))).isEqualTo(jpeg);
        assertThat(filesUnder(".incoming")).isEmpty();
        assertThat(queued).containsExactly(photo.id());
        assertThat(one("SELECT original_name || '|' || source || '|' || number || '|' || file_size FROM image"))
                .isEqualTo("IMG_0001.JPG|app|1|" + jpeg.length);
        assertThat(one("SELECT next_photo_number FROM taxon WHERE id = 'A5'")).isEqualTo("2");
    }

    @Test
    void theSnapshotAndTheDimensionsRowAreSavedWithThePhoto() {
        byte[] jpeg = TestExif.camera().taken("2024:05:01 10:22:33", "+05:30").jpeg(TestImages.jpeg(400, 300));

        long id = add("A5", jpeg).photo().id();

        assertThat(one("SELECT json FROM image_meta WHERE image_id = " + id))
                .startsWith("{\"v\":1,\"format\":\"JPEG\",\"width\":400,\"height\":300,\"orientation\":1,"
                        + "\"capturedAt\":\"2024-05-01T04:52:33Z\"")
                .contains("\"Make\":\"Canon\"");
        assertThat(one("SELECT read_at FROM image_meta WHERE image_id = " + id)).isEqualTo("2026-10-11T09:00:00Z");
        assertThat(one("SELECT consumer || ' v' || version || ' ' || status FROM image_processing WHERE image_id = " + id))
                .isEqualTo("dimensions v1 done");
    }

    @Test
    void rawPhotosWithoutAPreviewAndAvifPhotosAreRefusedAndLeaveNothingBehind() throws Exception {
        byte[] tiff = TestExif.camera().tiff(8, 6);

        assertThatThrownBy(() -> store.add("A5", new ByteArrayInputStream(tiff), "image/tiff", "DSC_0001.NEF", List.of()))
                .isInstanceOfSatisfying(AtlasException.class,
                        e -> assertThat(e.getMessage()).isEqualTo(PhotoFormat.RAW_NO_PREVIEW));
        assertThatThrownBy(() -> add("A5", TestExif.camera().dng().tiff(8, 6))).isInstanceOfSatisfying(AtlasException.class,
                e -> assertThat(e.getMessage()).isEqualTo(PhotoFormat.RAW_NO_PREVIEW));
        assertThatThrownBy(() -> add("A5", "\0\0\0\u0018ftypavis\0\0\0\0avis".getBytes(StandardCharsets.ISO_8859_1)))
                .isInstanceOfSatisfying(AtlasException.class,
                        e -> assertThat(e.getMessage()).isEqualTo("AVIF photos aren't supported yet."));

        assertThat(filesUnder("images")).isEmpty();
        assertThat(filesUnder(".incoming")).isEmpty();
        assertThat(one("SELECT COUNT(*) FROM image")).isEqualTo("0");
    }

    @Test
    void numbersGoUpAndTheBytesDecideTheExtension() {
        add("A5", TestImages.jpeg(40, 30, 1));
        AddResult png = store.add("A5", new ByteArrayInputStream(TestImages.png(40, 30)), "image/png", "a.png", List.of());
        // A JPEG sent as image/png (a renamed file) is still a JPEG.
        AddResult renamed = store.add("A5", new ByteArrayInputStream(TestImages.jpeg(40, 30, 2)), "image/png", "b.png", List.of());

        assertThat(png.photo().fileName()).isEqualTo("Danaus_chrysippus_02.png");
        assertThat(png.firstForSpecies()).isFalse();
        assertThat(renamed.photo().fileName()).isEqualTo("Danaus_chrysippus_03.jpg");
    }

    @Test
    void tiffAndWebpAreAccepted() {
        AddResult tiff = store.add("A6", new ByteArrayInputStream(TestImages.tiff(30, 20)), "image/tiff", "s.tif", List.of());
        AddResult webp = store.add("A6", new ByteArrayInputStream(TestImages.webp()), "image/webp", "w.webp", List.of());

        assertThat(tiff.photo().fileName()).isEqualTo("Danaus_genutia_01.tif");
        assertThat(webp.photo().fileName()).isEqualTo("Danaus_genutia_02.webp");
        assertThat(webp.photo().width()).isEqualTo(2);
    }

    @Test
    void keepsTheBatchTagsInBuiltInOrder() {
        AddResult result = add("A5", TestImages.jpeg(40, 30), "male", "larva", "male");

        assertThat(result.photo().tags()).containsExactly("larva", "male");
    }

    @Test
    void aSidewaysPhotoKeepsItsDisplayedSize() {
        AddResult result = add("A5", TestImages.withOrientation(TestImages.jpeg(40, 30), 6));

        assertThat(result.photo().width()).isEqualTo(30);
        assertThat(result.photo().height()).isEqualTo(40);
        assertThat(one("SELECT orientation FROM image")).isEqualTo("6");
    }

    @Test
    void theSameFileTwiceInOneSpeciesIsSkipped() throws Exception {
        byte[] jpeg = TestImages.jpeg(40, 30);
        add("A5", jpeg);

        AddResult again = add("A5", jpeg);

        assertThat(again.status()).isEqualTo("skipped");
        assertThat(again.photo()).isNull();
        assertThat(again.otherTaxon()).isEqualTo(new TaxonRef("A5", "Danaus chrysippus", "Plain Tiger"));
        assertThat(again.firstForSpecies()).isFalse();
        assertThat(filesUnder(PLAIN_TIGER_FOLDER)).containsExactly("Danaus_chrysippus_01.jpg");
        assertThat(filesUnder(".incoming")).isEmpty();
        assertThat(one("SELECT next_photo_number FROM taxon WHERE id = 'A5'")).isEqualTo("2");
    }

    @Test
    void theSameFileUnderAnotherSpeciesIsAddedAndFlagged() {
        byte[] jpeg = TestImages.jpeg(40, 30);
        add("A5", jpeg);

        AddResult striped = add("A6", jpeg);

        assertThat(striped.status()).isEqualTo("addedElsewhereToo");
        assertThat(striped.otherTaxon()).isEqualTo(new TaxonRef("A5", "Danaus chrysippus", "Plain Tiger"));
        assertThat(striped.photo().fileName()).isEqualTo("Danaus_genutia_01.jpg");
        assertThat(striped.firstForSpecies()).isTrue();
    }

    @Test
    void aCopyInRecentlyDeletedIsNotADuplicate() {
        byte[] jpeg = TestImages.jpeg(40, 30);
        TestBundles.markDeleted(library.database(), add("A5", jpeg).photo().id());

        AddResult again = add("A5", jpeg);

        assertThat(again.status()).isEqualTo("added");
        assertThat(again.photo().fileName()).isEqualTo("Danaus_chrysippus_02.jpg");
    }

    @Test
    void refusesFilesThatAreNotPhotosInPlainWords() throws Exception {
        assertThatThrownBy(() -> add("A5", "hello".getBytes(StandardCharsets.US_ASCII)))
                .isInstanceOfSatisfying(AtlasException.class, e -> {
                    assertThat(e.kind()).isEqualTo(AtlasException.Kind.UNSUPPORTED);
                    assertThat(e.getMessage()).isEqualTo("This file isn't a photo Butterfly Atlas can read.");
                });
        assertThatThrownBy(() -> add("A5", "\0\0\0\u0018ftypmsf1\0\0\0\0".getBytes(StandardCharsets.ISO_8859_1)))
                .isInstanceOfSatisfying(AtlasException.class,
                        e -> assertThat(e.getMessage()).isEqualTo(PhotoFormat.HEIC_SEQUENCE));
        assertThatThrownBy(() -> add("A5", "\0\0\0\u0018ftypheic\0\0\0\0".getBytes(StandardCharsets.ISO_8859_1)))
                .as("a HEIC by its brand whose boxes are damaged").isInstanceOfSatisfying(AtlasException.class,
                        e -> assertThat(e.getMessage()).isEqualTo(PhotoFormat.UNREADABLE));
        byte[] jpeg = TestImages.jpeg(40, 30);
        byte[] broken = java.util.Arrays.copyOf(jpeg, 20);
        assertThatThrownBy(() -> add("A5", broken)).isInstanceOfSatisfying(AtlasException.class,
                e -> assertThat(e.kind()).isEqualTo(AtlasException.Kind.UNSUPPORTED));

        assertThat(filesUnder("images")).isEmpty();
        assertThat(filesUnder(".incoming")).isEmpty();
        assertThat(one("SELECT COUNT(*) FROM image")).isEqualTo("0");
    }

    @Test
    void refusesFilesOverTheLimitWhileStreaming() throws Exception {
        PhotoStore small = new PhotoStore(() -> library, new ImageIoPhotoProcessor(), clock, queued::add, 1000);
        byte[] jpeg = TestImages.jpeg(200, 200);
        assertThat(jpeg.length).isGreaterThan(1000);

        assertThatThrownBy(() -> small.add("A5", new ByteArrayInputStream(jpeg), "image/jpeg", "big.jpg", List.of()))
                .isInstanceOfSatisfying(AtlasException.class, e -> assertThat(e.kind()).isEqualTo(AtlasException.Kind.TOO_LARGE));
        assertThat(filesUnder(".incoming")).isEmpty();
        assertThat(store.maxBytes()).isEqualTo(300L * 1024 * 1024);
    }

    @Test
    void anUploadThatIsCutOffIsInterruptedNotAnError() throws Exception {
        InputStream cut = new InputStream() {
            private int sent;

            @Override
            public int read() throws java.io.IOException {
                return read(new byte[1], 0, 1);
            }

            @Override
            public int read(byte[] b, int off, int len) throws java.io.IOException {
                if (sent > 0) {
                    throw new java.io.IOException("Connection reset");
                }
                sent = 10;
                java.util.Arrays.fill(b, off, off + 10, (byte) 1);
                return 10;
            }
        };

        assertThatThrownBy(() -> store.add("A5", cut, "image/jpeg", "x.jpg", List.of()))
                .isInstanceOfSatisfying(AtlasException.class, e -> {
                    assertThat(e.kind()).isEqualTo(AtlasException.Kind.INTERRUPTED);
                    assertThat(e.getMessage()).isEqualTo("The upload was interrupted.");
                });
        assertThat(filesUnder(".incoming")).isEmpty();
        assertThat(one("SELECT COUNT(*) FROM image")).isEqualTo("0");
    }

    @Test
    void skipsNamesAlreadyTakenByFilesCopiedInByHand() throws Exception {
        Path folder = Files.createDirectories(library.folder().resolve(PLAIN_TIGER_FOLDER));
        Files.writeString(folder.resolve("Danaus_chrysippus_01.jpg"), "copied by hand");

        AddResult result = add("A5", TestImages.jpeg(40, 30));

        assertThat(result.photo().fileName()).isEqualTo("Danaus_chrysippus_02.jpg");
        assertThat(Files.readString(folder.resolve("Danaus_chrysippus_01.jpg"))).isEqualTo("copied by hand");
        assertThat(one("SELECT next_photo_number FROM taxon WHERE id = 'A5'")).isEqualTo("3");
    }

    @Test
    void onlySpeciesAndKnownTagsAreAccepted() throws Exception {
        byte[] jpeg = TestImages.jpeg(40, 30);

        assertThatThrownBy(() -> add("A999", jpeg)).isInstanceOfSatisfying(AtlasException.class,
                e -> assertThat(e.kind()).isEqualTo(AtlasException.Kind.NOT_FOUND));
        assertThatThrownBy(() -> add("A4", jpeg)).isInstanceOfSatisfying(AtlasException.class,
                e -> assertThat(e.getMessage()).isEqualTo("Photos can only be added to a species."));
        assertThatThrownBy(() -> add("A5", jpeg, "wingspan")).isInstanceOfSatisfying(AtlasException.class,
                e -> assertThat(e.getMessage()).isEqualTo("There is no tag called \"wingspan\"."));
        assertThat(filesUnder(".incoming")).isEmpty();
    }

    @Test
    void whenSavingTheRecordFailsTheFileIsRemovedAgain() throws Exception {
        library.database().write(c -> {
            try (Statement st = c.createStatement()) {
                st.executeUpdate("CREATE TRIGGER refuse BEFORE INSERT ON image BEGIN SELECT RAISE(ABORT, 'disk full'); END");
            }
            return null;
        });

        assertThatThrownBy(() -> add("A5", TestImages.jpeg(40, 30)))
                .isInstanceOf(RuntimeException.class).isNotInstanceOf(AtlasException.class)
                .hasStackTraceContaining("disk full");

        assertThat(filesUnder("images")).isEmpty();
        assertThat(filesUnder(".incoming")).isEmpty();
        assertThat(one("SELECT next_photo_number FROM taxon WHERE id = 'A5'")).isEqualTo("1");
        assertThat(queued).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"image_meta", "image_processing"})
    void whenSavingTheSnapshotOrLedgerRowFailsNothingIsLeftBehind(String table) throws Exception {
        library.database().write(c -> {
            try (Statement st = c.createStatement()) {
                st.executeUpdate("CREATE TRIGGER refuse BEFORE INSERT ON " + table
                        + " BEGIN SELECT RAISE(ABORT, 'disk full'); END");
            }
            return null;
        });

        assertThatThrownBy(() -> add("A5", TestImages.jpeg(40, 30)))
                .isInstanceOf(RuntimeException.class).isNotInstanceOf(AtlasException.class)
                .hasStackTraceContaining("disk full");

        assertThat(filesUnder("images")).isEmpty();
        assertThat(filesUnder(".incoming")).isEmpty();
        assertThat(one("SELECT COUNT(*) FROM image")).isEqualTo("0");
        assertThat(one("SELECT next_photo_number FROM taxon WHERE id = 'A5'")).isEqualTo("1");
        assertThat(queued).isEmpty();
    }

    @Test
    void whenTheFolderCannotBeMadeNothingIsLeftBehind() throws Exception {
        Path folder = library.folder().resolve(PLAIN_TIGER_FOLDER);
        Files.createDirectories(folder.getParent());
        Files.writeString(folder.getParent().resolve("Danaus_chrysippus_[A5]"), "a file where the folder should be");

        assertThatThrownBy(() -> add("A5", TestImages.jpeg(40, 30))).isNotNull();

        assertThat(filesUnder(".incoming")).isEmpty();
        assertThat(one("SELECT COUNT(*) FROM image")).isEqualTo("0");
    }

    @Test
    void theSpeciesCountIsTakenAfterTheAdd() {
        byte[] jpeg = TestImages.jpeg(40, 30);
        TestBundles.addImage(library.database(), "A12");

        assertThat(add("A5", jpeg).speciesCount()).as("first photo of a second species").isEqualTo(2);
        assertThat(add("A5", TestImages.jpeg(40, 30, 3)).speciesCount()).as("same species again").isEqualTo(2);
        assertThat(add("A5", jpeg).speciesCount()).as("skipped").isEqualTo(2);
        assertThat(add("A6", jpeg).speciesCount()).as("added elsewhere too").isEqualTo(3);
    }

    @Test
    void uploadsLeftHalfWrittenByACrashAreCleanedUp() throws Exception {
        Path incoming = Files.createDirectories(library.folder().resolve(".incoming"));
        Files.writeString(incoming.resolve("0b9c-crashed.part"), "half an upload");
        Files.writeString(incoming.resolve("1f2e-crashed.part"), "another");
        Files.writeString(incoming.resolve("notes.txt"), "not ours");
        Files.writeString(incoming.resolve("2a3b-arriving.part"), "being uploaded right now");
        java.nio.file.attribute.FileTime old = java.nio.file.attribute.FileTime.from(java.time.Instant.now().minusSeconds(3600));
        Files.setLastModifiedTime(incoming.resolve("0b9c-crashed.part"), old);
        Files.setLastModifiedTime(incoming.resolve("1f2e-crashed.part"), old);

        assertThat(store.cleanIncoming()).as("the fresh one is an upload in progress").isEqualTo(2);

        assertThat(filesUnder(".incoming")).containsExactlyInAnyOrder("notes.txt", "2a3b-arriving.part");
        assertThat(store.cleanIncoming()).isZero();
    }

    @Test
    void theTenthSpeciesPhotographedIsAMilestone() {
        for (String id : List.of("U1", "U2", "U3", "U4")) {
            TestBundles.addManualSpecies(library.database(), id, "Species " + id);
        }
        for (String id : List.of("A5", "A6", "A8", "A12", "A13", "A14", "U1", "U2", "U3")) {
            TestBundles.addImage(library.database(), id);
        }

        AddResult tenth = add("U4", TestImages.jpeg(40, 30));
        AddResult again = add("U4", TestImages.jpeg(40, 30, 9));

        assertThat(tenth.firstForSpecies()).isTrue();
        assertThat(tenth.milestone()).isEqualTo(10);
        assertThat(tenth.speciesCount()).isEqualTo(10);
        assertThat(tenth.photo().relPath()).isEqualTo("images/Custom_additions/Species_U4_[U4]/Species_U4_01.jpg");
        assertThat(again.milestone()).isNull();
    }

    @Test
    void theLimitIsInclusive() {
        byte[] jpeg = TestImages.jpeg(40, 30);
        PhotoStore exact = new PhotoStore(() -> library, new ImageIoPhotoProcessor(), clock, queued::add, jpeg.length);
        PhotoStore oneShort = new PhotoStore(() -> library, new ImageIoPhotoProcessor(), clock, queued::add, jpeg.length - 1);

        assertThat(exact.add("A5", new ByteArrayInputStream(jpeg), "image/jpeg", "a.jpg", List.of()).status()).isEqualTo("added");
        assertThatThrownBy(() -> oneShort.add("A6", new ByteArrayInputStream(jpeg), "image/jpeg", "a.jpg", List.of()))
                .isInstanceOfSatisfying(AtlasException.class, e -> assertThat(e.kind()).isEqualTo(AtlasException.Kind.TOO_LARGE));
    }

    @Test
    void aFailingThumbnailQueueDoesNotFailASavedUpload() {
        PhotoStore store = new PhotoStore(() -> library, new ImageIoPhotoProcessor(), clock, id -> {
            throw new IllegalStateException("queue closed");
        });

        AddResult result = store.add("A5", new ByteArrayInputStream(TestImages.jpeg(40, 30)), "image/jpeg", "a.jpg", List.of());

        assertThat(result.status()).isEqualTo("added");
        assertThat(one("SELECT COUNT(*) FROM image")).isEqualTo("1");
    }

    /** Reports end of stream only once every upload has streamed its whole body. */
    private static InputStream waitingAtTheEnd(byte[] bytes, CountDownLatch streamed) {
        return new ByteArrayInputStream(bytes) {
            @Override
            public synchronized int read(byte[] b, int off, int len) {
                int n = super.read(b, off, len);
                if (n == -1) {
                    streamed.countDown();
                    try {
                        streamed.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                return n;
            }
        };
    }

    @Test
    void twoUploadsOfTheSameBytesAtOnceAddOneAndSkipOne() throws Exception {
        byte[] jpeg = TestImages.jpeg(40, 30);
        CountDownLatch streamed = new CountDownLatch(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<AddResult>> both = List.of(
                    pool.submit(() -> store.add("A5", waitingAtTheEnd(jpeg, streamed), "image/jpeg", "1.jpg", List.of())),
                    pool.submit(() -> store.add("A5", waitingAtTheEnd(jpeg, streamed), "image/jpeg", "2.jpg", List.of())));
            List<String> statuses = new ArrayList<>();
            for (Future<AddResult> f : both) {
                statuses.add(f.get(30, java.util.concurrent.TimeUnit.SECONDS).status());
            }

            assertThat(statuses).containsExactlyInAnyOrder("added", "skipped");
            assertThat(filesUnder(PLAIN_TIGER_FOLDER)).containsExactly("Danaus_chrysippus_01.jpg");
            assertThat(filesUnder(".incoming")).isEmpty();
            assertThat(one("SELECT COUNT(*) FROM image")).isEqualTo("1");
        } finally {
            pool.shutdownNow();
        }
    }
}
