package com.butterflyatlas.app.library;

import com.butterflyatlas.app.ErrorCodes;
import com.butterflyatlas.app.db.DatabaseException;
import org.sqlite.SQLiteException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.function.ToLongFunction;

/**
 * The library's drive full or gone (5b contract §7: no silent failures), told without SQL: a drive filled to the last
 * byte leaves SQLite unable even to read ({@code SQLITE_IOERR_SHMOPEN}), so a failed request is answered in the drive's
 * words, and {@code /api/status} names the problem for an app-wide banner, checked at most every 5 seconds.
 */
public final class LibraryDrive {

    /** Under this much usable space the drive is full (as an update's moves judge it). */
    public static final long FULL_BELOW = 1024 * 1024;
    public static final String DRIVE_FULL = ErrorCodes.DRIVE_FULL;
    public static final String DRIVE_GONE = ErrorCodes.DRIVE_GONE;
    public static final Problem FULL = new Problem(DRIVE_FULL, "The drive with your library is full, so Butterfly Atlas can't"
            + " read or save anything. Free some space, then try again.");
    public static final Problem GONE = new Problem(DRIVE_GONE, "The drive with your library can't be found. Reconnect it,"
            + " then try again.");
    static final Duration EVERY = Duration.ofSeconds(5);

    /** {@code code} drive_full or drive_gone, and what to do. */
    public record Problem(String code, String message) {
    }

    private final Supplier<Optional<Path>> folder;
    private final ToLongFunction<Path> usableSpace;
    private final LongSupplier nanos;
    private final Object lock = new Object();
    private boolean checked;
    private long checkedAt;
    private Problem last;

    /** The library {@code holder} has open, its free space as the file system reports it. */
    public LibraryDrive(LibraryHolder holder) {
        this(() -> holder.current().map(Library::folder), LibraryDrive::usableSpace, System::nanoTime);
    }

    /** With the library's folder, its free space and the clock given (tests). */
    public LibraryDrive(Supplier<Optional<Path>> folder, ToLongFunction<Path> usableSpace, LongSupplier nanos) {
        this.folder = folder;
        this.usableSpace = usableSpace;
        this.nanos = nanos;
    }

    /** For {@code /api/status}: the drive's problem, or null; checked at most every 5 seconds. */
    public Problem problem() {
        synchronized (lock) {
            long now = nanos.getAsLong();
            if (checked && now - checkedAt < EVERY.toNanos()) {
                return last;
            }
        }
        return now();
    }

    /** The drive's problem checked now (a request failed), or null; the status hears of it too. */
    public Problem now() {
        Problem problem = folder.get().map(f -> check(f, usableSpace)).orElse(null);
        synchronized (lock) {
            checked = true;
            checkedAt = nanos.getAsLong();
            last = problem;
        }
        return problem;
    }

    /** {@link #GONE} when {@code folder} cannot be reached, {@link #FULL} under 1 MiB usable, else null. */
    public static Problem check(Path folder, ToLongFunction<Path> usableSpace) {
        if (!Files.isDirectory(folder)) {
            return GONE;
        }
        return usableSpace.applyAsLong(folder) < FULL_BELOW ? FULL : null;
    }

    /** The usable space of the drive {@code folder} is on; as good as unlimited when it cannot be told. */
    public static long usableSpace(Path folder) {
        try {
            return Files.getFileStore(folder).getUsableSpace();
        } catch (IOException | RuntimeException e) {
            return Long.MAX_VALUE;
        }
    }

    /** Whether a failure is one of storage: a database or I/O error anywhere among its causes. */
    public static boolean storageFailure(Throwable e) {
        Throwable t = e;
        for (int depth = 0; t != null && depth < 20; depth++, t = t.getCause()) { // bounded: a cause chain may loop
            if (t instanceof DatabaseException || t instanceof SQLException || t instanceof IOException
                    || t instanceof UncheckedIOException) {
                return true;
            }
        }
        return false;
    }

    /** Whether SQLite answered one of its I/O errors ({@code SQLITE_IOERR_*}: shared memory, write, fsync…). */
    public static boolean sqliteIoError(Throwable e) {
        Throwable t = e;
        for (int depth = 0; t != null && depth < 20; depth++, t = t.getCause()) {
            if (t instanceof SQLiteException sqlite && sqlite.getResultCode().name().startsWith("SQLITE_IOERR")) {
                return true;
            }
            String message = t.getMessage() == null ? "" : t.getMessage().toLowerCase(Locale.ROOT);
            if (message.contains("sqlite_ioerr")) {
                return true;
            }
        }
        return false;
    }
}
