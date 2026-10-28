package com.butterflyatlas.app.db;

import org.flywaydb.core.Flyway;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteOpenMode;
import org.sqlite.SQLiteDataSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.OptionalInt;
import java.util.function.UnaryOperator;

/**
 * The library's SQLite file. Each call gets its own short-lived connection with foreign keys on,
 * write-ahead logging and a busy timeout; a single-user app needs no pool. Opening applies any
 * pending Flyway migrations silently.
 */
public final class Database {

    public static final String FILE_NAME = "atlas.db";

    /** Work done with a connection. */
    @FunctionalInterface
    public interface Work<T> {
        T apply(Connection connection) throws SQLException;
    }

    private final Path file;
    private final SQLiteDataSource dataSource;
    /** Wraps each connection: as it is in the app; tests make one fail in ways SQLite never does on demand. */
    private volatile UnaryOperator<Connection> connections = UnaryOperator.identity();

    private Database(Path file, SQLiteDataSource dataSource) {
        this.file = file;
        this.dataSource = dataSource;
    }

    /**
     * Opens the database file, creating it if needed, and brings its schema up to date; a library made by a newer
     * Butterfly Atlas throws {@link LibraryTooNewException} first.
     */
    public static Database open(Path file) {
        return open(file, null);
    }

    /** As {@link #open(Path)}, migrating only up to {@code target} (a Flyway version; null for the latest): for migration tests. */
    static Database open(Path file, String target) {
        if (target == null) {
            refuseNewer(file);
        }
        SQLiteConfig config = new SQLiteConfig();
        config.enforceForeignKeys(true);
        config.setJournalMode(SQLiteConfig.JournalMode.WAL);
        config.setBusyTimeout(5000);
        // Write transactions take the write lock up front so a read-then-write transaction waits (busy timeout) instead of failing with SQLITE_BUSY under WAL when requests overlap.
        config.setTransactionMode(SQLiteConfig.TransactionMode.IMMEDIATE);
        SQLiteDataSource dataSource = new SQLiteDataSource(config);
        dataSource.setUrl("jdbc:sqlite:" + file.toAbsolutePath());
        try {
            var flyway = Flyway.configure()
                    .dataSource(dataSource)
                    .locations("classpath:db/migration")
                    .cleanDisabled(true);
            if (target != null) {
                flyway.target(target);
            }
            flyway.load().migrate();
        } catch (RuntimeException e) {
            throw new DatabaseException("Could not prepare the database " + file, e);
        }
        return new Database(file, dataSource);
    }

    /** The newest schema version this app ships a migration for. */
    public static int knownSchemaVersion() {
        return Known.VERSION;
    }

    private static final class Known {
        static final int VERSION = newest();

        private static int newest() {
            SQLiteDataSource memory = new SQLiteDataSource();
            memory.setUrl("jdbc:sqlite::memory:");
            return Arrays.stream(Flyway.configure().dataSource(memory).locations("classpath:db/migration").load().info().all())
                    .mapToInt(m -> Integer.parseInt(m.getVersion().getVersion())).max().orElse(0);
        }
    }

    /**
     * The newest schema version a library file records as applied, read without changing anything (and without creating
     * a file); empty when there is no file, it is empty, or it has no migration history (a new library).
     */
    public static OptionalInt newestSchemaVersion(Path file) {
        try {
            if (!Files.isRegularFile(file) || Files.size(file) == 0) {
                return OptionalInt.empty();
            }
        } catch (IOException e) {
            return OptionalInt.empty();
        }
        SQLiteConfig config = new SQLiteConfig();
        config.setOpenMode(SQLiteOpenMode.READWRITE); // never CREATE: a missing file is not made here
        try (Connection c = config.createConnection("jdbc:sqlite:" + file.toAbsolutePath());
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT MAX(CAST(version AS INTEGER)) FROM flyway_schema_history"
                     + " WHERE success = 1 AND version IS NOT NULL")) {
            if (rs.next()) {
                int newest = rs.getInt(1);
                return rs.wasNull() ? OptionalInt.empty() : OptionalInt.of(newest);
            }
        } catch (SQLException e) {
            // not a SQLite file, or no migration history: Flyway deals with it (and says why) when it opens
        }
        return OptionalInt.empty();
    }

    /** A library newer than this app is refused before anything touches it (audit 5). */
    private static void refuseNewer(Path file) {
        OptionalInt found = newestSchemaVersion(file);
        if (found.isPresent() && found.getAsInt() > knownSchemaVersion()) {
            throw new LibraryTooNewException(found.getAsInt(), knownSchemaVersion());
        }
    }

    /** Wraps each connection from now on (tests). */
    void connections(UnaryOperator<Connection> wrap) {
        connections = wrap;
    }

    private Connection connection() throws SQLException {
        return connections.apply(dataSource.getConnection());
    }

    public Path file() {
        return file;
    }

    /** Runs work outside a transaction; intended for reads. */
    public <T> T read(Work<T> work) {
        try (Connection connection = connection()) {
            return work.apply(connection);
        } catch (SQLException e) {
            throw new DatabaseException("Database read failed", e);
        }
    }

    /**
     * Runs work that must see one state of the database (several statements answering one question) inside a deferred
     * read transaction: every statement sees the same committed state, while other connections may still commit. It
     * takes no write lock, as the connection's own (IMMEDIATE) transactions would. It is for reads only: a write inside
     * it would take the write lock there and then and be committed with the rest when the work returns.
     */
    public <T> T snapshot(Work<T> work) {
        try (Connection connection = connection()) {
            try (Statement st = connection.createStatement()) {
                st.execute("BEGIN DEFERRED");
            }
            T result;
            try {
                result = work.apply(connection);
            } catch (SQLException | RuntimeException | Error e) {
                try (Statement st = connection.createStatement()) {
                    st.execute("ROLLBACK");
                } catch (SQLException rollbackFailure) {
                    e.addSuppressed(rollbackFailure);
                }
                throw e;
            }
            try (Statement st = connection.createStatement()) {
                st.execute("COMMIT");
            }
            return result;
        } catch (SQLException e) {
            throw new DatabaseException("Database read failed", e);
        }
    }

    /** Runs work in one transaction: committed if it returns, rolled back if it throws. */
    public <T> T write(Work<T> work) {
        return write(work, () -> { });
    }

    /**
     * Like {@link #write(Work)}, and runs {@code undo} when the work did not commit (it threw, or the
     * commit itself failed), before the rollback: the transaction still holds the write lock, so no other
     * writer sees the database and the files out of step meanwhile. Not run when the commit went through,
     * even if something fails afterwards, so a caller can put back files it moved inside the work.
     */
    public <T> T write(Work<T> work, Runnable undo) {
        boolean committed = false;
        boolean undone = false;
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            try {
                T result = work.apply(connection);
                connection.commit();
                committed = true;
                return result;
            } catch (SQLException | RuntimeException | Error e) {
                if (!committed) {
                    undone = true;
                    try {
                        undo.run();
                    } catch (RuntimeException | Error undoFailure) {
                        e.addSuppressed(undoFailure);
                    }
                    try {
                        connection.rollback();
                    } catch (SQLException rollbackFailure) {
                        e.addSuppressed(rollbackFailure);
                    }
                }
                throw e;
            }
        } catch (SQLException e) {
            throw new DatabaseException("Database write failed", e);
        } finally {
            if (!committed && !undone) {
                undo.run(); // failed before the work began (no connection): nothing was moved, harmless
            }
        }
    }
}
