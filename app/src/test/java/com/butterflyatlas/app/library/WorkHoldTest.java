package com.butterflyatlas.app.library;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/** Background work held while a library is copied (change-library-folder spec §5). */
class WorkHoldTest {

    private final WorkHold hold = new WorkHold();

    @Test
    void holdingWaitsForTheWorkUnderWayAndNoneStartsUntilItIsReleased() throws Exception {
        hold.enter();
        CountDownLatch held = new CountDownLatch(1);
        Thread holding = new Thread(() -> {
            try {
                hold.hold();
                held.countDown();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        holding.start();
        assertThat(held.await(200, TimeUnit.MILLISECONDS)).as("the work under way is waited for").isFalse();
        assertThat(hold.held()).isTrue();

        hold.exit();
        assertThat(held.await(5, TimeUnit.SECONDS)).isTrue();

        AtomicBoolean started = new AtomicBoolean();
        CountDownLatch next = new CountDownLatch(1);
        Thread work = new Thread(() -> {
            try {
                hold.enter();
                started.set(true);
                hold.exit();
                hold.awaitFree();
                next.countDown();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        work.start();
        Thread.sleep(150);
        assertThat(started).as("held").isFalse();

        hold.release();
        assertThat(next.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(started).isTrue();
        assertThat(hold.held()).isFalse();
    }

    @Test
    void aWaitEndsWhenTheAppClosesItsThread() throws Exception {
        hold.hold();
        AtomicBoolean interrupted = new AtomicBoolean();
        Thread work = new Thread(() -> {
            try {
                hold.enter();
            } catch (InterruptedException e) {
                interrupted.set(true);
            }
        });
        work.start();
        Thread.sleep(50);

        work.interrupt();
        work.join(5000);

        assertThat(interrupted).isTrue();
    }
}
