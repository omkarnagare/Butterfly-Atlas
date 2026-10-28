package com.butterflyatlas.app.exports;

import com.butterflyatlas.app.AtlasException;
import com.butterflyatlas.app.ErrorCodes;
import com.butterflyatlas.app.filters.Filter;
import com.butterflyatlas.app.filters.FilterQuery;
import com.butterflyatlas.app.library.Library;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.function.ToLongFunction;

/**
 * Exports (4b contract): what the HTTP layer calls. It keeps the rules: one export at a time (running or paused), the
 * checks before anything is written, and the start's folder, CSV and records made in one go ({@link ExportStart}); the
 * copy runs in the background ({@link ExportJobs}, {@link ExportCopier}) and is paused, resumed and cancelled here.
 * Nothing copies by itself after a restart: an export the app's closing cut short is paused ({@link #startup}).
 */
public final class Exports implements AutoCloseable {

    /** How long a cancel waits for the current file. */
    static final Duration CANCEL_WAIT = Duration.ofSeconds(60);
    /** The problems a detail answer lists at most. */
    static final int MAX_PROBLEMS = 1000;

    private static final Logger LOG = LoggerFactory.getLogger(Exports.class);

    public static final String PHOTOS = "photos";
    public static final String CSV = "csv";
    public static final String BOTH = "both";
    public static final String SPECIES = "species";
    public static final String FLAT = "flat";
    /** Places in the list, by depth: none, country, state, district. */
    public static final List<String> DEPTHS = List.of("none", "country", "state", "district");

    /**
     * A start: the filter's query string and the sheet's choices (null: the default); {@code rawWithLocation} that the
     * user accepts RAW and HEIC photos going out exactly, with any location they hold (7a contract §5, Phase 7b contract
     * §6; null: false); {@code acceptedKinds} which of them the tick the user saw named ({@code raw}, {@code heic}; audit
     * 17; null: those the check shows now, for older pages).
     */
    public record Request(String filter, String what, String path, String layout, Boolean stripLocation, String placeDepth,
                          Boolean rawWithLocation, List<String> acceptedKinds) {

        public Request(String filter, String what, String path, String layout, Boolean stripLocation, String placeDepth) {
            this(filter, what, path, layout, stripLocation, placeDepth, null, null);
        }

        public Request(String filter, String what, String path, String layout, Boolean stripLocation, String placeDepth,
                       Boolean rawWithLocation) {
            this(filter, what, path, layout, stripLocation, placeDepth, rawWithLocation, null);
        }
    }

    /** Why an export cannot start: a code and its plain words. */
    public record Refusal(String code, String message) {
    }

    /**
     * The sheet's space line (contract §2 Check); {@code raw}: the RAW photos among them (7a contract §5); {@code heic}:
     * the HEIC photos (Phase 7b contract §6).
     */
    public record Check(int photos, int species, long bytes, long neededBytes, long freeBytes, String name,
                        Refusal problem, Raw raw, Raw heic) {
    }

    /**
     * The RAW (or HEIC) photos of an export, how many of them have a GPS position, and how many could not be checked
     * for one (their stored details could not be read; fixes J7).
     */
    public record Raw(int photos, int withGps, int withUnknown) {
    }

    /** One export as the UI shows it (contract §2 Export). */
    public record Export(long id, String state, String what, String layout, boolean stripLocation, String placeDepth,
                         String filter, String path, String csv, int species, int total, int done, int copied, int skipped,
                         int failed, int renamed, long bytesTotal, long bytesDone, String pausedBy, String notice,
                         String cancel, int notRemoved, String startedAt, String finishedAt, int rawCount,
                         int heicCount) {

        static Export of(ExportStore.Run run) {
            return new Export(run.id(), run.state(), run.what(), run.layout(), run.stripLocation(), run.placeDepth(),
                    run.filter(), run.folder().toString(), run.csvName(), run.species(), run.total(), run.done(), run.copied(),
                    run.skipped(), run.failed(), run.renamed(), run.totalBytes(), run.bytesDone(), run.pausedBy(),
                    run.notice(), run.cancel(), run.notRemoved(), run.startedAt(), run.finishedAt(), run.rawCount(),
                    run.heicCount());
        }
    }

