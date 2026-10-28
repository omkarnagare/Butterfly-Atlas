package com.butterflyatlas.app.exports;

import com.butterflyatlas.app.AtlasException;
import com.butterflyatlas.app.db.TestDatabases;
import com.butterflyatlas.app.testing.TestImages;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A resume waiting for a stopping copy does not hold the taxonomy update's gate meanwhile (review J16): an update
 * closing the gate is not held up by it, and the resume is then refused.
 */
class ExportResumeGateTest {

    @TempDir
    Path home;

    private final CountDownLatch release = new CountDownLatch(1);
    private ExportFixture f;

    @AfterEach
    void tearDown() {
        release.countDown();
        f.exports.close();
    }

    @Test
    void aResumeWaitingForTheCopyToStopDoesNotHoldTheGate() throws Exception {
        f = new ExportFixture(home);
        for (int n = 1; n <= 3; n++) {
            f.photo("A5", "Danaus_chrysippus_0" + n + ".jpg", TestImages.jpeg(8, 6, n), null);
        }
        f.exports.stopWait = Duration.ofSeconds(20);
        CountDownLatch saving = new CountDownLatch(1);
        f.exports.beforePhoto(item -> {
            if (item.seq() == 2) {
                f.exports.pause(f.exports.current().orElseThrow().id());
                // From the next photo on the copy has seen the pause: its save waits until released.
                TestDatabases.beforeCommit(f.db, () -> {
                    if (Thread.currentThread().getName().equals("atlas-export")) {
                        saving.countDown();
                        await(release);
                    }
                });
            }
        });
        long id = f.exports.start(new Exports.Request("", "photos", f.dest.toString(), "flat", false, null)).id();
        assertThat(saving.await(20, TimeUnit.SECONDS)).isTrue();
        AtomicReference<String> answer = new AtomicReference<>();
        Thread resumer = new Thread(() -> {
            try {
                answer.set(f.exports.resume(id).state());
            } catch (AtlasException e) {
                answer.set(e.code());
            }
        });
        resumer.start();
        Thread.sleep(300); // the resume waits for the copy to stop

        long started = System.nanoTime();
        f.library.gate().close();
        long tookMs = (System.nanoTime() - started) / 1_000_000;

        assertThat(tookMs).as("the update's gate closes at once").isLessThan(1_000);
        release.countDown();
        resumer.join(30_000);
        assertThat(answer.get()).isEqualTo("taxonomy_updating");
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
