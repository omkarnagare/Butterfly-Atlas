package com.butterflyatlas.app.web;

import com.butterflyatlas.app.places.PlaceSearch;
import com.butterflyatlas.app.places.UserPlaces;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.javalin.Javalin;

import java.util.List;

/** Places (3c-2 contract §1): search, recent, the user's own, and adding, renaming and deleting user districts. */
final class PlaceRoutes {

    record Places(List<PlaceSearch.Place> places) {
    }

    /** A search's answer: the places to pick, and the former places that match, never pickable (5c contract §10). */
    record Found(List<PlaceSearch.Place> places, List<PlaceSearch.FormerMatch> formerMatches) {
    }

    record MyPlaces(List<UserPlaces.MyPlace> places) {
    }

    record Cleared(int photosCleared) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record AddRequest(String name, String parentId) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record RenameRequest(String name) {
    }

    private final PlaceSearch search;
    private final UserPlaces places;

    PlaceRoutes(PlaceSearch search, UserPlaces places) {
        this.search = search;
        this.places = places;
    }

    void register(Javalin app) {
        app.get("/api/places/search", ctx -> ctx.json(new Found(search.search(ctx.queryParam("q"), ctx.queryParam("level")),
                search.formerMatches(ctx.queryParam("q"), ctx.queryParam("level")))));
        app.get("/api/places/recent", ctx -> ctx.json(new Places(places.recent())));
        app.get("/api/places/mine", ctx -> ctx.json(new MyPlaces(places.mine())));
        app.post("/api/places", ctx -> {
            AddRequest request = Json.body(ctx, AddRequest.class);
            PlaceSearch.Place added = places.add(request.name(), request.parentId());
            ctx.status(201);
            ctx.json(added);
        });
        app.patch("/api/places/{id}", ctx -> ctx.json(places.rename(ctx.pathParam("id"),
                Json.body(ctx, RenameRequest.class).name())));
        app.delete("/api/places/{id}", ctx -> ctx.json(new Cleared(places.delete(ctx.pathParam("id")))));
    }
}
