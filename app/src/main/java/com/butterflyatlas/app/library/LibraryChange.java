package com.butterflyatlas.app.library;

import com.butterflyatlas.app.AtlasException;
import com.butterflyatlas.app.ErrorCodes;
import com.butterflyatlas.app.db.Database;
import com.butterflyatlas.app.window.InstanceLock;
import com.butterflyatlas.app.window.Relaunch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sqlite.SQLiteConfig;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.function.ToLongFunction;
import java.util.stream.Stream;

/**
 * Changing the library folder (change-library-folder spec §5, §7, §10): the state machine every window shows, the copy of
 * a move, its check, Cancel and the clean-up. A move copies every file of the library folder into the new library folder
 * (each written as {@code .part} and renamed), then the database ({@code VACUUM INTO}), then checks the copy; only then
 * does it switch, and from there it can't be cancelled. Before the switch the old library is never written to: a failure
 * or a Cancel deletes the copy (the new library folder and nothing else) and says what couldn't be removed. Every step
 * that touches a drive goes through {@link ChangeSteps}; every failure is logged with its raw error and told in plain
 * words. One change at a time, on a thread of its own.
 */
public final class LibraryChange {

    private static final Logger LOG = LoggerFactory.getLogger(LibraryChange.class);

    public static final String MOVE = "move";
    public static final String SWITCH = "switch";

    public static final String COPYING = "copying";
    public static final String COPYING_DATABASE = "copying_database";
    public static final String CHECKING = "checking";
    public static final String SWITCHING = "switching";
    public static final String REOPENING = "reopening";
    public static final String FAILED = "failed";
    public static final String CANCELLING = "cancelling";

    /** 409 once Cancel can no longer stop the change, or when nothing runs. */
    public static final String CANNOT_CANCEL = ErrorCodes.CANNOT_CANCEL;

    /** How many of the files a failed check names. */
    static final int NAMED = 10;

    /** What a move does not copy, at the top of the library folder. */
    private static final Set<String> NOT_COPIED = Set.of(Database.FILE_NAME, Database.FILE_NAME + "-wal",
            Database.FILE_NAME + "-shm", Database.FILE_NAME + "-journal", InstanceLock.FILE);
    /** The HEIC decoder's working folder: nothing in it outlives the app. */
    private static final Path DECODE = Path.of(".thumbs", ".decode");

    static final String FULL_THERE = "The drive you chose is full. Free some space, then try again.";
    static final String GONE_THERE = "The drive you chose can't be found. Reconnect it, then try again.";
    static final String NOT_WRITABLE_THERE = "Butterfly Atlas can't write to the folder you chose. Check that the drive"
            + " isn't read-only and that the folder may be changed, then try again.";
    static final String CHECK_THERE = "Check the drive you chose, then try again.";

    /** Done and total, of files or bytes. */
    public record Count(long done, long total) {
        static final Count NONE = new Count(0, 0);
    }

    /** What couldn't be removed of a copy: its folder and how many files are still in it. */
    public record Left(String folder, int files) {
    }

    /** How the app reopens ({@code restart} or {@code quit}), on which folder, and why it quits instead (else null). */
    public record Reopen(String how, String folder, String why) {
    }

    /** {@code /api/status}'s {@code changing} (contract §2). */
    public record Changing(String kind, String to, String step, Count files, Count bytes, boolean canCancel,
                           String problem, Left left, Reopen reopen) {
    }

    /** Background work held while a library is copied (nothing writes to it then), let go when the change fails. */
    public interface Background {

        /** Returns once the work under way has stopped; none starts until {@link #release}. */
        void hold();

        void release();

        Background NONE = new Background() {
            @Override
            public void hold() {
            }

            @Override
            public void release() {
            }
        };

        /** The open library's own {@link WorkHold}: thumbnails (and their HEIC decodes) and photo details. */
        static Background of(Supplier<Library> library) {
            return new Background() {
                @Override
                public void hold() {
                    try {
                        library.get().workHold().hold();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("Interrupted while holding the background work", e);
                    }
                }

                @Override
                public void release() {
                    library.get().workHold().release();
                }
            };
        }
    }

    /** The switch (spec §5 step 4): the settings saved with the new library folder; throws when they can't be. */
    @FunctionalInterface
    public interface Switcher {
        void switchTo(String kind, Path from, Path to) throws Exception;
    }

    /** The refusals of a folder for a change (contract §3), as {@code inspect} answers them. */
    public static final String EXISTING_LIBRARY = ErrorCodes.EXISTING_LIBRARY;
    public static final String INSIDE_LIBRARY = ErrorCodes.INSIDE_LIBRARY;
    public static final String CURRENT_LIBRARY = ErrorCodes.CURRENT_LIBRARY;

    static final String EXISTING_WORDS = "That folder already holds a library. To open it, use Use a different library.";
    static final String OUTSIDE_WORDS = "Choose a folder outside your library.";
    static final String CURRENT_WORDS = "That is the library you are using.";
    static final String IN_USE_WORDS = "Another copy of Butterfly Atlas is using this library.";
    static final String NOT_WRITABLE_WORDS = "Butterfly Atlas cannot save files in that folder. Choose another place.";
    static final String FIXED_WORDS = "This copy of Butterfly Atlas was started on a fixed folder.";

