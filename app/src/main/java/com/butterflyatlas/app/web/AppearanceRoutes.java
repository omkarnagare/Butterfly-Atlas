package com.butterflyatlas.app.web;

import com.butterflyatlas.app.library.SetupService;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.javalin.Javalin;
import io.javalin.http.Context;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileSystemException;

/**
 * {@code GET} and {@code PUT /api/appearance} (Meadow UI contract §2, §3): the saved Light or Dark choice, or null to
 * follow the computer. A choice can be saved once a library folder is chosen (409 {@code not_set_up} before); a failed
 * write is 500 {@code save_failed} with its reason, and the choice in memory stays as it was.
 */
final class AppearanceRoutes {

    record Appearance(String theme) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record AppearanceRequest(String theme) {
    }

    private final SetupService setup;

    AppearanceRoutes(SetupService setup) {
        this.setup = setup;
    }

    void register(Javalin app) {
        app.get("/api/appearance", ctx -> ctx.json(new Appearance(setup.theme())));
        app.put("/api/appearance", ctx -> {
            try {
                ctx.json(new Appearance(setup.setTheme(requested(ctx))));
            } catch (UncheckedIOException e) {
                throw new ApiException(500, "save_failed", "Couldn't save the appearance: " + reason(e.getCause()));
            }
        });
    }

    /** The theme asked for; null when the body is missing, is not JSON or names none (the service asks again). */
    private static String requested(Context ctx) {
        try {
            AppearanceRequest request = ctx.bodyAsClass(AppearanceRequest.class);
            return request == null ? null : request.theme();
        } catch (Exception e) {
            return null;
        }
    }

    /** What the computer said, without the path: "Permission denied" rather than "/Users/me/config/config.json.tmp". */
    private static String reason(IOException e) {
        if (e instanceof FileSystemException fs && fs.getReason() != null) {
            return fs.getReason();
        }
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
}
