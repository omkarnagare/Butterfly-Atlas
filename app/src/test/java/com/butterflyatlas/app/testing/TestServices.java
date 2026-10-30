package com.butterflyatlas.app.testing;

import com.butterflyatlas.app.config.ConfigStore;
import com.butterflyatlas.app.backup.Backups;
import com.butterflyatlas.app.custom.CustomSpecies;
import com.butterflyatlas.app.exports.Exports;
import com.butterflyatlas.app.filters.FilterQuery;
import com.butterflyatlas.app.imaging.CommonsMetadataStripper;
import com.butterflyatlas.app.imaging.ImageIoPhotoProcessor;
import com.butterflyatlas.app.imports.FolderBrowser;
import com.butterflyatlas.app.imports.ImportPlaceChoices;
import com.butterflyatlas.app.imports.Imports;
import com.butterflyatlas.app.imports.TestImports;
import com.butterflyatlas.app.imaging.heif.TestDecoders;
import com.butterflyatlas.app.library.ChangeSteps;
import com.butterflyatlas.app.library.Library;
import com.butterflyatlas.app.library.LibraryChange;
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
import com.butterflyatlas.app.web.RunningWork;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.function.ToLongFunction;

/**
 * Real services over the fixture bundle and the fixture places ({@link TestPlaces}), with both fact consumers, with
 * config and data under a temporary home. The startup
 * work (rescan, catch-up, purge, the imports' and exports' startup checks) is not wired, so tests stay deterministic;
 * thumbnails use two daemon threads, imports one and exports one. The taxonomy updates look for a newer taxonomy when the
 * library opens.
 */
public final class TestServices {

    private TestServices() {
    }

    /** No library chosen yet. */
    public static AtlasServices unconfigured(Path home) {
        return unconfigured(home, new ImageIoPhotoProcessor());
    }

    /** No library chosen yet; photos are read and shrunk by {@code processor}. */
    public static AtlasServices unconfigured(Path home, PhotoProcessor processor) {
        return unconfigured(home, processor, null);
    }

    /** As {@link #unconfigured(Path, PhotoProcessor)}, the imports' cancels waiting {@code cancelWait} (null: the usual). */
    public static AtlasServices unconfigured(Path home, PhotoProcessor processor, Duration cancelWait) {
        return unconfigured(home, processor, cancelWait, new LibraryHolder());
    }

    /** As {@link #unconfigured(Path, PhotoProcessor, Duration)}, opening the library into {@code holder}. */
    public static AtlasServices unconfigured(Path home, PhotoProcessor processor, Duration cancelWait, LibraryHolder holder) {
        return unconfigured(home, processor, cancelWait, holder, TestBundles.fixture(), LibraryDrive::usableSpace,
                TestPlaces.bundles());
    }

