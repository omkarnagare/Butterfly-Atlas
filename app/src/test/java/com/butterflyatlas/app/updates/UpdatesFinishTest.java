package com.butterflyatlas.app.updates;

import com.butterflyatlas.app.photos.SpeciesFiles;
import com.butterflyatlas.app.taxonomy.bundle.Bundle;
import com.butterflyatlas.app.testing.UpdateBundles;
import com.butterflyatlas.app.updates.UpdateViews.Update;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An update (or a match) is seen done only with its result and the gate open, and a run that has just finished never
 * keeps the next Apply, Merge or Retry from starting (review J13).
 */
class UpdatesFinishTest {

    @TempDir
    Path dir;

    private UpdateFixture f;

    @BeforeEach
    void setUp() {
        f = new UpdateFixture(dir);
        f.populated();
    }

    /** A rename, a merge, and Neptis sankara added (the custom U1 then matches it). */
    private static Bundle v2() {
        return UpdateBundles.derive(UpdateFixture.v1(), "2026-02-01").rename("A6", "Danaus melanippus")
                .add("A15", "A11", "species", "Papilio agestor", "Tawny Mime").merge("A15", "A14")
                .add("A20", "A3", "genus", "Neptis", null).add("A21", "A20", "species", "Neptis sankara", null).build();
    }

    private Updates updates(UpdateMoves.Mover mover) {
        Updates updates = new Updates(() -> f.library, UpdateBundles.bundles(UpdateFixture.v1(), v2()), f.clock, mover,
                path -> Long.MAX_VALUE);
        updates.startup();
        return updates;
    }

    @Test
    void doneIsNeverSeenWithoutItsResultOrWithTheGateStillClosed() throws Exception {
        Updates updates = updates((from, to) -> {
            sleep(5);
            SpeciesFiles.moveFile(from, to);
        });

        // Watched from the moment each run has started (its 202) until it is idle: no other start comes in between.
        updates.apply();
        assertThat(watched(updates)).isEmpty();
        updates.merge("U1", "A21");
        assertThat(watched(updates)).isEmpty();

        assertThat(updates.update().state()).isEqualTo("done");
        assertThat(updates.update().kind()).isEqualTo("match");
    }

    /** What a watcher saw wrong until the run is idle: done without its result, or with the gate still closed. */
    private List<String> watched(Updates updates) throws InterruptedException {
        List<String> problems = new CopyOnWriteArrayList<>();
        AtomicBoolean watching = new AtomicBoolean(true);
        Thread watcher = new Thread(() -> {
            while (watching.get()) {
                Update update = updates.update();
                boolean gateOpen = !f.library.gate().isClosed();
                if ("done".equals(update.state()) && (update.result() == null || !gateOpen)) {
                    problems.add("done without " + (update.result() == null ? "its result" : "the gate open"));
                }
                if (updates.status().map(s -> "done".equals(s.state())).orElse(false) && updates.update().result() == null) {
                    problems.add("status done without the result");
                }
            }
        });
        watcher.start();
        assertThat(updates.awaitIdle(Duration.ofSeconds(30))).isTrue();
        watching.set(false);
        watcher.join();
        return problems;
    }

    @Test
    void aMergeAskedForWhileTheFinishedRunIsStillEndingStartsItsOwnRun() throws Exception {
        Updates updates = updates(SpeciesFiles::moveFile);
        updates.afterDone = () -> sleep(500);

        updates.apply();
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (!"done".equals(updates.update().state()) && System.nanoTime() < deadline) {
            Thread.sleep(2);
        }
        updates.afterDone = () -> { };
        updates.merge("U1", "A21");
        assertThat(updates.awaitIdle(Duration.ofSeconds(30))).isTrue();

        Update merged = updates.update();
        assertThat(merged.kind()).isEqualTo("match");
        assertThat(merged.state()).isEqualTo("done");
        assertThat(f.rows("SELECT state FROM taxonomy_update ORDER BY id")).containsExactly("done", "done");
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
