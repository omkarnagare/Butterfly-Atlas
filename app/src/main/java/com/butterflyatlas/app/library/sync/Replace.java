package com.butterflyatlas.app.library.sync;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Puts a finished {@code .part} in place of the library's snapshot or marker (one-device design §5). Windows refuses to
 * replace a file another program has open, as a sync app does while it uploads the last snapshot, or as a virus scanner
 * does; such a refusal is tried again every half second, 6 times in all (about 2.5 s), before it counts as a failed
 * publish. A missing {@code .part} is not tried again.
 */
final class Replace {

    private static final Logger LOG = LoggerFactory.getLogger(Replace.class);

    static final int TRIES = 6;
    static final long WAIT_MILLIS = 500;

    /** The rename itself (tests refuse it). */
    @FunctionalInterface
    interface Mover {
        void move(Path from, Path to) throws IOException;

        Mover REAL = (from, to) -> Files.move(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private Replace() {
    }

    /** Renames {@code part} over {@code target}, trying again as the class says. */
    static void replace(Path part, Path target) throws IOException {
        replace(part, target, Mover.REAL, MarkerFile.Pause.REAL);
    }

    static void replace(Path part, Path target, Mover mover, MarkerFile.Pause pause) throws IOException {
        for (int attempt = 1; ; attempt++) {
            try {
                mover.move(part, target);
                if (attempt > 1) {
                    LOG.info("Replaced {} at attempt {}", target.getFileName(), attempt);
                }
                return;
            } catch (NoSuchFileException e) {
                throw e;
            } catch (FileSystemException e) {
                if (attempt >= TRIES) {
                    throw e;
                }
                LOG.debug("Could not replace {} yet (attempt {}): {}", target.getFileName(), attempt, e.getMessage());
                try {
                    pause.pause(WAIT_MILLIS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    InterruptedIOException stopped = new InterruptedIOException("Stopped while replacing " + target);
                    stopped.addSuppressed(e);
                    throw stopped;
                }
            }
        }
    }
}
