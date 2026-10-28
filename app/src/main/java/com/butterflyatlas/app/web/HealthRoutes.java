package com.butterflyatlas.app.web;

import com.butterflyatlas.app.AtlasVersion;
import io.javalin.Javalin;

import java.util.Map;

/** {@code GET /api/health}: the UI's "is the service running" check. */
final class HealthRoutes {

    void register(Javalin app) {
        app.get("/api/health", ctx -> ctx.json(Map.of("status", "ok", "version", AtlasVersion.current())));
    }
}
