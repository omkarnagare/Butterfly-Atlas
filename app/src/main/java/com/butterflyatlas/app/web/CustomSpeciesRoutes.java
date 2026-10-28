package com.butterflyatlas.app.web;

import com.butterflyatlas.app.custom.CustomSpecies;
import com.butterflyatlas.app.custom.NameBlocked;
import com.butterflyatlas.app.photos.TaxonRef;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.javalin.Javalin;

/**
 * The user's custom species (5a contract §3): add, rename, delete, and the name check as it is typed. A name already
 * taken is 409 with the species that has it ({@code "taxon"} in the error), so the UI can offer to open it.
 */
final class CustomSpeciesRoutes {

    @JsonIgnoreProperties(ignoreUnknown = true)
    record AddRequest(String familyId, String name, String common) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record RenameRequest(String name, String common) {
    }

    /** The check's answer when the name can be used. */
    record Free(boolean ok, String name) {
    }

    /** The check's answer when it cannot; {@code taxon} is null for {@code invalid}. */
    record Blocked(boolean ok, String code, String message, TaxonRef taxon) {
    }

    /** The 409 of a name already taken: the 3a error shape with the taxon. */
    record Taken(Detail error) {

        record Detail(String code, String message, TaxonRef taxon) {
        }
    }

    private final CustomSpecies custom;

    CustomSpeciesRoutes(CustomSpecies custom) {
        this.custom = custom;
    }

    void register(Javalin app) {
        app.exception(NameBlocked.class, (e, ctx) -> {
            ctx.status(409);
            ctx.json(new Taken(new Taken.Detail(e.code(), e.getMessage(), e.taxon())));
        });
        app.get("/api/custom-species/check", ctx -> {
            CustomSpecies.Check check = custom.check(ctx.queryParam("name"), ctx.queryParam("id"));
            ctx.json(check.ok() ? new Free(true, check.name()) : new Blocked(false, check.code(), check.message(), check.taxon()));
        });
        app.post("/api/custom-species", ctx -> {
            AddRequest request = Json.body(ctx, AddRequest.class);
            ctx.status(201);
            ctx.json(custom.add(request.familyId(), request.name(), request.common()));
        });
        app.patch("/api/custom-species/{id}", ctx -> {
            RenameRequest request = Json.body(ctx, RenameRequest.class);
            ctx.json(custom.rename(ctx.pathParam("id"), request.name(), request.common()));
        });
        app.delete("/api/custom-species/{id}", ctx -> {
            custom.delete(ctx.pathParam("id"));
            ctx.status(204);
        });
    }
}
