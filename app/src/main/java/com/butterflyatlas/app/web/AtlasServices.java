package com.butterflyatlas.app.web;

import com.butterflyatlas.app.backup.Backups;
import com.butterflyatlas.app.custom.CustomSpecies;
import com.butterflyatlas.app.exports.Exports;
import com.butterflyatlas.app.filters.FilterQuery;
import com.butterflyatlas.app.imaging.heif.HeicDecoders;
import com.butterflyatlas.app.imports.FolderBrowser;
import com.butterflyatlas.app.imports.Imports;
import com.butterflyatlas.app.library.LibraryChange;
import com.butterflyatlas.app.library.LibraryDrive;
import com.butterflyatlas.app.library.SetupService;
import com.butterflyatlas.app.photos.ConsumerRunner;
import com.butterflyatlas.app.photos.PhotoPlaces;
import com.butterflyatlas.app.photos.PhotoQueries;
import com.butterflyatlas.app.photos.PhotoStore;
import com.butterflyatlas.app.photos.Rescan;
import com.butterflyatlas.app.photos.SharpCopies;
import com.butterflyatlas.app.photos.TagService;
import com.butterflyatlas.app.photos.Thumbnails;
import com.butterflyatlas.app.placeupdates.PlaceNotes;
import com.butterflyatlas.app.places.PlaceSearch;
import com.butterflyatlas.app.places.UserPlaces;
import com.butterflyatlas.app.search.SearchService;
import com.butterflyatlas.app.library.sync.LibrarySync;
import com.butterflyatlas.app.taxa.SearchWordService;
import com.butterflyatlas.app.taxa.TaxonService;
import com.butterflyatlas.app.updates.SplitSorter;
import com.butterflyatlas.app.updates.Updates;

/**
 * Everything the HTTP layer calls. Built once in {@code Main}; tests build their own. {@code sync} is one device at a
 * time (null in tests whose libraries open in place); {@code sharp} the sharp copies kept on this device (null: none are
 * made, and the route answers that there is none).
 */
public record AtlasServices(SetupService setup, TaxonService taxa, SearchWordService searchWords, SearchService search,
                            PhotoStore photos, PhotoQueries photoQueries, TagService tags, Thumbnails thumbnails,
                            Rescan rescan, ConsumerRunner processing, FolderBrowser folders,
                            Imports imports, PlaceSearch placeSearch, UserPlaces places, PhotoPlaces photoPlaces,
                            FilterQuery filters, Exports exports, CustomSpecies custom, Updates updates,
                            SplitSorter splits, LibraryDrive drive, PlaceNotes placeNotes, Backups backups,
                            HeicDecoders heic, LibraryChange change, LibrarySync sync, SharpCopies sharp) {

    /** Without sharp copies. */
    public AtlasServices(SetupService setup, TaxonService taxa, SearchWordService searchWords, SearchService search,
                         PhotoStore photos, PhotoQueries photoQueries, TagService tags, Thumbnails thumbnails,
                         Rescan rescan, ConsumerRunner processing, FolderBrowser folders,
                         Imports imports, PlaceSearch placeSearch, UserPlaces places, PhotoPlaces photoPlaces,
                         FilterQuery filters, Exports exports, CustomSpecies custom, Updates updates,
                         SplitSorter splits, LibraryDrive drive, PlaceNotes placeNotes, Backups backups,
                         HeicDecoders heic, LibraryChange change, LibrarySync sync) {
        this(setup, taxa, searchWords, search, photos, photoQueries, tags, thumbnails, rescan, processing, folders, imports,
                placeSearch, places, photoPlaces, filters, exports, custom, updates, splits, drive, placeNotes, backups, heic,
                change, sync, null);
    }

    /** Without one device at a time: libraries open in place (tests of other features). */
    public AtlasServices(SetupService setup, TaxonService taxa, SearchWordService searchWords, SearchService search,
                         PhotoStore photos, PhotoQueries photoQueries, TagService tags, Thumbnails thumbnails,
                         Rescan rescan, ConsumerRunner processing, FolderBrowser folders,
                         Imports imports, PlaceSearch placeSearch, UserPlaces places, PhotoPlaces photoPlaces,
                         FilterQuery filters, Exports exports, CustomSpecies custom, Updates updates,
                         SplitSorter splits, LibraryDrive drive, PlaceNotes placeNotes, Backups backups,
                         HeicDecoders heic, LibraryChange change) {
        this(setup, taxa, searchWords, search, photos, photoQueries, tags, thumbnails, rescan, processing, folders, imports,
                placeSearch, places, photoPlaces, filters, exports, custom, updates, splits, drive, placeNotes, backups, heic,
                change, null, null);
    }
}
