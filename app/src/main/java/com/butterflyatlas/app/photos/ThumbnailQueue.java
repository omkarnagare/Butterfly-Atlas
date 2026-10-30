package com.butterflyatlas.app.photos;

import com.butterflyatlas.app.library.Library;
import com.butterflyatlas.app.library.WorkHold;
import com.butterflyatlas.app.photos.CacheFiles.Source;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Thumbnails waiting for the two background workers, made in queue order (a photo the gallery asks for moves to the
 * front): queuing, waiting for one, the catch-up of missing ones, and the progress the UI shows.
 *
 * <p>A HEIC photo's thumbnail (Phase 7b contract §2, §8) is neither queued nor attempted while the computer's decoder is
 * not available: while it is being checked the gallery is told it is on its way, while it is missing that it is not
 * (and nothing is marked failed). A HEIC job that finds no decoder slot free goes back to the end of the queue and frees
 * its worker (audit 9); one the gallery asked for decodes before catch-up's.
 */
final class ThumbnailQueue {

    /** Photos per read and ledger write during catch-up. */
    private static final int BATCH = 200;

    private static final Logger LOG = LoggerFactory.getLogger(ThumbnailQueue.class);

    /** A thumbnail waiting for a worker; {@code shown} once the gallery asked for it (its HEIC decode goes first). */
    private record Job(Source source, Path thumb, CompletableFuture<Path> future, AtomicBoolean shown) {
    }

    private final Supplier<Library> library;
    private final ProcessingLedger ledger;
    /** The ledger version written and asked for: {@link Thumbnails#VERSION}, or another in tests. */
    private final int version;
    private final CopyMaker maker;
    private final ExecutorService executor;
    /** Files being made, by their final path (shared with the display copies). */
    private final Map<Path, CompletableFuture<Path>> pending;
    /**
     * Thumbnails waiting, by their final path, in the order the workers take them (guarded by itself): moving one to
     * the front ({@link #lookup}) is O(1).
     */
    private final LinkedHashMap<Path, Job> queue = new LinkedHashMap<>();
    private final Object counts = new Object();
    private int queued;
    private int finished;