    /** A move needs the library's size plus this share of it... */
    private static final double SPARE_SHARE = 0.05;
    /** ...and this much more free on the drive chosen (spec §4). */
    private static final long SPARE_BYTES = 200_000_000L;

    /**
     * {@code GET /api/library/change} (contract §3): the library folder, its size (every file in it), its drive's usable
     * space, whether it is fixed ({@code -Datlas.dataDir}), and the change running or waiting to be dismissed.
     */
    public record Overview(String folder, long size, long free, boolean fixed, Changing changing, OldCopy oldCopy) {
    }

    /** The old copy a move left (spec §9): its folder, its size, and the library's last backup (null when none). */
    public record OldCopy(String folder, long size, LastBackup lastBackup) {
    }

    /** The newest backup that finished: {@code everything} or {@code database}, and when (an ISO instant). */
    public record LastBackup(String what, String finishedAt) {
    }

    /**
     * {@code DELETE /api/library/old-copy}: whether the old library is gone ({@code deleted}; false while some of it is
     * left), and up to {@link #NAMED} of what is still in its folder, of {@code leftCount}.
     */
    public record Deleted(boolean deleted, List<String> left, int leftCount) {
    }

    /** What a library is made of: all that Delete old copy removes (spec §9). */
    private static final List<String> LIBRARY_PARTS = List.of(Database.FILE_NAME, Database.FILE_NAME + "-wal",
            Database.FILE_NAME + "-shm", Database.FILE_NAME + "-journal", "images", ".thumbs", ".deleted", Library.INCOMING,
            InstanceLock.FILE);

    /**
     * {@code GET /api/library/change/inspect} (contract §3): the setup screen's check of the folder shown, the refusal of
     * this change (null when it may go ahead), and for a move the drive's free space and what the move needs.
     */
    public record Inspect(String path, boolean absolute, String libraryFolder, String kind, UnfinishedWork.Counts unfinished,
                          String refusal, Long free, Long needed) {
    }

    /** One file of the library to copy: its path inside the library folder and its size. */
    private record Item(Path relative, long size) {
    }

    /** A Cancel seen between two steps. */
    private static final class Cancelled extends Exception {
        Cancelled() {
            super(null, null, false, false);
        }
    }

    /** A step that failed, with the plain words for it (the cause is logged). */
    private static final class Failed extends Exception {
        Failed(String problem, Throwable cause) {
            super(problem, cause);
        }
    }

    private final Supplier<Library> library;
    /** The setup's folder rules and settings; null in tests of the copy alone. */
    private final SetupService setup;
    /** What runs now that refuses a change, in plain words (spec §4), else empty. */
    private final Supplier<Optional<String>> runningWork;
    private final ChangeSteps steps;
    private final ToLongFunction<Path> usableSpace;
    private final Background background;
    private final Switcher switcher;
    private final Object lock = new Object();
    /** One Delete old copy at a time. */
    private final Object deleting = new Object();
    /** Ends this copy of the app after the switch (set once the app runs; until then it quits and asks, doing nothing). */
    private volatile Relaunch.Reopener reopener = new Relaunch.Reopener() {
        @Override
        public Relaunch.Plan plan() {
            return Relaunch.plan(null);
        }

        @Override
        public boolean begin(Relaunch.Plan plan) {
            LOG.warn("No app to reopen after the library change");
            return false;
        }

        @Override
        public void exit() {
        }
    };
    /** How long every window has to read {@code reopening} before the quit (contract §2). */
    private volatile Duration reopenWait = REOPEN_WAIT;
    /** What every window is shown; null when no change runs or waits to be dismissed. */
    private Changing current;
    private boolean cancelRequested;
    private Thread worker;

    public LibraryChange(Supplier<Library> library, ChangeSteps steps, ToLongFunction<Path> usableSpace,
                         Background background, Switcher switcher) {
        this(library, null, steps, usableSpace, background, switcher, Optional::empty);
    }

    /**
     * {@code setup} gives the folder rules and the settings; {@code runningWork} names the work that refuses a change
     * while it runs (an export or backup running, an import filing, a taxonomy or place update, the startup work).
     */
    public LibraryChange(Supplier<Library> library, SetupService setup, ChangeSteps steps, ToLongFunction<Path> usableSpace,
                         Background background, Switcher switcher, Supplier<Optional<String>> runningWork) {
        this.setup = setup;
        this.runningWork = runningWork;
        this.library = library;
        this.steps = steps;
        this.usableSpace = usableSpace;
        this.background = background;
        this.switcher = switcher;
    }

    /** The change running or waiting to be dismissed, else empty (from memory). */
    public Optional<Changing> changing() {
        synchronized (lock) {
            return Optional.ofNullable(current);
        }
    }

    /** The Library folder section of Settings (contract §3). */
    public Overview overview() {
        Path folder = library.get().folder();
        return new Overview(folder.toString(), size(folder), usableSpace.applyAsLong(folder), setup != null && setup.fixed(),
                changing().orElse(null), oldCopy());
    }

    /** The old copy a move left, while the settings keep it (until it is deleted or the user went back to it). */
    private OldCopy oldCopy() {
        Path folder = setup == null ? null : setup.oldCopy();
        if (folder == null) {
            return null;
        }
        return new OldCopy(folder.toString(), Files.isDirectory(folder) ? size(folder) : 0, lastBackup());
    }

