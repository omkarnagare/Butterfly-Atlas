package com.butterflyatlas.app.library;

/**
 * Background work held while the library is copied (change-library-folder spec §5): thumbnails (and the HEIC decodes they
 * make) and photo details. Each piece of work runs between {@link #enter} and {@link #exit}, or waits at
 * {@link #awaitFree} between batches; {@link #hold} returns once the work under way is over and none starts until
 * {@link #release}. One per library. Waits end when the thread is interrupted (the app closing).
 */
public final class WorkHold {

    private boolean held;
    private int active;

    /** Waits while the work is held, then counts this piece as under way. */
    public synchronized void enter() throws InterruptedException {
        while (held) {
            wait();
        }
        active++;
    }

    /** The piece of work begun with {@link #enter} is over. */
    public synchronized void exit() {
        active--;
        notifyAll();
    }

    /** Waits while the work is held (between two batches). */
    public synchronized void awaitFree() throws InterruptedException {
        while (held) {
            wait();
        }
    }

    /** From now on no work starts; returns once the work under way is over. */
    public synchronized void hold() throws InterruptedException {
        held = true;
        while (active > 0) {
            wait();
        }
    }

    /** The work may go on. */
    public synchronized void release() {
        held = false;
        notifyAll();
    }

    /** Whether the work is held. */
    public synchronized boolean held() {
        return held;
    }
}
