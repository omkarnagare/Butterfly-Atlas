package com.butterflyatlas.app.imports;

import com.butterflyatlas.app.AtlasException;
import com.butterflyatlas.app.custom.CustomSpecies;
import com.butterflyatlas.app.db.TestDatabases;
import com.butterflyatlas.app.library.Library;
import com.butterflyatlas.app.search.SearchService;
import com.butterflyatlas.app.taxa.TaxonService;
import com.butterflyatlas.app.testing.TestBundles;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.DosFileAttributes;
import java.nio.file.attribute.FileTime;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.text.Normalizer;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assumptions.assumeThat;

class ImportWalkerTest {

    @TempDir
    Path dir;

    private Path root;
    private Library library;
    private ImportStore store;
    private ImportWalker walker;

    @BeforeEach
    void setUp() throws IOException {
        root = Files.createDirectories(dir.resolve("Butterflies 2024"));
        library = TestBundles.fixtureLibrary(dir.resolve("Atlas"));
        store = new ImportStore(() -> library, TestBundles.CLOCK);
        walker = new ImportWalker(() -> library, new SearchService(() -> library.database()), TestBundles.CLOCK);
    }

    private static Path write(Path file, int bytes) throws IOException {
        Files.createDirectories(file.getParent());
        Files.write(file, new byte[bytes]);
        Files.setLastModifiedTime(file, FileTime.from(Instant.parse("2024-08-12T05:00:00.123456Z")));
        return file;
    }

    private Map<String, ImportStore.Item> items(long run) {
        return store.items(run, null).items().stream().collect(Collectors.toMap(ImportStore.Item::path, Function.identity()));
    }

    private ImportWalker.Result walk(long run) {
        return walker.walk(run, n -> { }, () -> false).walked();
    }