    /** The newest backup of the library in use that finished (spec §9), or null; one that can't be read is none. */
    private LastBackup lastBackup() {
        try {
            return library.get().database().read(c -> {
                try (PreparedStatement ps = c.prepareStatement("SELECT what, finished_at FROM backup_run WHERE state = 'done'"
                        + " ORDER BY finished_at DESC, id DESC LIMIT 1"); ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? new LastBackup(rs.getString(1), rs.getString(2)) : null;
                }
            });
        } catch (RuntimeException e) {
            LOG.warn("Could not read the last backup", e);
            return null;
        }
    }

    /**
     * Delete old copy (spec §9): removes only what a library is made of from the old copy's folder, with its lock taken
     * first and held while deleting (given back before the lock file itself goes); the folder goes only when that leaves
     * it empty. When all of the library is gone, the settings forget the old copy; when some of it is left, they keep it
     * and Delete can be chosen again. 409 {@code no_old_copy} when there is none; 409 {@code library_in_use} when it is
     * the library in use or another copy of the app holds it.
     */
    public Deleted deleteOldCopy() {
        synchronized (deleting) {
            Path folder = setup == null ? null : setup.oldCopy();
            if (folder == null) {
                throw new AtlasException(AtlasException.Kind.CONFLICT, ErrorCodes.NO_OLD_COPY,
                        "There is no old copy of your library to delete.");
            }
            Path current = real(library.get().folder());
            Path target = real(folder);
            if (target.equals(current) || current.startsWith(target) || target.startsWith(current)) {
                throw new AtlasException(AtlasException.Kind.CONFLICT, ErrorCodes.LIBRARY_IN_USE,
                        "That is the library you are using, so it can't be deleted.");
            }
            if (!Files.isDirectory(folder)) {
                LOG.info("The old copy at {} is gone already", folder);
                setup.forgetOldCopy();
                return new Deleted(true, List.of(), 0);
            }
            InstanceLock.Result lock = InstanceLock.acquire(folder);
            if (lock.state() == InstanceLock.State.HELD) {
                throw new AtlasException(AtlasException.Kind.CONFLICT, ErrorCodes.LIBRARY_IN_USE, IN_USE_WORDS);
            }
            if (lock.state() == InstanceLock.State.CANNOT_LOCK) {
                LOG.warn("The drive of {} cannot lock files ({}); deleting the old copy without its lock", folder,
                        lock.reason());
            }
            try {
                for (String part : LIBRARY_PARTS) {
                    if (!part.equals(InstanceLock.FILE)) {
                        deleteTree(folder.resolve(part));
                    }
                }
            } finally {
                if (lock.lock() != null) {
                    try {
                        lock.lock().close();
                    } catch (IOException e) {
                        LOG.debug("Could not give back the lock of {}", folder, e);
                    }
                }
            }
            deleteTree(folder.resolve(InstanceLock.FILE));
            List<String> left = new ArrayList<>();
            for (String part : LIBRARY_PARTS) {
                left.addAll(filesIn(folder.resolve(part)));
            }
            boolean deleted = left.isEmpty();
            try (Stream<Path> entries = Files.list(folder)) {
                entries.filter(p -> !LIBRARY_PARTS.contains(p.getFileName().toString())).map(Path::toString).sorted()
                        .forEach(left::add);
            } catch (IOException e) {
                LOG.warn("Could not list what is left in {}", folder, e);
            }
            if (left.isEmpty()) {
                try {
                    steps.delete(folder);
                } catch (IOException | RuntimeException e) {
                    LOG.warn("Could not remove the emptied folder {}", folder, e);
                    left.add(folder.toString());
                }
            }
            if (deleted) {
                setup.forgetOldCopy();
                LOG.info("Deleted the old copy of the library at {}{}", folder, left.isEmpty() ? "" : "; other things in"
                        + " its folder were left: " + left.size());
            } else {
                LOG.warn("{} file(s) of the old copy at {} couldn't be deleted", left.size(), folder);
            }
            return new Deleted(deleted, List.copyOf(left.subList(0, Math.min(NAMED, left.size()))), left.size());
        }
    }

