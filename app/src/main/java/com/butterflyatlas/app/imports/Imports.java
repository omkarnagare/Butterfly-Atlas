package com.butterflyatlas.app.imports;

import com.butterflyatlas.app.AtlasException;
import com.butterflyatlas.app.library.Library;
import com.butterflyatlas.app.library.OpenImports;
import com.butterflyatlas.app.photos.PhotoProcessor;
import com.butterflyatlas.app.photos.PhotoStore;
import com.butterflyatlas.app.photos.RawDisplays;
import com.butterflyatlas.app.photos.Thumbnails;
import com.butterflyatlas.app.search.SearchService;
import com.fasterxml.jackson.annotation.JsonInclude;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * Import folder (design §4, contract §2): what the HTTP layer calls. It keeps the rules: one import of a folder at a
 * time (choosing the folder again continues it), changes refused while filing, filing only when there is something
 * to file and the folder is there, and the background work ({@link ImportJobs}) started only when the user asks. Each
 * phase is recorded on its import while it runs, so one cut short by the app closing is reported as stopped at the next
 * start ({@link #startup}) until the user continues, finishes or discards the import. A finished import is read-only
 * (it can still be deleted). The checks before a change of an import and the change itself happen under that import's
 * lock, which starting filing, finishing, relocating and discarding take too, so a change never slips in between.
 *
 * <p>Here: the lifecycle of an import and its item edits; the phases are {@link ImportPhases} (saved by
 * {@link ImportProgressSaver}), the locks and checks {@link ImportLocks}, previews and large views
 * {@link ImportPreviews}, and what the UI shows {@link ImportSummaries}.
 */
public final class Imports implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(Imports.class);

    /** How long a cancel (finish, discard, relocate) waits for the running batch. */
    static final Duration CANCEL_WAIT = Duration.ofSeconds(60);
    /** How often a running phase's progress is saved on its import (for "stopped" after a crash). */
    static final Duration SAVE_PROGRESS_EVERY = Duration.ofSeconds(2);
    public static final String IDLE = "idle";

    public record Progress(int done, int total) {
    }

    /**
     * One import as the UI shows it (contract §2 "ImportSummary"). {@code filings}: how many filings have begun; a
     * change while the phase is not {@code filing} tells the UI that a filing began and ended between two of its looks.
     * {@code pairs}: its RAW + JPEG pairs, null when it has none (7a contract §3). {@code heic}: its HEIC items (count,
     * those the missing decoder cannot show, previews being made), null when it has none (Phase 7b contract §4, audit 10).
     * {@code revision}: up with every change to its items or choices (multi-window contract §5), sent back with File;
     * {@code previousRevision}: on the answer to a change, the revision just before it (§11 A20), else left out.
     */
    public record Summary(long id, String rootPath, String rootName, String state, String createdAt, String updatedAt,
                          boolean folderMissing, boolean paused, String phase, Progress progress,
                          ImportStore.Stopped stopped, String notice, String updateNote, ImportStore.Counts counts,
                          int filings, ImportStore.Pairs pairs, ImportStore.Heic heic, long revision,
                          @JsonInclude(JsonInclude.Include.NON_NULL) Long previousRevision) {

        /** This summary as the answer to a change: with the revision just before it (§11 A20). */
        Summary changedFrom(long previous) {
            return new Summary(id, rootPath, rootName, state, createdAt, updatedAt, folderMissing, paused, phase, progress,
                    stopped, notice, updateNote, counts, filings, pairs, heic, revision, previous);
        }
    }

    public record Started(Summary summary, boolean continued) {
    }

    public record Finished(Summary summary, ImportStore.Result result) {
    }

    /** An import working now, or stopped by the app closing, for {@code /api/status}. */
    public record Working(long id, String rootName, String phase, int done, int total) {
    }

    /** {@code /api/status}'s imports: the one working now (or null) and those stopped. */
    public record StatusPart(Working working, List<Working> stopped) {
    }

    /**
     * An item's preview: the file when made, else whether it is still being made. Once filing has adopted the preview as
     * the library photo's thumbnail (moving the file), {@code imageId} names that photo: its thumbnail is the preview.
     */
    public record Preview(Path file, boolean pending, Long imageId) {

        public Preview(Path file, boolean pending) {
            this(file, pending, null);
        }
    }

    /**
     * What the large view shows: the source file as it is, or a JPEG copy (TIFF): a file kept with the previews, or, for
     * a finished import (whose previews are gone), the copy's {@code bytes} with {@code file} null; for a RAW item, its
     * preview read from inside the source ({@code raw}, with {@code file} null).
     */
    public record Display(Path file, String contentType, byte[] bytes, RawDisplays.Preview raw) {

        Display(Path file, String contentType) {
            this(file, contentType, null, null);
        }

        Display(Path file, String contentType, byte[] bytes) {
            this(file, contentType, bytes, null);
        }
    }

    private final Supplier<Library> library;
    private final Path home;
    private final Clock clock;
    private final ImportStore store;
    private final ImportEdits edits;
    private final ImportWalker walker;
    private final ImportReader reader;
    private final ImportFiler filer;
    private final ImportJobs jobs;
    private final Duration cancelWait;
    private final ImportPreviews previews;
    private final ImportSummaries summaries;
    private final ImportLocks locks;
    private final ImportProgressSaver saver;
    private final ImportHeicPreviews heicPreviews;
    /** Imports whose last walk found their folder empty where photos still to file were: as good as missing. */
    private final Set<Long> emptied = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;
    /** Runs inside an edit's lock, after its checks (tests hold an edit there). */
    volatile Runnable duringEdit = () -> { };
    /** Runs inside the start lock just before a new import is created (tests). */
    volatile Runnable beforeCreate = () -> { };
    /** Runs when a relocate has walked the new folder, before it takes the start lock to apply the walk (tests). */
    volatile Runnable beforeRelocateApply = () -> { };
    /** Whether cloud placeholders ("other" files) count as files, as on Windows (tests set it). */
    volatile boolean windows = ImportNames.WINDOWS;
    /** Runs when a start has found the open import, before it takes that import's lock (tests). */
    volatile Runnable beforeContinue = () -> { };
    /** Guards finding the open import of a folder and creating one, so one folder never gets two. */
    private final ReentrantLock startLock = new ReentrantLock();

    public Imports(Supplier<Library> library, Path home, SearchService search, PhotoStore photos, PhotoProcessor processor,
                   Thumbnails thumbnails, Clock clock) {
        this(library, home, search, photos, processor, thumbnails, clock,
                new ImportFiler(library, photos, processor, thumbnails, clock), CANCEL_WAIT);
    }

    Imports(Supplier<Library> library, Path home, SearchService search, PhotoStore photos, PhotoProcessor processor,
            Thumbnails thumbnails, Clock clock, ImportFiler filer, Duration cancelWait) {
        this.library = library;
        this.home = home;
        this.clock = clock;
        this.store = new ImportStore(library, clock);
        this.edits = new ImportEdits(library, clock);
        this.walker = new ImportWalker(library, search, clock);
        this.reader = new ImportReader(library, processor, clock);
        this.filer = filer;
        this.cancelWait = cancelWait;
        this.heicPreviews = new ImportHeicPreviews(library, store, reader, clock);
        this.reader.afterReading(heicPreviews::fill);
        this.previews = new ImportPreviews(library, store, processor, () -> windows, heicPreviews);
        this.jobs = new ImportJobs(new ImportPhases(store, walker, reader, filer, previews, emptied, () -> closed,
                this::startSavingProgress));
        this.summaries = new ImportSummaries(store, jobs, emptied, heicPreviews::making, this::revision);
        this.locks = new ImportLocks(jobs);
        this.saver = new ImportProgressSaver(store, jobs, () -> closed);
    }

    public ImportStore store() {
        return store;
    }

    /** Every import, open ones first, newest first (their counts in one query). */
    public List<Summary> list() {
        return summaries.list();
    }

    public Summary summary(long id) {
        return summaries.summary(id);
    }

    /**
     * Starts an import of the folder, or continues the open import of the same folder (B4); either way the folder is
     * walked again and read in the background, unless it is missing. Continuing clears a "stopped" note of reading;
     * one of filing stays until the user files again, finishes or discards.
     */
    public Started start(String rawPath) {
        Path folder = FolderBrowser.resolve(rawPath, home);
        if (ImportNames.isInPackage(folder)) {
            throw ImportErrors.packageFolder(); // a Photos library's originals are not imported from inside its database
        }
        while (true) {
            long id;
            startLock.lock();
            try {
                Optional<ImportStore.Run> open = store.openRunOf(folder);
                if (open.isEmpty()) {
                    if (!Files.isDirectory(folder)) {
                        throw ImportErrors.folderNotFound();
                    }
                    if (FolderBrowser.isInside(folder, library.get().folder())) {
                        throw ImportErrors.libraryFolder();
                    }
                    beforeCreate.run();
                    long created = store.create(folder).id();
                    jobs.walkAndRead(created);
                    return new Started(summary(created), false);
                }
                id = open.get().id();
            } finally {
                startLock.unlock();
            }
            beforeContinue.run();
            Optional<Started> continued = continueImport(id);
            if (continued.isPresent()) {
                return continued.get();
            }
            // finished (or discarded) meanwhile: look again, which starts a new import of the folder
        }
    }

    /** Continues an open import under its lock; empty when it is no longer open. */
    private Optional<Started> continueImport(long id) {
        ReentrantLock lock = locks.lockOf(id);
        lock.lock();
        try {
            Optional<ImportStore.Run> run = store.find(id).filter(r -> ImportStore.OPEN.equals(r.state()));
            if (run.isEmpty()) {
                return Optional.empty();
            }
            if (Files.isDirectory(run.get().root())) {
                ImportStore.Stopped stopped = run.get().stopped();
                if (stopped != null && ImportJobs.READING.equals(stopped.phase())) {
                    // only when the walk is queued: a missing folder continues nothing
                    store.clearStopped(id); // a stopped filing stays reported until the user files again (or finishes)
                }
                jobs.walkAndRead(id);
            }
            return Optional.of(new Started(summary(id), true));
        } finally {
            lock.unlock();
        }
    }

    /**
     * All items, or those changed after version {@code since}, with the import's revision, read under the import's lock
     * (§11 A21): the revision is the one of these very items, which is what the window shows and sends with File.
     */
    public ImportStore.ItemsPage items(long id, Long since) {
        ReentrantLock lock = locks.lockOf(id);
        lock.lock();
        try {
            store.run(id);
            return store.items(id, since).withRevision(revision(id));
        } finally {
            lock.unlock();
        }
    }

    public ImportStore.ItemsPage assign(long id, List<Long> itemIds, String taxonId) {
        return editItems(id, () -> edits.assign(id, itemIds, taxonId));
    }

    public ImportStore.ItemsPage tags(long id, List<Long> itemIds, List<String> add, List<String> remove) {
        return editItems(id, () -> edits.tags(id, itemIds, add, remove));
    }

    public ImportStore.ItemsPage place(long id, List<Long> itemIds, String placeId, boolean remove) {
        return editItems(id, () -> edits.place(id, itemIds, placeId, remove));
    }

    public ImportStore.ItemsPage skip(long id, List<Long> itemIds, boolean skipped) {
        return editItems(id, () -> edits.skip(id, itemIds, skipped));
    }

    public ImportStore.ItemsPage acceptSuggestions(long id, List<Long> itemIds) {
        return editItems(id, () -> edits.acceptSuggestions(id, itemIds));
    }

    /** The import's revision now (multi-window contract §5). */
    public long revision(long id) {
        return library.get().openImports().revision(id);
    }

    /** An edit of the items: the revision goes up with it, and the answer carries the new one and the one before. */
    private ImportStore.ItemsPage editItems(long id, Supplier<ImportStore.ItemsPage> change) {
        return edit(id, () -> {
            ImportStore.ItemsPage page = change.get();
            long revision = library.get().openImports().changed(id);
            return page.withRevision(revision, revision - 1);
        });
    }

    /** A change of the user's: under the import's lock, refused for a finished import and while it files. */
    private <T> T edit(long id, Supplier<T> change) {
        ReentrantLock lock = locks.lockOf(id);
        lock.lock();
        try {
            ImportLocks.stillOpen(store.run(id));
            locks.notFiling(id);
            duringEdit.run();
            return change.get();
        } finally {
            lock.unlock();
        }
    }

    /** As {@link #file(long, long)} at the import's revision as it is under its lock (callers that hold no window: tests). */
    public Summary file(long id) {
        return file(id, null);
    }

    public Summary file(long id, long revision) {
        return file(id, Long.valueOf(revision));
    }

    /**
     * Files the assigned photos in the background (409 when the folder is missing or nothing is assigned; 409
     * {@code taxonomy_updating} while a taxonomy update runs, waits or is paused). {@code revision} is the one the window
     * last had: when the import changed since (another window), 409 {@code import_changed} and nothing is filed.
     */
    private Summary file(long id, Long revision) {
        ReentrantLock lock = locks.lockOf(id);
        lock.lock();
        try {
            Library lib = library.get();
            lib.gate().whileOpen(() -> lib.photoHold().whileFree(() -> startFiling(id, revision)));
        } finally {
            lock.unlock();
        }
        return summary(id);
    }

    /** Whether this import is filing or about to (a tag, place or species it chose is not deleted meanwhile). */
    public boolean filing(long id) {
        return jobs.filing(id);
    }

    /** Whether any import is filing or about to (a taxonomy update waits for it). */
    public boolean filing() {
        return jobs.filingAny();
    }

    /** The checks and the start of a filing, under the import's lock. */
    private void startFiling(long id, Long revision) {
        ImportStore.Run run = store.run(id);
        ImportLocks.stillOpen(run);
        locks.notFiling(id);
        if (emptied.contains(id) || !ImportWalker.hasListedFiles(run.root(), library.get().folder())) {
            throw ImportErrors.folderMissing(); // gone, or holding no photos (an emptied folder, a drive unplugged)
        }
        ImportStore.Pairs pairs = store.pairs(id);
        if (pairs != null && pairs.toFile() > 0 && pairs.choice() == null) {
            throw ImportPairs.choiceNeeded(pairs.toFile(), pairs.kinds()); // filing waits for the choice (7a design §3.1)
        }
        if (!filer.hasWork(id)) {
            throw ImportErrors.nothingToFile();
        }
        OpenImports open = library.get().openImports();
        if (revision != null && (open.changing(id) || revision != revision(id))) {
            throw ImportErrors.changed(); // before anything is written
        }
        store.clearStopped(id);
        store.setPaused(id, false);
        store.setNotice(id, null);
        // Checked again, and filing begun, under the database's write lock: a tag, place or species delete either
        // committed before (its imports were marked changing, then their revisions went up: refused here) or comes after
        // and sees this import filing (refused there).
        library.get().database().write(c -> {
            if (revision != null && (open.changing(id) || revision != revision(id))) {
                throw ImportErrors.changed();
            }
            jobs.file(id); // from here the import counts as filing: the next change waiting for the lock is refused
            return null;
        });
    }

    /**
     * How the import's RAW + JPEG pairs are filed ({@code both}, {@code jpeg} or {@code raw}; 7a contract §3): saved with
     * the import, changeable until filing has touched a pair (409 {@code pair_choice_locked}); 409 {@code no_pairs} for an
     * import without pairs; under the import's lock,
     * refused for a finished import and while it files, as the other changes.
     */
    public Summary pairs(long id, String choice) {
        long revision = edit(id, () -> {
            if (choice == null || !ImportPairs.CHOICES.contains(choice)) {
                throw ImportPairs.unknownChoice(choice);
            }
            ImportStore.Pairs pairs = store.pairs(id);
            if (pairs == null) {
                throw ImportPairs.noPairs(); // nothing to choose for (fixes J4)
            }
            if (pairs.locked() && !choice.equals(pairs.choice())) {
                throw ImportPairs.choiceLocked();
            }
            store.setPairChoice(id, choice);
            return library.get().openImports().changed(id);
        });
        return summary(id).changedFrom(revision - 1);
    }

    /** Filing stops after the current batch; the import shows as paused (no warning). */
    public Summary pause(long id) {
        ReentrantLock lock = locks.lockOf(id);
        lock.lock();
        try {
            store.run(id);
            if (jobs.pause(id)) {
                store.setPaused(id, true);
            }
        } finally {
            lock.unlock();
        }
        return summary(id);
    }

    /**
     * The folder moved: walks the new place (400 {@code folder_mismatch} if none of the files are there), then reads.
     * The walk runs on the caller's thread and stops if the app closes meanwhile (503, nothing changed).
     */
    public Summary relocate(long id, String rawPath) {
        ReentrantLock lock = locks.lockOf(id);
        long revision;
        lock.lock();
        try {
            ImportLocks.stillOpen(store.run(id));
            locks.notFiling(id);
            Path folder = FolderBrowser.resolve(rawPath, home);
            if (ImportNames.isInPackage(folder)) {
                throw ImportErrors.packageFolder();
            }
            if (Files.isDirectory(folder) && FolderBrowser.isInside(folder, library.get().folder())) {
                throw ImportErrors.libraryFolder();
            }
            stopWork(id);
            // The walk's result is applied under the start lock, after the folder_in_use check again: a start of the
            // new folder (another tab) during the walk either came first and wins, or comes after and continues this one.
            if (walker.relocate(id, folder, () -> closed, apply -> {
                beforeRelocateApply.run();
                startLock.lock();
                try {
                    return apply.get();
                } finally {
                    startLock.unlock();
                }
            }).isEmpty()) {
                throw AtlasException.busy("Butterfly Atlas is closing. Try again when it is open.");
            }
            emptied.remove(id);
            store.clearStopped(id);
            revision = library.get().openImports().changed(id);
            jobs.read(id);
        } finally {
            lock.unlock();
        }
        return summary(id).changedFrom(revision - 1);
    }

    /**
     * Finishes the import (with {@code skipRest}, every item still waiting is skipped); its previews go. Finishing a
     * finished import changes nothing.
     */
    public Finished finish(long id, boolean skipRest) {
        ReentrantLock lock = locks.lockOf(id);
        lock.lock();
        try {
            ImportStore.Run run = store.run(id);
            if (ImportStore.OPEN.equals(run.state())) {
                locks.notFiling(id);
                stopWork(id);
                if (skipRest) {
                    edits.skipRest(id);
                }
                store.finish(id);
                emptied.remove(id);
                previews.deletePreviews(id);
            }
        } finally {
            lock.unlock();
        }
        return new Finished(summary(id), store.result(id));
    }

    public ImportStore.Result result(long id) {
        return store.result(id);
    }

    /** Removes the import's rows and previews; never the source folder or photos already filed. */
    public void discard(long id) {
        ReentrantLock lock = locks.lockOf(id);
        lock.lock();
        try {
            store.run(id);
            locks.notFiling(id);
            jobs.cancel(id, cancelWait); // a phase still running past the wait meets the import gone and stops (ImportPhases)
            store.delete(id);
            emptied.remove(id);
            previews.deletePreviews(id);
        } finally {
            lock.unlock();
        }
    }

    /** See {@link ImportPreviews#preview}. */
    public Preview preview(long id, long itemId) {
        return previews.preview(id, itemId);
    }

    /** See {@link ImportPreviews#display}. */
    public Display display(long id, long itemId) {
        return previews.display(id, itemId);
    }

    /** The import working now, if any. */
    public Optional<Working> working() {
        return summaries.working();
    }

    /** The import working now and those stopped, if the imports are in memory, else empty: never reads (a status poll). */
    public Optional<StatusPart> statusKept() {
        return store.briefsKept().map(briefs -> new StatusPart(summaries.working(briefs).orElse(null),
                summaries.stopped(briefs)));
    }

    /** Open imports whose work the app's closing cut short, not continued since. */
    public List<Working> stopped() {
        return summaries.stopped();
    }

    /**
     * At startup, before any import works: phases still marked running were cut short by the app closing and become
     * "stopped"; preview folders of imports that are no longer open are removed. Returns how many folders went.
     */
    public int startup() {
        try {
            return tidyAtStartup();
        } catch (RuntimeException e) {
            LOG.warn("Could not check imports at startup", e);
            return 0;
        }
    }

    private int tidyAtStartup() {
        int stopped = store.markStopped();
        if (stopped > 0) {
            LOG.info("{} imports were stopped when Butterfly Atlas closed; they carry on when continued", stopped);
        }
        return previews.tidy();
    }

    /** Holds filing back until {@link #startupFilesSettled()} (called before the startup work begins). */
    public void holdForStartup() {
        jobs.hold();
    }

    /** The startup's rescan, purge and sweep are done (or failed): imports may file. */
    public void startupFilesSettled() {
        jobs.release();
    }

    /** Waits until no import works (tests). */
    public boolean awaitIdle(Duration timeout) throws InterruptedException {
        return jobs.awaitIdle(timeout);
    }

    /** Phases that ended with an error (tests). */
    int failedPhases() {
        return jobs.failures();
    }

    /** Whether a start waits for another (tests). */
    boolean startWaiting() {
        return startLock.hasQueuedThreads();
    }

    /** Whether the imports are closing or closed (tests). */
    boolean closing() {
        return closed;
    }

    /** Whether a call waits for the import's lock (tests). */
    boolean waitingFor(long id) {
        return locks.lockOf(id).hasQueuedThreads();
    }

    /** See {@link ImportProgressSaver#saveProgress} (tests). */
    void saveProgress() {
        saver.saveProgress();
    }

    private void startSavingProgress() {
        saver.start();
    }

    /**
     * The app is quitting: the running phase stops at its next look and nothing more starts, without waiting (called
     * before the web server stops, so a long walk does not hold the quit up); {@link #close} follows.
     */
    public void beginClose() {
        closed = true;
        jobs.beginClose();
    }

    @Override
    public void close() {
        closed = true;
        jobs.close(); // waits (bounded) for the running phase, which keeps its record: reported stopped at the next start
        saver.close();
        heicPreviews.close();
    }

    /**
     * The computer's HEIC decoder is available now (at start or after Check again; Phase 7b contract §3, audit 10):
     * open imports' HEIC items read without a preview get one, in the background; their summaries say how many are
     * being made ({@code heic.making}).
     */
    public void heicAvailable() {
        heicPreviews.fillAll();
    }

    /** Test-only: waits until no HEIC preview is being made, at most {@code timeout}. */
    public boolean awaitHeicPreviews(Duration timeout) throws InterruptedException {
        return heicPreviews.awaitIdle(timeout.toMillis());
    }

    /** Stops the import's work, or 503 when its running step outlasts the wait (it still stops when it next looks). */
    private void stopWork(long id) {
        if (!jobs.cancel(id, cancelWait)) {
            throw AtlasException.busy("Butterfly Atlas is still finishing the last step. Try again in a moment.");
        }
    }
}
