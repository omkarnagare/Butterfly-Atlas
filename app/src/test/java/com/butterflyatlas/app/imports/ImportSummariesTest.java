package com.butterflyatlas.app.imports;

import com.butterflyatlas.app.db.TestDatabases;
import com.butterflyatlas.app.library.Library;
import com.butterflyatlas.app.testing.TestBundles;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** The import working now and the stopped ones, for {@code /api/status}: from memory (design D9). */
class ImportSummariesTest {

    @TempDir
    Path dir;

    private final CountDownLatch walking = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);
    private Library library;
    private ImportStore store;
    private ImportJobs jobs;
    private ImportSummaries summaries;

    @BeforeEach
    void setUp() {
        library = TestBundles.fixtureLibrary(dir.resolve("Atlas"));
        store = new ImportStore(() -> library, TestBundles.CLOCK);
        jobs = new ImportJobs(new ImportJobs.Work() {
            @Override
            public boolean walk(long runId, ImportJobs.Reporter reporter, java.util.function.BooleanSupplier stop) {
                reporter.report(4, 0);
                walking.countDown();
                try {
                    release.await(20, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return false;
            }

            @Override
            public void read(long runId, ImportJobs.Reporter reporter, java.util.function.BooleanSupplier stop) {
            }

            @Override
            public void file(long runId, ImportJobs.Reporter reporter, java.util.function.BooleanSupplier stop) {
            }
        });
        summaries = new ImportSummaries(store, jobs, new HashSet<>(), runId -> 0, runId -> 0);
    }

    @AfterEach
    void close() {
        release.countDown();
        jobs.close();
    }

    @Test
    void theImportWorkingNowIsServedFromMemoryEvenJustAfterItFinished() throws Exception {
        long id = store.create(Files.createDirectories(dir.resolve("Butterflies 2024"))).id();
        jobs.walkAndRead(id);
        assertThat(walking.await(20, TimeUnit.SECONDS)).isTrue();
        store.finish(id); // the job is still giving way when the import is finished
        summaries.stopped(); // the first look reads the library once

        AtomicInteger opened = TestDatabases.countConnections(library.database());
        for (int i = 0; i < 5; i++) {
            Optional<Imports.Working> working = summaries.working();
            assertThat(working).map(Imports.Working::rootName).contains("Butterflies 2024");
            assertThat(summaries.stopped()).isEmpty();
        }
        assertThat(opened.get()).as("no SQL").isZero();
    }

    @Test
    void everyStoreOfALibrarySharesTheMemorySoAnotherStoresWriteIsSeen() throws Exception {
        ImportStore other = new ImportStore(() -> library, TestBundles.CLOCK);
        long id = other.create(Files.createDirectories(dir.resolve("Butterflies 2024"))).id();
        other.setActive(id, "filing", 2, 5);
        other.markStopped();
        assertThat(summaries.stopped()).extracting(Imports.Working::id).containsExactly(id);

        other.finish(id);

        AtomicInteger opened = TestDatabases.countConnections(library.database());
        assertThat(summaries.stopped()).isEmpty();
        assertThat(store.briefs()).singleElement().satisfies(run -> assertThat(run.open()).isFalse());
        assertThat(opened.get()).as("no SQL").isZero();
    }
}
