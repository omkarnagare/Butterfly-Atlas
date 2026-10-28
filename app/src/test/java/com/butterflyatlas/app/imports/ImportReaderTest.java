package com.butterflyatlas.app.imports;

import com.butterflyatlas.app.imaging.ImageIoPhotoProcessor;
import com.butterflyatlas.app.library.Library;
import com.butterflyatlas.app.photos.PhotoFormat;
import com.butterflyatlas.app.photos.Thumbnails;
import com.butterflyatlas.app.places.PlaceBundles;
import com.butterflyatlas.app.places.PlaceRef;
import com.butterflyatlas.app.search.SearchService;
import com.butterflyatlas.app.testing.PlaceBin;
import com.butterflyatlas.app.testing.TestBundles;
import com.butterflyatlas.app.testing.TestExif;
import com.butterflyatlas.app.testing.TestImages;
import com.butterflyatlas.app.testing.TestPlaces;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.sql.PreparedStatement;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class ImportReaderTest {

    @TempDir
    Path dir;

    private Path root;
    private Path data;
    private Library library;
    private ImportStore store;
    private ImportWalker walker;
    private ImportReader reader;
    private Thumbnails thumbnails;

    @BeforeEach
    void setUp() throws IOException {
        root = Files.createDirectories(dir.resolve("Butterflies 2024"));
        data = dir.resolve("Atlas");
        library = TestBundles.fixtureLibrary(data);
        store = new ImportStore(() -> library, TestBundles.CLOCK);
        walker = new ImportWalker(() -> library, new SearchService(() -> library.database()), TestBundles.CLOCK);
        ImageIoPhotoProcessor processor = new ImageIoPhotoProcessor(ZoneOffset.UTC);
        reader = new ImportReader(() -> library, processor, TestBundles.CLOCK);
        thumbnails = new Thumbnails(() -> library, processor, TestBundles.CLOCK);
    }

    @AfterEach
    void close() {
        thumbnails.close();
    }

    /** A HEIC image sequence's first bytes (the bytes decide; Phase 7b contract §1). */
    static byte[] heicSequence() {
        return ftyp("msf1", "msf1", "heic");
    }

    static byte[] avif() {
        return ftyp("avif", "mif1", "avif");
    }

    private static byte[] ftyp(String major, String... compatible) {
        int size = 16 + 4 * compatible.length;
        StringBuilder box = new StringBuilder("ftyp").append(major).append("\0\0\0\0");
        for (String brand : compatible) {
            box.append(brand);
        }
        byte[] text = box.toString().getBytes(StandardCharsets.ISO_8859_1);
        byte[] bytes = new byte[size + 32];
        bytes[3] = (byte) size;
        System.arraycopy(text, 0, bytes, 4, text.length);
        return bytes;
    }

    private Path write(String relPath, byte[] bytes) throws IOException {
        Path file = root.resolve(relPath);
        Files.createDirectories(file.getParent());
        return Files.write(file, bytes);
    }

    static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private long start() {
        long run = store.create(root).id();
        walker.walk(run, n -> { }, () -> false).walked();
        return run;
    }

    private Map<String, ImportStore.Item> items(long run) {
        return store.items(run, null).items().stream().collect(Collectors.toMap(ImportStore.Item::path, Function.identity()));
    }

    private List<String> gpsPlaces(Library lib, long run) {
        return lib.database().read(c -> {
            List<String> rows = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT rel_path || ' ' || coalesce(gps_place_id, '-')"
                    + " FROM import_item WHERE run_id = ? ORDER BY rel_path")) {
                ps.setLong(1, run);
                try (var rs = ps.executeQuery()) {
                    while (rs.next()) {
                        rows.add(rs.getString(1));
                    }
                }
            }
            return rows;
        });
    }

    @Test
    void readingLooksUpThePlaceOfEachPhotosGpsAndReadingItAgainAfterAChangeUpdatesIt() throws Exception {
        Path a = write("a.jpg", TestExif.camera().gps(18.5, 73.5, 600).jpeg(TestImages.jpeg(64, 48, 1)));
        write("b.jpg", TestImages.jpeg(64, 48, 2));
        write("c.jpg", TestExif.camera().gps(15.0, 65.0, 0).jpeg(TestImages.jpeg(64, 48, 3)));
        long run = start();

        reader.read(run, (d, t) -> { }, () -> false);

        assertThat(gpsPlaces(library, run)).containsExactly("a.jpg P4", "b.jpg -", "c.jpg -");
        assertThat(items(run).get("a.jpg").place()).isEqualTo(new PlaceRef("P4", List.of("Alpha", "Northstate", "Testland"),
                List.of("P4", "P2", "P1"), "gps", false, "district"));
        assertThat(items(run).get("b.jpg").place()).isNull();

        Files.write(a, TestExif.camera().gps(18.5, 74.5, 600).jpeg(TestImages.jpeg(64, 48, 4)));
        Files.setLastModifiedTime(a, FileTime.from(java.time.Instant.now().plusSeconds(3600)));
        walker.walk(run, n -> { }, () -> false);
        assertThat(gpsPlaces(library, run)).as("a changed file is read again").containsExactly("a.jpg -", "b.jpg -", "c.jpg -");
        reader.read(run, (d, t) -> { }, () -> false);

        assertThat(gpsPlaces(library, run)).containsExactly("a.jpg P5", "b.jpg -", "c.jpg -");
    }

    @Test
    void boundariesThatCannotBeReadNeverStopAPhotoBeingRead() throws Exception {
        Map<String, byte[]> files = new java.util.LinkedHashMap<>(TestPlaces.files());
        files.put(TestPlaces.BIN, PlaceBin.gzip("not boundaries".getBytes(StandardCharsets.US_ASCII)));
        files.put(PlaceBundles.CHECKSUMS, TestPlaces.checksums(files));
        Library broken = Library.open(dir.resolve("Broken"), TestBundles.fixture(), PlaceBundles.load(files), TestBundles.CLOCK);
        write("a.jpg", TestExif.camera().gps(18.5, 73.5, 600).jpeg(TestImages.jpeg(64, 48, 1)));
        ImportStore brokenStore = new ImportStore(() -> broken, TestBundles.CLOCK);
        long run = brokenStore.create(root).id();
        new ImportWalker(() -> broken, new SearchService(() -> broken.database()), TestBundles.CLOCK)
                .walk(run, n -> { }, () -> false);

        new ImportReader(() -> broken, new ImageIoPhotoProcessor(ZoneOffset.UTC), TestBundles.CLOCK)
                .read(run, (d, t) -> { }, () -> false);

        ImportStore.Item item = brokenStore.items(run, null).items().get(0);
        assertThat(item.state()).isEqualTo("read");
        assertThat(item.place()).isNull();
    }

    @Test
    void eachPhotoIsReadOnceWithItsCaptureTimeAndAPreview() throws Exception {
        byte[] photo = TestExif.camera().taken("2024:08:12 10:00:00", "+05:30").jpeg(TestImages.jpeg(1200, 800, 1));
        write("Plain tiger/IMG_0001.JPG", photo);
        write("IMG_0002.png", TestImages.png(300, 200));
        long run = start();
        List<int[]> reports = new ArrayList<>();

        int read = reader.read(run, (done, total) -> {
            synchronized (reports) {
                reports.add(new int[] {done, total});
            }
        }, () -> false);

        assertThat(read).isEqualTo(2);
        assertThat(reports.get(0)).containsExactly(0, 2);
        assertThat(reports).anySatisfy(r -> assertThat(r).containsExactly(2, 2));
        ImportStore.Item item = items(run).get("Plain tiger/IMG_0001.JPG");
        assertThat(item.state()).isEqualTo("read");
        assertThat(item.capturedAt()).isEqualTo("2024-08-12T04:30:00Z");
        assertThat(item.width()).isEqualTo(1200);
        assertThat(item.height()).isEqualTo(800);
        assertThat(item.v()).isEqualTo(sha256(photo).substring(0, 8));
        assertThat(item.suggestedTaxonId()).isEqualTo("A5");
        assertThat(item.libraryTaxonId()).isNull();
        assertThat(item.copyOf()).isNull();
        Path preview = ImportStore.previewFile(data, run, item.id(), item.v());
        BufferedImage small = ImageIO.read(preview.toFile());
        assertThat(Math.max(small.getWidth(), small.getHeight())).isEqualTo(480);
        assertThat(items(run).get("IMG_0002.png").capturedAt()).isNull();
        String stored = library.database().read(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT sha256, format, meta_json FROM import_item WHERE id = ?")) {
                ps.setLong(1, item.id());
                try (var rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getString(1) + "|" + rs.getString(2) + "|" + rs.getString(3).startsWith("{\"v\":1,\"format\":\"JPEG\"");
                }
            }
        });
        assertThat(stored).isEqualTo(sha256(photo) + "|JPEG|true");
    }

    @Test
    void unsupportedFilesSayWhyAndAreNeverHashedForNothing() throws Exception {
        write("a.heic", heicSequence());
        write("b.NEF", TestImages.tiff(40, 30));
        write("c.avif", avif());
        write("d.mp4", new byte[] {0, 0, 0, 0x18, 'f', 't', 'y', 'p'});
        write("e.jpg", heicSequence());
        write("f.jpg", "not a photo".getBytes(StandardCharsets.UTF_8));
        write("g.heic", TestImages.jpeg(40, 30));
        write("h.cr2", "II*\0\0\0\0\0CR".getBytes(StandardCharsets.ISO_8859_1));
        write("i.arw", "junk".getBytes(StandardCharsets.UTF_8));
        long run = start();

        reader.read(run, (d, t) -> { }, () -> false);

        Map<String, ImportStore.Item> items = items(run);
        assertThat(items.get("a.heic").problem()).isEqualTo(PhotoFormat.HEIC_SEQUENCE);
        assertThat(items.get("b.NEF").problem()).as("a TIFF named .NEF with no preview").isEqualTo(PhotoFormat.RAW_NO_PREVIEW);
        assertThat(items.get("c.avif").problem()).isEqualTo(PhotoFormat.AVIF);
        assertThat(items.get("d.mp4").problem()).isEqualTo("Videos can't be imported.");
        assertThat(items.get("e.jpg").problem()).as("the bytes decide").isEqualTo(PhotoFormat.HEIC_SEQUENCE);
        assertThat(items.get("f.jpg").problem()).isEqualTo(PhotoFormat.UNREADABLE);
        assertThat(items.get("h.cr2").problem()).isEqualTo(PhotoFormat.RAW_NO_PREVIEW);
        assertThat(items.get("i.arw").problem()).as("not a photo, whatever its name").isEqualTo(PhotoFormat.UNREADABLE);
        for (String name : List.of("a.heic", "b.NEF", "c.avif", "d.mp4", "e.jpg", "f.jpg", "h.cr2", "i.arw")) {
            assertThat(items.get(name).state()).as(name).isEqualTo("unsupported");
            assertThat(items.get(name).v()).as(name).isNull();
        }
        assertThat(items.get("g.heic").state()).as("a JPEG named .heic is a JPEG").isEqualTo("read");
        assertThat(store.counts(run).problems()).isEqualTo(8);
    }

    @Test
    void aPhotoThatCannotBeReadFailsAndAVanishedOneIsMissing() throws Exception {
        write("broken.jpg", new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0, 0, 0});
        write("gone.jpg", TestImages.jpeg(40, 30));
        long run = start();
        Files.delete(root.resolve("gone.jpg"));

        reader.read(run, (d, t) -> { }, () -> false);

        Map<String, ImportStore.Item> items = items(run);
        assertThat(items.get("broken.jpg").state()).isEqualTo("failed");
        assertThat(items.get("broken.jpg").problem()).isEqualTo("Butterfly Atlas couldn't read this photo.");
        assertThat(items.get("gone.jpg").state()).isEqualTo("missing");
    }

    @Test
    void photosAlreadyInTheLibraryAndCopiesWithinTheImportAreRecognised() throws Exception {
        byte[] known = TestImages.jpeg(60, 40, 7);
        byte[] twice = TestImages.jpeg(60, 40, 8);
        write("z-known.jpg", known);
        write("b/twice.jpg", twice);
        write("a/twice-copy.jpg", twice);
        write("c/twice-again.jpg", twice);
        String knownSha = sha256(known);
        library.database().write(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO image (taxon_id, rel_path, file_name, file_size,"
                    + " modified_at, sha256, added_at, source) VALUES ('A8', 'images/x.jpg', 'x.jpg', 1, 't', ?, 't', 'app')")) {
                ps.setString(1, knownSha);
                ps.executeUpdate();
            }
            return null;
        });
        long run = start();

        reader.read(run, (d, t) -> { }, () -> false);

        Map<String, ImportStore.Item> items = items(run);
        assertThat(items.get("z-known.jpg").libraryTaxonId()).isEqualTo("A8");
        assertThat(items.get("z-known.jpg").suggestedTaxonId()).as("photos in the library are pre-suggested").isEqualTo("A8");
        List<ImportStore.Item> same = List.of(items.get("b/twice.jpg"), items.get("a/twice-copy.jpg"),
                items.get("c/twice-again.jpg"));
        long original = same.stream().mapToLong(ImportStore.Item::id).min().orElseThrow(); // the earliest item
        for (ImportStore.Item item : same) {
            assertThat(item.copyOf()).as(item.path()).isEqualTo(item.id() == original ? null : original);
        }
    }

    @Test
    void readingStopsWhenAskedAndCarriesOnLater() throws Exception {
        for (int i = 0; i < 40; i++) {
            write("IMG_" + i + ".jpg", TestImages.jpeg(20, 20, i));
        }
        long run = start();

        assertThat(reader.read(run, (d, t) -> { }, () -> true)).isZero();
        assertThat(store.counts(run).reading()).isEqualTo(40);
        java.util.concurrent.atomic.AtomicBoolean enough = new java.util.concurrent.atomic.AtomicBoolean();
        reader.read(run, (d, t) -> {
            if (d >= ImportReader.CHUNK) {
                enough.set(true);
            }
        }, enough::get);
        assertThat(store.counts(run).reading()).as("one chunk read").isEqualTo(40 - ImportReader.CHUNK);

        reader.read(run, (d, t) -> { }, () -> false);
        assertThat(store.counts(run).reading()).isZero();
    }

    @Test
    void anItemEditedWhileItWasReadKeepsItsNewState() throws Exception {
        write("a.jpg", TestImages.jpeg(40, 30));
        long run = start();
        long id = items(run).get("a.jpg").id();

        reader.read(run, (done, total) -> {
            if (done == 1) { // the file is read, its result not saved yet: the walk sees another version meanwhile
                library.database().write(c -> {
                    try (PreparedStatement ps = c.prepareStatement("UPDATE import_item SET file_size = 1 WHERE id = ?")) {
                        ps.setLong(1, id);
                        ps.executeUpdate();
                    }
                    return null;
                });
            }
        }, () -> false);

        assertThat(items(run).get("a.jpg").state()).isEqualTo("pending");
        try (var files = Files.list(ImportStore.previewFolder(data, run))) {
            assertThat(files.toList()).as("the stale preview is removed").isEmpty();
        }
    }

    @Test
    void tidyingThumbnailsLeavesImportPreviewsAlone() throws Exception {
        write("a.jpg", TestImages.jpeg(40, 30));
        long run = start();
        reader.read(run, (d, t) -> { }, () -> false);
        ImportStore.Item item = items(run).get("a.jpg");

        thumbnails.cleanOrphans();

        assertThat(ImportStore.previewFile(data, run, item.id(), item.v())).exists();
        assertThat(root.resolve("a.jpg")).exists();
    }

    private static BasicFileAttributes attrs(boolean regular, boolean other, long size, long millis) {
        return new BasicFileAttributes() {
            public FileTime lastModifiedTime() { return FileTime.fromMillis(millis); }
            public FileTime lastAccessTime() { return lastModifiedTime(); }
            public FileTime creationTime() { return lastModifiedTime(); }
            public boolean isRegularFile() { return regular; }
            public boolean isDirectory() { return false; }
            public boolean isSymbolicLink() { return false; }
            public boolean isOther() { return other; }
            public long size() { return size; }
            public Object fileKey() { return null; }
        };
    }

    @Test
    void aCloudPlaceholderIsReadOnWindowsOnly() {
        String t = "2024-08-12T04:30:00Z";
        long ms = java.time.Instant.parse(t).toEpochMilli();
        assertThat(ImportReader.look(attrs(true, false, 5, ms), false, 5, t)).isEqualTo(ImportReader.Look.OK);
        assertThat(ImportReader.look(attrs(false, true, 5, ms), true, 5, t)).isEqualTo(ImportReader.Look.OK);
        assertThat(ImportReader.look(attrs(false, true, 5, ms), false, 5, t)).isEqualTo(ImportReader.Look.MISSING);
        assertThat(ImportReader.look(attrs(true, false, 6, ms), false, 5, t)).isEqualTo(ImportReader.Look.CHANGED);
        assertThat(ImportReader.look(attrs(true, false, 5, ms + 5000), false, 5, t)).isEqualTo(ImportReader.Look.CHANGED);
    }

    @Test
    void aFileChangedSinceTheWalkStaysPending() throws Exception {
        Path file = write("a.jpg", TestImages.jpeg(40, 30));
        long run = start();
        Files.write(file, TestImages.jpeg(50, 30, 3));

        assertThat(reader.readAll(run, (d, t) -> { }, () -> false)).isEqualTo(new ImportReader.Reading(0, 1));

        assertThat(items(run).get("a.jpg").state()).isEqualTo("pending");
    }

    @Test
    void aPathTheWalkCouldNotHaveSavedFails() throws Exception {
        write("a.jpg", TestImages.jpeg(40, 30));
        long run = start();
        library.database().write(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE import_item SET rel_path = 'x/../a.jpg' WHERE run_id = ?")) {
                ps.setLong(1, run);
                ps.executeUpdate();
            }
            return null;
        });

        reader.read(run, (d, t) -> { }, () -> false);

        assertThat(items(run).get("x/../a.jpg").state()).isEqualTo("failed");
    }

    @Test
    void progressNeverGoesDownAndAnOlderPreviewIsReplaced() throws Exception {
        for (int i = 0; i < 20; i++) {
            write("IMG_" + i + ".jpg", TestImages.jpeg(20, 20, i));
        }
        long run = start();
        int[] last = {-1};
        boolean[] ordered = {true};
        reader.read(run, (d, t) -> {
            synchronized (last) {
                ordered[0] &= d >= last[0];
                last[0] = d;
            }
        }, () -> false);
        assertThat(ordered[0]).isTrue();
        assertThat(last[0]).isEqualTo(20);

        ImportStore.Item item = items(run).get("IMG_0.jpg");
        Path old = ImportStore.previewFile(data, run, item.id(), "00000000");
        Files.copy(ImportStore.previewFile(data, run, item.id(), item.v()), old);
        library.database().write(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE import_item SET read_state = 'pending', v = NULL WHERE id = ?")) {
                ps.setLong(1, item.id());
                ps.executeUpdate();
            }
            return null;
        });
        reader.read(run, (d, t) -> { }, () -> false);

        assertThat(old).doesNotExist();
        assertThat(ImportStore.previewFile(data, run, item.id(), item.v())).exists();
    }
}