    /** Deletes a file, or a folder and everything in it, through {@link ChangeSteps#delete}; what fails is logged. */
    private void deleteTree(Path path) {
        if (!Files.exists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        List<Path> paths;
        try (Stream<Path> walk = Files.walk(path)) {
            paths = walk.sorted(Comparator.reverseOrder()).toList();
        } catch (IOException | java.io.UncheckedIOException e) {
            LOG.warn("Could not list {} to delete it", path, e);
            return;
        }
        for (Path p : paths) {
            try {
                steps.delete(p);
            } catch (IOException | RuntimeException e) {
                LOG.warn("Could not delete {}", p, e);
            }
        }
    }

    /** The files still at {@code path} (itself, or under it), as full paths, sorted. */
    private static List<String> filesIn(Path path) {
        if (!Files.exists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(path)) {
            return walk.filter(p -> !Files.isDirectory(p, java.nio.file.LinkOption.NOFOLLOW_LINKS)).map(Path::toString)
                    .sorted().toList();
        } catch (IOException | java.io.UncheckedIOException e) {
            return List.of(path.toString());
        }
    }

    /**
     * The folder the browser shows, checked for a move or a switch ({@code kind}): as setup checks it (the folder itself
     * when it holds a library, else its {@code Butterfly_Atlas}), with this change's refusal.
     */
    public Inspect inspect(String rawPath, String kind) {
        boolean move = kind(kind);
        SetupService.FolderCheck check = setup.inspect(rawPath, false);
        if (check.libraryFolder() == null) {
            return new Inspect(check.path(), check.absolute(), null, check.kind(), check.unfinished(), null, null, null);
        }
        Path folder = Path.of(check.libraryFolder());
        Long free = move ? usableSpace.applyAsLong(nearestExisting(folder)) : null;
        Long needed = move ? needed(size(library.get().folder())) : null;
        String refusal = refusal(move, Path.of(check.path()), folder, check.kind(), free, needed);
        return new Inspect(check.path(), check.absolute(), check.libraryFolder(), check.kind(), check.unfinished(), refusal,
                free, needed);
    }

    /**
     * Move (spec §3, §4): checks again (fixed folder, a change already running, running work, the folder, the space) and
     * starts the copy. 409 {@code fixed_folder}, {@code library_changing}, {@code work_running} (naming what runs) or
     * {@code not_enough_space}; 400 {@code invalid} for the folder, in the setup screen's words.
     */
    public Changing move(String rawPath) {
        allowed();
        SetupService.Place place = place(rawPath);
        if (SetupService.NOT_WRITABLE.equals(place.kind())) {
            throw invalid(NOT_WRITABLE_WORDS);
        }
        long free = usableSpace.applyAsLong(nearestExisting(place.folder()));
        long needed = needed(size(library.get().folder()));
        String refusal = refusal(true, setup.resolve(rawPath), place.folder(), place.kind(), free, needed);
        if (refusal != null) {
            throw refused(true, refusal, free, needed);
        }
        return copyTo(place.folder());
    }

    /** Refuses any change on a fixed folder, while one runs, or while work runs that must finish first. */
    private void allowed() {
        if (setup != null && setup.fixed()) {
            throw new AtlasException(AtlasException.Kind.CONFLICT, ErrorCodes.FIXED_FOLDER, FIXED_WORDS);
        }
        if (blocks()) {
            throw new AtlasException(AtlasException.Kind.CONFLICT, ErrorCodes.LIBRARY_CHANGING, CHANGING_MESSAGE);
        }
        Optional<String> running = runningWork.get();
        if (running.isPresent()) {
            throw new AtlasException(AtlasException.Kind.CONFLICT, ErrorCodes.WORK_RUNNING, running.get());
        }
    }

    /** The library folder of a chosen folder (the browser's rule), or 400 {@code invalid} in the setup screen's words. */
    private SetupService.Place place(String rawPath) {
        Path picked;
        try {
            picked = setup.resolve(rawPath);
        } catch (AtlasException e) {
            throw invalid(e.getMessage());
        }
        SetupService.Place place = SetupService.place(picked, false);
        String words = SetupService.refusal(place);
        if (words != null) {
            throw invalid(words);
        }
        return place;
    }

    private static AtlasException invalid(String words) {
        return new AtlasException(AtlasException.Kind.INVALID, ErrorCodes.INVALID, words);
    }

    /**
     * The refusal as the answer to a move or a switch (contract §3): a move refuses the library itself and a folder inside
     * it in the same words (spec §3).
     */
    private static AtlasException refused(boolean move, String refusal, long free, long needed) {
        return switch (refusal) {
            case EXISTING_LIBRARY -> invalid(EXISTING_WORDS);
            case INSIDE_LIBRARY -> invalid(OUTSIDE_WORDS);
            case CURRENT_LIBRARY -> invalid(move ? OUTSIDE_WORDS : CURRENT_WORDS);
            case ErrorCodes.LIBRARY_IN_USE -> new AtlasException(AtlasException.Kind.CONFLICT, ErrorCodes.LIBRARY_IN_USE,
                    IN_USE_WORDS);
            case ErrorCodes.NOT_ENOUGH_SPACE -> new AtlasException(AtlasException.Kind.CONFLICT,
                    ErrorCodes.NOT_ENOUGH_SPACE, "That drive has " + words(free) + " free; moving your library needs about "
                    + words(needed) + ".");
            default -> throw new IllegalArgumentException(refusal);
        };
    }

    /**
     * This change's refusal of a library folder (contract §3), else null: the library in use itself, a folder inside it;
     * for a move a folder that holds a library, or a drive with too little space; for a switch a library another copy
     * holds (its lock is tried and given back at once).
     */
    private String refusal(boolean move, Path picked, Path folder, String kind, Long free, Long needed) {
        Path current = real(library.get().folder());
        Path target = real(folder);
        if (target.equals(current)) {
            return CURRENT_LIBRARY;
        }
        if (target.startsWith(current) || real(picked).startsWith(current)) {
            return INSIDE_LIBRARY;
        }
        if (move) {
            if (SetupService.EXISTING.equals(kind)) {
                return EXISTING_LIBRARY;
            }
            if (SetupService.CREATE.equals(kind) && free != null && needed != null && free < needed) {
                return ErrorCodes.NOT_ENOUGH_SPACE;
            }
            return null;
        }
        if (SetupService.EXISTING.equals(kind) && heldByAnother(folder)) {
            return ErrorCodes.LIBRARY_IN_USE;
        }
        return null;
    }

    /** Whether another copy of the app holds the library's lock: tried, and given back at once. */
    private static boolean heldByAnother(Path folder) {
        InstanceLock.Result probe = InstanceLock.acquire(folder);
        if (probe.lock() != null) {
            try {
                probe.lock().close();
            } catch (IOException e) {
                LOG.debug("Could not give back the lock of {}", folder, e);
            }
        }
        return probe.state() == InstanceLock.State.HELD;
    }

    private static boolean kind(String kind) {
        if (MOVE.equals(kind)) {
            return true;
        }
        if (SWITCH.equals(kind)) {
            return false;
        }
        throw invalid("Say whether the library is moved or another library is used.");
    }

    private static Path real(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException | RuntimeException e) {
            return path.toAbsolutePath().normalize();
        }
    }

