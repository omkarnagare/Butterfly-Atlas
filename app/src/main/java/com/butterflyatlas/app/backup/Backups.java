package com.butterflyatlas.app.backup;

import com.butterflyatlas.app.AtlasException;
import com.butterflyatlas.app.ErrorCodes;
import com.butterflyatlas.app.exports.ExportCleanup;
import com.butterflyatlas.app.exports.ExportCopier;
import com.butterflyatlas.app.exports.ExportErrors;
import com.butterflyatlas.app.exports.ExportJobs;
import com.butterflyatlas.app.exports.ExportNames;
import com.butterflyatlas.app.exports.ExportStart;
import com.butterflyatlas.app.exports.ExportStore;
import com.butterflyatlas.app.exports.Exports;
import com.butterflyatlas.app.library.Library;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.function.ToLongFunction;

/**
 * Backups (Phase 6 contract §3–§5): what the HTTP layer calls. The reminder preference and the last backup; the check
 * behind the sheet's space line; one backup at a time (running or paused), the new folder made atomically
 * ({@code Butterfly_Atlas_backup_<date>}, then {@code _2}, {@code _3}, …, nothing overwritten); the copy in the background
 * ({@link ExportJobs}, {@link BackupCopier}) paused, resumed and cancelled here, as an export is. Nothing copies by itself
 * after a restart: a backup the app's closing cut short is paused (closed, {@link #startup}). The Export screen's machinery
 * is shared where it fits (the job runner, the cleanup, the refusals' words); a backup's own words are here.
 */
public final class Backups implements AutoCloseable {

    /** How long a cancel waits for the current file. */
    static final Duration CANCEL_WAIT = Duration.ofSeconds(60);
    /** How long a start waits for a backup of the database alone, which is quick, so the answer can say it is done. */
    static final Duration QUICK_WAIT = Duration.ofSeconds(10);
    /** The problems a detail answer lists at most. */
    static final int MAX_PROBLEMS = 1000;

    private static final Logger LOG = LoggerFactory.getLogger(Backups.class);

    public static final String DATABASE = "database";
    public static final String EVERYTHING = "everything";
    /** The pause reason of a backup the app's closing stopped. */
    static final String CLOSED = "closed";

    static final String RUNNING = "The backup in progress has to finish or be cancelled first.";
    static final String FINISHED = "This backup has already finished.";
    static final String NOT_FINISHED = "Finish or cancel the backup first.";
    static final String CHECK_RUNNING = "Finish or cancel the backup in progress first.";
    static final String NO_SPACE = "There isn't enough space: %s needed, %s free.";
    /** The refusal of a photo change while a backup of Everything is copying (multi-window contract §10 A6). */
    public static final String HOLDS_RUNNING = "A backup is copying your photos, so photos can't be changed right now."
            + " Wait for it to finish, or cancel it, change your photos, then start a new backup.";
    /**
     * The same while it is paused (at any stage), stopped by the app closing: in the words of the buttons
     * the card then shows, Continue backup and Discard… (§10 A6, §11 A22).
     */
    public static final String HOLDS_CLOSED = "A paused backup still holds your photos, so they can't be changed yet."
            + " Continue the backup and let it finish, or discard it, change your photos, then start a new backup.";
    /** The same, paused any other way (the user, the drive, write errors): the card's buttons are Resume and Cancel…. */
    public static final String HOLDS_PAUSED = "A paused backup still holds your photos, so they can't be changed yet."
            + " Resume the backup and let it finish, or cancel it, change your photos, then start a new backup.";
    /** A start or a resume while an import files (multi-window design §5 G1b). */
    public static final String IMPORT_FILING = "An import is filing photos. Wait for filing to finish, or pause it, then start"
            + " the backup.";

    /** A start: what to back up and the folder to put it in. */
    public record Request(String what, String path) {
    }

    /** Why a backup cannot start: a code and its plain words. */
    public record Refusal(String code, String message) {
    }

    /** The sheet's space line (contract §4 check). */
    public record Check(int photos, long bytes, long neededBytes, long freeBytes, String name, boolean sameDrive,
                        Refusal problem) {
    }

