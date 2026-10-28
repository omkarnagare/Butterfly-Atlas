package com.butterflyatlas.app.exports;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/** {@link ExportJobs} with a stub copy blocked on latches: a pause taken back, or not, in either order. */
class ExportJobsUnitTest {

    private final CountDownLatch started = new CountDownLatch(1);
    private final CountDownLatch look = new CountDownLatch(1);
    private final CountDownLatch seen = new CountDownLatch(1);
    private final CountDownLatch finish = new CountDownLatch(1);
    private final List<ExportJobs.Stop> stops = new CopyOnWriteArrayList<>();
    private final AtomicLong failed = new AtomicLong();
    private final ExportJobs jobs = new ExportJobs((runId, stop) -> {
        started.countDown();
        await(look);
        stops.add(stop.get()); // the copy looks at its stop between two files
        seen.countDown();
        await(finish);
    }, failed::set);

    @AfterEach
    void close() {
        finish.countDown();
        look.countDown();
        jobs.close();
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(20, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void aPauseNotYetSeenIsTakenBack() throws Exception {
        jobs.run(1);
        assertThat(started.await(20, TimeUnit.SECONDS)).isTrue();

        assertThat(jobs.stop(1, ExportJobs.Stop.PAUSE)).isTrue();
        assertThat(jobs.keepGoing(1)).isTrue();
        look.countDown();
        assertThat(seen.await(20, TimeUnit.SECONDS)).isTrue();

        assertThat(stops).containsExactly(ExportJobs.Stop.NONE);
    }

    @Test
    void aPauseAlreadySeenIsNotTakenBackAndTheWaitCanRunOut() throws Exception {
        jobs.run(1);
        assertThat(started.await(20, TimeUnit.SECONDS)).isTrue();
        jobs.stop(1, ExportJobs.Stop.PAUSE);
        look.countDown();
        assertThat(seen.await(20, TimeUnit.SECONDS)).isTrue();

        assertThat(jobs.keepGoing(1)).as("the copy is saving and recording its pause").isFalse();
        assertThat(jobs.awaitStopped(1, Duration.ofMillis(100))).as("still finishing").isFalse();
        finish.countDown();
        assertThat(jobs.awaitStopped(1, Duration.ofSeconds(20))).isTrue();
        assertThat(stops).containsExactly(ExportJobs.Stop.PAUSE);
    }

    @Test
    void aCopyThatThrowsIsReportedSoItIsNeverLeftRunning() throws Exception {
        ExportJobs failing = new ExportJobs((runId, stop) -> {
            throw new IllegalStateException("a bug");
        }, failed::set);
        try {
            failing.run(7);
            assertThat(failing.awaitIdle(Duration.ofSeconds(20))).isTrue();
            assertThat(failed.get()).isEqualTo(7);
            assertThat(failing.awaitStopped(7, Duration.ZERO)).as("not copying").isTrue();
            assertThat(failing.keepGoing(7)).isFalse();
        } finally {
            failing.close();
        }
    }

    @Test
    void whenRecordingAFailedCopyFailsTooTheExportCanStillBePausedAndRunAgain() throws Exception {
        java.util.concurrent.atomic.AtomicInteger copies = new java.util.concurrent.atomic.AtomicInteger();
        ExportJobs failing = new ExportJobs((runId, stop) -> {
            copies.incrementAndGet();
            throw new IllegalStateException("a bug");
        }, runId -> {
            throw new IllegalStateException("the database is gone");
        });
        try {
            failing.run(7);
            assertThat(failing.awaitIdle(Duration.ofSeconds(20))).isTrue();

            assertThat(failing.stop(7, ExportJobs.Stop.PAUSE)).as("no copy: the facade pauses it at once").isFalse();
            failing.run(7);
            assertThat(failing.awaitIdle(Duration.ofSeconds(20))).isTrue();
            assertThat(copies.get()).as("the worker carries on").isEqualTo(2);
        } finally {
            failing.close();
        }
    }
}