    /** The export running or paused, and the done and cancelled ones, newest first (contract §2). */
    public record Exported(Export current, List<Export> past) {
    }

    /** One export with its skipped, failed and renamed photos ({@link #MAX_PROBLEMS} at most) and how many more. */
    public record Detail(Export export, List<ExportStore.Problem> problems, int moreProblems) {
    }

    /**
     * The running or paused export, for {@code /api/status}; {@code rawCount}: its RAW photos copied so far (fixes J6);
     * {@code heicCount} its HEIC photos.
     */
    public record Status(long id, String state, int done, int total, String pausedBy, String notice, String path,
                         int rawCount, int heicCount) {
    }

    private final Supplier<Library> library;
    private final ExportStore store;
    private final ExportStart starts;
    private final ExportCopier copier;
    private final ExportJobs jobs;
    /** How long a resume or cancel waits for a stopping copy (tests shorten it). */
    volatile Duration stopWait = CANCEL_WAIT;
    /** Guards the one-at-a-time check and the start, so two starts never both begin. */
    private final Object startLock = new Object();

    public Exports(Supplier<Library> library, FilterQuery filters, MetadataStripper stripper, Clock clock) {
        this(library, filters, stripper, clock, ZoneId.systemDefault(), Exports::usableSpace);
    }

    /** With the time zone of the export folder's date and the free space given (tests). */
    Exports(Supplier<Library> library, FilterQuery filters, MetadataStripper stripper, Clock clock, ZoneId zone,
            ToLongFunction<Path> usableSpace) {
        this.library = library;
        this.store = new ExportStore(() -> library.get().database(), clock);
        this.copier = new ExportCopier(library, store, stripper, usableSpace);
        this.starts = new ExportStart(library, filters, store, clock, zone, usableSpace, copier::place);
        // a copy that stops on an unexpected error is paused like failed writes: resumable, never left "running"
        this.jobs = new ExportJobs(copier, id -> store.pause(id, ErrorCodes.WRITE_ERRORS, ExportCopier.WRITE_ERRORS_NOTICE));
    }

    public ExportStore store() {
        return store;
    }

    /** The check behind the sheet's space line: read-only; the filter's 400s and a bad {@code what} are refused. */
    public Check check(String filterQuery, String what, String path) {
        return check(filterQuery, what, path, false);
    }

    /** As {@link #check(String, String, String)}, with the RAW photos accepted or not ({@code rawWithLocation}). */
    public Check check(String filterQuery, String what, String path, boolean rawWithLocation) {
        Filter filter = Filter.parse(filterQuery);
        return starts.check(filter, ExportStart.choices(what, null, null, null, rawWithLocation, null), path);
    }

    /**
     * Starts an export: refused (409 {@code export_running}) while one is running or paused, else after the checks;
     * a CSV-only export is done in the answer. Refused (409 {@code taxonomy_updating}) while a taxonomy update runs,
     * waits or is paused: its photos are moving.
     */
    public Export start(Request request) {
        Filter filter = Filter.parse(request.filter()); // null: no filter
        ExportStart.Choices choices = ExportStart.choices(request.what(), request.layout(), request.stripLocation(),
                request.placeDepth(), Boolean.TRUE.equals(request.rawWithLocation()), request.acceptedKinds());
        synchronized (startLock) {
            return library.get().gate().whileOpen(() -> {
                if (store.current().isPresent()) {
                    throw ExportErrors.running(ExportErrors.RUNNING);
                }
                long id = starts.start(filter, choices, request.path());
                if (choices.photos()) {
                    jobs.run(id); // recorded running: the copy is asked for before anything else can fail
                }
                return Export.of(store.run(id));
            });
        }
    }

    /** Whether an export is copying or about to (a taxonomy update waits for it). */
    public boolean copying() {
        return jobs.busy();
    }

    /** The export running or paused, if any. */
    public Optional<Export> current() {
        return store.current().map(Export::of);
    }

    /** The current export and the history (without problems), from one state of the library. */
    public Exported list() {
        ExportStore.History history = store.history();
        return new Exported(history.current() == null ? null : Export.of(history.current()),
                history.past().stream().map(Export::of).toList());
    }