    /** What a move of a library this size needs free on the drive chosen: its size, 5% more and 200 MB (spec §4). */
    static long needed(long size) {
        return Math.round(size * (1 + SPARE_SHARE)) + SPARE_BYTES;
    }

    /** The size of every file in the folder (what cannot be read counts nothing). */
    static long size(Path folder) {
        try (Stream<Path> walk = Files.walk(folder)) {
            return walk.filter(p -> Files.isRegularFile(p, java.nio.file.LinkOption.NOFOLLOW_LINKS)).mapToLong(p -> {
                try {
                    return Files.size(p);
                } catch (IOException e) {
                    return 0;
                }
            }).sum();
        } catch (IOException | java.io.UncheckedIOException e) {
            LOG.debug("Could not add up the size of {}", folder, e);
            return 0;
        }
    }

    /** A size as a drive shows it: megabytes under a gigabyte, else gigabytes, one decimal. */
    static String words(long bytes) {
        if (bytes < 1_000_000_000L) {
            return String.format(Locale.ROOT, "%,.1f MB", bytes / 1e6);
        }
        return String.format(Locale.ROOT, "%,.1f GB", bytes / 1e9);
    }

    /** Whether a change runs (not one that failed and waits to be dismissed): every window is told every second. */
    public boolean running() {
        synchronized (lock) {
            return current != null && !FAILED.equals(current.step());
        }
    }

    /** Whether the app is blocked: a change runs, or a failed one waits to be dismissed (contract §4). */
    public boolean blocks() {
        synchronized (lock) {
            return current != null;
        }
    }

    /**
     * Starts moving the open library to {@code to} (a new or empty folder, already checked): from now on the app is
     * blocked. Answers the change as it starts.
     */
    Changing copyTo(Path to) {
        Path from = library.get().folder();
        synchronized (lock) {
            if (current != null) {
                throw new AtlasException(AtlasException.Kind.CONFLICT, ErrorCodes.LIBRARY_CHANGING,
                        CHANGING_MESSAGE);
            }
            cancelRequested = false;
            current = new Changing(MOVE, to.toString(), COPYING, Count.NONE, Count.NONE, true, null, null, null);
            worker = new Thread(() -> move(from, to), "atlas-library-change");
            worker.setDaemon(true);
            worker.start();
            return current;
        }
    }

    /** How long every window is given to read {@code reopening} before the app quits (contract §2). */
    public static final Duration REOPEN_WAIT = Duration.ofMillis(1500);

    /** What ends this copy of the app after a switch, and how long the windows are given to read how first. */
    public void reopenWith(Relaunch.Reopener reopener, Duration wait) {
        this.reopener = reopener;
        this.reopenWait = wait;
    }

    /** The words of the refusal while a change runs or waits to be dismissed (contract §4). */
    public static final String CHANGING_MESSAGE = "Butterfly Atlas is changing your library folder.";

    /**
     * Cancel (spec §5): the copy stops after the file under way and is deleted; then the app goes on with its library.
     * 409 {@code cannot_cancel} once the change is switching, or when none runs.
     */
    public Changing cancel() {
        synchronized (lock) {
            if (current == null || !current.canCancel()) {
                throw new AtlasException(AtlasException.Kind.CONFLICT, CANNOT_CANCEL, current == null
                        ? "No change of library folder is running." : "The change of library folder can no longer be"
                        + " cancelled.");
            }
            cancelRequested = true;
            current = with(current, CANCELLING, false);
            return current;
        }
    }

    /**
     * Puts away a failed change (or the note of what a cancelled one left): the app goes on with its library. Nothing
     * to put away is fine; 409 {@code cannot_cancel} while a change runs.
     */
    public void dismiss() {
        synchronized (lock) {
            if (current == null) {
                return;
            }
            if (!FAILED.equals(current.step())) {
                throw new AtlasException(AtlasException.Kind.CONFLICT, CANNOT_CANCEL,
                        "Wait for the change of library folder to finish.");
            }
            current = null;
        }
    }

    /** Waits for the change's thread to end (tests); true when it has. */
    boolean await(Duration wait) throws InterruptedException {
        Thread running;
        synchronized (lock) {
            running = worker;
        }
        if (running == null) {
            return true;
        }
        running.join(wait.toMillis());
        return !running.isAlive();
    }

