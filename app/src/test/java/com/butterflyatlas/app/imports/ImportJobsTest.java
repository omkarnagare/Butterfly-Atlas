package com.butterflyatlas.app.imports;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

class ImportJobsTest {

    private static final Duration WAIT = Duration.ofSeconds(10);

    private final List<String> calls = new CopyOnWriteArrayList<>();
    private ImportJobs jobs;

    @AfterEach
    void close() {
        if (jobs != null) {
            jobs.close();
        }
    }

    /** Work that records each phase; a phase given a latch waits for it, or (with none) runs until told to stop. */
    private class Recorded implements ImportJobs.Work {

        CountDownLatch walkGate = new CountDownLatch(0);
        CountDownLatch fileGate = new CountDownLatch(0);
        CountDownLatch readGate = new CountDownLatch(0);
        CountDownLatch entered = new CountDownLatch(1);
        boolean readsUntilStopped;
        boolean filesUntilStopped;
        boolean folderThere = true;

        @Override
        public boolean walk(long runId, ImportJobs.Reporter reporter, BooleanSupplier stop) {
            calls.add("walk " + runId);
            reporter.report(3, 0);
            entered.countDown();
            await(walkGate);
            return folderThere;
        }

        @Override
        public void read(long runId, ImportJobs.Reporter reporter, BooleanSupplier stop) {
            calls.add("read " + runId);
            entered.countDown();
            await(readGate);
            if (readsUntilStopped) {
                readsUntilStopped = false; // only the first read waits; the read after filing finishes at once
                spinUntil(stop);
            }
        }

        @Override
        public void file(long runId, ImportJobs.Reporter reporter, BooleanSupplier stop) {
            calls.add("file " + runId);
            reporter.report(50, 120);
            entered.countDown();
            await(fileGate);
            if (filesUntilStopped) {
                spinUntil(stop);
            }
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A batch loop: checks between batches until it is asked to stop. */
    private static void spinUntil(BooleanSupplier stop) {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (!stop.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new IllegalStateException("never asked to stop");
            }
            Thread.onSpinWait();
        }
    }

    @Test
    void oneImportWorksAtATimeAndTheNextWaitsItsTurn() throws Exception {
        Recorded work = new Recorded();
        work.walkGate = new CountDownLatch(1);
        jobs = new ImportJobs(work);

        jobs.walkAndRead(1);
        jobs.walkAndRead(2);
        await(work.entered);

        assertThat(jobs.current()).contains(new ImportJobs.Progress(1, "walking", 3, 0));
        assertThat(jobs.of(2)).contains(new ImportJobs.Progress(2, "walking", 0, 0));
        assertThat(jobs.of(3)).isEmpty();
        work.walkGate.countDown();
        assertThat(jobs.awaitIdle(WAIT)).isTrue();
        assertThat(calls).containsExactly("walk 1", "read 1", "walk 2", "read 2");
        assertThat(jobs.current()).isEmpty();
        assertThat(jobs.of(1)).isEmpty();
    }

    @Test
    void filingInterruptsReadingWhichCarriesOnAfterwards() throws Exception {
        Recorded work = new Recorded();
        work.readsUntilStopped = true;
        work.fileGate = new CountDownLatch(1);
        jobs = new ImportJobs(work);

        jobs.read(1);
        await(work.entered);
        jobs.file(1);

        assertThat(jobs.filing(1)).as("asked, then running (held at its gate)").isTrue();
        work.fileGate.countDown();
        assertThat(jobs.awaitIdle(WAIT)).isTrue();
        assertThat(calls).containsExactly("read 1", "file 1", "read 1");
        assertThat(jobs.filing(1)).isFalse();
    }

    @Test
    void aFilingAskedWhileReadingStillGivesWayShowsAsFilingAtOnce() throws Exception {
        Recorded work = new Recorded();
        work.readGate = new CountDownLatch(1); // a read busy with one photo: it looks at the stop signal afterwards
        jobs = new ImportJobs(work);

        jobs.read(1);
        await(work.entered);
        jobs.file(1);

        assertThat(jobs.of(1)).as("the filing wanted, not the reading giving way").contains(
                new ImportJobs.Progress(1, "filing", 0, 0));
        work.readGate.countDown();
        assertThat(jobs.awaitIdle(WAIT)).isTrue();
        assertThat(calls).containsExactly("read 1", "file 1", "read 1");
        assertThat(jobs.of(1)).isEmpty();
    }

