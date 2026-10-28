package com.butterflyatlas.app.imports;

import com.butterflyatlas.app.AtlasException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * The phases, each recorded on its import while it runs (and its progress saved now and then), and cleared when it
 * ends, fails or is paused. The app closing mid-phase leaves the record, which {@link Imports#startup} turns into
 * "stopped". An import discarded while a phase still ran is a normal stop: the phase ends quietly and its previews go.
 */
final class ImportPhases implements ImportJobs.Work {

    private static final Logger LOG = LoggerFactory.getLogger(ImportPhases.class);

    private final ImportStore store;
    private final ImportWalker walker;
    private final ImportReader reader;
    private final ImportFiler filer;
    private final ImportPreviews previews;
    /** Imports whose last walk found their folder empty where photos still to file were (shared with {@link Imports}). */
    private final Set<Long> emptied;
    /** Whether the app is closing ({@link Imports#beginClose}). */
    private final BooleanSupplier closed;
    /** Starts saving the running phase's progress now and then (once). */
    private final Runnable startSavingProgress;

    ImportPhases(ImportStore store, ImportWalker walker, ImportReader reader, ImportFiler filer, ImportPreviews previews,
                 Set<Long> emptied, BooleanSupplier closed, Runnable startSavingProgress) {
        this.store = store;
        this.walker = walker;
        this.reader = reader;
        this.filer = filer;
        this.previews = previews;
        this.emptied = emptied;
        this.closed = closed;
        this.startSavingProgress = startSavingProgress;
    }

    @Override
    public boolean walk(long runId, ImportJobs.Reporter reporter, BooleanSupplier stop) {
        Recorder recorder = new Recorder(runId, ImportJobs.WALKING, reporter);
        try {
            return folderThere(runId, walker.walk(runId, n -> recorder.report(n, 0), stop));
        } catch (AtlasException e) {
            return discarded(runId, e);
        } finally {
            recorder.end();
        }
    }

    @Override
    public void read(long runId, ImportJobs.Reporter reporter, BooleanSupplier stop) {
        Recorder recorder = new Recorder(runId, ImportJobs.READING, reporter);
        try {
            ImportReader.Reading reading = reader.readAll(runId, recorder::report, stop);
            if (reading.changedSinceWalk() > 0 && !stop.getAsBoolean()) {
                // Files changed after the walk saw them: walk once more, then read what that walk reset.
                if (folderThere(runId, walker.walk(runId, n -> { }, stop)) && !stop.getAsBoolean()) {
                    reader.readAll(runId, recorder::report, stop);
                }
            }
        } catch (AtlasException e) {
            discarded(runId, e);
        } finally {
            recorder.end();
        }
    }

    @Override
    public void file(long runId, ImportJobs.Reporter reporter, BooleanSupplier stop) {
        Recorder recorder = new Recorder(runId, ImportJobs.FILING, reporter);
        try {
            filer.file(runId, recorder::report, stop);
        } catch (AtlasException e) {
            if (IMPORT_NOT_FOUND.equals(e.code())) {
                discarded(runId, e);
            } else if (LIBRARY_FULL.equals(e.code()) || LIBRARY_UNWRITABLE.equals(e.code())) {
                // The library cannot take the photos: filing ends here (not "stopped"), and the import says why.
                LOG.warn("Filing of import {} stopped: {}", runId, e.getMessage());
                store.setNotice(runId, e.getMessage());
            } else {
                throw unexpected(runId, e);
            }
        } catch (RuntimeException e) {
            throw unexpected(runId, e);
        } finally {
            recorder.end();
        }
    }

    /** Notes what a walk found of the folder; false when it is missing or emptied (reading then does not run). */
    private boolean folderThere(long runId, ImportWalker.Walk walk) {
        switch (walk.ended()) {
            case FOLDER_EMPTY -> {
                emptied.add(runId);
                return false;
            }
            case FOLDER_MISSING -> {
                return false;
            }
            case WALKED -> {
                emptied.remove(runId);
                return true;
            }
            default -> {
                return true; // stopped: the jobs do not go on with it
            }
        }
    }

    /** Leaves the user a trace of a filing that failed unexpectedly; the error goes on to be logged. */
    private RuntimeException unexpected(long runId, RuntimeException e) {
        try {
            store.setNotice(runId, UNEXPECTED);
        } catch (RuntimeException noted) {
            e.addSuppressed(noted);
        }
        return e;
    }

    /** An import discarded while it worked: a normal stop (its previews go when the phase ends). Else rethrows. */
    private boolean discarded(long runId, AtlasException e) {
        if (!IMPORT_NOT_FOUND.equals(e.code())) {
            throw e;
        }
        LOG.info("Import {} was discarded while it worked; stopping", runId);
        return false;
    }

    private static final String IMPORT_NOT_FOUND = "import_not_found";
    static final String UNEXPECTED = "Filing stopped because of an unexpected problem. Try continuing filing.";
    private static final String LIBRARY_FULL = "library_full";
    private static final String LIBRARY_UNWRITABLE = "library_unwritable";

    /**
     * Records a phase on its import when it starts and clears it when it ends; reports progress to the jobs only (in
     * memory: cheap enough for the reader's lock). {@link Imports#saveProgress} saves it on the import now and then; filing also
     * saves it after every batch (filing reports outside its transactions, once per batch). A phase
     * that ends because the app is closing keeps its record, with its last progress, so the next start reports it as
     * stopped.
     */
    private final class Recorder {

        private final long runId;
        private final String phase;
        private final ImportJobs.Reporter reporter;
        private volatile int done;
        private volatile int total;

        Recorder(long runId, String phase, ImportJobs.Reporter reporter) {
            this.runId = runId;
            this.phase = phase;
            this.reporter = reporter;
            startSavingProgress.run();
            store.setActive(runId, phase, 0, 0);
        }

        void report(int done, int total) {
            reporter.report(done, total);
            this.done = done;
            this.total = total;
            if (ImportJobs.FILING.equals(phase)) {
                // Each filing batch is a transaction anyway: "stopped at 300 of 400" after a kill must match what was filed.
                try {
                    store.saveProgress(runId, phase, done, total);
                } catch (RuntimeException e) {
                    LOG.debug("Could not save the progress of import {}", runId, e);
                }
            }
        }

        void end() {
            try {
                if (closed.getAsBoolean()) {
                    store.saveProgress(runId, phase, done, total); // cut short by the closing: left marked as running
                } else {
                    store.setActive(runId, null, 0, 0);
                }
                if (store.find(runId).isEmpty()) {
                    previews.deletePreviews(runId); // discarded meanwhile: previews made since then go too
                }
            } catch (RuntimeException e) {
                LOG.warn("Could not record that import {} stopped working", runId, e);
            }
        }
    }
}
