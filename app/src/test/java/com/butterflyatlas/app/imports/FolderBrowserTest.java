package com.butterflyatlas.app.imports;

import com.butterflyatlas.app.AtlasException;
import com.butterflyatlas.app.library.Library;
import com.butterflyatlas.app.testing.TestBundles;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.nio.file.attribute.DosFileAttributes;
import java.nio.file.attribute.FileTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assumptions.assumeThat;

class FolderBrowserTest {

    @TempDir
    Path dir;

    private Path home;
    private Path drive;
    private Library library;
    private ImportStore store;
    private FolderBrowser browser;

    @BeforeEach
    void setUp() throws IOException {
        home = Files.createDirectories(dir.resolve("home").resolve("you"));
        drive = Files.createDirectories(dir.resolve("Volumes").resolve("Photos SSD"));
        Files.createDirectories(home.resolve("Pictures"));
        Files.createDirectories(home.resolve("Documents"));
        library = TestBundles.fixtureLibrary(home.resolve("Butterfly Atlas"));
        store = new ImportStore(() -> library, TestBundles.CLOCK);
        browser = new FolderBrowser(() -> Optional.of(library), store, home,
                () -> List.of(drive, dir.resolve("Volumes").resolve("Unplugged")));
    }

    private static void touch(Path folder, String... names) throws IOException {
        Files.createDirectories(folder);
        for (String name : names) {
            Files.write(folder.resolve(name), new byte[] {1});
        }
    }

    @Test
    void rootsAreTheShortcutsAndDrivesThatExist() {
        assertThat(browser.roots()).containsExactly(
                new FolderBrowser.Root("Home", home.toString(), "home"),
                new FolderBrowser.Root("Pictures", home.resolve("Pictures").toString(), "pictures"),
                new FolderBrowser.Root("Documents", home.resolve("Documents").toString(), "documents"),
                new FolderBrowser.Root("Photos SSD", drive.toString(), "drive"));
    }

    @Test
    void aFolderListsItsSubfoldersNaturallyWithTheirCountsAndItsPhotos() throws IOException {
        Path trip = home.resolve("Pictures").resolve("Butterflies 2024");
        touch(trip, "IMG_1.JPG", "b.png", "c.tiff", "d.webp", "e.heic", "f.NEF", "g.avif", "notes.txt", "clip.mp4",
                ".hidden.jpg");
        touch(trip.resolve("Day 10"), "a.jpg");
        touch(trip.resolve("day 2"), "a.jpg", "b.jpg");
        Files.createDirectories(trip.resolve("day 2").resolve("raw"));
        touch(trip.resolve("Day 1"));
        touch(trip.resolve(".git"), "x.jpg");
        touch(trip.resolve("$RECYCLE.BIN"));
        touch(trip.resolve("@eaDir"));
        touch(trip.resolve("System Volume Information"));

        FolderBrowser.Listing listing = browser.list(trip.toString());

        assertThat(listing.path()).isEqualTo(trip.toString());
        assertThat(listing.name()).isEqualTo("Butterflies 2024");
        assertThat(listing.parent()).isEqualTo(home.resolve("Pictures").toString());
        assertThat(listing.photos()).as("by name: jpg, png, tiff, webp, heic, NEF, avif").isEqualTo(7);
        assertThat(listing.folders()).containsExactly(
                new FolderBrowser.Folder("Day 1", trip.resolve("Day 1").toString(), 0, 0),
                new FolderBrowser.Folder("day 2", trip.resolve("day 2").toString(), 1, 2),
                new FolderBrowser.Folder("Day 10", trip.resolve("Day 10").toString(), 0, 1));
        assertThat(listing.crumbs()).containsExactly(
                new FolderBrowser.Crumb("Pictures", home.resolve("Pictures").toString()),
                new FolderBrowser.Crumb("Butterflies 2024", trip.toString()));
        assertThat(listing.isLibrary()).isFalse();
        assertThat(listing.openImport()).isNull();
    }

    @Test
    void packagesAreNotFolders() throws IOException {
        Path pictures = home.resolve("Pictures");
        for (String name : new String[] {"Photos Library.photoslibrary", "Aperture.aplibrary", "Lightroom Catalog.lrdata",
                "Catalog Previews.LRDATA", "Preview.app", "Thing.bundle", "Kit.framework", "Old.lrlibrary"}) {
            touch(pictures.resolve(name), "inside.jpg");
        }
        touch(pictures.resolve("Trip.2024"), "a.jpg");

        FolderBrowser.Listing listing = browser.list(pictures.toString());

        assertThat(listing.folders()).extracting(FolderBrowser.Folder::name).containsExactly("Trip.2024");
        assertThat(browser.list(home.toString()).folders()).filteredOn(f -> f.name().equals("Pictures"))
                .extracting(FolderBrowser.Folder::folders).containsExactly(1);
    }

