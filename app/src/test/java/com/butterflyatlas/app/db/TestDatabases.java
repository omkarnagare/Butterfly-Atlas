package com.butterflyatlas.app.db;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** A library database whose connections can be made to fail in ways SQLite never does on demand. */
public final class TestDatabases {

    private TestDatabases() {
    }

    /**
     * From now on, while {@code armed}, the first connection of {@code db} that commits then fails to close (as a close
     * after a commit may report an I/O error): the commit stands, and the caller sees the failure. Disarms itself.
     */
    public static void failCloseAfterCommit(Database db, AtomicBoolean armed) {
        db.connections(connection -> {
            boolean[] committed = {false};
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class},
                    (proxy, method, args) -> {
                        Object result;
                        try {
                            result = method.invoke(connection, args);
                        } catch (InvocationTargetException e) {
                            throw e.getCause();
                        }
                        committed[0] |= method.getName().equals("commit");
                        if (method.getName().equals("close") && committed[0] && armed.getAndSet(false)) {
                            throw new SQLException("disk I/O error while closing");
                        }
                        return result;
                    });
        });
    }

    /**
     * From now on, {@code hook} runs before every commit of {@code db}, on the committing thread: it may hold the write
     * (wait) or fail it (throw), as a drive that went away would.
     */
    public static void beforeCommit(Database db, Runnable hook) {
        db.connections(connection -> (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                    if (method.getName().equals("commit")) {
                        hook.run();
                    }
                    try {
                        return method.invoke(connection, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                }));
    }

    /**
     * From now on, a commit of {@code db} fails with the SQL error {@code failure} gives (null: it commits), as SQLite
     * answers when the drive is full.
     */
    public static void failCommits(Database db, Supplier<SQLException> failure) {
        db.connections(connection -> (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                    if (method.getName().equals("commit")) {
                        SQLException e = failure.get();
                        if (e != null) {
                            throw e;
                        }
                    }
                    try {
                        return method.invoke(connection, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                }));
    }

    /** From now on, while {@code failing} is true, every statement of {@code db} fails (a read error on the drive). */
    public static void failStatements(Database db, BooleanSupplier failing) {
        db.connections(connection -> (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                    if (failing.getAsBoolean() && method.getName().endsWith("Statement")) {
                        throw new SQLException("disk I/O error");
                    }
                    try {
                        return method.invoke(connection, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                }));
    }

    /**
     * From now on every statement of {@code db} fails (a drive filled to the last byte: SQLite cannot even read), and the
     * connections opened on {@code thread} are counted.
     */
    public static AtomicInteger failEverythingCounting(Database db, Thread thread) {
        AtomicInteger opened = new AtomicInteger();
        db.connections(connection -> {
            if (Thread.currentThread() == thread) {
                opened.incrementAndGet();
            }
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class},
                    (proxy, method, args) -> {
                        if (method.getName().endsWith("Statement")) {
                            throw new SQLException("[SQLITE_IOERR_SHMOPEN] I/O error within xShmMap while trying to open a"
                                    + " new shared memory segment");
                        }
                        try {
                            return method.invoke(connection, args);
                        } catch (InvocationTargetException e) {
                            throw e.getCause();
                        }
                    });
        });
        return opened;
    }

    /** From now on, counts the connections of {@code db} opened on the calling thread (others are not counted). */
    public static AtomicInteger countConnections(Database db) {
        Thread counted = Thread.currentThread();
        AtomicInteger opened = new AtomicInteger();
        db.connections(connection -> {
            if (Thread.currentThread() == counted) {
                opened.incrementAndGet();
            }
            return connection;
        });
        return opened;
    }
}
