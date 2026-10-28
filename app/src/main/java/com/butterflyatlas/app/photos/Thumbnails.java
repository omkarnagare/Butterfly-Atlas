package com.butterflyatlas.app.photos;

import com.butterflyatlas.app.library.Library;
import com.butterflyatlas.app.photos.CacheFiles.Source;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/**
 * The image cache (design §5) in {@code <data>/.thumbs/}: thumbnails
 * {@code <id>-480-<modified millis>-<bytes>.jpg}, made by two background threads in queue order (a photo the
 * gallery asks for moves to the front, {@link #lookup}), and display copies
 * {@code <id>-2560-<modified millis>-<bytes>.jpg} for photos the viewer cannot show as they are (TIFF in
 * WebView2), made on the request that needs one. A name changes whenever the photo does, so a
 * ready file can be cached by the browser for good. Photos in Recently deleted keep theirs until purged.
 *
 * <p>This worker is the pixel consumer {@value #CONSUMER} (design §3.3): each thumbnail made (once its file is in
 * place) or failed is recorded in the ledger, dated by the clock given, and {@link #catchUp()} queues the live
 * photos without a current row. Raising {@link #VERSION} renames thumbnails ({@code -t<version>}), so all are made
 * again and the old ones become orphans.
 *
 * <p>Here: the cache's names, adopting import previews, the placeholder and tidying; the queue and its workers are
 * {@link ThumbnailQueue}, display copies {@link DisplayCopies}, making one copy {@link CopyMaker}, and a photo's source
 * file {@link CacheFiles}.
 */
public final class Thumbnails implements AutoCloseable {

    public static final int SIZE = 480;
    public static final int DISPLAY_SIZE = 2560;
    public static final String FOLDER = ".thumbs";
    /** The placeholder colour before a photo's own is known. */
    public static final String NEUTRAL = "#d9d9d9";
    /** Ledger key of the thumbnail consumer. */
    public static final String CONSUMER = "thumbnail";
    /** Raise when thumbnails must all be made again (a new size or look). */
    public static final int VERSION = 1;

    private static final Logger LOG = LoggerFactory.getLogger(Thumbnails.class);
    /** How long a failure is remembered; after that the file is tried again (a Windows lock, a copy still going). */
    static final Duration RETRY_FAILED_AFTER = Duration.ofMinutes(10);
    /** How long a request for a display copy waits for another request that is making the same one. */
    static final Duration DISPLAY_WAIT = Duration.ofSeconds(30);

    /**
     * Preview progress for the UI: thumbnails queued or being made, and how many were finished
     * since the queue was last empty (back to 0 when nothing is pending).
     */
    public record Progress(int pending, int done) {
    }

    /** For the thumbnail route: the thumbnail when it is ready, else whether it is on its way (false once it has failed). */
    public record Lookup(Path file, boolean pending) {
    }

    private final Supplier<Library> library;
    private final ProcessingLedger ledger;
    /** The ledger version written and asked for: {@link #VERSION}, or another in tests. */
    private final int version;
    /** Files being made, by their final path (thumbnails and display copies). */
    private final Map<Path, CompletableFuture<Path>> pending = new ConcurrentHashMap<>();
    private final CopyMaker maker;
    private final ThumbnailQueue queue;
    private final DisplayCopies displays;

    public Thumbnails(Supplier<Library> library, PhotoProcessor processor) {
        this(library, processor, Clock.systemUTC());
    }

    /** @param clock dates the ledger rows */
    public Thumbnails(Supplier<Library> library, PhotoProcessor processor, Clock clock) {
        this(library, processor, clock, VERSION, System::nanoTime, RETRY_FAILED_AFTER, DISPLAY_WAIT);
    }

    Thumbnails(Supplier<Library> library, PhotoProcessor processor, LongSupplier nanos, Duration retryFailedAfter,
               Duration displayWait) {
        this(library, processor, Clock.systemUTC(), VERSION, nanos, retryFailedAfter, displayWait);
    }

    /** With another ledger version (tests: as if {@link #VERSION} had been raised). */
    Thumbnails(Supplier<Library> library, PhotoProcessor processor, Clock clock, int version, LongSupplier nanos,
               Duration retryFailedAfter, Duration displayWait) {
        this.library = library;
        this.ledger = new ProcessingLedger(clock);
        this.version = version;
        this.maker = new CopyMaker(library, processor, ledger, version, nanos, retryFailedAfter.toNanos());
        this.queue = new ThumbnailQueue(library, ledger, version, maker, pending);
        this.displays = new DisplayCopies(library, version, pending, maker, displayWait);
    }