    @Test
    void foldersAfterTheFirst200AreListedWithoutCounts() throws IOException {
        Path many = home.resolve("many");
        for (int i = 1; i <= 202; i++) {
            Files.createDirectories(many.resolve("f" + i));
        }

        List<FolderBrowser.Folder> folders = browser.list(many.toString()).folders();

        assertThat(folders).hasSize(202);
        assertThat(folders.get(199)).isEqualTo(new FolderBrowser.Folder("f200", many.resolve("f200").toString(), 0, 0));
        assertThat(folders.get(200)).isEqualTo(new FolderBrowser.Folder("f201", many.resolve("f201").toString(), null, null));
    }

    @Test
    void theLibraryFolderAndEverythingInsideItAreMarked() throws IOException {
        Path data = home.resolve("Butterfly Atlas");
        Files.createDirectories(data.resolve("images"));

        assertThat(browser.list(data.toString()).isLibrary()).isTrue();
        assertThat(browser.list(data.resolve("images").toString()).isLibrary()).isTrue();
        assertThat(browser.list(home.toString()).isLibrary()).as("a folder holding the library can be chosen").isFalse();
        assertThat(browser.list(home.toString()).crumbs()).containsExactly(new FolderBrowser.Crumb("you", home.toString()));
    }

    @Test
    void anUnfinishedImportOfTheFolderIsOffered() throws IOException {
        Path trip = Files.createDirectories(home.resolve("trip"));
        long id = store.create(trip).id();

        assertThat(browser.list(trip.toString()).openImport()).isEqualTo(new ImportStore.OpenImport(id, 0, 0));
        assertThat(browser.list(home.toString()).openImport()).isNull();
    }

    @Test
    void aTypedPathIsTidiedAndTheHomeShortcutUnderstood() throws IOException {
        Files.createDirectories(home.resolve("Pictures").resolve("x"));

        assertThat(browser.list("  " + home.resolve("Pictures").resolve("x").resolve("..") + "  ").path())
                .isEqualTo(home.resolve("Pictures").toString());
        assertThat(browser.list("~").path()).isEqualTo(home.toString());
        assertThat(browser.list("~/Pictures").path()).isEqualTo(home.resolve("Pictures").toString());
    }

    @Test
    void badMissingAndUnreadablePathsAreRefusedInPlainWords() throws IOException {
        assertThatThrownBy(() -> browser.list("")).isInstanceOfSatisfying(AtlasException.class,
                e -> assertThat(e.code()).isEqualTo("bad_path"));
        assertThatThrownBy(() -> browser.list("Pictures/x")).isInstanceOfSatisfying(AtlasException.class,
                e -> assertThat(e.code()).isEqualTo("bad_path"));
        assertThatThrownBy(() -> browser.list(home.resolve("nope").toString())).isInstanceOfSatisfying(AtlasException.class, e -> {
            assertThat(e.code()).isEqualTo("folder_not_found");
            assertThat(e.getMessage()).isEqualTo("That folder can't be found.");
        });
        Path file = Files.write(home.resolve("a.jpg"), new byte[] {1});
        assertThatThrownBy(() -> browser.list(file.toString())).isInstanceOfSatisfying(AtlasException.class,
                e -> assertThat(e.code()).isEqualTo("folder_not_found"));

        Path locked = Files.createDirectories(home.resolve("locked"));
        boolean madeUnreadable = locked.toFile().setReadable(false, false); // POSIX only; Windows ignores it
        try {
            assumeThat(madeUnreadable && !Files.isReadable(locked)).as("this file system can lock a folder").isTrue();
            assertThatThrownBy(() -> browser.list(locked.toString())).isInstanceOfSatisfying(AtlasException.class, e -> {
                assertThat(e.code()).isEqualTo("folder_unreadable");
                assertThat(e.kind()).isEqualTo(AtlasException.Kind.FORBIDDEN);
            });
        } finally {
            locked.toFile().setReadable(true, false);
        }
    }

