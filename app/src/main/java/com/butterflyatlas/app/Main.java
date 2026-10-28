package com.butterflyatlas.app;

import com.butterflyatlas.app.backup.Backups;
import com.butterflyatlas.app.config.AppDirs;
import com.butterflyatlas.app.config.ConfigStore;
import com.butterflyatlas.app.custom.CustomSpecies;
import com.butterflyatlas.app.exports.Exports;
import com.butterflyatlas.app.filters.FilterQuery;
import com.butterflyatlas.app.imaging.CommonsMetadataStripper;
import com.butterflyatlas.app.imaging.ImageIoPhotoProcessor;
import com.butterflyatlas.app.imaging.heif.HeicDecoders;
import com.butterflyatlas.app.imports.FolderBrowser;
import com.butterflyatlas.app.imports.ImportPlaceChoices;
import com.butterflyatlas.app.imports.Imports;
import com.butterflyatlas.app.library.Library;
import com.butterflyatlas.app.library.LibraryDrive;
import com.butterflyatlas.app.library.LibraryHolder;
import com.butterflyatlas.app.library.SetupService;
import com.butterflyatlas.app.photos.ConsumerRunner;
import com.butterflyatlas.app.photos.PhotoConsumer;
import com.butterflyatlas.app.photos.PhotoIngest;
import com.butterflyatlas.app.photos.PhotoPlaces;
import com.butterflyatlas.app.photos.PhotoProcessor;
import com.butterflyatlas.app.photos.PhotoQueries;
import com.butterflyatlas.app.photos.PhotoStore;
import com.butterflyatlas.app.photos.ProcessingLedger;
import com.butterflyatlas.app.photos.Rescan;
import com.butterflyatlas.app.photos.StartupWork;
import com.butterflyatlas.app.photos.TagService;
import com.butterflyatlas.app.photos.Thumbnails;
import com.butterflyatlas.app.photos.consumers.Dimensions;
import com.butterflyatlas.app.photos.consumers.Place;
import com.butterflyatlas.app.placeupdates.PlaceNotes;
import com.butterflyatlas.app.places.PlaceBundles;
import com.butterflyatlas.app.places.PlaceSearch;
import com.butterflyatlas.app.places.UserPlaces;
import com.butterflyatlas.app.search.SearchService;
import com.butterflyatlas.app.taxa.SearchWordService;
import com.butterflyatlas.app.taxa.TaxonService;
import com.butterflyatlas.app.taxonomy.TaxonomyBundles;
import com.butterflyatlas.app.updates.SplitSorter;
import com.butterflyatlas.app.updates.Updates;
import com.butterflyatlas.app.web.Api;
import com.butterflyatlas.app.web.AtlasServices;
import com.butterflyatlas.app.window.AtlasLog;
import com.butterflyatlas.app.window.InstanceLock;
import com.butterflyatlas.app.window.StartupFailure;

import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.function.Function;

/**
 * Starts Butterfly Atlas ({@link AppStart} does the work): the UI opens in its own app window (Edge or Chrome in app mode),
 * or in a tab of the default browser when none is found.
 *
 * <p>Options: {@code -Datlas.port=7878} (or env {@code ATLAS_PORT}) fixes the port, useful for
 * UI development (without it the fixed port {@value #DEFAULT_PORT} is tried first, then any free one);
 * {@code -Datlas.noBrowser=true} skips opening any window (and the app then never quits by itself until a window has
 * connected); {@code -Datlas.browser=<program>} (or env {@code ATLAS_BROWSER}) names the browser for the app window;
 * {@code -Datlas.quitSeconds=<n>} is how many seconds with no window open before the app quits (default 10);
 * {@code -Datlas.startSeconds=<n>} is how long a window has to connect after start before the default browser is tried
 * (default 60; the app quits after as long again);
 * {@code -Datlas.dataDir=<folder>} opens that library and skips the first-run choice;
 * {@code -Datlas.configDir=<folder>} keeps settings (and the log, the pointer file and the browser profile) somewhere other
 * than the usual place;
 * {@code -Datlas.taxonomyDir=<folder>} (developer only, to try a taxonomy update) reads the taxonomy bundles and their
 * {@code bundles.sha256} from that folder instead of the ones inside the app, with the same checks (a folder made by the
 * test-scope {@code testing.UpdateBundles} main holds the shipped bundle and a v2 derived from it);
 * {@code -Datlas.placesDir=<folder>} (developer only, to try a place update) reads the place lists, their boundaries and
 * {@code places.sha256} from that folder instead of the ones inside the app, with the same checks (a folder made by the
 * test-scope {@code testing.UpdatePlaces} main holds the shipped lists and a next one derived from the newest).
 */
