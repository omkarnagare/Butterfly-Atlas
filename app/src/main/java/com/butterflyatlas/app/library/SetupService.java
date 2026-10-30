package com.butterflyatlas.app.library;

import com.butterflyatlas.app.AtlasException;
import com.butterflyatlas.app.ErrorCodes;
import com.butterflyatlas.app.config.AppConfig;
import com.butterflyatlas.app.config.AppDirs;
import com.butterflyatlas.app.config.ConfigStore;
import com.butterflyatlas.app.db.Database;
import com.butterflyatlas.app.db.LibraryTooNewException;
import com.butterflyatlas.app.places.PlaceBundles;
import com.butterflyatlas.app.placeupdates.PlaceDataUnavailableException;
import com.butterflyatlas.app.placeupdates.PlaceUpdateException;
import com.butterflyatlas.app.taxonomy.TaxonomyBundles;
import com.butterflyatlas.app.window.InstanceLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sqlite.SQLiteConfig;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Optional;
import java.util.function.Function;

/** First-run choice of data folder (docs/PLAN.md §12), and reopening it on later launches. */
public final class SetupService {

    private static final Logger LOG = LoggerFactory.getLogger(SetupService.class);

    static final String LIBRARY_IN_USE = ErrorCodes.LIBRARY_IN_USE;
    static final String LIBRARY_TOO_NEW = "library_too_new";
    static final String INVALID_THEME = "invalid";
    static final String NOT_SET_UP = "not_set_up";

    /**
     * What the setup screen needs to know. {@code problem} explains why a remembered library did not open; {@code retry}
     * is true then, when opening it again ({@link #retry}) may work. When it is the first open after a change of library
     * folder (change-library-folder spec §6), {@code previousFolder} is the library used before, offered instead
     * ({@link #usePrevious}), and after a move {@code movedCopy} is the moved copy, which is not deleted; else both null.
     */
    public record Status(boolean configured, String suggestedFolder, String dataFolder, String problem, boolean retry,
                         String previousFolder, String movedCopy) {
    }

    /** The one-time note after a move (change-library-folder contract §2): where the library is now. */
    public record Moved(String to) {
    }

