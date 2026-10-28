package com.butterflyatlas.app.updates;

import com.butterflyatlas.app.db.TestDatabases;
import com.butterflyatlas.app.photos.PhotoQueries;
import com.butterflyatlas.app.photos.SpeciesFiles;
import com.butterflyatlas.app.testing.UpdateBundles;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.AccessDeniedException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** The startup's purge of Recently deleted, skipped while an update held the gate, runs when it finishes (5b §7). */
class UpdatesSkippedPurgeTest {

    @TempDir
    Path dir;

    @Test
    void theStartupsSkippedPurgeRunsWhenTheUpdateFinishes() throws Exception {
        UpdateFixture f = new UpdateFixture(dir);
        f.populated();
        f.sql("UPDATE image SET deleted_at = '2026-01-01T00:00:00Z' WHERE deleted_at IS NOT NULL"); // long in Recently deleted
        AtomicInteger calls = new AtomicInteger();
        Updates updates = new Updates(() -> f.library, UpdateBundles.bundles(UpdateFixture.v1(), UpdateFixture.v2()), f.clock,
                (from, to) -> {
                    if (calls.incrementAndGet() == 1) {
                        throw new AccessDeniedException(from.toString());
                    }
                    SpeciesFiles.moveFile(from, to);
                }, path -> Long.MAX_VALUE);
        updates.whenFinished(f.photos::purgeSkipped);
        updates.startup();
        updates.apply();
        assertThat(updates.awaitIdle(Duration.ofSeconds(30))).isTrue();

        assertThat(f.photos.purgeOlderThan(PhotoQueries.KEEP_DELETED)).as("skipped while paused").isZero();
        assertThat(f.photos.deletedCount()).isEqualTo(1);

        updates.retry();
        assertThat(updates.awaitIdle(Duration.ofSeconds(30))).isTrue();

        assertThat(updates.update().state()).isEqualTo("done");
        assertThat(f.photos.deletedCount()).as("purged once the update finished").isZero();
        assertThat(f.photos.purgeSkipped()).as("only once").isZero();
    }

    @Test
    void aSkippedPurgeThatFailsIsTriedAgainNextTime() {
        UpdateFixture f = new UpdateFixture(dir);
        f.populated();
        f.sql("UPDATE image SET deleted_at = '2026-01-01T00:00:00Z' WHERE deleted_at IS NOT NULL");
        f.library.gate().close();
        assertThat(f.photos.purgeOlderThan(PhotoQueries.KEEP_DELETED)).isZero();
        f.library.gate().open();
        AtomicBoolean failing = new AtomicBoolean(true);
        TestDatabases.failStatements(f.db, failing::get);

        assertThat(f.photos.purgeSkipped()).as("failed, logged, not thrown").isZero();
        failing.set(false);
        assertThat(f.photos.deletedCount()).isEqualTo(1);

        assertThat(f.photos.purgeSkipped()).as("tried again").isEqualTo(1);
        assertThat(f.photos.deletedCount()).isZero();
        assertThat(f.photos.purgeSkipped()).isZero();
    }
}
