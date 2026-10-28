package com.butterflyatlas.app.db;

import com.butterflyatlas.app.ErrorCodes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.function.Supplier;

/**
 * The database stayed busy past its timeout (multi-window design §5 G5, contract §6, audit A16): another window's long
 * write held it. A request is answered 503 {@code busy} in {@link #MESSAGE}'s words; a background job tries the item again
 * ({@link #retry}) and, if it is still busy, records it with those words.
 */
public final class DatabaseBusy {

    public static final String CODE = ErrorCodes.BUSY;
    public static final String MESSAGE = "Another window is saving changes. Try again in a moment.";
    /** How many more times a background job tries an item the database was busy for. */
    public static final int RETRIES = 3;

    private static final Logger LOG = LoggerFactory.getLogger(DatabaseBusy.class);
    /** SQLite's primary result code for "database is locked" ({@code SQLITE_BUSY} and its extended codes). */
    private static final int SQLITE_BUSY = 5;

    private DatabaseBusy() {
    }

    /** Whether {@code e}, or anything in its cause chain, is SQLite's busy ({@code SQLITE_BUSY}, {@code _SNAPSHOT}, …). */
    public static boolean is(Throwable e) {
        Throwable t = e;
        for (int depth = 0; t != null && depth < 20; depth++, t = t.getCause()) { // bounded: a cause chain may loop
            if (t instanceof org.sqlite.SQLiteException sqlite && sqlite.getResultCode().name().startsWith("SQLITE_BUSY")) {
                return true;
            }
            if (t instanceof SQLException sql && (sql.getErrorCode() & 0xff) == SQLITE_BUSY) {
                return true;
            }
        }
        return false;
    }

    /**
     * Runs {@code work}, again up to {@link #RETRIES} more times while the database is busy (each try has already waited
     * the busy timeout); any other failure, or a busy one after the last try, is thrown. For work whose failed
     * transaction left nothing behind.
     */
    public static <T> T retry(String what, Supplier<T> work) {
        for (int attempt = 0; ; attempt++) {
            try {
                return work.get();
            } catch (RuntimeException e) {
                if (attempt >= RETRIES || !is(e)) {
                    throw e;
                }
                LOG.info("The database was busy while {}; trying again ({} of {})", what, attempt + 1, RETRIES);
            }
        }
    }
}