    /** One export with its problems, from one state of the library; 404 {@code not_found} when there is none. */
    public Detail detail(long id) {
        ExportStore.Details details = store.details(id, MAX_PROBLEMS);
        return new Detail(Export.of(details.run()), details.problems(),
                Math.max(0, details.count() - details.problems().size()));
    }

    /** {@code /api/status}'s {@code export}: the running or paused export, else empty. */
    public Optional<Status> status() {
        return store.current().map(Exports::status);
    }

    /** {@code /api/status}'s {@code export} part (its export null when none) if in memory, else empty: never reads. */
    public Optional<StatusPart> statusKept() {
        return store.currentKept().map(current -> new StatusPart(current.map(Exports::status).orElse(null)));
    }

    /** {@code /api/status}'s export: the running or paused one, or null. */
    public record StatusPart(Status export) {
    }

    private static Status status(ExportStore.Run r) {
        return new Status(r.id(), r.state(), r.done(), r.total(), r.pausedBy(), r.notice(), r.folder().toString(),
                r.rawCount(), r.heicCount());
    }

    /**
     * Removes a done or cancelled export from the history; its files are never touched. 409 {@code export_running}
     * while it runs or is paused, 404 when unknown.
     */
    public void delete(long id) {
        ExportStore.Run run = store.run(id);
        if (!run.finished()) {
            throw ExportErrors.running(ExportErrors.NOT_FINISHED);
        }
        if (!store.delete(id)) {
            store.run(id); // removed meanwhile (Clear history): 404
            throw ExportErrors.running(ExportErrors.NOT_FINISHED);
        }
    }

    /** Clear history: removes every done and cancelled export's record (never files); returns how many. */
    public int clear() {
        return store.deleteFinished();
    }

    /**
     * Pauses after the current file (the answer may still say running until it stops); a paused export stays as it is.
     * 409 {@code export_finished} once done or cancelled.
     */
    public Export pause(long id) {
        ExportStore.Run run = unfinished(id);
        if (ExportStore.RUNNING.equals(run.state()) && !jobs.stop(id, ExportJobs.Stop.PAUSE)) {
            store.pause(id, "you", null); // not copying (held at startup): paused at once
        }
        return Export.of(store.run(id));
    }

    /**
     * Resumes a paused export: its {@code .part} files are removed and the photos not recorded done are copied, a file
     * already at a photo's target being compared, never replaced (the same bytes: done; other bytes: failed). While its
     * folder still cannot be reached it stays paused with {@code drive_gone}. 409 {@code export_finished} once done or
     * cancelled; 409 {@code taxonomy_updating} while a taxonomy update runs, waits or is paused.
     */
    public Export resume(long id) {
        synchronized (startLock) {
            Library lib = library.get();
            lib.gate().check();
            ExportStore.Run run = unfinished(id);
            if (ExportStore.RUNNING.equals(run.state()) && jobs.keepGoing(id)) {
                return Export.of(run); // copying (a pause not yet taken effect is taken back)
            }
            // A pause still finishing its file records itself first; waited for outside the gate, so a taxonomy update
            // starting meanwhile is not held up by it.
            if (!jobs.awaitStopped(id, stopWait)) {
                throw AtlasException.busy(ExportErrors.STILL_FINISHING);
            }
            return lib.gate().whileOpen(() -> resumed(id));
        }
    }

    private Export resumed(long id) {
        ExportStore.Run run = unfinished(id);
        if (ExportStore.PAUSED.equals(run.state())) {
            store.resume(id);
        }
        if (!ExportCleanup.exportFolder(run.folder())) {
            store.pause(id, ErrorCodes.DRIVE_GONE, ExportCopier.DRIVE_GONE_NOTICE);
        } else {
            jobs.run(id);
        }
        return Export.of(store.run(id));
    }

