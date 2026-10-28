package com.butterflyatlas.app.web;

import com.butterflyatlas.app.library.SetupService;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.javalin.Javalin;

/** First-run choice of data folder, and Try again for a remembered library that did not open. */
final class SetupRoutes {

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SetupRequest(String dataFolder) {
    }

    private final SetupService setup;

    SetupRoutes(SetupService setup) {
        this.setup = setup;
    }

    void register(Javalin app) {
        app.get("/api/setup", ctx -> ctx.json(setup.status()));
        app.get("/api/setup/inspect", ctx -> ctx.json(setup.inspect(ctx.queryParam("path"))));
        app.post("/api/setup", ctx -> ctx.json(setup.setup(Json.body(ctx, SetupRequest.class).dataFolder())));
        app.post("/api/setup/retry", ctx -> ctx.json(setup.retry()));
    }
}
