package com.butterflyatlas.app.web;

import com.butterflyatlas.app.backup.Backups;
import com.butterflyatlas.app.exports.Exports;
import com.butterflyatlas.app.imports.Imports;
import com.butterflyatlas.app.photos.PhotoStore;
import io.javalin.Javalin;
import io.javalin.json.JavalinJackson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Wires the HTTP API onto a Javalin instance: guards first, then errors, then routes. */
public final class Api {

    private static final Logger LOG = LoggerFactory.getLogger(Api.class);

    private Api() {
    }

    /**
     * Reads what {@code /api/status} keeps in memory (Recently deleted's count, the imports, the export): called when a
     * library opens, so no poll reads the database. A failure is logged; the status reads them later on its own thread.
     */
    public static void readStatus(PhotoStore photos, Imports imports, Exports exports, Backups backups) {
        try {
            StatusRoutes.read(photos, imports, exports, backups);
        } catch (RuntimeException e) {
            LOG.warn("Could not read the status's counts when the library opened", e);
        }
    }

    /** Installs the API with no session secret: every request is answered (tests of the app itself). */
    public static Runnable install(Javalin app, AtlasServices services) {
        return install(app, services, null);
    }

    /**
     * Installs the API; returns what closes it once the server has stopped (the status's reads on threads of their own).
     * With a {@code session}, every request but the launch exchange needs its cookie (contract §2), checked after the
     * Host, Origin and Sec-Fetch-Site guards.
     */
    public static Runnable install(Javalin app, AtlasServices services, Session session) {
        app.before(new RequestGuards(app::port)::check);
        if (session != null) {
            app.before(new SessionGuard(session.keys(), app::port, session.mode())::check);
        }
        app.before(new ChangeGate(services.change()::blocks)::check); // while the library folder changes (its contract §4)
        ErrorHandling.install(app, services.drive());
        // Whatever the answer, no other site may embed or sniff it.
        app.after("/api/*", ctx -> {
            ctx.header("Cross-Origin-Resource-Policy", "same-origin");
            ctx.header("X-Content-Type-Options", "nosniff");
        });
        new HealthRoutes().register(app);
        new SetupRoutes(services.setup()).register(app);
        new AppearanceRoutes(services.setup()).register(app);
        new TaxonRoutes(services.taxa(), services.searchWords()).register(app);
        new SearchRoutes(services.search()).register(app);
        new CustomSpeciesRoutes(services.custom()).register(app);
        new PhotoRoutes(services.photos(), services.photoQueries(), services.tags(), services.photoPlaces()).register(app);
        new TagRoutes(services.tags()).register(app);
        new PlaceRoutes(services.placeSearch(), services.places()).register(app);
        new FilterRoutes(services.filters()).register(app);
        new DeletedRoutes(services.photos(), services.photoQueries()).register(app);
        new ImageRoutes(services.thumbnails(), services.photoQueries()).register(app);
        new FsRoutes(services.folders()).register(app);
        new ImportRoutes(services.imports(), services.thumbnails()).register(app);
        new ExportRoutes(services.exports()).register(app);
        new BackupRoutes(services.backups()).register(app);
        new UpdateRoutes(services.updates(), services.splits()).register(app);
        new PlaceUpdateRoutes(services.placeNotes()).register(app);
        new HeicRoutes(services.heic()).register(app);
        new LibraryChangeRoutes(services.change()).register(app);
        StatusRoutes status = new StatusRoutes(services.rescan(), services.thumbnails(), services.photos(),
                services.processing(), services.imports(), services.exports(), services.updates(), services.drive(),
                services.placeNotes(), services.backups(), services.setup()::theme,
                () -> services.change().changing().orElse(null));
        status.register(app);
        JavalinJackson json = new JavalinJackson();
        SessionRoutes sessions = session == null ? null : new SessionRoutes(session, services.setup(),
                () -> json.toJsonString(status.status(), StatusRoutes.Status.class), services.change()::running);
        if (sessions != null) {
            sessions.register(app);
        }
        return () -> {
            try {
                status.close();
            } finally {
                if (sessions != null) {
                    sessions.close();
                }
            }
        };
    }
}
