package com.butterflyatlas.app.web;

import com.butterflyatlas.app.AtlasException;
import com.butterflyatlas.app.ErrorCodes;
import com.butterflyatlas.app.library.sync.LibrarySync;
import io.javalin.Javalin;

/**
 * One device at a time while running (one-device contract §2): Try again when publishing has stopped, putting Home's
 * one-time note away, and Quit once the
 * library was opened on another device (the app quits 1.5 seconds later). Without one device
 * at a time (tests whose libraries open in place) every route answers as when there is nothing to act on.
 */
final class SyncRoutes {

    /** {@code POST /api/library/publish}'s answer: the status's {@code publish} after the attempt (null: it worked). */
    record Published(LibrarySync.Publishing publish) {
    }

    private final LibrarySync sync;

    SyncRoutes(LibrarySync sync) {
        this.sync = sync;
    }

    void register(Javalin app) {
        app.post("/api/library/publish", ctx -> {
            if (sync == null) {
                throw new AtlasException(AtlasException.Kind.CONFLICT, ErrorCodes.NOT_STOPPED,
                        "Butterfly Atlas is not waiting for you to try sending your changes again.");
            }
            ctx.status(202).json(new Published(sync.tryAgain()));
        });
        app.post("/api/library/sync-note/dismiss", ctx -> {
            if (sync != null) {
                sync.dismissNote();
            }
            ctx.status(204);
        });
        app.post("/api/library/opened-elsewhere/quit", ctx -> {
            if (sync == null) {
                throw new AtlasException(AtlasException.Kind.CONFLICT, ErrorCodes.NOT_OPENED_ELSEWHERE,
                        "Your library wasn't opened on another device.");
            }
            sync.quit();
            ctx.status(202);
        });
    }
}
