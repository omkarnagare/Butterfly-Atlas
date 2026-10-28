package com.butterflyatlas.app.web;

import com.butterflyatlas.app.ErrorCodes;
import com.butterflyatlas.app.window.SessionKeys;
import com.butterflyatlas.app.window.WindowLauncher;
import io.javalin.http.Context;
import io.javalin.http.Header;
import io.javalin.http.HandlerType;

import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * Everything but the launch exchange needs the session cookie (contract §2, design §3): under {@code /api/} a 401
 * {@code no_session}, elsewhere (the UI's files too) a small page written here, so no UI bundle is needed to say it.
 * It runs after the Host, Origin and Sec-Fetch-Site checks, which stay as they are.
 */
final class SessionGuard {

    static final String MESSAGE = "To open Butterfly Atlas, use its icon, or New window in a Butterfly Atlas window.";
    static final String TAB_MESSAGE = " Or reload from the address the app printed.";

    private final SessionKeys keys;
    private final IntSupplier port;
    private final Supplier<WindowLauncher.Mode> mode;

    SessionGuard(SessionKeys keys, IntSupplier port, Supplier<WindowLauncher.Mode> mode) {
        this.keys = keys;
        this.port = port;
        this.mode = mode;
    }

    void check(Context ctx) {
        if (isLaunch(ctx)) {
            return;
        }
        if (keys.validCookie(ctx.cookie(SessionKeys.cookieName(port.getAsInt())))) {
            return;
        }
        if (ctx.path().startsWith("/api/")) {
            throw new ApiException(401, ErrorCodes.NO_SESSION, MESSAGE);
        }
        page(ctx, mode.get() == WindowLauncher.Mode.TAB ? MESSAGE + TAB_MESSAGE : MESSAGE);
        ctx.skipRemainingHandlers();
    }

    /** The no-session page: plain HTML, never kept by the browser. */
    static void page(Context ctx, String message) {
        ctx.status(401);
        ctx.header(Header.CACHE_CONTROL, ResponseHeaders.NO_STORE);
        ctx.html("<!doctype html>\n<html lang=\"en\"><head><meta charset=\"utf-8\"><title>Butterfly Atlas</title>"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"><style>"
                + "body{font:16px/1.5 system-ui,sans-serif;max-width:32rem;margin:20vh auto;padding:0 1rem;color:#222}"
                + "@media(prefers-color-scheme:dark){body{background:#181818;color:#eee}}</style></head><body><h1>Butterfly Atlas"
                + "</h1><p>" + message + "</p></body></html>\n");
    }

    /** The two requests that need no cookie: the exchange of a launch key, and a second launch asking for a key. */
    private static boolean isLaunch(Context ctx) {
        return ctx.method() == HandlerType.GET && ctx.path().equals(SessionKeys.LAUNCH_PATH)
                || ctx.method() == HandlerType.POST && ctx.path().equals(SessionKeys.NEW_LAUNCH_PATH);
    }
}
