package com.butterflyatlas.app.web;

import com.butterflyatlas.app.photos.TagService;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.javalin.Javalin;

import java.util.List;

/** The user's tags and the built-in ones. */
final class TagRoutes {

    record Tags(List<TagService.Tag> tags) {
    }

    record Removed(int removedFromPhotos) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record LabelRequest(String label) {
    }

    private final TagService tags;

    TagRoutes(TagService tags) {
        this.tags = tags;
    }

    void register(Javalin app) {
        app.get("/api/tags", ctx -> ctx.json(new Tags(tags.list())));
        app.post("/api/tags", ctx -> ctx.json(tags.create(Json.body(ctx, LabelRequest.class).label())));
        app.patch("/api/tags/{id}", ctx -> ctx.json(tags.rename(PhotoIds.tag(ctx), Json.body(ctx, LabelRequest.class).label())));
        app.delete("/api/tags/{id}", ctx -> ctx.json(new Removed(tags.delete(PhotoIds.tag(ctx)))));
    }
}
