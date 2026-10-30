package com.butterflyatlas.app.web;

import com.butterflyatlas.app.ErrorCodes;
import com.butterflyatlas.app.library.LibraryChange;
import io.javalin.http.Context;
import io.javalin.http.HandlerType;

import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * The gate of a library change (change-library-folder contract §4): while a change runs or waits to be dismissed, every
 * {@code /api} request answers 409 {@code library_changing}, except the status, the session's routes and stream, and the
 * change's own overview, Cancel and dismiss, which every window needs to show the change and end it. Checked after the
 * request guards and the session's cookie.
 */
final class ChangeGate {

    /** What passes, as {@code METHOD path}. */
    private static final Set<String> PASSES = Set.of("GET /api/status", "GET /api/session", "GET /api/session/stream",
            "POST /api/window", "GET /api/library/change", "POST /api/library/change/cancel",
            "POST /api/library/change/dismiss");

    private final BooleanSupplier blocks;

    /** {@code blocks}: whether a change runs or waits to be dismissed ({@link LibraryChange#blocks}). */
    ChangeGate(BooleanSupplier blocks) {
        this.blocks = blocks;
    }

    void check(Context ctx) {
        if (!passes(ctx.method(), ctx.path()) && blocks.getAsBoolean()) {
            throw new ApiException(409, ErrorCodes.LIBRARY_CHANGING, LibraryChange.CHANGING_MESSAGE);
        }
    }

    /** Whether a request passes the gate while a change blocks the app: anything outside {@code /api}, and the list. */
    static boolean passes(HandlerType method, String path) {
        if (!path.startsWith("/api/")) {
            return true;
        }
        return PASSES.contains(method.name() + " " + path);
    }
}
