package com.butterflyatlas.app.web;

import com.butterflyatlas.app.placeupdates.PlaceNotes;
import io.javalin.Javalin;

/** A place update's Home note (5c contract §6, §9): the note (204 when there is nothing to show) and Dismiss. */
final class PlaceUpdateRoutes {

    private final PlaceNotes notes;

    PlaceUpdateRoutes(PlaceNotes notes) {
        this.notes = notes;
    }

    void register(Javalin app) {
        app.get("/api/place-update", ctx -> {
            PlaceNotes.Note note = notes.note();
            if (note == null) {
                ctx.status(204);
            } else {
                ctx.json(note);
            }
        });
        app.post("/api/place-update/dismiss", ctx -> {
            notes.dismiss();
            ctx.status(204);
        });
    }
}
