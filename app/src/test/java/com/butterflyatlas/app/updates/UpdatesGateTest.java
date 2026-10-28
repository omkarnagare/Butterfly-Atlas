package com.butterflyatlas.app.updates;

import com.butterflyatlas.app.AtlasException;
import com.butterflyatlas.app.backup.Backups;
import com.butterflyatlas.app.backup.TestBackups;
import com.butterflyatlas.app.exports.Exports;
import com.butterflyatlas.app.imports.ImportStore;
import com.butterflyatlas.app.library.Library;
import com.butterflyatlas.app.library.LibraryHolder;
import com.butterflyatlas.app.library.TaxonomyGate;
import com.butterflyatlas.app.photos.Photo;
import com.butterflyatlas.app.photos.SpeciesFiles;
import com.butterflyatlas.app.taxonomy.TaxonomyBundles;
import com.butterflyatlas.app.taxonomy.bundle.Bundle;
import com.butterflyatlas.app.testing.HoldingProcessor;
import com.butterflyatlas.app.testing.TestClock;
import com.butterflyatlas.app.testing.TestImages;
import com.butterflyatlas.app.testing.TestServices;
import com.butterflyatlas.app.testing.UpdateBundles;
import com.butterflyatlas.app.web.AtlasServices;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * While an update runs, waits or is paused, the changes that move or file photos or change custom species are refused
 * (409 {@code taxonomy_updating}) and everything else works; Apply waits while an import files or an export copies; the
 * switch-over remaps import items (5b contract §3).
 */
class UpdatesGateTest {

    private static final Duration WAIT = Duration.ofSeconds(30);

    @TempDir
    Path home;

    private final HoldingProcessor processor = new HoldingProcessor();
    private final TestClock clock = new TestClock();
    private final LibraryHolder holder = new LibraryHolder();
    private AtlasServices services;
    private Library library;
    private int seed;

    @BeforeEach
    void setUp() {
        services = TestServices.configured(home, processor, holder);
        library = holder.library();
    }

    @AfterEach
    void tearDown() {
        processor.release();
        services.imports().close();
        services.exports().close();
        services.backups().close();
        services.thumbnails().close();
        services.placeNotes().close();
    }

    private Photo add(String taxonId) {
        return services.photos().add(taxonId, new ByteArrayInputStream(TestImages.jpeg(30 + ++seed, 20, seed)), "image/jpeg",
                null, List.of()).photo();
    }

    private Updates updates(TaxonomyBundles bundles) {
        Updates updates = new Updates(holder::library, bundles, services.imports(), services.exports(), services.backups(), clock);
        updates.startup();
        return updates;
    }

    private static void refused(ThrowingCallable call) {
        refused(call, "Wait for the taxonomy update to finish.");
    }

    /** Refused while the update is paused: the words say so and what to do. */
    private static void refusedPaused(ThrowingCallable call) {
        refused(call, "The taxonomy update is paused, so this can't be done yet. Open Taxonomy update to see why, then"
                + " choose Retry.");
    }

    private static void refused(ThrowingCallable call, String message) {
        assertThatThrownBy(call).isInstanceOfSatisfying(AtlasException.class, e -> {
            assertThat(e.kind()).isEqualTo(AtlasException.Kind.CONFLICT);
            assertThat(e.code()).isEqualTo(TaxonomyGate.CODE);
            assertThat(e.getMessage()).isEqualTo(message);
        });
    }

    /** An import of two photos read and waiting, its items. */
    private long importOfTwo(String folderName) throws Exception {
        Path photos = Files.createDirectories(home.resolve("Pictures").resolve(folderName));
        Files.write(photos.resolve("IMG_0001.JPG"), TestImages.jpeg(64, 48, 1000 + ++seed));
        Files.write(photos.resolve("IMG_0002.JPG"), TestImages.jpeg(64, 48, 1000 + ++seed));
        long id = services.imports().start(photos.toString()).summary().id();
        assertThat(services.imports().awaitIdle(WAIT)).isTrue();
        return id;
    }

    private List<Long> items(long importId) {
        return services.imports().items(importId, null).items().stream().map(ImportStore.Item::id).toList();
    }

