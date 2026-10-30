package com.butterflyatlas.app.web;

import com.butterflyatlas.app.library.LibraryChange;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.javalin.Javalin;

/**
 * Changing the library folder from Settings (change-library-folder contract §3): the Library folder section's facts, the
 * folder browser's live check, Move and Use a different library (202 once started; the status's {@code changing} follows
 * it), Cancel, and putting a failed change away.
 */
final class LibraryChangeRoutes {

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PathRequest(String path) {
    }

    private final LibraryChange change;

    LibraryChangeRoutes(LibraryChange change) {
        this.change = change;
    }

    void register(Javalin app) {
        app.get("/api/library/change", ctx -> ctx.json(change.overview()));
        app.get("/api/library/change/inspect", ctx -> ctx.json(change.inspect(ctx.queryParam("path"),
                ctx.queryParam("for"))));
        app.post("/api/library/change/move", ctx -> {
            PathRequest request = Json.body(ctx, PathRequest.class);
            ctx.status(202).json(change.move(request.path()));
        });
        app.post("/api/library/change/switch", ctx -> {
            PathRequest request = Json.body(ctx, PathRequest.class);
            ctx.status(202).json(change.switchTo(request.path()));
        });
        app.post("/api/library/change/cancel", ctx -> ctx.status(202).json(change.cancel()));
        app.post("/api/library/change/dismiss", ctx -> {
            change.dismiss();
            ctx.status(204);
        });
    }
}
