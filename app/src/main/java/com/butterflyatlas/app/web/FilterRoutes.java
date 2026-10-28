package com.butterflyatlas.app.web;

import com.butterflyatlas.app.filters.Filter;
import com.butterflyatlas.app.filters.FilterQuery;
import io.javalin.Javalin;

import java.util.List;

/** The Export screen's filter (4a contract §3): read-only GETs, the filter in the query string. */
final class FilterRoutes {

    record SpeciesList(List<FilterQuery.SpeciesRow> species) {
    }

    record Places(List<FilterQuery.PlaceRow> places) {
    }

    private final FilterQuery filters;

    FilterRoutes(FilterQuery filters) {
        this.filters = filters;
    }

    void register(Javalin app) {
        app.get("/api/filter/count", ctx -> ctx.json(filters.count(Filter.parse(ctx.queryString()))));
        app.get("/api/filter/species", ctx -> ctx.json(new SpeciesList(filters.species(Filter.parse(ctx.queryString())))));
        app.get("/api/filter/photos", ctx -> {
            Filter filter = Filter.parse(ctx.queryString());
            ctx.json(filters.photos(filter, ctx.queryParam("after"), FilterQuery.limit(ctx.queryParam("limit"))));
        });
        app.get("/api/filter/places", ctx -> ctx.json(new Places(filters.places(Filter.parse(ctx.queryString())))));
    }
}
