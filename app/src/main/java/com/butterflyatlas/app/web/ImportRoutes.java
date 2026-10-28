package com.butterflyatlas.app.web;

import com.butterflyatlas.app.AtlasException;
import com.butterflyatlas.app.ErrorCodes;
import com.butterflyatlas.app.imports.ImportStore;
import com.butterflyatlas.app.imports.Imports;
import com.butterflyatlas.app.photos.Thumbnails;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.Header;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.util.List;

/** Import folder (contract §2): imports, their items, the user's choices, filing, and previews from the source folder. */
final class ImportRoutes {

    record ImportList(List<Imports.Summary> imports) {
    }

    record Started(@JsonProperty("import") Imports.Summary summary, boolean continued) {
    }

    record Finished(@JsonProperty("import") Imports.Summary summary, ImportStore.Result result) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PathRequest(String path) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record AssignRequest(List<Long> itemIds, String taxonId) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TagsRequest(List<Long> itemIds, List<String> add, List<String> remove) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SkipRequest(List<Long> itemIds, Boolean skipped) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ItemsRequest(List<Long> itemIds) {
    }

    /** {@code PUT …/pairs}: how the RAW + JPEG pairs are filed (7a contract §3). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record PairsRequest(String choice) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PlaceRequest(List<Long> itemIds, String placeId, Boolean remove) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record FinishRequest(Boolean skipRest) {
    }

    /** {@code POST …/file}: the import's revision the window last had (multi-window contract §5). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record FileRequest(Long revision) {
    }

    private final Imports imports;
    private final Thumbnails thumbnails;

    ImportRoutes(Imports imports, Thumbnails thumbnails) {
        this.imports = imports;
        this.thumbnails = thumbnails;
    }

    void register(Javalin app) {
        app.get("/api/imports", ctx -> ctx.json(new ImportList(imports.list())));
        app.post("/api/imports", ctx -> {
            Imports.Started started = imports.start(Json.body(ctx, PathRequest.class).path());
            ctx.json(new Started(started.summary(), started.continued()));
        });
        app.get("/api/imports/{id}", ctx -> ctx.json(imports.summary(id(ctx))));
        app.delete("/api/imports/{id}", ctx -> {
            imports.discard(id(ctx));
            ctx.status(204);
        });
        app.get("/api/imports/{id}/items", ctx -> ctx.json(imports.items(id(ctx), since(ctx.queryParam("since")))));
        app.post("/api/imports/{id}/assign", ctx -> {
            AssignRequest request = Json.body(ctx, AssignRequest.class);
            ctx.json(imports.assign(id(ctx), request.itemIds(), request.taxonId()));
        });
        app.post("/api/imports/{id}/tags", ctx -> {
            TagsRequest request = Json.body(ctx, TagsRequest.class);
            ctx.json(imports.tags(id(ctx), request.itemIds(), request.add(), request.remove()));
        });
        app.post("/api/imports/{id}/place", ctx -> {
            PlaceRequest request = Json.body(ctx, PlaceRequest.class);
            ctx.json(imports.place(id(ctx), request.itemIds(), request.placeId(), Boolean.TRUE.equals(request.remove())));
        });
        app.post("/api/imports/{id}/skip", ctx -> {
            SkipRequest request = Json.body(ctx, SkipRequest.class);
            if (request.skipped() == null) {
                throw AtlasException.invalid("Say whether to skip the photos.");
            }
            ctx.json(imports.skip(id(ctx), request.itemIds(), request.skipped()));
        });
        app.post("/api/imports/{id}/accept-suggestions", ctx ->
                ctx.json(imports.acceptSuggestions(id(ctx), Json.body(ctx, ItemsRequest.class).itemIds())));
        app.post("/api/imports/{id}/file", ctx -> {
            Long revision = Json.body(ctx, FileRequest.class).revision();
            if (revision == null) {
                // an old page cannot skip the check
                throw new AtlasException(AtlasException.Kind.INVALID, ErrorCodes.INVALID, "Say which version of the import to file.");
            }
            Imports.Summary summary = imports.file(id(ctx), revision);
            ctx.status(202);
            ctx.json(summary);
        });
        app.post("/api/imports/{id}/pause", ctx -> ctx.json(imports.pause(id(ctx))));
        app.put("/api/imports/{id}/pairs", ctx -> ctx.json(imports.pairs(id(ctx), Json.body(ctx, PairsRequest.class).choice())));
        app.post("/api/imports/{id}/relocate", ctx ->
                ctx.json(imports.relocate(id(ctx), Json.body(ctx, PathRequest.class).path())));
        app.post("/api/imports/{id}/finish", ctx -> {
            FinishRequest request = Json.body(ctx, FinishRequest.class);
            Imports.Finished finished = imports.finish(id(ctx), Boolean.TRUE.equals(request.skipRest()));
            ctx.json(new Finished(finished.summary(), finished.result()));
        });
        app.get("/api/imports/{id}/result", ctx -> ctx.json(imports.result(id(ctx))));
        app.get("/api/imports/{id}/items/{itemId}/preview", this::preview);
        app.get("/api/imports/{id}/items/{itemId}/display", ctx -> {
            Imports.Display display = imports.display(id(ctx), itemId(ctx));
            ctx.header(Header.CACHE_CONTROL, ResponseHeaders.NO_CACHE);
            if (display.raw() != null) {
                ImageRoutes.sendPreview(ctx, display.raw());
                return;
            }
            if (display.file() == null) {
                ctx.header(Header.CONTENT_SECURITY_POLICY, ResponseHeaders.SANDBOX);
                ctx.contentType(display.contentType());
                ctx.result(display.bytes());
                return;
            }
            try {
                ImageRoutes.send(ctx, display.contentType(), display.file());
            } catch (NoSuchFileException e) {
                // removed since the lookup (the file moved, or the import was finished or discarded)
                throw AtlasException.notFound(AtlasException.CANT_SHOW_PHOTO);
            }
        });
    }

    /**
     * As the thumbnail route: never waits; a preview not made yet is the placeholder with {@code X-Atlas-Pending: 1}. A
     * filed photo's preview has become its library thumbnail, which is served instead (asked again each time, as the
     * library photo can change).
     */
    private void preview(Context ctx) throws IOException {
        Imports.Preview preview = imports.preview(id(ctx), itemId(ctx));
        if (preview.file() == null && preview.imageId() != null) {
            libraryThumbnail(ctx, preview.imageId());
        } else if (preview.file() != null) {
            InputStream in;
            try {
                in = Files.newInputStream(preview.file());
            } catch (NoSuchFileException e) {
                placeholder(ctx, false); // removed since the lookup (the import was finished or discarded)
                return;
            }
            ctx.header(Header.CACHE_CONTROL, ctx.queryParam("v") == null ? ResponseHeaders.NO_CACHE : ResponseHeaders.FOREVER);
            ctx.contentType("image/jpeg");
            ctx.result(in);
        } else {
            placeholder(ctx, preview.pending());
        }
    }