    @Test
    void whilePausedEveryChangeThatMovesPhotosIsRefusedAndTheRestWorks() throws Exception {
        Photo genutia = add("A6");
        add("A6");
        Photo deleted = add("A5");
        services.photos().delete(List.of(deleted.id()));
        services.custom().add("A1", "Neptis sankara", null);
        long importId = importOfTwo("Butterflies 2024");
        services.imports().assign(importId, items(importId), "A5");
        AtomicInteger calls = new AtomicInteger();
        Updates updates = new Updates(holder::library, UpdateBundles.bundles(UpdateFixture.v1(), UpdateFixture.v2()), clock,
                () -> null, (from, to) -> {
                    if (calls.incrementAndGet() == 2) {
                        throw new AccessDeniedException(from.toString());
                    }
                    SpeciesFiles.moveFile(from, to);
                }, path -> Long.MAX_VALUE);
        updates.startup();
        updates.apply();
        assertThat(updates.awaitIdle(WAIT)).isTrue();
        assertThat(updates.update().state()).isEqualTo("paused");

        refusedPaused(() -> add("A5"));
        refusedPaused(() -> services.photos().move(List.of(genutia.id()), "A5"));
        refusedPaused(() -> services.photos().delete(List.of(genutia.id())));
        refusedPaused(() -> services.photos().restore(List.of(deleted.id())));
        refusedPaused(() -> services.photos().purge(List.of()));
        refusedPaused(() -> services.custom().add("A1", "Neptis soma", null));
        refusedPaused(() -> services.custom().rename("U1", "Neptis soma", null));
        refusedPaused(() -> services.custom().delete("U1"));
        refusedPaused(() -> services.exports().start(new Exports.Request("", "photos", home.resolve("Backup").toString(), null,
                false, "none")));
        refusedPaused(() -> services.exports().resume(99));
        refusedPaused(() -> services.imports().file(importId));
        assertThat(services.photos().purgeOlderThan(Duration.ZERO)).as("the startup's purge waits for the next start").isZero();
        assertThat(services.photos().deletedCount()).isEqualTo(1);

        assertThat(services.tags().create("basking").label()).isEqualTo("basking");
        assertThat(services.searchWords().add("A5", "tiger")).contains("tiger");
        assertThat(services.photos().setCover("A6", genutia.id())).isEqualTo(genutia.id());
        assertThat(services.exports().check("", "photos", home.toString()).photos()).isEqualTo(2);
        assertThat(services.imports().assign(importId, items(importId), "A12").items()).hasSize(2);

        updates.retry();
        assertThat(updates.awaitIdle(WAIT)).isTrue();
        assertThat(updates.update().state()).isEqualTo("done");
        assertThat(add("A5").taxonId()).as("open again").isEqualTo("A5");
        assertThat(services.custom().rename("U1", "Neptis soma", null).name()).isEqualTo("Neptis soma");
    }

    @Test
    void applyWaitsWhileAnImportIsFilingThenItsPhotosMoveWithTheRest() throws Exception {
        add("A6");
        long importId = importOfTwo("Butterflies 2024");
        services.imports().assign(importId, items(importId), "A6");
        processor.hold((call, file) -> call.equals("read") && file.toString().contains(".incoming"));
        services.imports().file(importId);
        assertThat(processor.awaitHeld()).isTrue();
        Updates updates = updates(UpdateBundles.bundles(UpdateFixture.v1(), UpdateFixture.v2()));

        updates.apply();
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (!"waiting".equals(updates.status().orElseThrow().state()) && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }

        UpdateViews.Status waiting = updates.status().orElseThrow();
        assertThat(waiting.state()).isEqualTo("waiting");
        assertThat(waiting.waitingFor()).isEqualTo("import");
        assertThat(updates.update().progress().waitingFor()).isEqualTo("import");
        refused(() -> add("A5"));
        refused(() -> services.imports().file(importId));

        processor.release();
        assertThat(services.imports().awaitIdle(WAIT)).isTrue();
        assertThat(updates.awaitIdle(WAIT)).isTrue();

        assertThat(updates.update().state()).isEqualTo("done");
        assertThat(rows("SELECT rel_path FROM image WHERE taxon_id = 'A6' AND deleted_at IS NULL ORDER BY number")).containsExactly(
                "images/Nymphalidae/Danainae/Danaini/Danaus/Danaus_melanippus_[A6]/Danaus_melanippus_01.jpg",
                "images/Nymphalidae/Danainae/Danaini/Danaus/Danaus_melanippus_[A6]/Danaus_melanippus_02.jpg",
                "images/Nymphalidae/Danainae/Danaini/Danaus/Danaus_melanippus_[A6]/Danaus_melanippus_03.jpg");
        assertThat(rows("SELECT COUNT(*) FROM import_item WHERE outcome = 'filed'")).containsExactly("2");
    }

