package com.butterflyatlas.app.updates;

import com.butterflyatlas.app.AtlasException;
import com.butterflyatlas.app.ErrorCodes;
import com.butterflyatlas.app.JobStates;
import com.butterflyatlas.app.backup.Backups;
import com.butterflyatlas.app.db.ChangeLog;
import com.butterflyatlas.app.db.Database;
import com.butterflyatlas.app.exports.Exports;
import com.butterflyatlas.app.imports.Imports;
import com.butterflyatlas.app.library.Library;
import com.butterflyatlas.app.library.LibraryDrive;
import com.butterflyatlas.app.photos.SpeciesFiles;
import com.butterflyatlas.app.taxonomy.TaxonomyBundles;
import com.butterflyatlas.app.updates.UpdateViews.Notice;
import com.butterflyatlas.app.updates.UpdateViews.Progress;
import com.butterflyatlas.app.updates.UpdateViews.Result;
import com.butterflyatlas.app.updates.UpdateViews.Status;
import com.butterflyatlas.app.updates.UpdateViews.Summary;
import com.butterflyatlas.app.updates.UpdateViews.Update;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sqlite.SQLiteErrorCode;
import org.sqlite.SQLiteException;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import java.util.function.ToLongFunction;

/**
 * Taxonomy updates (5b contract): what the HTTP layer calls. At startup the library's version is compared with the
 * shipped bundles ({@link PendingUpdate}); Apply runs the whole chain in the background on one thread
 * ({@code atlas-taxonomy}), one update at a time, each step through plan, moves and switch-over. What {@code /api/status}
 * shows is kept in memory, so a poll runs no SQL.
 */
