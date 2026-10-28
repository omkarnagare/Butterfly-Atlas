package com.butterflyatlas.app.web;

import com.butterflyatlas.app.taxa.SearchWordService;
import com.butterflyatlas.app.taxa.TaxonService;
import com.butterflyatlas.app.taxa.TaxonViews.TaxonCard;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.javalin.Javalin;

import java.util.List;

/** Tree browsing, counts and the user's search words. */
final class TaxonRoutes {

    record Children(List<TaxonCard> children) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record WordRequest(String word) {
    }

    record Words(List<String> words) {
    }

    private final TaxonService taxa;
    private final SearchWordService words;

    TaxonRoutes(TaxonService taxa, SearchWordService words) {
        this.taxa = taxa;
        this.words = words;
    }

    void register(Javalin app) {
        app.get("/api/summary", ctx -> ctx.json(taxa.summary()));
        app.get("/api/tree", ctx -> ctx.json(taxa.tree()));
        app.get("/api/taxa/{id}", ctx -> ctx.json(taxa.taxon(ctx.pathParam("id"))));
        app.get("/api/taxa/{id}/children", ctx -> ctx.json(new Children(taxa.children(ctx.pathParam("id")))));
        app.post("/api/taxa/{id}/search-words", ctx -> ctx.json(new Words(
                words.add(ctx.pathParam("id"), Json.body(ctx, WordRequest.class).word()))));
        // The word is a query parameter, so any characters (including "/") pass safely without path-encoding rules.
        app.delete("/api/taxa/{id}/search-words", ctx -> ctx.json(new Words(
                words.remove(ctx.pathParam("id"), ctx.queryParam("word")))));
    }
}
