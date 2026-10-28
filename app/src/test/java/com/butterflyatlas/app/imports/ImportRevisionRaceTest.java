package com.butterflyatlas.app.imports;

import com.butterflyatlas.app.AtlasException;
import com.butterflyatlas.app.library.LibraryHolder;
import com.butterflyatlas.app.library.OpenImports;
import com.butterflyatlas.app.photos.TagService;
import com.butterflyatlas.app.testing.TestImages;
import com.butterflyatlas.app.testing.TestServices;
import com.butterflyatlas.app.web.AtlasServices;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A delete that clears an import's choice racing that import's File (multi-window review fix 3): File's revision check
 * and the start of filing run under the database's write lock, and the delete's revisions go up only once it commits,
 * so a File held back by the delete is refused, never filed with a revision taken before the delete.
 */
class ImportRevisionRaceTest {

    private static final Duration WAIT = Duration.ofSeconds(20);

    @TempDir
    Path home;

    private final LibraryHolder holder = new LibraryHolder();
    private AtlasServices services;
    private Imports imports;
    private long id;
    private TagService.Tag tag;

    @BeforeEach
    void start() throws Exception {
        services = TestServices.configured(home, holder);
        imports = services.imports();
        Path photos = Files.createDirectories(home.resolve("Pictures"));
        Files.write(photos.resolve("a.jpg"), TestImages.jpeg(64, 48, 71));
        id = imports.start(photos.toString()).summary().id();
        assertThat(imports.awaitIdle(WAIT)).isTrue();
        List<Long> items = imports.items(id, null).items().stream().map(ImportStore.Item::id).toList();
        tag = services.tags().create("Garden");
        imports.assign(id, items, "A5");
        imports.tags(id, items, List.of(tag.key()), List.of());
    }

    @AfterEach
    void stop() {
        holder.library().openImports().afterMarked = () -> { };
        imports.close();
        services.thumbnails().close();
        services.placeNotes().close();
    }

    @Test
    void aFileThatTheDeletesWriteHeldBackIsRefusedAsChanged() throws Exception {
        long held = imports.revision(id); // what the window has
        OpenImports open = holder.library().openImports();
        CountDownLatch marked = new CountDownLatch(1);
        CountDownLatch letGo = new CountDownLatch(1);
        open.afterMarked = () -> {
            marked.countDown();
            try {
                letGo.await(20, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> deleting = pool.submit(() -> services.tags().delete(tag.id()));
            assertThat(marked.await(10, TimeUnit.SECONDS)).isTrue(); // inside the delete's write, not committed yet
            assertThat(imports.revision(id)).as("not up before the commit").isEqualTo(held);
            open.afterMarked = () -> { };
            // refused at once (the import is marked changing) or, had it got past that, when the delete's write lets go
            Future<Imports.Summary> filing = pool.submit(() -> imports.file(id, held));
            Thread.sleep(300);

            letGo.countDown();
            deleting.get(20, TimeUnit.SECONDS);
            assertThatThrownBy(() -> filing.get(20, TimeUnit.SECONDS)).cause()
                    .isInstanceOfSatisfying(AtlasException.class, e -> assertThat(e.code()).isEqualTo("import_changed"));
        } finally {
            letGo.countDown();
            pool.shutdownNow();
        }
        assertThat(imports.revision(id)).as("up once the delete committed").isEqualTo(held + 1);
        assertThat(open.changing(id)).isFalse();
        assertThat(imports.summary(id).filings()).as("nothing was filed").isZero();
    }

    @Test
    void aDeleteThatDoesNotCommitLeavesTheRevisionAsItWas() throws Exception {
        long held = imports.revision(id);
        holder.library().openImports().afterMarked = () -> {
            throw new IllegalStateException("the write fails");
        };

        assertThatThrownBy(() -> services.tags().delete(tag.id())).isInstanceOf(IllegalStateException.class);

        assertThat(imports.revision(id)).isEqualTo(held);
        assertThat(holder.library().openImports().changing(id)).isFalse();
        holder.library().openImports().afterMarked = () -> { };
        imports.file(id, held);
        assertThat(imports.awaitIdle(WAIT)).isTrue(); // the filing (and its count) begins in the background
        assertThat(imports.summary(id).filings()).isEqualTo(1);
    }
}