    /** One backup as the UI shows it (contract §4 Backup). */
    public record Backup(long id, String state, String what, String folder, String path, int total, int done, int copied,
                         int skipped, int failed, long bytesTotal, long bytesDone, String pausedBy, String notice,
                         String cancel, int notRemoved, String startedAt, String finishedAt) {

        static Backup of(BackupStore.Run run) {
            return new Backup(run.id(), run.state(), run.what(), run.folder(), run.folderPath().toString(), run.total(),
                    run.done(), run.copied(), run.skipped(), run.failed(), run.bytesTotal(), run.bytesDone(), run.pausedBy(),
                    run.notice(), run.cancel(), run.notRemoved(), run.startedAt(), run.finishedAt());
        }
    }

    /** What {@code GET /api/backup} answers (contract §3). */
    public record Listing(boolean asked, String preference, String lastAt, String lastWhat, int lastUnread,
                          Integer daysSince, boolean due, Backup current, List<Backup> past) {
    }

    /** One backup with its skipped and failed photos ({@link #MAX_PROBLEMS} at most) and how many more. */
    public record Detail(Backup backup, List<BackupStore.Problem> problems, int moreProblems) {
    }

    /**
     * The running or paused backup, for {@code /api/status}; {@code holdsPhotos}: photos can't be changed while it is
     * unfinished ({@link #photosHeldBy}).
     */
    public record Status(long id, String state, int done, int total, String pausedBy, String notice, String path,
                         boolean holdsPhotos) {
    }

    /** {@code /api/status}'s backup: the running or paused one, or null. */
    public record StatusPart(Status backup) {
    }

    /** What a check found; {@code dest} is the chosen folder once it is known to be one. */
    private record Plan(Path dest, String name, int photos, long photoBytes, long databaseBytes, long needed, long free,
                        boolean sameDrive, Refusal problem) {
    }

    private final Supplier<Library> library;
    private final BackupStore store;
    private final BackupSettings settings;
    private final BackupCopier copier;
    private final ExportJobs jobs;
    private final Clock clock;
    private final ZoneId zone;
    private final ToLongFunction<Path> usableSpace;
    /** How long a resume or cancel waits for a stopping copy (tests shorten it). */
    volatile Duration stopWait = CANCEL_WAIT;
    /** Guards the one-at-a-time check and the start, so two starts never both begin. */
    private final Object startLock = new Object();
    /** Whether an import is filing (a backup neither starts nor resumes meanwhile). */
    private volatile java.util.function.BooleanSupplier importFiling = () -> false;

    public Backups(Supplier<Library> library, Clock clock) {
        this(library, clock, ZoneId.systemDefault(), Exports::usableSpace);
    }

    /** With the time zone of the backup folder's date and the free space given (tests). */
    Backups(Supplier<Library> library, Clock clock, ZoneId zone, ToLongFunction<Path> usableSpace) {
        this.library = library;
        this.clock = clock;
        this.zone = zone;
        this.usableSpace = usableSpace;
        this.store = new BackupStore(() -> library.get().database(), clock);
        this.settings = new BackupSettings(() -> library.get().database());
        this.copier = new BackupCopier(library, store, usableSpace);
        // a copy that stops on an unexpected error is paused like failed writes: resumable, never left "running"
        this.jobs = new ExportJobs(copier, id -> store.pause(id, ErrorCodes.WRITE_ERRORS, BackupCopier.WRITE_ERRORS_NOTICE),
                "atlas-backup");
    }

    // ---- the reminder preference and the list ----

    /** The preference, the last backup, whether Home reminds, the backup running or paused, and the past ones. */
    public Listing list() {
        BackupSettings.Values values = settings.read();
        BackupStore.History history = store.history();
        Instant now = clock.instant();
        return new Listing(values.asked(), values.preference(), values.lastAt() == null ? null : values.lastAt().toString(),
                values.lastWhat(), values.lastUnread(), BackupSettings.daysSince(values.lastAt(), now),
                BackupSettings.due(values.preference(), values.lastAt(), now),
                history.current() == null ? null : Backup.of(history.current()),
                history.past().stream().map(Backup::of).toList());
    }

    /** Sets the preference (400 {@code bad_preference} for any other value) and answers the list. */
    public Listing setPreference(String preference) {
        settings.setPreference(preference);
        return list();
    }

    // ---- the check and the start ----

    /** The check behind the sheet's space line: read-only. */
    public Check check(String what, String path) {
        Plan plan = plan(what(what), path);
        return new Check(plan.photos(), plan.photoBytes() + plan.databaseBytes(), plan.needed(), plan.free(), plan.name(),
                plan.sameDrive(), plan.problem());
    }

