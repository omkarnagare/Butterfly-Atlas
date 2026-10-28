package com.butterflyatlas.app.web;

import com.butterflyatlas.app.AtlasException;
import io.javalin.http.Context;
import io.javalin.http.HttpResponseException;

/** Reads a JSON request body, answering 400 in plain language when it is not valid. */
final class Json {

    private Json() {
    }

    static <T> T body(Context ctx, Class<T> type) {
        T value;
        try {
            value = ctx.bodyAsClass(type);
        } catch (HttpResponseException e) {
            throw e; // e.g. 413 keeps its own status
        } catch (Exception e) {
            // Jackson's JacksonException (possibly thrown sneakily as a checked exception) and anything else unreadable.
            throw AtlasException.invalid("The request was not valid JSON.");
        }
        if (value == null) {
            throw AtlasException.invalid("The request was not valid JSON.");
        }
        return value;
    }
}
