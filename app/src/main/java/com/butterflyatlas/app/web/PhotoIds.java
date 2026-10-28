package com.butterflyatlas.app.web;

import com.butterflyatlas.app.AtlasException;
import io.javalin.http.Context;

import java.util.function.Supplier;

/** Reads the {@code {id}} of a photo or tag route; anything but a whole number is simply not found. */
final class PhotoIds {

    private PhotoIds() {
    }

    static long photo(Context ctx) {
        String id = ctx.pathParam("id");
        return parse(id, () -> AtlasException.noPhoto(id));
    }

    static long tag(Context ctx) {
        return parse(ctx.pathParam("id"), () -> AtlasException.notFound("There is no such tag."));
    }

    private static long parse(String raw, Supplier<AtlasException> notFound) {
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            throw notFound.get();
        }
    }
}