    private void move(Path from, Path to) {
        boolean held = false;
        boolean created = false;
        try {
            background.hold();
            held = true;
            stopIfCancelled();
            try {
                steps.createFolder(to);
                created = true;
                copyFiles(from, to);
            } catch (IOException | RuntimeException e) {
                throw new Failed("Your library couldn't be copied to " + to + ". " + reason(from, to, created, e), e);
            }
            step(COPYING_DATABASE);
            stopIfCancelled();
            try {
                steps.copyDatabase(library.get().database(), to.resolve(Database.FILE_NAME));
            } catch (IOException | RuntimeException e) {
                throw new Failed("Your library's database couldn't be copied to " + to + ". "
                        + reason(from, to, true, e), e);
            }
            step(CHECKING);
            stopIfCancelled();
            check(from, to);
            synchronized (lock) {
                if (cancelRequested) {
                    throw new Cancelled();
                }
                current = with(current, SWITCHING, false);
            }
            try {
                switcher.switchTo(MOVE, from, to);
            } catch (Exception e) {
                throw new Failed("Butterfly Atlas couldn't save where your library is, so it wasn't moved. Check that"
                        + " its settings folder can be written, then try again.", e);
            }
            LOG.info("Your library was copied to {} and checked; Butterfly Atlas switches to it", to);
            reopen(to);
        } catch (Cancelled c) {
            LOG.info("The move of the library to {} was cancelled", to);
            Left left = created || Files.exists(to) ? removeCopy(from, to) : null;
            synchronized (lock) {
                current = left == null ? null : new Changing(MOVE, to.toString(), FAILED, current.files(),
                        current.bytes(), false, null, left, null);
            }
            release(held);
        } catch (Failed f) {
            LOG.warn("Could not move the library to {}: {}", to, f.getMessage(), f.getCause());
            failed(from, to, f.getMessage(), held);
        } catch (RuntimeException | Error e) {
            LOG.error("The move of the library to {} stopped on an unexpected error", to, e);
            failed(from, to, "Your library couldn't be copied to " + to + ". " + SetupService.UNEXPECTED, held);
        }
    }

    /**
     * Use a different library (spec §5): checks again (fixed folder, a change already running, running work, the folder,
     * a library another copy holds) and starts the switch. 409 {@code fixed_folder}, {@code library_changing},
     * {@code work_running} or {@code library_in_use}; 400 {@code invalid} for the folder, in the setup screen's words.
     */
    public Changing switchTo(String rawPath) {
        allowed();
        SetupService.Place place = place(rawPath);
        if (SetupService.NOT_WRITABLE.equals(place.kind())) {
            throw invalid(NOT_WRITABLE_WORDS);
        }
        String refusal = refusal(false, setup.resolve(rawPath), place.folder(), place.kind(), null, null);
        if (refusal != null) {
            throw refused(false, refusal, 0, 0);
        }
        Path from = library.get().folder();
        Path to = place.folder();
        boolean existing = SetupService.EXISTING.equals(place.kind());
        synchronized (lock) {
            if (current != null) {
                throw new AtlasException(AtlasException.Kind.CONFLICT, ErrorCodes.LIBRARY_CHANGING, CHANGING_MESSAGE);
            }
            cancelRequested = false;
            current = new Changing(SWITCH, to.toString(), CHECKING, Count.NONE, Count.NONE, true, null, null, null);
            worker = new Thread(() -> switchLibrary(from, to, existing), "atlas-library-change");
            worker.setDaemon(true);
            worker.start();
            return current;
        }
    }

    /**
     * {@code checking}: an existing library is read to confirm this app can open it (not newer, its place data shipped);
     * a new one's folder is made and must take files (the next start creates the library in it). Then the switch and the
     * reopening. Its unfinished work is cleared by the next start, which opens it.
     */
    private void switchLibrary(Path from, Path to, boolean existing) {
        boolean made = false;
        try {
            if (existing) {
                String why = setup.cannotOpen(to);
                if (why != null) {
                    throw new Failed("Butterfly Atlas can't open the library at " + to + ". " + why,
                            new IllegalStateException(why));
                }
            } else {
                made = !Files.exists(to);
                try {
                    steps.createFolder(to);
                } catch (IOException | RuntimeException e) {
                    throw new Failed("Butterfly Atlas can't make a library at " + to + ". " + reason(from, to, false, e), e);
                }
                if (!SetupService.canWrite(to)) {
                    throw new Failed("Butterfly Atlas can't make a library at " + to + ". " + NOT_WRITABLE_THERE,
                            new IOException("cannot write in " + to));
                }
            }
            synchronized (lock) {
                if (cancelRequested) {
                    throw new Cancelled();
                }
                current = with(current, SWITCHING, false);
            }
            try {
                switcher.switchTo(SWITCH, from, to);
            } catch (Exception e) {
                throw new Failed("Butterfly Atlas couldn't save which library to use, so it still uses this one. Check"
                        + " that its settings folder can be written, then try again.", e);
            }
            LOG.info("Butterfly Atlas switches to the library at {}", to);
            reopen(to);
        } catch (Cancelled c) {
            LOG.info("The switch to the library at {} was cancelled", to);
            removeMadeFolder(made, to);
            synchronized (lock) {
                current = null;
            }
        } catch (Failed f) {
            LOG.warn("Could not switch to the library at {}: {}", to, f.getMessage(), f.getCause());
            removeMadeFolder(made, to);
            synchronized (lock) {
                current = new Changing(SWITCH, to.toString(), FAILED, Count.NONE, Count.NONE, false, f.getMessage(), null,
                        null);
            }
        } catch (RuntimeException | Error e) {
            LOG.error("The switch to the library at {} stopped on an unexpected error", to, e);
            removeMadeFolder(made, to);
            synchronized (lock) {
                current = new Changing(SWITCH, to.toString(), FAILED, Count.NONE, Count.NONE, false,
                        "Butterfly Atlas couldn't switch to the library at " + to + ". " + SetupService.UNEXPECTED, null,
                        null);
            }
        }
    }

