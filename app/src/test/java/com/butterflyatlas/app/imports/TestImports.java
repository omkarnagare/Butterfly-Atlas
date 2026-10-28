package com.butterflyatlas.app.imports;

import com.butterflyatlas.app.library.Library;
import com.butterflyatlas.app.photos.PhotoProcessor;
import com.butterflyatlas.app.photos.PhotoStore;
import com.butterflyatlas.app.photos.Thumbnails;
import com.butterflyatlas.app.search.SearchService;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.function.Supplier;

/** Reaches the imports' test seams from other packages (the web tests, Main's). */
public final class TestImports {

    private TestImports() {
    }

    /** Imports whose cancels (finish, relocate, discard) wait {@code cancelWait} for the running step. */
    public static Imports create(Supplier<Library> library, Path home, SearchService search, PhotoStore photos,
                                 PhotoProcessor processor, Thumbnails thumbnails, Clock clock, Duration cancelWait) {
        return new Imports(library, home, search, photos, processor, thumbnails, clock,
                new ImportFiler(library, photos, processor, thumbnails, clock), cancelWait);
    }

    /** Whether the imports are closing or closed. */
    public static boolean closing(Imports imports) {
        return imports.closing();
    }
}