    /** A switched-to library whose unfinished work couldn't be cleared: it is not opened, and the words say why. */
    private static final class NotCleared extends RuntimeException {
        NotCleared(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * A live check of a folder, before the user commits to it: the library folder it gives ({@code libraryFolder}, null
     * when there is none) and what happens there ({@code kind}). {@code unfinished}: what an existing library was left
     * with where it was used before, cleared when it is opened here (null unless {@link #EXISTING}).
     */
    public record FolderCheck(String path, boolean absolute, String libraryFolder, String kind, UnfinishedWork.Counts unfinished) {
    }

    /** The library folder holds a library, which opens as it is. */
    public static final String EXISTING = "existing";
    /** The library is created in the library folder. */
    public static final String CREATE = "create";
    /** The folder's {@code Butterfly_Atlas} is a file, or a folder that is neither a library nor empty. */
    public static final String TAKEN = "taken";
    public static final String NOT_WRITABLE = "not_writable";
    public static final String NOT_FOUND = "not_found";
    public static final String NOT_A_FOLDER = "not_a_folder";
    /** Not a full path (or not a path at all). */
    public static final String RELATIVE = "relative";

    /** The library folder for a chosen folder, and what happens there. */
    record Place(Path folder, String kind) {
    }

    private final ConfigStore config;
    private final LibraryHolder holder;
    private final TaxonomyBundles bundles;
    private final PlaceBundles places;
    private final Path home;
    private final Path suggested;
    private final Clock clock;
    /** Takes a library's lock before it opens (null: none is taken, as in tests of everything but the lock). */
    private final Function<Path, InstanceLock.Result> locks;
    /** The lock of the open library, held for the life of the process. */
    private InstanceLock held;
    /** The saved look ({@code light}, {@code dark}, or null to match the device): kept in memory, and in every save. */
    private volatile String theme;
    private volatile String problem;
    private volatile boolean inUse;
    /** The remembered library that failed to open (Try again opens it again); null when none did. */
    private volatile Path failed;
    /** Whether Try again may help the library that failed (not for place data this copy of the app lacks). */
    private volatile boolean retry = true;
    /** Whether the library's drive could not take the lock file (the app started anyway: Settings and the footer say so). */
    private volatile boolean lockWarning;
    /** Whether this copy was started on a fixed folder ({@code -Datlas.dataDir}): its library folder can't be changed. */
    private volatile boolean fixed;
    /**
     * The settings of a change of library folder waiting for the new library's first open ({@code changePending}), read
     * at start; null once it opened, the old library was chosen instead, or another folder was set up.
     */
    private volatile AppConfig change;
    /** The note after a move, in memory until dismissed; null when none. */
    private volatile Moved moved;

    public SetupService(ConfigStore config, LibraryHolder holder, TaxonomyBundles bundles, PlaceBundles places, Path home,
                        Clock clock) {
        this(config, holder, bundles, places, home, clock, null);
    }

    /**
     * As above, taking each library's lock with {@code locks} before opening it: a library another copy holds is refused
     * ({@code library_in_use}), one whose drive cannot lock files is opened with {@link #lockWarning()} set.
     */
    public SetupService(ConfigStore config, LibraryHolder holder, TaxonomyBundles bundles, PlaceBundles places, Path home,
                        Clock clock, Function<Path, InstanceLock.Result> locks) {
        this.locks = locks;
        this.config = config;
        this.holder = holder;
        this.bundles = bundles;
        this.places = places;
        this.home = home;
        this.suggested = AppDirs.defaultDataFolder(home);
        this.clock = clock;
        this.theme = config.read().map(AppConfig::theme).orElse(null);
    }

    /** The saved look: {@code light} or {@code dark}, else {@code device} (match the device). */
    public String theme() {
        return theme == null ? AppConfig.DEVICE : theme;
    }

    /**
     * Opens the library at launch: {@code override} (from {@code -Datlas.dataDir}) if given, else
     * the remembered folder. A remembered folder that is missing or will not open leaves the app
     * unconfigured with a {@link Status#problem()} for the setup screen.
     */
    public void startup(Optional<Path> override) {
        if (override.isPresent()) {
            fixed = true;
            holder.set(openLocked(override.get().toAbsolutePath().normalize()));
            return;
        }
        Optional<AppConfig> saved = config.load();
        if (saved.isEmpty()) {
            return;
        }
        change = saved.get().pending() ? saved.get() : null;
        String raw = saved.get().dataFolder();
        Path folder;
        try {
            folder = Path.of(raw);
        } catch (InvalidPathException e) {
            folder = null;
        }
        if (folder == null || !folder.isAbsolute() || !Files.isDirectory(folder)) {
            problem = "Butterfly Atlas could not find your library at " + raw
                    + ". If it is on an external drive, connect it and open the app again, or choose another folder.";
            failed = folder != null && folder.isAbsolute() ? folder : null;
            retry = true;
            return;
        }
        open(folder);
    }

    /**
     * Opens the remembered library that failed to open, again (5c contract §6, Try again): on success the app opens as
     * at a normal start; on failure the problem says why again. 409 {@code not_failed} when no library failed to open.
     */
    public synchronized Status retry() {
        Path folder = failed;
        if (folder == null || holder.current().isPresent()) {
            throw new AtlasException(AtlasException.Kind.CONFLICT, "not_failed", "Your library is not waiting to be opened"
                    + " again.");
        }
        if (!Files.isDirectory(folder)) {
            problem = "Butterfly Atlas could not find your library at " + folder
                    + ". If it is on an external drive, connect it and choose Try again, or choose another folder.";
            return status();
        }
        open(folder);
        return status();
    }

    /** Opens the remembered library; when it will not open, the problem says why in plain words and Try again is offered. */
    private void open(Path folder) {
        try {
            Library library = openLocked(folder);
            firstOpen(library, folder);
            holder.set(library);
            problem = null;
            inUse = false;
            failed = null;
            retry = true;
        } catch (LibraryTooNewException e) {
            inUse = false;
            LOG.warn("The library at {} is newer than this app", folder, e);
            failed = folder;
            retry = false; // only the newer version of the app can open it
            problem = e.getMessage();
        } catch (AtlasException e) {
            if (!LIBRARY_IN_USE.equals(e.code())) {
                throw e;
            }
            failed = folder;
            retry = true; // once the other copy has closed
            inUse = true;
            problem = e.getMessage();
        } catch (PlaceDataUnavailableException e) {
            inUse = false;
            LOG.warn("Could not open the library at {}", folder, e);
            failed = folder;
            retry = false; // newer or not, this copy of the app lacks the place data: trying again can't help
            problem = e.getMessage();
        } catch (NotCleared e) {
            inUse = false;
            failed = folder;
            retry = true;
            problem = e.getMessage();
        } catch (RuntimeException e) {
            inUse = false;
            failed = folder;
            retry = true;
            problem = problem(folder, e, true);
        }
    }

    /**
     * The first open after a change of library folder (spec §6), before the library is handed over: a switched-to
     * library's unfinished work is cleared (as setup does; when that fails it is not opened, its lock is given back, and
     * the setup screen says why), the change is no longer pending (a move keeps its old copy and where it is), and a move
     * is noted for Home. Nothing when no change waits.
     */
    private void firstOpen(Library library, Path folder) {
        AppConfig pending = change;
        if (pending == null) {
            return;
        }
        if (!pending.hasOldCopy()) {
            try {
                UnfinishedWork.clear(library, clock);
            } catch (RuntimeException e) {
                release(held);
                held = null;
                LOG.warn("Could not clear the unfinished work of the library in {}", folder, e);
                throw new NotCleared("Butterfly Atlas couldn't clear the work left unfinished in your library at " + folder
                        + ", so it didn't open it. " + plain(folder, e, LibraryDrive::usableSpace), e);
            }
        }
        try {
            config.save(pending.hasOldCopy()
                    ? new AppConfig(folder.toString(), theme, pending.previousDataFolder(), null, true)
                    : new AppConfig(folder.toString(), theme));
        } catch (UncheckedIOException e) {
            LOG.warn("Butterfly Atlas could not record that the library change is done", e);
        }
        if (pending.hasOldCopy()) {
            moved = new Moved(folder.toString());
            LOG.info("Your library was moved to {}; the old copy is at {}", folder, pending.previousDataFolder());
        }
        change = null;
    }

    /**
     * "Use the old library instead" (spec §6, contract §3): during the first open after a change, when the new library
     * won't open, the library used before opens as it was (its unfinished work kept) and is remembered again; the change
     * and its old copy are forgotten (a moved copy is not deleted). 409 {@code no_previous} when it is not offered.
     */
    public synchronized Status usePrevious() {
        AppConfig pending = change;
        if (pending == null || pending.previousDataFolder() == null || holder.current().isPresent()) {
            throw new AtlasException(AtlasException.Kind.CONFLICT, ErrorCodes.NO_PREVIOUS,
                    "There is no earlier library to go back to.");
        }
        Path previous = Path.of(pending.previousDataFolder());
        if (!hasLibrary(previous)) {
            throw AtlasException.invalid("Butterfly Atlas could not find your old library at " + previous
                    + ". If it is on an external drive, connect it and try again.");
        }
        Library library;
        try {
            library = openLocked(previous);
        } catch (AtlasException e) {
            throw e;
        } catch (LibraryTooNewException e) {
            throw new AtlasException(AtlasException.Kind.CONFLICT, LIBRARY_TOO_NEW, e.getMessage());
        } catch (PlaceDataUnavailableException e) {
            LOG.warn("Could not open the old library at {}", previous, e);
            throw AtlasException.invalid(e.getMessage());
        } catch (RuntimeException e) {
            LOG.warn("Could not open the old library at {}", previous, e);
            throw AtlasException.invalid("Butterfly Atlas could not open your old library at " + previous + ". "
                    + plain(previous, e, LibraryDrive::usableSpace));
        }
        try {
            config.save(new AppConfig(previous.toString(), theme));
        } catch (UncheckedIOException e) {
            LOG.warn("Butterfly Atlas could not remember the library folder for next time", e);
        }
        LOG.info("Went back to the old library at {}; {} was not deleted", previous, pending.dataFolder());
        change = null;
        problem = null;
        failed = null;
        inUse = false;
        retry = true;
        holder.set(library);
        return status();
    }

    /** The old copy a move left (spec §9), while the settings keep it; else null. */
    public Path oldCopy() {
        try {
            return config.read().filter(AppConfig::hasOldCopy).map(AppConfig::previousDataFolder).map(Path::of)
                    .orElse(null);
        } catch (InvalidPathException e) {
            return null;
        }
    }

    /** The old copy is gone: the settings no longer name it (nor the library it was). */
    public synchronized void forgetOldCopy() {
        config.read().ifPresent(saved -> config.save(new AppConfig(saved.dataFolder(), saved.theme(), null,
                saved.changePending(), null)));
    }

    /** After a move, where the library is now, until the note is dismissed; else null. */
    public Moved libraryMoved() {
        return moved;
    }

    /** Home's note after a move is put away (in memory). */
    public void dismissMovedNote() {
        moved = null;
    }

    /**
     * The problem of a library that would not open, in plain words (the raw error goes to the log only); {@code tryAgain}:
     * the screen it is shown on offers Try again (the setup screen does; choosing a folder does not).
     */
    private static String problem(Path folder, RuntimeException e, boolean tryAgain) {
        String reason = plain(folder, e, LibraryDrive::usableSpace);
        if (e instanceof PlaceUpdateException step) {
            LOG.warn("Could not update the place names of the library at {}", folder, step);
            if (!step.applied().isEmpty()) {
                return "Place names were updated to " + step.applied().getLast() + ", but the update to " + step.to()
                        + " couldn't be applied: " + lower(reason) + " Nothing of that last update was kept"
                        + (tryAgain ? "; choose Try again." : ".");
            }
            return "Place names couldn't be updated: " + lower(reason) + " Your library is unchanged.";
        }
        LOG.warn("Could not open the library at {}", folder, e);
        return "Butterfly Atlas could not open your library at " + folder + ". " + reason;
    }

    static final String FULL = "The drive with your library is full. Free some space, then try again.";
    static final String GONE = "The drive with your library can't be found. Reconnect it, then try again.";
    static final String LOCKED = "Close any other program using your library folder (a sync tool, another copy of Butterfly"
            + " Atlas), then try again.";
    static final String READ_ONLY = "Butterfly Atlas can't write to your library folder. Check that the drive isn't"
            + " read-only and that the folder may be changed, then try again.";
    static final String UNREADABLE = "Your library couldn't be read or written. Check the drive it is on, then try again.";
    // The app writes no log file (its log goes to the console it was started from), so the user is asked for what
    // they did instead.
    static final String UNEXPECTED = "Something unexpected stopped it. If this keeps happening, contact the Butterfly Atlas"
            + " developers with what you did just before.";

    /** Why the library at {@code folder} would not open, in plain words (never the raw error): for the start-up page. */
    public static String reasonFor(Path folder, Throwable e) {
        return plain(folder, e, LibraryDrive::usableSpace);
    }

    /**
     * Why a library would not open, in plain words (5c contract §10), whatever stopped it (the migration, a place update
     * step, anything else): a full or missing drive in 5b's words, a library another program holds, a folder that may not
     * be written, a drive that cannot be read or written, else something unexpected. Never the raw error text.
     */
    static String plain(Path folder, Throwable e, java.util.function.ToLongFunction<Path> usableSpace) {
        LibraryDrive.Problem drive = LibraryDrive.check(folder, usableSpace);
        if (drive != null) {
            return drive.code().equals(LibraryDrive.DRIVE_FULL) ? FULL : GONE;
        }
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof org.sqlite.SQLiteException sqlite) {
                String code = sqlite.getResultCode().name();
                if (code.startsWith("SQLITE_BUSY") || code.startsWith("SQLITE_LOCKED")) {
                    return LOCKED;
                }
                if (code.startsWith("SQLITE_FULL")) {
                    return FULL;
                }
                if (code.startsWith("SQLITE_READONLY") || code.startsWith("SQLITE_CANTOPEN") || code.startsWith("SQLITE_PERM")) {
                    return READ_ONLY;
                }
            }
        }
        if (LibraryDrive.sqliteIoError(e) || ioFailure(e)) {
            return UNREADABLE;
        }
        return UNEXPECTED;
    }

    private static boolean ioFailure(Throwable e) {
        Throwable t = e;
        for (int depth = 0; t != null && depth < 20; depth++, t = t.getCause()) {
            if (t instanceof IOException || t instanceof UncheckedIOException) {
                return true;
            }
        }
        return false;
    }

    /** A reason sentence to follow "…: ": its first word lower-cased, unless it is the app's name. */
    static String lower(String sentence) {
        if (sentence.startsWith("Butterfly Atlas")) {
            return sentence;
        }
        return Character.toLowerCase(sentence.charAt(0)) + sentence.substring(1);
    }

    /** Whether this copy was started on a fixed folder ({@code -Datlas.dataDir}), so its library folder can't change. */
    public boolean fixed() {
        return fixed;
    }

    /** True when the open library's drive cannot prevent a second copy from opening it (design §15, D2). */
    public boolean lockWarning() {
        return lockWarning;
    }

    /**
     * The folder of the remembered library when another copy of the app holds its lock (still, after the wait the launch
     * allows), else null: the launch then shows the "already running" page instead of a setup screen (design §15.4).
     */
    public Path libraryInUse() {
        return inUse && holder.current().isEmpty() ? failed : null;
    }

    /** The setup screen's status; a remembered library that would not open is named as {@code dataFolder}. */
    public Status status() {
        Path folder = failed;
        AppConfig pending = change;
        return holder.current()
                .map(library -> new Status(true, suggested.toString(), library.folder().toString(), null, false, null, null))
                .orElseGet(() -> new Status(false, suggested.toString(), folder == null ? null : folder.toString(), problem,
                        folder != null && retry, pending == null ? null : pending.previousDataFolder(),
                        pending != null && pending.hasOldCopy() ? pending.dataFolder() : null));
    }

    /**
     * Saves the Match device, Light or Dark choice with the library folder (Meadow UI contract §3, match-device design §3)
     * and keeps it in memory once saved; {@code device} drops the saved look. Invalid unless {@code device}, {@code light}
     * or {@code dark}; 409 {@code not_set_up} while no library is chosen; a failed write is
     * an {@link UncheckedIOException}, and the choice in memory stays as it was.
     */
    public synchronized String setTheme(String requested) {
        if (!AppConfig.isChoice(requested)) {
            throw new AtlasException(AtlasException.Kind.INVALID, INVALID_THEME, "Choose Match device, Light or Dark.");
        }
        if (holder.current().isEmpty()) {
            throw new AtlasException(AtlasException.Kind.CONFLICT, NOT_SET_UP,
                    "Choose where to keep your photos first.");
        }
        // what else the settings hold (the library folder, a library change's fields) is kept as it is
        String look = AppConfig.isTheme(requested) ? requested : null;
        config.save(config.read().map(saved -> saved.withTheme(look)).orElse(new AppConfig(null, look)));
        theme = look;
        return requested;
    }

    /**
     * Whether this app can open the library at {@code folder}, read without changing it (change-library-folder spec §5,
     * Use a different library's {@code checking}): not made by a newer app, and on place data this app ships. Null when
     * it can, else why not in plain words.
     */
    String cannotOpen(Path folder) {
        Path file = folder.resolve(Database.FILE_NAME);
        SQLiteConfig readOnly = new SQLiteConfig();
        readOnly.setReadOnly(true);
        try (java.sql.Connection c = readOnly.createConnection("jdbc:sqlite:" + file.toAbsolutePath())) {
            int newest = 0;
            try (java.sql.Statement st = c.createStatement(); java.sql.ResultSet rs = st.executeQuery(
                    "SELECT MAX(CAST(version AS INTEGER)) FROM flyway_schema_history WHERE success = 1")) {
                if (rs.next()) {
                    newest = rs.getInt(1);
                }
            }
            if (newest > Database.knownSchemaVersion()) {
                return new LibraryTooNewException(newest, Database.knownSchemaVersion()).getMessage();
            }
            java.util.List<String> versions = new java.util.ArrayList<>();
            try (java.sql.Statement st = c.createStatement(); java.sql.ResultSet rs = st.executeQuery(
                    "SELECT DISTINCT bundle_version FROM place_state ORDER BY 1")) {
                while (rs.next()) {
                    versions.add(rs.getString(1));
                }
            }
            return com.butterflyatlas.app.placeupdates.PlaceChain.unavailable(versions, places).orElse(null);
        } catch (java.sql.SQLException e) {
            LOG.warn("Could not read the library at {}", folder, e);
            return "Its atlas.db couldn't be read as a Butterfly Atlas library. " + plain(folder, e, LibraryDrive::usableSpace);
        }
    }

    /**
     * Remembers a change of library folder at its switch (spec §5 step 4, §8): {@code to} is the library folder from now
     * on, {@code from} the one before it, the change waits for the new library's first open, and after a move the old copy
     * is kept. The look is kept. A failed write throws and the settings are as they were.
     */
    public synchronized void rememberChange(String kind, Path from, Path to) {
        config.save(new AppConfig(to.toString(), theme, from.toString(), true, LibraryChange.MOVE.equals(kind)));
    }

    /** {@link #inspect(String, boolean)} for the folder as given. */
    public FolderCheck inspect(String rawPath) {
        return inspect(rawPath, true);
    }

    /**
     * Checks a folder for setup. {@code exact}: the folder is the library folder (the suggested folder, a typed path);
     * otherwise it is where the library is kept (the folder browser): the folder itself when it holds a library, else its
     * {@code Butterfly_Atlas}.
     */
    public FolderCheck inspect(String rawPath, boolean exact) {
        Path picked;
        try {
            picked = resolve(rawPath);
        } catch (AtlasException e) {
            return new FolderCheck(rawPath, false, null, RELATIVE, null);
        }
        Place place = place(picked, exact);
        UnfinishedWork.Counts unfinished = EXISTING.equals(place.kind())
                ? UnfinishedWork.count(place.folder().resolve(Database.FILE_NAME)) : null;
        return new FolderCheck(picked.toString(), true, place.folder() == null ? null : place.folder().toString(),
                place.kind(), unfinished);
    }

    /** Where the library of a chosen folder is (see {@link #inspect(String, boolean)}), and what happens there. */
    static Place place(Path picked, boolean exact) {
        if (Files.exists(picked) && !Files.isDirectory(picked)) {
            return new Place(null, NOT_A_FOLDER);
        }
        if (hasLibrary(picked)) {
            return new Place(picked, EXISTING);
        }
        if (exact) {
            return new Place(picked, writable(picked) ? CREATE : NOT_WRITABLE);
        }
        if (!Files.isDirectory(picked)) {
            return new Place(null, NOT_FOUND);
        }
        Path inside = picked.resolve(AppDirs.LIBRARY_NAME);
        if (hasLibrary(inside)) {
            return new Place(inside, EXISTING);
        }
        if (Files.exists(inside) && (!Files.isDirectory(inside) || !isEmpty(inside))) {
            return new Place(inside, TAKEN);
        }
        return new Place(inside, writable(inside) ? CREATE : NOT_WRITABLE);
    }

    static boolean hasLibrary(Path folder) {
        return Files.isRegularFile(folder.resolve(Database.FILE_NAME));
    }

    private static boolean writable(Path folder) {
        return Files.isDirectory(folder) ? Files.isWritable(folder) : nearestExistingIsWritable(folder);
    }

    /** True when the folder has nothing in it; one that can't be listed counts as not empty. */
    private static boolean isEmpty(Path folder) {
        try (java.util.stream.Stream<Path> entries = Files.list(folder)) {
            return entries.findAny().isEmpty();
        } catch (IOException e) {
            return false;
        }
    }

    /** {@link #setup(String, boolean)} for the folder as given. */
    public synchronized Status setup(String rawPath) {
        return setup(rawPath, true);
    }

    /** Why a chosen folder can't hold the library, in the setup screen's words; null when it can. */
    static String refusal(Place place) {
        return switch (place.kind()) {
            case NOT_A_FOLDER -> "That is a file, not a folder. Choose a folder.";
            case NOT_FOUND -> "That folder can't be found. Choose another folder.";
            case TAKEN -> place.folder() + " already exists and isn't a Butterfly Atlas library. Choose another folder.";
            default -> null;
        };
    }

    /** Creates or opens the library of a chosen folder ({@code exact} as in {@link #inspect(String, boolean)}). */
    public synchronized Status setup(String rawPath, boolean exact) {
        if (holder.current().isPresent()) {
            throw new AtlasException(AtlasException.Kind.ALREADY_CONFIGURED, "Your library is already set up.");
        }
        Place place = place(resolve(rawPath), exact);
        String refused = refusal(place);
        if (refused != null) {
            throw AtlasException.invalid(refused);
        }
        Path folder = place.folder();
        boolean existing = EXISTING.equals(place.kind());
        try {
            Files.createDirectories(folder);
        } catch (IOException e) {
            throw AtlasException.invalid("Butterfly Atlas cannot create a folder there. Choose another place.");
        }
        if (!canWrite(folder)) {
            throw AtlasException.invalid("Butterfly Atlas cannot save files in that folder. Choose another place.");
        }
        Library library;
        try {
            library = openLocked(folder);
        } catch (AtlasException e) {
            throw e;
        } catch (LibraryTooNewException e) {
            throw new AtlasException(AtlasException.Kind.CONFLICT, LIBRARY_TOO_NEW, e.getMessage());
        } catch (PlaceUpdateException e) {
            throw AtlasException.invalid(problem(folder, e, false));
        } catch (PlaceDataUnavailableException e) {
            LOG.warn("Could not open a library in {}", folder, e);
            throw AtlasException.invalid(e.getMessage());
        } catch (RuntimeException e) {
            LOG.warn("Could not open a library in {}", folder, e);
            String reason = plain(folder, e, LibraryDrive::usableSpace);
            if (!reason.equals(UNEXPECTED)) {
                throw AtlasException.invalid("Butterfly Atlas cannot open the library in that folder. " + reason);
            }
            throw AtlasException.invalid("Butterfly Atlas cannot open the library in that folder. "
                    + "If it holds a file called atlas.db from somewhere else, choose another folder.");
        }
        if (existing) {
            clearUnfinished(library, folder);
        }
        try {
            config.save(new AppConfig(folder.toString(), theme));
        } catch (UncheckedIOException e) {
            LOG.warn("Butterfly Atlas could not remember the library folder for next time", e);
        }
        change = null; // another folder was chosen: the old library is no longer offered
        holder.set(library);
        problem = null;
        failed = null;
        return status();
    }

    /**
     * Clears the work an existing library was left with where it was used before (setup design §3); when that fails
     * nothing of it changed, the library is not opened (its lock is given back) and the setup screen says why.
     */
    private void clearUnfinished(Library library, Path folder) {
        try {
            UnfinishedWork.clear(library, clock);
        } catch (RuntimeException e) {
            release(held);
            held = null;
            LOG.warn("Could not clear the unfinished work of the library in {}", folder, e);
            throw AtlasException.invalid("Butterfly Atlas couldn't clear the work left unfinished in that library, so it"
                    + " didn't open it. " + plain(folder, e, LibraryDrive::usableSpace));
        }
    }

    /**
     * Opens the library with its lock taken first and given back if it does not open: a lock held by another copy is
     * {@code library_in_use}; a drive that cannot lock files sets {@link #lockWarning()} and the library opens anyway.
     */
    private Library openLocked(Path folder) {
        InstanceLock lock = null;
        if (locks != null) {
            InstanceLock.Result result = locks.apply(folder);
            switch (result.state()) {
                case HELD -> throw new AtlasException(AtlasException.Kind.CONFLICT, LIBRARY_IN_USE,
                        "Another copy of Butterfly Atlas is using this library.");
                case CANNOT_LOCK -> {
                    LOG.warn("The drive of {} cannot lock files ({}); opening it without a lock", folder, result.reason());
                    lockWarning = true;
                }
                default -> lock = result.lock();
            }
        }
        try {
            Library library = Library.open(folder, bundles, places, clock);
            held = lock;
            return library;
        } catch (RuntimeException | Error e) {
            release(lock);
            throw e;
        }
    }

    private static void release(InstanceLock lock) {
        if (lock != null) {
            try {
                lock.close();
            } catch (IOException e) {
                LOG.debug("Could not release the library's lock", e);
            }
        }
    }

    /** A typed or chosen path as a full path, or 400 in plain words. */
    Path resolve(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            throw AtlasException.invalid("Choose a folder for your library.");
        }
        String text = rawPath.strip();
        Path path;
        try {
            if (text.equals("~")) {
                path = home;
            } else if (text.startsWith("~/") || text.startsWith("~\\")) {
                path = home.resolve(text.substring(2));
            } else {
                path = Path.of(text);
            }
        } catch (InvalidPathException e) {
            throw AtlasException.invalid("That is not a valid folder path.");
        }
        if (!path.isAbsolute()) {
            throw AtlasException.invalid("Enter the full path of a folder, such as " + suggested + ".");
        }
        return path.normalize();
    }

    static boolean canWrite(Path folder) {
        Path probe;
        try {
            probe = Files.createTempFile(folder, ".atlas-write-check", ".tmp");
        } catch (IOException e) {
            return false;
        }
        try {
            Files.deleteIfExists(probe);
        } catch (IOException e) {
            LOG.debug("Could not remove {}", probe, e);
        }
        return true;
    }

    private static boolean nearestExistingIsWritable(Path path) {
        for (Path p = path.getParent(); p != null; p = p.getParent()) {
            if (Files.isDirectory(p)) {
                return Files.isWritable(p);
            }
        }
        return false;
    }
}
