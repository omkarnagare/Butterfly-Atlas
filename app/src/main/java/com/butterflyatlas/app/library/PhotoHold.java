package com.butterflyatlas.app.library;

import com.butterflyatlas.app.AtlasException;

import com.butterflyatlas.app.ErrorCodes;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;

/**
 * The photos a backup holds (multi-window design §5 G1, contract §4): while a backup of Everything is running, or paused
 * before its photos are all copied, the changes that add, move, delete, restore or purge photos are refused (409
 * {@code backup_copying}, in the words the backup gives). The packages that make those changes run them through
 * {@link #whileFree}; a backup that starts or resumes {@link #claim claims} the photos, which waits for the changes already
 * under way. One per library; what holds it is told by the backups ({@link #heldBy}). Taken after the taxonomy gate.
 */
public final class PhotoHold {

    public static final String CODE = ErrorCodes.BACKUP_COPYING;

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    /** The refusal's words while a backup holds the photos, else empty. */
    private volatile Supplier<Optional<String>> refusal = Optional::empty;

    /** What holds the photos: the words of the refusal while something does, else empty. */
    public void heldBy(Supplier<Optional<String>> refusal) {
        this.refusal = refusal;
    }

    /** 409 {@code backup_copying} while a backup holds the photos. */
    public void check() {
        Optional<String> words = refusal.get();
        if (words.isPresent()) {
            throw new AtlasException(AtlasException.Kind.CONFLICT, CODE, words.get());
        }
    }

    /** Runs {@code work} unless a backup holds the photos (then 409); a backup starting meanwhile waits for it. */
    public <T> T whileFree(Supplier<T> work) {
        lock.readLock().lock();
        try {
            check();
            return work.get();
        } finally {
            lock.readLock().unlock();
        }
    }

    /** As {@link #whileFree(Supplier)}, for work with no answer. */
    public void whileFree(Runnable work) {
        whileFree(() -> {
            work.run();
            return null;
        });
    }

    /** Runs {@code work} (a backup starting or resuming) once the changes under way are over, none starting meanwhile. */
    public <T> T claim(Supplier<T> work) {
        lock.writeLock().lock();
        try {
            return work.get();
        } finally {
            lock.writeLock().unlock();
        }
    }
}
