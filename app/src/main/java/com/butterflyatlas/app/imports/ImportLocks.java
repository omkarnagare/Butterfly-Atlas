package com.butterflyatlas.app.imports;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The rules every change of an import keeps: one lock per import, under which its checks and its change happen; a
 * finished import is read-only; nothing changes while it files.
 */
final class ImportLocks {

    /** One lock per import: its checks and its change, filing's start, finish, relocate and discard. */
    private final Map<Long, ReentrantLock> locks = new ConcurrentHashMap<>();
    private final ImportJobs jobs;

    ImportLocks(ImportJobs jobs) {
        this.jobs = jobs;
    }

    /** The import's lock: its checks and its change, filing's start, finish, relocate and discard. */
    ReentrantLock lockOf(long id) {
        return locks.computeIfAbsent(id, key -> new ReentrantLock());
    }

    /** 409 {@code import_finished}: a finished import is read-only. */
    static void stillOpen(ImportStore.Run run) {
        if (!ImportStore.OPEN.equals(run.state())) {
            throw ImportErrors.importFinished();
        }
    }

    /** 409 {@code filing}: no change while the import files. */
    void notFiling(long id) {
        if (jobs.filing(id)) {
            throw ImportErrors.filing();
        }
    }
}