public final class Main {

    /** The port tried first when none is named (an uncommon one, so the browser's saved view survives launches). */
    static final int DEFAULT_PORT = 47821;

    private Main() {
    }

    public static void main(String[] args) {
        // AWT is only used to open the default browser: keep it from showing a Java icon in the Dock.
        System.setProperty("apple.awt.UIElement", "true");

        Path config = AppDirs.currentConfigDir();
        try {
            AppDirs.createConfigDir(config);
        } catch (RuntimeException e) {
            System.out.println("Butterfly Atlas could not prepare its settings folder " + config + ": " + e.getMessage());
        }
        AtlasLog log = AtlasLog.open(config);
        log.captureStdErr(); // what the libraries print goes to the log too (nobody sees the console of an installed app)
        AppOptions options;
        try {
            options = AppOptions.fromSystem();
        } catch (IllegalArgumentException e) {
            AppStart.failBeforeStart(log, config, StartupFailure.badSetting(e.getMessage()), AppStart.World.system());
            return;
        }
        new AppStart(options, log, AppStart.World.system()).run();
    }

    /**
     * A normal quit: no more requests; then a taxonomy update's moves stop after the current file, which is recorded (the
     * update stays running, so the next start continues it), waiting at most {@code Updates.CLOSE_WAIT}; then the imports
     * close, waiting (bounded) for the step running now, which keeps
     * its record so the next start reports the import as stopped (they need the library and the thumbnails, which a
     * filing batch adopts previews into); then the export's copy closes, waiting at most {@code ExportJobs.CLOSE_WAIT}
     * for the photo it is copying, whose progress is saved (the export stays running, so the next start pauses it as
     * closing); then the thumbnails stop. The library's database stays open for the JVM's exit.
     */
    static void shutdown(AtlasServer server, AtlasServices services) {
        // A taxonomy update's moves, import work and the export's copy are asked to stop first, so a long walk or copy
        // does not hold the quit up; they are waited for (bounded) once the server is closed, while the library is still
        // there to record them.
        beginClose(services);
        try {
            server.close();
        } finally {
            closeServices(services);
        }
    }

    private static void beginClose(AtlasServices services) {
        services.updates().beginClose();
        services.imports().beginClose();
        services.exports().beginClose();
        services.backups().beginClose();
    }

    /** What a quit closes once the server has stopped, in order; also when the server never started. */
    static void closeServices(AtlasServices services) {
        beginClose(services); // again, harmless: when the server never started nothing asked yet
        try {
            services.updates().close();
        } finally {
            try {
                services.imports().close();
            } finally {
                try {
                    services.exports().close();
                } finally {
                    try {
                        services.backups().close();
                    } finally {
                        try {
                            services.thumbnails().close();
                        } finally {
                            services.placeNotes().close(); // its count under way ends before the library goes
                        }
                    }
                }
            }
        }
    }

