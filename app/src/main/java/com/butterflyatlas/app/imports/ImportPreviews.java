package com.butterflyatlas.app.imports;

import com.butterflyatlas.app.AtlasException;
import com.butterflyatlas.app.library.Library;
import com.butterflyatlas.app.photos.HeicDecodeException;
import com.butterflyatlas.app.photos.HeicStatus;
import com.butterflyatlas.app.photos.PhotoFormat;
import com.butterflyatlas.app.photos.RawDisplays;
import com.butterflyatlas.app.photos.PhotoProcessor;
import com.butterflyatlas.app.photos.Thumbnails;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * An import's previews and large views (design §4.3): the 480 px preview of an item, the large view (the source file,
 * or a JPEG copy of a TIFF), and removing an import's previews.
 */
final class ImportPreviews {

    private static final Logger LOG = LoggerFactory.getLogger(ImportPreviews.class);

    private final Supplier<Library> library;
    private final ImportStore store;
    private final PhotoProcessor processor;
    /** Whether cloud placeholders ("other" files) count as files, as on Windows ({@link Imports}' flag; tests set it). */
    private final BooleanSupplier windows;
    private final Semaphore displaySlots = new Semaphore(2);
    private final ImportHeicPreviews heicPreviews;

    ImportPreviews(Supplier<Library> library, ImportStore store, PhotoProcessor processor, BooleanSupplier windows,
                   ImportHeicPreviews heicPreviews) {
        this.library = library;
        this.store = store;
        this.processor = processor;
        this.windows = windows;
        this.heicPreviews = heicPreviews;
    }

    /**
     * The 480 px preview of an item of this import; for one filed (or found in the library), whose preview filing moved
     * into the library or which the finish removed, the library photo to show instead.
     */
    Imports.Preview preview(long id, long itemId) {
        ImportStore.ItemFile item = store.itemFile(id, itemId);
        if (ImportStore.PENDING.equals(item.state())) {
            return new Imports.Preview(null, true);
        }
        if (ImportStore.READ.equals(item.state()) && item.v() != null) {
            Path file = ImportStore.previewFile(library.get().folder(), id, itemId, item.v());
            if (Files.exists(file)) {
                return new Imports.Preview(file, false);
            }
            if (item.imageId() == null && item.previewFailed() == ImportReader.PREVIEW_TIMED_OUT) {
                heicPreviews.retryOnce(id, itemId); // took too long when read: once more, now that it is on screen
                return new Imports.Preview(null, true);
            }
            if (item.imageId() == null && PhotoFormat.HEIC.name().equals(item.format())
                    && HeicStatus.current().isChecking()) {
                return new Imports.Preview(null, true); // made once the check is done
            }
        }
        return new Imports.Preview(null, false, item.imageId());
    }

    /**
     * The large view of an item of this import that has been read: the source file for JPEG, PNG and WebP, a RAW's
     * preview read from inside it, and a 2560 px JPEG copy for TIFF (made on first request, kept with the previews; for a finished import, made each time
     * and handed over in memory).
     */
    Imports.Display display(long id, long itemId) {
        ImportStore.Run run = store.run(id);
        ImportStore.ItemFile item = store.itemFile(id, itemId);
        Path source = ImportStore.resolve(run.root(), item.relPath());
        if (!ImportStore.READ.equals(item.state()) || item.format() == null || !isFile(source)) {
            throw AtlasException.notFound(AtlasException.CANT_SHOW_PHOTO);
        }
        PhotoFormat format = PhotoFormat.valueOf(item.format());
        if (format == PhotoFormat.RAW) {
            // Its preview, read from inside the source (the page turns it by the item's rawPreview.orientation).
            try {
                return new Imports.Display(null, PhotoFormat.JPEG.contentType(), null,
                        RawDisplays.of(source, item.rawPreview(), item.size(), item.modifiedAt()));
            } catch (IOException e) {
                LOG.info("Could not read the preview of {}: {}", source, e.toString());
                throw AtlasException.unsupported(PhotoFormat.RAW_PREVIEW_UNREADABLE);
            }
        }
        if (format == PhotoFormat.HEIC) {
            HeicStatus.requireShown(); // 503 while the decoder is checked, 415 with the install note while it is missing
        } else if (format != PhotoFormat.TIFF) {
            return new Imports.Display(source, format.contentType());
        }
        if (!ImportStore.OPEN.equals(run.state())) {
            // A finished import's previews are gone: the copy is made aside, handed over in memory, and not kept.
            Path aside = library.get().folder().resolve(Library.INCOMING).resolve(UUID.randomUUID() + "-display.jpg");
            try {
                makeDisplay(source, item.orientation(), aside, format);
                return new Imports.Display(null, PhotoFormat.JPEG.contentType(), Files.readAllBytes(aside));
            } catch (IOException e) {
                LOG.info("Could not read the large view of {}: {}", source, e.toString());
                throw AtlasException.unsupported(unreadable(format));
            } finally {
                ImportReader.deleteQuietly(aside);
            }
        }
        Path copy = ImportStore.previewFolder(library.get().folder(), id)
                .resolve(itemId + "-" + item.v() + "-" + Thumbnails.DISPLAY_SIZE + ".jpg");
        if (!Files.exists(copy)) {
            makeDisplay(source, item.orientation(), copy, format);
        }
        return new Imports.Display(copy, PhotoFormat.JPEG.contentType());
    }

