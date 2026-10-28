package com.butterflyatlas.app.web;

import com.butterflyatlas.app.imports.FolderBrowser;
import io.javalin.Javalin;

import java.util.List;

/** The folder browser for Import folder (contract §1): shortcuts and drives, and one folder's contents. */
final class FsRoutes {

    record Roots(List<FolderBrowser.Root> roots) {
    }

    private final FolderBrowser folders;

    FsRoutes(FolderBrowser folders) {
        this.folders = folders;
    }

    void register(Javalin app) {
        app.get("/api/fs/roots", ctx -> ctx.json(new Roots(folders.roots())));
        app.get("/api/fs/list", ctx -> ctx.json(folders.list(ctx.queryParam("path"))));
    }
}