public final class Updates implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(Updates.class);

    /** How long closing waits for the file being moved. */
    static final Duration CLOSE_WAIT = Duration.ofSeconds(5);
    /** How often a waiting update looks again whether the import or export it waits for is over. */
    static final Duration WAIT_STEP = Duration.ofMillis(100);

    /** The change log's kind for Home's "The taxonomy was updated" card dismissed: {@code {"update": id}}. */
    static final String DISMISSED = "taxonomy.dismissed";

    public static final String NONE = "none";
    public static final String PENDING = JobStates.PENDING;
    public static final String WAITING = JobStates.WAITING;
    public static final String IMPORT = "import";
    public static final String EXPORT = "export";
    public static final String BACKUP = "backup";
    /** A backup of Everything paused before it is finished: it holds them (multi-window contract §10, A7). */
    public static final String BACKUP_PAUSED = "backup_paused";
    /** What an update waits for while the startup's rescan, catch-up and purge still run. */
    public static final String STARTUP = "startup";
    /** The pause of an unfinished update whose bundle is not shipped with this copy. */
    static final String NEEDS_NEWER = "needs_newer";

    /** What an update must wait for before it moves anything: an import filing, an export or a backup copying, or nothing (null). */
    @FunctionalInterface
    interface Busy {
        String waitingFor();
    }

    /** The update in hand (running, waiting, paused, or done in this run), as memory has it (guarded by lock). */
    private static final class Current {
        final long id;
        final String kind;
        final String from;
        final String to;
        final List<String> steps;
        String step;
        String stage;
        String state;
        int done;
        int total;
        Notice notice;
        boolean continuing;
        String waitingFor;
        Result result;
        String resultProblem;
        /** Home's card for this update, once done, was dismissed (recorded in the change log). */
        boolean dismissed;

        Current(UpdateStore.Row row, List<String> steps) {
            this.id = row.id();
            this.kind = row.kind();
            this.from = row.from();
            this.to = row.to();
            this.steps = row.match() ? List.of(row.step()) : steps;
            take(row);
        }

        void take(UpdateStore.Row row) {
            step = row.step();
            stage = row.stage();
            state = row.state();
            done = row.done();
            total = row.total();
            notice = row.noticeCode() == null ? null : new Notice(row.noticeCode(), row.notice());
        }

        int stepNumber() {
            int at = steps.indexOf(step);
            return at < 0 ? steps.size() : at + 1;
        }
    }

    private final Supplier<Library> library;
    private final TaxonomyBundles bundles;
    private final Clock clock;
    private final Busy busy;
    private final UpdateMoves.Mover mover;
    private final ToLongFunction<Path> usableSpace;

    private final Object lock = new Object();
    /**
     * Held by Apply, Retry and Merge for their whole start (one at a time), while {@code lock} is only taken briefly: a
     * start waiting for the gate to close never holds up {@code /api/status} or {@code GET /api/update}.
     */
    private final Object starting = new Object();
    private PendingUpdate pending;
    private Summed summary;
    /** The most recent finished update's or merge's result (read at startup, then kept by each finish). */
    private Result last;
    /** Why {@code last} could not be read at startup (5b contract §7: never only logged), or null. */
    private String lastProblem;
    private boolean later;
    private Current current;
    private Thread worker;
    private boolean closed;
    /** Whether a file the computer will not let go is one open in another program (Windows; tests choose). */
    volatile boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
    /** Runs first on the worker (tests hold a run back before it does anything). */
    volatile Runnable beforeWork = () -> { };
    /** Run once an update or a merge has finished (on its worker): the startup's skipped purge, for one. */
    private final List<Runnable> whenFinished = new CopyOnWriteArrayList<>();
    /** Runs on the worker as it ends, before it gives its place up (tests widen that window). */
    volatile Runnable beforeEnd = () -> { };
    /** Runs on the worker once an update is done, before the worker ends (tests widen that window). */
    volatile Runnable afterDone = () -> { };
    /**
     * False while the startup's photo-file steps (rescan, catch-up, purge, sweep) run: an update asked for meanwhile
     * waits for them ({@code waitingFor} "startup"), except the startup's own continuation, which runs before them.
     */
    private volatile boolean filesSettled = true;
    /** {@code /api/status}'s counts, recounted by the writes that can change them ({@link #recount}). */
    private volatile int splitsToSort;
    private volatile int customMatches;

    /**
     * An update waits while an import is filing, an export is copying, a backup is copying, or a backup of Everything is
     * paused before it is finished.
     */
    public Updates(Supplier<Library> library, TaxonomyBundles bundles, Imports imports, Exports exports, Backups backups,
                   Clock clock) {
        this(library, bundles, clock, () -> imports.filing() ? IMPORT : exports.copying() ? EXPORT
                        : backups.copying() ? BACKUP : backups.photosHeldBy().isPresent() ? BACKUP_PAUSED : null,
                SpeciesFiles::moveFile, LibraryDrive::usableSpace);
    }

    /** With nothing to wait for (tests). */
    Updates(Supplier<Library> library, TaxonomyBundles bundles, Clock clock) {
        this(library, bundles, clock, () -> null, SpeciesFiles::moveFile, LibraryDrive::usableSpace);
    }

    /** With the file moves and the free space given, nothing to wait for (tests). */
    Updates(Supplier<Library> library, TaxonomyBundles bundles, Clock clock, UpdateMoves.Mover mover,
            ToLongFunction<Path> usableSpace) {
        this(library, bundles, clock, () -> null, mover, usableSpace);
    }

    /** With what to wait for, the file moves and the free space given (tests). */
    Updates(Supplier<Library> library, TaxonomyBundles bundles, Clock clock, Busy busy, UpdateMoves.Mover mover,
            ToLongFunction<Path> usableSpace) {
        this.library = library;
        this.bundles = bundles;
        this.clock = clock;
        this.busy = busy;
        this.mover = mover;
        this.usableSpace = usableSpace;
    }

    private Database db() {
        return library.get().database();
    }

    /**
     * Once the library is open, before the startup's rescan: an update the app's closing cut short (a close leaves it
     * running) continues at once ({@code continuing} until it finishes or pauses; {@link #awaitContinuation}); a paused
     * one waits for Retry. Otherwise: whether an update is pending (or unavailable).
     */
    public void startup() {
        Library lib = library.get();
        Database db = lib.database();
        readLast(db);
        Optional<UpdateStore.Row> unfinished = db.read(UpdateStore::unfinished);
        if (unfinished.isPresent()) {
            lib.gate().close(); // nothing that moves photos may start before it is done (nothing runs yet at startup)
            UpdateStore.Row row = unfinished.get();
            boolean resume = UpdateStore.RUNNING.equals(row.state());
            lib.gate().paused(!resume);
            synchronized (lock) {
                pending = null;
                summary = null;
                current = new Current(row, chain(row.from(), row.to()));
                if (resume) {
                    current.state = UpdateStore.RUNNING;
                    current.notice = null;
                    current.continuing = true;
                    LOG.info("Finishing the taxonomy update cut short when Butterfly Atlas closed");
                    start(row.id(), false);
                }
            }
            return;
        }
        PendingUpdate found = PendingUpdate.of(db, bundles);
        synchronized (lock) {
            pending = found;
            summary = null;
        }
        recount();
    }

    /** The most recent finished update's or merge's result, from memory. */
    private Result lastResult() {
        synchronized (lock) {
            return last;
        }
    }

    /** Why the most recent result could not be read, from memory. */
    private String lastProblem() {
        synchronized (lock) {
            return lastProblem;
        }
    }

    /** Reads the most recent finished update's or merge's result into memory (it survives a restart in change_log). */
    private void readLast(Database db) {
        try {
            Result found = db.read(Results::last).orElse(null);
            synchronized (lock) {
                last = found;
                lastProblem = null;
            }
        } catch (RuntimeException e) {
            LOG.warn("Could not read the last taxonomy update's result", e);
            synchronized (lock) {
                last = null;
                lastProblem = problem("The last update's summary of what changed couldn't be read", e);
            }
        }
    }

    /** Holds updates back until {@link #startupFilesSettled} (called before the startup work begins). */
    public void holdForStartup() {
        filesSettled = false;
    }

    /** The startup's photo-file steps are over (or failed): an update waiting for them may run. */
    public void startupFilesSettled() {
        synchronized (lock) {
            filesSettled = true;
            lock.notifyAll();
        }
    }

    /**
     * Dismiss on Home's "The taxonomy was updated" card: recorded in the library's change log for the update or merge
     * done in this run, and said by the status from then on, so a page reload keeps the card away; the next finish
     * shows it again. Nothing is recorded when nothing is done.
     */
    public void dismiss() {
        long id;
        synchronized (lock) {
            if (current == null || !UpdateStore.DONE.equals(current.state)) {
                return;
            }
            id = current.id;
        }
        db().write(c -> {
            ChangeLog.record(c, clock.instant(), DISMISSED, ChangeLog.payload("update", id));
            return null;
        });
        synchronized (lock) {
            if (current != null && current.id == id) {
                current.dismissed = true;
            }
        }
    }

    /** Later: the update screen does not open by itself again in this run (remembered in memory only). */
    public void later() {
        synchronized (lock) {
            later = true;
        }
    }

    /**
     * Counts again the split species with photos to sort and the open custom matches, for {@code /api/status} (called
     * by the writes that can change them: an update, a sort, a merge or decline, a photo moved, deleted, restored or
     * purged, a custom species changed, the startup's rescan).
     */
    public void recount() {
        try {
            int[] counts = db().read(c -> new int[] {Splits.count(c), CustomMatches.count(c)});
            splitsToSort = counts[0];
            customMatches = counts[1];
        } catch (RuntimeException e) {
            LOG.warn("Could not count the splits to sort and the custom matches", e);
        }
    }

    /** Split species with live photos still to sort, from memory. */
    public int splitsToSort() {
        return splitsToSort;
    }

    /** Open custom-species matches, from memory. */
    public int customMatches() {
        return customMatches;
    }

    /**
     * Waits while the startup's continuation runs (until it is done or paused, or the app closes): the startup's rescan
     * and the other photo-file steps come after it.
     */
    public void awaitContinuation() {
        synchronized (lock) {
            while (worker != null && current != null && current.continuing) {
                try {
                    lock.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    /**
     * The update screen's answer (contract §5 Update); a pending update's summary is worked out once and kept until the
     * library changes (one that could not be worked out is tried again on the next read).
     */
    public Update update() {
        PendingUpdate p;
        synchronized (lock) {
            if (current != null && !doneWithAnUpdatePending()) {
                return view(current);
            }
            p = pending;
        }
        if (p == null) {
            return new Update(NONE, null, false, null, null, List.of(), null, null, null, null, null, null, lastResult(),
                    lastProblem());
        }
        return switch (p.state()) {
            case NONE -> new Update(NONE, null, laterChosen(), p.from(), p.to(), List.of(), null, null, null, null, null, null,
                    lastResult(), lastProblem());
            case UNAVAILABLE -> new Update(PendingUpdate.UNAVAILABLE, null, laterChosen(), p.from(), p.from(), List.of(), null,
                    null, null, new Notice(PendingUpdate.UNAVAILABLE, p.notice()), null, null, lastResult(), lastProblem());
            case PENDING -> {
                Summed summed = summary(p);
                yield new Update(PENDING, null, laterChosen(), p.from(), p.to(), p.versions(), summed.summary(), summed.problem(),
                        null, null, null, null, lastResult(), lastProblem());
            }
        };
    }

    private boolean laterChosen() {
        synchronized (lock) {
            return later;
        }
    }

    /** A pending update's summary, or why it could not be worked out. */
    private record Summed(Summary summary, String problem) {
    }

    /**
     * The summary of a pending update, worked out once and kept; a dry run that fails never fails the answer: the summary
     * is null and {@code summaryProblem} says so (5b contract §7), and it is not kept: the next read tries again.
     */
    private Summed summary(PendingUpdate p) {
        synchronized (lock) {
            if (summary != null && pending == p) {
                return summary;
            }
        }
        Summed s;
        try {
            s = new Summed(p.summary(db()), null);
        } catch (RuntimeException e) {
            LOG.warn("The summary of the taxonomy update could not be worked out", e);
            String drive = driveWords(e);
            s = new Summed(null, drive != null ? "The list of changes couldn't be worked out. " + drive
                    : "The list of changes couldn't be worked out (" + detail(e) + "). You can still apply the update, or"
                    + " choose Later.");
        }
        synchronized (lock) {
            if (pending == p && s.problem() == null) {
                summary = s;
            }
        }
        return s;
    }

    private Update view(Current c) {
        boolean finished = UpdateStore.DONE.equals(c.state);
        Progress progress = finished ? null : new Progress(c.stepNumber(), c.steps.size(), c.stage, c.done, c.total,
                c.continuing, c.waitingFor);
        Notice notice = UpdateStore.PAUSED.equals(c.state) ? c.notice : null;
        return new Update(c.state, c.kind, later, c.from, c.to, c.steps, null, null, progress, notice,
                finished ? c.result : null, finished ? c.resultProblem : null, last, lastProblem);
    }

    /**
     * A merge (or an update) done in this run while a newer bundle is still pending (Later, then a merge from Home): the
     * pending update is answered, with the merge's result in {@code last} (guarded by lock).
     */
    private boolean doneWithAnUpdatePending() {
        return UpdateStore.DONE.equals(current.state) && pending != null && pending.state() == PendingUpdate.State.PENDING;
    }

    /**
     * {@code /api/status}'s {@code taxonomyUpdate}, from memory; empty when nothing is pending, running, paused or
     * done.
     */
    public Optional<Status> status() {
        synchronized (lock) {
            if (current != null && !doneWithAnUpdatePending()) {
                Current c = current;
                return Optional.of(new Status(c.state, c.kind, later, c.done, c.total, c.stepNumber(), c.steps.size(),
                        c.continuing, c.waitingFor, UpdateStore.PAUSED.equals(c.state) ? c.notice : null, c.dismissed));
            }
            if (pending == null || pending.state() == PendingUpdate.State.NONE) {
                return Optional.empty();
            }
            boolean unavailable = pending.state() == PendingUpdate.State.UNAVAILABLE;
            return Optional.of(new Status(unavailable ? PendingUpdate.UNAVAILABLE : PENDING, null, later, 0, 0, 0,
                    pending.steps().size(), false, null,
                    unavailable ? new Notice(PendingUpdate.UNAVAILABLE, pending.notice()) : null, false));
        }
    }

    /**
     * Applies the pending chain in the background: 202 with the update. 409 {@code update_running} while one is running,
     * waiting or paused; 409 {@code no_update} when nothing is pending. The gate is closed before the answer: from then
     * on the changes that move photos are refused.
     */
    public Update apply() {
        synchronized (starting) {
            PendingUpdate p;
            synchronized (lock) {
                if (current != null && !UpdateStore.DONE.equals(current.state)) {
                    throw updateRunning();
                }
                if (pending == null || pending.state() != PendingUpdate.State.PENDING) {
                    throw new AtlasException(AtlasException.Kind.CONFLICT, "no_update", "There is no taxonomy update to apply.");
                }
                p = pending;
            }
            Instant now = clock.instant();
            UpdateStore.Row row = closed("Apply now", () -> db().write(c -> {
                if (UpdateStore.unfinished(c).isPresent()) {
                    throw updateRunning();
                }
                long id = UpdateStore.create(c, UpdateStore.BUNDLE, p.from(), p.to(), p.versions().getFirst(), null, null, now);
                return UpdateStore.get(c, id);
            }));
            synchronized (lock) {
                current = new Current(row, p.versions());
                start(row.id(), false);
                return view(current);
            }
        }
    }

    /**
     * Runs a paused update again from where it is (the startup's rules): 202 with the update; 409 {@code not_paused}
     * unless it is paused. After {@code missing}, a photo still gone from its old and new folder is left behind (the next
     * start's rescan drops its record).
     */
    public Update retry() {
        synchronized (starting) {
            long id;
            boolean carryOnMissing;
            synchronized (lock) {
                if (current == null || !UpdateStore.PAUSED.equals(current.state)) {
                    throw new AtlasException(AtlasException.Kind.CONFLICT, "not_paused", "The taxonomy update is not paused.");
                }
                id = current.id;
                if (worker != null) { // the run that paused is still ending
                    throw notResumed("the last attempt is still ending", "Wait a moment, then choose Retry.");
                }
                carryOnMissing = current.notice != null && UpdateMoves.MISSING.equals(current.notice.code());
            }
            try {
                db().write(c -> {
                    UpdateStore.resume(c, id);
                    return null;
                });
            } catch (RuntimeException e) {
                if (fullNow(e)) {
                    throw driveFull("Retry", e); // still paused
                }
                LOG.warn("Could not record that taxonomy update {} is running again", id, e);
                if (!UpdateMoves.reachable(library.get().folder())) {
                    throw notResumed("the drive with your library still can't be found", "Reconnect it, then choose Retry.");
                }
                throw notResumed("the library couldn't be written (" + detail(e) + ")", "Choose Retry again.");
            }
            synchronized (lock) {
                if (current == null || current.id != id) {
                    throw notResumed("another update is in hand now", "Open Taxonomy update to see it.");
                }
                current.state = UpdateStore.RUNNING;
                current.notice = null;
                start(id, carryOnMissing);
                return view(current);
            }
        }
    }

    /** The open custom-species matches (contract §5 Match), by the custom species' name. */
    public List<UpdateViews.Match> matches() {
        return db().read(CustomMatches::open);
    }

    /**
     * Merges a custom species into the official species it now matches, as a {@code match} update (plan, moves,
     * switch-over, the same pause and Retry): 202 with the update. 404 {@code not_found} when either species is not there
     * (the official one active); 409 {@code no_match} when their match is not open; 409 {@code taxonomy_updating} while
     * an update runs, waits or is paused.
     */
    public Update merge(String customId, String officialId) {
        synchronized (starting) {
            Library lib = library.get();
            lib.gate().check();
            synchronized (lock) {
                if (current != null && !UpdateStore.DONE.equals(current.state)) {
                    throw updateRunning();
                }
            }
            Instant now = clock.instant();
            UpdateStore.Row row = closed("Merge", () -> lib.database().write(c -> {
                requireSpecies(c, customId, "manual");
                if (CustomMatches.isOpen(c, customId, officialId)) {
                    requireOnTheList(c, officialId);
                }
                requireSpecies(c, officialId, "official");
                if (!CustomMatches.isOpen(c, customId, officialId)) {
                    throw new AtlasException(AtlasException.Kind.CONFLICT, "no_match", customId + " is not offered for merging"
                            + " into " + officialId + ".");
                }
                if (UpdateStore.unfinished(c).isPresent()) {
                    throw updateRunning();
                }
                String version = PendingUpdate.libraryVersion(c);
                long id = UpdateStore.create(c, UpdateStore.MATCH, version, version, version, customId, officialId, now);
                return UpdateStore.get(c, id);
            }));
            synchronized (lock) {
                current = new Current(row, List.of());
                start(row.id(), false);
                return view(current);
            }
        }
    }

    /**
     * The gate closed (changes under way finish first, new ones are refused) before {@code start} records an update;
     * opened again when it fails and the gate was open before. A full drive is 409 {@code drive_full} ({@code again}:
     * the action to choose once space is freed).
     */
    private UpdateStore.Row closed(String again, Supplier<UpdateStore.Row> start) {
        Library lib = library.get();
        boolean open = !lib.gate().isClosed();
        lib.gate().close();
        try {
            return start.get();
        } catch (RuntimeException | Error e) {
            if (open) {
                lib.gate().open();
            }
            if (fullNow(e)) {
                throw driveFull(again, e);
            }
            throw e;
        }
    }

    /** 409 {@code update_running}: "A taxonomy update is already running." */
    private static AtlasException updateRunning() {
        return new AtlasException(AtlasException.Kind.CONFLICT, "update_running", "A taxonomy update is already running.");
    }

    /** 409 {@code not_resumed}: "Tried again, but <why>. <what to do>." (5b contract §7); still paused. */
    private static AtlasException notResumed(String why, String next) {
        return new AtlasException(AtlasException.Kind.CONFLICT, "not_resumed", "Tried again, but " + why + ". " + next);
    }

    /** A failure's own words, else plain words that send the reader to the log (never a class name). */
    static String detail(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        if (root.getMessage() == null || root.getMessage().isBlank()) {
            LOG.warn("A failure with no message of its own", e);
            return UpdateMoves.UNEXPECTED;
        }
        return root.getMessage();
    }

    /** Whether a failure is the drive being full: SQLite's {@code SQLITE_FULL}, or no space left for a file. */
    static boolean full(Throwable e) {
        Throwable t = e;
        for (int depth = 0; t != null && depth < 20; depth++, t = t.getCause()) { // bounded: a cause chain may loop
            if (t instanceof SQLiteException sqlite && sqlite.getResultCode() == SQLiteErrorCode.SQLITE_FULL) {
                return true;
            }
            String message = t.getMessage() == null ? "" : t.getMessage().toLowerCase(Locale.ROOT);
            if (message.contains("sqlite_full") || message.contains("database or disk is full")
                    || message.contains("no space left") || message.contains("not enough space on the disk")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether a failure is the drive being full: as {@link #full}, or one of SQLite's I/O errors with under 1 MiB usable
     * (a drive filled to the last byte leaves SQLite unable to map its shared memory: {@code SQLITE_IOERR_SHMOPEN}).
     */
    private boolean fullNow(Throwable e) {
        return full(e) || (LibraryDrive.sqliteIoError(e)
                && usableSpace.applyAsLong(library.get().folder()) < LibraryDrive.FULL_BELOW);
    }

    /**
     * The drive's own words when a storage failure is the drive being full or gone (never SQLite's text then), else
     * null.
     */
    private String driveWords(Throwable e) {
        if (!LibraryDrive.storageFailure(e)) {
            return null;
        }
        if (fullNow(e)) {
            return LibraryDrive.FULL.message();
        }
        LibraryDrive.Problem problem = LibraryDrive.check(library.get().folder(), usableSpace);
        return problem == null ? null : problem.message();
    }

    /** "<what> (<detail>)." or, when the drive is full or gone, "<what>. <the drive's words>". */
    private String problem(String what, Throwable e) {
        String drive = driveWords(e);
        return drive != null ? what + ". " + drive : what + " (" + detail(e) + ").";
    }

    /** 409 {@code drive_full}: nothing was started or changed. */
    private static AtlasException driveFull(String again, Throwable e) {
        LOG.warn("The library's drive is full", e);
        return new AtlasException(AtlasException.Kind.CONFLICT, ErrorCodes.DRIVE_FULL, "The drive with your library is full."
                + " Free some space, then choose " + again + " again.");
    }

    /**
     * Not now: the match is declined, and offered again after the next update if it still matches; 404 when not open,
     * 409 {@code taxonomy_updating} while that custom species' merge is unfinished.
     */
    public void decline(String customId, String officialId) {
        Instant now = clock.instant();
        boolean declined = db().write(c -> {
            Optional<UpdateStore.Row> unfinished = UpdateStore.unfinished(c);
            if (unfinished.isPresent() && unfinished.get().match() && customId.equals(unfinished.get().customId())) {
                throw library.get().gate().refusal();
            }
            if (CustomMatches.isOpen(c, customId, officialId)) {
                requireOnTheList(c, officialId);
            }
            return CustomMatches.decide(c, customId, officialId, CustomMatches.DECLINED, now);
        });
        if (!declined) {
            throw AtlasException.notFound("There is no open match of " + customId + " with " + officialId + ".");
        }
        recount();
    }

    /**
     * 404 {@code match_withdrawn} when the official species of an open match is no longer on the list ("Neptis sankara is
     * no longer on the official list, so this match was withdrawn.").
     */
    private static void requireOnTheList(Connection c, String officialId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT name, active FROM taxon WHERE id = ?")) {
            ps.setString(1, officialId);
            try (ResultSet rs = ps.executeQuery()) {
                boolean found = rs.next();
                if (!found || rs.getInt(2) != 1) {
                    String name = found ? rs.getString(1) : officialId;
                    throw new AtlasException(AtlasException.Kind.NOT_FOUND, "match_withdrawn", name + " is no longer on the"
                            + " official list, so this match was withdrawn.");
                }
            }
        }
    }

    /** 404 unless {@code id} is a species of that source (an official one active). */
    private static void requireSpecies(Connection c, String id, String source) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM taxon WHERE id = ? AND rank = 'species' AND source = ?"
                + " AND (source = 'manual' OR active = 1)")) {
            ps.setString(1, id);
            ps.setString(2, source);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw AtlasException.notFound("There is no " + ("manual".equals(source) ? "custom " : "") + "species " + id
                            + " in your atlas.");
                }
            }
        }
    }

    /** Runs the update on the worker thread unless it is running already (guarded by lock). */
    private void start(long id, boolean carryOnMissing) {
        if (closed || worker != null) {
            return;
        }
        library.get().gate().paused(false);
        worker = new Thread(() -> work(id, carryOnMissing), "atlas-taxonomy");
        worker.setDaemon(true);
        worker.start();
    }

    private boolean stopping() {
        synchronized (lock) {
            return closed;
        }
    }

    /**
     * The update from where it is to its end, a pause, or the app closing. First the gate is closed (from now on the
     * changes that move photos are refused; those under way finish), then the update waits while an import is filing or
     * an export is copying.
     */
    private void work(long id, boolean carryOnMissing) {
        boolean skipMissing = carryOnMissing;
        try {
            beforeWork.run();
            Library lib = library.get();
            lib.gate().close();
            if (!waitTurn(id)) {
                return;
            }
            Database db = lib.database();
            UpdateMoves moves = new UpdateMoves(db, lib.folder(), mover, usableSpace, windows, clock);
            while (!stopping()) {
                UpdateStore.Row row = db.read(c -> UpdateStore.get(c, id));
                if (UpdateStore.DONE.equals(row.state())) {
                    finished(row);
                    afterDone.run();
                    return;
                }
                switch (row.stage()) {
                    case UpdateStore.PLAN -> {
                        if (!plan(db, lib.folder(), row)) {
                            return;
                        }
                    }
                    case UpdateStore.MOVES -> {
                        UpdateMoves.Outcome outcome = moves.run(id, row.step(), skipMissing, this::stopping, this::moved);
                        skipMissing = false;
                        if (!outcome.done()) {
                            if (outcome.code() != null) {
                                pause(id, outcome.code(), outcome.message());
                            }
                            return;
                        }
                        db.write(c -> {
                            UpdateStore.stage(c, id, UpdateStore.SWITCH, row.step());
                            return null;
                        });
                        took(db, id);
                    }
                    default -> switchOver(db, lib.folder(), row);
                }
            }
        } catch (RuntimeException | Error e) {
            LOG.error("The taxonomy update stopped after an error", e);
            if (e instanceof NeedsNewer) {
                pause(id, NEEDS_NEWER, "This update needs a newer version of Butterfly Atlas. Install it, then open the library"
                        + " again.");
            } else if (!UpdateMoves.reachable(library.get().folder())) {
                pause(id, ErrorCodes.DRIVE_GONE, UpdateMoves.driveGone()); // the database went with it
            } else if (fullNow(e)) {
                pause(id, ErrorCodes.DRIVE_FULL, UpdateMoves.driveFull()); // the plan, a batch or the switch-over
            } else {
                pause(id, UpdateMoves.FAILED, UpdateMoves.failed(e instanceof Exception ex ? ex : new RuntimeException(e)));
            }
        } finally {
            beforeEnd.run();
            synchronized (lock) {
                // A run that is done or paused gave its place up already: a new run may be going by now.
                if (worker == Thread.currentThread()) {
                    worker = null;
                    if (current != null) {
                        current.continuing = false;
                    }
                }
                lock.notifyAll();
            }
        }
    }

    /** The worker's place is given up (guarded by lock): a new Apply, Retry or Merge starts its own run at once. */
    private void release() {
        if (worker == Thread.currentThread()) {
            worker = null;
            lock.notifyAll();
        }
    }

    /**
     * Waits while the startup's photo-file steps run (not the startup's own continuation), an import files or an export or
     * a backup copies ({@code waiting}); false when the app closes meanwhile.
     */
    private boolean waitTurn(long id) {
        while (true) {
            boolean continuing;
            synchronized (lock) {
                continuing = current != null && current.id == id && current.continuing;
            }
            String waitingFor = !filesSettled && !continuing ? STARTUP : busy.waitingFor();
            synchronized (lock) {
                if (closed) {
                    return false;
                }
                if (current != null && current.id == id) {
                    current.state = waitingFor == null ? UpdateStore.RUNNING : WAITING;
                    current.waitingFor = waitingFor;
                }
                if (waitingFor == null) {
                    return true;
                }
                try {
                    lock.wait(WAIT_STEP.toMillis());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
    }

    /** The plan of the step; false when a taken target paused it (nothing written but the pause). */
    private boolean plan(Database db, Path data, UpdateStore.Row row) {
        UpdatePlanner.Plan plan = db.write(c -> {
            int seq = UpdateStore.nextSeq(c, row.id(), row.step());
            UpdatePlanner.Plan p = row.match() ? MatchMerge.plan(c, data, row, seq)
                    : UpdatePlanner.plan(c, data, row.id(), shipped(row.step()).bundle(), seq);
            if (p.conflict() != null) {
                UpdateStore.pause(c, row.id(), UpdateMoves.TARGET_EXISTS, p.conflict().message());
            } else {
                UpdateStore.addTotal(c, row.id(), p.moves().size());
                UpdateStore.stage(c, row.id(), UpdateStore.MOVES, row.step());
            }
            return p;
        });
        UpdateStore.Row after = db.read(c -> UpdateStore.get(c, row.id()));
        synchronized (lock) {
            if (current != null && current.id == row.id()) {
                current.take(after);
                if (plan.conflict() != null) {
                    current.continuing = false;
                }
            }
            if (plan.conflict() != null) {
                library.get().gate().paused(true);
                release(); // seen paused and the place given up together: a Retry starts its own run at once
            }
        }
        return plan.conflict() == null;
    }

    /**
     * The switch-over of the step, then the next step's plan or, after the last, the custom matches offered and done; a
     * match's switch-over, then done.
     */
    private void switchOver(Database db, Path data, UpdateStore.Row row) {
        if (row.match()) {
            Instant now = clock.instant();
            db.write(c -> {
                MatchMerge.Merged merged = MatchMerge.switchOver(c, row, now);
                Folders.Removed folders = Folders.removeEmpty(data, merged.oldFolders(), livePaths(c));
                UpdateStore.done(c, row.id(), now);
                ChangeLog.record(c, now, MatchMerge.LOGGED, Results.payload(ChangeLog.payload("customId", row.customId(),
                        "officialId", row.officialId(), "name", merged.name()), row, merged.photos(),
                        Results.moved(c, row.id()), folders,
                        Results.leftBehind(c, row.id()), List.of(), List.of(), CustomMatches.open(c)));
                return null;
            });
            took(db, row.id());
            return;
        }
        TaxonomyBundles.Shipped shipped = shipped(row.step());
        List<String> steps = chain(row.from(), row.to());
        int at = steps.indexOf(row.step());
        String next = at >= 0 && at + 1 < steps.size() ? steps.get(at + 1) : null;
        Instant now = clock.instant();
        db.write(c -> {
            SwitchOver.Switched s = SwitchOver.apply(c, row.id(), shipped.bundle(), shipped.sha256(), now);
            // The folders the step's photos left go now: only empty ones are removed, so if the write does not commit
            // nothing is lost (a folder is made again when a photo needs it).
            Folders.Removed folders = Folders.removeEmpty(data, s.oldFolders(), livePaths(c));
            ChangeLog.record(c, now, SwitchOver.STEP, ChangeLog.payload("from", s.from(), "to", row.step(), "update", row.id(),
                    "keptFolders", folders.kept(), "notRemoved", folders.notRemoved()));
            if (next != null) {
                UpdateStore.stage(c, row.id(), UpdateStore.PLAN, next);
            } else {
                CustomMatches.offer(c, row.to(), now);
                UpdateStore.done(c, row.id(), now);
                List<UpdateViews.SplitBrief> splits = Splits.briefs(c).stream()
                        .filter(b -> b.version().compareTo(row.from()) > 0 && b.version().compareTo(row.to()) <= 0).toList();
                ChangeLog.record(c, now, SwitchOver.DONE, Results.payload(Map.of(), row, Results.photos(c, row),
                        Results.moved(c, row.id()), Results.stepFolders(c, row.id()), Results.leftBehind(c, row.id()), splits,
                        Results.splitsMerged(c, row.id()), CustomMatches.open(c)));
            }
            return null;
        });
        took(db, row.id());
    }

    /** The live photos' paths (the folders in use). */
    private static List<String> livePaths(Connection c) throws SQLException {
        List<String> paths = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT rel_path FROM image WHERE deleted_at IS NULL");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                paths.add(rs.getString(1));
            }
        }
        return paths;
    }

    /**
     * Done: the result, and the library's pending update afresh. The state {@code done}, the result and the gate opened
     * are seen together, and the worker gives its place up at the same moment.
     */
    private void finished(UpdateStore.Row row) {
        Database db = db();
        Result result = null;
        String resultProblem = null;
        try {
            result = db.read(c -> Results.of(c, row.id())).orElse(null);
        } catch (RuntimeException e) {
            // the update is done all the same: the gate opens, and the answer says the result could not be read
            LOG.warn("Could not read the result of taxonomy update {}", row.id(), e);
            resultProblem = problem("The update finished, but its summary of what changed couldn't be read", e);
        }
        PendingUpdate afresh = null;
        try {
            afresh = PendingUpdate.of(db, bundles);
        } catch (RuntimeException e) {
            LOG.warn("Could not tell whether another taxonomy update is pending", e);
        }
        recount();
        // The gate opens first, then the work that waited for the update (the startup's purge), then done is seen.
        library.get().gate().open();
        for (Runnable work : whenFinished) {
            try {
                work.run();
            } catch (RuntimeException e) {
                LOG.warn("Work waiting for the taxonomy update failed", e);
            }
        }
        synchronized (lock) {
            library.get().gate().open();
            if (current != null && current.id == row.id()) {
                current.take(row);
                current.continuing = false;
                current.waitingFor = null;
                current.result = result;
                current.resultProblem = resultProblem;
            }
            if (result != null) {
                last = result;
                lastProblem = null;
            }
            pending = afresh;
            summary = null;
            release();
        }
    }

    /** Runs {@code work} after each update or merge has finished, the gate open again (5b contract §7). */
    public void whenFinished(Runnable work) {
        whenFinished.add(work);
    }

    /**
     * The library changed (a photo added, filed, moved, deleted, restored or purged, a split sort, a custom species, the
     * startup's rescan): a pending update's summary is worked out again when asked, and the counts are recounted.
     */
    public void libraryChanged() {
        synchronized (lock) {
            summary = null;
        }
        recount();
    }

    /** The update's row into memory; {@code done} only with its result ({@link #finished}). */
    private void took(Database db, long id) {
        UpdateStore.Row row = db.read(c -> UpdateStore.get(c, id));
        synchronized (lock) {
            if (current != null && current.id == id) {
                String state = current.state;
                current.take(row);
                if (UpdateStore.DONE.equals(row.state())) {
                    current.state = state;
                }
            }
        }
    }

    /** A batch of moves recorded. */
    private void moved(int moves) {
        synchronized (lock) {
            if (current != null) {
                current.done += moves;
            }
        }
    }

    /** Paused with a notice: recorded, and in memory even when the record cannot be written (the drive is gone). */
    private void pause(long id, String code, String message) {
        LOG.info("Taxonomy update {} paused: {}", id, code);
        try {
            db().write(c -> {
                UpdateStore.pause(c, id, code, message);
                return null;
            });
        } catch (RuntimeException e) {
            LOG.warn("Could not record that taxonomy update {} paused", id, e);
        }
        synchronized (lock) {
            if (current != null && current.id == id) {
                current.state = UpdateStore.PAUSED;
                current.notice = new Notice(code, message);
                current.continuing = false;
                current.waitingFor = null;
            }
            library.get().gate().paused(true);
            release();
        }
    }

    private TaxonomyBundles.Shipped shipped(String version) {
        return bundles.byVersion(version).orElseThrow(() -> new NeedsNewer(version));
    }

    /** An unfinished update whose bundle is not shipped with this copy (the library was opened by a newer one). */
    private static final class NeedsNewer extends RuntimeException {
        NeedsNewer(String version) {
            super("The taxonomy bundle " + version + " is not in this copy of Butterfly Atlas");
        }
    }

    /** The versions from the one after {@code from} to {@code to}. */
    private List<String> chain(String from, String to) {
        List<String> steps = new ArrayList<>();
        for (String version : PendingUpdate.of(from, bundles).versions()) {
            steps.add(version);
            if (version.equals(to)) {
                break;
            }
        }
        return steps;
    }

    /** Waits until no update work runs (tests); true if that happened within {@code timeout}. */
    public boolean awaitIdle(Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        synchronized (lock) {
            while (worker != null) {
                long left = deadline - System.nanoTime();
                if (left <= 0) {
                    return false;
                }
                lock.wait(Math.max(1, left / 1_000_000));
            }
            return true;
        }
    }

    /** The app is quitting: the moves stop after the current file (their progress saved), nothing more starts. */
    public void beginClose() {
        synchronized (lock) {
            closed = true;
            lock.notifyAll();
        }
    }

    /**
     * As {@link #beginClose}, waiting a bounded time for the file being moved; the update stays running for the next
     * start.
     */
    @Override
    public void close() {
        Thread thread;
        synchronized (lock) {
            closed = true;
            thread = worker;
        }
        if (thread != null && thread != Thread.currentThread()) {
            try {
                thread.join(CLOSE_WAIT.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (thread.isAlive()) {
                LOG.warn("A taxonomy update was still moving a photo when Butterfly Atlas closed");
            }
        }
    }
}
