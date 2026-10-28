package com.butterflyatlas.app.web;

import com.butterflyatlas.app.backup.BackupStore;
import com.butterflyatlas.app.backup.Backups;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import io.javalin.Javalin;
import io.javalin.http.Context;

import java.util.List;

/**
 * Backups (Phase 6 contract §3, §4): the list and the reminder preference, the check, the start, one backup with its
 * problems, its actions, the history.
 */
final class BackupRoutes {

    @JsonIgnoreProperties(ignoreUnknown = true)
    record StartRequest(String what, String path) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PreferenceRequest(String preference) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record CancelRequest(Boolean remove) {
    }

    /** A backup with its problems: the Backup's fields, then {@code problems} and {@code moreProblems}. */
    record Detail(@JsonUnwrapped Backups.Backup backup, List<BackupStore.Problem> problems, int moreProblems) {
    }

    record Removed(int removed) {
    }

    private final Backups backups;

    BackupRoutes(Backups backups) {
        this.backups = backups;
    }

    void register(Javalin app) {
        app.get("/api/backup", ctx -> ctx.json(backups.list()));
        app.put("/api/backup/preference", ctx -> ctx.json(backups.setPreference(
                Json.body(ctx, PreferenceRequest.class).preference())));
        app.get("/api/backup/check", ctx -> ctx.json(backups.check(ctx.queryParam("what"), ctx.queryParam("path"))));
        app.post("/api/backup", ctx -> {
            StartRequest r = Json.body(ctx, StartRequest.class);
            Backups.Backup backup = backups.start(new Backups.Request(r.what(), r.path()));
            ctx.status(201);
            ctx.json(backup);
        });
        app.delete("/api/backup", ctx -> ctx.json(new Removed(backups.clear())));
        app.get("/api/backup/{id}", ctx -> {
            Backups.Detail detail = backups.detail(id(ctx));
            ctx.json(new Detail(detail.backup(), detail.problems(), detail.moreProblems()));
        });
        app.post("/api/backup/{id}/pause", ctx -> ctx.json(backups.pause(id(ctx))));
        app.post("/api/backup/{id}/resume", ctx -> ctx.json(backups.resume(id(ctx))));
        app.post("/api/backup/{id}/cancel", ctx -> {
            CancelRequest r = Json.body(ctx, CancelRequest.class);
            ctx.json(backups.cancel(id(ctx), Boolean.TRUE.equals(r.remove())));
        });
        app.delete("/api/backup/{id}", ctx -> {
            backups.delete(id(ctx));
            ctx.status(204);
        });
    }

    private static long id(Context ctx) {
        try {
            return Long.parseLong(ctx.pathParam("id"));
        } catch (NumberFormatException e) {
            throw BackupStore.notFound();
        }
    }
}
