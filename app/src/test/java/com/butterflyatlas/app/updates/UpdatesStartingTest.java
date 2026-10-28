package com.butterflyatlas.app.updates;

import com.butterflyatlas.app.taxonomy.bundle.Bundle;
import com.butterflyatlas.app.testing.UpdateBundles;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Starting an update (review J21): the summary and the result count the same photos; a plan that pauses gives its place
 * up at once, so a Retry right after starts a run; and a start waiting for the gate never holds up the status.
 */
class UpdatesStartingTest {

    @TempDir
    Path dir;

    private UpdateFixture f;
    private Updates updates;

    @BeforeEach
    void setUp() {
        f = new UpdateFixture(dir);
        f.populated();
        updates = new Updates(() -> f.library, UpdateBundles.bundles(UpdateFixture.v1(), UpdateFixture.v2()), f.clock);
        updates.startup();
    }

    @Test
    void theResultCountsThePhotosTheSummaryCounted() throws Exception {
        int summary = updates.update().summary().photos();

        updates.apply();
        assertThat(updates.awaitIdle(Duration.ofSeconds(30))).isTrue();

        assertThat(updates.update().result().photos()).isEqualTo(summary).isEqualTo(9);
    }

    @Test
    void thePendingSummaryFollowsThePhotos() {
        f.photos.onChange(updates::libraryChanged);
        assertThat(updates.update().summary().photos()).isEqualTo(9);

        f.add("A6");

        assertThat(updates.update().summary().photos()).isEqualTo(10);
    }

    @Test
    void aRetryRightAfterATakenTargetPausedThePlanStartsItsOwnRun() throws Exception {
        Path inTheWay = f.file("images/Nymphalidae/Danainae/Danaini/Danaus/Danaus_melanippus_[A6]/Danaus_melanippus_01.jpg");
        Files.createDirectories(inTheWay.getParent());
        Files.writeString(inTheWay, "someone else's");
        updates.beforeEnd = () -> sleep(500);

        updates.apply();
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (!"paused".equals(updates.update().state()) && System.nanoTime() < deadline) {
            Thread.sleep(2);
        }
        Files.delete(inTheWay);
        updates.beforeEnd = () -> { };
        assertThat(updates.retry().state()).as("seen paused: its place is given up, the Retry runs").isEqualTo("running");
        assertThat(updates.awaitIdle(Duration.ofSeconds(30))).isTrue();

        assertThat(updates.update().state()).isEqualTo("done");
    }

    @Test
    void theStatusAnswersWhileAnApplyWaitsForAChangeUnderWayToFinish() throws Exception {
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread change = new Thread(() -> f.library.gate().whileOpen(() -> {
            inside.countDown();
            await(release);
        }));
        change.start();
        assertThat(inside.await(10, TimeUnit.SECONDS)).isTrue();
        Thread applying = new Thread(updates::apply);
        applying.start();
        Thread.sleep(300); // the apply waits for the change to finish before it closes the gate

        long started = System.nanoTime();
        UpdateViews.Status status = updates.status().orElseThrow();
        UpdateViews.Update update = updates.update();
        long tookMs = (System.nanoTime() - started) / 1_000_000;

        assertThat(tookMs).isLessThan(1_000);
        assertThat(status.state()).isEqualTo("pending");
        assertThat(update.state()).isEqualTo("pending");
        release.countDown();
        applying.join(30_000);
        change.join(30_000);
        assertThat(updates.awaitIdle(Duration.ofSeconds(30))).isTrue();
        assertThat(updates.update().state()).isEqualTo("done");
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
