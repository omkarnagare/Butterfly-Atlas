package com.butterflyatlas.app.web;

import com.butterflyatlas.app.AtlasException;
import com.butterflyatlas.app.search.SearchService;
import io.javalin.Javalin;

import java.util.List;
import java.util.Objects;

/** {@code GET /api/search?q=&limit=}. */
final class SearchRoutes {

    record Results(String query, List<SearchService.Hit> results) {
    }

    private final SearchService search;

    SearchRoutes(SearchService search) {
        this.search = search;
    }

    void register(Javalin app) {
        app.get("/api/search", ctx -> {
            String query = Objects.requireNonNullElse(ctx.queryParam("q"), "");
            ctx.json(new Results(query, search.search(query, limit(ctx.queryParam("limit")))));
        });
    }

    private static int limit(String value) {
        if (value == null || value.isBlank()) {
            return SearchService.DEFAULT_LIMIT;
        }
        try {
            return Integer.parseInt(value.strip());
        } catch (NumberFormatException e) {
            throw AtlasException.invalid("The search limit must be a whole number.");
        }
    }
}