    @Test
    void pausingStopsFilingAtTheNextBatch() throws Exception {
        Recorded work = new Recorded();
        work.filesUntilStopped = true;
        jobs = new ImportJobs(work);

        jobs.file(1);
        await(work.entered);
        assertThat(jobs.current()).contains(new ImportJobs.Progress(1, "filing", 50, 120));
        jobs.pause(1);

        assertThat(jobs.filing(1)).as("paused: changes are allowed again").isFalse();
        assertThat(jobs.awaitIdle(WAIT)).isTrue();
        assertThat(calls).containsExactly("file 1");
    }

    @Test
    void pausingAFilingThatHasNotBegunDropsIt() throws Exception {
        Recorded work = new Recorded();
        work.walkGate = new CountDownLatch(1);
        jobs = new ImportJobs(work);

        jobs.walkAndRead(1);
        await(work.entered);
        jobs.file(2);
        jobs.pause(2);
        work.walkGate.countDown();

        assertThat(jobs.awaitIdle(WAIT)).isTrue();
        assertThat(calls).containsExactly("walk 1", "read 1");
    }

    @Test
    void cancellingStopsTheRunningWorkAndWaitsForIt() throws Exception {
        Recorded work = new Recorded();
        work.readsUntilStopped = true;
        jobs = new ImportJobs(work);

        jobs.read(1);
        await(work.entered);

        assertThat(jobs.cancel(1, WAIT)).isTrue();
        assertThat(jobs.current()).isEmpty();
        assertThat(jobs.cancel(2, WAIT)).as("nothing to cancel").isTrue();
        assertThat(jobs.awaitIdle(WAIT)).isTrue();
        assertThat(calls).containsExactly("read 1");
    }

    @Test
    void heldFilingWaitsForTheStartupWhileWalkingAndReadingRun() throws Exception {
        Recorded work = new Recorded();
        jobs = new ImportJobs(work);
        jobs.hold();

        jobs.file(2);
        jobs.walkAndRead(1);

        assertThat(jobs.awaitIdle(Duration.ofMillis(300))).as("filing waits while held").isFalse();
        assertThat(calls).as("walking and reading never touch the library's photos").containsExactly("walk 1", "read 1");
        assertThat(jobs.current()).isEmpty();
        assertThat(jobs.of(2)).contains(new ImportJobs.Progress(2, "filing", 0, 0));
        assertThat(jobs.filing(2)).isTrue();
        jobs.release();
        assertThat(jobs.awaitIdle(WAIT)).isTrue();
        assertThat(calls).containsExactly("walk 1", "read 1", "file 2");
    }

    @Test
    void aReadGoesOnWhileItsFilingIsHeldAndGivesWayOnRelease() throws Exception {
        Recorded work = new Recorded();
        work.readsUntilStopped = true;
        jobs = new ImportJobs(work);
        jobs.hold();

        jobs.read(1);
        assertThat(work.entered.await(10, TimeUnit.SECONDS)).isTrue();
        jobs.file(1);

        assertThat(jobs.awaitIdle(Duration.ofMillis(300))).isFalse();
        assertThat(calls).as("the read is not stopped for a filing that cannot start").containsExactly("read 1");
        assertThat(jobs.current()).contains(new ImportJobs.Progress(1, "reading", 0, 0));
        jobs.release();
        assertThat(jobs.awaitIdle(WAIT)).isTrue();
        assertThat(calls).containsExactly("read 1", "file 1", "read 1");
    }

    @Test
    void beginningToCloseStopsTheRunningPhaseAndStartsNothingMore() throws Exception {
        Recorded work = new Recorded();
        work.filesUntilStopped = true;
        jobs = new ImportJobs(work);
        jobs.file(1);
        assertThat(work.entered.await(10, TimeUnit.SECONDS)).isTrue();
        jobs.read(2);

        jobs.beginClose();

        assertThat(jobs.awaitIdle(WAIT)).as("the filing stops at its next look, without waiting for close").isTrue();
        jobs.read(3);
        assertThat(jobs.awaitIdle(Duration.ofMillis(200))).isTrue();
        assertThat(calls).containsExactly("file 1");
    }