    ThumbnailQueue(Supplier<Library> library, ProcessingLedger ledger, int version, CopyMaker maker,
                   Map<Path, CompletableFuture<Path>> pending) {
        this.library = library;
        this.ledger = ledger;
        this.version = version;
        this.maker = maker;
        this.pending = pending;
        AtomicInteger n = new AtomicInteger();
        this.executor = Executors.newFixedThreadPool(2, task -> {
            Thread thread = new Thread(task, "atlas-thumbnails-" + n.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    /** Queues the thumbnail of a photo unless it exists; never fails (problems are logged). */
    void request(long id) {
        try {
            Source source = CacheFiles.source(library.get(), id, version, false);
            Path thumb = source.target(Thumbnails.SIZE);
            if (!Files.exists(thumb) && !waits(source, thumb)) {
                schedule(source);
            }
        } catch (RuntimeException e) {
            LOG.warn("Could not queue the thumbnail of photo {}", id, e);
        }
    }

    /**
     * For the thumbnail route, never waiting: the thumbnail if it exists; otherwise it is queued if need be and
     * moved to the front of the queue (the gallery is showing it), and the answer says it is on its way, unless
     * it could not be made.
     */
    Thumbnails.Lookup lookup(long id) {
        Source source = CacheFiles.source(library.get(), id, version, false);
        Path thumb = source.target(Thumbnails.SIZE);
        if (Files.exists(thumb)) {
            return new Thumbnails.Lookup(thumb, false);
        }
        if (source.heic() && !HeicStatus.current().isAvailable()) {
            // Checking: it comes once the check is done. Missing: the placeholder, not failed (made once a decoder appears).
            return new Thumbnails.Lookup(null, HeicStatus.current().isChecking());
        }
        if (maker.hasFailed(thumb)) {
            return new Thumbnails.Lookup(null, false);
        }
        if (maker.heldBack(thumb)) {
            return new Thumbnails.Lookup(null, true); // a HEIC that took too long: tried again later (fixes J11)
        }
        try {
            schedule(source);
        } catch (RejectedExecutionException e) {
            return new Thumbnails.Lookup(null, false); // closed
        }
        synchronized (queue) {
            Job job = queue.get(thumb);
            if (job != null) {
                job.shown().set(true);
                queue.putFirst(thumb, job);
            }
        }
        return new Thumbnails.Lookup(null, true);
    }

    /**
     * Test-only: the app's own code never calls it (it asks {@link #lookup}, which never waits); public because tests in
     * other packages wait on thumbnails with it. The thumbnail, made now if needed,
     * waiting at most {@code timeout}; empty if it is not ready by then or cannot be made. While a worker has the thumbnail in hand this waits for it to finish, even when the file is already
     * there (it appears a moment before its ledger row and before {@link #progress()} stops counting it), so once
     * this answers, the ledger and the counts agree with the answer, unless it timed out. (That covers the queue's own work:
     * a thumbnail {@link Thumbnails#adopt} has moved into place may be on disk a moment before adopt records it.)
     */
    Optional<Path> await(long id, Duration timeout) {
        Source source = CacheFiles.source(library.get(), id, version, false);
        Path thumb = source.target(Thumbnails.SIZE);
        CompletableFuture<Path> job = pending.get(thumb);
        if (job == null) {
            if (Files.exists(thumb)) {
                return Optional.of(thumb);
            }
            if (waits(source, thumb)) {
                return Optional.empty();
            }
            try {
                job = schedule(source);
            } catch (RejectedExecutionException e) {
                return Optional.empty(); // closed
            }
        }
        try {
            return Optional.of(job.get(timeout.toMillis(), TimeUnit.MILLISECONDS));
        } catch (TimeoutException | ExecutionException e) {
            // Not finished in time, or failed; a file already in place is the answer all the same.
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return Files.exists(thumb) ? Optional.of(thumb) : Optional.empty();
    }

    Thumbnails.Progress progress() {
        synchronized (counts) {
            return new Thumbnails.Progress(queued, finished);
        }
    }

    /**
     * Thumbnail catch-up: live photos with no current {@value Thumbnails#CONSUMER} ledger row are marked done when their
     * thumbnail is already on disk (no decoding: the first start after 3b-1, or a crash between moving a thumbnail
     * into place and recording it), and queued otherwise, unless they failed recently. After {@link Thumbnails#VERSION} is
     * raised the thumbnails have new names, so none is on disk and all are made again; the old ones are left to
     * {@link Thumbnails#cleanOrphans}. Their progress shows under {@link #progress()}. Returns how many this call queued (a photo
     * already queued, by a request for its thumbnail, is not counted again).
     */
    int catchUp() {
        Library lib = library.get();
        int queuedNow = 0;
        long after = 0;
        while (true) {
            long cursor = after;
            List<Long> ids = lib.database().read(c -> ledger.pending(c, Thumbnails.CONSUMER, version, cursor, BATCH));
            if (ids.isEmpty()) {
                break;
            }
            List<Source> sources = lib.database().read(c -> CacheFiles.liveSources(c, lib.folder(), ids, version));
            List<Source> present = new ArrayList<>();
            for (Source source : sources) {
                Path thumb = source.target(Thumbnails.SIZE);
                if (Files.exists(thumb)) {
                    present.add(source);
                } else if (!waits(source, thumb)) {
                    try {
                        if (enqueue(source).isNew()) {
                            queuedNow++; // one already queued (a request got there first) is not queued now
                        }
                    } catch (RejectedExecutionException e) {
                        return queuedNow; // closed
                    }
                }
            }
            if (!present.isEmpty()) {
                lib.database().write(c -> {
                    // As in record: only for the file version the thumbnail on disk was made from.
                    List<Long> current = new ArrayList<>();
                    for (Source source : present) {
                        if (CacheFiles.current(c, source)) {
                            current.add(source.id());
                        }
                    }
                    ledger.markDone(c, current, Thumbnails.CONSUMER, version);
                    return null;
                });
            }
            after = ids.get(ids.size() - 1);
        }
        if (queuedNow > 0) {
            LOG.info("Queued {} missing thumbnails", queuedNow);
        }
        return queuedNow;
    }

    /**
     * Whether a missing thumbnail is not to be made now: it failed lately, it is a HEIC held back after a timeout, or a
     * HEIC while the decoder is not available.
     */
    private boolean waits(Source source, Path thumb) {
        return maker.hasFailed(thumb) || maker.heldBack(thumb) || (source.heic() && !HeicStatus.current().isAvailable());
    }

    /**
     * Queues a thumbnail once. Each job adds one turn for the workers ({@link #runNext}), which take the job at
     * the front of the queue, so the queue's order (not the executor's) decides what is made next.
     *
     * <p>Lock rule: never take another lock (counts, queue, the executor's) while inside a {@link #pending} operation
     * (a computeIfAbsent mapping function holds a map bin, and a resize may hold others), and never touch
     * {@link #pending} while holding counts or queue. Either nesting alone is safe; both together deadlock.
     */
    CompletableFuture<Path> schedule(Source source) {
        return enqueue(source).future();
    }

    /** What {@link #enqueue} did: the job's future, and whether this call queued it (false: it was already queued). */
    private record Enqueued(CompletableFuture<Path> future, boolean isNew) {
    }

    /** {@link #schedule}, also saying whether this call is the one that queued the job. */
    private Enqueued enqueue(Source source) {
        Path thumb = source.target(Thumbnails.SIZE);
        Job job = new Job(source, thumb, new CompletableFuture<>(), new AtomicBoolean());
        CompletableFuture<Path> running = pending.putIfAbsent(thumb, job.future());
        if (running != null) {
            return new Enqueued(running, false);
        }
        synchronized (counts) {
            queued++;
        }
        synchronized (queue) {
            queue.putLast(thumb, job);
        }
        try {
            executor.execute(this::runNext);
        } catch (RejectedExecutionException e) {
            // After close(): nothing was queued, so the count must not claim it was; and whoever found the job in
            // pending meanwhile must not wait for it.
            synchronized (queue) {
                queue.remove(thumb, job);
            }
            synchronized (counts) {
                queued--;
            }
            pending.remove(thumb, job.future());
            job.future().completeExceptionally(e);
            throw e;
        }
        return new Enqueued(job.future(), true);
    }

    /**
     * One worker turn: makes the thumbnail at the front of the queue, once the library's background work is not held (a
     * change of library folder copies it meanwhile).
     */
    private void runNext() {
        WorkHold hold;
        try {
            hold = library.get().workHold();
            hold.enter();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // closing: the job stays queued
            return;
        } catch (RuntimeException e) {
            hold = null; // no library (closing): the turn below finds out
        }
        try {
            makeNext();
        } finally {
            if (hold != null) {
                hold.exit();
            }
        }
    }

    private void makeNext() {
        Job job;
        synchronized (queue) {
            Map.Entry<Path, Job> first = queue.pollFirstEntry();
            job = first == null ? null : first.getValue();
        }
        if (job == null) {
            return;
        }
        Path made = null;
        Throwable failure = null;
        try {
            made = maker.make(job.source(), Thumbnails.SIZE,
                    job.shown().get() ? PhotoProcessor.Use.SHOWN : PhotoProcessor.Use.CATCH_UP);
        } catch (Throwable e) {
            failure = e;
        }
        if (failure != null && failure.getCause() instanceof HeicDecodeException busy
                && busy.kind() == HeicDecodeException.Kind.BUSY && requeue(job)) {
            return; // back in line, still queued and pending: this worker takes the next job
        }
        // Counts first, then pending, then the future: a caller that finds the job gone from pending (await), or
        // that waited for its future, then reads settled counts. pending is touched outside the counts' lock
        // (see the lock rule at schedule).
        synchronized (counts) {
            queued--;
            finished = queued == 0 ? 0 : finished + 1;
        }
        pending.remove(job.thumb());
        if (failure == null) {
            job.future().complete(made);
        } else {
            job.future().completeExceptionally(failure);
            if (failure instanceof Error error) {
                throw error;
            }
        }
    }

    /**
     * Puts a HEIC job that found no decoder slot back at the end of the queue (audit 9), with a turn for it; false once
     * the workers are closed.
     */
    private boolean requeue(Job job) {
        synchronized (queue) {
            queue.putIfAbsent(job.thumb(), job);
        }
        try {
            executor.execute(this::runNext);
            return true;
        } catch (RejectedExecutionException e) {
            synchronized (queue) {
                queue.remove(job.thumb(), job);
            }
            return false;
        }
    }

    /** Stops the workers, letting a running job release its file. */
    void close() {
        executor.shutdownNow();
        try {
            // Let a running job release its file (Windows will not move a file that is still open).
            executor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
