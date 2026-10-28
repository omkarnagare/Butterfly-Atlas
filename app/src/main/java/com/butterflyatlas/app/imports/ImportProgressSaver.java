package com.butterflyatlas.app.imports;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * Saves the running phase's progress on its import now and then, off the threads that report it, so an import cut short
 * by the app closing is reported as stopped where it was.
 */
final class ImportProgressSaver {

    private static final Logger LOG = LoggerFactory.getLogger(ImportProgressSaver.class);

    private final ImportStore store;
    private final ImportJobs jobs;
    /** Whether the app is closing ({@link Imports#beginClose}). */
    private final BooleanSupplier closed;
    /** Started with the first phase; guarded by saverLock. */
    private ScheduledExecutorService progressSaver;
    private final Object saverLock = new Object();

    ImportProgressSaver(ImportStore store, ImportJobs jobs, BooleanSupplier closed) {
        this.store = store;
        this.jobs = jobs;
        this.closed = closed;
    }

    /**
     * Saves how far the running phase is on its import (every {@link Imports#SAVE_PROGRESS_EVERY}), for "stopped" after a crash:
     * the phases only report to memory, so the threads reporting never wait for the database.
     */
    void saveProgress() {
        jobs.current().ifPresent(p -> {
            try {
                store.saveProgress(p.runId(), p.phase(), p.done(), p.total());
            } catch (RuntimeException e) {
                LOG.debug("Could not save the progress of import {}", p.runId(), e);
            }
        });
    }

    /** Starts saving progress now and then, once (the first phase); not after closing. */
    void start() {
        synchronized (saverLock) {
            if (progressSaver != null || closed.getAsBoolean()) {
                return;
            }
            progressSaver = Executors.newSingleThreadScheduledExecutor(task -> {
                Thread thread = new Thread(task, "atlas-import-progress");
                thread.setDaemon(true);
                return thread;
            });
            long every = Imports.SAVE_PROGRESS_EVERY.toMillis();
            progressSaver.scheduleWithFixedDelay(this::saveProgress, every, every, TimeUnit.MILLISECONDS);
        }
    }

    /** Stops saving (after the jobs closed), waiting a bounded time for a save in progress. */
    void close() {
        ScheduledExecutorService saver;
        synchronized (saverLock) {
            saver = progressSaver;
        }
        if (saver == null) {
            return;
        }
        saver.shutdownNow();
        try {
            if (!saver.awaitTermination(ImportJobs.CLOSE_WAIT.toMillis(), TimeUnit.MILLISECONDS)) {
                LOG.warn("Saving import progress had not stopped when Butterfly Atlas closed");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