    @Test
    void aMissingFolderIsNotRead() throws Exception {
        Recorded work = new Recorded();
        work.folderThere = false;
        jobs = new ImportJobs(work);

        jobs.walkAndRead(1);

        assertThat(jobs.awaitIdle(WAIT)).isTrue();
        assertThat(calls).containsExactly("walk 1");
    }

    @Test
    void aFailingPhaseIsLoggedAndTheNextImportStillRuns() throws Exception {
        Recorded work = new Recorded() {
            @Override
            public boolean walk(long runId, ImportJobs.Reporter reporter, BooleanSupplier stop) {
                if (runId == 1) {
                    calls.add("walk 1 fails");
                    throw new IllegalStateException("disk error");
                }
                return super.walk(runId, reporter, stop);
            }
        };
        jobs = new ImportJobs(work);

        jobs.walkAndRead(1);
        jobs.walkAndRead(2);

        assertThat(jobs.awaitIdle(WAIT)).isTrue();
        assertThat(calls).containsExactly("walk 1 fails", "walk 2", "read 2");
        assertThat(jobs.failures()).isEqualTo(1);
    }

    @Test
    void aCancelThatTimesOutStillStopsThePhaseWhenItNextLooks() throws Exception {
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch inside = new CountDownLatch(1);
        List<Boolean> sawStop = new CopyOnWriteArrayList<>();
        jobs = new ImportJobs(new Recorded() {
            @Override
            public void read(long runId, ImportJobs.Reporter reporter, BooleanSupplier stop) {
                inside.countDown();
                ImportJobsTest.await(held); // a step that does not look at stop for a while
                sawStop.add(stop.getAsBoolean());
            }
        });
        jobs.read(1);
        await(inside);

        assertThat(jobs.cancel(1, Duration.ofMillis(20))).as("the phase outlasted the wait").isFalse();
        held.countDown();

        assertThat(jobs.awaitIdle(WAIT)).isTrue();
        assertThat(sawStop).as("the stop request was not withdrawn").containsExactly(true);
        jobs.read(1);
        assertThat(jobs.awaitIdle(WAIT)).isTrue();
        assertThat(sawStop).as("cleared once that phase ended").containsExactly(true, false);
    }

    @Test
    void pausingTellsWhetherThereWasFilingToPause() throws Exception {
        Recorded work = new Recorded();
        work.filesUntilStopped = true;
        jobs = new ImportJobs(work);

        assertThat(jobs.pause(1)).as("nothing filing").isFalse();
        jobs.file(1);
        await(work.entered);
        assertThat(jobs.pause(1)).isTrue();
        assertThat(jobs.awaitIdle(WAIT)).isTrue();
    }

    @Test
    void closingWaitsForTheRunningPhaseToStop() throws Exception {
        Recorded work = new Recorded() {
            @Override
            public void read(long runId, ImportJobs.Reporter reporter, BooleanSupplier stop) {
                super.read(runId, reporter, stop);
                calls.add("read " + runId + " stopped");
            }
        };
        work.readsUntilStopped = true;
        jobs = new ImportJobs(work);
        jobs.read(1);
        await(work.entered);

        jobs.close();

        assertThat(calls).as("the phase had ended when close returned").containsExactly("read 1", "read 1 stopped");
    }

    @Test
    void workAskedAfterACancelTimedOutIsNotDropped() throws Exception {
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch inside = new CountDownLatch(1);
        jobs = new ImportJobs(new Recorded() {
            @Override
            public void read(long runId, ImportJobs.Reporter reporter, BooleanSupplier stop) {
                calls.add("read " + runId);
                inside.countDown();
                ImportJobsTest.await(held);
            }
        });
        jobs.read(1);
        await(inside);
        assertThat(jobs.cancel(1, Duration.ofMillis(20))).isFalse(); // a 503 for finish or relocate

        jobs.walkAndRead(1); // the user continues the import
        held.countDown();

        assertThat(jobs.awaitIdle(WAIT)).isTrue();
        assertThat(calls).as("the new request ran").containsExactly("read 1", "walk 1", "read 1");
    }
}