    /**
     * Starts a backup: refused (409 {@code backup_running}) while one is running or paused, (409 {@code taxonomy_updating})
     * while a taxonomy update runs, waits or is paused (its photos are moving), (409 {@code import_filing}) while an import
     * files, and with the check's problem otherwise. It claims the photos ({@link com.butterflyatlas.app.library.PhotoHold}),
     * so a photo change under way finishes first. One of the database alone is quick and is waited for a little, so the
     * answer may already say done.
     */
    public Backup start(Request request) {
        String what = what(request.what());
        long id;
        synchronized (startLock) {
            Library held = library.get();
            id = held.gate().whileOpen(() -> held.photoHold().claim(() -> {
                refuseWhileFiling();
                if (store.current().isPresent()) {
                    throw running(RUNNING);
                }
                Plan plan = plan(what, request.path());
                if (plan.problem() != null) {
                    throw ExportErrors.refused(plan.problem().code(), plan.problem().message());
                }
                Path folder = createFolder(plan.dest(), ExportNames.numbered("Butterfly_Atlas_backup_" + today(), 1));
                long run;
                try {
                    boolean photos = EVERYTHING.equals(what);
                    run = store.create(new BackupStore.NewRun(what, plan.dest().toString(), folder.getFileName().toString(),
                            plan.photos(), plan.databaseBytes() + (photos ? plan.photoBytes() : 0)));
                } catch (RuntimeException e) {
                    deleteQuietly(folder);
                    throw e;
                }
                jobs.run(run); // recorded running: the copy is asked for before anything else can fail
                return run;
            }));
        }
        if (DATABASE.equals(what)) {
            try {
                jobs.awaitIdle(QUICK_WAIT);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        return Backup.of(store.run(id));
    }

    private static String what(String what) {
        if (!DATABASE.equals(what) && !EVERYTHING.equals(what)) {
            throw ExportErrors.refused(ErrorCodes.INVALID, "Unknown backup “" + (what == null ? "" : what) + "”.");
        }
        return what;
    }

    private LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), zone);
    }

    /**
     * The refusals in order, then the free space, the name the backup folder would have and the path length. For a backup of
     * everything the photos are counted (live ones only: Recently deleted is not included).
     */
    private Plan plan(String what, String rawPath) {
        Library lib = library.get();
        long databaseBytes = size(lib.database().file());
        int photos = 0;
        long photoBytes = 0;
        int longest = 0;
        if (EVERYTHING.equals(what)) {
            long[] totals = lib.database().read(c -> {
                try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*), COALESCE(SUM(file_size), 0),"
                        + " COALESCE(MAX(LENGTH(rel_path)), 0) FROM image WHERE deleted_at IS NULL");
                     ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return new long[] {rs.getLong(1), rs.getLong(2), rs.getLong(3)};
                }
            });
            photos = (int) totals[0];
            photoBytes = totals[1];
            longest = (int) totals[2];
        }
        long needed = photoBytes + 2 * databaseBytes;
        if (store.current().isPresent()) {
            return refused(photos, photoBytes, databaseBytes, needed, "backup_running", CHECK_RUNNING);
        }
        Path folder = path(rawPath);
        if (folder == null) {
            return refused(photos, photoBytes, databaseBytes, needed, ErrorCodes.INVALID, ExportErrors.NO_PATH);
        }
        if (!Files.exists(folder)) {
            return refused(photos, photoBytes, databaseBytes, needed, ErrorCodes.NOT_FOUND, ExportErrors.NOT_FOUND);
        }
        if (!Files.isDirectory(folder)) {
            return refused(photos, photoBytes, databaseBytes, needed, ErrorCodes.NOT_FOLDER, ExportErrors.NOT_FOLDER);
        }
        long free = usableSpace.applyAsLong(folder);
        boolean same = sameDrive(folder, lib.folder());
        String name = null;
        Refusal problem = null;
        if (ExportStart.isInside(folder, lib.folder())) {
            problem = new Refusal(ErrorCodes.LIBRARY_FOLDER, ExportErrors.LIBRARY_FOLDER);
        } else if (!ExportStart.writable(folder)) {
            problem = new Refusal(ErrorCodes.NOT_WRITABLE, ExportErrors.NOT_WRITABLE);
        } else {
            name = ExportNames.firstFree("Butterfly_Atlas_backup_" + today(), ExportStart.entries(folder));
            int path = folder.resolve(name).toString().length() + 1 + Math.max(longest, BackupCopier.DATABASE.length())
                    + ExportNames.PART.length();
            if (path > ExportNames.MAX_PATH) {
                problem = new Refusal(ErrorCodes.PATH_TOO_LONG, ExportErrors.PATH_TOO_LONG_FLAT);
            } else if (free < needed) {
                problem = new Refusal(ErrorCodes.NO_SPACE, String.format(NO_SPACE, ExportErrors.size(needed), ExportErrors.size(free)));
            }
        }
        return new Plan(folder, name, photos, photoBytes, databaseBytes, needed, free, same, problem);
    }

    private Plan refused(int photos, long photoBytes, long databaseBytes, long needed, String code, String message) {
        return new Plan(null, null, photos, photoBytes, databaseBytes, needed, 0, false, new Refusal(code, message));
    }

    private static long size(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            return 0;
        }
    }

    /** Whether the folder is on the drive the library is on (a note, not a refusal: it will not survive that drive failing). */
    private static boolean sameDrive(Path folder, Path library) {
        try {
            return Files.getFileStore(folder).equals(Files.getFileStore(library));
        } catch (IOException | RuntimeException e) {
            LOG.debug("Could not tell whether {} is on the library's drive", folder, e);
            return false;
        }
    }

    /** The chosen folder as an absolute, normalised path; null when none is given (or it is not a full path). */
    private static Path path(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            return null;
        }
        try {
            Path path = Path.of(rawPath.strip());
            return path.isAbsolute() ? path.normalize() : null;
        } catch (InvalidPathException e) {
            return null;
        }
    }

    /** Creates the backup folder atomically: the first numbered name not taken (without case) that it can create. */
    private static Path createFolder(Path dest, String base) {
        Set<String> taken = ExportStart.entries(dest);
        for (int n = 1; n <= 10_000; n++) {
            String name = ExportNames.numbered(base, n);
            if (taken.contains(ExportNames.lower(name))) {
                continue;
            }
            try {
                return Files.createDirectory(dest.resolve(name));
            } catch (FileAlreadyExistsException e) {
                taken.add(ExportNames.lower(name)); // made meanwhile: take the next
            } catch (NoSuchFileException e) {
                throw ExportErrors.refused(ErrorCodes.NOT_FOUND, ExportErrors.NOT_FOUND);
            } catch (IOException e) {
                LOG.info("Could not create the backup folder {}: {}", name, e.toString());
                throw ExportErrors.refused(ErrorCodes.NOT_WRITABLE, ExportErrors.NOT_WRITABLE);
            }
        }
        throw ExportErrors.refused(ErrorCodes.NOT_WRITABLE, ExportErrors.NOT_WRITABLE);
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            LOG.warn("Could not remove {}", file, e);
        }
    }

    // ---- the running backup, its actions and the history ----

    private static AtlasException running(String message) {
        return new AtlasException(AtlasException.Kind.CONFLICT, "backup_running", message);
    }

    private static AtlasException finished() {
        return new AtlasException(AtlasException.Kind.CONFLICT, "backup_finished", FINISHED);
    }

    /** Whether a backup is copying or about to. */
    public boolean copying() {
        return jobs.busy();
    }

    /** The backup running or paused, if any. */
    public Optional<Backup> current() {
        return store.current().map(Backup::of);
    }

    /** One backup with its problems, from one state of the library; 404 {@code not_found} when there is none. */
    public Detail detail(long id) {
        BackupStore.Details details = store.details(id, MAX_PROBLEMS);
        return new Detail(Backup.of(details.run()), details.problems(),
                Math.max(0, details.count() - details.problems().size()));
    }

    /** {@code /api/status}'s {@code backup} part if the runs are in memory, else empty: never reads (a status poll). */
    public Optional<StatusPart> statusKept() {
        return store.currentKept().map(current -> new StatusPart(current.map(Backups::status).orElse(null)));
    }

    /** {@code /api/status}'s {@code backup}: the running or paused one (reading it when it is not in memory yet). */
    public Optional<Status> status() {
        return store.current().map(Backups::status);
    }

    private static Status status(BackupStore.Run r) {
        return new Status(r.id(), r.state(), r.done(), r.total(), r.pausedBy(), r.notice(), r.folderPath().toString(),
                holdsPhotos(r));
    }

    /**
     * The words that refuse a photo change while a backup holds the photos, or empty (multi-window contract §4, audit A6):
     * a backup of Everything that is running, or paused before it is finished. Its database is copied last to
     * match the photos it copied, so a photo changed meanwhile would be named in a backup that lacks it. From memory once
     * the library's runs are read; the library's {@link com.butterflyatlas.app.library.PhotoHold} asks it.
     */
    public Optional<String> photoRefusal() {
        return store.current().filter(Backups::holdsPhotos)
                .map(r -> BackupStore.RUNNING.equals(r.state()) ? HOLDS_RUNNING
                        : CLOSED.equals(r.pausedBy()) ? HOLDS_CLOSED : HOLDS_PAUSED);
    }

    /** What tells whether an import is filing: while one is, a backup neither starts nor resumes (409 import_filing). */
    public void importFiling(java.util.function.BooleanSupplier filing) {
        this.importFiling = filing;
    }

    private void refuseWhileFiling() {
        if (importFiling.getAsBoolean()) {
            throw new AtlasException(AtlasException.Kind.CONFLICT, ErrorCodes.IMPORT_FILING, IMPORT_FILING);
        }
    }

    /** The state of the backup that holds the photos ({@code running} or {@code paused}); see {@link #photoRefusal}. */
    public Optional<String> photosHeldBy() {
        return store.current().filter(Backups::holdsPhotos).map(BackupStore.Run::state);
    }

    private static boolean holdsPhotos(BackupStore.Run r) {
        // paused at any stage: one paused at its database (write errors, a full drive) still copies it to match its photos
        return EVERYTHING.equals(r.what()) && (BackupStore.RUNNING.equals(r.state()) || BackupStore.PAUSED.equals(r.state()));
    }

    /** Removes a done or cancelled backup from the history; its files are never touched. 409 while it runs or is paused. */
    public void delete(long id) {
        BackupStore.Run run = store.run(id);
        if (!run.finished()) {
            throw running(NOT_FINISHED);
        }
        if (!store.delete(id)) {
            store.run(id); // removed meanwhile (Clear history): 404
            throw running(NOT_FINISHED);
        }
    }

    /** Clear history: removes every done and cancelled backup's record (never files); returns how many. */
    public int clear() {
        return store.deleteFinished();
    }

    /** Pauses after the current file (the answer may still say running until it stops). 409 once done or cancelled. */
    public Backup pause(long id) {
        BackupStore.Run run = unfinished(id);
        if (BackupStore.RUNNING.equals(run.state()) && !jobs.stop(id, ExportJobs.Stop.PAUSE)) {
            store.pause(id, "user", null); // not copying (held at startup): paused at once
        }
        return Backup.of(store.run(id));
    }

    /**
     * Resumes a paused backup, which goes on after the last photo it settled. While its folder cannot be reached it stays
     * paused ({@code drive}). 409 {@code backup_finished} once done or cancelled; 409 {@code taxonomy_updating} while a
     * taxonomy update runs, waits or is paused; 409 {@code import_filing} while an import files.
     */
    public Backup resume(long id) {
        synchronized (startLock) {
            Library lib = library.get();
            lib.gate().check();
            BackupStore.Run run = unfinished(id);
            if (BackupStore.RUNNING.equals(run.state()) && jobs.keepGoing(id)) {
                return Backup.of(run); // copying (a pause not yet taken effect is taken back)
            }
            if (!jobs.awaitStopped(id, stopWait)) {
                throw AtlasException.busy(ExportErrors.STILL_FINISHING);
            }
            return lib.gate().whileOpen(() -> lib.photoHold().claim(() -> {
                refuseWhileFiling();
                return resumed(id);
            }));
        }
    }

    private Backup resumed(long id) {
        BackupStore.Run run = unfinished(id);
        if (BackupStore.PAUSED.equals(run.state())) {
            store.resume(id);
        }
        if (!ExportCleanup.exportFolder(run.folderPath())) {
            store.pause(id, "drive", BackupCopier.DRIVE_GONE_NOTICE);
        } else {
            jobs.run(id);
        }
        return Backup.of(store.run(id));
    }

    /**
     * Cancels a running or paused backup after the current file, keeping what was copied or removing the files this backup
     * wrote and that are unchanged ({@link ExportCleanup}; never through a link). 409 {@code backup_finished} once done or
     * cancelled; 503 when the current file outlasts the wait.
     */
    public Backup cancel(long id, boolean remove) {
        synchronized (startLock) {
            unfinished(id);
            jobs.stop(id, ExportJobs.Stop.CANCEL);
            if (!jobs.awaitStopped(id, stopWait)) {
                throw AtlasException.busy(ExportErrors.STILL_FINISHING);
            }
            BackupStore.Run run = unfinished(id); // it may have finished meanwhile: nothing is removed then
            List<ExportStore.Written> written = store.writtenFiles(id);
            List<String> targets = written.stream().map(ExportStore.Written::targetRel).toList();
            int notRemoved = 0;
            if (remove) {
                notRemoved = ExportCleanup.remove(run.folderPath(), written, targets, List.of(), null);
                removeEmptyFolders(run.folderPath()); // the folders made for photos that failed or were still to copy
            } else {
                ExportCleanup.removeParts(run.folderPath(), targets); // the copies stay, never a .part of ours
            }
            if (!store.cancel(id, remove ? "remove" : "keep", notRemoved)) {
                throw finished();
            }
            return Backup.of(store.run(id));
        }
    }

    /**
     * After Remove: the empty folders left in the backup folder (made for photos that failed to write or were still to be
     * copied, which are in no record), deepest first, then the backup folder itself if it is empty. A link is never followed
     * or deleted; a folder that holds anything stays.
     */
    private static void removeEmptyFolders(Path folder) {
        if (!ExportCleanup.exportFolder(folder)) {
            return;
        }
        List<Path> folders;
        try (java.util.stream.Stream<Path> all = Files.walk(folder)) {
            folders = all.filter(p -> Files.isDirectory(p, java.nio.file.LinkOption.NOFOLLOW_LINKS))
                    .sorted(java.util.Comparator.comparingInt(Path::getNameCount).reversed()).toList();
        } catch (IOException | RuntimeException e) {
            LOG.warn("Could not look for empty folders in {}", folder, e);
            return;
        }
        for (Path p : folders) {
            try {
                Files.delete(p); // only an empty folder goes
            } catch (IOException e) {
                LOG.debug("{} stays: {}", p, e.toString());
            }
        }
    }

    /** The backup, or 404; 409 {@code backup_finished} when it is done or cancelled. */
    private BackupStore.Run unfinished(long id) {
        BackupStore.Run run = store.run(id);
        if (run.finished()) {
            throw finished();
        }
        return run;
    }

    // ---- start and close of the app ----

    /**
     * At startup, before any backup copies: one still marked running was cut short by the app closing and becomes paused
     * ({@code closed}); it continues only when the user resumes it. Returns how many.
     */
    public int startup() {
        try {
            int stopped = store.markClosed();
            if (stopped > 0) {
                LOG.info("A backup was stopped when Butterfly Atlas closed; it carries on when resumed");
            }
            return stopped;
        } catch (RuntimeException e) {
            LOG.warn("Could not check backups at startup", e);
            return 0;
        }
    }

    /** Holds copying back until {@link #startupFilesSettled()} (called before the startup work begins). */
    public void holdForStartup() {
        jobs.hold();
    }

    /** The startup's rescan, purge and sweep are done (or failed): backups may copy. */
    public void startupFilesSettled() {
        jobs.release();
    }

    /** The app is quitting: the copy stops after the current file, saving its progress; {@link #close} follows. */
    public void beginClose() {
        jobs.beginClose();
    }

    @Override
    public void close() {
        jobs.close();
    }

    // ---- test hooks ----

    /** Runs before each photo is copied, with its id (tests). */
    void beforePhoto(Consumer<Long> hook) {
        copier.beforePhoto = hook;
    }

    /** How hard links are made (tests make them unsupported). */
    void linker(ExportCopier.Linker linker) {
        copier.linker = linker;
    }

    /** How {@code .part} files are opened (tests make writes fail). */
    void opener(BackupCopier.Opener opener) {
        copier.opener = opener;
    }

    /** Runs once a photo's {@code .part} is written (tests). */
    void afterWrite(Consumer<Path> hook) {
        copier.afterWrite = hook;
    }

    /** Runs once the database's copy is in place, before it is read back (tests). */
    void afterDatabase(Consumer<Path> hook) {
        copier.afterDatabase = hook;
    }

    /** After how many photos progress is saved (tests). */
    void saveEvery(int photos) {
        copier.saveEvery = photos;
    }

    /** Whether the backup's copy has been asked to stop and not yet stopped (tests). */
    boolean stopAsked(long id) {
        return jobs.stopAsked(id);
    }

    /** Waits until no backup copies (tests). */
    boolean awaitIdle(Duration timeout) throws InterruptedException {
        return jobs.awaitIdle(timeout);
    }

    BackupStore store() {
        return store;
    }
}