    private void set(long itemId, String sql, Object... values) {
        library.database().write(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE import_item SET " + sql + " WHERE id = ?")) {
                for (int i = 0; i < values.length; i++) {
                    ps.setObject(i + 1, values[i]);
                }
                ps.setLong(values.length + 1, itemId);
                ps.executeUpdate();
            }
            return null;
        });
    }

    @Test
    void aSuggestedCustomSpeciesDeletedBeforeTheWriteIsNoSuggestionAndTheWalkIsSaved() throws IOException {
        CustomSpecies custom = new CustomSpecies(() -> library, new TaxonService(() -> library.database()), TestBundles.CLOCK);
        custom.add("A1", "Neptis sankara", null);
        write(root.resolve("Neptis sankara/DSC_1.jpg"), 20);
        write(root.resolve("Plain tiger/DSC_2.jpg"), 20);
        Path other = Files.createDirectories(dir.resolve("Other"));
        write(other.resolve("Neptis sankara/DSC_3.jpg"), 20);
        long first = store.create(other).id();
        walk(first);
        assertThat(items(first).get("Neptis sankara/DSC_3.jpg").suggestedTaxonId()).as("suggested while it exists").isEqualTo("U1");
        long run = store.create(root).id();
        walker.beforeWrite(() -> custom.delete("U1"));

        assertThat(walk(run).added()).isEqualTo(2);

        Map<String, ImportStore.Item> items = items(run);
        assertThat(items.get("Neptis sankara/DSC_1.jpg").suggestedTaxonId()).isNull();
        assertThat(items.get("Plain tiger/DSC_2.jpg").suggestedTaxonId()).isEqualTo("A5");
    }

    @Test
    void everyPhotoAndVideoBecomesAPendingItemByItsRelativePath() throws IOException {
        write(root.resolve("IMG_0001.JPG"), 10);
        write(root.resolve("Plain tiger").resolve("DSC_1.jpeg"), 20);
        write(root.resolve("Plain tiger").resolve("a.heic"), 1);
        write(root.resolve("Plain tiger").resolve("b.NEF"), 1);
        write(root.resolve("clips").resolve("c.MOV"), 1);
        write(root.resolve("notes.txt"), 1);
        write(root.resolve(".DS_Store"), 1);
        write(root.resolve("._IMG_0001.JPG"), 1);
        write(root.resolve(".hidden").resolve("x.jpg"), 1);
        write(root.resolve("@eaDir").resolve("x.jpg"), 1);
        write(root.resolve("$RECYCLE.BIN").resolve("x.jpg"), 1);
        write(root.resolve("Photos Library.photoslibrary").resolve("originals").resolve("x.jpg"), 1);
        long run = store.create(root).id();
        AtomicInteger found = new AtomicInteger();

        ImportWalker.Result result = walker.walk(run, found::set, () -> false).walked();

        assertThat(result).isEqualTo(new ImportWalker.Result(5, 0, 0, 0, 5));
        assertThat(found.get()).isEqualTo(5);
        Map<String, ImportStore.Item> items = items(run);
        assertThat(items).containsOnlyKeys("IMG_0001.JPG", "Plain tiger/DSC_1.jpeg", "Plain tiger/a.heic", "Plain tiger/b.NEF",
                "clips/c.MOV");
        ImportStore.Item tiger = items.get("Plain tiger/DSC_1.jpeg");
        assertThat(tiger.state()).isEqualTo("pending");
        assertThat(tiger.size()).isEqualTo(20);
        assertThat(tiger.modifiedAt()).isEqualTo("2024-08-12T05:00:00.123Z");
        assertThat(tiger.folder()).isEqualTo("Plain tiger");
        assertThat(tiger.suggestedTaxonId()).as("from the folder name").isEqualTo("A5");
        assertThat(items.get("IMG_0001.JPG").suggestedTaxonId()).isNull();
        assertThat(store.run(run).lastWalkAt()).isEqualTo("2026-10-09T08:00:00Z");
    }

    @Test
    void theLibraryFolderInsideTheChosenFolderIsSkipped() throws IOException {
        write(dir.resolve("Atlas").resolve("images").resolve("x").resolve("Danaus_chrysippus_01.jpg"), 1);
        write(dir.resolve("Butterflies 2024").resolve("a.jpg"), 1);
        long run = store.create(dir).id();

        walk(run);

        assertThat(items(run)).containsOnlyKeys("Butterflies 2024/a.jpg");
    }

    @Test
    void symbolicLinksAreNotFollowed() throws IOException {
        Path outside = write(dir.resolve("outside").resolve("secret.jpg"), 1);
        write(root.resolve("a.jpg"), 1);
        try {
            Files.createSymbolicLink(root.resolve("linked"), outside.getParent());
            Files.createSymbolicLink(root.resolve("link.jpg"), outside);
        } catch (IOException | UnsupportedOperationException e) {
            assumeThat(false).as("symbolic links need privileges here").isTrue();
        }
        long run = store.create(root).id();

        walk(run);

        assertThat(items(run)).containsOnlyKeys("a.jpg");
    }

    @Test
    void aFolderLinkLoopIsNotFollowed() throws IOException {
        write(root.resolve("a.jpg"), 1);
        try {
            Files.createSymbolicLink(root.resolve("loop"), root);
        } catch (IOException | UnsupportedOperationException e) {
            assumeThat(false).as("symbolic links need privileges here").isTrue();
        }
        long run = store.create(root).id();

        walk(run);

        assertThat(items(run)).containsOnlyKeys("a.jpg");
    }

    @Test
    void aJunctionThatJavaReportsAsAPlainFolderIsRecognisedByItsRealPath() throws IOException {
        Path target = Files.createDirectories(dir.resolve("target"));
        Path link = dir.resolve("junction");
        try {
            Files.createSymbolicLink(link, target);
        } catch (IOException | UnsupportedOperationException e) {
            assumeThat(false).as("symbolic links need privileges here").isTrue();
        }
        Path real = dir.toRealPath();
        BasicFileAttributes plainFolder = Files.readAttributes(real.resolve("target"), BasicFileAttributes.class);

        assertThat(plainFolder.isDirectory()).isTrue();
        ImportWalker onWindows = windowsWalker(Path::toRealPath);
        assertThat(onWindows.verdict(real.resolve("junction"), plainFolder, data(real))).as("resolves elsewhere")
                .isEqualTo(ImportWalker.Verdict.SKIP);
        assertThat(onWindows.verdict(real.resolve("target"), plainFolder, data(real))).isEqualTo(ImportWalker.Verdict.WALK);
    }

    @Test
    void hiddenAndSystemFoldersAreSkippedByTheirDosFlags() throws IOException {
        Path folder = Files.createDirectories(dir.toRealPath().resolve("Application Data"));
        Path data = data(dir.toRealPath());
        ImportWalker onWindows = windowsWalker(Path::toRealPath);

        assertThat(onWindows.verdict(folder, dos(true, false), data)).isEqualTo(ImportWalker.Verdict.SKIP);
        assertThat(onWindows.verdict(folder, dos(false, true), data)).isEqualTo(ImportWalker.Verdict.SKIP);
        assertThat(onWindows.verdict(folder, dos(false, false), data)).isEqualTo(ImportWalker.Verdict.WALK);
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

    private ImportWalker windowsWalker(ImportWalker.RealPath realPath) {
        return new ImportWalker(() -> library, new SearchService(() -> library.database()), TestBundles.CLOCK, true, realPath);
    }

    private static Path data(Path real) {
        return real.resolve("Atlas");
    }

    private static BasicFileAttributes attrs(boolean directory, boolean symbolicLink, boolean other) {
        return new BasicFileAttributes() {
            public FileTime lastModifiedTime() { return FileTime.fromMillis(0); }
            public FileTime lastAccessTime() { return FileTime.fromMillis(0); }
            public FileTime creationTime() { return FileTime.fromMillis(0); }
            public boolean isRegularFile() { return !directory && !symbolicLink && !other; }
            public boolean isDirectory() { return directory; }
            public boolean isSymbolicLink() { return symbolicLink; }
            public boolean isOther() { return other; }
            public long size() { return 1; }
            public Object fileKey() { return null; }
        };
    }

    @Test
    void aCloudPlaceholderOrOtherReparsePointIsWalkedAndListed() throws IOException {
        Path folder = Files.createDirectories(dir.toRealPath().resolve("OneDrive"));

        assertThat(windowsWalker(Path::toRealPath).verdict(folder, attrs(true, false, true), data(dir.toRealPath())))
                .isEqualTo(ImportWalker.Verdict.WALK);
        assertThat(ImportNames.isFile(attrs(false, false, true), true)).as("a placeholder file").isTrue();
        assertThat(ImportNames.isFile(attrs(false, false, true), false)).as("a FIFO or device elsewhere").isFalse();
        assertThat(ImportNames.isFile(attrs(false, false, false), false)).isTrue();
        assertThat(ImportNames.isFile(attrs(false, true, false), true)).as("a link").isFalse();
        assertThat(ImportNames.isFile(attrs(true, false, false), true)).isFalse();
    }

    @Test
    void aFifoNamedLikeAPhotoIsNotListed() throws Exception {
        write(root.resolve("a.jpg"), 1);
        Process made;
        try {
            made = new ProcessBuilder("mkfifo", root.resolve("pipe.jpg").toString()).start();
            assumeThat(made.waitFor()).as("mkfifo is available").isZero();
        } catch (IOException e) {
            assumeThat(false).as("mkfifo is available").isTrue();
        }
        // On Windows, mkfifo may make no file or an ordinary one; the test needs a real "other" entry.
        java.nio.file.Path fifo = root.resolve("pipe.jpg");
        assumeThat(java.nio.file.Files.exists(fifo, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                && java.nio.file.Files.readAttributes(fifo, java.nio.file.attribute.BasicFileAttributes.class,
                        java.nio.file.LinkOption.NOFOLLOW_LINKS).isOther())
                .as("mkfifo made a special file").isTrue();
        long run = store.create(root).id();

        walk(run);

        assertThat(items(run)).containsOnlyKeys("a.jpg");
    }

    @Test
    void realPathsAreOnlyComparedOnWindowsAndAnUnresolvableFolderIsUnreadableNotMissing() throws IOException {
        AtomicInteger asked = new AtomicInteger();
        ImportWalker elsewhere = new ImportWalker(() -> library, new SearchService(() -> library.database()),
                TestBundles.CLOCK, false, p -> { asked.incrementAndGet(); throw new IOException("no"); });
        Path folder = Files.createDirectories(dir.toRealPath().resolve("x"));
        assertThat(elsewhere.verdict(folder, attrs(true, false, false), data(dir.toRealPath())))
                .isEqualTo(ImportWalker.Verdict.WALK);
        assertThat(elsewhere.verdict(folder, attrs(true, true, false), data(dir.toRealPath())))
                .isEqualTo(ImportWalker.Verdict.SKIP);
        assertThat(asked.get()).isZero();

        write(root.resolve("open").resolve("a.jpg"), 1);
        write(root.resolve("locked").resolve("b.jpg"), 1);
        long run = store.create(root).id();
        walk(run);
        ImportWalker flaky = windowsWalker(p -> {
            if (p.endsWith("locked")) {
                throw new IOException("denied");
            }
            return p.toRealPath();
        });

        assertThat(flaky.walk(run, n -> { }, () -> false).walked()).isEqualTo(new ImportWalker.Result(0, 0, 0, 0, 1));
        assertThat(items(run).get("locked/b.jpg").state()).isEqualTo("pending");
    }

    @Test
    void relocatingIntoAFolderAnotherOpenImportUsesIsRefused() throws IOException {
        write(root.resolve("a.jpg"), 1);
        long run = store.create(root).id();
        walk(run);
        Path other = Files.createDirectories(dir.resolve("other"));
        write(other.resolve("a.jpg"), 1);
        store.create(other);

        assertThatThrownBy(() -> walker.relocate(run, other)).isInstanceOfSatisfying(AtlasException.class, e -> {
            assertThat(e.code()).isEqualTo("folder_in_use");
            assertThat(e.getMessage()).isEqualTo("Another import already uses that folder.");
            assertThat(e.kind()).isEqualTo(AtlasException.Kind.CONFLICT);
        });
        assertThat(store.run(run).rootPath()).isEqualTo(root.toString());
    }

    @Test
    void relocatingNeedsHalfTheItemsToMatchByPathAndSize() throws IOException {
        for (int n = 1; n <= 4; n++) {
            write(root.resolve("DSC_000" + n + ".JPG"), 10);
        }
        long run = store.create(root).id();
        walk(run);
        set(items(run).get("DSC_0004.JPG").id(), "outcome = 'filed'");
        Path wrong = dir.resolve("other camera");
        write(wrong.resolve("DSC_0001.JPG"), 10);
        write(wrong.resolve("DSC_0002.JPG"), 99);
        write(wrong.resolve("DSC_0003.JPG"), 98);

        assertThatThrownBy(() -> walker.relocate(run, wrong)).isInstanceOfSatisfying(AtlasException.class, e -> {
            assertThat(e.code()).isEqualTo("folder_mismatch");
            assertThat(e.getMessage()).isEqualTo("None of this import's photos are in that folder.");
        });

        Path moved = dir.resolve("moved");
        write(moved.resolve("DSC_0001.JPG"), 10);
        write(moved.resolve("DSC_0004.JPG"), 10); // filed ones count: the originals stay in the source
        write(moved.resolve("extra 1.jpg"), 1);
        write(moved.resolve("extra 2.jpg"), 1);
        write(moved.resolve("extra 3.jpg"), 1);

        assertThat(walker.relocate(run, moved).total()).isEqualTo(5);
        assertThat(store.run(run).rootPath()).isEqualTo(moved.toString());
    }

    @Test
    void aRelocateKeepsTheImportsInMemoryCurrent() throws IOException {
        write(root.resolve("DSC_0001.JPG"), 10);
        long run = store.create(root).id();
        walk(run);
        assertThat(store.briefs()).extracting(ImportStore.Brief::rootPath).containsExactly(root.toString());
        Path moved = dir.resolve("moved");
        write(moved.resolve("DSC_0001.JPG"), 10);

        walker.relocate(run, moved);

        AtomicInteger opened = TestDatabases.countConnections(library.database());
        assertThat(store.briefs()).extracting(ImportStore.Brief::rootPath).containsExactly(moved.toString());
        assertThat(opened.get()).as("from memory").isZero();
    }

    @Test
    void photosAlreadyMissingDoNotCountAgainstARelocate() throws IOException {
        for (int n = 1; n <= 4; n++) {
            write(root.resolve("DSC_000" + n + ".JPG"), 10);
        }
        long run = store.create(root).id();
        walk(run);
        for (int n = 2; n <= 4; n++) {
            Files.delete(root.resolve("DSC_000" + n + ".JPG"));
        }
        walk(run); // three are missing now
        Path moved = dir.resolve("moved");
        write(moved.resolve("DSC_0001.JPG"), 10);

        assertThat(walker.relocate(run, moved).total()).as("the one photo still there is found").isEqualTo(1);
        assertThat(store.run(run).rootPath()).isEqualTo(moved.toString());
    }

    @Test
    void aWalkThatFindsNothingWhereThereWereFilesIsAMissingFolder() throws IOException {
        write(root.resolve("a.jpg"), 1);
        long run = store.create(root).id();
        walk(run);
        long version = store.items(run, null).version();
        Files.delete(root.resolve("a.jpg"));

        assertThat(walker.walk(run, n -> { }, () -> false).ended()).as("an emptied or unplugged folder")
                .isEqualTo(ImportWalker.Ended.FOLDER_EMPTY);

        assertThat(items(run).get("a.jpg").state()).isEqualTo("pending");
        assertThat(store.items(run, null).version()).isEqualTo(version);
        long fresh = store.create(Files.createDirectories(dir.resolve("empty"))).id();
        assertThat(walker.walk(fresh, n -> { }, () -> false)).as("a new import of an empty folder").isEqualTo(
                new ImportWalker.Walk(ImportWalker.Ended.WALKED, new ImportWalker.Result(0, 0, 0, 0, 0)));
    }

    @Test
    void aFolderClearedAfterEverythingWasFiledIsJustEmpty() throws IOException {
        write(root.resolve("a.jpg"), 1);
        write(root.resolve("b.jpg"), 1);
        long run = store.create(root).id();
        walk(run);
        set(items(run).get("a.jpg").id(), "outcome = 'filed'");
        Files.delete(root.resolve("a.jpg"));
        Files.delete(root.resolve("b.jpg"));

        assertThat(walker.walk(run, n -> { }, () -> false).ended()).as("b.jpg was still to file")
                .isEqualTo(ImportWalker.Ended.FOLDER_EMPTY);

        set(items(run).get("b.jpg").id(), "outcome = 'duplicate'");
        ImportWalker.Walk walk = walker.walk(run, n -> { }, () -> false);
        assertThat(walk.ended()).as("only filed photos were there").isEqualTo(ImportWalker.Ended.WALKED);
        assertThat(walk.result()).isEqualTo(new ImportWalker.Result(0, 0, 0, 0, 0));
        assertThat(items(run).get("a.jpg").outcome()).as("a filed item is left alone").isEqualTo("filed");
    }

    @Test
    void namesThatDifferOnlyInUnicodeFormAreTheSameItemAndTakeTheNewForm() throws IOException {
        String composed = Normalizer.normalize("Pieris é.jpg", Normalizer.Form.NFC);
        String decomposed = Normalizer.normalize(composed, Normalizer.Form.NFD);
        write(root.resolve(composed), 1);
        long run = store.create(root).id();
        walk(run);
        set(items(run).values().iterator().next().id(), "taxon_id = 'A5'");
        Files.delete(root.resolve(composed));
        write(root.resolve(decomposed), 1);
        try (var files = Files.list(root)) {
            assumeThat(files.map(f -> f.getFileName().toString()).toList()).as("this file system keeps the form")
                    .containsExactly(decomposed);
        }

        assertThat(walk(run)).isEqualTo(new ImportWalker.Result(0, 0, 0, 0, 1));

        assertThat(items(run)).containsOnlyKeys(decomposed);
        assertThat(items(run).get(decomposed).taxonId()).isEqualTo("A5");
    }

    @Test
    void aWalkThatChangesNothingLeavesTheChangeNumberAlone() throws IOException {
        write(root.resolve("a.jpg"), 1);
        long run = store.create(root).id();
        walk(run);
        long version = store.items(run, null).version();

        assertThat(walk(run)).isEqualTo(new ImportWalker.Result(0, 0, 0, 0, 1));

        assertThat(store.items(run, null).version()).isEqualTo(version);
    }

    @Test
    void aWalkIsStoppableInTheMiddleOfAFolder() throws IOException {
        for (int n = 0; n < 450; n++) {
            write(root.resolve("f" + n + ".jpg"), 1);
        }
        long run = store.create(root).id();
        AtomicInteger asked = new AtomicInteger();

        assertThat(walker.walk(run, n -> { }, () -> asked.incrementAndGet() > 1).ended()).isEqualTo(ImportWalker.Ended.STOPPED);
        assertThat(items(run)).isEmpty();
    }

    @Test
    void aTimeDifferenceOfTwoSecondsWithTheSameSizeIsNotAnEdit() throws IOException {
        Path a = write(root.resolve("a.jpg"), 5);
        long run = store.create(root).id();
        walk(run);

        Files.setLastModifiedTime(a, FileTime.from(Instant.parse("2024-08-12T05:00:02.000Z")));
        assertThat(walk(run).changed()).as("FAT keeps two-second times").isZero();
        Files.setLastModifiedTime(a, FileTime.from(Instant.parse("2024-08-12T05:00:09.000Z")));
        assertThat(walk(run).changed()).isEqualTo(1);
    }

    @Test
    void copiesAreNeverRecomputedForFiledItems() throws IOException {
        write(root.resolve("a.jpg"), 1);
        write(root.resolve("b.jpg"), 1);
        long run = store.create(root).id();
        walk(run);
        long a = items(run).get("a.jpg").id();
        long b = items(run).get("b.jpg").id();
        set(a, "read_state = 'read', sha256 = 's'");
        set(b, "read_state = 'read', sha256 = 's', outcome = 'filed'");
        long version = store.items(run, null).version();

        library.database().write(c -> {
            ImportStore.recomputeCopies(c, run, List.of("s"), 99);
            return null;
        });

        assertThat(items(run).get("b.jpg").copyOf()).as("filed is final").isNull();
        assertThat(store.items(run, null).version()).isEqualTo(version);
    }

    @Test
    void aRewalkAddsNewFilesMarksVanishedOnesAndResetsEditedOnesKeepingTheChoices() throws IOException {
        write(root.resolve("keep.jpg"), 10);
        write(root.resolve("edit.jpg"), 10);
        write(root.resolve("gone.jpg"), 10);
        write(root.resolve("filed.jpg"), 10);
        long run = store.create(root).id();
        walk(run);
        Map<String, ImportStore.Item> first = items(run);
        set(first.get("edit.jpg").id(), "read_state = 'read', sha256 = 'e', v = 'e', taxon_id = 'A5', skipped = 1");
        set(first.get("gone.jpg").id(), "read_state = 'read', sha256 = 'g', taxon_id = 'A6'");
        set(first.get("filed.jpg").id(), "read_state = 'read', sha256 = 'f', taxon_id = 'A6', outcome = 'filed'");
        set(first.get("keep.jpg").id(), "read_state = 'read', sha256 = 'k'");
        long version = store.items(run, null).version();

        write(root.resolve("edit.jpg"), 11);
        Files.delete(root.resolve("gone.jpg"));
        Files.delete(root.resolve("filed.jpg"));
        write(root.resolve("new").resolve("new.jpg"), 1);
        ImportWalker.Result result = walk(run);

        assertThat(result).isEqualTo(new ImportWalker.Result(1, 1, 1, 0, 3));
        Map<String, ImportStore.Item> items = items(run);
        assertThat(items.get("edit.jpg")).satisfies(item -> {
            assertThat(item.state()).isEqualTo("pending");
            assertThat(item.size()).isEqualTo(11);
            assertThat(item.v()).isNull();
            assertThat(item.taxonId()).isEqualTo("A5");
            assertThat(item.skipped()).isTrue();
        });
        assertThat(items.get("gone.jpg").state()).isEqualTo("missing");
        assertThat(items.get("gone.jpg").taxonId()).isEqualTo("A6");
        assertThat(items.get("filed.jpg").state()).as("a filed item is done with").isEqualTo("read");
        assertThat(items.get("keep.jpg").state()).isEqualTo("read");
        assertThat(items.get("new/new.jpg").state()).isEqualTo("pending");
        assertThat(store.items(run, version).items()).extracting(ImportStore.Item::path)
                .containsExactlyInAnyOrder("edit.jpg", "gone.jpg", "new/new.jpg");

        write(root.resolve("gone.jpg"), 10);
        assertThat(walk(run)).isEqualTo(new ImportWalker.Result(0, 0, 0, 1, 4));
        assertThat(items(run).get("gone.jpg").state()).isEqualTo("pending");
        assertThat(items(run).get("gone.jpg").taxonId()).isEqualTo("A6");
    }

    @Test
    void aCopyWhoseOriginalVanishesBecomesTheOriginal() throws IOException {
        write(root.resolve("a.jpg"), 10);
        write(root.resolve("b.jpg"), 10);
        write(root.resolve("c.jpg"), 10);
        long run = store.create(root).id();
        walk(run);
        Map<String, ImportStore.Item> first = items(run);
        long a = first.get("a.jpg").id();
        long b = first.get("b.jpg").id();
        long c = first.get("c.jpg").id();
        set(a, "read_state = 'read', sha256 = 's'");
        set(b, "read_state = 'read', sha256 = 's', copy_of_item_id = ?", a);
        set(c, "read_state = 'read', sha256 = 's', copy_of_item_id = ?", a);

        Files.delete(root.resolve("a.jpg"));
        walk(run);

        Map<String, ImportStore.Item> items = items(run);
        assertThat(items.get("a.jpg").copyOf()).isNull();
        assertThat(items.get("b.jpg").copyOf()).isNull();
        assertThat(items.get("c.jpg").copyOf()).isEqualTo(b);
    }

    @Test
    void anUnreadableFolderHidesNothing() throws IOException {
        write(root.resolve("open").resolve("a.jpg"), 1);
        write(root.resolve("locked").resolve("b.jpg"), 1);
        long run = store.create(root).id();
        walk(run);
        Path locked = root.resolve("locked");
        boolean madeUnreadable = locked.toFile().setReadable(false, false);
        try {
            assumeThat(madeUnreadable && !Files.isReadable(locked)).as("this file system can lock a folder").isTrue();

            walk(run);

            assertThat(items(run).get("locked/b.jpg").state()).isEqualTo("pending");
        } finally {
            locked.toFile().setReadable(true, false);
        }
    }

    @Test
    void aMissingFolderOrAStoppedWalkChangesNothing() throws IOException {
        write(root.resolve("a.jpg"), 1);
        long run = store.create(root).id();
        walk(run);
        long version = store.items(run, null).version();

        assertThat(walker.walk(run, n -> { }, () -> true).ended()).isEqualTo(ImportWalker.Ended.STOPPED);
        Files.delete(root.resolve("a.jpg"));
        Files.delete(root);
        assertThat(walker.walk(run, n -> { }, () -> false).ended()).isEqualTo(ImportWalker.Ended.FOLDER_MISSING);

        assertThat(items(run).get("a.jpg").state()).isEqualTo("pending");
        assertThat(store.items(run, null).version()).isEqualTo(version);
    }

    @Test
    void whetherAFolderStillHoldsPhotosFollowsWhatAWalkWouldFind() throws IOException {
        Path data = library.folder();
        assertThat(ImportWalker.hasListedFiles(dir.resolve("nope"), data)).isFalse();
        write(root.resolve(".hidden").resolve("a.jpg"), 1);
        write(root.resolve("@eaDir").resolve("b.jpg"), 1);
        write(root.resolve("Photos Library.photoslibrary").resolve("originals").resolve("d.jpg"), 1);
        write(root.resolve("notes.txt"), 1);
        assertThat(ImportWalker.hasListedFiles(root, data)).as("only in folders a walk leaves out").isFalse();

        write(root.resolve("day 1").resolve("c.jpg"), 1);
        assertThat(ImportWalker.hasListedFiles(root, data)).isTrue();
        assertThat(walk(store.create(root).id()).total()).isEqualTo(1);
    }

    @Test
    void aFolderWhosePhotosAreOnlyInTheLibraryOrBehindALinkHoldsNone() throws IOException {
        Path outer = Files.createDirectories(dir.resolve("outer"));
        Path data = outer.resolve("Atlas");
        write(data.resolve("images").resolve("x.jpg"), 1);
        Path elsewhere = write(dir.resolve("elsewhere").resolve("y.jpg"), 1).getParent();
        try {
            Files.createSymbolicLink(outer.resolve("linked"), elsewhere);
        } catch (IOException | UnsupportedOperationException e) {
            assumeThat(false).as("symbolic links can be made here").isTrue();
        }

        assertThat(ImportWalker.hasListedFiles(outer, data)).isFalse();
    }

    @Test
    void aStoppedRelocateChangesNothing() throws IOException {
        write(root.resolve("a.jpg"), 1);
        long run = store.create(root).id();
        walk(run);
        long version = store.items(run, null).version();
        Path moved = dir.resolve("moved");
        write(moved.resolve("a.jpg"), 1);

        assertThat(walker.relocate(run, moved, () -> true)).isEmpty();

        assertThat(store.run(run).rootPath()).isEqualTo(root.toString());
        assertThat(store.items(run, null).version()).isEqualTo(version);
        assertThat(walker.relocate(run, moved, () -> false)).isPresent();
        assertThat(store.run(run).rootPath()).isEqualTo(moved.toString());
    }

    @Test
    void relocatingMatchesByRelativePathAndRefusesAFolderWithNoneOfThem() throws IOException {
        write(root.resolve("day 1").resolve("a.jpg"), 1);
        write(root.resolve("b.jpg"), 1);
        long run = store.create(root).id();
        walk(run);
        Path moved = dir.resolve("SSD").resolve("Butterflies 2024");
        write(moved.resolve("day 1").resolve("a.jpg"), 1);
        write(moved.resolve("c.jpg"), 1);
        Path unrelated = Files.createDirectories(dir.resolve("unrelated"));
        write(unrelated.resolve("x.jpg"), 1);

        assertThatThrownBy(() -> walker.relocate(run, unrelated)).isInstanceOfSatisfying(AtlasException.class,
                e -> assertThat(e.code()).isEqualTo("folder_mismatch"));
        assertThat(store.run(run).rootPath()).isEqualTo(root.toString());
        assertThatThrownBy(() -> walker.relocate(run, dir.resolve("nope"))).isInstanceOfSatisfying(AtlasException.class,
                e -> assertThat(e.code()).isEqualTo("folder_not_found"));

        ImportWalker.Result result = walker.relocate(run, moved);

        assertThat(result).isEqualTo(new ImportWalker.Result(1, 0, 1, 0, 2));
        assertThat(store.run(run).rootPath()).isEqualTo(moved.toString());
        Map<String, ImportStore.Item> items = items(run);
        assertThat(items.get("day 1/a.jpg").state()).isEqualTo("pending");
        assertThat(items.get("b.jpg").state()).isEqualTo("missing");
        assertThat(items.get("c.jpg").state()).isEqualTo("pending");
        assertThat(Files.exists(root.resolve("b.jpg"))).as("the old folder is only read").isTrue();
    }

    @Test
    void theSourceFolderIsNeverChanged() throws IOException {
        Path a = write(root.resolve("a.jpg"), 3);
        long run = store.create(root).id();

        walk(run);

        assertThat(Files.getLastModifiedTime(a).toInstant()).isEqualTo(Instant.parse("2024-08-12T05:00:00.123456Z"));
        try (var files = Files.list(root)) {
            assertThat(files.map(f -> f.getFileName().toString()).toList()).isEqualTo(List.of("a.jpg"));
        }
    }
}