    /**
     * Whether the source is a file, looked at without following links, as the walk, the read and filing do: a regular
     * file, or on Windows a cloud placeholder (OneDrive's "other" files), which the route opens with a plain stream so
     * that the cloud client fetches it.
     */
    private boolean isFile(Path source) {
        try {
            return ImportNames.isFile(Files.readAttributes(source, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS),
                    windows.getAsBoolean());
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /** The words for a large view that cannot be made: a HEIC's decode note (Phase 7b contract §1), else the usual. */
    private static String unreadable(PhotoFormat format) {
        return format == PhotoFormat.HEIC ? HeicStatus.DECODE_FAILED : PhotoFormat.UNREADABLE;
    }

    private void makeDisplay(Path source, int orientation, Path copy, PhotoFormat format) {
        try {
            displaySlots.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw AtlasException.busy(AtlasException.PHOTO_TOO_SLOW);
        }
        Path part = copy.resolveSibling(copy.getFileName() + "." + Thread.currentThread().getId() + Library.PART);
        try {
            Files.createDirectories(copy.getParent());
            processor.thumbnail(source, orientation, Thumbnails.DISPLAY_SIZE, part, PhotoProcessor.Use.VIEW);
            try {
                Files.move(part, copy, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(part, copy, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (HeicDecodeException e) {
            LOG.info("No large view of {} now: {}", source, e.getMessage());
            if (e.kind() == HeicDecodeException.Kind.UNAVAILABLE) {
                HeicStatus.requireShown();
            }
            if (e.kind() == HeicDecodeException.Kind.NO_WORKSPACE) {
                throw HeicStatus.workspaceProblem();
            }
            throw AtlasException.busy(AtlasException.PHOTO_TOO_SLOW);
        } catch (AtlasException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            LOG.info("Could not make a large view of {}: {}", source, e.toString());
            throw AtlasException.unsupported(unreadable(format));
        } finally {
            ImportReader.deleteQuietly(part);
            displaySlots.release();
        }
    }

    /** Removes the preview folders of imports that are no longer open (at startup); returns how many went. */
    int tidy() {
        Path folder = library.get().folder().resolve(Thumbnails.FOLDER).resolve(ImportStore.PREVIEWS);
        if (!Files.isDirectory(folder)) {
            return 0;
        }
        Set<String> open = new HashSet<>();
        for (ImportStore.Run run : store.runs()) {
            if (ImportStore.OPEN.equals(run.state())) {
                open.add(Long.toString(run.id()));
            }
        }
        int removed = 0;
        try (DirectoryStream<Path> runs = Files.newDirectoryStream(folder)) {
            for (Path run : runs) {
                if (!open.contains(run.getFileName().toString()) && deleteTree(run)) {
                    removed++;
                }
            }
        } catch (IOException | DirectoryIteratorException e) {
            LOG.warn("Could not tidy import previews in {}", folder, e);
        }
        return removed;
    }

    /** Removes the import's previews (and large views kept with them). */
    void deletePreviews(long id) {
        deleteTree(ImportStore.previewFolder(library.get().folder(), id));
    }

    /** Removes a preview folder and what is in it; never follows a link out of it. */
    static boolean deleteTree(Path folder) {
        if (!Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        try (Stream<Path> files = Files.walk(folder)) {
            List<Path> all = new ArrayList<>(files.toList());
            all.sort(Comparator.reverseOrder()); // children before their folder
            for (Path file : all) {
                Files.deleteIfExists(file);
            }
            return true;
        } catch (IOException | RuntimeException e) {
            LOG.warn("Could not remove the import previews in {}", folder, e);
            return false;
        }
    }
}
