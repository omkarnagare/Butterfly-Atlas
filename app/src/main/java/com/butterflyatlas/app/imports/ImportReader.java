package com.butterflyatlas.app.imports;

import com.butterflyatlas.app.AtlasException;
import com.butterflyatlas.app.library.Library;
import com.butterflyatlas.app.photos.HeicDecodeException;
import com.butterflyatlas.app.photos.HeicStatus;
import com.butterflyatlas.app.photos.PhotoFacts;
import com.butterflyatlas.app.photos.PhotoFormat;
import com.butterflyatlas.app.photos.PhotoIngest;
import com.butterflyatlas.app.photos.PhotoProcessor;
import com.butterflyatlas.app.photos.PhotoStore;
import com.butterflyatlas.app.photos.Thumbnails;
import com.butterflyatlas.app.places.PlaceIndex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Reads an import's pending items (design §4.2 step 2), four files at a time: each file once through
 * {@link PhotoIngest#ingest(Path)} (hash, format, facts) and a 480 px preview into {@code .thumbs/import/<run>/}. An
 * item learns whether its bytes are already a live photo in the library ({@code library_taxon_id}, which is then also
 * its suggestion) or belong to an earlier item of this import ({@code copy_of_item_id}), and the place its GPS lies in
 * ({@code gps_place_id}, from the library's place boundaries). Videos, HEIC sequences, AVIF, RAW photos without a
 * preview and files too large are {@code unsupported} with the reason; a photo that cannot be read is {@code failed}. A
 * HEIC photo is read in pure Java with or without a decoder; its preview comes from the computer's decoder (at 960 px,
 * after the thumbnails on screen) and is not attempted while the decoder is not available: the item is read without one
 * and nothing is marked failed ({@link Imports#heicAvailable} fills them in later). A preview that could not be made
 * says so ({@code preview_failed}, Phase 7b audit 6). A {@code .mov} beside a HEIC or JPEG of the same name is a Live
 * Photo's video, and says so (audit 16, D3). Results are
 * saved {@value #CHUNK} at a time; an item the walk changed meanwhile keeps its new state. Reading stops when asked or
 * when the folder disappears. The source is only read.
 */
public final class ImportReader {

    /** How far a phase is; called from worker threads, with {@code done} never going down. */
    @FunctionalInterface
    public interface Progress {
        void report(int done, int total);
    }

    public static final String UNREADABLE = "Butterfly Atlas couldn't read this photo.";

    private static final Logger LOG = LoggerFactory.getLogger(ImportReader.class);
    static final int AT_ONCE = 4;
    static final int CHUNK = 32;

    private final Supplier<Library> library;
    private final PhotoProcessor processor;
    private final PhotoIngest ingest;
    private final Clock clock;
    /** Told the import's id when a reading ends (its HEIC previews still to make are made then, if they can be). */
    private volatile java.util.function.LongConsumer afterReading = id -> { };

    public ImportReader(Supplier<Library> library, PhotoProcessor processor, Clock clock) {
        this.library = library;
        this.processor = processor;
        this.ingest = new PhotoIngest(processor);
        this.clock = clock;
    }

    /** Sets what is told when a reading ends ({@link #afterReading}). */
    void afterReading(java.util.function.LongConsumer then) {
        this.afterReading = then;
    }

    /** An item to read, as the walk saved it. */
    private record Pending(long id, String relPath, long size, String modifiedAt) {
    }

    /**
     * What reading one item found: {@code state} read, unsupported, failed or missing; {@code placeId} from its GPS;
     * {@code previewFailed} as {@link #preview} says.
     */
    private record Outcome(Pending item, String state, String problem, PhotoIngest.Result result, String v, Path preview,
                           String placeId, int previewFailed) {

        Outcome(Pending item, String state, String problem, PhotoIngest.Result result, String v, Path preview) {
            this(item, state, problem, result, v, preview, null, 0);
        }
    }

    /** {@code preview_failed}: made (or not attempted), could not be made, or took too long (tried again once). */
    static final int PREVIEW_OK = 0;
    static final int PREVIEW_FAILED = 1;
    static final int PREVIEW_TIMED_OUT = 2;

    /** The words for a Live Photo's video (Phase 7b audit 16): {@code %s} is its photo's file name. */
    static final String LIVE_PHOTO_VIDEO = "This is the video part of a Live Photo. Its photo (%s) is in this import;"
            + " videos can't be imported.";

    /**
     * What a reading did: how many items it read, and how many it left pending because their file is no longer as the
     * walk saw it (another size or time: the next walk resets them, and they can be read after it).
     */
    public record Reading(int read, int changedSinceWalk) {
    }

    /** Reads every pending item of the import, until done or {@code stop}; returns how many were read. */
    public int read(long runId, Progress progress, BooleanSupplier stop) {
        return readAll(runId, progress, stop).read();
    }

    /** {@link #read}, also telling how many items were left pending as changed since the walk. */
    public Reading readAll(long runId, Progress progress, BooleanSupplier stop) {
        Library lib = library.get();
        ImportStore.Run run = new ImportStore(library, clock).run(runId);
        Path root = run.root();
        int total = lib.database().read(c -> count(c, runId));
        AtomicInteger done = new AtomicInteger();
        AtomicInteger changed = new AtomicInteger();
        progress.report(0, total);
        AtomicInteger n = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(AT_ONCE, task -> {
            Thread thread = new Thread(task, "atlas-import-read-" + n.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        try {
            long after = 0;
            // A folder that went away (a drive unplugged) stops the reading: its files are not missing one by one.
            while (!stop.getAsBoolean() && Files.isDirectory(root)) {
                long cursor = after;
                List<Pending> chunk = lib.database().read(c -> pending(c, runId, cursor));
                if (chunk.isEmpty()) {
                    break;
                }
                List<Future<Outcome>> futures = new ArrayList<>();
                for (Pending item : chunk) {
                    futures.add(pool.submit(() -> {
                        if (stop.getAsBoolean()) {
                            return new Outcome(item, null, null, null, null, null); // not started: stays pending
                        }
                        Outcome outcome = readOne(lib, runId, root, item, changed);
                        if (outcome.state() != null) {
                            synchronized (done) {
                                progress.report(done.incrementAndGet(), total);
                            }
                        }
                        return outcome;
                    }));
                }
                List<Outcome> outcomes = new ArrayList<>();
                for (Future<Outcome> future : futures) {
                    try {
                        outcomes.add(future.get());
                    } catch (ExecutionException e) {
                        LOG.warn("Reading an import item failed unexpectedly", e.getCause());
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return new Reading(done.get(), changed.get());
                    }
                }
                save(runId, outcomes);
                after = chunk.get(chunk.size() - 1).id();
            }
        } finally {
            pool.shutdownNow();
        }
        try {
            afterReading.accept(runId);
        } catch (RuntimeException e) {
            LOG.warn("Could not look for the previews import {} still needs", runId, e);
        }
        return new Reading(done.get(), changed.get());
    }

    /** What a look at the file's entry found. */
    enum Look {
        /** Gone, or not a file (on Windows a cloud placeholder counts as one). */
        MISSING,
        /** Another size or time than the walk saw: the next walk resets the item. */
        CHANGED,
        OK
    }

    /** Decides from the entry's attributes (read without following links) whether the item can be read now. */
    static Look look(BasicFileAttributes attrs, boolean windows, long size, String modifiedAt) {
        if (!ImportNames.isFile(attrs, windows)) {
            return Look.MISSING;
        }
        if (attrs.size() != size) {
            return Look.CHANGED;
        }
        try {
            long saved = Instant.parse(modifiedAt).toEpochMilli();
            return Math.abs(attrs.lastModifiedTime().toMillis() - saved) > 2000 ? Look.CHANGED : Look.OK;
        } catch (RuntimeException e) {
            return Look.CHANGED;
        }
    }

    private Outcome readOne(Library lib, long runId, Path root, Pending item, AtomicInteger changed) {
        Path file;
        try {
            file = ImportStore.resolve(root, item.relPath());
        } catch (IllegalArgumentException e) {
            return new Outcome(item, ImportStore.FAILED, UNREADABLE, null, null, null);
        }
        String name = ImportStore.nameOf(item.relPath());
        try {
            Look look = look(Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS),
                    ImportNames.WINDOWS, item.size(), item.modifiedAt());
            if (look == Look.MISSING) {
                return new Outcome(item, ImportStore.MISSING, null, null, null, null);
            }
            if (look == Look.CHANGED) {
                changed.incrementAndGet();
                return new Outcome(item, null, null, null, null, null);
            }
        } catch (NoSuchFileException e) {
            return new Outcome(item, ImportStore.MISSING, null, null, null, null);
        } catch (IOException | RuntimeException e) {
            LOG.info("Import could not look at {}: {}", file, e.toString());
            return new Outcome(item, ImportStore.FAILED, UNREADABLE, null, null, null);
        }
        String byName = ImportNames.problemByName(name);
        if (byName != null) {
            String photo = ImportNames.isLivePhotoVideo(name) ? livePhotoOf(lib, runId, item.relPath()) : null;
            return new Outcome(item, ImportStore.UNSUPPORTED, photo == null ? byName : LIVE_PHOTO_VIDEO.formatted(photo),
                    null, null, null);
        }
        try {
            if (Files.size(file) > PhotoStore.MAX_BYTES) {
                return new Outcome(item, ImportStore.UNSUPPORTED, ingest.tooLargeMessage(), null, null, null);
            }
            if (!ImportNames.isSupported(name)) {
                // HEIC or AVIF by name: only the first bytes decide, so a large file is not hashed for nothing.
                String refusal = refusalOfHead(file, name);
                if (refusal != null) {
                    return new Outcome(item, ImportStore.UNSUPPORTED, refusal, null, null, null);
                }
            }
            PhotoIngest.Result result = ingest.ingest(file);
            if (result.facts() == null) {
                return new Outcome(item, ImportStore.FAILED, UNREADABLE, null, null, null);
            }
            String v = result.sha256().substring(0, 8);
            Path preview = ImportStore.previewFile(lib.folder(), runId, item.id(), v);
            int previewFailed = preview(file, result.format(), result.facts(), preview, PhotoProcessor.Use.IMPORT);
            return new Outcome(item, ImportStore.READ, null, result, v, preview, placeOf(lib.placeIndex(), result.facts(),
                    file), previewFailed);
        } catch (AtlasException e) {
            String reason = e.getMessage();
            if (PhotoFormat.UNREADABLE.equals(reason) && ImportNames.unsupportedByName(name) != null) {
                reason = ImportNames.unsupportedByName(name);
            }
            return new Outcome(item, ImportStore.UNSUPPORTED, reason, null, null, null);
        } catch (IOException | RuntimeException e) {
            LOG.info("Import could not read {}: {}", file, e.toString());
            return new Outcome(item, ImportStore.FAILED, UNREADABLE, null, null, null);
        }
    }

    /** The place the photo's GPS lies in; null without GPS, outside every boundary, or if the boundaries cannot be read. */
    private static String placeOf(PlaceIndex index, PhotoFacts facts, Path file) {
        if (facts.gps() == null) {
            return null;
        }
        try {
            return index.lookup(facts.gps().lat(), facts.gps().lon()).orElse(null);
        } catch (RuntimeException e) {
            LOG.warn("No place for {}: {}", file, e.toString());
            return null;
        }
    }

    /** For a name Butterfly Atlas does not read as it is: the refusal from its first bytes, or null if they are a photo it reads. */
    private static String refusalOfHead(Path file, String name) throws IOException {
        byte[] head;
        try (InputStream in = Files.newInputStream(file)) {
            head = in.readNBytes(PhotoFormat.HEAD_BYTES);
        }
        if (PhotoFormat.detect(head).isPresent()) {
            return null;
        }
        String refusal = PhotoFormat.refusal(head);
        return PhotoFormat.UNREADABLE.equals(refusal) && ImportNames.unsupportedByName(name) != null
                ? ImportNames.unsupportedByName(name) : refusal;
    }

    /**
     * The preview, made once per read of the file's content; a failure leaves the item without one (logged) and says so:
     * {@link #PREVIEW_FAILED} when the picture could not be read, {@link #PREVIEW_TIMED_OUT} when a HEIC decode took too
     * long, found no decoder slot or no working copy. A HEIC is not attempted while the decoder is not available (nor
     * when it went away meanwhile): {@link #PREVIEW_OK}, without a preview.
     */
    int preview(Path file, PhotoFormat format, PhotoFacts facts, Path preview, PhotoProcessor.Use use) {
        if (Files.exists(preview)) {
            return PREVIEW_OK;
        }
        if (format == PhotoFormat.HEIC && !HeicStatus.current().isAvailable()) {
            return PREVIEW_OK;
        }
        Path part = preview.resolveSibling(preview.getFileName() + "." + Thread.currentThread().threadId() + Library.PART);
        try {
            Files.createDirectories(preview.getParent());
            processor.thumbnail(file, facts.orientation(), Thumbnails.SIZE, part, use);
            try {
                Files.move(part, preview, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(part, preview, StandardCopyOption.REPLACE_EXISTING);
            }
            return PREVIEW_OK;
        } catch (HeicDecodeException e) {
            LOG.info("No preview for {} now: {}", file, e.getMessage());
            return e.kind() == HeicDecodeException.Kind.UNAVAILABLE ? PREVIEW_OK : PREVIEW_TIMED_OUT;
        } catch (IOException | RuntimeException e) {
            LOG.info("No preview for {}: {}", file, e.toString());
            return PREVIEW_FAILED;
        } finally {
            deleteQuietly(part);
        }
    }

    /**
     * The file name of the photo a Live Photo's video goes with: an item of this import in the same folder with the same
     * name (any case) and a HEIC or JPEG extension (a HEIC first); null when there is none.
     */
    private static String livePhotoOf(Library lib, long runId, String videoPath) {
        int slash = videoPath.lastIndexOf('/');
        String folder = videoPath.substring(0, slash + 1);
        String name = videoPath.substring(slash + 1);
        String base = name.substring(0, name.lastIndexOf('.'));
        List<String> siblings = lib.database().read(c -> {
            List<String> paths = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT rel_path FROM import_item WHERE run_id = ?"
                    + " AND substr(rel_path, 1, ?) = ? AND length(rel_path) <= ?")) {
                ps.setLong(1, runId);
                ps.setInt(2, folder.length());
                ps.setString(3, folder);
                ps.setInt(4, folder.length() + base.length() + 6);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        paths.add(rs.getString(1));
                    }
                }
            }
            return paths;
        });
        String jpeg = null;
        for (String path : siblings) {
            String sibling = path.substring(folder.length());
            int dot = sibling.lastIndexOf('.');
            if (sibling.indexOf('/') >= 0 || dot < 0 || !sibling.substring(0, dot).equalsIgnoreCase(base)) {
                continue;
            }
            switch (sibling.substring(dot + 1).toLowerCase(java.util.Locale.ROOT)) {
                case "heic", "heif", "hif" -> {
                    return sibling;
                }
                case "jpg", "jpeg" -> jpeg = jpeg == null ? sibling : jpeg;
                default -> {
                    // not a Live Photo's still
                }
            }
        }
        return jpeg;
    }

    /** Saves a chunk's outcomes in one transaction; an item changed since it was listed is left as it is now. */
    private void save(long runId, List<Outcome> outcomes) {
        if (outcomes.isEmpty()) {
            return;
        }
        List<Path> stale = new ArrayList<>();
        List<Outcome> saved = new ArrayList<>();
        library.get().database().write(c -> {
            long seq = ImportStore.stamp(c, runId, clock.instant());
            List<String> shas = new ArrayList<>();
            for (Outcome outcome : outcomes) {
                if (outcome.state() == null) {
                    continue; // not read: stays pending
                }
                int updated;
                if (ImportStore.READ.equals(outcome.state())) {
                    PhotoIngest.Result result = outcome.result();
                    String libraryTaxon = libraryTaxon(c, result.sha256());
                    try (PreparedStatement ps = c.prepareStatement("UPDATE import_item SET read_state = 'read', problem = NULL,"
                            + " file_size = ?, sha256 = ?, format = ?, captured_at = ?, meta_json = ?, v = ?,"
                            + " library_taxon_id = ?, suggested_taxon_id = coalesce(?, suggested_taxon_id), seq = ?,"
                            + " gps_place_id = ?, file_type = ?, preview_failed = ? WHERE id = ? AND read_state = 'pending'"
                            + " AND file_size = ? AND modified_at = ?")) {
                        ps.setLong(1, result.size());
                        ps.setString(2, result.sha256());
                        ps.setString(3, result.format().name());
                        ps.setString(4, result.facts().capturedAt() == null ? null : result.facts().capturedAt().toString());
                        ps.setString(5, result.facts().metaJson());
                        ps.setString(6, outcome.v());
                        ps.setString(7, libraryTaxon);
                        ps.setString(8, libraryTaxon);
                        ps.setLong(9, seq);
                        ps.setString(10, outcome.placeId());
                        ps.setString(11, result.format().fileType());
                        ps.setInt(12, outcome.previewFailed());
                        bindItem(ps, 13, outcome.item());
                        updated = ps.executeUpdate();
                    }
                    if (updated > 0) {
                        shas.add(result.sha256());
                        saved.add(outcome);
                    }
                } else {
                    try (PreparedStatement ps = c.prepareStatement("UPDATE import_item SET read_state = ?, problem = ?, seq = ?"
                            + " WHERE id = ? AND read_state = 'pending' AND file_size = ? AND modified_at = ?")) {
                        ps.setString(1, outcome.state());
                        ps.setString(2, outcome.problem());
                        ps.setLong(3, seq);
                        bindItem(ps, 4, outcome.item());
                        updated = ps.executeUpdate();
                    }
                }
                if (updated == 0 && outcome.preview() != null && !currentV(c, outcome.item().id(), outcome.v())) {
                    stale.add(outcome.preview()); // re-walked (or discarded) meanwhile: this read no longer counts
                }
            }
            ImportStore.recomputeCopies(c, runId, shas, seq);
            ImportPairs.recompute(c, runId, seq);
            return null;
        });
        stale.forEach(ImportReader::deleteQuietly);
        for (Outcome outcome : saved) {
            deleteOlderPreviews(outcome.preview(), outcome.item().id());
        }
    }

    /** Whether the item's saved {@code v} is already this one (a preview of it must be kept). */
    private static boolean currentV(Connection c, long itemId, String v) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT v FROM import_item WHERE id = ?")) {
            ps.setLong(1, itemId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && v.equals(rs.getString(1));
            }
        }
    }

    /** Removes the item's previews of earlier versions of the file, keeping {@code current}. */
    private static void deleteOlderPreviews(Path current, long itemId) {
        try (var files = Files.newDirectoryStream(current.getParent(), itemId + "-*.jpg")) {
            for (Path file : files) {
                if (!file.getFileName().equals(current.getFileName())) {
                    deleteQuietly(file);
                }
            }
        } catch (IOException e) {
            LOG.warn("Could not tidy previews in {}", current.getParent(), e);
        }
    }

    private static void bindItem(PreparedStatement ps, int at, Pending item) throws SQLException {
        ps.setLong(at, item.id());
        ps.setLong(at + 1, item.size());
        ps.setString(at + 2, item.modifiedAt());
    }

    /** The species already holding a live photo with these bytes (the first by id when several do), or null. */
    static String libraryTaxon(Connection c, String sha256) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT taxon_id FROM image WHERE sha256 = ? AND deleted_at IS NULL"
                + " ORDER BY taxon_id LIMIT 1")) {
            ps.setString(1, sha256);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private static int count(Connection c, long runId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM import_item WHERE run_id = ? AND read_state = 'pending'")) {
            ps.setLong(1, runId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private static List<Pending> pending(Connection c, long runId, long after) throws SQLException {
        List<Pending> items = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT id, rel_path, file_size, modified_at FROM import_item"
                + " WHERE run_id = ? AND read_state = 'pending' AND id > ? ORDER BY id LIMIT ?")) {
            ps.setLong(1, runId);
            ps.setLong(2, after);
            ps.setInt(3, CHUNK);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    items.add(new Pending(rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getString(4)));
                }
            }
        }
        return items;
    }

    static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            LOG.warn("Could not remove {}", file, e);
        }
    }
}
