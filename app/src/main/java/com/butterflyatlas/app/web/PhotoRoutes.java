package com.butterflyatlas.app.web;

import com.butterflyatlas.app.AtlasException;
import com.butterflyatlas.app.photos.Photo;
import com.butterflyatlas.app.photos.PhotoPlaces;
import com.butterflyatlas.app.photos.PhotoQueries;
import com.butterflyatlas.app.photos.PhotoStore;
import com.butterflyatlas.app.photos.TagService;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.javalin.Javalin;
import io.javalin.http.Context;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.List;

/** Adding photos to a species and changing them: tags, place, species, cover, Recently deleted. */
final class PhotoRoutes {

    record Photos(List<Photo> photos) {
    }

    record Cover(Long coverId) {
    }

    record DeletedCount(int deleted) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TagsRequest(List<String> tags) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record BulkTagsRequest(List<Long> ids, List<String> add, List<String> remove) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record MoveRequest(List<Long> ids, String taxonId) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record CoverRequest(Long photoId) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record IdsRequest(List<Long> ids) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PlaceRequest(List<Long> ids, String placeId, Boolean remove) {
    }

    private final PhotoStore photos;
    private final PhotoQueries queries;
    private final TagService tags;
    private final PhotoPlaces places;

    PhotoRoutes(PhotoStore photos, PhotoQueries queries, TagService tags, PhotoPlaces places) {
        this.photos = photos;
        this.queries = queries;
        this.tags = tags;
        this.places = places;
    }

    void register(Javalin app) {
        app.post("/api/taxa/{id}/photos", this::upload);
        app.get("/api/taxa/{id}/photos", ctx -> ctx.json(new Photos(queries.photos(ctx.pathParam("id")))));
        app.patch("/api/photos/{id}", ctx -> {
            TagsRequest request = Json.body(ctx, TagsRequest.class);
            if (request.tags() == null) {
                throw AtlasException.invalid("Send the photo's tags.");
            }
            ctx.json(tags.setPhotoTags(PhotoIds.photo(ctx), request.tags()));
        });
        app.post("/api/photos/tags", ctx -> {
            BulkTagsRequest request = Json.body(ctx, BulkTagsRequest.class);
            ctx.json(new Photos(tags.edit(request.ids(), request.add(), request.remove())));
        });
        app.post("/api/photos/place", ctx -> {
            PlaceRequest request = Json.body(ctx, PlaceRequest.class);
            ctx.json(new Photos(places.place(request.ids(), request.placeId(), Boolean.TRUE.equals(request.remove()))));
        });
        app.post("/api/photos/move", ctx -> {
            MoveRequest request = Json.body(ctx, MoveRequest.class);
            ctx.json(new Photos(photos.move(request.ids(), request.taxonId())));
        });
        app.put("/api/taxa/{id}/cover", ctx -> ctx.json(new Cover(
                photos.setCover(ctx.pathParam("id"), Json.body(ctx, CoverRequest.class).photoId()))));
        app.post("/api/photos/delete", ctx -> ctx.json(new DeletedCount(
                photos.delete(Json.body(ctx, IdsRequest.class).ids()))));
    }

    /**
     * The body is read as a stream ({@code ctx.bodyInputStream()}), never with {@code ctx.body()}:
     * Javalin's {@code maxRequestSize} (1 MB) only applies to the latter, and a photo can be 300 MB.
     */
    private void upload(Context ctx) {
        long declared = ctx.req().getContentLengthLong();
        if (declared > photos.maxBytes()) {
            throw AtlasException.tooLarge(photos.tooLargeMessage());
        }
        try {
            ctx.json(photos.add(ctx.pathParam("id"), ctx.bodyInputStream(), ctx.contentType(), ctx.queryParam("name"),
                    tagKeys(ctx.queryParam("tags"))));
        } catch (AtlasException e) {
            drain(ctx); // a refusal before the body was read (a backup holding the photos, an update): never a reset
            throw e;
        }
    }

    /**
     * Reads what is left of the body and drops it (audit A5): a server that answers without reading a large upload may reset
     * the connection, and the window would say the app could not be reached instead of the refusal.
     */
    private static void drain(Context ctx) {
        try (InputStream rest = ctx.req().getInputStream()) {
            rest.transferTo(OutputStream.nullOutputStream());
        } catch (IOException | RuntimeException e) {
            // the connection is gone: nothing more to read
        }
    }

    private static List<String> tagKeys(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(raw.split(",")).map(String::strip).filter(k -> !k.isEmpty()).toList();
    }
}
