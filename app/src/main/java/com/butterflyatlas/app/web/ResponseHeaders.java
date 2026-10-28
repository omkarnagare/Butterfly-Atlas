package com.butterflyatlas.app.web;

/** Header values more than one route answers with; the names come from Javalin's {@code Header}. */
final class ResponseHeaders {

    /** A placeholder or a page that must never be kept. */
    static final String NO_STORE = "no-store";
    /** Kept, but asked about again each time. */
    static final String NO_CACHE = "no-cache";
    /** A URL that changes with the photo ({@code ?v=}), so it never goes stale. */
    static final String FOREVER = "public, max-age=31536000, immutable";
    /** A file served as it is (an original photo) can run nothing. */
    static final String SANDBOX = "sandbox";
    /** Marks a placeholder whose real image is on its way. */
    static final String PENDING = "X-Atlas-Pending";

    private ResponseHeaders() {
    }
}
