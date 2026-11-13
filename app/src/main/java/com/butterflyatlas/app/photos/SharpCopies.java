package com.butterflyatlas.app.photos;

import com.butterflyatlas.app.AtlasException;
import com.butterflyatlas.app.library.Library;
import com.butterflyatlas.app.library.WorkHold;
import com.butterflyatlas.app.library.sync.WorkingCopy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.ClosedByInterruptException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Sharp copies kept on this device: a {@value #SIZE} px (long side) JPEG of a photo for large tiles and cards on
 * high-density screens, where the 480 px thumbnail looks blurry. They are made only when asked for ({@link #lookup}),
 * by one background worker that waits while thumbnails are being made (imports and the startup check go first), and
 * kept in this device's own folder for the library ({@link #onThisDevice}: beside its working copy in the config
 * folder), never in the library's folder, which may be shared through a cloud drive and is what backups and moves copy.
 *
 * <p>A copy is named as the thumbnails are ({@code <id>-960-<cache key>.jpg}), so a changed file gets a new copy and the
 * old one becomes an orphan for {@link #cleanOrphans} (run in the background by {@link #start}, as a library opens, and
 * also removing copies of photos deleted for good, as the thumbnails' tidy does). A photo no bigger than its thumbnail
 * (long side {@value Thumbnails#SIZE} px or less; for a RAW photo, its preview) has none: it would add nothing. A copy is
 * never enlarged, so a photo between the two sizes gets one at its own size. A copy that could not be made is
 * remembered for {@link Thumbnails#RETRY_FAILED_AFTER}, then tried again.
 *
 * <p>The folder is kept under a size limit (1 GB): after each copy is made, the copies used least recently (by their
 * file time, set when a copy is made and each time it is served) are deleted until it is under the limit again.
 */
public final class SharpCopies implements AutoCloseable {

    public static final int SIZE = 960;
    /** The folder beside the working copy that holds the copies. */
    public static final String FOLDER = "sharp";
    /** The most the folder may hold, in bytes. */
    public static final long LIMIT = 1L << 30;

    private static final Logger LOG = LoggerFactory.getLogger(SharpCopies.class);
    /** How often a waiting worker looks again whether the thumbnails are done. */
    static final Duration THUMBNAILS_POLL = Duration.ofMillis(500);

    /** For the route: the copy when it is ready, else whether it is on its way (false: there will be none, for now). */
    public record Lookup(Path file, boolean pending) {
    }

    /** What a copy is made from and where it goes. */
    private record Source(long id, Path file, int orientation, Path target, Path display, boolean heic) {
    }

    private final Supplier<Library> library;
    private final PhotoProcessor processor;
    private final Function<Library, Path> folderFor;
    private final IntSupplier thumbnailsPending;
    private final long limit;
    private final Clock clock;
    private final LongSupplier nanos;
    private final long retryAfterNanos;
    private final long pollMillis;
    private final ExecutorService executor;
    /** Copies waiting for the worker, by their final path, in the order it takes them (guarded by itself). */
    private final LinkedHashMap<Path, Source> queue = new LinkedHashMap<>();
    /** Copies that could not be made, with when (nanoTime). */
    private final Map<Path, Long> failed = new ConcurrentHashMap<>();
    /** HEIC copies remembered as failed, forgotten when the decoder becomes available ({@link #heicAvailable}). */
    private final Set<Path> heic = ConcurrentHashMap.newKeySet();
    /** The copy the worker is writing now (its part file is not an orphan), or null. */
    private volatile Path making;
    /** The folder's size in bytes as last counted and added to since; -1 when it must be counted again. */
    private long total = -1;

    /**
     * @param folderFor         this device's folder for a library's copies; null when it has none (no copies are made)
     * @param thumbnailsPending how many thumbnails are queued or being made: copies wait while it is above 0
     */
    public SharpCopies(Supplier<Library> library, PhotoProcessor processor, Function<Library, Path> folderFor,
                       IntSupplier thumbnailsPending) {
        this(library, processor, folderFor, thumbnailsPending, LIMIT, Clock.systemUTC(), System::nanoTime,
                Thumbnails.RETRY_FAILED_AFTER, THUMBNAILS_POLL);
    }

    SharpCopies(Supplier<Library> library, PhotoProcessor processor, Function<Library, Path> folderFor,
                IntSupplier thumbnailsPending, long limit, Clock clock, LongSupplier nanos, Duration retryFailedAfter,
                Duration poll) {
        this.library = library;
        this.processor = processor;
        this.folderFor = folderFor;
        this.thumbnailsPending = thumbnailsPending;
        this.limit = limit;
        this.clock = clock;
        this.nanos = nanos;
        this.retryAfterNanos = retryFailedAfter.toNanos();
        this.pollMillis = Math.max(1, poll.toMillis());
        this.executor = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "atlas-sharp-copies");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * This device's folder for a library's copies: {@code sharp} beside its working copy,
     * {@code <config folder>/libraries/<library id>/sharp} (one-device design §5), which goes with the working copy when
     * that is cleaned up. Null for a library whose database is not a working copy in {@code configDir} (opened in place):
     * it has no folder of this device's own, so no copies are made.
     */
    public static Function<Library, Path> onThisDevice(Path configDir) {
        Path root = configDir.resolve(WorkingCopy.FOLDER).toAbsolutePath().normalize();
        return lib -> {
            Path working = lib.database().file().toAbsolutePath().normalize().getParent();
            return working != null && root.equals(working.getParent()) ? working.resolve(FOLDER) : null;
        };
    }

    /**
     * For the sharp-copy route, never waiting: the copy if it exists (its use is recorded for the size limit);
     * otherwise, if one can be made, it is queued if need be and moved to the front of the queue, and the answer says
     * it is on its way. NOT_FOUND for an unknown photo.
     */
    public Lookup lookup(long id) {
        Library lib = library.get();
        Path folder = folderFor.apply(lib);
        Source source = source(lib, id, folder);
        if (source == null) {
            return new Lookup(null, false);
        }
        Path target = source.target();
        if (Files.exists(target)) {
            touch(target);
            return new Lookup(target, false);
        }
        if (source.heic() && !HeicStatus.current().isAvailable()) {
            return new Lookup(null, HeicStatus.current().isChecking()); // as the thumbnails: it comes after the check
        }
        if (hasFailed(target)) {
            return new Lookup(null, false);
        }
        try {
            synchronized (queue) {
                boolean queued = queue.containsKey(target);
                queue.putFirst(target, source);
                if (!queued) {
                    executor.execute(this::runNext);
                }
            }
        } catch (RejectedExecutionException e) {
            synchronized (queue) {
                queue.remove(target);
            }
            return new Lookup(null, false); // closed
        }
        return new Lookup(null, true);
    }

    /** Runs {@link #cleanOrphans} on the worker, before any copy waiting; never fails (problems are logged). */
    public void start() {
        try {
            executor.execute(() -> {
                try {
                    int removed = cleanOrphans();
                    if (removed > 0) {
                        LOG.info("Removed {} sharp copies no photo needs", removed);
                    }
                } catch (RuntimeException e) {
                    LOG.warn("Could not tidy the sharp copies", e);
                }
            });
        } catch (RejectedExecutionException e) {
            // closed
        }
    }

    /**
     * Deletes copies no photo needs: those of photos deleted for good, of older versions of a file, and half-written
     * ones. Photos in Recently deleted keep theirs. Returns how many went (0 when there is no folder).
     */
    public int cleanOrphans() {
        Library lib = library.get();
        Path folder = folderFor.apply(lib);
        if (folder == null || !Files.isDirectory(folder)) {
            return 0;
        }
        // List first, then ask which names are wanted: a copy finished in between is in the snapshot, never an orphan.
        List<Path> present = list(folder);
        Set<String> wanted = lib.database().read(c -> {
            Set<String> names = new HashSet<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT id, modified_at, file_size FROM image");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    names.add(Thumbnails.fileName(rs.getLong(1), SIZE, rs.getString(2), rs.getLong(3)));
                }
            }
            return names;
        });
        int removed = 0;
        for (Path file : present) {
            if (!Files.isDirectory(file) && !wanted.contains(file.getFileName().toString()) && !beingMade(file)
                    && PhotoFiles.deleteQuietly(file)) {
                removed++;
            }
        }
        synchronized (this) {
            total = -1;
        }
        return removed;
    }

    /** The computer's HEIC decoder has become available: HEIC copies that failed are tried again when next asked for. */
    public void heicAvailable() {
        for (Path target : List.copyOf(heic)) {
            failed.remove(target);
            heic.remove(target);
        }
    }

    /** Stops the worker, letting a copy being made release its files. */
    @Override
    public void close() {
        executor.shutdownNow();
        try {
            executor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * The photo's source and its copy's path; null when it has no copy to make (no folder on this device, or the photo
     * is no bigger than its thumbnail). NOT_FOUND as {@link CacheFiles#source} for an unknown photo.
     */
    private static Source source(Library lib, long id, Path folder) {
        return lib.database().read(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT CASE WHEN deleted_at IS NULL THEN rel_path"
                    + " ELSE deleted_rel_path END, modified_at, coalesce(orientation, 1), deleted_at IS NOT NULL, file_size,"
                    + " file_type, width, height FROM image WHERE id = ?")) {
                ps.setLong(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next() || rs.getString(1) == null) {
                        throw AtlasException.noPhoto(id);
                    }
                    Path file = PhotoFiles.resolve(lib.folder(), rs.getString(1));
                    if (rs.getBoolean(4) && !Files.exists(file)) {
                        throw AtlasException.noPhoto(id);
                    }
                    int width = rs.getInt(7);
                    boolean known = !rs.wasNull();
                    int height = rs.getInt(8);
                    known &= !rs.wasNull();
                    if (folder == null || (known && Math.max(width, height) <= Thumbnails.SIZE)) {
                        return null; // a sharp copy would add nothing to the thumbnail
                    }
                    String modifiedAt = rs.getString(2);
                    long fileSize = rs.getLong(5);
                    return new Source(id, file, rs.getInt(3),
                            folder.resolve(Thumbnails.fileName(id, SIZE, modifiedAt, fileSize)),
                            lib.folder().resolve(Thumbnails.FOLDER)
                                    .resolve(Thumbnails.fileName(id, Thumbnails.DISPLAY_SIZE, modifiedAt, fileSize)),
                            PhotoFormat.HEIC.fileType().equals(rs.getString(6)));
                }
            }
        });
    }

    private boolean hasFailed(Path target) {
        Long at = failed.get(target);
        if (at == null) {
            return false;
        }
        if (nanos.getAsLong() - at < retryAfterNanos) {
            return true;
        }
        failed.remove(target, at); // expired: tried again
        return false;
    }

    /**
     * One worker turn: once no thumbnails are pending and the library's background work is not held (a change of
     * library folder copies it meanwhile), makes the copy at the front of the queue.
     */
    private void runNext() {
        WorkHold hold;
        try {
            while (thumbnailsPending.getAsInt() > 0) {
                Thread.sleep(pollMillis);
            }
            hold = library.get().workHold();
            hold.enter();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // closing
            return;
        } catch (RuntimeException e) {
            return; // no library (closing)
        }
        try {
            makeNext();
        } finally {
            hold.exit();
        }
    }

    private void makeNext() {
        Source source;
        synchronized (queue) {
            Map.Entry<Path, Source> first = queue.pollFirstEntry();
            source = first == null ? null : first.getValue();
        }
        if (source == null) {
            return;
        }
        Path target = source.target();
        if (Files.exists(target)) {
            return;
        }
        making = target;
        try {
            make(source);
            trim(target);
        } catch (HeicDecodeException e) {
            notThePhotos(source, e);
        } catch (NoSuchFileException e) {
            retryMoved(source, e);
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            fail(source, e);
        } finally {
            making = null;
        }
    }

    /** A photo moved since it was queued (deleted to Recently deleted, or restored): made from where it is now, once. */
    private void retryMoved(Source source, NoSuchFileException e) {
        Source now;
        try {
            Library lib = library.get();
            now = source(lib, source.id(), folderFor.apply(lib));
        } catch (RuntimeException gone) {
            return; // deleted for good: nothing to make
        }
        if (now == null || !now.target().equals(source.target()) || !Files.exists(now.file())) {
            fail(source, e);
            return;
        }
        try {
            make(now);
            trim(now.target());
        } catch (HeicDecodeException again) {
            notThePhotos(now, again);
        } catch (IOException | RuntimeException | OutOfMemoryError again) {
            fail(now, again);
        }
    }

    /**
     * Writes the copy to a part file beside it, then moves it into place, dated now (its first use). A HEIC photo's
     * display copy, when there is one, is used instead of decoding the photo again.
     */
    private void make(Source source) throws IOException {
        Path target = source.target();
        Path part = target.resolveSibling(target.getFileName() + Library.PART);
        try {
            Files.createDirectories(target.getParent());
            if (source.heic() && Files.exists(source.display())) {
                processor.thumbnail(source.display(), 1, SIZE, part, PhotoProcessor.Use.CATCH_UP); // already upright
            } else {
                processor.thumbnail(source.file(), source.orientation(), SIZE, part, PhotoProcessor.Use.CATCH_UP);
            }
            Files.setLastModifiedTime(part, FileTime.from(clock.instant()));
            try {
                Files.move(part, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(part, target);
            }
        } finally {
            PhotoFiles.deleteQuietly(part);
        }
    }

    /**
     * A HEIC copy not made for a reason that is not the photo's: no slot free puts it back at the end of the queue;
     * no decoder leaves nothing behind; a timeout or no working copy waits for the retry time.
     */
    private void notThePhotos(Source source, HeicDecodeException e) {
        switch (e.kind()) {
            case BUSY -> {
                synchronized (queue) {
                    queue.putIfAbsent(source.target(), source);
                }
                try {
                    executor.execute(this::runNext);
                } catch (RejectedExecutionException closed) {
                    synchronized (queue) {
                        queue.remove(source.target());
                    }
                }
            }
            case UNAVAILABLE -> LOG.debug("Photo {}'s sharp copy waits for a HEIC decoder", source.id());
            default -> {
                heic.add(source.target());
                failed.put(source.target(), nanos.getAsLong());
                LOG.info("Photo {}'s sharp copy is tried again later: {}", source.id(), e.getMessage());
            }
        }
    }

    private void fail(Source source, Throwable e) {
        if (e instanceof ClosedByInterruptException || Thread.currentThread().isInterrupted()) {
            return; // closing, not the photo's fault: asked for again after the next start
        }
        failed.put(source.target(), nanos.getAsLong());
        if (source.heic()) {
            heic.add(source.target());
        }
        LOG.warn("Could not make a sharp copy of {}", source.file(), e);
    }

    /** Records a use of a copy (for the size limit): its file time becomes now. Never fails. */
    private void touch(Path file) {
        try {
            Files.setLastModifiedTime(file, FileTime.from(clock.instant()));
        } catch (IOException | RuntimeException e) {
            LOG.debug("Could not record the use of {}", file, e);
        }
    }

    /**
     * After {@code made} was written: while the folder holds more than the limit, the copies used least recently go,
     * never {@code made} itself. The folder is counted only when its size is not known (or over the limit).
     */
    private synchronized void trim(Path made) {
        Path folder = made.getParent();
        try {
            long size = Files.size(made);
            if (total >= 0 && total + size <= limit) {
                total += size;
                return;
            }
        } catch (IOException e) {
            // counted below
        }
        record Copy(Path file, long size, FileTime used) {
        }
        List<Copy> copies = new ArrayList<>();
        long sum = 0;
        for (Path file : list(folder)) {
            try {
                BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class);
                if (attributes.isRegularFile()) {
                    copies.add(new Copy(file, attributes.size(), attributes.lastModifiedTime()));
                    sum += attributes.size();
                }
            } catch (IOException e) {
                // gone meanwhile
            }
        }
        copies.sort(Comparator.comparing(Copy::used));
        int removed = 0;
        for (Copy copy : copies) {
            if (sum <= limit) {
                break;
            }
            if (!copy.file().equals(made) && PhotoFiles.deleteQuietly(copy.file())) {
                sum -= copy.size();
                removed++;
            }
        }
        if (removed > 0) {
            LOG.info("Removed {} sharp copies used least recently, to keep them under {} MB", removed, limit >> 20);
        }
        total = sum;
    }

    private boolean beingMade(Path file) {
        String name = file.getFileName().toString();
        Path now = making;
        return name.endsWith(Library.PART) && now != null
                && now.equals(file.resolveSibling(name.substring(0, name.length() - Library.PART.length())));
    }

    private static List<Path> list(Path folder) {
        List<Path> present = new ArrayList<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(folder)) {
            files.forEach(present::add);
        } catch (NoSuchFileException e) {
            return present;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return present;
    }
}