    /**
     * Everything the HTTP layer calls, with the library opened (taking its lock with {@code locks}) when the settings name
     * one or a remembered one exists.
     */
    static AtlasServices services(AppOptions options, Function<Path, InstanceLock.Result> locks) {
        Clock clock = Clock.systemUTC();
        TaxonomyBundles bundles = options.taxonomyDir().map(TaxonomyBundles::fromDirectory)
                .orElseGet(TaxonomyBundles::fromClasspath);
        PlaceBundles places = options.placesDir().map(PlaceBundles::fromDirectory).orElseGet(PlaceBundles::fromClasspath);
        LibraryHolder holder = new LibraryHolder();
        // The computer's HEIC decoder (Phase 7b): its working folder is the open library's .thumbs/.decode/.
        HeicDecoders heic = new HeicDecoders(() -> holder.current()
                .map(library -> library.folder().resolve(Thumbnails.FOLDER).resolve(".decode")).orElse(null));
        PhotoProcessor processor = new ImageIoPhotoProcessor(heic);
        Thumbnails thumbnails = new Thumbnails(holder::library, processor, clock);
        // Once HEIC photos can be shown (at start or after Check again), their missing thumbnails are made.

        // The fact consumers: caught up at startup, and applied inline to every photo added, filed or edited.
        // The place consumer's version is the newest place list's position: after a place update every photo is due again.
        List<PhotoConsumer> facts = List.of(new Dimensions(), new Place(places.index(), places.position(places.version()),
                clock));
        PhotoStore photos = new PhotoStore(holder::library, processor, clock, thumbnails::request, facts);
        Rescan rescan = new Rescan(holder::library, processor, clock, thumbnails::request, facts);
        ConsumerRunner processing = new ConsumerRunner(holder::library, new PhotoIngest(processor), facts, thumbnails, clock);
        Path home = Path.of(System.getProperty("user.home"));
        SearchService search = new SearchService(holder);
        Imports imports = new Imports(holder::library, home, search, photos, processor, thumbnails, clock);
        // Once HEIC photos can be shown (at start or after Check again): their missing thumbnails are made, and open
        // imports' HEIC previews.
        heic.onChange(status -> {
            if (status.isAvailable() && holder.current().isPresent()) {
                thumbnails.heicAvailable();
                imports.heicAvailable();
            }
        });
        FilterQuery filters = new FilterQuery(() -> holder.library().database());
        Exports exports = new Exports(holder::library, filters, new CommonsMetadataStripper(), clock);
        Backups backups = new Backups(holder::library, clock);
        Updates updates = new Updates(holder::library, bundles, imports, exports, backups, clock);
        // a backup of Everything holds the photos while it copies them; it neither starts nor resumes while an import files
        backups.importFiling(imports::filing);
        holder.whenOpened(library -> {
            library.photoHold().heldBy(backups::photoRefusal);
            library.openImports().filing(imports::filing); // a tag, place or species an import files with stays
        });
        PhotoQueries photoQueries = new PhotoQueries(holder::library, clock);
        SplitSorter splits = new SplitSorter(holder::library, photos, photoQueries, updates::libraryChanged);
        photos.onChange(updates::libraryChanged); // the pending summary and the counts follow the photos
        updates.whenFinished(photos::purgeSkipped); // the startup's purge, skipped while an update held the gate
        Runnable filesSettled = () -> {
            updates.startupFilesSettled();
            updates.libraryChanged(); // after the rescan: a photo it dropped may have been one to sort, or to move
            imports.startupFilesSettled();
            exports.startupFilesSettled();
            backups.startupFilesSettled();
        };
        StartupWork startupWork = new StartupWork(rescan, photos, processing, thumbnails, updates::awaitContinuation,
                filesSettled);
        // At launch and after first-run setup alike: a taxonomy update the app's closing cut short continues at once
        // (changes that move photos wait for it), else a newer taxonomy is looked for; imports and an export cut short by
        // the app closing are marked stopped (paused) before anything can work on them (and previews of closed imports
        // go), and filing and copying wait; then, in the background, clear crashed uploads, wait for the taxonomy update,
        // rescan, catch up photo details, purge Recently deleted, sweep its leftovers (filing and copying may start from
        // here), tidy thumbnails.
        // A place update's Home note: its status part read when a library opens, and again as photos change place.
        PlaceNotes placeNotes = new PlaceNotes(() -> holder.current().map(Library::database), places.position(places.version()),
                ProcessingLedger.MAX_ATTEMPTS, clock);
        PhotoPlaces photoPlaces = new PhotoPlaces(holder::library, clock);
        photos.onChange(placeNotes::changed);
        photoPlaces.onChange(placeNotes::changed);
        processing.onPlacesChecked(placeNotes::changed);
        processing.onPlacesFinished(placeNotes::placesFinished);
        holder.whenOpened(library -> placeNoteFirst(placeNotes, () -> {
            heic.start(); // its working folder emptied, the decoder checked in the background
            opened(updates, imports, exports, backups, startupWork::start, filesSettled);
            Api.readStatus(photos, imports, exports, backups); // a status poll never reads the database
        }));
        SetupService setup = new SetupService(new ConfigStore(options.configDir()), holder, bundles, places, home, clock,
                locks);
        setup.startup(options.dataDir());
        PlaceSearch placeSearch = new PlaceSearch(() -> holder.library().database(), places);
        TaxonService taxa = new TaxonService(holder);
        CustomSpecies custom = new CustomSpecies(holder::library, taxa, clock);
        custom.onChange(updates::libraryChanged);
        custom.onChange(photos::changedElsewhere); // a rename or delete moves photos: the library's version
        updates.whenFinished(photos::changedElsewhere); // as do an update's moves
        return new AtlasServices(setup, taxa, new SearchWordService(holder, clock),
                search, photos, photoQueries, new TagService(holder::library),
                thumbnails, rescan, processing, new FolderBrowser(holder::current, imports.store(), home), imports,
                placeSearch, new UserPlaces(() -> holder.library().database(), clock, placeSearch,
                        new ImportPlaceChoices(holder::library)),
                photoPlaces, filters, exports, custom, updates, splits, new LibraryDrive(holder), placeNotes, backups, heic);
    }