    private void libraryThumbnail(Context ctx, long imageId) {
        Thumbnails.Lookup thumb;
        try {
            thumb = thumbnails.lookup(imageId);
        } catch (AtlasException e) {
            if (e.kind() != AtlasException.Kind.NOT_FOUND) {
                throw e;
            }
            placeholder(ctx, false); // the photo has left the library since
            return;
        }
        if (thumb.file() == null) {
            placeholder(ctx, thumb.pending());
            return;
        }
        InputStream in;
        try {
            in = Files.newInputStream(thumb.file());
        } catch (IOException e) {
            thumbnails.request(imageId); // removed since the lookup: it is made again
            placeholder(ctx, true);
            return;
        }
        ctx.header(Header.CACHE_CONTROL, ResponseHeaders.NO_CACHE);
        ctx.contentType("image/jpeg");
        ctx.result(in);
    }

    private void placeholder(Context ctx, boolean pending) {
        ctx.header(Header.CACHE_CONTROL, ResponseHeaders.NO_STORE);
        if (pending) {
            ctx.header(ResponseHeaders.PENDING, "1");
        }
        ctx.contentType("image/png");
        ctx.result(thumbnails.placeholder(-1));
    }

    private static long id(Context ctx) {
        try {
            return Long.parseLong(ctx.pathParam("id"));
        } catch (NumberFormatException e) {
            throw new AtlasException(AtlasException.Kind.NOT_FOUND, "import_not_found", "That import can't be found.");
        }
    }

    private static long itemId(Context ctx) {
        try {
            return Long.parseLong(ctx.pathParam("itemId"));
        } catch (NumberFormatException e) {
            throw AtlasException.notFound("That photo is not part of this import.");
        }
    }

    private static Long since(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(raw.strip());
        } catch (NumberFormatException e) {
            throw AtlasException.invalid("The version must be a whole number.");
        }
    }
}
