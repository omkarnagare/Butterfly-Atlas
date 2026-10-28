package com.butterflyatlas.app.library;

import com.butterflyatlas.app.db.Database;
import com.butterflyatlas.app.placeupdates.PlaceChain;
import com.butterflyatlas.app.places.PlaceBundles;
import com.butterflyatlas.app.places.PlaceIndex;
import com.butterflyatlas.app.taxonomy.BundleLoader;
import com.butterflyatlas.app.taxonomy.TaxonomyBundles;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.util.List;

/**
 * A data folder holding {@code atlas.db}. Opening a new folder creates the database and loads the
 * newest shipped taxonomy; an existing library is opened as it is (its taxonomy updates come after, 5b). Every
 * opening brings the library's places up to the newest shipped place list before anything reads them
 * ({@link PlaceChain}: a new library loads it, one on an older list takes each later list as a step), and reads the
 * bundled boundaries ({@link PlaceIndex#warm}) before anything can write photos.
 */
public final class Library {

    /** The folder in the library where new photos land first. */
    public static final String INCOMING = ".incoming";
    /** What a file being written ends in until it is complete (the startup cleanup removes leftovers by it). */
    public static final String PART = ".part";

    private final Path folder;
    private final Database database;
    private final PlaceIndex placeIndex;
    private final List<String> placeSteps;
    private final TaxonomyGate gate = new TaxonomyGate();
    private final PhotoHold photoHold = new PhotoHold();
    private final OpenImports openImports = new OpenImports();

    private Library(Path folder, Database database, PlaceIndex placeIndex, List<String> placeSteps) {
        this.folder = folder;
        this.database = database;
        this.placeIndex = placeIndex;
        this.placeSteps = placeSteps;
    }

    /**
     * Opens the library, bringing its places up to date. A place update step that fails throws
     * {@link com.butterflyatlas.app.placeupdates.PlaceUpdateException} (the library is as the step found it, and not
     * opened); a library on place data this app does not ship throws
     * {@link com.butterflyatlas.app.placeupdates.PlaceDataUnavailableException}.
     */
    public static Library open(Path folder, TaxonomyBundles bundles, PlaceBundles places, Clock clock) {
        try {
            Files.createDirectories(folder);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not create " + folder, e);
        }
        Database database = Database.open(folder.resolve(Database.FILE_NAME));
        boolean empty = database.read(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM taxonomy_state"); ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1) == 0;
            }
        });
        if (empty) {
            BundleLoader.load(database, bundles.newest(), clock);
        }
        List<String> steps = PlaceChain.bringUpToDate(database, places, clock);
        // Read the boundaries now, outside any transaction: the first photo's place lookup then never reads them
        // while its write transaction holds the write lock.
        places.index().warm();
        return new Library(folder, database, places.index(), steps);
    }

    public Path folder() {
        return folder;
    }

    public Database database() {
        return database;
    }

    /** The place lists brought in by update steps when it opened, oldest first (empty: none, or a new library). */
    public List<String> placeSteps() {
        return placeSteps;
    }

    /** The "wait" rule of taxonomy updates for this library. */
    public TaxonomyGate gate() {
        return gate;
    }

    /** Each import's revision (multi-window contract §5), in memory. */
    public OpenImports openImports() {
        return openImports;
    }

    /** The photos a backup holds (multi-window contract §4): taken after {@link #gate()}. */
    public PhotoHold photoHold() {
        return photoHold;
    }

    /** The bundled boundaries whose places this library holds (read when it opened). */
    public PlaceIndex placeIndex() {
        return placeIndex;
    }
}