    /**
     * Cancels a running or paused export after the current file, keeping the copied files or removing the files this
     * export wrote ({@link ExportCleanup}). 409 {@code export_finished} once done or cancelled; 503 when the current file
     * outlasts the wait.
     */
    public Export cancel(long id, boolean remove) {
        synchronized (startLock) {
            unfinished(id);
            jobs.stop(id, ExportJobs.Stop.CANCEL);
            if (!jobs.awaitStopped(id, stopWait)) {
                throw AtlasException.busy(ExportErrors.STILL_FINISHING);
            }
            ExportStore.Run run = unfinished(id); // it may have finished meanwhile: nothing is removed then
            int notRemoved = 0;
            if (remove) {
                notRemoved = ExportCleanup.remove(run.folder(), store.writtenFiles(id), store.targets(id),
                        store.pendingTargets(id), store.csvWritten(id));
            } else {
                ExportCleanup.removeParts(run.folder(), store.targets(id)); // the copies stay, never a .part of ours
            }
            copier.forget(id);
            if (!store.cancel(id, remove ? "remove" : "keep", notRemoved)) {
                throw ExportErrors.finished();
            }
            return Export.of(store.run(id));
        }
    }

    /** The export, or 404; 409 {@code export_finished} when it is done or cancelled. */
    private ExportStore.Run unfinished(long id) {
        ExportStore.Run run = store.run(id);
        if (run.finished()) {
            throw ExportErrors.finished();
        }
        return run;
    }

    /**
     * At startup, before any export copies: one still marked running was cut short by the app closing and becomes
     * paused ({@code closing}); it continues only when the user resumes it. Returns how many.
     */
    public int startup() {
        try {
            int stopped = store.markClosing();
            if (stopped > 0) {
                LOG.info("An export was stopped when Butterfly Atlas closed; it carries on when resumed");
            }
            return stopped;
        } catch (RuntimeException e) {
            LOG.warn("Could not check exports at startup", e);
            return 0;
        }
    }

    /** Holds copying back until {@link #startupFilesSettled()} (called before the startup work begins). */
    public void holdForStartup() {
        jobs.hold();
    }

    /** The startup's rescan, purge and sweep are done (or failed): exports may copy. */
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

    /** Whether the export's copy has been asked to stop and not yet stopped (tests). */
    boolean stopAsked(long id) {
        return jobs.stopAsked(id);
    }

    /** Waits until no export copies (tests). */
    boolean awaitIdle(Duration timeout) throws InterruptedException {
        return jobs.awaitIdle(timeout);
    }

    /** Runs before each photo is copied (tests). */
    void beforePhoto(Consumer<ExportStore.Item> hook) {
        copier.beforePhoto = hook;
    }

    /** Runs just before a copy or the species list is put in place (tests). */
    void beforeMove(Consumer<Path> hook) {
        copier.beforeMove = hook;
    }

    /** How hard links are made (tests make them unsupported). */
    void linker(ExportCopier.Linker linker) {
        copier.linker = linker;
    }

    /** How a placed copy's {@code .part} is deleted (tests make it fail). */
    void partDeleter(ExportCopier.Deleter deleter) {
        copier.partDeleter = deleter;
    }

    /** How folders' attributes are read (tests). */
    void attributes(ExportCopier.Attributes attributes) {
        copier.attributes = attributes;
    }

    /** After how many photos progress is saved (tests). */
    void saveEvery(int photos) {
        copier.saveEvery = photos;
    }

    /** The clock of the timed saves (tests). */
    void nanos(LongSupplier nanos) {
        copier.nanos = nanos;
    }

    /** How {@code .part} files are opened (tests make writes fail). */
    void opener(ExportCopier.Opener opener) {
        copier.opener = opener;
    }

    /** Runs before the export folder is created (tests). */
    void beforeFolder(Consumer<Path> hook) {
        starts.beforeFolder = hook;
    }

    /** Runs once the start's write has committed (tests). */
    void afterRecord(Runnable hook) {
        starts.afterRecord = hook;
    }

    /** How the species list is written (tests make it fail). */
    void csvWriter(ExportStart.CsvWriter writer) {
        starts.csvWriter = writer;
    }

    /** Runs inside the start's write before the run is recorded (tests). */
    void beforeRecord(Runnable hook) {
        starts.beforeRecord = hook;
    }

    /** The usable space of the drive {@code folder} is on; unknown reads as plenty (never a refusal on a guess). */
    public static long usableSpace(Path folder) {
        try {
            return Files.getFileStore(folder).getUsableSpace();
        } catch (IOException | RuntimeException e) {
            LOG.info("Could not tell the free space of {}: {}", folder, e.toString());
            return Long.MAX_VALUE;
        }
    }
}
