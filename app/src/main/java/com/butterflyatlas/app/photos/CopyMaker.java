package com.butterflyatlas.app.photos;

import com.butterflyatlas.app.AtlasException;
import com.butterflyatlas.app.db.DatabaseException;
import com.butterflyatlas.app.library.Library;
import com.butterflyatlas.app.photos.CacheFiles.Source;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Makes one cached copy (a thumbnail or a display copy) from a photo's file, remembers copies that could not be made,
 * and records thumbnails in the ledger; a thumbnail's dominant colour is kept for the placeholder.
 *
 * <p>A HEIC copy that could not be made for a reason that is not the photo's ({@link HeicDecodeException}; Phase 7b
 * audit 8, 9, 13) is never recorded as failed: no decoder now, or no slot, leaves nothing behind; a timeout or no working
 * copy holds the thumbnail back for the retry time ({@link #heldBack}; the gallery is told it is on its way), and is
 * never recorded as failed (review fix J11). A HEIC thumbnail is made from its display copy when that exists (one decode per use).
 */
final class CopyMaker {

    private static final Logger LOG = LoggerFactory.getLogger(CopyMaker.class);

    private final Supplier<Library> library;
    private final PhotoProcessor processor;
    private final ProcessingLedger ledger;
    private final int version;
    private final Map<Long, String> colors = new ConcurrentHashMap<>();
    /** Files that could not be made, with when (nanoTime); entries expire after {@link #retryAfterNanos}. */
    private final Map<Path, Long> failed = new ConcurrentHashMap<>();
    /** HEIC thumbnails held back after a timeout or without a working copy, with when (nanoTime); they expire alike. */
    private final Map<Path, Long> held = new ConcurrentHashMap<>();
    /** HEIC copies remembered as failed or held back, forgotten when the decoder becomes available ({@link #forgetHeic}). */
    private final Set<Path> heic = ConcurrentHashMap.newKeySet();
    private final LongSupplier nanos;
    private final long retryAfterNanos;

    CopyMaker(Supplier<Library> library, PhotoProcessor processor, ProcessingLedger ledger, int version, LongSupplier nanos,
              long retryAfterNanos) {
        this.library = library;
        this.processor = processor;
        this.ledger = ledger;
        this.version = version;
        this.nanos = nanos;
        this.retryAfterNanos = retryAfterNanos;
    }

    /** The photo's dominant colour once its thumbnail was made, else {@code fallback}. */
    String color(long id, String fallback) {
        return colors.getOrDefault(id, fallback);
    }

    /** Whether making this file failed lately (a failure is forgotten after the retry time: then it is tried again). */
    boolean hasFailed(Path target) {
        return recent(failed, target);
    }

    /** Whether this HEIC thumbnail is held back after a timeout or a missing working copy (forgotten likewise). */
    boolean heldBack(Path target) {
        return recent(held, target);
    }

    private boolean recent(Map<Path, Long> times, Path target) {
        Long at = times.get(target);
        if (at == null) {
            return false;
        }
        if (nanos.getAsLong() - at < retryAfterNanos) {
            return true;
        }
        times.remove(target, at); // expired: a transient failure gets another try
        return false;
    }

    /** Forgets every HEIC copy remembered as failed, held back or timed out (the decoder is available now, audit 12). */
    void forgetHeic() {
        for (Path target : List.copyOf(heic)) {
            failed.remove(target);
            held.remove(target);
            heic.remove(target);
        }
    }

    /**
     * Makes a copy of {@code size} from the file the source names. A photo moved since its source was read (deleted to
     * Recently deleted, or restored, before a queued thumbnail's turn) is read again from where it is now, once; a
     * failure is remembered ({@link #hasFailed}) and, for a thumbnail, recorded in the ledger.
     */
    Path make(Source source, int size) {
        return make(source, size, size == Thumbnails.SIZE ? PhotoProcessor.Use.CATCH_UP : PhotoProcessor.Use.VIEW);
    }

    /** {@link #make(Source, int)} for a {@code use} (a HEIC decode's place in line). */
    Path make(Source source, int size, PhotoProcessor.Use use) {
        try {
            return makeFrom(source, size, use);
        } catch (Throwable e) {
            Throwable failure = e;
            Source failedSource = source;
            Source moved = e instanceof NoSuchFileException ? movedSince(source, size) : null;
            if (moved != null) {
                try {
                    return makeFrom(moved, size, use);
                } catch (Throwable again) {
                    failure = again;
                    failedSource = moved;
                }
            }
            if (failure instanceof HeicDecodeException notThePhotos) {
                return notThePhotos(failedSource, size, notThePhotos);
            }
            if (failedSource.heic()) {
                heic.add(failedSource.target(size));
            }
            return fail(failedSource, size, failure);
        }
    }

    /**
     * A HEIC copy not made for a reason that is not the photo's: nothing recorded for no decoder or no slot; a timeout
     * or no working copy held back for the retry time, never recorded as failed. Always
     * throws.
     */
    private Path notThePhotos(Source source, int size, HeicDecodeException e) {
        Path target = source.target(size);
        switch (e.kind()) {
            case TIMED_OUT -> {
                // Never written as failed (fixes J11): held back in memory, tried again after the retry time.
                heic.add(target);
                held.put(target, nanos.getAsLong());
                LOG.info("The HEIC decoder took too long for photo {}; it is tried again later", source.id());
            }
            case NO_WORKSPACE -> {
                heic.add(target);
                held.put(target, nanos.getAsLong());
                LOG.warn("No working copy for photo {}'s HEIC decode: {}", source.id(), e.getMessage());
            }
            default -> LOG.debug("Photo {}'s HEIC copy waits: {}", source.id(), e.getMessage());
        }
        throw new CompletionException(e);
    }


    /**
     * The photo's source as it is now, if its file is there and the copy would keep its name; else null. Read in a
     * write transaction: a delete or restore moves the file before it commits, and this waits for that commit (or
     * rollback, whose undo puts the file back at the same path) rather than reading the old path mid-move.
     */
    Source movedSince(Source source, int size) {
        for (int attempt = 1; ; attempt++) {
            try {
                Source now = CacheFiles.source(library.get(), source.id(), version, true);
                return Files.exists(now.file()) && now.target(size).equals(source.target(size)) ? now : null;
            } catch (AtlasException e) {
                if (e.kind() == AtlasException.Kind.NOT_FOUND) {
                    return null; // purged, or deleted with its file missing: genuinely gone
                }
                LOG.warn("Could not look up where photo {} is now", source.id(), e);
                return null;
            } catch (DatabaseException e) {
                // Busy (a long bulk delete holding the write lock past the busy timeout): once more, then give up.
                if (attempt == 2) {
                    LOG.warn("Could not look up where photo {} is now", source.id(), e);
                    return null;
                }
            } catch (RuntimeException e) {
                LOG.warn("Could not look up where photo {} is now", source.id(), e);
                return null;
            }
        }
    }

    private Path makeFrom(Source source, int size, PhotoProcessor.Use use) throws IOException {
        Path target = source.target(size);
        Path part = target.resolveSibling(target.getFileName() + Library.PART);
        try {
            if (Files.exists(target)) {
                if (size == Thumbnails.SIZE) {
                    record(source, null);
                }
                return target;
            }
            Files.createDirectories(part.getParent());
            Path display = source.target(Thumbnails.DISPLAY_SIZE);
            if (size == Thumbnails.SIZE && source.heic() && Files.exists(display)) {
                processor.thumbnail(display, 1, size, part, use); // already decoded and upright: no second decode
            } else {
                processor.thumbnail(source.file(), source.orientation(), size, part, use);
            }
            if (Files.exists(target)) {
                return madeMeanwhile(source, size, part, target);
            }
            if (size == Thumbnails.SIZE) {
                // Before the move: once the thumbnail is visible, its colour is known too.
                try {
                    colors.put(source.id(), processor.dominantColor(part));
                } catch (IOException | RuntimeException e) {
                    LOG.debug("No dominant colour for photo {}", source.id(), e);
                }
            }
            try {
                try {
                    Files.move(part, target, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(part, target);
                }
            } catch (IOException e) {
                // Made meanwhile (an import's preview adopted, or another request): on Windows an open file there
                // makes the move fail. The file in place is the same picture, so it counts as made.
                if (Files.exists(target)) {
                    return madeMeanwhile(source, size, part, target);
                }
                throw e;
            }
            if (size == Thumbnails.SIZE) {
                // After the move: a crash in between leaves a file without a row, which catch-up records without
                // decoding, never a done row without its file. (A failed move records the failure below.)
                record(source, null);
            }
            return target;
        } catch (Throwable e) {
            PhotoFiles.deleteQuietly(part);
            throw e;
        }
    }

    /**
     * The copy appeared while this one was being decoded ({@link Thumbnails#adopt}, or another request): keeps the file in place,
     * drops this one, and records the thumbnail as made (for the file version it names, as {@link #record} does).
     */
    private Path madeMeanwhile(Source source, int size, Path part, Path target) {
        PhotoFiles.deleteQuietly(part);
        if (size == Thumbnails.SIZE) {
            record(source, null);
        }
        return target;
    }

    /** Remembers and logs a copy that could not be made (and records a thumbnail's in the ledger); always throws. */
    private Path fail(Source source, int size, Throwable e) {
        Path target = source.target(size);
        // Also an Error (out of memory on a huge TIFF): remember it, so the same photo is not retried on every request.
        failed.put(target, nanos.getAsLong());
        held.remove(target);
        LOG.warn("Could not make a {} px copy of {}", size, source.file(), e);
        if (size == Thumbnails.SIZE) {
            record(source, e.toString());
        }
        if (e instanceof Error error) {
            throw error;
        }
        throw new CompletionException(e);
    }

    /**
     * Writes the thumbnail's ledger row (done when {@code error} is null); a failure to write it is only logged. The
     * row is written only while the photo still has the file version the thumbnail was made from (its modified_at and
     * size, checked in the same transaction): if a rescan replaced the content meanwhile, it stays pending for
     * catch-up instead of being marked done for content it no longer has.
     */
    private void record(Source source, String error) {
        long id = source.id();
        try {
            library.get().database().write(c -> {
                if (!CacheFiles.current(c, source)) {
                    LOG.debug("Photo {} changed while its thumbnail was made; not recording it", id);
                } else if (error == null) {
                    ledger.markDone(c, id, Thumbnails.CONSUMER, version);
                } else {
                    ledger.markFailed(c, id, Thumbnails.CONSUMER, version, error);
                }
                return null;
            });
        } catch (RuntimeException e) {
            LOG.warn("Could not record the thumbnail of photo {}", id, e);
        }
    }
}