    /**
     * When a library opens: the taxonomy update, the imports, the export and the backup checked, filing and copying held, then the
     * startup work. If any of it fails, {@code filesSettled} runs (no startup work will run it): updates, filing and
     * copying must not wait for ever.
     */
    static void opened(Updates updates, Imports imports, Exports exports, Runnable startWork, Runnable filesSettled) {
        opened(updates, imports, exports, null, startWork, filesSettled);
    }

    /** As {@link #opened(Updates, Imports, Exports, Runnable, Runnable)}, with a backup the closing cut short paused too. */
    static void opened(Updates updates, Imports imports, Exports exports, Backups backups, Runnable startWork,
                       Runnable filesSettled) {
        updates.holdForStartup(); // an Apply, Retry or Merge waits for the rescan and the other photo-file steps
        try {
            updates.startup();
            imports.startup();
            exports.startup();
            if (backups != null) {
                backups.startup();
            }
            imports.holdForStartup();
            exports.holdForStartup();
            if (backups != null) {
                backups.holdForStartup();
            }
            startWork.run();
        } catch (RuntimeException | Error e) {
            filesSettled.run();
            throw e;
        }
    }

    /** The place update note's status part into memory; if it cannot be read now, it is read on a thread of its own. */
    /**
     * Reads the place note's status part, then runs the rest of the opening: the startup work it starts may finish its
     * places step at once, and the note must hear that after it was read, not be reset by the read.
     */
    static void placeNoteFirst(PlaceNotes placeNotes, Runnable opening) {
        readPlaceNote(placeNotes);
        opening.run();
    }

    static void readPlaceNote(PlaceNotes placeNotes) {
        try {
            placeNotes.opened();
        } catch (RuntimeException e) {
            placeNotes.changed();
        }
    }

    static int parsePort(String source, String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            int port = Integer.parseInt(value.trim());
            if (port >= 0 && port <= 65535) {
                return port;
            }
        } catch (NumberFormatException e) {
            // fall through to the shared message below
        }
        throw new IllegalArgumentException(
                source + " must be a whole number between 0 and 65535, got: " + value);
    }
}
