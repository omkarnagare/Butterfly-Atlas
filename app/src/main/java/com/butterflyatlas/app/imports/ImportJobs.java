package com.butterflyatlas.app.imports;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * The background work of imports (contract §4): walking, reading and filing, on one thread, so at most one import
 * works at a time and a second import waits its turn. Work runs only when asked (the user continues an import, files,
 * or relocates it); nothing starts by itself after a restart. For the import being worked on, a request to file
 * interrupts its reading (which carries on after filing), a pause stops filing after the current batch, and a cancel
 * stops whatever runs at the next batch and waits for it.
 */
public final class ImportJobs implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(ImportJobs.class);

    /** How long closing waits for the running phase to stop. */
    static final Duration CLOSE_WAIT = Duration.ofSeconds(5);

    public static final String WALKING = "walking";
    public static final String READING = "reading";
    public static final String FILING = "filing";

    /** How far the running phase of an import is. */
    public record Progress(long runId, String phase, int done, int total) {
    }

    /** The phases themselves (the import facade supplies them). */
    public interface Work {
        /** Walks the import's folder; false when it is missing or emptied (reading then does not run). */
        boolean walk(long runId, Reporter reporter, BooleanSupplier stop);

        void read(long runId, Reporter reporter, BooleanSupplier stop);

        /** Files until done or {@code stop} (a cancel, or the user's pause). */
        void file(long runId, Reporter reporter, BooleanSupplier stop);
    }

    /** Where a phase reports its progress. */
    @FunctionalInterface
    public interface Reporter {
        void report(int done, int total);
    }

    /** What is wanted for one import. */
    private static final class Wanted {
        boolean walk;
        boolean read;
        boolean file;

        String firstPhase() {
            return walk ? WALKING : file ? FILING : READING;
        }
    }

    private final Work work;
    private final Object lock = new Object();
    /** Imports with work to do, in the order asked (guarded by lock). */
    private final Map<Long, Wanted> wanted = new LinkedHashMap<>();
    private final Set<Long> paused = new HashSet<>();
    private final Set<Long> cancelled = new HashSet<>();
    private Progress running;
    private Thread worker;
    private boolean closed;
    /** Phases that ended with an error (guarded by lock). */
    private int failures;
    /**
     * While true no filing starts (the startup's rescan and other photo-file steps are still running; guarded by lock).
     * Walking and reading go on: they never touch the library's photos.
     */
    private boolean held;

    public ImportJobs(Work work) {
        this.work = work;
    }

    /**
     * Holds filing back until {@link #release}: the startup's rescan, purge and sweep move and register photo files, and
     * filing must not run alongside them. A filing asked meanwhile waits, showing as filing at 0 of 0; walking and
     * reading run as usual (a read is not interrupted for a filing that cannot start yet).
     */
    public void hold() {
        synchronized (lock) {
            held = true;
        }
    }

    /**
     * The app is quitting: the running phase is asked to stop at its next look and nothing more starts, without waiting
     * ({@link #close} then waits, bounded). Called before the web server stops, so a long walk does not hold the quit up.
     */
    public void beginClose() {
        synchronized (lock) {
            closed = true;
            wanted.clear();
            lock.notifyAll();
        }
    }

    /** Lets held filing start. */
    public void release() {
        synchronized (lock) {
            held = false;
            lock.notifyAll();
        }
    }

    /** Walk the import's folder again, then read what is new or changed. */
    public void walkAndRead(long runId) {
        ask(runId, w -> {
            w.walk = true;
            w.read = true;
        });
    }

    /** Read what is pending (after a relocate, whose walk has already run). */
    public void read(long runId) {
        ask(runId, w -> w.read = true);
    }

    /** File the import's assigned photos; reading of the same import, if running, gives way and carries on afterwards. */
    public void file(long runId) {
        synchronized (lock) {
            paused.remove(runId);
        }
        ask(runId, w -> w.file = true);
    }

    /** Stop filing after the current batch; a filing not begun yet is dropped. True when there was filing to pause. */
    public boolean pause(long runId) {
        boolean any = false;
        synchronized (lock) {
            Wanted w = wanted.get(runId);
            if (w != null) {
                any = w.file;
                w.file = false;
                if (!w.walk && !w.read) {
                    wanted.remove(runId);
                }
            }
            if (running != null && running.runId() == runId && FILING.equals(running.phase()) && paused.add(runId)) {
                any = true;
            }
            lock.notifyAll();
        }
        return any;
    }

    /**
     * Drops the import's work and waits (at most {@code timeout}) until nothing runs for it. True when it stopped; when
     * it did not, the running phase is still asked to stop (until it ends).
     */
    public boolean cancel(long runId, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        synchronized (lock) {
            wanted.remove(runId);
            if (running == null || running.runId() != runId) {
                return true;
            }
            cancelled.add(runId);
            try {
                while (running != null && running.runId() == runId) {
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
            return true; // the worker clears the stop request when the phase ends
        }
    }

    /** The import being worked on now, if any. */
    public Optional<Progress> current() {
        synchronized (lock) {
            return Optional.ofNullable(running);
        }
    }

    /**
     * The import's phase: running, or waiting its turn (progress 0 of 0); empty when idle. A filing wanted that may start
     * (not held) shows as filing at 0 of 0 at once, even while the running walk or reading is still giving way: the
     * answer to "file" says filing, and a filing small enough to end before the next look is still seen to begin.
     */
    public Optional<Progress> of(long runId) {
        synchronized (lock) {
            Wanted w = wanted.get(runId);
            boolean filingNext = w != null && w.file && !held;
            if (running != null && running.runId() == runId) {
                return Optional.of(filingNext && !FILING.equals(running.phase()) ? new Progress(runId, FILING, 0, 0)
                        : running);
            }
            return w == null ? Optional.empty() : Optional.of(new Progress(runId, w.firstPhase(), 0, 0));
        }
    }

    /** Whether any import is filing, or has filing asked for (held or not). */
    public boolean filingAny() {
        synchronized (lock) {
            return running != null && FILING.equals(running.phase()) || wanted.values().stream().anyMatch(w -> w.file);
        }
    }

    /** Whether the import is filing or about to (mutations are refused meanwhile). */
    public boolean filing(long runId) {
        synchronized (lock) {
            if (running != null && running.runId() == runId && FILING.equals(running.phase()) && !paused.contains(runId)) {
                return true;
            }
            Wanted w = wanted.get(runId);
            return w != null && w.file;
        }
    }

    /** Waits until no work is running or waiting (tests); true if that happened within {@code timeout}. */
    public boolean awaitIdle(Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        synchronized (lock) {
            while (running != null || !wanted.isEmpty()) {
                long left = deadline - System.nanoTime();
                if (left <= 0) {
                    return false;
                }
                lock.wait(Math.max(1, left / 1_000_000));
            }
            return true;
        }
    }

    /** How many phases ended with an error so far (tests). */
    int failures() {
        synchronized (lock) {
            return failures;
        }
    }

    @Override
    public void close() {
        Thread thread;
        synchronized (lock) {
            closed = true;
            wanted.clear();
            lock.notifyAll();
            thread = worker;
        }
        if (thread != null && thread != Thread.currentThread()) {
            try {
                thread.join(CLOSE_WAIT.toMillis()); // the running phase stops at its next look
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (thread.isAlive()) {
                LOG.warn("Import work was still running when Butterfly Atlas closed");
            }
        }
    }

    private interface Change {
        void apply(Wanted wanted);
    }

    private void ask(long runId, Change change) {
        synchronized (lock) {
            if (closed) {
                return;
            }
            cancelled.remove(runId); // new work outranks an earlier stop request (one that timed out) for this import
            change.apply(wanted.computeIfAbsent(runId, id -> new Wanted()));
            if (worker == null) {
                worker = new Thread(this::loop, "atlas-import");
                worker.setDaemon(true);
                worker.start();
            }
            lock.notifyAll();
        }
    }

    private void loop() {
        while (true) {
            long runId;
            synchronized (lock) {
                Long next;
                while ((next = nextRunnable()) == null && !closed) {
                    try {
                        lock.wait();
                    } catch (InterruptedException e) {
                        return;
                    }
                }
                if (closed) {
                    return;
                }
                runId = next;
            }
            try {
                runFor(runId);
            } catch (RuntimeException | Error e) {
                LOG.error("Import {} stopped after an error", runId, e);
                synchronized (lock) {
                    failures++;
                    wanted.remove(runId);
                }
            } finally {
                synchronized (lock) {
                    running = null;
                    paused.remove(runId);
                    cancelled.remove(runId);
                    lock.notifyAll();
                }
            }
        }
    }

    /** Works on one import until it has nothing left (or is cancelled): walk, then filing before reading. */
    private void runFor(long runId) {
        while (true) {
            String phase;
            synchronized (lock) {
                Wanted w = wanted.get(runId);
                if (w == null || cancelled.contains(runId) || closed) {
                    wanted.remove(runId);
                    return;
                }
                if (w.walk) {
                    w.walk = false;
                    phase = WALKING;
                } else if (w.file && !held) {
                    w.file = false;
                    phase = FILING;
                } else if (w.read) {
                    phase = READING;
                } else if (w.file) {
                    return; // only filing is left, and it is held: it stays wanted until the startup releases it
                } else {
                    wanted.remove(runId);
                    return;
                }
                running = new Progress(runId, phase, 0, 0);
                lock.notifyAll();
            }
            Reporter reporter = (done, total) -> {
                synchronized (lock) {
                    if (running != null && running.runId() == runId) {
                        running = new Progress(runId, running.phase(), done, total);
                    }
                }
            };
            BooleanSupplier stopped = () -> {
                synchronized (lock) {
                    return cancelled.contains(runId) || closed;
                }
            };
            switch (phase) {
                case WALKING -> {
                    if (!work.walk(runId, reporter, stopped)) {
                        synchronized (lock) {
                            Wanted w = wanted.get(runId);
                            if (w != null) {
                                w.read = false; // the folder is missing or emptied: reading would find every file gone
                            }
                        }
                    }
                }
                case FILING -> work.file(runId, reporter, () -> stopped.getAsBoolean() || isPaused(runId));
                default -> {
                    work.read(runId, reporter, () -> stopped.getAsBoolean() || fileWanted(runId));
                    synchronized (lock) {
                        Wanted w = wanted.get(runId);
                        if (w != null && !(w.file && !held) && !w.walk) {
                            // done (or cancelled); a filing request that may start leaves it to carry on afterwards
                            // (a held one does not: the read ran to its end), and a walk asked meanwhile (a continue)
                            // is followed by its own reading
                            w.read = false;
                        }
                    }
                }
            }
        }
    }

    private boolean isPaused(long runId) {
        synchronized (lock) {
            return paused.contains(runId);
        }
    }

    /** Whether a filing is wanted that may start now (a held one does not interrupt reading). */
    private boolean fileWanted(long runId) {
        synchronized (lock) {
            Wanted w = wanted.get(runId);
            return w != null && w.file && !held;
        }
    }

    /** The first import, in the order asked, with work that may run now; null if none (guarded by lock). */
    private Long nextRunnable() {
        for (Map.Entry<Long, Wanted> entry : wanted.entrySet()) {
            Wanted w = entry.getValue();
            if (w.walk || w.read || (w.file && !held)) {
                return entry.getKey();
            }
        }
        return null;
    }
}
