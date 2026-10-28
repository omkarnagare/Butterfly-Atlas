package com.butterflyatlas.app.imports;

import com.butterflyatlas.app.imaging.ImageIoPhotoProcessor;
import com.butterflyatlas.app.AtlasException;
import com.butterflyatlas.app.library.Library;
import com.butterflyatlas.app.photos.PhotoIngest;
import com.butterflyatlas.app.photos.PhotoStore;
import com.butterflyatlas.app.photos.Thumbnails;
import com.butterflyatlas.app.photos.consumers.Dimensions;
import com.butterflyatlas.app.photos.consumers.Place;
import com.butterflyatlas.app.search.SearchService;
import com.butterflyatlas.app.testing.TestBundles;
import com.butterflyatlas.app.testing.TestExif;
import com.butterflyatlas.app.testing.TestHeif;
import com.butterflyatlas.app.testing.TestImages;
import com.butterflyatlas.app.testing.TestPlaces;
import com.butterflyatlas.app.testing.TestRaw;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class ImportFilerTest {

    @TempDir
    Path dir;

    private Path root;
    private Path data;
    private Library library;
    private ImportStore store;
    private ImportWalker walker;
    private ImportReader reader;
    private ImportEdits edits;
    private PhotoStore photos;
    private Thumbnails thumbnails;
    private ImageIoPhotoProcessor processor;

    @BeforeEach
    void setUp() throws IOException {
        root = Files.createDirectories(dir.resolve("Butterflies 2024"));
        data = dir.resolve("Atlas");
        library = TestBundles.fixtureLibrary(data);
        processor = new ImageIoPhotoProcessor();
        store = new ImportStore(() -> library, TestBundles.CLOCK);
        walker = new ImportWalker(() -> library, new SearchService(() -> library.database()), TestBundles.CLOCK);
        reader = new ImportReader(() -> library, processor, TestBundles.CLOCK);
        edits = new ImportEdits(() -> library, TestBundles.CLOCK);
        thumbnails = new Thumbnails(() -> library, processor, TestBundles.CLOCK);
        photos = new PhotoStore(() -> library, processor, TestBundles.CLOCK, thumbnails::request);
    }

    @AfterEach
    void close() {
        thumbnails.close();
    }

    private ImportFiler filer(ImportFiler.BeforeCommit beforeCommit) {
        return new ImportFiler(() -> library, photos, processor, thumbnails, TestBundles.CLOCK, beforeCommit);
    }

    private ImportFiler filer(ImportFiler.BeforeCommit beforeCommit, ImportFiler.Opener opener, ImportFiler.Ingester ingester,
                              java.util.function.ToLongFunction<Path> usableSpace) {
        PhotoIngest ingest = new PhotoIngest(processor, photos.maxBytes());
        return new ImportFiler(() -> library, photos, processor, thumbnails, TestBundles.CLOCK, beforeCommit,
                opener == null ? ImportFiler.Opener.NIO : opener, ingester == null ? ingest::ingest : ingester,
                usableSpace == null ? p -> Long.MAX_VALUE : usableSpace);
    }

    /** A stream that gives a few bytes, runs {@code then}, and fails: a drive that goes away part-way. */
    private static java.io.InputStream failingPartWay(IoAction then) {
        return new java.io.InputStream() {
            private int given;

            @Override
            public int read() throws IOException {
                if (given < 10) {
                    given++;
                    return 0xFF;
                }
                then.run();
                throw new IOException("the device is not ready");
            }
        };
    }

    @FunctionalInterface
    private interface IoAction {
        void run() throws IOException;
    }

    /** A small PNG unlike any other seed's (tiny JPEGs of nearby seeds can come out byte for byte the same). */
    private static byte[] distinct(int seed) {
        BufferedImage img = TestImages.halves(8, 8);
        img.setRGB(0, 0, seed & 0xFFFFFF);
        img.setRGB(1, 0, (seed >> 24) & 0xFF);
        return TestImages.encode(img, "png");
    }

    private Path write(String relPath, byte[] bytes) throws IOException {
        Path file = root.resolve(relPath);
        Files.createDirectories(file.getParent());
        return Files.write(file, bytes);
    }

    /** Walks and reads the folder; returns the import. */
    private long readAll() {
        long run = store.create(root).id();
        walker.walk(run, n -> { }, () -> false).walked();
        reader.read(run, (d, t) -> { }, () -> false);
        return run;
    }

    private Map<String, ImportStore.Item> items(long run) {
        return store.items(run, null).items().stream().collect(Collectors.toMap(ImportStore.Item::path, Function.identity()));
    }

    private long id(long run, String path) {
        return items(run).get(path).id();
    }

    private List<String> rows(String sql) {
        return library.database().read(c -> {
            List<String> rows = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
                int columns = rs.getMetaData().getColumnCount();
                while (rs.next()) {
                    List<String> row = new ArrayList<>();
                    for (int i = 1; i <= columns; i++) {
                        row.add(rs.getString(i));
                    }
                    rows.add(String.join("|", row));
                }
            }
            return rows;
        });
    }

    private List<String> filesUnder(Path folder) throws IOException {
        if (!Files.exists(folder)) {
            return List.of();
        }
        try (Stream<Path> files = Files.walk(folder)) {
            return files.filter(Files::isRegularFile).map(f -> folder.relativize(f).toString().replace('\\', '/')).sorted().toList();
        }
    }

    @Test
    void assignedPhotosAreFiledAsCopiesNamedAndRecordedAsAddPhotosDoes() throws Exception {
        byte[] first = TestImages.jpeg(600, 400, 1);
        write("day 1/IMG_0001.JPG", first);
        write("day 1/IMG_0002.JPG", TestImages.jpeg(600, 400, 2));
        write("IMG_0003.png", TestImages.png(300, 200));
        write("IMG_0004.JPG", TestImages.jpeg(600, 400, 4));
        write("IMG_0005.JPG", TestImages.jpeg(600, 400, 5));
        long run = readAll();
        Map<String, ImportStore.Item> before = items(run);
        edits.assign(run, List.of(before.get("day 1/IMG_0001.JPG").id(), before.get("day 1/IMG_0002.JPG").id(),
                before.get("IMG_0003.png").id(), before.get("IMG_0005.JPG").id()), "A5");
        edits.tags(run, List.of(before.get("day 1/IMG_0001.JPG").id()), List.of("male", "upper_wing"), List.of());
        edits.skip(run, List.of(before.get("IMG_0005.JPG").id()), true);
        List<int[]> reports = new ArrayList<>();

        int done = filer(ids -> { }).file(run, (d, t) -> reports.add(new int[] {d, t}), () -> false);

        assertThat(done).isEqualTo(3);
        assertThat(reports).extracting(r -> r[0] + "/" + r[1]).containsExactly("0/3", "3/3");
        assertThat(rows("SELECT file_name, original_name, source, number FROM image ORDER BY id")).containsExactly(
                "Danaus_chrysippus_01.jpg|IMG_0001.JPG|import|1", "Danaus_chrysippus_02.jpg|IMG_0002.JPG|import|2",
                "Danaus_chrysippus_03.png|IMG_0003.png|import|3");
        Map<String, ImportStore.Item> items = items(run);
        ImportStore.Item filed = items.get("day 1/IMG_0001.JPG");
        assertThat(filed.outcome()).isEqualTo("filed");
        assertThat(filed.imageId()).isNotNull();
        assertThat(rows("SELECT t.key FROM image_tag it JOIN tag t ON t.id = it.tag_id WHERE it.image_id = " + filed.imageId()
                + " ORDER BY t.key")).containsExactly("male", "upper_wing");
        assertThat(rows("SELECT COUNT(*) FROM image_meta")).containsExactly("3");
        assertThat(rows("SELECT consumer, status, COUNT(*) FROM image_processing GROUP BY 1, 2 ORDER BY 1"))
                .containsExactly("dimensions|done|3", "thumbnail|done|3");
        assertThat(rows("SELECT width || 'x' || height FROM image WHERE id = " + filed.imageId())).containsExactly("600x400");
        assertThat(thumbnails.lookup(filed.imageId()).file()).as("the preview became the thumbnail").isNotNull();
        assertThat(ImportStore.previewFile(data, run, filed.id(), filed.v())).doesNotExist();
        assertThat(rows("SELECT first_for_species FROM import_item WHERE outcome = 'filed' ORDER BY id"))
                .containsExactly("1", "0", "0");
        assertThat(items.get("IMG_0004.JPG").outcome()).isNull();
        assertThat(items.get("IMG_0005.JPG").outcome()).isNull();
        assertThat(filesUnder(root)).as("the source folder is untouched").containsExactly("IMG_0003.png", "IMG_0004.JPG",
                "IMG_0005.JPG", "day 1/IMG_0001.JPG", "day 1/IMG_0002.JPG");
        assertThat(Files.readAllBytes(root.resolve("day 1/IMG_0001.JPG"))).isEqualTo(first);
        assertThat(filesUnder(data.resolve(".incoming"))).isEmpty();

        ImportStore.Result result = store.result(run);
        assertThat(result.filed()).isEqualTo(3);
        assertThat(result.newSpecies()).isEqualTo(1);
        assertThat(result.speciesCount()).isEqualTo(1);
        assertThat(result.milestone()).isNull();
        assertThat(result.bySpecies()).containsExactly(new ImportStore.SpeciesFiled("A5", "Danaus chrysippus", "Plain Tiger", 3, true));
        assertThat(result.skipped()).isEqualTo(1);
        assertThat(result.left()).isEqualTo(1);
        assertThat(result.lastFiling()).isEqualTo(new ImportStore.LastFiling(3, 1, null, 1));
    }

    @Test
    void eachFiledBatchTellsThePhotoListeners() throws Exception {
        write("IMG_0001.JPG", TestImages.jpeg(64, 48, 31));
        write("IMG_0002.JPG", TestImages.jpeg(64, 48, 32));
        long run = readAll();
        edits.assign(run, store.items(run, null).items().stream().map(ImportStore.Item::id).toList(), "A5");
        AtomicInteger told = new AtomicInteger();
        photos.onChange(told::incrementAndGet);

        filer(ids -> { }).file(run, (d, t) -> { }, () -> false);

        assertThat(told.get()).isEqualTo(1);
    }

    @Test
    void filedPhotosGetEveryFactIncludingTheirPlaceInTheFilingTransaction() throws Exception {
        photos = new PhotoStore(() -> library, processor, TestBundles.CLOCK, thumbnails::request,
                List.of(new Dimensions(), new Place(TestPlaces.bundles().index())));
        write("in alpha.jpg", TestExif.camera().gps(18.5, 73.5, 600).jpeg(TestImages.jpeg(64, 48, 21)));
        write("no gps.jpg", TestImages.jpeg(64, 48, 22));
        long run = readAll();
        edits.assign(run, store.items(run, null).items().stream().map(ImportStore.Item::id).toList(), "A5");

        filer(ids -> { }).file(run, (d, t) -> { }, () -> false);

        assertThat(rows("SELECT original_name, coalesce(place_id, '-'), coalesce(place_source, '-') FROM image"
                + " ORDER BY original_name")).containsExactly("in alpha.jpg|P4|gps", "no gps.jpg|-|-");
        assertThat(rows("SELECT consumer, status, COUNT(*) FROM image_processing GROUP BY 1, 2 ORDER BY 1"))
                .containsExactly("dimensions|done|2", "place|done|2", "thumbnail|done|2");
    }

    @Test
    void photosAreFiledFiftyToATransactionWithProgress() throws Exception {
        for (int i = 0; i < 120; i++) {
            write("IMG_" + i + ".jpg", distinct(1000 + i));
        }
        long run = readAll();
        edits.assign(run, store.items(run, null).items().stream().map(ImportStore.Item::id).toList(), "A6");
        AtomicInteger batches = new AtomicInteger();
        List<String> reports = new ArrayList<>();

        filer(ids -> batches.incrementAndGet()).file(run, (d, t) -> reports.add(d + "/" + t), () -> false);

        assertThat(batches.get()).isEqualTo(3);
        assertThat(reports).containsExactly("0/120", "50/120", "100/120", "120/120");
        assertThat(store.counts(run).filed()).isEqualTo(120);
        assertThat(rows("SELECT MAX(number) FROM image")).containsExactly("120");
    }

    @Test
    void duplicatesInTheLibraryAndCopiesWithinTheFolderFollowB9() throws Exception {
        byte[] same = TestImages.jpeg(64, 48, 11);
        byte[] other = TestImages.jpeg(64, 48, 12);
        byte[] twice = TestImages.jpeg(64, 48, 13);
        photos.add("A5", new ByteArrayInputStream(same), "image/jpeg", "old.jpg", List.of());
        photos.add("A6", new ByteArrayInputStream(other), "image/jpeg", "old2.jpg", List.of());
        write("same.jpg", same);
        write("other.jpg", other);
        write("a/twice.jpg", twice);
        write("b/twice.jpg", twice);
        long run = readAll();
        Map<String, ImportStore.Item> before = items(run);
        edits.assign(run, before.values().stream().map(ImportStore.Item::id).toList(), "A5");

        filer(ids -> { }).file(run, (d, t) -> { }, () -> false);

        Map<String, ImportStore.Item> items = items(run);
        assertThat(items.get("same.jpg").outcome()).isEqualTo("duplicate");
        assertThat(items.get("same.jpg").imageId()).isEqualTo(1L);
        assertThat(items.get("other.jpg").outcome()).isEqualTo("also_elsewhere");
        assertThat(items.get("other.jpg").error()).isEqualTo("Also filed under Striped Tiger (Danaus genutia).");
        ImportStore.Item firstTwice = items.get("a/twice.jpg").id() < items.get("b/twice.jpg").id()
                ? items.get("a/twice.jpg") : items.get("b/twice.jpg");
        ImportStore.Item secondTwice = firstTwice == items.get("a/twice.jpg") ? items.get("b/twice.jpg") : items.get("a/twice.jpg");
        assertThat(firstTwice.outcome()).isEqualTo("filed");
        assertThat(secondTwice.outcome()).isEqualTo("copy_of");
        assertThat(secondTwice.copyOf()).isEqualTo(firstTwice.id());
        assertThat(secondTwice.error()).isEqualTo("Same photo as " + firstTwice.path() + ".");
        assertThat(rows("SELECT COUNT(*) FROM image")).containsExactly("4");
        ImportStore.Result result = store.result(run);
        assertThat(result.filed()).isEqualTo(2);
        assertThat(result.duplicates()).isEqualTo(1);
        assertThat(result.alsoElsewhere()).isEqualTo(1);
        assertThat(result.copies()).isEqualTo(1);
        assertThat(result.newSpecies()).as("A5 had a photo before").isZero();
        assertThat(filesUnder(data.resolve(".incoming"))).isEmpty();
    }

    @Test
    void aFileChangedSinceItWasReadIsFiledWithWhatItHoldsNow() throws Exception {
        write("a.jpg", TestImages.jpeg(64, 48, 21));
        long run = readAll();
        long item = id(run, "a.jpg");
        String readV = items(run).get("a.jpg").v();
        edits.assign(run, List.of(item), "A8");
        byte[] edited = TestImages.jpeg(320, 200, 22);
        write("a.jpg", edited);

        filer(ids -> { }).file(run, (d, t) -> { }, () -> false);

        ImportStore.Item filed = items(run).get("a.jpg");
        assertThat(filed.outcome()).isEqualTo("filed");
        assertThat(filed.v()).isNotEqualTo(readV);
        assertThat(filed.size()).isEqualTo(edited.length);
        assertThat(rows("SELECT width || 'x' || height FROM image")).containsExactly("320x200");
        assertThat(thumbnails.await(filed.imageId(), Duration.ofSeconds(10))).as("made by the queue").isPresent();
    }

    @Test
    void aVanishedOrUnreadableFileFailsAloneAndIsTriedAgainNextTime() throws Exception {
        write("a.jpg", TestImages.jpeg(64, 48, 31));
        byte[] goneBytes = TestImages.jpeg(64, 48, 32);
        write("gone.jpg", goneBytes);
        long run = readAll();
        edits.assign(run, List.of(id(run, "a.jpg"), id(run, "gone.jpg")), "A5");
        Files.delete(root.resolve("gone.jpg"));

        filer(ids -> { }).file(run, (d, t) -> { }, () -> false);

        assertThat(items(run).get("a.jpg").outcome()).isEqualTo("filed");
        assertThat(items(run).get("gone.jpg").outcome()).isEqualTo("failed");
        assertThat(items(run).get("gone.jpg").error()).isEqualTo("The photo is no longer in the folder.");
        assertThat(store.counts(run).problems()).isEqualTo(1);
        assertThat(store.result(run).failed()).isEqualTo(1);

        write("gone.jpg", goneBytes);
        filer(ids -> { }).file(run, (d, t) -> { }, () -> false);
        assertThat(items(run).get("gone.jpg").outcome()).isEqualTo("filed");
        assertThat(store.result(run).failed()).isZero();
    }

    @Test
    void aBatchThatDoesNotCommitPutsEveryFileBackAndIsFiledOnePhotoAtATime() throws Exception {
        for (int i = 0; i < 4; i++) {
            write("IMG_" + i + ".jpg", TestImages.jpeg(32, 32, 40 + i));
        }
        long run = readAll();
        edits.assign(run, store.items(run, null).items().stream().map(ImportStore.Item::id).toList(), "A5");
        long refused = id(run, "IMG_2.jpg");
        List<List<String>> speciesFolderDuringFailure = new ArrayList<>();
        Path folder = data.resolve("images/Nymphalidae/Danainae/Danaini/Danaus/Danaus_chrysippus_[A5]");

        AtomicInteger refusals = new AtomicInteger();
        filer(ids -> {
            // The batch fails, and so does the refused photo's own transaction (only its failure is then recorded).
            if (ids.size() > 1 || (ids.contains(refused) && refusals.getAndIncrement() == 0)) {
                try {
                    speciesFolderDuringFailure.add(filesUnder(folder));
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
                throw new IllegalStateException("the disk is full");
            }
        }).file(run, (d, t) -> { }, () -> false);

        assertThat(speciesFolderDuringFailure.get(0)).as("the files were placed before the commit failed").hasSize(4);
        assertThat(store.counts(run).filed()).isEqualTo(3);
        assertThat(items(run).get("IMG_2.jpg").outcome()).isEqualTo("failed");
        assertThat(items(run).get("IMG_2.jpg").error()).isEqualTo("Butterfly Atlas couldn't file this photo.");
        assertThat(filesUnder(folder)).as("only the committed photos are in the library").hasSize(3);
        assertThat(rows("SELECT COUNT(*) FROM image")).containsExactly("3");
        assertThat(filesUnder(data.resolve(".incoming"))).isEmpty();
    }

    /** What SQLite throws once its busy timeout has run out, wrapped as the database wraps it. */
    private static RuntimeException busy() {
        return new com.butterflyatlas.app.db.DatabaseException("write failed", new org.sqlite.SQLiteException(
                "[SQLITE_BUSY] The database file is locked (database is locked)", org.sqlite.SQLiteErrorCode.SQLITE_BUSY));
    }

    @Test
    void aPhotoTheDatabaseStaysBusyForIsTriedThreeMoreTimesThenRecordedInTheBusyWords() throws Exception {
        for (int i = 0; i < 3; i++) {
            write("IMG_" + i + ".jpg", TestImages.jpeg(32, 32, 60 + i));
        }
        long run = readAll();
        edits.assign(run, store.items(run, null).items().stream().map(ImportStore.Item::id).toList(), "A5");
        long twice = id(run, "IMG_1.jpg");
        long always = id(run, "IMG_2.jpg");
        AtomicInteger twiceTries = new AtomicInteger();
        AtomicInteger alwaysTries = new AtomicInteger();

        filer(ids -> {
            if (ids.size() > 1) {
                throw busy(); // the batch, then each photo alone
            }
            if (ids.contains(twice) && twiceTries.incrementAndGet() <= 2) {
                throw busy();
            }
            if (ids.contains(always) && alwaysTries.incrementAndGet() <= 4) {
                throw busy(); // its record of the failure (the fifth write) goes through
            }
        }).file(run, (d, t) -> { }, () -> false);

        assertThat(items(run).get("IMG_0.jpg").outcome()).isEqualTo("filed");
        assertThat(items(run).get("IMG_1.jpg").outcome()).as("busy twice, then filed").isEqualTo("filed");
        assertThat(items(run).get("IMG_2.jpg").outcome()).isEqualTo("failed");
        assertThat(items(run).get("IMG_2.jpg").error()).isEqualTo("Another window is saving changes. Try again in a moment.");
        assertThat(alwaysTries.get()).as("one try and three more, then its record").isEqualTo(5);
        assertThat(rows("SELECT COUNT(*) FROM image")).containsExactly("2");
        assertThat(filesUnder(data.resolve(".incoming"))).isEmpty();
    }

    @Test
    void pausingStopsAfterTheCurrentBatchAndTheNextFilingCarriesOn() throws Exception {
        for (int i = 0; i < 70; i++) {
            write("IMG_" + i + ".jpg", distinct(2000 + i));
        }
        long run = readAll();
        edits.assign(run, store.items(run, null).items().stream().map(ImportStore.Item::id).toList(), "A5");
        AtomicInteger batches = new AtomicInteger();
        ImportFiler filer = filer(ids -> batches.incrementAndGet());

        filer.file(run, (d, t) -> { }, () -> batches.get() >= 1);

        assertThat(store.counts(run).filed()).isEqualTo(50);
        assertThat(filer.hasWork(run)).isTrue();
        filer.file(run, (d, t) -> { }, () -> false);
        assertThat(store.counts(run).filed()).isEqualTo(70);
        assertThat(filer.hasWork(run)).isFalse();
        assertThat(rows("SELECT COUNT(DISTINCT number) FROM image")).containsExactly("70");
    }

    @Test
    void aStopIsHeardBeforeEachPhotoAndTheCopiesAlreadyMadeAreFiled() throws Exception {
        for (int i = 0; i < 6; i++) {
            write("IMG_" + i + ".jpg", distinct(3000 + i));
        }
        long run = readAll();
        edits.assign(run, store.items(run, null).items().stream().map(ImportStore.Item::id).toList(), "A5");
        AtomicInteger opened = new AtomicInteger();
        List<String> reports = new ArrayList<>();
        ImportFiler filer = filer(ids -> { }, (p, follow) -> {
            opened.incrementAndGet();
            return ImportFiler.Opener.NIO.open(p, follow);
        }, null, null);

        int done = filer.file(run, (d, t) -> reports.add(d + "/" + t), () -> opened.get() >= 3);

        assertThat(done).isEqualTo(3);
        assertThat(opened.get()).as("no photo copied after the stop").isEqualTo(3);
        assertThat(store.counts(run).filed()).isEqualTo(3);
        assertThat(store.items(run, null).items()).extracting(ImportStore.Item::outcome)
                .containsExactly("filed", "filed", "filed", null, null, null);
        assertThat(reports).containsExactly("0/6", "3/6");
        assertThat(filesUnder(data.resolve(".incoming"))).isEmpty();
        assertThat(filer.hasWork(run)).isTrue();
    }

    /** The crash: the photo moved into the species folder, the app died before the commit, and the next start's rescan registered it. */
    private long strandedByTheRescan(byte[] bytes, String name) throws Exception {
        return strandedByTheRescan(bytes, name, "2026-10-09T08:00:00Z");
    }

    private long strandedByTheRescan(byte[] bytes, String name, String addedAt) throws Exception {
        Path folder = Files.createDirectories(data.resolve("images/Nymphalidae/Danainae/Danaini/Danaus/Danaus_chrysippus_[A5]"));
        Files.write(folder.resolve(name), bytes);
        String sha = ImportReaderTest.sha256(bytes);
        return library.database().write(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO image (taxon_id, rel_path, file_name, file_size,"
                    + " modified_at, sha256, added_at, source) VALUES ('A5', ?, ?, ?, ?, ?, ?, 'rescan')",
                    PreparedStatement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, "images/Nymphalidae/Danainae/Danaini/Danaus/Danaus_chrysippus_[A5]/" + name);
                ps.setString(2, name);
                ps.setLong(3, bytes.length);
                ps.setString(4, "2026-10-09T08:00:00Z");
                ps.setString(5, sha);
                ps.setString(6, addedAt);
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    keys.next();
                    return keys.getLong(1);
                }
            }
        });
    }

    /** A filing of the import began (its number is committed before any batch): as before the app was killed. */
    private void aFilingBegan(long run) {
        library.database().write(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE import_run SET filings = filings + 1 WHERE id = ?")) {
                ps.setLong(1, run);
                ps.executeUpdate();
            }
            return null;
        });
    }

    @Test
    void anItemsChosenPlaceIsFiledAsTheUsersAndThePlaceConsumerLeavesItAlone() throws Exception {
        photos = new PhotoStore(() -> library, processor, TestBundles.CLOCK, thumbnails::request,
                List.of(new Dimensions(), new Place(TestPlaces.bundles().index())));
        write("chosen.jpg", TestExif.camera().gps(18.5, 73.5, 600).jpeg(TestImages.jpeg(64, 48, 23)));
        write("removed.jpg", TestExif.camera().gps(18.5, 73.6, 600).jpeg(TestImages.jpeg(64, 48, 24)));
        write("gps.jpg", TestExif.camera().gps(18.5, 73.7, 600).jpeg(TestImages.jpeg(64, 48, 25)));
        write("no gps.jpg", TestImages.jpeg(64, 48, 26));
        long run = readAll();
        edits.assign(run, store.items(run, null).items().stream().map(ImportStore.Item::id).toList(), "A5");
        edits.place(run, List.of(id(run, "chosen.jpg"), id(run, "no gps.jpg")), "P5", false);
        edits.place(run, List.of(id(run, "removed.jpg")), null, true);

        filer(ids -> { }).file(run, (d, t) -> { }, () -> false);

        assertThat(rows("SELECT original_name, coalesce(place_id, '-'), coalesce(place_source, '-') FROM image"
                + " ORDER BY original_name")).containsExactly("chosen.jpg|P5|user", "gps.jpg|P4|gps", "no gps.jpg|P5|user",
                "removed.jpg|-|user");
        assertThat(rows("SELECT consumer, status, COUNT(*) FROM image_processing GROUP BY 1, 2 ORDER BY 1"))
                .containsExactly("dimensions|done|4", "place|done|4", "thumbnail|done|4");
    }

    @Test
    void aPhotoAFilingLeftBehindWhenTheAppWasKilledIsTakenOverNotCalledADuplicate() throws Exception {
        byte[] bytes = TestImages.jpeg(64, 48, 71);
        write("a.jpg", bytes);
        write("b.jpg", TestImages.jpeg(64, 48, 72));
        long run = readAll();
        long a = id(run, "a.jpg");
        long b = id(run, "b.jpg");
        edits.assign(run, List.of(a, b), "A5");
        edits.tags(run, List.of(a), List.of("female"), List.of());
        // The crash: the batch moved a.jpg into the species folder, the app died before the commit, and the next start's
        // rescan registered the file (source 'rescan', its own name, no tags); the item has no outcome.
        aFilingBegan(run);
        long stranded = strandedByTheRescan(bytes, "Danaus_chrysippus_01.jpg");
        String sha = ImportReaderTest.sha256(bytes);

        filer(ids -> { }).file(run, (d, t) -> { }, () -> false);

        ImportStore.Item item = items(run).get("a.jpg");
        assertThat(item.outcome()).isEqualTo("filed");
        assertThat(item.imageId()).isEqualTo(stranded);
        assertThat(rows("SELECT t.key FROM image_tag it JOIN tag t ON t.id = it.tag_id WHERE it.image_id = " + stranded))
                .containsExactly("female");
        assertThat(rows("SELECT width || 'x' || height FROM image WHERE id = " + stranded)).containsExactly("64x48");
        assertThat(rows("SELECT consumer, status FROM image_processing WHERE image_id = " + stranded + " ORDER BY consumer"))
                .startsWith("dimensions|done");
        assertThat(rows("SELECT COUNT(*) FROM image WHERE sha256 = '" + sha + "'")).as("not filed twice").containsExactly("1");
        assertThat(items(run).get("b.jpg").outcome()).isEqualTo("filed");
        ImportStore.Result result = store.result(run);
        assertThat(result.filed()).isEqualTo(2);
        assertThat(result.duplicates()).isZero();
        assertThat(result.newSpecies()).isEqualTo(1);
        assertThat(rows("SELECT species_before || '/' || last_species_before FROM import_run WHERE id = " + run))
                .as("the photo left behind goes in with this filing").containsExactly("0/0");
    }

    @Test
    void twoPhotosAKilledBatchLeftOfANewSpeciesMakeOneNewSpecies() throws Exception {
        byte[] first = TestImages.jpeg(64, 48, 76);
        byte[] second = TestImages.jpeg(64, 48, 77);
        write("a.jpg", first);
        write("b.jpg", second);
        long run = readAll();
        edits.assign(run, List.of(id(run, "a.jpg"), id(run, "b.jpg")), "A5");
        aFilingBegan(run);
        strandedByTheRescan(first, "Danaus_chrysippus_01.jpg");
        strandedByTheRescan(second, "Danaus_chrysippus_02.jpg");

        filer(ids -> { }).file(run, (d, t) -> { }, () -> false);

        ImportStore.Result result = store.result(run);
        assertThat(result.filed()).isEqualTo(2);
        assertThat(result.newSpecies()).as("the other photo left behind does not count as already there").isEqualTo(1);
        assertThat(result.lastFiling().newSpecies()).isEqualTo(1);
        assertThat(rows("SELECT SUM(first_for_species) FROM import_item WHERE run_id = " + run)).containsExactly("1");
        assertThat(rows("SELECT species_before FROM import_run WHERE id = " + run)).containsExactly("0");
        assertThat(result.speciesCount()).isEqualTo(1);
    }

    @Test
    void aPhotoTakenOverLooksLikeAFilingAndIsNeverTakenOverAgain() throws Exception {
        byte[] bytes = TestImages.jpeg(64, 48, 78);
        write("a.jpg", bytes);
        write("b.jpg", TestImages.jpeg(64, 48, 79));
        long run = readAll();
        edits.assign(run, List.of(id(run, "a.jpg"), id(run, "b.jpg")), "A5");
        aFilingBegan(run);
        long stranded = strandedByTheRescan(bytes, "Danaus_chrysippus_05.jpg");

        filer(ids -> { }).file(run, (d, t) -> { }, () -> false);

        assertThat(rows("SELECT source, original_name, number FROM image WHERE id = " + stranded))
                .containsExactly("import|a.jpg|5");
        assertThat(rows("SELECT file_name FROM image WHERE id = " + items(run).get("b.jpg").imageId()))
                .as("numbers go on after the photo taken over").containsExactly("Danaus_chrysippus_06.jpg");

        store.delete(run); // the import goes; its photos stay in the library, claimed by no item
        long again = readAll();
        edits.assign(again, List.of(id(again, "a.jpg")), "A5");
        aFilingBegan(again);
        filer(ids -> { }).file(again, (d, t) -> { }, () -> false);

        assertThat(items(again).get("a.jpg").outcome()).as("filed by an import, not left behind").isEqualTo("duplicate");
    }

    @Test
    void aPhotoLeftBehindIsRecognisedWhateverTheTimeStampsPrecision() throws Exception {
        byte[] bytes = TestImages.jpeg(64, 48, 80);
        write("a.jpg", bytes);
        long run = readAll();
        edits.assign(run, List.of(id(run, "a.jpg")), "A5");
        aFilingBegan(run);
        // Registered half a second after the import was created ("...00Z"): later in time, earlier as text.
        long stranded = strandedByTheRescan(bytes, "Danaus_chrysippus_01.jpg", "2026-10-09T08:00:00.500Z");

        filer(ids -> { }).file(run, (d, t) -> { }, () -> false);

        assertThat(items(run).get("a.jpg").imageId()).isEqualTo(stranded);
        assertThat(items(run).get("a.jpg").outcome()).isEqualTo("filed");
    }

    @Test
    void aPhotoAddedByTheUserIsStillADuplicate() throws Exception {
        byte[] bytes = TestImages.jpeg(64, 48, 73);
        photos.add("A5", new ByteArrayInputStream(bytes), "image/jpeg", "mine.jpg", List.of());
        write("a.jpg", bytes);
        long run = readAll();
        edits.assign(run, List.of(id(run, "a.jpg")), "A5");
        aFilingBegan(run);

        filer(ids -> { }).file(run, (d, t) -> { }, () -> false);

        assertThat(items(run).get("a.jpg").outcome()).as("added with Add photos, not left by this import").isEqualTo("duplicate");
    }

    @Test
    void aPhotoTheRescanFoundBeforeTheImportEverFiledIsStillADuplicate() throws Exception {
        byte[] bytes = TestImages.jpeg(64, 48, 74);
        write("a.jpg", bytes);
        long run = readAll();
        edits.assign(run, List.of(id(run, "a.jpg")), "A5");
        // The user copied the same photo into the species folder themselves: no filing of this import could have left it.
        long theirs = strandedByTheRescan(bytes, "copied by hand.jpg");

        filer(ids -> { }).file(run, (d, t) -> { }, () -> false);

        assertThat(items(run).get("a.jpg").outcome()).isEqualTo("duplicate");
        assertThat(rows("SELECT COUNT(*) FROM image_tag WHERE image_id = " + theirs)).containsExactly("0");
    }

    @Test
    void aPhotoAnotherItemOfTheImportClaimsIsNotTakenOver() throws Exception {
        byte[] bytes = TestImages.jpeg(64, 48, 75);
        write("a.jpg", bytes);
        long run = readAll();
        edits.assign(run, List.of(id(run, "a.jpg")), "A5");
        aFilingBegan(run);
        long stranded = strandedByTheRescan(bytes, "Danaus_chrysippus_01.jpg");
        long other = store.create(Files.createDirectories(dir.resolve("Other"))).id();
        library.database().write(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO import_item (run_id, rel_path, file_size, modified_at,"
                    + " read_state, outcome, image_id, seq) VALUES (?, 'x.jpg', 1, '2026-10-09T08:00:00Z', 'read', 'filed', ?, 1)")) {
                ps.setLong(1, other);
                ps.setLong(2, stranded);
                ps.executeUpdate();
            }
            return null;
        });

        filer(ids -> { }).file(run, (d, t) -> { }, () -> false);

        assertThat(items(run).get("a.jpg").outcome()).isEqualTo("duplicate");
    }

    @Test
    void filingStopsWhenTheFolderDisappears() throws Exception {
        write("a.jpg", TestImages.jpeg(16, 16, 51));
        long run = readAll();
        edits.assign(run, List.of(id(run, "a.jpg")), "A5");
        Files.delete(root.resolve("a.jpg"));
        Files.delete(root);

        filer(ids -> { }).file(run, (d, t) -> { }, () -> false);

        assertThat(items(run).get("a.jpg").outcome()).as("not failed: the drive may come back").isNull();
    }

    @Test
    void aFolderLeftWithNoPhotosIsLikeAMissingOneAndNoPhotoIsBlamed() throws Exception {
        write("day 1/a.jpg", TestImages.jpeg(16, 16, 52));
        write("b.jpg", TestImages.jpeg(16, 16, 53));
        long run = readAll();
        edits.assign(run, List.of(id(run, "day 1/a.jpg"), id(run, "b.jpg")), "A5");
        Files.delete(root.resolve("day 1/a.jpg"));
        Files.delete(root.resolve("b.jpg"));
        Files.write(root.resolve("notes.txt"), new byte[] {1}); // the folder is there, its photos are not

        filer(ids -> { }).file(run, (d, t) -> { }, () -> false);

        assertThat(items(run).values()).extracting(ImportStore.Item::outcome).containsOnlyNulls();
    }

    @Test
    void whetherTheFolderStillHoldsPhotosIsAskedAtMostOncePerBatch() throws Exception {
        write("keep.jpg", TestImages.jpeg(16, 16, 55)); // not assigned: the folder still holds a photo
        List<Long> assigned = new ArrayList<>();
        for (int n = 1; n <= ImportFiler.BATCH; n++) {
            write("gone " + n + ".jpg", TestImages.jpeg(16, 16, 100 + n));
        }
        long run = readAll();
        items(run).values().stream().filter(i -> i.name().startsWith("gone")).forEach(i -> assigned.add(i.id()));
        edits.assign(run, assigned, "A5");
        for (int n = 1; n <= ImportFiler.BATCH; n++) {
            Files.delete(root.resolve("gone " + n + ".jpg"));
        }
        AtomicInteger checks = new AtomicInteger();
        ImportFiler counted = new ImportFiler(() -> library, photos, processor, thumbnails, TestBundles.CLOCK, ids -> { },
                ImportFiler.Opener.NIO, new PhotoIngest(processor, photos.maxBytes())::ingest, folder -> Long.MAX_VALUE,
                (folder, data) -> {
                    checks.incrementAndGet();
                    return ImportWalker.hasListedFiles(folder, data);
                });

        counted.file(run, (d, t) -> { }, () -> false);

        assertThat(checks).as("one look at the folder for the whole batch").hasValue(1);
        assertThat(items(run).values()).filteredOn(i -> i.name().startsWith("gone")).extracting(ImportStore.Item::error)
                .containsOnly(ImportFiler.GONE);
    }

    @Test
    void aFilingStoppedBeforeItBeganIsNotCounted() throws Exception {
        write("a.jpg", TestImages.jpeg(16, 16, 54));
        long run = readAll();
        edits.assign(run, List.of(id(run, "a.jpg")), "A5");

        assertThat(filer(ids -> { }).file(run, (d, t) -> { }, () -> true)).isZero();

        assertThat(rows("SELECT filings FROM import_run WHERE id = " + run)).containsExactly("0");
        assertThat(store.result(run).lastFiling()).isNull();
    }

    @Test
    void theResultNamesTheMilestoneThisImportCrossed() throws Exception {
        for (int i = 1; i <= 9; i++) {
            TestBundles.addManualSpecies(library.database(), "U" + i, "Manual species " + i);
            TestBundles.addImage(library.database(), "U" + i);
        }
        write("a.jpg", TestImages.jpeg(16, 16, 61));
        long run = readAll();
        edits.assign(run, List.of(id(run, "a.jpg")), "A12");

        filer(ids -> { }).file(run, (d, t) -> { }, () -> false);

        ImportStore.Result result = store.result(run);
        assertThat(result.speciesCount()).isEqualTo(10);
        assertThat(result.milestone()).isEqualTo(10);
        assertThat(result.newSpecies()).isEqualTo(1);
        assertThat(result.lastFiling()).isEqualTo(new ImportStore.LastFiling(1, 1, 10, 10));

        write("b.jpg", TestImages.jpeg(16, 16, 62));
        walker.walk(run, n -> { }, () -> false).walked();
        reader.read(run, (d, t) -> { }, () -> false);
        edits.assign(run, List.of(id(run, "b.jpg")), "A13");
        filer(ids -> { }).file(run, (d, t) -> { }, () -> false);
        assertThat(store.result(run).lastFiling()).as("only the latest filing").isEqualTo(new ImportStore.LastFiling(1, 1, null, 11));
        assertThat(store.result(run).milestone()).as("the whole import still crossed 10").isEqualTo(10);
        assertThat(store.result(run).filed()).isEqualTo(2);
    }

    @Test
    void beforeAnyFilingThereIsNoLastFiling() throws Exception {
        write("a.jpg", TestImages.jpeg(16, 16, 63));
        long run = readAll();

        assertThat(store.result(run).lastFiling()).isNull();
        assertThat(filer(ids -> { }).file(run, (d, t) -> { }, () -> false)).isZero();
        assertThat(store.result(run).lastFiling()).as("nothing was assigned: no filing happened").isNull();
    }
    private static java.nio.file.attribute.BasicFileAttributes attrs(boolean regular, boolean other, boolean folder, boolean link) {
        return new java.nio.file.attribute.BasicFileAttributes() {
            public java.nio.file.attribute.FileTime lastModifiedTime() { return java.nio.file.attribute.FileTime.fromMillis(0); }
            public java.nio.file.attribute.FileTime lastAccessTime() { return lastModifiedTime(); }
            public java.nio.file.attribute.FileTime creationTime() { return lastModifiedTime(); }
            public boolean isRegularFile() { return regular; }
            public boolean isDirectory() { return folder; }
            public boolean isSymbolicLink() { return link; }
            public boolean isOther() { return other; }
            public long size() { return 5; }
            public Object fileKey() { return null; }
        };
    }

    @Test
    void aCloudPlaceholderIsFiledOnWindowsAndALinkOrFolderInThePhotosPlaceIsNot() {
        assertThat(ImportFiler.kind(attrs(true, false, false, false), false)).isEqualTo(ImportFiler.SourceKind.FILE);
        assertThat(ImportFiler.kind(attrs(true, false, false, false), true)).isEqualTo(ImportFiler.SourceKind.FILE);
        assertThat(ImportFiler.kind(attrs(false, true, false, false), true)).as("a OneDrive placeholder")
                .isEqualTo(ImportFiler.SourceKind.PLACEHOLDER);
        assertThat(ImportFiler.kind(attrs(false, true, false, false), false)).as("a FIFO or device")
                .isEqualTo(ImportFiler.SourceKind.NOT_A_FILE);
        assertThat(ImportFiler.kind(attrs(false, false, false, true), true)).isEqualTo(ImportFiler.SourceKind.NOT_A_FILE);
        assertThat(ImportFiler.kind(attrs(false, false, true, false), true)).isEqualTo(ImportFiler.SourceKind.NOT_A_FILE);
    }

    @Test
    void onlyAPlaceholderIsOpenedFollowingLinks() throws Exception {
        write("a.jpg", TestImages.jpeg(64, 48, 81));
        long run = readAll();
        edits.assign(run, List.of(id(run, "a.jpg")), "A5");
        List<Boolean> follows = new ArrayList<>();

        filer(ids -> { }, (p, follow) -> {
            follows.add(follow);
            return ImportFiler.Opener.NIO.open(p, follow);
        }, null, null).file(run, (d, t) -> { }, () -> false);

        assertThat(follows).containsExactly(false);
        assertThat(items(run).get("a.jpg").outcome()).isEqualTo("filed");
    }

    @Test
    void aSourceThatFailsPartWayIsUnreadableNotAnInterruptedUpload() throws Exception {
        write("a.jpg", TestImages.jpeg(64, 48, 82));
        long run = readAll();
        edits.assign(run, List.of(id(run, "a.jpg")), "A5");

        filer(ids -> { }, (p, follow) -> failingPartWay(() -> { }), null, null).file(run, (d, t) -> { }, () -> false);

        assertThat(items(run).get("a.jpg").outcome()).isEqualTo("failed");
        assertThat(items(run).get("a.jpg").error()).isEqualTo(ImportReader.UNREADABLE);
        assertThat(rows("SELECT COUNT(*) FROM image")).containsExactly("0");
        assertThat(filesUnder(data.resolve(".incoming"))).isEmpty();
    }

    @Test
    void aSourceThatVanishesPartWayIsNoLongerInTheFolder() throws Exception {
        write("a.jpg", TestImages.jpeg(64, 48, 83));
        write("b.jpg", TestImages.jpeg(64, 48, 84));
        long run = readAll();
        edits.assign(run, List.of(id(run, "a.jpg")), "A5");

        filer(ids -> { }, (p, follow) -> failingPartWay(() -> Files.delete(p)), null, null)
                .file(run, (d, t) -> { }, () -> false);

        assertThat(items(run).get("a.jpg").outcome()).isEqualTo("failed");
        assertThat(items(run).get("a.jpg").error()).isEqualTo(ImportFiler.GONE);
    }

    @Test
    void aFolderThatVanishesPartWayLeavesItsPhotosUntouched() throws Exception {
        write("a.jpg", TestImages.jpeg(64, 48, 85));
        write("b.jpg", TestImages.jpeg(64, 48, 86));
        long run = readAll();
        edits.assign(run, List.of(id(run, "a.jpg"), id(run, "b.jpg")), "A5");
        Path b = root.resolve("b.jpg");

        int done = filer(ids -> { }, (p, follow) -> failingPartWay(() -> {
            Files.delete(p);
            Files.deleteIfExists(b);
            Files.delete(root);
        }), null, null).file(run, (d, t) -> { }, () -> false);

        assertThat(done).isZero();
        assertThat(items(run).get("a.jpg").outcome()).as("the drive may come back").isNull();
        assertThat(items(run).get("b.jpg").outcome()).isNull();
        assertThat(filesUnder(data.resolve(".incoming"))).isEmpty();
    }

    @Test
    void aLibraryDriveWithoutRoomStopsFilingAndBlamesNoPhoto() throws Exception {
        write("a.jpg", TestImages.jpeg(64, 48, 87));
        long run = readAll();
        edits.assign(run, List.of(id(run, "a.jpg")), "A5");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> filer(ids -> { }, null, null, p -> 1000L)
                        .file(run, (d, t) -> { }, () -> false))
                .isInstanceOfSatisfying(AtlasException.class, e -> {
                    assertThat(e.code()).isEqualTo("library_full");
                    assertThat(e.kind()).isEqualTo(AtlasException.Kind.CONFLICT);
                    assertThat(e.getMessage()).isEqualTo(
                            "Your library's drive is full. Free up some space, then continue filing.");
                });

        assertThat(items(run).get("a.jpg").outcome()).isNull();
        assertThat(rows("SELECT COUNT(*) FROM image")).containsExactly("0");
        assertThat(filesUnder(data.resolve(".incoming"))).isEmpty();
    }

    @Test
    void aCopyThatCannotBeWrittenIntoTheLibraryStopsFilingAndBlamesNoPhoto() throws Exception {
        for (int i = 0; i < 70; i++) {
            write("IMG_" + i + ".jpg", distinct(3000 + i));
        }
        long run = readAll();
        edits.assign(run, store.items(run, null).items().stream().map(ImportStore.Item::id).toList(), "A5");
        PhotoIngest ingest = new PhotoIngest(processor, photos.maxBytes());
        AtomicInteger copies = new AtomicInteger();

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> filer(ids -> { }, null, (in, target, name) -> {
                    if (copies.incrementAndGet() == 55) {
                        Files.write(target, new byte[] {1, 2, 3});
                        throw new IOException("Access is denied");
                    }
                    return ingest.ingest(in, target, name);
                }, null).file(run, (d, t) -> { }, () -> false))
                .isInstanceOfSatisfying(AtlasException.class, e -> assertThat(e.code()).isEqualTo("library_unwritable"));

        assertThat(store.counts(run).filed()).as("the first batch is kept").isEqualTo(50);
        assertThat(store.counts(run).problems()).isZero();
        assertThat(rows("SELECT COUNT(*) FROM import_item WHERE outcome IS NULL")).containsExactly("20");
        assertThat(filesUnder(data.resolve(".incoming"))).isEmpty();
    }

    @Test
    void aPhotoThatCouldNotBePutBackIsLeftUntouchedSoItIsNeverCopiedTwiceInOneFiling() throws Exception {
        for (int i = 0; i < 4; i++) {
            write("IMG_" + i + ".jpg", TestImages.jpeg(32, 32, 90 + i));
        }
        long run = readAll();
        edits.assign(run, store.items(run, null).items().stream().map(ImportStore.Item::id).toList(), "A5");
        Path folder = data.resolve("images/Nymphalidae/Danainae/Danaini/Danaus/Danaus_chrysippus_[A5]");

        filer(ids -> {
            if (ids.size() > 1) {
                try {
                    Files.delete(folder.resolve("Danaus_chrysippus_02.jpg")); // so it cannot be moved back
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
                throw new IllegalStateException("the disk is full");
            }
        }).file(run, (d, t) -> { }, () -> false);

        assertThat(store.counts(run).filed()).isEqualTo(3);
        assertThat(store.counts(run).problems()).isZero();
        assertThat(rows("SELECT COUNT(*) FROM import_item WHERE outcome IS NULL")).containsExactly("1");
        assertThat(rows("SELECT COUNT(*) FROM image")).containsExactly("3");
    }

    @Test
    void theSpeciesCountBeforeIsTakenOnlyWhenAPhotoIsFiled() throws Exception {
        byte[] same = TestImages.jpeg(64, 48, 95);
        photos.add("A5", new ByteArrayInputStream(same), "image/jpeg", "old.jpg", List.of());
        write("same.jpg", same);
        write("new.jpg", TestImages.jpeg(64, 48, 96));
        long run = readAll();
        edits.assign(run, List.of(id(run, "same.jpg")), "A5");

        filer(ids -> { }).file(run, (d, t) -> { }, () -> false);
        assertThat(rows("SELECT species_before FROM import_run WHERE id = " + run)).as("only a duplicate").containsExactly("null");

        edits.assign(run, List.of(id(run, "new.jpg")), "A6");
        filer(ids -> { }).file(run, (d, t) -> { }, () -> false);
        assertThat(rows("SELECT species_before FROM import_run WHERE id = " + run)).containsExactly("1");
    }

    @Test
    void aLinkPutInThePhotosPlaceIsNotFollowed() throws Exception {
        write("a.jpg", TestImages.jpeg(64, 48, 71));
        Path elsewhere = Files.write(dir.resolve("elsewhere.jpg"), TestImages.jpeg(64, 48, 72));
        long run = readAll();
        edits.assign(run, List.of(id(run, "a.jpg")), "A5");
        Files.delete(root.resolve("a.jpg"));
        try {
            Files.createSymbolicLink(root.resolve("a.jpg"), elsewhere);
        } catch (IOException | UnsupportedOperationException e) {
            org.junit.jupiter.api.Assumptions.abort("links cannot be made here: " + e);
        }

        filer(ids -> { }).file(run, (d, t) -> { }, () -> false);

        assertThat(items(run).get("a.jpg").outcome()).isEqualTo("failed");
        assertThat(items(run).get("a.jpg").error()).isEqualTo(ImportFiler.GONE);
        assertThat(rows("SELECT COUNT(*) FROM image")).containsExactly("0");
    }

    /** A RAW + JPEG pair of {@code name} (fixes J3); both read, so it forms a pair. */
    private void pair(String name, int seed) throws IOException {
        write(name + ".NEF", TestRaw.tiff(new TestRaw.Ifd().jpeg(TestImages.jpeg(1024, 768, seed))));
        write(name + ".JPG", TestImages.jpeg(64, 48, seed));
    }

    /** A RAW + HEIC pair of {@code name} (Phase 7b): a camera's RAW beside the HEIC it also wrote. */
    private void heicPair(String name, int seed) throws IOException {
        write(name + ".NEF", TestRaw.tiff(new TestRaw.Ifd().jpeg(TestImages.jpeg(1024, 768, seed))));
        write(name + ".HIF", TestHeif.still(640 + seed, 480).brands("heix", "mif1", "heix").build());
    }

    @Test
    void rawPlusHeicPairsFormAndFileAsChosenInTheirOwnWords() throws Exception {
        heicPair("A", 201);
        heicPair("B", 202);
        long run = readAll();

        assertThat(items(run).get("A.HIF").pairId()).isEqualTo(id(run, "A.NEF"));
        assertThat(store.pairs(run)).isEqualTo(new ImportStore.Pairs(2, 0, null, false, List.of("heic")));
        edits.assign(run, List.of(id(run, "A.HIF"), id(run, "B.NEF")), "A5");
        assertThat(store.pairs(run).toFile()).isEqualTo(2);
        store.setPairChoice(run, ImportPairs.JPEG); // the JPEG or HEIC half

        filer(ids -> { }).file(run, (d, t) -> { }, () -> false);

        assertThat(half(run, "A.HIF")).isEqualTo("filed|0|-");
        assertThat(half(run, "A.NEF")).isEqualTo("-|1|The RAW of this pair was not filed: you chose HEIC only.");
        assertThat(half(run, "B.HIF")).isEqualTo("filed|0|-");
        assertThat(rows("SELECT file_name FROM image ORDER BY id")).allMatch(n -> n.endsWith(".heic"));
    }

    @Test
    void choosingRawLeavesTheHeicOutAndBothKindsAreNamedTogether() throws Exception {
        heicPair("A", 211);
        pair("B", 212);
        long run = readAll();
        assertThat(store.pairs(run).kinds()).containsExactly("jpeg", "heic");
        edits.assign(run, List.of(id(run, "A.HIF"), id(run, "B.JPG")), "A5");
        store.setPairChoice(run, ImportPairs.RAW);

        filer(ids -> { }).file(run, (d, t) -> { }, () -> false);

        assertThat(half(run, "A.HIF")).isEqualTo("-|1|The HEIC of this pair was not filed: you chose RAW only.");
        assertThat(half(run, "B.JPG")).isEqualTo("-|1|The JPEG of this pair was not filed: you chose RAW only.");
        assertThat(ImportPairs.leftOut("raw", List.of("jpeg", "heic")))
                .isEqualTo("The RAW of this pair was not filed: you chose JPEG or HEIC only.");
        assertThat(ImportPairs.choiceNeeded(120, List.of("heic")).getMessage())
                .isEqualTo("Choose how to file the 120 RAW + HEIC pairs first.");
        assertThat(ImportPairs.choiceNeeded(340, List.of("jpeg", "heic")).getMessage())
                .isEqualTo("Choose how to file the 340 RAW + JPEG or RAW + HEIC pairs first.");
    }

    @Test
    void aJpegAndAHeicOfOneNameAreNotAPair() throws Exception {
        write("C.JPG", TestImages.jpeg(64, 48, 221));
        write("C.HEIC", TestHeif.still(640, 480).build());
        long run = readAll();

        assertThat(items(run).get("C.HEIC").pairId()).isNull();
        assertThat(store.pairs(run)).isNull();
    }

    @Test
    void acceptingSuggestionsOfAPairTakesTheHeicHalfs() throws Exception {
        heicPair("A", 231);
        long run = readAll();
        library.database().write(c -> {
            try (var st = c.createStatement()) {
                st.executeUpdate("UPDATE import_item SET suggested_taxon_id = 'A5' WHERE rel_path = 'A.HIF'");
                return st.executeUpdate("UPDATE import_item SET suggested_taxon_id = 'A6' WHERE rel_path = 'A.NEF'");
            }
        });

        edits.acceptSuggestions(run, List.of(id(run, "A.NEF")));

        assertThat(items(run).get("A.NEF").taxonId()).isEqualTo("A5");
        assertThat(items(run).get("A.HIF").taxonId()).isEqualTo("A5");
    }

    /** {@code outcome|pair_left|error} of an item, {@code -} for null. */
    private String half(long run, String path) {
        return rows("SELECT coalesce(outcome, '-') || '|' || pair_left || '|' || coalesce(error, '-') FROM import_item"
                + " WHERE run_id = " + run + " AND rel_path = '" + path + "'").get(0);
    }

    @Test
    void thePairChoiceTakesEffectPerShotInTheTransactionThatFilesTheChosenHalf() throws Exception {
        pair("A", 101);
        pair("B", 102);
        pair("C", 103);
        long run = readAll();
        edits.assign(run, List.of(id(run, "A.JPG"), id(run, "B.JPG"), id(run, "C.JPG")), "A5");
        store.setPairChoice(run, ImportPairs.JPEG);
        AtomicInteger opened = new AtomicInteger();
        ImportFiler filer = filer(ids -> { }, (p, follow) -> {
            opened.incrementAndGet();
            return ImportFiler.Opener.NIO.open(p, follow);
        }, null, null);

        filer.file(run, (d, t) -> { }, () -> opened.get() >= 2);

        assertThat(half(run, "A.JPG")).isEqualTo("filed|0|-");
        assertThat(half(run, "A.NEF")).isEqualTo("-|1|" + ImportPairs.RAW_LEFT);
        assertThat(half(run, "B.NEF")).isEqualTo("-|1|" + ImportPairs.RAW_LEFT);
        assertThat(rows("SELECT COUNT(DISTINCT seq) FROM import_item WHERE rel_path IN ('A.JPG', 'A.NEF')"))
                .as("the same change").containsExactly("1");
        assertThat(half(run, "C.JPG")).as("not filed yet").isEqualTo("-|0|-");
        assertThat(half(run, "C.NEF")).as("left as it was").isEqualTo("-|0|-");
        assertThat(store.pairs(run)).isEqualTo(new ImportStore.Pairs(3, 1, "jpeg", true, List.of("jpeg")));

        filer.file(run, (d, t) -> { }, () -> false);
        assertThat(half(run, "C.NEF")).isEqualTo("-|1|" + ImportPairs.RAW_LEFT);
        assertThat(store.result(run).pairLeft()).isEqualTo(3);
        assertThat(rows("SELECT COUNT(*) FROM image")).containsExactly("3");
    }

    @Test
    void aPauseBeforeAnyCopyLeavesThePairsUntouchedAndTheChoiceChangeable() throws Exception {
        pair("A", 111);
        long run = readAll();
        edits.assign(run, List.of(id(run, "A.JPG")), "A5");
        store.setPairChoice(run, ImportPairs.JPEG);
        AtomicInteger asked = new AtomicInteger();

        filer(ids -> { }).file(run, (d, t) -> { }, () -> asked.incrementAndGet() > 1);

        assertThat(rows("SELECT filings FROM import_run WHERE id = " + run)).as("a filing began").containsExactly("1");
        assertThat(half(run, "A.JPG")).isEqualTo("-|0|-");
        assertThat(half(run, "A.NEF")).isEqualTo("-|0|-");
        assertThat(store.pairs(run)).isEqualTo(new ImportStore.Pairs(1, 1, "jpeg", false, List.of("jpeg")));

        store.setPairChoice(run, ImportPairs.RAW);
        filer(ids -> { }).file(run, (d, t) -> { }, () -> false);
        assertThat(half(run, "A.NEF")).isEqualTo("filed|0|-");
        assertThat(half(run, "A.JPG")).isEqualTo("-|1|" + ImportPairs.JPEG_LEFT);
    }

    @Test
    void aFullDriveInTheFirstBatchLeavesThePairsUntouched() throws Exception {
        pair("A", 121);
        long run = readAll();
        edits.assign(run, List.of(id(run, "A.JPG")), "A5");
        store.setPairChoice(run, ImportPairs.RAW);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> filer(ids -> { }, null, null, p -> 1000L)
                        .file(run, (d, t) -> { }, () -> false))
                .isInstanceOfSatisfying(AtlasException.class, e -> assertThat(e.code()).isEqualTo("library_full"));

        assertThat(half(run, "A.JPG")).isEqualTo("-|0|-");
        assertThat(half(run, "A.NEF")).isEqualTo("-|0|-");
        assertThat(store.pairs(run).locked()).isFalse();
    }

    @Test
    void aFailedCopyOfTheChosenHalfLeavesTheChoiceChangeableAndMarksNothingLeftOut() throws Exception {
        pair("A", 131);
        long run = readAll();
        edits.assign(run, List.of(id(run, "A.JPG")), "A5");
        store.setPairChoice(run, ImportPairs.JPEG);
        Files.delete(root.resolve("A.JPG"));

        filer(ids -> { }).file(run, (d, t) -> { }, () -> false);

        assertThat(half(run, "A.JPG")).isEqualTo("failed|0|" + ImportFiler.GONE);
        assertThat(half(run, "A.NEF")).isEqualTo("-|0|-");
        assertThat(store.pairs(run)).isEqualTo(new ImportStore.Pairs(1, 1, "jpeg", false, List.of("jpeg")));

        store.setPairChoice(run, ImportPairs.RAW);
        filer(ids -> { }).file(run, (d, t) -> { }, () -> false);

        assertThat(half(run, "A.NEF")).isEqualTo("filed|0|-");
        assertThat(half(run, "A.JPG")).as("left out now, no longer a failure").isEqualTo("-|1|" + ImportPairs.JPEG_LEFT);
        assertThat(store.result(run).failed()).isZero();
        assertThat(store.result(run).pairLeft()).isEqualTo(1);
        assertThat(store.counts(run).problems()).isZero();
        assertThat(store.counts(run).filed()).isEqualTo(1);
        assertThat(store.pairs(run)).isEqualTo(new ImportStore.Pairs(1, 0, "raw", true, List.of("jpeg")));
    }

    @Test
    void aShotWithAFailedHalfIsAProblemWhicheverHalfFailed() throws Exception {
        pair("A", 141);
        pair("B", 142);
        long run = readAll();
        edits.assign(run, List.of(id(run, "A.JPG"), id(run, "B.JPG")), "A5");
        store.setPairChoice(run, ImportPairs.BOTH);
        Files.delete(root.resolve("A.NEF")); // the RAW of A fails, its JPEG is filed
        Files.delete(root.resolve("B.JPG")); // the JPEG of B fails, its RAW is filed

        filer(ids -> { }).file(run, (d, t) -> { }, () -> false);

        assertThat(half(run, "A.JPG")).startsWith("filed|");
        assertThat(half(run, "A.NEF")).startsWith("failed|");
        assertThat(half(run, "B.NEF")).startsWith("filed|");
        assertThat(half(run, "B.JPG")).startsWith("failed|");
        ImportStore.Counts counts = store.counts(run);
        assertThat(counts.total()).isEqualTo(2);
        assertThat(counts.problems()).as("fixes J5").isEqualTo(2);
        assertThat(counts.filed()).isZero();
    }

    @Test
    void theResultsLeftCountsShotsLikeTheGrid() throws Exception {
        pair("A", 151);
        pair("B", 152);
        write("C.JPG", TestImages.jpeg(64, 48, 153));
        long run = readAll();
        edits.assign(run, List.of(id(run, "A.JPG"), id(run, "B.JPG"), id(run, "C.JPG")), "A5");
        store.setPairChoice(run, ImportPairs.BOTH);

        assertThat(store.result(run).left()).as("fixes J5: three shots of five files").isEqualTo(3);
        assertThat(store.counts(run).assigned()).isEqualTo(3);

        AtomicInteger opened = new AtomicInteger();
        filer(ids -> { }, (p, follow) -> {
            opened.incrementAndGet();
            return ImportFiler.Opener.NIO.open(p, follow);
        }, null, null).file(run, (d, t) -> { }, () -> opened.get() >= 1);

        assertThat(rows("SELECT COUNT(*) FROM import_item WHERE outcome = 'filed'")).containsExactly("1");
        assertThat(store.result(run).left()).as("a shot with a half filed is filed").isEqualTo(2);
    }
}
