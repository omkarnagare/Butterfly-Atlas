package com.butterflyatlas.app.web;

import com.butterflyatlas.app.photos.PhotoQueries;
import com.butterflyatlas.app.photos.PhotoStore;
import io.javalin.Javalin;

import java.util.List;

/** Recently deleted: list, restore, remove for good. */
final class DeletedRoutes {

    record DeletedPhotos(List<PhotoQueries.DeletedPhoto> photos) {
    }

    record Removed(int removed) {
    }

    private final PhotoStore photos;
    private final PhotoQueries queries;

    DeletedRoutes(PhotoStore photos, PhotoQueries queries) {
        this.photos = photos;
        this.queries = queries;
    }

    void register(Javalin app) {
        app.get("/api/deleted", ctx -> ctx.json(new DeletedPhotos(queries.deleted())));
        app.post("/api/deleted/restore", ctx -> ctx.json(new PhotoRoutes.Photos(
                photos.restore(Json.body(ctx, PhotoRoutes.IdsRequest.class).ids()))));
        app.post("/api/deleted/empty", ctx -> {
            List<Long> ids = Json.body(ctx, PhotoRoutes.IdsRequest.class).ids();
            // No "ids" empties everything; an explicit empty list removes nothing.
            int removed = ids == null ? photos.purge(List.of()) : ids.isEmpty() ? 0 : photos.purge(ids);
            ctx.json(new Removed(removed));
        });
    }
}
