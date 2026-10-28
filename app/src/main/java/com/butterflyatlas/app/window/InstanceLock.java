package com.butterflyatlas.app.window;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;

/**
 * The library's lock (design §4): {@code .atlas.lock} in the library folder, held with an operating-system file lock for
 * the life of the process, so two copies never write one library. A lock another copy holds ({@link State#HELD}) is told
 * apart from a drive that cannot lock files at all ({@link State#CANNOT_LOCK}: some network drives, FAT variants): the app
 * starts anyway then, with a standing note (audit 10, D2). The file stays in the folder; it holds nothing.
 */
public final class InstanceLock implements AutoCloseable {

    public static final String FILE = ".atlas.lock";

    public enum State {
        ACQUIRED, HELD, CANNOT_LOCK
    }

    /** The outcome: {@code lock} only when acquired, {@code reason} only when the drive cannot lock. */
    public record Result(State state, InstanceLock lock, String reason) {
    }

    /** Takes the lock on an open file ({@link FileChannel#tryLock}; tests make it fail). */
    @FunctionalInterface
    interface Locker {
        FileLock lock(FileChannel channel) throws IOException;
    }

    private final FileChannel channel;
    private final FileLock lock;

    private InstanceLock(FileChannel channel, FileLock lock) {
        this.channel = channel;
        this.lock = lock;
    }

    public static Result acquire(Path libraryFolder) {
        return acquire(libraryFolder, FileChannel::tryLock);
    }

    /**
     * As {@link #acquire(Path)}, but a lock another copy holds is tried again (every quarter of a second) for up to
     * {@code wait}: the copy that was quitting when this one was opened is about to let go (audit 4).
     */
    public static Result acquireWithin(Path libraryFolder, Duration wait) {
        long deadline = System.nanoTime() + wait.toNanos();
        while (true) {
            Result result = acquire(libraryFolder);
            if (result.state() != State.HELD || System.nanoTime() >= deadline) {
                return result;
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return result;
            }
        }
    }

    /**
     * The lock on a file of its own choosing (the config folder's {@code instance.lock}, which one copy holds from the first
     * moment of its start until its quit starts, so a launch that comes while it is still opening its library knows).
     */
    public static Result acquireFile(Path file) {
        return lockFile(file, FileChannel::tryLock);
    }

    static Result acquire(Path libraryFolder, Locker locker) {
        return lockFile(libraryFolder.resolve(FILE), locker);
    }

    private static Result lockFile(Path file, Locker locker) {
        FileChannel channel = null;
        try {
            channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            FileLock lock = locker.lock(channel);
            if (lock == null) {
                channel.close();
                return new Result(State.HELD, null, null);
            }
            return new Result(State.ACQUIRED, new InstanceLock(channel, lock), null);
        } catch (OverlappingFileLockException e) {
            close(channel); // this program holds it already (another library object, a second start)
            return new Result(State.HELD, null, null);
        } catch (IOException | RuntimeException e) {
            close(channel);
            return new Result(State.CANNOT_LOCK, null, e.getMessage() != null ? e.getMessage() : e.toString());
        }
    }

    @Override
    public void close() throws IOException {
        try {
            lock.release();
        } finally {
            channel.close();
        }
    }

    private static void close(FileChannel channel) {
        if (channel != null) {
            try {
                channel.close();
            } catch (IOException e) {
                // nothing more to do with it
            }
        }
    }
}
