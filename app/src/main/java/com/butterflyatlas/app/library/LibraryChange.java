package com.butterflyatlas.app.library;

import com.butterflyatlas.app.AtlasException;
import com.butterflyatlas.app.ErrorCodes;
import com.butterflyatlas.app.db.Database;
import com.butterflyatlas.app.window.InstanceLock;
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
    }

    /** The switch (spec §5 step 4): the settings saved with the new library folder; throws when they can't be. */
    @FunctionalInterface
    public interface Switcher {
        void switchTo(String kind, Path from, Path to) throws Exception;
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
    private final ChangeSteps steps;
    private final ToLongFunction<Path> usableSpace;
    private final Background background;
    private final Switcher switcher;
    private final Object lock = new Object();
    /** What every window is shown; null when no change runs or waits to be dismissed. */
    private Changing current;
    private boolean cancelRequested;
    private Thread worker;

    public LibraryChange(Supplier<Library> library, ChangeSteps steps, ToLongFunction<Path> usableSpace,
                         Background background, Switcher switcher) {
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
