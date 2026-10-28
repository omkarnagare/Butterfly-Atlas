package com.butterflyatlas.app.photos;

import com.butterflyatlas.app.db.Database;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * How many photos are in Recently deleted, kept in memory for {@code /api/status} (design D9): read from the library the
 * first time it is asked, then counted again inside every write that can change it ({@link PhotoChanges}' delete,
 * restore and purge), so a poll runs no SQL. Kept per library: another library opened is read afresh.
 */
final class DeletedCount {

    private record Kept(Database database, int count) {
    }

    private final Object writes = new Object();
    private volatile Kept kept;

    /** The count of {@code db}'s library, from memory once read. */
    int get(Database db) {
        Kept k = kept;
        if (k != null && k.database() == db) {
            return k.count();
        }
        synchronized (writes) {
            k = kept;
            if (k == null || k.database() != db) {
                k = new Kept(db, db.read(DeletedCount::count));
                kept = k;
            }
            return k.count();
        }
    }

    /** The count of {@code db}'s library if it is in memory, else null: never reads (a status poll). */
    Integer kept(Database db) {
        Kept k = kept;
        return k != null && k.database() == db ? k.count() : null;
    }

    /**
     * A write that may change the count: the work, then the count read again in the same write, kept once it commits.
     * When the write fails it may still have committed (a failure closing the connection), so the count is read from
     * the database again when next asked.
     */
    <T> T change(Database db, Database.Work<T> work, Runnable undo) {
        synchronized (writes) {
            int[] after = new int[1];
            try {
                T result = db.write(c -> {
                    T r = work.apply(c);
                    after[0] = count(c);
                    return r;
                }, undo);
                kept = new Kept(db, after[0]);
                return result;
            } catch (RuntimeException | Error e) {
                kept = null;
                throw e;
            }
        }
    }

    private static int count(Connection c) throws SQLException {
        return PhotoRows.count(c, "SELECT COUNT(*) FROM image WHERE deleted_at IS NOT NULL", ps -> { });
    }
}