    /**
     * The cache key of a file at the current {@link #VERSION}: the photo's {@code v}, which changes with the file
     * and when thumbnails are made anew.
     */
    public static String cacheKey(String modifiedAt, long fileSize) {
        return cacheKey(modifiedAt, fileSize, VERSION);
    }

    /**
     * {@code <modified millis>-<bytes>} at version 1 (as in 3a, so existing thumbnails stay valid), else
     * {@code <modified millis>-<bytes>-t<version>}.
     */
    static String cacheKey(String modifiedAt, long fileSize, int version) {
        return Instant.parse(modifiedAt).toEpochMilli() + "-" + fileSize + (version > 1 ? "-t" + version : "");
    }

    /** {@code 12-480-1791709200000-345678.jpg} (with {@code -t<version>} before {@code .jpg} once raised). */
    public static String fileName(long id, String modifiedAt, long fileSize) {
        return fileName(id, SIZE, modifiedAt, fileSize);
    }

    /** {@code 12-<size>-<cache key>.jpg} at the current {@link #VERSION}. */
    public static String fileName(long id, int size, String modifiedAt, long fileSize) {
        return fileName(id, size, modifiedAt, fileSize, VERSION);
    }

    /**
     * {@code 12-<size>-<cache key>.jpg}; the thumbnail version is in thumbnail names only (display copies do not
     * change with it). An older version's thumbnail thus has another name: an orphan for {@link #cleanOrphans}.
     */
    static String fileName(long id, int size, String modifiedAt, long fileSize, int version) {
        return id + "-" + size + "-" + cacheKey(modifiedAt, fileSize, size == SIZE ? version : 1) + ".jpg";
    }

    /** Queues the thumbnail of a photo unless it exists; never fails (problems are logged). */
    public void request(long id) {
        queue.request(id);
    }

    /** See {@link ThumbnailQueue#lookup}: never waits. */
    public Lookup lookup(long id) {
        return queue.lookup(id);
    }

    /** Test-only (see {@link ThumbnailQueue#await}): the thumbnail, made now if needed, waiting at most {@code timeout}. */
    public Optional<Path> await(long id, Duration timeout) {
        return queue.await(id, timeout);
    }

    /** See {@link DisplayCopies#display}. */
    public Path display(long id) {
        return displays.display(id);
    }

    public Progress progress() {
        return queue.progress();
    }

    /** See {@link ThumbnailQueue#catchUp}. */
    public int catchUp() {
        return queue.catchUp();
    }

