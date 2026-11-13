package com.butterflyatlas.app.web;

import com.butterflyatlas.app.filters.Filter;
import com.butterflyatlas.app.filters.FilterQuery;
import com.butterflyatlas.app.photos.PhotoStore;
import io.javalin.Javalin;

import java.util.List;

/**
 * The filter (4a contract §3): read-only GETs, the filter in the query string; and Explore's Delete these
 * ({@code POST /api/filter/delete}, 2026-11-13 design, addendum §2).
 */
final class FilterRoutes {

    record SpeciesList(List<FilterQuery.SpeciesRow> species) {
    }

    record Places(List<FilterQuery.PlaceRow> places) {
    }

    /** Delete these: how many photos the user confirmed. */
    record DeleteRequest(Integer expected) {
    }

    private final FilterQuery filters;
    private final PhotoStore photos;

    FilterRoutes(FilterQuery filters, PhotoStore photos) {
        this.filters = filters;
        this.photos = photos;
    }

    void register(Javalin app) {
        app.get("/api/filter/count", ctx -> ctx.json(filters.count(Filter.parse(ctx.queryString()))));
        app.get("/api/filter/species", ctx -> ctx.json(new SpeciesList(filters.species(Filter.parse(ctx.queryString())))));
        app.get("/api/filter/photos", ctx -> {
            Filter filter = Filter.parse(ctx.queryString());
            ctx.json(filters.photos(filter, ctx.queryParam("after"), FilterQuery.limit(ctx.queryParam("limit"))));
        });
        app.post("/api/filter/delete", ctx -> {
            Filter filter = Filter.parse(ctx.queryString());
            if (filter.matchesEverything()) {
                throw Filter.invalid("Choose a filter first: Delete these never deletes every photo at once.");
            }
            Integer expected = Json.body(ctx, DeleteRequest.class).expected();
            if (expected == null) {
                throw Filter.invalid("Say how many photos you confirmed.");
            }
            ctx.json(new PhotoRoutes.DeletedCount(photos.deleteMatching(c -> FilterQuery.photoIds(c, filter), expected)));
        });
        app.get("/api/filter/places", ctx -> ctx.json(new Places(filters.places(Filter.parse(ctx.queryString())))));
    }
}
