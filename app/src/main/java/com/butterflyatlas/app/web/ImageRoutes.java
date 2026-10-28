package com.butterflyatlas.app.web;

import com.butterflyatlas.app.photos.PhotoFormat;
import com.butterflyatlas.app.photos.PhotoQueries;
import com.butterflyatlas.app.photos.RawDisplays;
import com.butterflyatlas.app.photos.Thumbnails;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.Header;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

/**
 * Thumbnails for the gallery; originals and display versions for the viewer. The viewer uses
 * {@code /display}: the original for JPEG, PNG and WebP, a 2560 px JPEG copy for TIFF, which
 * WebView2 (Windows) cannot show, so the viewer looks the same on every platform, and for RAW the JPEG preview read
 * from inside the file (the page turns it by the photo's {@code rawPreview.orientation}). A HEIC photo's is its 2560 px
 * display copy made by the computer's decoder, already upright (Phase 7b contract §2: 503 while the decoder is checked,
 * 415 {@code heic_decoder_missing} while it is missing); its original is the exact file ({@code image/heic}).
 */
final class ImageRoutes {

    private final Thumbnails thumbnails;
    private final PhotoQueries queries;

    ImageRoutes(Thumbnails thumbnails, PhotoQueries queries) {
        this.thumbnails = thumbnails;
        this.queries = queries;
    }

    void register(Javalin app) {
        app.get("/api/photos/{id}/thumb", this::thumb);
        app.get("/api/photos/{id}/original", this::original);
        app.get("/api/photos/{id}/display", this::display);
    }

    /**
     * Never waits (a gallery of hundreds would otherwise hold the browser's few connections): a thumbnail
     * not made yet gets the placeholder at once with {@code X-Atlas-Pending: 1}, and its photo moves to the
     * front of the queue; the UI asks again with back-off.
     */
    private void thumb(Context ctx) throws IOException {
        long id = PhotoIds.photo(ctx);
        Thumbnails.Lookup thumb = thumbnails.lookup(id);
        if (thumb.file() != null) {
            InputStream in;
            try {
                in = Files.newInputStream(thumb.file());
            } catch (NoSuchFileException e) {
                // Removed since the lookup (tidied as an orphan, or its photo changed): it is made again.
                thumbnails.request(id);
                placeholder(ctx, id, true);
                return;
            }
            // With ?v=<cache key> the URL changes with the photo, so it never goes stale; without one (or an empty one),
            // ask again each time.
            String v = ctx.queryParam("v");
            ctx.header(Header.CACHE_CONTROL, v == null || v.isBlank() ? ResponseHeaders.NO_CACHE : ResponseHeaders.FOREVER);
            ctx.contentType("image/jpeg");
            ctx.result(in);
        } else {
            placeholder(ctx, id, thumb.pending());
        }
    }

    private void placeholder(Context ctx, long id, boolean pending) {
        ctx.header(Header.CACHE_CONTROL, ResponseHeaders.NO_STORE);
        if (pending) {
            ctx.header(ResponseHeaders.PENDING, "1");
        }
        ctx.contentType("image/png");
        ctx.result(thumbnails.placeholder(id));
    }

    /**
     * A TIFF's display copy is made on this request when it does not exist yet (a second request
     * waits for the first): simpler than a "pending" answer the viewer would have to poll, and the
     * viewer shows the thumbnail meanwhile. Subsampled decoding keeps it to a few seconds.
     */
    private void display(Context ctx) throws IOException {
        long id = PhotoIds.photo(ctx);
        PhotoQueries.Original original = queries.original(id);
        ctx.header(Header.CACHE_CONTROL, ResponseHeaders.NO_CACHE);
        if (PhotoFormat.RAW.contentType().equals(original.contentType())) {
            Optional<RawDisplays.Preview> preview = queries.rawDisplay(id);
            if (preview.isPresent()) {
                sendPreview(ctx, preview.get());
                return;
            }
        }
        if (PhotoFormat.TIFF.contentType().equals(original.contentType())
                || PhotoFormat.HEIC.contentType().equals(original.contentType())) {
            send(ctx, "image/jpeg", thumbnails.display(id));
        } else {
            send(ctx, original.contentType(), original.file());
        }
    }

    private void original(Context ctx) throws IOException {
        PhotoQueries.Original original = queries.original(PhotoIds.photo(ctx));
        ctx.header(Header.CACHE_CONTROL, ResponseHeaders.NO_CACHE);
        send(ctx, original.contentType(), original.file());
    }

    /**
     * A RAW photo's preview as the answer ({@code image/jpeg}), with the ETag of the RAW file itself (its size and
     * modified time), so it is sent again only when the file changed. The preview's file is closed here unless its
     * stream is handed to the answer (which closes it when sent).
     */
    static void sendPreview(Context ctx, RawDisplays.Preview preview) throws IOException {
        boolean handedOver = false;
        try {
            String tag = "\"" + preview.fileSize() + "-" + preview.modified().toMillis() + "\"";
            ctx.header(Header.CONTENT_SECURITY_POLICY, ResponseHeaders.SANDBOX);
            ctx.header("ETag", tag);
            ctx.header("Last-Modified", DateTimeFormatter.RFC_1123_DATE_TIME.format(preview.modified().toInstant()
                    .atZone(ZoneOffset.UTC)));
            if (tag.equals(ctx.header("If-None-Match"))) {
                ctx.status(304);
                return;
            }
            InputStream bytes = preview.open();
            handedOver = true;
            ctx.header("Content-Length", String.valueOf(preview.length()));
            ctx.contentType("image/jpeg");
            ctx.result(bytes);
        } finally {
            if (!handedOver) {
                preview.close();
            }
        }
    }

    /**
     * A photo file as the answer: its length and modified time are known, and it can never run as a page. Its ETag is
     * its size and modified time, so a browser asking again (no-cache) for a file that has not changed gets 304 and
     * keeps its copy instead of the whole photo again; any change to the file changes the tag.
     */
    static void send(Context ctx, String contentType, Path file) throws IOException {
        long size = Files.size(file);
        FileTime modified = Files.getLastModifiedTime(file);
        String tag = "\"" + size + "-" + modified.toMillis() + "\"";
        ctx.header(Header.CONTENT_SECURITY_POLICY, ResponseHeaders.SANDBOX);
        ctx.header("ETag", tag);
        ctx.header("Last-Modified", DateTimeFormatter.RFC_1123_DATE_TIME.format(modified.toInstant().atZone(ZoneOffset.UTC)));
        if (tag.equals(ctx.header("If-None-Match"))) {
            ctx.status(304);
            return;
        }
        ctx.header("Content-Length", String.valueOf(size));
        ctx.contentType(contentType);
        ctx.result(Files.newInputStream(file));
    }
}
