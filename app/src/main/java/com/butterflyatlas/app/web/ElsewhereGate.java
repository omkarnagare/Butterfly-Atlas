package com.butterflyatlas.app.web;

import com.butterflyatlas.app.ErrorCodes;
import com.butterflyatlas.app.library.sync.LibrarySync;
import io.javalin.http.Context;
import io.javalin.http.HandlerType;

import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * The gate once the library was opened on another device while open here (one-device contract §3): every {@code /api}
 * request answers 409 {@code library_opened_elsewhere}, except the status, the session's routes and stream, a new window,
 * and Quit, which every window needs to show what happened and end it. The same mechanism as {@link ChangeGate}, with its
 * own list.
 */
final class ElsewhereGate {

    /** What passes, as {@code METHOD path}. */
    private static final Set<String> PASSES = Set.of("GET /api/status", "GET /api/session", "GET /api/session/stream",
            "POST /api/window", "POST /api/library/opened-elsewhere/quit");

    private final BooleanSupplier blocks;

    /** {@code blocks}: whether the library was opened elsewhere ({@link LibrarySync#openedElsewhere}). */
    ElsewhereGate(BooleanSupplier blocks) {
        this.blocks = blocks;
    }

    void check(Context ctx) {
        if (!passes(ctx.method(), ctx.path()) && blocks.getAsBoolean()) {
            throw new ApiException(409, ErrorCodes.LIBRARY_OPENED_ELSEWHERE, LibrarySync.STOPPED_SAVING);
        }
    }

    /** Whether a request passes the gate: anything outside {@code /api}, and the list. */
    static boolean passes(HandlerType method, String path) {
        if (!path.startsWith("/api/")) {
            return true;
        }
        return PASSES.contains(method.name() + " " + path);
    }
}
