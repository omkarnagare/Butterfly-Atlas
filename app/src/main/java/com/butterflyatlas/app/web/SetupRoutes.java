package com.butterflyatlas.app.web;

import com.butterflyatlas.app.library.SetupService;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.javalin.Javalin;

/**
 * First-run choice of data folder, Try again for a remembered library that did not open, and the old library instead of
 * a new one that did not open after a change of library folder.
 */
final class SetupRoutes {

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SetupRequest(String dataFolder, Boolean exact) {
    }

    private final SetupService setup;

    SetupRoutes(SetupService setup) {
        this.setup = setup;
    }

    void register(Javalin app) {
        app.get("/api/setup", ctx -> ctx.json(setup.status()));
        // exact: the folder is the library folder (the default); exact=false: the library is kept in it (setup design §3).
        app.get("/api/setup/inspect", ctx -> ctx.json(setup.inspect(ctx.queryParam("path"),
                !"false".equals(ctx.queryParam("exact")))));
        app.post("/api/setup", ctx -> {
            SetupRequest request = Json.body(ctx, SetupRequest.class);
            ctx.json(setup.setup(request.dataFolder(), request.exact() == null || request.exact()));
        });
        app.post("/api/setup/retry", ctx -> ctx.json(setup.retry()));
        // the first open after a change of library folder: the library used before, instead of the new one
        app.post("/api/setup/previous", ctx -> ctx.json(setup.usePrevious()));
    }
}