    /**
     * As {@link #unconfigured(Path, PhotoProcessor, Duration, LibraryHolder)}, with these taxonomy bundles (a new library
     * gets the newest; the taxonomy updates look for those after a library's version when it opens).
     */
    private static AtlasServices unconfigured(Path home, PhotoProcessor processor, Duration cancelWait, LibraryHolder holder,
                                              TaxonomyBundles bundles, ToLongFunction<Path> usableSpace, PlaceBundles places) {
        SetupService setup = new SetupService(new ConfigStore(home.resolve("config")), holder, bundles,
                places, home, TestBundles.CLOCK);
        Thumbnails thumbnails = new Thumbnails(holder::library, processor, TestBundles.CLOCK);
        SearchService search = new SearchService(holder);
        int placeVersion = places.position(places.version());
        List<PhotoConsumer> facts = List.of(new Dimensions(), new Place(places.index(), placeVersion, TestBundles.CLOCK));
        PhotoStore photos = new PhotoStore(holder::library, processor, TestBundles.CLOCK, thumbnails::request, facts);
        Imports imports = cancelWait == null
                ? new Imports(holder::library, home, search, photos, processor, thumbnails, TestBundles.CLOCK)
                : TestImports.create(holder::library, home, search, photos, processor, thumbnails, TestBundles.CLOCK, cancelWait);
        PlaceSearch placeSearch = new PlaceSearch(() -> holder.library().database(), places);
        FilterQuery filters = new FilterQuery(() -> holder.library().database());
        TaxonService taxa = new TaxonService(holder);
        Exports exports = new Exports(holder::library, filters, new CommonsMetadataStripper(), TestBundles.CLOCK);
        Backups backups = new Backups(holder::library, TestBundles.CLOCK);
        Updates updates = new Updates(holder::library, bundles, imports, exports, backups, TestBundles.CLOCK);
        backups.importFiling(imports::filing);
        holder.whenOpened(library -> {
            library.photoHold().heldBy(backups::photoRefusal);
            library.openImports().filing(imports::filing); // a tag, place or species an import files with stays
        });
        ConsumerRunner processing = new ConsumerRunner(holder::library, new PhotoIngest(processor), facts, thumbnails,
                TestBundles.CLOCK);
        PlaceNotes placeNotes = new PlaceNotes(() -> holder.current().map(Library::database), placeVersion,
                ProcessingLedger.MAX_ATTEMPTS, TestBundles.CLOCK);
        PhotoPlaces photoPlaces = new PhotoPlaces(holder::library, TestBundles.CLOCK);
        photos.onChange(placeNotes::changed);
        photoPlaces.onChange(placeNotes::changed);
        processing.onPlacesChecked(placeNotes::changed);
        processing.onPlacesFinished(placeNotes::placesFinished);
        holder.whenOpened(library -> {
            updates.startup();
            Api.readStatus(photos, imports, exports, backups);
            placeNotes.opened();
        });
        PhotoQueries photoQueries = new PhotoQueries(holder::library, TestBundles.CLOCK);
        photos.onChange(updates::libraryChanged);
        CustomSpecies custom = new CustomSpecies(holder::library, taxa, TestBundles.CLOCK);
        custom.onChange(updates::libraryChanged);
        custom.onChange(photos::changedElsewhere); // a rename or delete moves photos: the library's version
        updates.whenFinished(photos::changedElsewhere); // as do an update's moves
        LibraryChange change = new LibraryChange(holder::library, setup, ChangeSteps.REAL, usableSpace,
                LibraryChange.Background.of(holder::library), setup::rememberChange,
                RunningWork.of(exports, backups, imports, updates, processing, () -> false));
        return new AtlasServices(setup, taxa, new SearchWordService(holder, TestBundles.CLOCK), search,
                photos, photoQueries, new TagService(holder::library), thumbnails,
                new Rescan(holder::library, processor, TestBundles.CLOCK, thumbnails::request, facts),
                processing,
                new FolderBrowser(holder::current, imports.store(), home), imports, placeSearch,
                new UserPlaces(() -> holder.library().database(), TestBundles.CLOCK, placeSearch,
                        new ImportPlaceChoices(holder::library)),
                photoPlaces, filters, exports, custom, updates,
                new SplitSorter(holder::library, photos, photoQueries, updates::libraryChanged),
                new LibraryDrive(() -> holder.current().map(Library::folder), usableSpace, System::nanoTime), placeNotes,
                backups, TestDecoders.none("mac", () -> holder.current().map(l -> l.folder().resolve(Thumbnails.FOLDER)
                        .resolve(".decode")).orElse(null)), change);
    }

    /** The same services with {@code change} as their library change (tests of a change's steps). */
    public static AtlasServices withChange(AtlasServices s, LibraryChange change) {
        return new AtlasServices(s.setup(), s.taxa(), s.searchWords(), s.search(), s.photos(), s.photoQueries(), s.tags(),
                s.thumbnails(), s.rescan(), s.processing(), s.folders(), s.imports(), s.placeSearch(), s.places(),
                s.photoPlaces(), s.filters(), s.exports(), s.custom(), s.updates(), s.splits(), s.drive(), s.placeNotes(),
                s.backups(), s.heic(), change);
    }

