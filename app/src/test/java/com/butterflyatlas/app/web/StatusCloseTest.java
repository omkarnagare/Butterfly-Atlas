package com.butterflyatlas.app.web;

import com.butterflyatlas.app.db.TestDatabases;
import com.butterflyatlas.app.imaging.ImageIoPhotoProcessor;
import com.butterflyatlas.app.library.LibraryHolder;
import com.butterflyatlas.app.photos.PhotoStore;
import com.butterflyatlas.app.testing.TestBundles;
import com.butterflyatlas.app.testing.TestServices;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Closing the status and the place note waits for a read of theirs that is under way, and starts none after (CI: a
 * read after a test's end recreated the library's files while its folder was deleted).
 */
class StatusCloseTest {

    @TempDir
    Path home;

    private final LibraryHolder holder = new LibraryHolder();
    private AtlasServices services;

    @AfterEach
    void close() {
        services.placeNotes().close();
        services.updates().close();
        services.imports().close();
        services.exports().close();
        services.thumbnails().close();
    }

    /** From now on a read on a thread of this name waits in {@code entered}/{@code release}; returns the reads seen there. */
    private AtomicInteger holdReadsOn(String threadName, CountDownLatch entered, CountDownLatch release) {
        AtomicInteger reads = new AtomicInteger();
        TestDatabases.failStatements(holder.get(), () -> {
            if (Thread.currentThread().getName().equals(threadName)) {
                reads.incrementAndGet();
                entered.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return false;
        });
        return reads;
    }

    private static Thread closing(Runnable close, AtomicBoolean closed) {
        Thread thread = new Thread(() -> {
            close.run();
            closed.set(true);
        });
        thread.start();
        return thread;
    }

    @Test
    void closingTheStatusWaitsForItsReadAndNoneStartsAfter() throws Exception {
        services = TestServices.configured(home, holder);
        // A photo store that has read nothing yet: the status reads its counts on a thread of its own.
        PhotoStore unread = new PhotoStore(holder::library, new ImageIoPhotoProcessor(), TestBundles.CLOCK, id -> { });
        StatusRoutes status = new StatusRoutes(services.rescan(), services.thumbnails(), unread, services.processing(),
                services.imports(), services.exports(), services.updates(), services.drive(), services.placeNotes());
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger reads = holdReadsOn("atlas-status", entered, release);
        status.status();
        assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();

        AtomicBoolean closed = new AtomicBoolean();
        Thread closer = closing(status::close, closed);
        Thread.sleep(200);
        assertThat(closed.get()).as("waits for the read under way").isFalse();
        release.countDown();
        closer.join(10_000);
        assertThat(closed.get()).isTrue();

        int seen = reads.get();
        status.status();
        Thread.sleep(200);
        assertThat(reads.get()).as("no read after close").isEqualTo(seen);
    }

    @Test
    void closingThePlaceNoteWaitsForItsCountAndNoneStartsAfter() throws Exception {
        services = TestServices.configured(home, holder);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger reads = holdReadsOn("atlas-place-note", entered, release);
        services.placeNotes().changed();
        assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();

        AtomicBoolean closed = new AtomicBoolean();
        Thread closer = closing(services.placeNotes()::close, closed);
        Thread.sleep(200);
        assertThat(closed.get()).as("waits for the count under way").isFalse();
        release.countDown();
        closer.join(10_000);
        assertThat(closed.get()).isTrue();

        int seen = reads.get();
        services.placeNotes().changed();
        services.placeNotes().status();
        Thread.sleep(200);
        assertThat(reads.get()).as("no count after close").isEqualTo(seen);
    }
}