    /** A new library's folder this switch made goes again when the switch does not happen (only while it is empty). */
    private void removeMadeFolder(boolean made, Path to) {
        if (made) {
            try {
                steps.delete(to);
            } catch (IOException | RuntimeException e) {
                LOG.warn("Could not remove the folder {} made for the new library", to, e);
            }
        }
    }

    /**
     * {@code reopening} (spec §6, contract §2): every window is told how the app reopens on {@code to}, and given
     * {@link #REOPEN_WAIT} to read it; then the quit begins and the new copy is started. When it can't be, the windows are
     * told the app quits instead, and why, and given the time again; then the exit.
     */
    private void reopen(Path to) {
        Relaunch.Plan plan = reopener.plan();
        set(new Reopen(plan.restarts() ? RESTART : QUIT, to.toString(), plan.why()));
        pause();
        boolean started = reopener.begin(plan);
        if (plan.restarts() && !started) {
            set(new Reopen(QUIT, to.toString(), Relaunch.NOT_STARTED));
            pause();
        }
        reopener.exit();
    }

    /** How the app reopens: it starts itself again, or quits and asks to be opened again. */
    public static final String RESTART = "restart";
    public static final String QUIT = "quit";

    private void set(Reopen reopen) {
        synchronized (lock) {
            current = new Changing(current.kind(), current.to(), REOPENING, current.files(), current.bytes(), false, null,
                    null, reopen);
        }
    }