    /**
     * The library at {@code home/Atlas} opened with these taxonomy bundles (made with the newest when there is none yet):
     * for taxonomy updates, open an existing library with a newer chain.
     */
    public static AtlasServices configured(Path home, TaxonomyBundles bundles) {
        return configured(home, bundles, new LibraryHolder());
    }

    /** As {@link #configured(Path, TaxonomyBundles)}, held by {@code holder} (tests that watch its database). */
    public static AtlasServices configured(Path home, TaxonomyBundles bundles, LibraryHolder holder) {
        AtlasServices services = unconfigured(home, new ImageIoPhotoProcessor(), null, holder, bundles,
                LibraryDrive::usableSpace, TestPlaces.bundles());
        services.setup().setup(home.resolve("Atlas").toString());
        return services;
    }

    /**
     * The library at {@code home/Atlas} opened with these place lists (made with the newest when there is none yet): for
     * place updates, open an existing library with a newer chain.
     */
    public static AtlasServices configured(Path home, PlaceBundles places) {
        AtlasServices services = unconfigured(home, new ImageIoPhotoProcessor(), null, new LibraryHolder(),
                TestBundles.fixture(), LibraryDrive::usableSpace, places);
        services.setup().setup(home.resolve("Atlas").toString());
        return services;
    }

    /**
     * The fixture library open at {@code home/Atlas}, held by {@code holder}, its drive's free space as
     * {@code usableSpace} says (tests of a full drive).
     */
    public static AtlasServices configured(Path home, LibraryHolder holder, ToLongFunction<Path> usableSpace) {
        return configured(home, TestBundles.fixture(), holder, usableSpace);
    }

    /** As {@link #configured(Path, LibraryHolder, ToLongFunction)}, with these taxonomy bundles. */
    public static AtlasServices configured(Path home, TaxonomyBundles bundles, LibraryHolder holder,
                                           ToLongFunction<Path> usableSpace) {
        AtlasServices services = unconfigured(home, new ImageIoPhotoProcessor(), null, holder, bundles, usableSpace,
                TestPlaces.bundles());
        services.setup().setup(home.resolve("Atlas").toString());
        return services;
    }

    /**
     * The library {@code home} remembers, opened as the app does at its next start (setup would clear its unfinished
     * work: setup design §3). Its folder was set up by an earlier {@code configured(home…)}.
     */
    public static AtlasServices restarted(Path home) {
        AtlasServices services = unconfigured(home, new ImageIoPhotoProcessor(), null, new LibraryHolder());
        services.setup().startup(java.util.Optional.empty());
        if (!services.setup().status().configured()) {
            throw new IllegalStateException("The remembered library did not open: " + services.setup().status().problem());
        }
        return services;
    }

    /** The fixture library open at {@code home/Atlas}. */
    public static AtlasServices configured(Path home) {
        return configured(home, new ImageIoPhotoProcessor());
    }

    /** The fixture library open at {@code home/Atlas}, with this processor (an existing library there is reopened). */
    public static AtlasServices configured(Path home, PhotoProcessor processor) {
        return configured(home, processor, (Duration) null);
    }

    /** The fixture library open at {@code home/Atlas}; the imports' cancels wait {@code cancelWait} (null: the usual). */
    public static AtlasServices configured(Path home, PhotoProcessor processor, Duration cancelWait) {
        return configured(home, processor, cancelWait, new LibraryHolder());
    }

    /** The fixture library open at {@code home/Atlas}, held by {@code holder} (tests that watch its database). */
    public static AtlasServices configured(Path home, LibraryHolder holder) {
        return configured(home, new ImageIoPhotoProcessor(), null, holder);
    }

    /** The fixture library open at {@code home/Atlas} with this processor, held by {@code holder}. */
    public static AtlasServices configured(Path home, PhotoProcessor processor, LibraryHolder holder) {
        return configured(home, processor, null, holder);
    }

    private static AtlasServices configured(Path home, PhotoProcessor processor, Duration cancelWait, LibraryHolder holder) {
        AtlasServices services = unconfigured(home, processor, cancelWait, holder);
        services.setup().setup(home.resolve("Atlas").toString());
        return services;
    }
}
