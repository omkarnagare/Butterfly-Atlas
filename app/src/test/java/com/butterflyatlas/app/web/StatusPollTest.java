package com.butterflyatlas.app.web;

import com.butterflyatlas.app.db.TestDatabases;
import com.butterflyatlas.app.imports.Imports;
import com.butterflyatlas.app.library.LibraryHolder;
import com.butterflyatlas.app.photos.Photo;
import com.butterflyatlas.app.testing.TestImages;
import com.butterflyatlas.app.testing.TestServices;
import com.butterflyatlas.app.testing.UpdateBundles;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** {@code /api/status} from memory (design D9): Recently deleted and stopped imports are kept current by their writes. */
class StatusPollTest {

    @TempDir
    Path home;

    private final LibraryHolder holder = new LibraryHolder();
    private AtlasServices services;
    private StatusRoutes status;

    @BeforeEach
    void open() {
        services = TestServices.configured(home, holder);
        status = new StatusRoutes(services.rescan(), services.thumbnails(), services.photos(), services.processing(),
                services.imports(), services.exports(), services.updates(), services.drive(),
                services.placeNotes());
    }

    @AfterEach
    void close() {
        status.close();
        services.placeNotes().close();
        services.imports().close();
        services.exports().close();
        services.thumbnails().close();
    }

    private Photo photo(int seed) {
        return services.photos().add("A5", new ByteArrayInputStream(TestImages.jpeg(40, 30, seed)), "image/jpeg", null, List.of())
                .photo();
    }

    /** Polls five times; each answer is checked, and no poll may open a connection. */
    private void polls(int deleted, List<Imports.Working> stopped) {
        AtomicInteger opened = TestDatabases.countConnections(holder.get());
        for (int i = 0; i < 5; i++) {
            StatusRoutes.Status now = status.status();
            assertThat(now.deletedCount()).isEqualTo(deleted);
            assertThat(now.stoppedImports()).isEqualTo(stopped);
        }
        assertThat(opened.get()).as("a poll runs no SQL").isZero();
    }

    @Test
    void aPollRunsNoSqlAndFollowsTheTaxonomyUpdateItsSplitsAndMatches() throws Exception {
        Photo a = photo(1);
        photo(2);
        services.custom().add("A1", "Neptis sankara", null);
        close();
        LibraryHolder reopened = new LibraryHolder();
        services = TestServices.configured(home, UpdateBundles.bundles(UpdateBundles.fixture(),
                UpdateBundles.derive(UpdateBundles.fixture(), "2026-02-01").add("A16", "A4", "species", "Danaus petilia", null)
                        .split("A5", "A5", "A16").add("A20", "A3", "genus", "Neptis", null)
                        .add("A21", "A20", "species", "Neptis sankara", null).build()), reopened);
        status = new StatusRoutes(services.rescan(), services.thumbnails(), services.photos(), services.processing(),
                services.imports(), services.exports(), services.updates(), services.drive(),
                services.placeNotes());
        status.status(); // Recently deleted, the imports and the exports were read when the library opened
        AtomicInteger opened = TestDatabases.countConnections(reopened.get());
        java.util.function.Supplier<StatusRoutes.Status> poll = () -> {
            int before = opened.get();
            StatusRoutes.Status now = status.status();
            assertThat(opened.get()).as("a poll runs no SQL").isEqualTo(before);
            return now;
        };

        StatusRoutes.Status pending = poll.get();
        services.updates().apply();
        assertThat(services.updates().awaitIdle(java.time.Duration.ofSeconds(30))).isTrue();
        StatusRoutes.Status done = poll.get();
        services.splits().sort("A5", List.of(a.id()), "A16");
        StatusRoutes.Status sorted = poll.get();
        services.updates().decline("U1", "A21");
        StatusRoutes.Status declined = poll.get();

        assertThat(pending.taxonomyUpdate().state()).isEqualTo("pending");
        assertThat(List.of(pending.splitsToSort(), pending.customMatches())).containsExactly(0, 0);
        assertThat(done.taxonomyUpdate().state()).isEqualTo("done");
        assertThat(List.of(done.splitsToSort(), done.customMatches())).containsExactly(1, 1);
        assertThat(List.of(sorted.splitsToSort(), sorted.customMatches())).containsExactly(1, 1);
        assertThat(done.libraryVersion()).as("the update's moves").isGreaterThan(pending.libraryVersion());
        assertThat(sorted.libraryVersion()).as("a split sort").isGreaterThan(done.libraryVersion());
        assertThat(List.of(declined.splitsToSort(), declined.customMatches())).containsExactly(1, 0);
        services.splits().sort("A5", List.of(services.splits().split("A5").photos().getFirst().id()), "A5");
        assertThat(poll.get().splitsToSort()).isZero();
    }

