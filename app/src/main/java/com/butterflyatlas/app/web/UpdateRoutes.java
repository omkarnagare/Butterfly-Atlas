package com.butterflyatlas.app.web;

import com.butterflyatlas.app.updates.SplitSorter;
import com.butterflyatlas.app.updates.UpdateViews;
import com.butterflyatlas.app.updates.Updates;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.javalin.Javalin;

import java.util.List;

/** Taxonomy updates (5b contract §5): the update, Apply, Retry, Later, Home card's Dismiss; split species and Sort; custom-species matches. */
final class UpdateRoutes {

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SortRequest(List<Long> photoIds, String target) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record MatchRequest(String officialId) {
    }

    record Splits(List<UpdateViews.SplitBrief> splits) {
    }

    record Matches(List<UpdateViews.Match> matches) {
    }

    private final Updates updates;
    private final SplitSorter splits;

    UpdateRoutes(Updates updates, SplitSorter splits) {
        this.updates = updates;
        this.splits = splits;
    }

    void register(Javalin app) {
        app.get("/api/update", ctx -> ctx.json(updates.update()));
        app.post("/api/update/apply", ctx -> {
            UpdateViews.Update update = updates.apply();
            ctx.status(202);
            ctx.json(update);
        });
        app.post("/api/update/retry", ctx -> {
            UpdateViews.Update update = updates.retry();
            ctx.status(202);
            ctx.json(update);
        });
        app.post("/api/update/later", ctx -> {
            updates.later();
            ctx.status(204);
        });
        app.post("/api/update/dismiss", ctx -> {
            updates.dismiss();
            ctx.status(204);
        });
        app.get("/api/splits", ctx -> ctx.json(new Splits(splits.splits())));
        app.get("/api/splits/{id}", ctx -> ctx.json(splits.split(ctx.pathParam("id"))));
        app.post("/api/splits/{id}/sort", ctx -> {
            SortRequest r = Json.body(ctx, SortRequest.class);
            ctx.json(splits.sort(ctx.pathParam("id"), r.photoIds(), r.target()));
        });
        app.get("/api/custom-matches", ctx -> ctx.json(new Matches(updates.matches())));
        app.post("/api/custom-matches/{customId}/merge", ctx -> {
            UpdateViews.Update update = updates.merge(ctx.pathParam("customId"), Json.body(ctx, MatchRequest.class).officialId());
            ctx.status(202);
            ctx.json(update);
        });
        app.post("/api/custom-matches/{customId}/decline", ctx -> {
            updates.decline(ctx.pathParam("customId"), Json.body(ctx, MatchRequest.class).officialId());
            ctx.status(204);
        });
    }
}
