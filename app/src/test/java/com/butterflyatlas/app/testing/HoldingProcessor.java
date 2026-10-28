package com.butterflyatlas.app.testing;

import com.butterflyatlas.app.imaging.ImageIoPhotoProcessor;
import com.butterflyatlas.app.photos.PhotoFacts;
import com.butterflyatlas.app.photos.PhotoProcessor;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BiPredicate;

/**
 * The real processor, except that the calls {@link #hold} picks ("read" or "thumbnail", and the file) wait until
 * {@link #release}: a test holds a reading or a filing mid-step.
 */
public final class HoldingProcessor implements PhotoProcessor {

    private final PhotoProcessor real = new ImageIoPhotoProcessor();
    private final CountDownLatch entered = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);
    private volatile BiPredicate<String, Path> hold = (call, file) -> false;

    /** From now on, the calls {@code which} picks wait for {@link #release}. */
    public void hold(BiPredicate<String, Path> which) {
        hold = which;
    }

    /** Waits until a held call has started. */
    public boolean awaitHeld() throws InterruptedException {
        return entered.await(20, TimeUnit.SECONDS);
    }

    public void release() {
        release.countDown();
    }

    private void maybeHold(String call, Path file) throws IOException {
        if (hold.test(call, file)) {
            entered.countDown();
            try {
                if (!release.await(30, TimeUnit.SECONDS)) {
                    throw new IOException("held too long");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("interrupted while held");
            }
        }
    }

    @Override
    public PhotoFacts read(Path file) throws IOException {
        maybeHold("read", file);
        return real.read(file);
    }

    @Override
    public void thumbnail(Path source, int orientation, int longSide, Path target) throws IOException {
        maybeHold("thumbnail", source);
        real.thumbnail(source, orientation, longSide, target);
    }

    @Override
    public String dominantColor(Path image) throws IOException {
        return real.dominantColor(image);
    }
}