    @Test
    void applyWaitsWhileABackupIsCopyingThenTheUpdateGoesOn() throws Exception {
        add("A6");
        add("A6");
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        TestBackups.beforePhoto(services.backups(), id -> {
            inside.countDown();
            try {
                release.await(WAIT.toSeconds(), TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        Path destination = Files.createDirectories(home.resolve("Backup"));
        Backups.Backup made = services.backups().start(new Backups.Request("everything", destination.toString()));
        assertThat(inside.await(10, TimeUnit.SECONDS)).isTrue();
        Updates updates = updates(UpdateBundles.bundles(UpdateFixture.v1(), UpdateFixture.v2()));

        updates.apply();
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (!"waiting".equals(updates.status().orElseThrow().state()) && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }

        assertThat(updates.status().orElseThrow().state()).isEqualTo("waiting");
        assertThat(updates.status().orElseThrow().waitingFor()).isEqualTo("backup");
        assertThat(services.backups().copying()).isTrue();
        release.countDown();
        assertThat(updates.awaitIdle(WAIT)).isTrue();
        assertThat(updates.update().state()).isEqualTo("done");
        deadline = System.nanoTime() + WAIT.toNanos();
        while (services.backups().copying() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(services.backups().detail(made.id()).backup().state()).isEqualTo("done");
    }

    @Test
    void applyWaitsForABackupOfEverythingPausedBeforeItsPhotosAreCopiedAndSaysSo() throws Exception {
        add("A6");
        add("A6");
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        TestBackups.beforePhoto(services.backups(), id -> {
            if (inside.getCount() > 0) {
                inside.countDown();
                try {
                    release.await(WAIT.toSeconds(), TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        Path destination = Files.createDirectories(home.resolve("Backup"));
        Backups.Backup made = services.backups().start(new Backups.Request("everything", destination.toString()));
        assertThat(inside.await(10, TimeUnit.SECONDS)).isTrue();
        services.backups().pause(made.id());
        release.countDown();
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (services.backups().copying() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(services.backups().photosHeldBy()).contains("paused");
        Updates updates = updates(UpdateBundles.bundles(UpdateFixture.v1(), UpdateFixture.v2()));

        updates.apply();
        deadline = System.nanoTime() + WAIT.toNanos();
        while (!"waiting".equals(updates.status().orElseThrow().state()) && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }

        assertThat(updates.status().orElseThrow().waitingFor()).isEqualTo("backup_paused");
        services.backups().cancel(made.id(), false); // discarded: the update goes on
        assertThat(updates.awaitIdle(WAIT)).isTrue();
        assertThat(updates.update().state()).isEqualTo("done");
    }

    @Test
    void applyWaitsWhileAnExportIsCopying() throws Exception {
        AtomicReference<String> busy = new AtomicReference<>("export");
        add("A6");
        Updates updates = new Updates(holder::library, UpdateBundles.bundles(UpdateFixture.v1(), UpdateFixture.v2()), clock,
                busy::get, SpeciesFiles::moveFile, path -> Long.MAX_VALUE);
        updates.startup();

        updates.apply();
        Thread.sleep(300);

        assertThat(updates.status().orElseThrow().state()).isEqualTo("waiting");
        assertThat(updates.status().orElseThrow().waitingFor()).isEqualTo("export");
        assertThat(rows("SELECT stage, done FROM taxonomy_update")).as("nothing planned yet").containsExactly("plan|0");
        refused(() -> add("A6"));
        busy.set(null);
        assertThat(updates.awaitIdle(WAIT)).isTrue();
        assertThat(updates.update().state()).isEqualTo("done");
        assertThat(services.exports().copying()).isFalse();
        assertThat(services.imports().filing()).isFalse();
    }

    @Test
    void importItemsOfAMergedSpeciesFollowItAndThoseOfASplitSpeciesAreUnassignedWithANotice() throws Exception {
        long first = importOfTwo("Butterflies 2024");
        List<Long> firstItems = items(first);
        services.imports().assign(first, List.of(firstItems.get(0)), "A14");
        services.imports().assign(first, List.of(firstItems.get(1)), "A5");
        long second = importOfTwo("Butterflies 2025");
        services.imports().assign(second, items(second), "A5");
        long before = services.imports().items(first, null).version();
        Bundle v2 = UpdateBundles.derive(UpdateFixture.v1(), "2026-02-01")
                .add("A15", "A11", "species", "Papilio agestor", null).merge("A15", "A14")
                .add("A16", "A4", "species", "Danaus petilia", null).add("A17", "A4", "species", "Danaus alcippus", null)
                .split("A5", "A16", "A17").build();
        Updates updates = updates(UpdateBundles.bundles(UpdateFixture.v1(), v2));

        updates.apply();
        assertThat(updates.awaitIdle(WAIT)).isTrue();

        assertThat(services.imports().items(first, null).items()).extracting(ImportStore.Item::taxonId)
                .containsExactlyInAnyOrder("A15", null);
        assertThat(services.imports().items(first, null).version()).as("stamped: the grid hears of it").isGreaterThan(before);
        assertThat(services.imports().summary(first).updateNote()).isEqualTo("1 photo was assigned to Danaus chrysippus, which was"
                + " split. Choose their species again.");
        assertThat(services.imports().items(second, null).items()).extracting(ImportStore.Item::taxonId).containsOnlyNulls();
        assertThat(services.imports().summary(second).updateNote()).isEqualTo("2 photos were assigned to Danaus chrysippus, which"
                + " was split. Choose their species again.");
        assertThat(rows("PRAGMA foreign_key_check")).isEmpty();
    }

    @Test
    void itemsOfASplitSpeciesThatKeepsItsIdAreUnassignedToo() throws Exception {
        long importId = importOfTwo("Butterflies 2024");
        services.imports().assign(importId, items(importId), "A5");
        Updates updates = updates(UpdateBundles.bundles(UpdateFixture.v1(), UpdateFixture.v2())); // A5 split into itself, A16

        updates.apply();
        assertThat(updates.awaitIdle(WAIT)).isTrue();

        assertThat(services.imports().items(importId, null).items()).extracting(ImportStore.Item::taxonId).containsOnlyNulls();
        assertThat(services.imports().summary(importId).updateNote()).isEqualTo("2 photos were assigned to Danaus chrysippus,"
                + " which was split. Choose their species again.");
    }

    @Test
    void aSplitsNoteIsAddedToTheImportsUpdateNoteCountingSuggestionsAndNeverTouchesTheNotice() throws Exception {
        long importId = importOfTwo("Butterflies 2024");
        List<Long> items = items(importId);
        services.imports().assign(importId, List.of(items.get(0)), "A5");
        library.database().write(c -> {
            try (var ps = c.prepareStatement("UPDATE import_item SET suggested_taxon_id = 'A5', taxon_id = NULL WHERE id = ?")) {
                ps.setLong(1, items.get(1));
                ps.executeUpdate();
            }
            try (var ps = c.prepareStatement("UPDATE import_run SET update_note = 'Earlier note.', notice = 'Filing stopped.'"
                    + " WHERE id = ?")) {
                ps.setLong(1, importId);
                ps.executeUpdate();
            }
            return null;
        });
        Updates updates = updates(UpdateBundles.bundles(UpdateFixture.v1(), UpdateFixture.v2()));

        updates.apply();
        assertThat(updates.awaitIdle(WAIT)).isTrue();

        assertThat(services.imports().summary(importId).updateNote()).isEqualTo("Earlier note. 1 photo was assigned to Danaus"
                + " chrysippus and 1 suggested as it, which was split. Choose their species again.");
        assertThat(services.imports().summary(importId).notice()).isEqualTo("Filing stopped.");
        assertThat(services.imports().items(importId, null).items()).extracting(ImportStore.Item::suggestedTaxonId)
                .containsOnlyNulls();
    }

    @Test
    void aSuggestionOfAnItemAssignedElsewhereIsNotCountedInTheNote() throws Exception {
        long importId = importOfTwo("Butterflies 2024");
        List<Long> items = items(importId);
        services.imports().assign(importId, List.of(items.get(0)), "A5");
        services.imports().assign(importId, List.of(items.get(1)), "A6");
        library.database().write(c -> {
            try (var ps = c.prepareStatement("UPDATE import_item SET suggested_taxon_id = 'A5' WHERE id = ?")) {
                ps.setLong(1, items.get(1));
                ps.executeUpdate();
            }
            return null;
        });
        Updates updates = updates(UpdateBundles.bundles(UpdateFixture.v1(), UpdateFixture.v2()));

        updates.apply();
        assertThat(updates.awaitIdle(WAIT)).isTrue();

        assertThat(services.imports().summary(importId).updateNote()).isEqualTo("1 photo was assigned to Danaus chrysippus,"
                + " which was split. Choose their species again.");
        assertThat(services.imports().items(importId, null).items()).extracting(ImportStore.Item::taxonId)
                .containsExactlyInAnyOrder(null, "A6");
    }

    private List<String> rows(String sql) {
        return library.database().read(c -> {
            List<String> rows = new java.util.ArrayList<>();
            try (var ps = c.prepareStatement(sql); var rs = ps.executeQuery()) {
                int n = rs.getMetaData().getColumnCount();
                while (rs.next()) {
                    StringBuilder row = new StringBuilder();
                    for (int i = 1; i <= n; i++) {
                        row.append(i > 1 ? "|" : "").append(rs.getString(i));
                    }
                    rows.add(row.toString());
                }
            }
            return rows;
        });
    }
}