    private void pause() {
        try {
            Thread.sleep(reopenWait.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void failed(Path from, Path to, String problem, boolean held) {
        Left left = Files.exists(to) ? removeCopy(from, to) : null;
        synchronized (lock) {
            current = new Changing(current.kind(), to.toString(), FAILED, current.files(), current.bytes(), false, problem,
                    left, null);
        }
        release(held);
    }

    private void release(boolean held) {
        if (held) {
            try {
                background.release();
            } catch (RuntimeException e) {
                LOG.warn("Could not let the background work go on after the library change", e);
            }
        }
    }

    private void stopIfCancelled() throws Cancelled {
        synchronized (lock) {
            if (cancelRequested) {
                throw new Cancelled();
            }
        }
    }

    private void step(String step) {
        synchronized (lock) {
            if (!cancelRequested) {
                current = with(current, step, true);
            }
        }
    }

    private static Changing with(Changing c, String step, boolean canCancel) {
        return new Changing(c.kind(), c.to(), step, c.files(), c.bytes(), canCancel, c.problem(), c.left(), c.reopen());
    }

    private void progress(Count files, Count bytes) {
        synchronized (lock) {
            current = new Changing(current.kind(), current.to(), current.step(), files, bytes, current.canCancel(),
                    current.problem(), current.left(), current.reopen());
        }
    }

    /** Step 1: every file but the database's, the lock, {@code .part} files and the decoder's working folder. */
    private void copyFiles(Path from, Path to) throws IOException, Cancelled {
        List<Path> folders = new ArrayList<>();
        List<Item> items = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(from)) {
            for (Path path : (Iterable<Path>) walk::iterator) {
                Path relative = from.relativize(path);
                if (relative.toString().isEmpty() || relative.startsWith(DECODE)) {
                    continue;
                }
                if (Files.isDirectory(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    folders.add(relative);
                } else if (Files.isRegularFile(path, java.nio.file.LinkOption.NOFOLLOW_LINKS) && copied(relative)) {
                    items.add(new Item(relative, Files.size(path)));
                }
            }
        }
        long totalBytes = items.stream().mapToLong(Item::size).sum();
        progress(new Count(0, items.size()), new Count(0, totalBytes));
        for (Path folder : folders) {
            steps.createFolder(to.resolve(folder.toString()));
        }
        long files = 0;
        long bytes = 0;
        for (Item item : items) {
            stopIfCancelled();
            steps.copyFile(from.resolve(item.relative()), to.resolve(item.relative().toString()));
            files++;
            bytes += item.size();
            progress(new Count(files, items.size()), new Count(bytes, totalBytes));
        }
    }

    private static boolean copied(Path relative) {
        String name = relative.getFileName().toString();
        if (name.toLowerCase(Locale.ROOT).endsWith(Library.PART)) {
            return false;
        }
        return relative.getNameCount() > 1 || !NOT_COPIED.contains(name);
    }

    /**
     * Step 3: every copied file has its source's size, the copied database passes {@code integrity_check}, and every
     * live photo's file and every Recently deleted photo's file the old library has is in the new folder.
     */
    private void check(Path from, Path to) throws Failed {
        List<String> wrong = new ArrayList<>();
        Set<String> seen = new java.util.HashSet<>();
        int count = 0;
        try (Stream<Path> walk = Files.walk(from)) {
            for (Path path : (Iterable<Path>) walk::iterator) {
                Path relative = from.relativize(path);
                if (relative.toString().isEmpty() || relative.startsWith(DECODE)
                        || !Files.isRegularFile(path, java.nio.file.LinkOption.NOFOLLOW_LINKS) || !copied(relative)) {
                    continue;
                }
                Path copy = to.resolve(relative.toString());
                if (!Files.isRegularFile(copy) || Files.size(copy) != Files.size(path)) {
                    seen.add(slashes(relative));
                    count++;
                    if (wrong.size() < NAMED) {
                        wrong.add(slashes(relative));
                    }
                }
            }
        } catch (IOException e) {
            throw new Failed("Your library's copy at " + to + " couldn't be checked. " + reason(from, to, true, e), e);
        }
        String integrity = steps.integrity(to.resolve(Database.FILE_NAME));
        if (integrity != null) {
            throw new Failed("The copy of your library's database at " + to + " didn't pass its check. " + CHECK_THERE,
                    new IllegalStateException("integrity_check: " + integrity));
        }
        List<String> photos;
        try {
            photos = photoFiles(to.resolve(Database.FILE_NAME));
        } catch (SQLException e) {
            throw new Failed("The copy of your library's database at " + to + " couldn't be read. " + CHECK_THERE, e);
        }
        for (String relPath : photos) {
            if (Files.exists(from.resolve(relPath)) && !Files.isRegularFile(to.resolve(relPath)) && seen.add(relPath)) {
                count++;
                if (wrong.size() < NAMED) {
                    wrong.add(relPath);
                }
            }
        }
        if (count > 0) {
            throw new Failed("The copy at " + to + " didn't match your library (" + count + (count == 1 ? " file: " : " files: ")
                    + String.join(", ", wrong) + (count > wrong.size() ? ", …" : "") + "). " + CHECK_THERE,
                    new IllegalStateException(count + " files differ, among them " + wrong));
        }
    }

    /** The files of the live photos and of those in Recently deleted, as the copied database names them. */
    private static List<String> photoFiles(Path database) throws SQLException {
        SQLiteConfig config = new SQLiteConfig();
        config.setReadOnly(true);
        List<String> out = new ArrayList<>();
        try (Connection c = config.createConnection("jdbc:sqlite:" + database.toAbsolutePath());
             PreparedStatement ps = c.prepareStatement("SELECT rel_path FROM image WHERE deleted_at IS NULL"
                     + " UNION ALL SELECT deleted_rel_path FROM image WHERE deleted_rel_path IS NOT NULL");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(rs.getString(1));
            }
        }
        return out;
    }

    private static String slashes(Path relative) {
        return relative.toString().replace('\\', '/');
    }

    /**
     * Deletes the copy: the new library folder and everything in it, nothing else (spec §7). Null when it is all gone,
     * else the folder and how many files are still in it.
     */
    private Left removeCopy(Path from, Path to) {
        if (from.startsWith(to) || to.startsWith(from)) {
            LOG.error("Not removing {}: it holds the library or is inside it", to);
            return new Left(to.toString(), files(to));
        }
        List<Path> paths;
        try (Stream<Path> walk = Files.walk(to)) {
            paths = walk.sorted(Comparator.reverseOrder()).toList();
        } catch (IOException e) {
            LOG.warn("Could not list the copy at {} to remove it", to, e);
            return Files.exists(to) ? new Left(to.toString(), files(to)) : null;
        }
        for (Path path : paths) {
            try {
                steps.delete(path);
            } catch (IOException | RuntimeException e) {
                LOG.warn("Could not remove {} of the copy", path, e);
            }
        }
        if (!Files.exists(to)) {
            return null;
        }
        int left = files(to);
        LOG.warn("{} file(s) of the copy at {} couldn't be removed", left, to);
        return new Left(to.toString(), left);
    }

    private static int files(Path folder) {
        try (Stream<Path> walk = Files.walk(folder)) {
            return (int) walk.filter(Files::isRegularFile).count();
        } catch (IOException e) {
            return 0;
        }
    }

    /**
     * Why a step failed, in plain words (spec §7): the drive chosen full or gone, the folder chosen not writable, a file
     * of the library that couldn't be read, another program holding a file, else {@link SetupService#plain}'s words.
     * {@code created}: the new library folder was made, so its disappearance means its drive went.
     */
    String reason(Path from, Path to, boolean created, Throwable e) {
        Path there = created ? to : nearestExisting(to);
        if (there == null || !Files.isDirectory(there)) {
            return GONE_THERE;
        }
        if (usableSpace.applyAsLong(there) < LibraryDrive.FULL_BELOW) {
            return FULL_THERE;
        }
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof AccessDeniedException denied && denied.getFile() != null) {
                Path file = Path.of(denied.getFile());
                if (file.startsWith(from)) {
                    return "A file in your library couldn't be read (" + slashes(from.relativize(file))
                            + "). Check the drive your library is on, then try again.";
                }
                return NOT_WRITABLE_THERE;
            }
            if (t instanceof FileSystemException fs && fs.getReason() != null
                    && fs.getReason().toLowerCase(Locale.ROOT).contains("used by another process")) {
                return SetupService.LOCKED;
            }
        }
        String plain = SetupService.plain(there, e, usableSpace);
        if (plain.equals(SetupService.FULL)) {
            return FULL_THERE;
        }
        if (plain.equals(SetupService.GONE)) {
            return GONE_THERE;
        }
        return plain;
    }

    private static Path nearestExisting(Path path) {
        for (Path p = path; p != null; p = p.getParent()) {
            if (Files.isDirectory(p)) {
                return p;
            }
        }
        return null;
    }
}
