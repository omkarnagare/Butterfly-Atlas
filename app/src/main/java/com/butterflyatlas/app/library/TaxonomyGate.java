package com.butterflyatlas.app.library;

import com.butterflyatlas.app.AtlasException;
import com.butterflyatlas.app.ErrorCodes;

import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;

/**
 * The "wait" rule of taxonomy updates (5b contract §3): while an update is running, waiting or paused, the changes that
 * move or file photos or change custom species are refused (409 {@code taxonomy_updating}). The packages that make those
 * changes run them through {@link #whileOpen}; an update {@link #close closes} the gate, which returns once the changes
 * already under way are over, so none of them runs alongside its plan, moves or switch-over. One per library.
 */
public final class TaxonomyGate {

    public static final String CODE = ErrorCodes.TAXONOMY_UPDATING;
    public static final String MESSAGE = "Wait for the taxonomy update to finish.";
    /** The words while the update is paused (5b contract §7). */
    public static final String PAUSED_MESSAGE = "The taxonomy update is paused, so this can't be done yet. Open Taxonomy update"
            + " to see why, then choose Retry.";

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private volatile boolean closed;
    private volatile boolean paused;

    /** 409 {@code taxonomy_updating} while an update is running, waiting or paused. */
    public void check() {
        if (closed) {
            throw refusal();
        }
    }

    /** Runs {@code work} unless an update holds the gate (then 409); an update closing meanwhile waits for it. */
    public <T> T whileOpen(Supplier<T> work) {
        lock.readLock().lock();
        try {
            check();
            return work.get();
        } finally {
            lock.readLock().unlock();
        }
    }

    /** As {@link #whileOpen(Supplier)}, for work with no answer. */
    public void whileOpen(Runnable work) {
        whileOpen(() -> {
            work.run();
            return null;
        });
    }

    /** From now on changes are refused; returns once those already under way are over. */
    public void close() {
        lock.writeLock().lock();
        try {
            closed = true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** The update is done: changes are allowed again. */
    public void open() {
        closed = false;
        paused = false;
    }

    /** Whether the update holding the gate is paused (the refusal says so) or running and waiting. */
    public void paused(boolean paused) {
        this.paused = paused;
    }

    /** 409 {@code taxonomy_updating}, in the words of the update's state. */
    public AtlasException refusal() {
        return new AtlasException(AtlasException.Kind.CONFLICT, CODE, paused ? PAUSED_MESSAGE : MESSAGE);
    }

    public boolean isClosed() {
        return closed;
    }
}
