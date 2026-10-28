package com.butterflyatlas.app.exports;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.function.LongConsumer;
import java.util.function.Supplier;

/**
 * The background copy of exports (4b contract §3), on one thread ({@code atlas-export}), one export at a time; backups
 * (Phase 6) run on a copy of it with a thread of their own. Work runs
 * only when asked (a start or a resume); nothing starts by itself after a restart. While held (the startup's photo-file
 * steps are still running) a copy asked for waits. A stop (pause, cancel, closing) takes effect after the current file.
 */
public final class ExportJobs implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(ExportJobs.class);

    /** How long closing waits for the current file. */
    static final Duration CLOSE_WAIT = Duration.ofSeconds(5);

    /** Why the copy is asked to stop. */
    public enum Stop { NONE, PAUSE, CANCEL, CLOSING }

    /** The copy of one export (the facade supplies it): until done, or until {@code stop} says so between files. */
    @FunctionalInterface
    public interface Work {
        void copy(long runId, Supplier<Stop> stop);
    }

    private final Work work;
    private final String threadName;
    /** Records that a copy stopped on an error (the facade pauses it), so it is never left running with no worker. */
    private final LongConsumer failed;
    private final Object lock = new Object();
    /** The export asked for and not begun yet (guarded by lock). */
    private Long wanted;
    /** The export being copied now (guarded by lock). */
    private Long running;
    private Stop stop = Stop.NONE;
    /** Whether the copy has seen its stop (it is saving and recording it now; guarded by lock). */
    private boolean stopping;
    private boolean held;
    private boolean closed;
    private Thread worker;

    ExportJobs(Work work, LongConsumer failed) {
        this(work, failed, "atlas-export");
    }

    /** With the name of the thread that copies (the export's and the backup's differ). */
    public ExportJobs(Work work, LongConsumer failed, String threadName) {
        this.work = work;
        this.failed = failed;
        this.threadName = threadName;
    }

    /** Copies the export in the background (once the startup releases a hold); a no-op if it is already being copied. */
    public void run(long runId) {
        synchronized (lock) {
            if (closed || Long.valueOf(runId).equals(running) && stop == Stop.NONE) {
                return;
            }
            wanted = runId;
            if (worker == null) {
                worker = new Thread(this::loop, threadName);
                worker.setDaemon(true);
                worker.start();
            }
            lock.notifyAll();
        }
    }

    /**
     * Asks the export's copy to stop after the current file; one not begun yet is dropped. True when a copy was running
     * (it saves its progress and records the stop itself), false when none was.
     */
    public boolean stop(long runId, Stop why) {
        synchronized (lock) {
            boolean copying = Long.valueOf(runId).equals(running);
            if (Long.valueOf(runId).equals(wanted)) {
                wanted = null;
            }
            if (copying) {
                stop = why;
            }
            lock.notifyAll();
            return copying;
        }
    }

    /**
     * Takes back a pause asked for but not yet seen by the copy (a resume right after a pause, while a file copies),
     * or a cancel whose request gave up waiting (nothing waits on it any more; the resume runs under the same lock as a
     * cancel, so never while one still waits): the copy carries on. False when there is none to take back (then the
     * copy stops and records the pause). Between a copy that throws and {@code failed} recording it, the export still
     * reads running here, so a resume then answers running and the export shows {@code write_errors} a moment later.
     */
    public boolean keepGoing(long runId) {
        synchronized (lock) {
            if (Long.valueOf(runId).equals(running) && (stop == Stop.PAUSE || stop == Stop.CANCEL) && !stopping) {
                stop = Stop.NONE;
                return true;
            }
            return Long.valueOf(runId).equals(running) && stop == Stop.NONE || Long.valueOf(runId).equals(wanted);
        }
    }

    /** Whether a copy runs, or is asked for and not begun yet. */
    public boolean busy() {
        synchronized (lock) {
            return running != null || wanted != null;
        }
    }

    /** Whether the export's copy is asked to stop and has not stopped yet (tests). */
    public boolean stopAsked(long runId) {
        synchronized (lock) {
            return Long.valueOf(runId).equals(running) && stop != Stop.NONE;
        }
    }

    /** Waits (at most {@code timeout}) until the export is no longer being copied; true when it is not. */
    public boolean awaitStopped(long runId, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        synchronized (lock) {
            try {
                while (Long.valueOf(runId).equals(running)) {
                    long left = deadline - System.nanoTime();
                    if (left <= 0) {
                        return false;
                    }
                    lock.wait(Math.max(1, left / 1_000_000));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            return true;
        }
    }

    /** Waits until no copy runs or waits (tests); true if that happened within {@code timeout}. */
    public boolean awaitIdle(Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        synchronized (lock) {
            while (running != null || wanted != null && !held) {
                long left = deadline - System.nanoTime();
                if (left <= 0) {
                    return false;
                }
                lock.wait(Math.max(1, left / 1_000_000));
            }
            return true;
        }
    }

    /** Holds copies back until {@link #release} (the startup's rescan and sweep move photo files). */
    public void hold() {
        synchronized (lock) {
            held = true;
        }
    }

    public void release() {
        synchronized (lock) {
            held = false;
            lock.notifyAll();
        }
    }

    /** The app is quitting: the copy stops after the current file and nothing more starts, without waiting. */
    public void beginClose() {
        synchronized (lock) {
            closed = true;
            wanted = null;
            if (running != null) {
                stop = Stop.CLOSING;
            }
            lock.notifyAll();
        }
    }

    /** Stops as {@link #beginClose} and waits a bounded time for the current file (its progress is saved). */
    @Override
    public void close() {
        Thread thread;
        synchronized (lock) {
            beginClose();
            thread = worker;
        }
        if (thread != null && thread != Thread.currentThread()) {
            try {
                thread.join(CLOSE_WAIT.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (thread.isAlive()) {
                LOG.warn("A copy ({}) was still copying a photo when Butterfly Atlas closed", threadName);
            }
        }
    }

    private void loop() {
        while (true) {
            long runId;
            synchronized (lock) {
                while ((wanted == null || held) && !closed) {
                    try {
                        lock.wait();
                    } catch (InterruptedException e) {
                        worker = null; // a later run starts a new worker
                        return;
                    }
                }
                if (closed) {
                    return;
                }
                runId = wanted;
                wanted = null;
                running = runId;
                stop = Stop.NONE;
                stopping = false;
                lock.notifyAll();
            }
            try {
                work.copy(runId, () -> {
                    synchronized (lock) {
                        stopping |= stop != Stop.NONE; // from here a pause can no longer be taken back
                        return stop;
                    }
                });
            } catch (RuntimeException | Error e) {
                LOG.error("The copy of {} ({}) stopped after an error", runId, threadName, e);
                try {
                    failed.accept(runId);
                } catch (RuntimeException | Error recording) {
                    // left running with no copy: a pause then records paused at once (stop finds no copy), a resume
                    // runs it again, and the next start of the app pauses it (closing); the worker carries on
                    LOG.warn("Could not record that export {} stopped", runId, recording);
                }
            } finally {
                synchronized (lock) {
                    running = null;
                    stop = Stop.NONE;
                    lock.notifyAll();
                }
            }
        }
    }
}
