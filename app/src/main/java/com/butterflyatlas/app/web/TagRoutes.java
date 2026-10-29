package com.butterflyatlas.app.web;

import com.butterflyatlas.app.photos.TagService;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.javalin.Javalin;

import java.util.List;

/** The user's tags and the built-in ones. */
final class TagRoutes {

    record Tags(List<TagService.Tag> tags) {
    }

    record Removed(int removedFromPhotos) {
    }

    /** The Home note of tag names (tag-names design §4); {@code long}: the tags of more than 3 words. */
    record NameNote(List<TagService.Merged> merged, List<TagService.Numbered> numbered,
                    @JsonProperty("long") List<String> longNames, boolean dismissed) {
        static NameNote of(TagService.Note note) {
            return new NameNote(note.merged(), note.numbered(), note.longNames(), note.dismissed());
        }
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
        app.get("/api/tag-names", ctx -> ctx.json(NameNote.of(tags.note())));
        app.post("/api/tag-names/dismiss", ctx -> ctx.json(NameNote.of(tags.dismissNote())));
    }
}