    @Test
    void symbolicLinksAreNotListed() throws IOException {
        Path folder = Files.createDirectories(home.resolve("linked"));
        touch(folder.resolve("real"), "a.jpg");
        try {
            Files.createSymbolicLink(folder.resolve("link"), folder.resolve("real"));
            Files.createSymbolicLink(folder.resolve("photo.jpg"), folder.resolve("real").resolve("a.jpg"));
        } catch (IOException | UnsupportedOperationException e) {
            assumeThat(false).as("symbolic links need privileges here").isTrue();
        }

        FolderBrowser.Listing listing = browser.list(folder.toString());

        assertThat(listing.folders()).extracting(FolderBrowser.Folder::name).containsExactly("real");
        assertThat(listing.photos()).isZero();
    }

    @Test
    void listingNeverAsksForTheDrivesAndStillShowsTheDriveCrumb() throws IOException {
        Path trip = Files.createDirectories(drive.resolve("trip"));
        AtomicInteger asked = new AtomicInteger();
        FolderBrowser noDrives = new FolderBrowser(() -> Optional.of(library), store, home, () -> {
            asked.incrementAndGet();
            throw new IllegalStateException("a dead mount would hang here");
        }, System::nanoTime, List.of(dir.resolve("Volumes"), dir.resolve("ghost")));

        assertThat(noDrives.list(trip.toString()).crumbs()).containsExactly(
                new FolderBrowser.Crumb("Photos SSD", drive.toString()), new FolderBrowser.Crumb("trip", trip.toString()));
        assertThat(noDrives.list(drive.toString()).crumbs()).containsExactly(
                new FolderBrowser.Crumb("Photos SSD", drive.toString()));
        assertThat(asked).hasValue(0);
    }

    @Test
    void countingStopsWhenItsTimeIsSpent() throws IOException {
        Path many = home.resolve("slow");
        for (int i = 1; i <= 3; i++) {
            touch(many.resolve("f" + i), "a.jpg");
        }
        AtomicInteger calls = new AtomicInteger();
        FolderBrowser slow = new FolderBrowser(() -> Optional.of(library), store, home, List::of,
                () -> calls.incrementAndGet() <= 2 ? 0L : 10_000_000_000L);

        assertThat(slow.list(many.toString()).folders()).containsExactly(
                new FolderBrowser.Folder("f1", many.resolve("f1").toString(), 0, 1),
                new FolderBrowser.Folder("f2", many.resolve("f2").toString(), null, null),
                new FolderBrowser.Folder("f3", many.resolve("f3").toString(), null, null));
    }

    @Test
    void windowsHiddenAndSystemFoldersAreLeftOut() throws IOException {
        assumeThat(System.getProperty("os.name", "").toLowerCase().contains("win")).as("DOS attributes").isTrue();
        Path folder = Files.createDirectories(home.resolve("dos"));
        Files.setAttribute(Files.createDirectories(folder.resolve("h")), "dos:hidden", true);
        Files.setAttribute(Files.createDirectories(folder.resolve("s")), "dos:system", true);
        Files.createDirectories(folder.resolve("plain"));

        assertThat(browser.list(folder.toString()).folders()).extracting(FolderBrowser.Folder::name).containsExactly("plain");
    }

    @Test
    void hiddenOrSystemIsReadFromDosAttributes() {
        assertThat(FolderBrowser.isHiddenOrSystem(dos(true, false))).isTrue();
        assertThat(FolderBrowser.isHiddenOrSystem(dos(false, true))).isTrue();
        assertThat(FolderBrowser.isHiddenOrSystem(dos(false, false))).isFalse();
    }

    private static DosFileAttributes dos(boolean hidden, boolean system) {
        return new DosFileAttributes() {
            public boolean isReadOnly() { return false; }
            public boolean isHidden() { return hidden; }
            public boolean isArchive() { return false; }
            public boolean isSystem() { return system; }
            public FileTime lastModifiedTime() { return FileTime.fromMillis(0); }
            public FileTime lastAccessTime() { return FileTime.fromMillis(0); }
            public FileTime creationTime() { return FileTime.fromMillis(0); }
            public boolean isRegularFile() { return false; }
            public boolean isDirectory() { return true; }
            public boolean isSymbolicLink() { return false; }
            public boolean isOther() { return false; }
            public long size() { return 0; }
            public Object fileKey() { return null; }
        };
    }

    @Test
    void quotesAroundAPastedPathAreDropped() throws IOException {
        Path x = Files.createDirectories(home.resolve("Pictures").resolve("x"));

        assertThat(browser.list("\"" + x + "\"").path()).isEqualTo(x.toString());
        assertThat(browser.list("  '" + x + "'  ").path()).isEqualTo(x.toString());
    }
}
