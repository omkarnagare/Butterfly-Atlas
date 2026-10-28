package com.butterflyatlas.app.web;

import com.butterflyatlas.app.exports.ExportStore;
import com.butterflyatlas.app.exports.Exports;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import io.javalin.Javalin;
import io.javalin.http.Context;

import java.util.List;

/** Exports (4b contract §2): the check, the start, the list, one export with its problems, its actions, the history. */
final class ExportRoutes {

    @JsonIgnoreProperties(ignoreUnknown = true)
    record StartRequest(String filter, String what, String path, String layout, Boolean stripLocation, String placeDepth,
                        Boolean rawWithLocation, List<String> acceptedKinds) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record CancelRequest(Boolean remove) {
    }

    /** An export with its problems: the Export's fields, then {@code problems} and {@code moreProblems}. */
    record Detail(@JsonUnwrapped Exports.Export export, List<ExportStore.Problem> problems, int moreProblems) {
    }

    record Removed(int removed) {
    }

    private final Exports exports;

    ExportRoutes(Exports exports) {
        this.exports = exports;
    }

    void register(Javalin app) {
        app.get("/api/exports/check", ctx -> ctx.json(exports.check(ctx.queryString(), ctx.queryParam("what"),
                ctx.queryParam("path"), "true".equals(ctx.queryParam("rawWithLocation"))))); // no query string: no filter
        app.post("/api/exports", ctx -> {
            StartRequest r = Json.body(ctx, StartRequest.class);
            Exports.Export export = exports.start(new Exports.Request(r.filter(), r.what(), r.path(), r.layout(),
                    r.stripLocation(), r.placeDepth(), r.rawWithLocation(), r.acceptedKinds()));
            ctx.status(201);
            ctx.json(export);
        });
        app.get("/api/exports", ctx -> ctx.json(exports.list()));
        app.delete("/api/exports", ctx -> ctx.json(new Removed(exports.clear())));
        app.get("/api/exports/{id}", ctx -> {
            Exports.Detail detail = exports.detail(id(ctx));
            ctx.json(new Detail(detail.export(), detail.problems(), detail.moreProblems()));
        });
        app.post("/api/exports/{id}/pause", ctx -> ctx.json(exports.pause(id(ctx))));
        app.post("/api/exports/{id}/resume", ctx -> ctx.json(exports.resume(id(ctx))));
        app.post("/api/exports/{id}/cancel", ctx -> {
            CancelRequest r = Json.body(ctx, CancelRequest.class);
            ctx.json(exports.cancel(id(ctx), Boolean.TRUE.equals(r.remove())));
        });
        app.delete("/api/exports/{id}", ctx -> {
            exports.delete(id(ctx));
            ctx.status(204);
        });
    }

    private static long id(Context ctx) {
        try {
            return Long.parseLong(ctx.pathParam("id"));
        } catch (NumberFormatException e) {
            throw ExportStore.notFound();
        }
    }
}
