package com.butterflyatlas.app.photos;

import com.butterflyatlas.app.AtlasException;
import com.butterflyatlas.app.db.DatabaseBusy;
import com.butterflyatlas.app.library.Library;
import com.butterflyatlas.app.photos.RescanWrites.Candidate;
import com.butterflyatlas.app.photos.RescanWrites.NewPhoto;
import com.butterflyatlas.app.photos.RescanWrites.Row;
import com.butterflyatlas.app.photos.consumers.Dimensions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongConsumer;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Startup check of {@code images/} (design §7); photos a taxonomy update not finished yet is moving are left to it. A
 * folder whose name ends in {@code [A…]} or
 * {@code [U…]} is that species' folder, whatever the rest of its name; supported photos in it
 * that the library does not know are registered ({@code source = 'rescan'}, untagged, no number), a RAW file without a
 * usable preview too, with no details, so the gallery says it can't be shown (fixes J1).
 * Everything else is left alone. The files are counted first, so {@link #progress()} can say how
 * far a run has got. Each file is read once through {@link PhotoIngest}; new photos are registered
 * {@link #BATCH} to a transaction, with their snapshot and every fact consumer's work ({@link FactConsumers}), and get
 * their thumbnails queued as each batch is saved. Batches are written while the walk goes on, so a file
 * removed meanwhile gets a record that the next run removes.
 *
 * <p>The rescan is deliberately conservative, because a wrong guess here loses a user's tags,
 * numbers and covers:
 * <ul>
 *   <li><b>Files still being copied</b> (modified in the last {@link #SETTLE}) and folders starting with
 *       {@code .} or {@code @} ({@code .Trashes}, {@code @eaDir}) are skipped; the next run takes the former.</li>
 *   <li><b>Renames follow the file.</b> A record whose file is gone is matched by content (same species,
 *       SHA-256 and size) with a file found under a new name or folder, and the record is updated in place,
 *       keeping tags, number and cover. A file moved into another species' folder is not matched: numbers and
 *       covers belong to a species, so it counts as a new photo there (and the old record as missing).</li>
 *   <li><b>Case-only renames</b> on case-insensitive file systems (macOS, Windows): the old spelling still
 *       "exists", so a record whose exact spelling is no longer in its folder listing, while a file differing
 *       only in case is, is treated like a missing one and reconciled by content as above, instead of getting
 *       a second record for the one file. This is decided from the folder listing, not from the file system's
 *       case rules, so it needs no platform checks.</li>
 *   <li><b>Edited files</b> (size or modification time changed, 2 seconds' tolerance) are re-read, so the
 *       thumbnail cache key (which holds the modified time) changes; the ledger forgets them, their snapshot is
 *       rewritten and the fact consumers process them again at once (the thumbnail is made again later). An edited
 *       file whose details cannot be read keeps its old GPS place until they can.</li>
 *   <li><b>Records are removed only when the walk was trustworthy</b>: never while any file is still arriving
 *       (it could be the file a record is waiting for), never if {@code images/} is missing
 *       or unreadable (an unplugged drive), never if any folder could not be listed, and never if more than
 *       half the live records (and at least {@link #MASS_MISSING}) look missing at once.</li>
 *   <li>One bad file or folder is logged and skipped; it never aborts the run, and the progress always ends
 *       with {@code finishedAt} set.</li>
 * </ul>
 *
 * <p>Here: the walk and matching files with records; the writes are {@link RescanWrites}.
 */
public final class Rescan {

    /** What a run changed. */
    public record Result(int added, int removed) {
    }

    /**
     * A run's progress, or the last finished run's summary: files {@code checked} of {@code total}
     * (supported files in species folders), photos {@code added} (rising as each batch is committed), records {@code removed}, photos {@code updated} (followed by rename, or refreshed after an edit);
     * {@code finishedAt} is null while the run is going.
     */
    public record Progress(int checked, int total, int added, int removed, int updated, String finishedAt) {
    }

    /**
     * Files in the library's species folders the last run did not add, grouped by a short reason, with what to do and up
     * to {@value #EXAMPLES} of them ({@code species folder/file name}), most first (Phase 7b audit D2, review fix J12).
     */
    public record Left(String reason, String next, int count, List<String> examples) {
    }

    /** Examples a {@link Left} names at most. */
    static final int EXAMPLES = 5;

    /** The short reason and what to do for a refusal's words (review fix J12). */
    private static String[] group(String refusal) {
        if (PhotoFormat.HEIC_SEQUENCE.equals(refusal)) {
            return new String[] {"HEIC image sequences (several frames in one file) aren't supported",
                    "Save the frame you want as a photo and add that."};
        }
        if (PhotoFormat.HEIF_OTHER.equals(refusal)) {
            return new String[] {"HEIF photos stored in a way Butterfly Atlas can't read", "Save them as JPEG and add those."};
        }
        if (PhotoFormat.AVIF.equals(refusal)) {
            return new String[] {"AVIF photos aren't supported yet", "Save them as JPEG and add those."};
        }
        return new String[] {"Files Butterfly Atlas can't read as photos",
                "Move them out of the library folder, or replace them with JPEG copies."};
    }

    /** The group of files the database stayed busy for (audit A16): another window's long write. */
    static final String BUSY_REASON = "Photos that couldn't be added while another window was saving changes";
    static final String BUSY_NEXT = "They're added the next time Butterfly Atlas opens.";

    /** The rescan's access to its writes (tests make them busy). */
    RescanWrites writes() {
        return writes;
    }

    /** A run's refusals of one group: how many, and the first few. */
    private static final class Refused {
        final String next;
        int count;
        final List<String> examples = new ArrayList<>();

        Refused(String next) {
            this.next = next;
        }
    }

    /** What the walk of {@code images/} found, and whether it saw everything. */
    private static final class Walk {
        final List<Path> folders = new ArrayList<>();
        boolean complete = true;
    }

    /**
     * Photo names the app recognises but does not read as they are (HEIF sequences, AVIF): their bytes decide, and a
     * refusal is told in {@link #left()} (audit D2) instead of the file being passed over in silence.
     */
    private static final Set<String> RECOGNISED = Set.of("heics", "heifs", "avif");

    private static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
    }

    /** Files modified more recently than this are probably still being copied. */
    static final Duration SETTLE = Duration.ofSeconds(5);
    /** Modified times closer than this are the same (file systems round differently). */
    private static final Duration SAME_TIME = Duration.ofSeconds(2);
    /** Records are never mass-removed below this many missing. */
    static final int MASS_MISSING = 20;
    /** New photos registered per write transaction. */
    static final int BATCH = 200;

    private static final Logger LOG = LoggerFactory.getLogger(Rescan.class);
    private static final Pattern SPECIES_FOLDER = Pattern.compile(".*\\[([AU]\\d+)]$");

    private final Supplier<Library> library;
    private final PhotoIngest ingest;
    private final RescanWrites writes;
    private final Clock clock;
    private final Clock fileClock;
    private final Duration settle;
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile Progress progress;
    /** What the last finished run left out (none before the first), and whether the user put its note away. */
    private volatile List<Left> left = List.of();
    private volatile boolean leftDismissed;
    /** Refusals of the run under way, by reason. */
    private final Map<String, Refused> refusing = new java.util.LinkedHashMap<>();

    /**
     * With {@code dimensions} as the only fact consumer (tests).
     *
     * @param found told the id of every photo the rescan registers or refreshes (Main queues its thumbnail)
     */
    public Rescan(Supplier<Library> library, PhotoProcessor processor, Clock clock, LongConsumer found) {
        this(library, processor, clock, found, Clock.systemUTC(), SETTLE);
    }

    /** @param consumers the fact consumers every new or edited photo gets in the transaction that saves it */
    public Rescan(Supplier<Library> library, PhotoProcessor processor, Clock clock, LongConsumer found,
                  List<PhotoConsumer> consumers) {
        this(library, new PhotoIngest(processor), clock, found, Clock.systemUTC(), SETTLE, consumers);
    }

    /** @param fileClock tells "now" when judging a file's age (the files' times are real, whatever {@code clock} says) */
    Rescan(Supplier<Library> library, PhotoProcessor processor, Clock clock, LongConsumer found, Clock fileClock,
           Duration settle) {
        this(library, new PhotoIngest(processor), clock, found, fileClock, settle);
    }

    Rescan(Supplier<Library> library, PhotoIngest ingest, Clock clock, LongConsumer found, Clock fileClock,
           Duration settle) {
        this(library, ingest, clock, found, fileClock, settle, List.of(new Dimensions()));
    }

    Rescan(Supplier<Library> library, PhotoIngest ingest, Clock clock, LongConsumer found, Clock fileClock,
           Duration settle, List<PhotoConsumer> consumers) {
        this.library = library;
        this.ingest = ingest;
        this.writes = new RescanWrites(ingest, clock, found, consumers);
        this.writes.onBusy(photo -> left(BUSY_REASON, BUSY_NEXT, photo.file().file()));
        this.clock = clock;
        this.fileClock = fileClock;
        this.settle = settle;
    }

    /** The running run's progress, else the last run's summary; null before the first run. */
    public Progress progress() {
        return progress;
    }

    /**
     * The files the last finished run did not add, grouped by reason (audit D2), until the user puts the note away
     * ({@link #dismissLeft}) or the next run finishes; empty when none.
     */
    public List<Left> left() {
        return leftDismissed ? List.of() : left;
    }

    /** Hides {@link #left()} until the next run. */
    public void dismissLeft() {
        leftDismissed = true;
    }

    /** Whether a run is in progress (the UI shows "Checking your photos…"). */
    public boolean running() {
        return running.get();
    }

    /** Runs once; a second call while one is running returns at once with nothing changed. */
    public Result run() {
        if (!running.compareAndSet(false, true)) {
            return new Result(0, 0);
        }
        int total = 0;
        int checked = 0;
        int added = 0;
        int removed = 0;
        int updated = 0;
        synchronized (refusing) {
            refusing.clear();
        }
        try {
            progress = new Progress(0, 0, 0, 0, 0, null); // counting; total follows
            Library lib = library.get();
            Set<String> species = lib.database().read(c -> {
                Set<String> ids = new HashSet<>();
                try (PreparedStatement ps = c.prepareStatement("SELECT id FROM taxon WHERE rank = 'species'");
                     ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        ids.add(rs.getString(1));
                    }
                }
                return ids;
            });
            List<Row> rows = lib.database().read(c -> {
                List<Row> live = new ArrayList<>();
                try (PreparedStatement ps = c.prepareStatement("SELECT id, taxon_id, rel_path, file_name, file_size,"
                        + " modified_at, sha256 FROM image WHERE deleted_at IS NULL");
                     ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        live.add(new Row(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                                rs.getLong(5), rs.getString(6), rs.getString(7)));
                    }
                }
                return live;
            });
            // A taxonomy update not finished yet is moving these photos (5b contract §3): their records and files at either
            // path are left to it.
            Set<Long> moving = new HashSet<>();
            Set<String> movingPaths = new HashSet<>();
            lib.database().read(c -> {
                try (PreparedStatement ps = c.prepareStatement("SELECT m.image_id, m.old_rel, m.new_rel FROM taxonomy_move m"
                        + " JOIN taxonomy_update u ON u.id = m.update_id WHERE u.state IN ('running', 'paused') AND m.done = 0");
                     ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        moving.add(rs.getLong(1));
                        movingPaths.add(fold(rs.getString(2)));
                        movingPaths.add(fold(rs.getString(3)));
                    }
                }
                return null;
            });
            rows.removeIf(row -> moving.contains(row.id()));
            Map<String, Row> byPath = new HashMap<>();
            for (Row row : rows) {
                byPath.put(row.relPath(), row);
            }

            Walk walk = new Walk();
            walkImages(lib.folder().resolve("images"), walk);
            Set<String> listed = new HashSet<>();
            Set<String> listedFolded = new HashSet<>();
            List<Candidate> candidates = new ArrayList<>();
            int arriving = 0;
            for (Path folder : walk.folders) {
                Matcher id = SPECIES_FOLDER.matcher(folder.getFileName().toString());
                if (!id.matches() || !species.contains(id.group(1))) {
                    continue;
                }
                try (Stream<Path> files = Files.list(folder)) {
                    for (Path file : files.sorted().toList()) {
                        String name = file.getFileName().toString();
                        if (name.startsWith(".") || (PhotoFormat.fromFileName(name).isEmpty()
                                && !PhotoFormat.isRawFileName(name) && !RECOGNISED.contains(extension(name)))) {
                            continue;
                        }
                        BasicFileAttributes attrs;
                        try {
                            attrs = Files.readAttributes(file, BasicFileAttributes.class);
                        } catch (IOException e) {
                            LOG.warn("Could not read {}", file, e);
                            walk.complete = false;
                            continue;
                        }
                        if (!attrs.isRegularFile()) {
                            continue;
                        }
                        String relPath = relative(lib.folder(), file);
                        if (movingPaths.contains(fold(relPath))) {
                            continue;
                        }
                        listed.add(relPath);
                        listedFolded.add(fold(relPath));
                        Instant modified = attrs.lastModifiedTime().toInstant();
                        if (stillArriving(modified)) {
                            arriving++;
                        } else {
                            candidates.add(new Candidate(id.group(1), file, relPath, attrs.size(), modified));
                        }
                    }
                } catch (IOException | UncheckedIOException e) {
                    LOG.warn("Could not list {}", folder, e);
                    walk.complete = false;
                }
            }

            // Records whose file is gone, or whose spelling changed in case only, wait for a file with the same content.
            List<Row> gone = new ArrayList<>();
            Map<String, Deque<Row>> byContent = new HashMap<>();
            for (Row row : rows) {
                // A file the walk just listed under this very spelling is there: only the others are looked for on
                // disk (a spelling changed in case, a folder not walked), saving a file check per photo at every start.
                boolean missing = !listed.contains(row.relPath())
                        && !Files.isRegularFile(PhotoFiles.resolve(lib.folder(), row.relPath()));
                if (missing && writes.putBack(lib, row)) {
                    missing = false;
                }
                boolean respelled = !missing && !listed.contains(row.relPath()) && listedFolded.contains(fold(row.relPath()));
                if (missing) {
                    gone.add(row);
                }
                if ((missing || respelled) && row.sha256() != null) {
                    byContent.computeIfAbsent(contentKey(row.taxonId(), row.sha256(), row.size()), k -> new ArrayDeque<>())
                            .addLast(row);
                }
            }
            Set<Long> followed = new HashSet<>();

            total = candidates.size();
            progress = new Progress(0, total, 0, 0, 0, null);
            List<NewPhoto> batch = new ArrayList<>();
            for (Candidate candidate : candidates) {
                try {
                    Row known = byPath.get(candidate.relPath());
                    if (known != null) {
                        if (changed(known, candidate) && writes.refresh(lib, known, candidate)) {
                            updated++;
                        }
                    } else {
                        PhotoIngest.Result read = read(candidate.file());
                        if (read != null && read.size() != candidate.size()) {
                            // Changed between listing and reading: still arriving; the next run decides.
                            arriving++;
                        } else if (read != null) {
                            Deque<Row> same = byContent.get(contentKey(candidate.taxonId(), read.sha256(), candidate.size()));
                            Row moved = same == null ? null : same.pollFirst();
                            if (moved == null) {
                                batch.add(new NewPhoto(candidate, read));
                            } else if (DatabaseBusy.retry("rescanning", () -> writes.follow(lib, moved, candidate))) {
                                followed.add(moved.id());
                                writes.notifyFound(moved.id());
                                updated++;
                            }
                        }
                    }
                } catch (RuntimeException e) {
                    LOG.warn("Rescan skipped {}", candidate.file(), e);
                }
                checked++;
                if (batch.size() >= BATCH) {
                    added += writes.register(lib, batch);
                    batch.clear();
                }
                progress = new Progress(checked, total, added, removed, updated, null);
            }
            added += writes.register(lib, batch);
            progress = new Progress(checked, total, added, removed, updated, null);

            List<Row> missing = gone.stream().filter(r -> !followed.contains(r.id())).toList();
            if (!walk.complete) {
                if (!missing.isEmpty()) {
                    LOG.warn("Rescan: the photo folders could not all be read, so {} records without a file were kept", missing.size());
                }
            } else if (arriving > 0) {
                // A record whose file is being copied in right now (a rename or an overwrite in progress) must
                // not be mistaken for a missing one; the next run, with the copy settled, decides.
                if (!missing.isEmpty()) {
                    LOG.info("Rescan: {} files are still arriving, so {} records without a file were kept for now", arriving, missing.size());
                }
            } else if (missing.size() >= MASS_MISSING && missing.size() * 2 > rows.size()) {
                LOG.warn("Rescan: {} of {} photos look missing at once (a drive or folder gone?), so none were removed",
                        missing.size(), rows.size());
            } else {
                for (Row row : missing) {
                    try {
                        if (DatabaseBusy.retry("rescanning", () -> writes.remove(lib, row.id(), row.relPath()))) {
                            removed++;
                            progress = new Progress(checked, total, added, removed, updated, null);
                        }
                    } catch (RuntimeException e) {
                        LOG.warn("Rescan could not remove the record of {}", row.relPath(), e);
                    }
                }
            }
            if (added > 0 || removed > 0 || updated > 0) {
                LOG.info("Rescan: {} photos found, {} renamed or changed, {} missing photos removed", added, updated, removed);
            }
            synchronized (refusing) {
                left = refusing.entrySet().stream()
                        .sorted(java.util.Comparator.<Map.Entry<String, Refused>>comparingInt(e -> -e.getValue().count)
                                .thenComparing(Map.Entry::getKey))
                        .map(e -> new Left(e.getKey(), e.getValue().next, e.getValue().count,
                                List.copyOf(e.getValue().examples)))
                        .toList();
            }
            leftDismissed = false;
            return new Result(added, removed);
        } finally {
            // Always, so the UI's "checking" ends even if the run failed.
            progress = new Progress(checked, total, added, removed, updated, clock.instant().toString());
            running.set(false);
        }
    }

    /**
     * Collects species folders under {@code images/}. A folder that cannot be read marks the walk
     * incomplete (and is skipped), as does {@code images/} itself missing or unreadable.
     */
    private static void walkImages(Path images, Walk walk) {
        if (!Files.isDirectory(images) || !Files.isReadable(images)) {
            walk.complete = false;
            return;
        }
        try {
            Files.walkFileTree(images, EnumSet.noneOf(FileVisitOption.class), Integer.MAX_VALUE, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    String name = dir.getFileName().toString();
                    if (!dir.equals(images) && (name.startsWith(".") || name.startsWith("@"))) {
                        return FileVisitResult.SKIP_SUBTREE; // .Trashes, @eaDir, …
                    }
                    if (SPECIES_FOLDER.matcher(name).matches()) {
                        walk.folders.add(dir);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException e) {
                    LOG.warn("Rescan could not read {}", file, e);
                    walk.complete = false;
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException e) {
                    if (e != null) {
                        LOG.warn("Rescan could not finish reading {}", dir, e);
                        walk.complete = false;
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException | UncheckedIOException e) {
            LOG.warn("Rescan could not walk {}", images, e);
            walk.complete = false;
        }
    }

    /**
     * Modified within the settle time: probably still being copied. A time well in the future is a wrong
     * camera or card-reader clock, not a copy in progress, so it does not hold a photo back for ever.
     * (A copy that keeps an old modified time cannot be told apart; if it is caught half done, the next run
     * sees a different size and re-reads the file.)
     */
    private boolean stillArriving(Instant modified) {
        Instant now = fileClock.instant();
        return modified.isAfter(now.minus(settle)) && !modified.isAfter(now.plus(Duration.ofMinutes(1)));
    }

    private static String fold(String relPath) {
        return relPath.toLowerCase(Locale.ROOT);
    }

    private static String contentKey(String taxonId, String sha256, long size) {
        return taxonId + "|" + sha256 + "|" + size;
    }

    private static String relative(Path data, Path file) {
        List<String> parts = new ArrayList<>();
        for (Path part : data.relativize(file)) {
            parts.add(part.toString());
        }
        return String.join("/", parts);
    }

    /** Whether a known file's size or modified time differs from its record's (modified times within 2 seconds are the same). */
    private static boolean changed(Row row, Candidate file) {
        if (row.size() != file.size()) {
            return true;
        }
        try {
            return Duration.between(Instant.parse(row.modifiedAt()), file.modified()).abs().compareTo(SAME_TIME) > 0;
        } catch (RuntimeException e) {
            return true;
        }
    }

    /** A file this run left out, in its group: counted, and named while the group has fewer than a few examples. */
    private void left(String reason, String next, Path file) {
        synchronized (refusing) {
            Refused refused = refusing.computeIfAbsent(reason, k -> new Refused(next));
            refused.count++;
            if (refused.examples.size() < EXAMPLES) {
                refused.examples.add(file.getParent().getFileName() + "/" + file.getFileName());
            }
        }
    }

    /** One pass over a file the library does not know; null if it is not a supported photo or cannot be read. */
    private PhotoIngest.Result read(Path file) {
        try {
            return ingest.ingestKeepingRaw(file);
        } catch (AtlasException e) {
            LOG.info("Rescan left {} alone: {}", file, e.getMessage());
            String[] why = group(e.getMessage());
            left(why[0], why[1], file);
            return null;
        } catch (IOException e) {
            LOG.warn("Could not read {}", file, e);
            return null;
        }
    }
}
