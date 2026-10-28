package com.butterflyatlas.app.web;

import com.butterflyatlas.app.ErrorCodes;
import com.butterflyatlas.app.photos.PhotoFormat;
import com.butterflyatlas.app.window.SessionKeys;
import io.javalin.http.Context;
import io.javalin.http.HandlerType;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import java.util.function.IntSupplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Checks every request before it is handled. The Host must name this computer and port, which
 * defeats DNS rebinding (a web page whose domain is re-pointed at 127.0.0.1). Requests that change
 * data must come from the app's own page (Origin) and, with a body, be JSON — a plain web form
 * cannot send that. The one exception is a photo upload, whose body is the photo itself; its image
 * content types are not "simple" either, so another site cannot send one without a CORS preflight,
 * which the app never grants.
 */
final class RequestGuards {

    private static final Set<HandlerType> CHANGES = EnumSet.of(HandlerType.POST, HandlerType.PUT, HandlerType.PATCH,
            HandlerType.DELETE);
    private static final Set<HandlerType> WITH_BODY = EnumSet.of(HandlerType.POST, HandlerType.PUT, HandlerType.PATCH);
    private static final Pattern UPLOAD = Pattern.compile("^/api/taxa/[^/]+/photos$");
    /**
     * The four image types, the RAW ones ({@link PhotoFormat#RAW_CONTENT_TYPES}) and the HEIC ones
     * ({@link PhotoFormat#HEIC_CONTENT_TYPES}, sequences included: the bytes decide); never {@code application/octet-stream}.
     */
    private static final Set<String> PHOTO_TYPES = Stream.of(Arrays.stream(PhotoFormat.values())
                    .filter(f -> f != PhotoFormat.RAW).map(PhotoFormat::contentType), PhotoFormat.RAW_CONTENT_TYPES.stream(),
                    PhotoFormat.HEIC_CONTENT_TYPES.stream()).flatMap(s -> s)
            .collect(Collectors.toUnmodifiableSet());
    private static final Set<String> AVIF_TYPES = Set.of("image/avif", "image/avif-sequence");

    private final IntSupplier port;

    RequestGuards(IntSupplier port) {
        this.port = port;
    }

    void check(Context ctx) {
        int p = port.getAsInt();
        String host = ctx.header("Host");
        if (host == null || !Set.of("127.0.0.1:" + p, "localhost:" + p).contains(host.toLowerCase(Locale.ROOT))) {
            throw new ApiException(403, "forbidden_host", "Butterfly Atlas only answers requests addressed to this computer.");
        }
        if (ctx.method() == HandlerType.POST && ctx.path().equals(SessionKeys.NEW_LAUNCH_PATH)) {
            return; // only the launcher of a second copy asks (with the pointer file's key): no Origin, no body
        }
        if (!CHANGES.contains(ctx.method())) {
            checkSite(ctx);
            return;
        }
        String origin = ctx.header("Origin");
        if (origin == null
                || !Set.of("http://127.0.0.1:" + p, "http://localhost:" + p).contains(origin.toLowerCase(Locale.ROOT))) {
            throw new ApiException(403, "forbidden_origin", "Butterfly Atlas only accepts changes made in its own window.");
        }
        if (WITH_BODY.contains(ctx.method())) {
            String type = ctx.contentType();
            String essence = type == null ? "" : type.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
            if (ctx.method() == HandlerType.POST && UPLOAD.matcher(ctx.path()).matches()) {
                checkPhotoType(essence);
                return;
            }
            if (!essence.equals("application/json")) {
                throw new ApiException(415, "unsupported_media_type", "Butterfly Atlas expects changes as JSON.");
            }
        }
    }

    /**
     * Another website can point an {@code <img>} at the app's address. Browsers say where such a
     * request comes from; reading is refused unless it is the app's own window. A missing header
     * (older web views, curl) is allowed.
     */
    private static void checkSite(Context ctx) {
        if (!ctx.path().startsWith("/api/") || (ctx.method() != HandlerType.GET && ctx.method() != HandlerType.HEAD)) {
            return;
        }
        String site = ctx.header("Sec-Fetch-Site");
        if (site != null && Set.of("cross-site", "same-site").contains(site.strip().toLowerCase(Locale.ROOT))) {
            throw new ApiException(403, "forbidden_site", "Butterfly Atlas only answers its own window.");
        }
    }

    private static void checkPhotoType(String essence) {
        if (PHOTO_TYPES.contains(essence)) {
            return;
        }
        if (AVIF_TYPES.contains(essence)) {
            throw new ApiException(415, ErrorCodes.UNSUPPORTED_PHOTO, PhotoFormat.AVIF);
        }
        if (essence.startsWith("image/")) {
            throw new ApiException(415, ErrorCodes.UNSUPPORTED_PHOTO, PhotoFormat.UNREADABLE);
        }
        throw new ApiException(415, "unsupported_media_type", "Butterfly Atlas expects a photo here.");
    }
}
