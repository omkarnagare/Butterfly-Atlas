package com.butterflyatlas.app.photos;

import com.butterflyatlas.app.AtlasException;
import com.butterflyatlas.app.library.Library;
import com.butterflyatlas.app.photos.CacheFiles.Source;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * Display copies (2560 px on the long side) for photos the viewer cannot show as they are, made on the request that
 * needs one, at most {@value #DISPLAY_AT_ONCE} at a time.
 *
 * <p>A HEIC photo's (Phase 7b contract §2, audit 7–9): while the decoder is checked, 503 {@code busy} ("still checking");
 * while it is missing, 415 {@code heic_decoder_missing} with this system's install note; when the decoder could not read
 * the file, 415 with the HEIC decode note; a decode that timed out or found no decoder slot in 30 s, 503 "taking too
 * long".
 */
final class DisplayCopies {

    /** Display copies are big (a 2560 px decode of a large TIFF): at most this many are made at once, whoever asks. */
    private static final int DISPLAY_AT_ONCE = 2;

    private final Supplier<Library> library;
    private final int version;
    /** Files being made, by their final path (shared with the thumbnail queue). */
    private final Map<Path, CompletableFuture<Path>> pending;
    private final CopyMaker maker;
    private final Semaphore displaySlots = new Semaphore(DISPLAY_AT_ONCE);
    private final Duration displayWait;

    DisplayCopies(Supplier<Library> library, int version, Map<Path, CompletableFuture<Path>> pending, CopyMaker maker,
                  Duration displayWait) {
        this.library = library;
        this.version = version;
        this.pending = pending;
        this.maker = maker;
        this.displayWait = displayWait;
    }

    /**
     * The display copy (2560 px on the long side, upright, sRGB JPEG), made on the calling thread
     * if it does not exist yet; a second request for the same photo waits for the first. Fails with
     * UNSUPPORTED if the photo cannot be decoded.
     */
    Path display(long id) {
        Source source = CacheFiles.source(library.get(), id, version, false);
        Path target = source.target(Thumbnails.DISPLAY_SIZE);
        if (Files.exists(target)) {
            return target;
        }
        if (source.heic()) {
            notShownNow();
        }
        if (maker.hasFailed(target)) {
            throw AtlasException.unsupported(unreadable(source));
        }
        CompletableFuture<Path> mine = new CompletableFuture<>();
        CompletableFuture<Path> running = pending.putIfAbsent(target, mine);
        if (running == null) {
            try {
                mine.complete(makeDisplay(source));
            } catch (Throwable e) {
                // Even an Error (out of memory on a huge TIFF) must release whoever waits for this copy.
                mine.completeExceptionally(e);
                if (e instanceof Error error) {
                    throw error;
                }
            } finally {
                pending.remove(target, mine);
            }
            running = mine;
        }
        try {
            return running.get(displayWait.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw AtlasException.busy(AtlasException.PHOTO_TOO_SLOW);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw AtlasException.busy(AtlasException.PHOTO_TOO_SLOW);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof AtlasException busy) {
                throw busy; // e.g. the maker was interrupted while waiting for a slot
            }
            if (e.getCause() instanceof HeicDecodeException heic) {
                if (heic.kind() == HeicDecodeException.Kind.UNAVAILABLE) {
                    notShownNow();
                }
                if (heic.kind() == HeicDecodeException.Kind.NO_WORKSPACE) {
                    throw HeicStatus.workspaceProblem(); // the library's working folder, not the photo (fixes J3)
                }
                throw AtlasException.busy(AtlasException.PHOTO_TOO_SLOW); // timed out, or no slot: try again
            }
            throw AtlasException.unsupported(unreadable(source));
        }
    }

    /** The words for a copy that cannot be made: a HEIC's decode note (contract §1), else the unreadable photo's. */
    private static String unreadable(Source source) {
        return source.heic() ? HeicStatus.DECODE_FAILED : PhotoFormat.UNREADABLE;
    }

    private static void notShownNow() {
        HeicStatus.requireShown();
    }

    /** A display copy, at most {@link #DISPLAY_AT_ONCE} being made at a time across all requests. */
    private Path makeDisplay(Source source) {
        try {
            displaySlots.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw AtlasException.busy(AtlasException.PHOTO_TOO_SLOW);
        }
        try {
            return maker.make(source, Thumbnails.DISPLAY_SIZE, PhotoProcessor.Use.VIEW);
        } finally {
            displaySlots.release();
        }
    }
}
