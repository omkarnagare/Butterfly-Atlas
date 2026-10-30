package com.butterflyatlas.app.web;

import com.butterflyatlas.app.backup.Backups;
import com.butterflyatlas.app.exports.Exports;
import com.butterflyatlas.app.imports.Imports;
import com.butterflyatlas.app.library.LibraryChange;
import com.butterflyatlas.app.library.LibraryDrive;
import com.butterflyatlas.app.library.SetupService;
import com.butterflyatlas.app.photos.ConsumerRunner;
import com.butterflyatlas.app.photos.HeicStatus;
import com.butterflyatlas.app.photos.PhotoStore;
import com.butterflyatlas.app.photos.Rescan;
import com.butterflyatlas.app.photos.Thumbnails;
import com.butterflyatlas.app.placeupdates.PlaceNotes;
import com.butterflyatlas.app.updates.UpdateViews;
import com.butterflyatlas.app.updates.Updates;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.javalin.Javalin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * {@code GET /api/status}: background work for the UI's info note (the rescan's progress, or the
 * last run's summary; previews being made; photo details being updated, null when idle; the import working now, and
 * imports the app's closing stopped; the export running or paused; the taxonomy update pending, running, paused or done
 * in this run, with the split species that have photos to sort and the open custom-species matches) and the Recently
 * deleted footer count; the library's drive full or gone ({@code library}, null when fine: checked at most every 5
 * seconds, and at once when a request fails for it); a place update's note and its photos ({@code placeUpdate}, null
 * when there is nothing to show; 5c contract §6, §9); the backup running or paused ({@code backup}, null when none;
 * Phase 6 contract §5); whether this computer can show HEIC photos ({@code heic}, Phase 7b contract §3: held in memory)
 * and the files the last rescan did not add, by reason ({@code rescanLeft}, audit D2; empty when none or put away with
 * {@code POST /api/rescan-left/dismiss}); the library's version ({@code libraryVersion}, multi-window contract §10 A8: up
 * with every write that changes photos, so another window reloads them); and the saved look ({@code theme}, Meadow UI
 * contract §4: {@code device}, {@code light} or {@code dark}, from memory, so another window follows a change); and the change of
 * library folder running or waiting to be dismissed ({@code changing}, change-library-folder contract §2, null when
 * none) and, on the first run after a move, where the library is now ({@code libraryMoved}, until dismissed). A poll runs no SQL (design D9).
 */
final class StatusRoutes {

    record Status(boolean rescanning, Rescan.Progress rescan, Thumbnails.Progress previews, int deletedCount,
                  ConsumerRunner.Progress processing, @JsonProperty("import") Imports.Working importing,
                  List<Imports.Working> stoppedImports, Exports.Status export, UpdateViews.Status taxonomyUpdate,
                  int splitsToSort, int customMatches, LibraryDrive.Problem library, PlaceNotes.Status placeUpdate,
                  Backups.Status backup, HeicStatus heic, List<Rescan.Left> rescanLeft, long libraryVersion, String theme,
                  LibraryChange.Changing changing, SetupService.Moved libraryMoved) {
    }

    private static final Logger LOG = LoggerFactory.getLogger(StatusRoutes.class);

    private final Rescan rescan;
    private final Thumbnails thumbnails;
    private final PhotoStore photos;
    private final ConsumerRunner processing;
    private final Imports imports;
    private final Exports exports;
    private final Updates updates;
    private final LibraryDrive drive;
    private final PlaceNotes placeNotes;
    private final Backups backups;
    private final Supplier<String> theme;
    private final Supplier<LibraryChange.Changing> changing;
    private final Supplier<SetupService.Moved> libraryMoved;
    /** The last known Recently deleted count, imports and export: answered while what is in memory is being read. */
    private volatile int lastDeleted;
    private volatile Imports.StatusPart lastImports = new Imports.StatusPart(null, List.of());
    private volatile Exports.StatusPart lastExport = new Exports.StatusPart(null);
    private volatile Backups.StatusPart lastBackup = new Backups.StatusPart(null);
    private final AtomicBoolean filling = new AtomicBoolean();
    /** Guards starting a read against {@link #close}. */
    private final Object reading = new Object();
    private boolean closed;
    private Thread reader;

    StatusRoutes(Rescan rescan, Thumbnails thumbnails, PhotoStore photos, ConsumerRunner processing, Imports imports,
                 Exports exports, Updates updates, LibraryDrive drive, PlaceNotes placeNotes) {
        this(rescan, thumbnails, photos, processing, imports, exports, updates, drive, placeNotes, null);
    }

    StatusRoutes(Rescan rescan, Thumbnails thumbnails, PhotoStore photos, ConsumerRunner processing, Imports imports,
                 Exports exports, Updates updates, LibraryDrive drive, PlaceNotes placeNotes, Backups backups) {
        this(rescan, thumbnails, photos, processing, imports, exports, updates, drive, placeNotes, backups, () -> null);
    }