    @Test
    void theLibrarysVersionGoesUpWithEveryWriteThatChangesPhotosAndIsReadFromMemory() throws Exception {
        long start = status.status().libraryVersion();
        Photo a = photo(1);
        long added = status.status().libraryVersion();
        assertThat(added).as("an upload").isGreaterThan(start);
        services.photos().move(List.of(a.id()), "A6");
        long moved = status.status().libraryVersion();
        assertThat(moved).as("a move").isGreaterThan(added);
        services.photos().delete(List.of(a.id()));
        long deleted = status.status().libraryVersion();
        assertThat(deleted).as("a delete").isGreaterThan(moved);
        services.photos().restore(List.of(a.id()));
        long restored = status.status().libraryVersion();
        assertThat(restored).as("a restore").isGreaterThan(deleted);
        services.photos().delete(List.of(a.id()));
        long again = status.status().libraryVersion();
        services.photos().purge(List.of());
        long emptied = status.status().libraryVersion();
        assertThat(emptied).as("Empty").isGreaterThan(again);
        var custom = services.custom().add("A1", "Neptis sankara", null);
        photo(3);
        long before = status.status().libraryVersion();
        services.custom().rename(custom.id(), "Neptis sankarae", null);
        long renamed = status.status().libraryVersion();
        assertThat(renamed).as("a custom species renamed").isGreaterThan(before);
        services.custom().delete(custom.id());
        assertThat(status.status().libraryVersion()).as("a custom species deleted").isGreaterThan(renamed);

        AtomicInteger opened = TestDatabases.countConnections(holder.get());
        status.status().libraryVersion();
        assertThat(opened.get()).as("a poll runs no SQL").isZero();
    }

    @Test
    void theLibrarysVersionGoesUpWhenAnImportFiles() throws Exception {
        Path folder = Files.createDirectories(home.resolve("Pictures"));
        Files.write(folder.resolve("a.jpg"), TestImages.jpeg(64, 48, 81));
        long id = services.imports().start(folder.toString()).summary().id();
        assertThat(services.imports().awaitIdle(java.time.Duration.ofSeconds(20))).isTrue();
        services.imports().assign(id, services.imports().items(id, null).items().stream()
                .map(com.butterflyatlas.app.imports.ImportStore.Item::id).toList(), "A5");
        long before = status.status().libraryVersion();

        services.imports().file(id, services.imports().revision(id));
        assertThat(services.imports().awaitIdle(java.time.Duration.ofSeconds(20))).isTrue();

        assertThat(services.imports().summary(id).counts().filed()).isEqualTo(1);
        assertThat(status.status().libraryVersion()).as("File").isGreaterThan(before);
    }

    @Test
    void aPollRunsNoSqlAndFollowsDeletesRestoresAndPurges() {
        Photo a = photo(1);
        Photo b = photo(2);
        status.status(); // read when the library opened
        polls(0, List.of());

        services.photos().delete(List.of(a.id(), b.id()));
        polls(2, List.of());
        services.photos().restore(List.of(a.id()));
        polls(1, List.of());
        services.photos().purge(List.of());
        polls(0, List.of());
    }

    @Test
    void aPollRunsNoSqlAndFollowsStoppedImports() throws Exception {
        Path folder = Files.createDirectories(home.resolve("Butterflies 2024"));
        long id = services.imports().store().create(folder).id();
        long other = services.imports().store().create(Files.createDirectories(home.resolve("More"))).id();
        status.status();
        polls(0, List.of());

        services.imports().store().setActive(id, "filing", 3, 9); // as the app left it when it closed mid-filing
        services.imports().store().setActive(other, "reading", 1, 4);
        services.imports().startup();
        polls(0, List.of(new Imports.Working(other, "More", "reading", 1, 4),
                new Imports.Working(id, "Butterflies 2024", "filing", 3, 9)));

        services.imports().store().clearStopped(other);
        polls(0, List.of(new Imports.Working(id, "Butterflies 2024", "filing", 3, 9)));
        services.imports().discard(id);
        polls(0, List.of());
    }
}
