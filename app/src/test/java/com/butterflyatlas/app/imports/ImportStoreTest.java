package com.butterflyatlas.app.imports;

import com.butterflyatlas.app.AtlasException;
import com.butterflyatlas.app.db.Database;
import com.butterflyatlas.app.library.Library;
import com.butterflyatlas.app.testing.TestBundles;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ImportStoreTest {

    @TempDir
    Path dir;

    private Library library;
    private ImportStore store;

    @BeforeEach
    void open() {
        library = TestBundles.fixtureLibrary(dir.resolve("Atlas"));
        store = new ImportStore(() -> library, TestBundles.CLOCK);
    }

    /** Inserts an item with the given columns (pairs of name, value) and stamps it; returns its id. */
    static long item(Database db, long runId, String relPath, Object... columns) {
        return db.write(c -> {
            long seq = ImportStore.stamp(c, runId, Instant.parse("2026-10-09T08:00:00Z"));
            StringBuilder names = new StringBuilder("run_id, rel_path, file_size, modified_at, read_state, seq");
            StringBuilder marks = new StringBuilder("?, ?, 1000, '2026-10-01T00:00:00Z', ?, ?");
            String state = "pending";
            for (int i = 0; i < columns.length; i += 2) {
                if (columns[i].equals("read_state")) {
                    state = (String) columns[i + 1];
                    continue;
                }
                names.append(", ").append(columns[i]);
                marks.append(", ?");
            }
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO import_item (" + names + ") VALUES (" + marks + ")",
                    PreparedStatement.RETURN_GENERATED_KEYS)) {
                ps.setLong(1, runId);
                ps.setString(2, relPath);
                ps.setString(3, state);
                ps.setLong(4, seq);
                int at = 5;
                for (int i = 0; i < columns.length; i += 2) {
                    if (!columns[i].equals("read_state")) {
                        ps.setObject(at++, columns[i + 1]);
                    }
                }
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    keys.next();
                    return keys.getLong(1);
                }
            }
        });
    }

    private Path folder(String name) throws Exception {
        return Files.createDirectories(dir.resolve(name)).toAbsolutePath().normalize();
    }

    @Test
    void importsAreListedOpenFirstThenNewestFirst() throws Exception {
        ImportStore.Run first = store.create(folder("One"));
        ImportStore.Run second = store.create(folder("Two"));
        library.database().write(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE import_run SET state = 'finished' WHERE id = ?")) {
                ps.setLong(1, second.id());
                ps.executeUpdate();
            }
            return null;
        });
        ImportStore.Run third = store.create(folder("Three"));

        assertThat(store.runs()).extracting(ImportStore.Run::id).containsExactly(third.id(), first.id(), second.id());
        assertThat(first.state()).isEqualTo("open");
        assertThat(first.rootPath()).isEqualTo(dir.resolve("One").toAbsolutePath().normalize().toString());
        assertThat(first.createdAt()).isEqualTo("2026-10-09T08:00:00Z");
        assertThat(store.run(first.id()).root()).isEqualTo(first.root());
    }

    @Test
    void anUnknownImportIsImportNotFound() {
        assertThatThrownBy(() -> store.run(404)).isInstanceOfSatisfying(AtlasException.class, e -> {
            assertThat(e.kind()).isEqualTo(AtlasException.Kind.NOT_FOUND);
            assertThat(e.code()).isEqualTo("import_not_found");
        });
    }

    @Test
    void changesToAnUnknownImportAreImportNotFound() {
        for (Runnable change : List.<Runnable>of(() -> store.clearStopped(404), () -> store.setPaused(404, true),
                () -> store.finish(404), () -> store.delete(404), () -> store.itemFile(404, 1),
                () -> library.database().write(c -> ImportStore.stamp(c, 404, Instant.EPOCH)))) {
            assertThatThrownBy(change::run).isInstanceOfSatisfying(AtlasException.class,
                    e -> assertThat(e.code()).isEqualTo("import_not_found"));
        }
    }

    @Test
    void anUnknownItemOfAKnownImportIsNotFound() throws Exception {
        long run = store.create(folder("Photos")).id();

        assertThatThrownBy(() -> store.itemFile(run, 404)).isInstanceOfSatisfying(AtlasException.class, e -> {
            assertThat(e.kind()).isEqualTo(AtlasException.Kind.NOT_FOUND);
            assertThat(e.getMessage()).isEqualTo("That photo is not part of this import.");
        });
    }

    @Test
    void stampsAreConsecutive() throws Exception {
        long run = store.create(folder("Photos")).id();

        long first = library.database().write(c -> ImportStore.stamp(c, run, Instant.EPOCH));
        long second = library.database().write(c -> ImportStore.stamp(c, run, Instant.parse("2026-10-10T00:00:00Z")));

        assertThat(second).isEqualTo(first + 1);
        assertThat(store.run(run).updatedAt()).isEqualTo("2026-10-10T00:00:00Z");
    }

    @Test
    void aRootNoLongerThereIsTheSameFolderOnlyByItsPath() throws Exception {
        Path gone = dir.resolve("Gone").toAbsolutePath();
        Path there = folder("There");

        assertThat(ImportStore.samePath(gone.toString(), gone)).isTrue();
        assertThat(ImportStore.samePath(gone.toString(), there)).isFalse();
        assertThat(ImportStore.samePath(there.toString(), gone)).isFalse();
    }

    @Test
    void copiesArePointedAgainForFailedItemsButNotForFiledOnes() throws Exception {
        long run = store.create(folder("Photos")).id();
        Database db = library.database();
        long a = item(db, run, "a.jpg", "read_state", "read", "sha256", "same");
        long failed = item(db, run, "b.jpg", "read_state", "read", "sha256", "same", "outcome", "failed");
        long filed = item(db, run, "c.jpg", "read_state", "read", "sha256", "same", "outcome", "filed");

        db.write(c -> {
            ImportStore.recomputeCopies(c, run, List.of("same"), ImportStore.stamp(c, run, Instant.EPOCH));
            return null;
        });

        Map<Long, Long> copyOf = new java.util.HashMap<>();
        store.items(run, null).items().forEach(i -> copyOf.put(i.id(), i.copyOf()));
        assertThat(copyOf.get(a)).isNull();
        assertThat(copyOf.get(failed)).as("tried again: a copy of a").isEqualTo(a);
        assertThat(copyOf.get(filed)).as("its outcome stands").isNull();
    }

    @Test
    void theOpenImportOfAFolderIsFoundAsTheComputerComparesPaths() throws Exception {
        Path photos = folder("Photos");
        ImportStore.Run run = store.create(photos);

        assertThat(store.openRunOf(photos.resolve("sub").resolve(".."))).map(ImportStore.Run::id).contains(run.id());
        Path upper = photos.resolveSibling(photos.getFileName().toString().toUpperCase(Locale.ROOT));
        if (Files.exists(upper)) { // a case-insensitive file system (macOS, Windows): the same folder
            assertThat(store.openRunOf(upper)).map(ImportStore.Run::id).contains(run.id());
        }
        assertThat(store.openRunOf(folder("Other"))).isEmpty();
        item(library.database(), run.id(), "a.jpg", "outcome", "filed", "read_state", "read");
        item(library.database(), run.id(), "b.jpg");
        assertThat(store.openImport(photos)).contains(new ImportStore.OpenImport(run.id(), 1, 2));

        library.database().write(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE import_run SET state = 'finished'")) {
                ps.executeUpdate();
            }
            return null;
        });
        assertThat(store.openRunOf(photos)).isEmpty();
        assertThat(store.openImport(photos)).isEmpty();
    }

    @Test
    void eachItemCountsInExactlyOneCategory() throws Exception {
        long run = store.create(folder("Photos")).id();
        Database db = library.database();
        item(db, run, "pending.jpg");
        item(db, run, "pending-assigned.jpg", "taxon_id", "A5");
        item(db, run, "heic.heic", "read_state", "unsupported", "problem", "HEIC photos aren't supported yet.");
        item(db, run, "broken.jpg", "read_state", "failed", "problem", "Butterfly Atlas couldn't read this photo.");
        item(db, run, "locked.jpg", "read_state", "read", "taxon_id", "A5", "outcome", "failed");
        item(db, run, "gone.jpg", "read_state", "missing", "taxon_id", "A5");
        item(db, run, "filed.jpg", "read_state", "read", "taxon_id", "A5", "outcome", "filed");
        item(db, run, "dup.jpg", "read_state", "read", "taxon_id", "A5", "outcome", "duplicate", "skipped", 1);
        item(db, run, "copy.jpg", "read_state", "read", "taxon_id", "A5", "outcome", "copy_of");
        item(db, run, "elsewhere.jpg", "read_state", "read", "taxon_id", "A5", "outcome", "also_elsewhere");
        item(db, run, "skipped.jpg", "read_state", "read", "taxon_id", "A5", "skipped", 1);
        item(db, run, "assigned.jpg", "read_state", "read", "taxon_id", "A5", "suggested_taxon_id", "A6");
        item(db, run, "suggested.jpg", "read_state", "read", "suggested_taxon_id", "A6");
        item(db, run, "plain.jpg", "read_state", "read");
        item(db, run, "plain2.jpg", "read_state", "read");

        assertThat(store.counts(run)).isEqualTo(new ImportStore.Counts(15, 11, 2, 2, 1, 1, 1, 3, 4, 1));
        assertThat(store.counts(store.create(folder("Empty")).id())).isEqualTo(new ImportStore.Counts(0, 0, 0, 0, 0, 0, 0, 0, 0, 0));
    }

    @Test
    void itemsComeWithTheirTagsAndTaxaAndOnlyThoseChangedSinceAVersion() throws Exception {
        long run = store.create(folder("Photos")).id();
        Database db = library.database();
        long a = item(db, run, "Sinhagad, Aug 2024/IMG_0412.JPG", "read_state", "read", "captured_at", "2024-08-12T05:00:00Z",
                "suggested_taxon_id", "A5", "taxon_id", "A6", "sha256", "abc", "v", "abc12345",
                "meta_json", "{\"v\":1,\"format\":\"JPEG\",\"width\":4000,\"height\":3000,\"orientation\":6}");
        long b = item(db, run, "top.jpg", "library_taxon_id", "A8", "copy_of_item_id", a);
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO import_item_tag (item_id, tag_id) SELECT ?, id FROM tag"
                    + " WHERE key IN ('male', 'larva')")) {
                ps.setLong(1, a);
                ps.executeUpdate();
            }
            return null;
        });

        ImportStore.ItemsPage all = store.items(run, null);

        assertThat(all.version()).isEqualTo(2);
        assertThat(all.items()).hasSize(2);
        ImportStore.Item first = all.items().get(0);
        assertThat(first).isEqualTo(new ImportStore.Item(a, "Sinhagad, Aug 2024/IMG_0412.JPG", "Sinhagad, Aug 2024",
                "IMG_0412.JPG", 1000, "2026-10-01T00:00:00Z", "2024-08-12T05:00:00Z", 4000, 3000, "read", null, "A5", null, null, "A6",
                List.of("larva", "male"), false, null, null, null, "abc12345", null, null, null, null, null, false));
        ImportStore.Item second = all.items().get(1);
        assertThat(second.folder()).isEmpty();
        assertThat(second.name()).isEqualTo("top.jpg");
        assertThat(second.copyOf()).isEqualTo(a);
        assertThat(second.state()).isEqualTo("pending");
        assertThat(second.v()).isNull();
        assertThat(second.width()).as("not read yet").isNull();
        assertThat(all.taxa()).containsOnlyKeys("A5", "A6", "A8");
        assertThat(all.taxa().get("A5")).isEqualTo(new ImportStore.TaxonName("Danaus chrysippus", "Plain Tiger"));

        ImportStore.ItemsPage since = store.items(run, 1L);
        assertThat(since.version()).isEqualTo(2);
        assertThat(since.items()).extracting(ImportStore.Item::id).containsExactly(b);
        assertThat(since.taxa()).containsOnlyKeys("A8");
        assertThat(store.items(run, 2L).items()).isEmpty();
    }

    @Test
    void deletingAnImportRemovesOnlyItsRows() throws Exception {
        long keep = store.create(folder("Keep")).id();
        long gone = store.create(folder("Gone")).id();
        item(library.database(), keep, "a.jpg");
        item(library.database(), gone, "a.jpg");

        store.delete(gone);

        assertThat(store.find(gone)).isEmpty();
        assertThat(store.items(keep, null).items()).hasSize(1);
        assertThat(Files.isDirectory(dir.resolve("Gone"))).isTrue();
    }

    @Test
    void aPhaseStillActiveAtTheNextStartIsReportedAsStoppedUntilTheUserGoesOn() throws Exception {
        long filing = store.create(folder("Filing")).id();
        long walking = store.create(folder("Walking")).id();
        long done = store.create(folder("Done")).id();
        store.setActive(filing, "filing", 1200, 2140);
        store.setActive(walking, "walking", 30, 0);
        store.setActive(done, "reading", 5, 10);
        store.setActive(done, null, 0, 0); // ended normally

        assertThat(store.markStopped()).isEqualTo(2);

        assertThat(store.run(filing).stopped()).isEqualTo(new ImportStore.Stopped("filing", 1200, 2140, "2026-10-09T08:00:00Z"));
        assertThat(store.run(walking).stopped()).isEqualTo(new ImportStore.Stopped("reading", 0, 0, "2026-10-09T08:00:00Z"));
        assertThat(store.run(done).stopped()).isNull();
        assertThat(store.markStopped()).as("nothing is active any more").isZero();
        assertThat(store.run(filing).stopped()).as("still remembered").isNotNull();

        store.clearStopped(filing);
        store.finish(walking);
        assertThat(store.run(filing).stopped()).isNull();
        assertThat(store.run(walking).stopped()).isNull();
        assertThat(store.run(walking).state()).isEqualTo("finished");
    }

    @Test
    void aStoppedFilingStaysWhenAContinuesWalkOrReadIsCutShortToo() throws Exception {
        long run = store.create(folder("Photos")).id();
        store.setActive(run, "filing", 300, 400);
        store.markStopped();

        for (String phase : List.of("walking", "reading")) {
            store.setActive(run, phase, 12, 40); // the user continued; the app closed again mid-walk or mid-read
            assertThat(store.markStopped()).isEqualTo(1);
            assertThat(active(run)).isNull();
            assertThat(store.run(run).stopped()).as("not relabelled as reading 0 of 0 after a " + phase)
                    .isEqualTo(new ImportStore.Stopped("filing", 300, 400, "2026-10-09T08:00:00Z"));
        }

        store.setActive(run, "filing", 350, 400); // filed again and was cut short again
        store.markStopped();
        assertThat(store.run(run).stopped()).isEqualTo(new ImportStore.Stopped("filing", 350, 400, "2026-10-09T08:00:00Z"));

        long reading = store.create(folder("Reading")).id();
        store.setActive(reading, "reading", 5, 10);
        store.markStopped();
        store.setActive(reading, "filing", 2, 8);
        store.markStopped();
        assertThat(store.run(reading).stopped()).as("a stopped reading gives way to a stopped filing")
                .isEqualTo(new ImportStore.Stopped("filing", 2, 8, "2026-10-09T08:00:00Z"));
    }

    @Test
    void pausingIsRememberedWithoutAWarning() throws Exception {
        long run = store.create(folder("Photos")).id();

        store.setPaused(run, true);
        assertThat(store.run(run).paused()).isTrue();
        assertThat(store.run(run).stopped()).isNull();
        store.setPaused(run, false);
        assertThat(store.run(run).paused()).isFalse();
    }

    @Test
    void previewsLiveUnderTheThumbnailFolder() {
        Path data = dir.resolve("Atlas");

        assertThat(ImportStore.previewFile(data, 3, 812, "a1b2c3d4"))
                .isEqualTo(data.resolve(".thumbs").resolve("import").resolve("3").resolve("812-a1b2c3d4.jpg"));
    }

    @Test
    void theItemsPageSeesOneStateEvenWhenAChangeCommitsBetweenItsReads() throws Exception {
        long run = store.create(folder("Photos")).id();
        Database db = library.database();
        item(db, run, "a.jpg");
        AtomicBoolean once = new AtomicBoolean();
        ImportStore racing = new ImportStore(() -> library, TestBundles.CLOCK, () -> {
            if (once.compareAndSet(false, true)) {
                item(db, run, "late.jpg"); // commits between the version read and the items read
            }
        });

        ImportStore.ItemsPage page = racing.items(run, null);

        assertThat(once).isTrue();
        assertThat(page.version()).isEqualTo(1);
        assertThat(page.items()).as("only what version 1 covers").extracting(ImportStore.Item::path).containsExactly("a.jpg");
        ImportStore.ItemsPage next = racing.items(run, page.version());
        assertThat(next.version()).isEqualTo(2);
        assertThat(next.items()).as("the change is sent next time, not skipped").extracting(ImportStore.Item::path)
                .containsExactly("late.jpg");
    }

    private String active(long run) {
        return library.database().read(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT active_phase || '|' || active_done || '|' || active_total"
                    + " FROM import_run WHERE id = ?")) {
                ps.setLong(1, run);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getString(1);
                }
            }
        });
    }

    @Test
    void savedProgressLandsOnlyOnThePhaseStillRunning() throws Exception {
        long run = store.create(folder("Photos")).id();
        store.setActive(run, "reading", 0, 0);

        store.saveProgress(run, "reading", 5, 10);
        assertThat(active(run)).isEqualTo("reading|5|10");
        store.saveProgress(run, "walking", 7, 0);
        assertThat(active(run)).as("another phase's progress").isEqualTo("reading|5|10");

        store.setActive(run, null, 0, 0);
        store.saveProgress(run, "reading", 9, 10);
        assertThat(active(run)).as("a save after the phase ended never marks it running again").isNull();
        assertThat(store.markStopped()).isZero();
    }

    @Test
    void theCountsOfEveryImportComeInOneGo() throws Exception {
        long a = store.create(folder("One")).id();
        long b = store.create(folder("Two")).id();
        long empty = store.create(folder("Three")).id();
        Database db = library.database();
        item(db, a, "x.jpg");
        item(db, a, "y.jpg", "read_state", "read", "taxon_id", "A5");
        item(db, a, "z.jpg", "read_state", "read", "suggested_taxon_id", "A5");
        item(db, b, "v.mov", "read_state", "unsupported", "problem", "Video");

        Map<Long, ImportStore.Counts> counts = store.countsByRun();

        assertThat(counts).containsOnlyKeys(a, b, empty);
        for (long run : List.of(a, b, empty)) {
            assertThat(counts.get(run)).isEqualTo(store.counts(run));
        }
        assertThat(counts.get(a)).isEqualTo(new ImportStore.Counts(3, 3, 1, 0, 1, 1, 0, 0, 0, 0));
        assertThat(counts.get(empty).total()).isZero();
    }

    @Test
    void aNoticeStaysUntilClearedOrTheImportIsFinished() throws Exception {
        long run = store.create(folder("Photos")).id();
        assertThat(store.run(run).notice()).isNull();

        store.setNotice(run, "Your library's drive is full.");
        assertThat(store.run(run).notice()).isEqualTo("Your library's drive is full.");
        store.setNotice(run, null);
        assertThat(store.run(run).notice()).isNull();

        store.setNotice(run, "Your library's drive is full.");
        store.finish(run);
        assertThat(store.run(run).notice()).isNull();
    }
}