    /**
     * The computer's HEIC decoder has become available (at start, or after Check again; Phase 7b contract §3, audit 12):
     * the HEIC thumbnails that failed (in the ledger and in memory) are forgotten, and every HEIC photo without a done
     * thumbnail is queued. Never fails (problems are logged); returns how many were queued.
     */
    public int heicAvailable() {
        try {
            maker.forgetHeic();
            Library lib = library.get();
            int cleared = lib.database().write(c -> {
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM image_processing WHERE consumer = ?"
                        + " AND status = 'failed' AND image_id IN (SELECT id FROM image WHERE file_type = ?)")) {
                    ps.setString(1, CONSUMER);
                    ps.setString(2, PhotoFormat.HEIC.fileType());
                    return ps.executeUpdate();
                }
            });
            if (cleared > 0) {
                LOG.info("Trying the {} HEIC thumbnails that failed again, now that a decoder works", cleared);
            }
            return queue.catchUp();
        } catch (RuntimeException e) {
            LOG.warn("Could not queue the HEIC thumbnails", e);
            return 0;
        }
    }

    /**
     * For photos an import has just filed: each one's 480 px preview, made from the same bytes when the import read the
     * file, becomes its thumbnail without a second decode (design §4.4 step 3). The preview is moved into place under
     * the thumbnail's name, and the thumbnails are recorded in the ledger in one transaction, each only while the photo
     * still has the file version it was moved for (as {@link CopyMaker#record} does). A preview that cannot be moved is left to
     * the queue ({@link #request}). No dominant colour is worked out: the placeholder is only shown while a thumbnail
     * is missing. Never fails (problems are logged); returns how many previews became thumbnail files (moved into
     * place, or dropped because the thumbnail was there already), not how many ledger rows were written: a photo
     * changed meanwhile keeps its file but stays pending for catch-up.
     */
    public int adopt(Map<Long, Path> previews) {
        if (previews.isEmpty()) {
            return 0;
        }
        List<Source> adopted = new ArrayList<>();
        try {
            Library lib = library.get();
            List<Long> ids = new ArrayList<>(previews.keySet());
            List<Source> sources = lib.database().read(c -> CacheFiles.liveSources(c, lib.folder(), ids, version));
            for (Source source : sources) {
                Path preview = previews.get(source.id());
                Path thumb = source.target(SIZE);
                try {
                    if (Files.exists(thumb)) {
                        PhotoFiles.deleteQuietly(preview); // made meanwhile by a request: the same picture
                    } else {
                        CacheFiles.movePreview(preview, thumb);
                    }
                    adopted.add(source);
                } catch (IOException | RuntimeException e) {
                    LOG.info("Could not use the import preview of photo {}; making its thumbnail: {}", source.id(), e.toString());
                    request(source.id());
                }
            }
            if (!adopted.isEmpty()) {
                lib.database().write(c -> {
                    List<Long> current = new ArrayList<>();
                    for (Source source : adopted) {
                        if (CacheFiles.current(c, source)) {
                            current.add(source.id());
                        }
                    }
                    ledger.markDone(c, current, CONSUMER, version);
                    return null;
                });
            }
        } catch (RuntimeException e) {
            LOG.warn("Could not record the thumbnails of {} imported photos", previews.size(), e);
        }
        return adopted.size();
    }

    /** A 1×1 PNG in the photo's dominant colour (once known), else neutral grey: shown while the thumbnail is made. */
    public byte[] placeholder(long id) {
        return png(maker.color(id, NEUTRAL));
    }

    /**
     * Deletes cached files that no photo needs any more: thumbnails and display copies of purged
     * photos, of older versions of a file, thumbnails of an older {@link #VERSION}, and half-written ones. Photos in Recently deleted keep
     * theirs. Folders (import previews in {@code import/}) are not this method's to tidy. Returns how many went.
     */
    public int cleanOrphans() {
        Library lib = library.get();
        Path folder = lib.folder().resolve(FOLDER);
        if (!Files.isDirectory(folder)) {
            return 0;
        }
        // List first, then ask which names are wanted: a thumbnail finished in between is then
        // in the snapshot, never mistaken for an orphan.
        List<Path> present = new ArrayList<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(folder)) {
            files.forEach(present::add);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Set<String> wanted = lib.database().read(c -> {
            Set<String> names = new HashSet<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT id, modified_at, file_size FROM image");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    names.add(fileName(rs.getLong(1), SIZE, rs.getString(2), rs.getLong(3), version));
                    names.add(fileName(rs.getLong(1), DISPLAY_SIZE, rs.getString(2), rs.getLong(3), version));
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
        return removed;
    }

    @Override
    public void close() {
        queue.close();
    }

    private boolean beingMade(Path file) {
        String name = file.getFileName().toString();
        return name.endsWith(Library.PART)
                && pending.containsKey(file.resolveSibling(name.substring(0, name.length() - Library.PART.length())));
    }

    /** A 1×1 PNG of one colour ({@code #rrggbb}), written by hand so the core needs no image library. */
    static byte[] png(String color) {
        int rgb = Integer.parseInt(color.substring(1), 16);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A});
        chunk(out, "IHDR", new byte[] {0, 0, 0, 1, 0, 0, 0, 1, 8, 2, 0, 0, 0}); // 1×1, 8-bit RGB
        Deflater deflater = new Deflater();
        deflater.setInput(new byte[] {0, (byte) (rgb >> 16), (byte) (rgb >> 8), (byte) rgb}); // filter 0, then R G B
        deflater.finish();
        byte[] buffer = new byte[64];
        int length = deflater.deflate(buffer);
        deflater.end();
        byte[] data = new byte[length];
        System.arraycopy(buffer, 0, data, 0, length);
        chunk(out, "IDAT", data);
        chunk(out, "IEND", new byte[0]);
        return out.toByteArray();
    }

    private static void chunk(ByteArrayOutputStream out, String type, byte[] data) {
        byte[] name = type.getBytes(StandardCharsets.US_ASCII);
        int length = data.length;
        out.writeBytes(new byte[] {(byte) (length >> 24), (byte) (length >> 16), (byte) (length >> 8), (byte) length});
        out.writeBytes(name);
        out.writeBytes(data);
        CRC32 crc = new CRC32();
        crc.update(name);
        crc.update(data);
        long value = crc.getValue();
        out.writeBytes(new byte[] {(byte) (value >> 24), (byte) (value >> 16), (byte) (value >> 8), (byte) value});
    }
}
