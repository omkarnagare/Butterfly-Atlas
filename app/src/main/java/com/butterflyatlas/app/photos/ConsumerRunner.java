package com.butterflyatlas.app.photos;

import com.butterflyatlas.app.db.Database;
import com.butterflyatlas.app.db.SqlLists;
import com.butterflyatlas.app.library.Library;
import com.butterflyatlas.app.photos.consumers.Place;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Catch-up for photo details and consumers (design §3.4), run after the rescan at every start:
 * <ol>
 *   <li><b>details</b>: live photos with no snapshot ({@code image_meta}) are read once through
 *       {@link PhotoIngest#facts} (after the upgrade to 3b-1, the last time their files are read for metadata;
 *       only the first bytes and the facts, never a whole-file hash);</li>
 *   <li><b>update</b>: each fact consumer processes, from the snapshots, the live photos the ledger says it has not
 *       (none yet, an older version, or a failure); the {@code place} consumer reports as its own step, <b>places</b>
 *       ("Finding places…"), after the others;</li>
 *   <li>the thumbnail worker queues the photos without a current thumbnail ({@link Thumbnails#catchUp()}), which
 *       report under {@code previews}.</li>
 * </ol>
 * Results are written {@link #BATCH} photos to a transaction, so an interrupted run resumes where the ledger says;
 * the details step's progress advances per photo read, the update step's per batch applied.
 * A photo that fails is recorded with the reason and tried again only at the next run: each step walks the photos
 * once, by id; after {@link ProcessingLedger#MAX_ATTEMPTS} failures at a version it is left (until the version
 * rises). When a batch fails (a snapshot or a consumer's work), each of its photos is tried on its own so one bad
 * photo cannot hold back the rest, and a consumer that fails altogether does not hold back the next one. The details
 * step never replaces a snapshot written meanwhile (by a rescan or an import).
 */
public final class ConsumerRunner {

    /**
     * What a run is doing, for {@code /api/status}: {@code step} is {@link #DETAILS}, {@link #UPDATE} or {@link #PLACES};
     * {@code total} is fixed for the step.
     */
    public record Progress(String step, int done, int total) {
    }

    public static final String DETAILS = "details";
    public static final String UPDATE = "update";
    public static final String PLACES = "places";
    /** Photos per write transaction. */
    static final int BATCH = 200;

    private static final Logger LOG = LoggerFactory.getLogger(ConsumerRunner.class);

    private final Supplier<Library> library;
    private final PhotoIngest ingest;
    private final List<PhotoConsumer> consumers;
    private final Thumbnails thumbnails;
    private final ProcessingLedger ledger;
    private final Clock clock;
    private final int batch;
    private final AtomicBoolean running = new AtomicBoolean();
    private final List<Runnable> placesChecked = new CopyOnWriteArrayList<>();
    private final List<Runnable> placesFinished = new CopyOnWriteArrayList<>();
    private volatile Progress progress;

    /**
     * @param consumers fact consumers only (the thumbnail worker is the pixel consumer)
     * @param thumbnails whose catch-up runs last; null to leave thumbnails alone (tests)
     */
    public ConsumerRunner(Supplier<Library> library, PhotoIngest ingest, List<PhotoConsumer> consumers, Thumbnails thumbnails,
                          Clock clock) {
        this(library, ingest, consumers, thumbnails, clock, BATCH);
    }

    /** With another number of photos per transaction (tests). */
    ConsumerRunner(Supplier<Library> library, PhotoIngest ingest, List<PhotoConsumer> consumers, Thumbnails thumbnails,
                   Clock clock, int batch) {
        if (batch < 1) {
            throw new IllegalArgumentException("batch must be at least 1");
        }
        for (PhotoConsumer consumer : consumers) {
            if (consumer.kind() != PhotoConsumer.Kind.FACTS) {
                throw new IllegalArgumentException(consumer.key() + " is not a fact consumer");
            }
        }
        this.library = library;
        this.ingest = ingest;
        this.consumers = List.copyOf(consumers);
        this.thumbnails = thumbnails;
        this.ledger = new ProcessingLedger(clock);
        this.clock = clock;
        this.batch = batch;
    }

    /**
     * Runs {@code listener} after each batch of the {@link #PLACES} step is written and when the step ends (photos may
     * have changed place: a place update's note counts them). Keep it quick.
     */
    public void onPlacesChecked(Runnable listener) {
        placesChecked.add(listener);
    }

    /**
     * Runs {@code listener} each time a run has gone past its {@link #PLACES} step, whether it had photos to check, all
     * of them were done, or some failed (a place update's re-check is over for this run). Keep it quick.
     */
    public void onPlacesFinished(Runnable listener) {
        placesFinished.add(listener);
    }

    private void placesChecked() {
        notify(placesChecked);
    }

    private static void notify(List<Runnable> listeners) {
        for (Runnable listener : listeners) {
            try {
                listener.run();
            } catch (RuntimeException e) {
                LOG.warn("A listener of the places step failed", e);
            }
        }
    }

    /** The running step's progress; null when no catch-up is running. */
    public Progress progress() {
        return progress;
    }

    /**
     * Runs once; a second call while one is running (thumbnail catch-up included) returns at once. A failing step is
     * logged and the next one still runs.
     */
    public void run() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            Library lib = library.get();
            step("read photo details", () -> details(lib));
            step("update photo details", () -> update(lib));
            progress = null;
            notify(placesFinished);
            if (thumbnails != null) {
                step("queue missing thumbnails", thumbnails::catchUp);
            }
        } finally {
            progress = null;
            running.set(false);
        }
    }

    private static void step(String what, Runnable work) {
        try {
            work.run();
        } catch (RuntimeException e) {
            LOG.warn("Could not {}", what, e);
        }
    }

    /**
     * Waits before a batch while a change of library folder holds the background work (it copies the library); false when
     * the wait was cut short by the app closing (the batches stop).
     */
    private static boolean free(Library lib) {
        try {
            lib.workHold().awaitFree();
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void details(Library lib) {
        Database db = lib.database();
        int total = db.read(ImageMeta::countMissing);
        if (total == 0) {
            return;
        }
        progress = new Progress(DETAILS, 0, total);
        int done = 0;
        int failures = 0;
        long after = 0;
        while (free(lib)) {
            long cursor = after;
            List<Long> ids = db.read(c -> ImageMeta.missing(c, cursor, batch));
            if (ids.isEmpty()) {
                break;
            }
            Map<Long, Path> files = db.read(c -> files(c, lib.folder(), ids));
            Map<Long, PhotoFacts> read = new LinkedHashMap<>();
            Map<Long, String> failed = new LinkedHashMap<>();
            for (long id : ids) {
                Path file = files.get(id);
                if (file == null) {
                    progress = new Progress(DETAILS, Math.min(++done, total), total);
                    continue; // deleted meanwhile
                }
                try {
                    // The first bytes and the facts only: catch-up needs no hash, so the whole file is not read.
                    PhotoFacts facts = ingest.facts(file);
                    if (facts == null) {
                        failed.put(id, PhotoFormat.UNREADABLE);
                    } else {
                        read.put(id, facts);
                    }
                } catch (IOException | RuntimeException e) {
                    failed.put(id, describe(e));
                }
                // Only this thread writes progress; readers see whole, immutable records.
                progress = new Progress(DETAILS, Math.min(++done, total), total);
            }
            failures += save(db, read, failed, clock.instant());
            after = ids.get(ids.size() - 1);
        }
        LOG.info("Read the details of {} photos{}", done - failures, failures == 0 ? "" : "; " + failures + " could not be read");
    }

    /**
     * Writes one batch of the details step in one transaction: the snapshots (never replacing one written meanwhile)
     * and the failed reads. If that fails, each photo is written on its own, and a snapshot that still cannot be
     * written is recorded as a failed read. Returns how many photos failed.
     */
    private int save(Database db, Map<Long, PhotoFacts> read, Map<Long, String> failed, Instant now) {
        try {
            db.write(c -> {
                for (Map.Entry<Long, PhotoFacts> facts : read.entrySet()) {
                    ImageMeta.writeIfMissing(c, facts.getKey(), facts.getValue(), now);
                }
                for (Map.Entry<Long, String> failure : failed.entrySet()) {
                    ledger.markFailed(c, failure.getKey(), ImageMeta.DETAILS, ImageMeta.DETAILS_VERSION, failure.getValue());
                }
                return null;
            });
            return failed.size();
        } catch (RuntimeException batchFailure) {
            LOG.debug("Could not save a batch of photo details; saving them one by one", batchFailure);
        }
        int failures = 0;
        for (Map.Entry<Long, PhotoFacts> facts : read.entrySet()) {
            try {
                db.write(c -> ImageMeta.writeIfMissing(c, facts.getKey(), facts.getValue(), now));
            } catch (RuntimeException e) {
                LOG.warn("Could not save the details of photo {}", facts.getKey(), e);
                markFailed(db, facts.getKey(), ImageMeta.DETAILS, ImageMeta.DETAILS_VERSION, describe(e));
                failures++;
            }
        }
        for (Map.Entry<Long, String> failure : failed.entrySet()) {
            markFailed(db, failure.getKey(), ImageMeta.DETAILS, ImageMeta.DETAILS_VERSION, failure.getValue());
            failures++;
        }
        return failures;
    }

    /** Records a failure in its own transaction; if even that fails it is only logged (the photo stays pending). */
    private void markFailed(Database db, long imageId, String consumer, int version, String reason) {
        try {
            db.write(c -> {
                ledger.markFailed(c, imageId, consumer, version, reason);
                return null;
            });
        } catch (RuntimeException e) {
            LOG.warn("Could not record that {} failed for photo {}", consumer, imageId, e);
        }
    }

    /** The step a consumer's catch-up reports under: {@link #PLACES} for the place consumer, else {@link #UPDATE}. */
    static String stepOf(PhotoConsumer consumer) {
        return Place.KEY.equals(consumer.key()) ? PLACES : UPDATE;
    }

    /** The fact consumers with photos to process, {@link #UPDATE} ones first, then {@link #PLACES}; each step has its own total. */
    private void update(Library lib) {
        Database db = lib.database();
        for (String step : List.of(UPDATE, PLACES)) {
            List<PhotoConsumer> due = new ArrayList<>();
            int total = 0;
            for (PhotoConsumer consumer : consumers) {
                if (!stepOf(consumer).equals(step)) {
                    continue;
                }
                try {
                    int pending = db.read(c -> ledger.countPending(c, consumer.key(), consumer.version()));
                    if (pending > 0) {
                        due.add(consumer);
                        total += pending;
                    }
                } catch (RuntimeException e) {
                    LOG.warn("Could not count the photos {} has to process; skipping it", consumer.key(), e);
                }
            }
            if (total > 0) {
                try {
                    catchUp(lib, step, due, total);
                } finally {
                    if (PLACES.equals(step)) {
                        progress = null;
                        placesChecked();
                    }
                }
            }
        }
    }

    private void catchUp(Library lib, String step, List<PhotoConsumer> due, int total) {
        Database db = lib.database();
        progress = new Progress(step, 0, total);
        int done = 0;
        for (PhotoConsumer consumer : due) {
            // One consumer's trouble (its ledger rows, its snapshots) must not hold back the next one.
            try {
                long after = 0;
                while (free(lib)) {
                    long cursor = after;
                    List<Long> ids = db.read(c -> ledger.pending(c, consumer.key(), consumer.version(), cursor, batch));
                    if (ids.isEmpty()) {
                        break;
                    }
                    // Photos without a snapshot (unreadable ones) are skipped; they stay pending until they have one.
                    apply(db, consumer, db.read(c -> ImageMeta.subjects(c, lib.folder(), ids)));
                    done += ids.size();
                    after = ids.get(ids.size() - 1);
                    progress = new Progress(step, Math.min(done, total), total);
                    if (PLACES.equals(step)) {
                        placesChecked();
                    }
                }
            } catch (IllegalStateException e) {
                // The consumer cannot work at all (boundaries that cannot be read): its photos stay pending, no
                // attempt counted, for the next start.
                LOG.warn("{} cannot work now; its photos wait for the next start", consumer.key(), e);
            } catch (RuntimeException e) {
                LOG.warn("Could not update photo details for {}; going on with the next consumer", consumer.key(), e);
            }
        }
    }

    /**
     * One batch in one transaction; if that fails, each photo on its own, recording the ones that still fail. An
     * {@link IllegalStateException} (the consumer's own data cannot be read, not the photo's fault) is thrown on, so
     * the consumer is skipped for this run without using up its photos' attempts.
     */
    private void apply(Database db, PhotoConsumer consumer, List<PhotoConsumer.Subject> subjects) {
        if (subjects.isEmpty()) {
            return;
        }
        try {
            db.write(c -> applyAndMark(c, consumer, subjects));
        } catch (IllegalStateException unavailable) {
            throw unavailable;
        } catch (RuntimeException batchFailure) {
            LOG.debug("{} failed for a batch; trying its photos one by one", consumer.key(), batchFailure);
            for (PhotoConsumer.Subject subject : subjects) {
                try {
                    db.write(c -> applyAndMark(c, consumer, List.of(subject)));
                } catch (IllegalStateException unavailable) {
                    throw unavailable;
                } catch (RuntimeException e) {
                    LOG.warn("{} could not process photo {}", consumer.key(), subject.imageId(), e);
                    markFailed(db, subject.imageId(), consumer.key(), consumer.version(), describe(e));
                }
            }
        }
    }

    private Void applyAndMark(Connection c, PhotoConsumer consumer, List<PhotoConsumer.Subject> subjects) throws SQLException {
        List<PhotoConsumer.Subject> present = existing(c, subjects);
        if (!present.isEmpty()) {
            consumer.apply(present, c);
            ledger.markDone(c, present.stream().map(PhotoConsumer.Subject::imageId).toList(), consumer.key(), consumer.version());
        }
        return null;
    }

    /** Those of the subjects whose photo still exists (one may have been purged since it was read). */
    private static List<PhotoConsumer.Subject> existing(Connection c, List<PhotoConsumer.Subject> subjects) throws SQLException {
        List<Long> ids = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT id FROM image WHERE id IN ("
                + SqlLists.marks(subjects.size()) + ")")) {
            SqlLists.bind(ps, 1, subjects.stream().map(PhotoConsumer.Subject::imageId).toList());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ids.add(rs.getLong(1));
                }
            }
        }
        return subjects.stream().filter(s -> ids.contains(s.imageId())).toList();
    }

    /** The live files of those of {@code ids} that are still live. */
    private static Map<Long, Path> files(Connection c, Path data, List<Long> ids) throws SQLException {
        Map<Long, Path> files = new LinkedHashMap<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT id, rel_path FROM image WHERE deleted_at IS NULL AND id IN ("
                + SqlLists.marks(ids.size()) + ")")) {
            SqlLists.bind(ps, 1, ids);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    files.put(rs.getLong(1), PhotoFiles.resolve(data, rs.getString(2)));
                }
            }
        }
        return files;
    }

    /** {@code NoSuchFileException: /path}: the innermost cause, which says what actually went wrong. */
    private static String describe(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getClass().getSimpleName() + (root.getMessage() == null ? "" : ": " + root.getMessage());
    }
}