    StatusRoutes(Rescan rescan, Thumbnails thumbnails, PhotoStore photos, ConsumerRunner processing, Imports imports,
                 Exports exports, Updates updates, LibraryDrive drive, PlaceNotes placeNotes, Backups backups,
                 Supplier<String> theme) {
        this(rescan, thumbnails, photos, processing, imports, exports, updates, drive, placeNotes, backups, theme, () -> null,
                () -> null);
    }

    /**
     * As above, with the library change running or waiting to be dismissed ({@code changing}: null when none) and the
     * note after a move ({@code libraryMoved}: null when none).
     */
    StatusRoutes(Rescan rescan, Thumbnails thumbnails, PhotoStore photos, ConsumerRunner processing, Imports imports,
                 Exports exports, Updates updates, LibraryDrive drive, PlaceNotes placeNotes, Backups backups,
                 Supplier<String> theme, Supplier<LibraryChange.Changing> changing,
                 Supplier<SetupService.Moved> libraryMoved) {
        this.changing = changing;
        this.libraryMoved = libraryMoved;
        this.theme = theme;
        this.backups = backups;
        this.rescan = rescan;
        this.thumbnails = thumbnails;
        this.photos = photos;
        this.processing = processing;
        this.imports = imports;
        this.exports = exports;
        this.updates = updates;
        this.drive = drive;
        this.placeNotes = placeNotes;
    }

    void register(Javalin app) {
        app.get("/api/status", ctx -> ctx.json(status()));
        app.post("/api/rescan-left/dismiss", ctx -> {
            rescan.dismissLeft();
            ctx.status(204);
        });
    }

    /**
     * The status from memory: Recently deleted, imports, the export and the taxonomy update are kept current by the code
     * that changes them, and read when the library opens. A poll never reads the database (5b contract §7: on a drive
     * filled to the last byte every read fails, and the banner depends on this answer): what is not in memory yet is
     * answered from the last known value and read on a thread of its own.
     */
    Status status() {
        Integer deleted = photos.deletedCountKept();
        Optional<Imports.StatusPart> importing = imports.statusKept();
        Optional<Exports.StatusPart> exporting = exports.statusKept();
        Optional<Backups.StatusPart> backingUp = backups == null ? Optional.of(lastBackup) : backups.statusKept();
        if (deleted == null || importing.isEmpty() || exporting.isEmpty() || backingUp.isEmpty()) {
            fill();
        }
        if (deleted != null) {
            lastDeleted = deleted;
        }
        importing.ifPresent(part -> lastImports = part);
        exporting.ifPresent(part -> lastExport = part);
        backingUp.ifPresent(part -> lastBackup = part);
        Imports.StatusPart i = lastImports;
        return new Status(rescan.running(), rescan.progress(), thumbnails.progress(), lastDeleted, processing.progress(),
                i.working(), i.stopped(), lastExport.export(), updates.status().orElse(null), updates.splitsToSort(),
                updates.customMatches(), drive.problem(), placeNotes.status(), lastBackup.backup(), HeicStatus.current(),
                rescan.left(), photos.libraryVersion(), theme.get(), changing.get(), libraryMoved.get());
    }

    /**
     * No read starts from now on, and one under way is waited for (at most {@link #CLOSE_WAIT}), so nothing reads the
     * library once the app (or a test) has closed.
     */
    void close() {
        Thread running;
        synchronized (reading) {
            closed = true;
            running = reader;
        }
        if (running != null) {
            try {
                running.join(CLOSE_WAIT.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** The longest {@link #close} waits for a read under way. */
    static final java.time.Duration CLOSE_WAIT = java.time.Duration.ofSeconds(10);

    /** Reads what the status keeps in memory, on a thread of its own (one at a time; none once closed). */
    private void fill() {
        synchronized (reading) {
            if (closed || !filling.compareAndSet(false, true)) {
                return;
            }
            Thread thread = new Thread(() -> {
                try {
                    read();
                } catch (RuntimeException e) {
                    LOG.debug("Could not read the status's counts", e);
                } finally {
                    filling.set(false);
                }
            }, "atlas-status");
            thread.setDaemon(true);
            reader = thread;
            thread.start();
        }
    }

    /**
     * Reads Recently deleted's count, the imports, the export and the backup into memory (when the library opens, so a poll finds
     * them there).
     */
    static void read(PhotoStore photos, Imports imports, Exports exports, Backups backups) {
        photos.deletedCount();
        imports.stopped();
        exports.status();
        if (backups != null) {
            backups.status();
        }
    }

    private void read() {
        read(photos, imports, exports, backups);
    }
}
