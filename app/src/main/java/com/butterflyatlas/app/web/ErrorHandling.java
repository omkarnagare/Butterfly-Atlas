package com.butterflyatlas.app.web;

import com.butterflyatlas.app.AtlasException;
import com.butterflyatlas.app.ErrorCodes;
import com.butterflyatlas.app.db.DatabaseBusy;
import com.butterflyatlas.app.library.LibraryDrive;
import com.butterflyatlas.app.photos.HeicStatus;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.HttpResponseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Turns every failure into the {@link ApiError} shape. Unexpected errors are logged, never sent; a database or I/O
 * failure while the library's drive is full or gone is said so (507 {@code drive_full}, 503 {@code drive_gone}; 5b
 * contract §7), never as the generic 500; a database that stayed busy, before that, is 503 {@code busy} (multi-window
 * contract §6).
 */
final class ErrorHandling {

    static final String INTERNAL_MESSAGE =
            "Something went wrong inside Butterfly Atlas. Try again, or close the app and open it again.";

    private static final Logger LOG = LoggerFactory.getLogger(ErrorHandling.class);

    private ErrorHandling() {
    }

    static void install(Javalin app, LibraryDrive drive) {
        app.exception(ApiException.class, (e, ctx) -> write(ctx, e.status(), e.code(), e.getMessage()));
        app.exception(AtlasException.class, (e, ctx) -> {
            if (HeicStatus.WORKSPACE_CODE.equals(e.code())) {
                // The HEIC decoder's working folder failed: when the library's drive is full or gone, say that (and the
                // status's banner hears of it); else the working folder's own words (Phase 7b review fix J3).
                LibraryDrive.Problem drives = drive.now();
                if (drives != null) {
                    write(ctx, LibraryDrive.DRIVE_FULL.equals(drives.code()) ? 507 : 503, drives.code(), drives.message());
                    return;
                }
            }
            Status status = statusOf(e.kind());
            write(ctx, status.status(), e.code() != null ? e.code() : status.code(), e.getMessage());
        });
        app.exception(HttpResponseException.class, (e, ctx) -> {
            int status = e.getStatus();
            if (status == 404 && !ctx.path().startsWith("/api/")) {
                // The UI uses hash routing, so an unknown page is simply not found.
                ctx.status(404);
                ctx.contentType("text/plain");
                ctx.result("Not found");
            } else if (status == 404) {
                // Javalin's own "Endpoint GET /x not found" is developer wording; say it in plain words.
                write(ctx, 404, ErrorCodes.NOT_FOUND, "There is nothing at " + ctx.path() + ".");
            } else if (status >= 500) {
                write(ctx, status, "internal", INTERNAL_MESSAGE);
            } else {
                write(ctx, status, codeFor(status), e.getMessage());
            }
        });
        app.exception(Exception.class, (e, ctx) -> {
            if (DatabaseBusy.is(e)) {
                // another window's long write held the database past its timeout: retryable, and said so (contract §6)
                LOG.warn("{} {} failed: the database stayed busy", ctx.method(), ctx.path(), e);
                write(ctx, 503, DatabaseBusy.CODE, DatabaseBusy.MESSAGE);
                return;
            }
            LibraryDrive.Problem drives = LibraryDrive.storageFailure(e) ? drive.now() : null;
            if (drives != null) {
                LOG.warn("{} {} failed: {}", ctx.method(), ctx.path(), drives.code(), e);
                write(ctx, LibraryDrive.DRIVE_FULL.equals(drives.code()) ? 507 : 503, drives.code(), drives.message());
                return;
            }
            LOG.error("{} {} failed", ctx.method(), ctx.path(), e);
            write(ctx, 500, "internal", INTERNAL_MESSAGE);
        });
    }

    private record Status(int status, String code) {
    }

    /** Exhaustive on purpose: a new {@code AtlasException.Kind} does not compile until it has a status. */
    private static Status statusOf(AtlasException.Kind kind) {
        return switch (kind) {
            case NOT_FOUND -> new Status(404, ErrorCodes.NOT_FOUND);
            case INVALID -> new Status(400, "bad_request");
            case SETUP_REQUIRED -> new Status(409, "setup_required");
            case ALREADY_CONFIGURED -> new Status(409, "already_configured");
            case TOO_LARGE -> new Status(413, "too_large");
            case UNSUPPORTED -> new Status(415, ErrorCodes.UNSUPPORTED_PHOTO);
            case BUSY -> new Status(503, ErrorCodes.BUSY);
            case INTERRUPTED -> new Status(400, "upload_interrupted");
            case CONFLICT -> new Status(409, "conflict");
            case FORBIDDEN -> new Status(403, "forbidden");
        };
    }

    private static String codeFor(int status) {
        return switch (status) {
            case 400 -> "bad_request";
            case 404 -> ErrorCodes.NOT_FOUND;
            case 405 -> "method_not_allowed";
            case 413 -> "too_large";
            default -> "http_" + status;
        };
    }

    static void write(Context ctx, int status, String code, String message) {
        ctx.status(status);
        ctx.json(ApiError.of(code, message));
    }
}
