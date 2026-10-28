package com.butterflyatlas.app.imports;

import com.butterflyatlas.app.AtlasException;
import com.butterflyatlas.app.db.DatabaseBusy;
import com.butterflyatlas.app.db.SqlLists;
import com.butterflyatlas.app.library.Library;
import com.butterflyatlas.app.photos.PhotoIngest;
import com.butterflyatlas.app.photos.PhotoProcessor;
import com.butterflyatlas.app.photos.PhotoStore;
import com.butterflyatlas.app.photos.TaxonRef;
import com.butterflyatlas.app.photos.Thumbnails;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.function.ToLongFunction;

/**
 * Files an import's assigned photos into the library as copies (design §4.4, B9, B10), {@value #BATCH} at a time. Each
 * source file is copied through {@link PhotoIngest} into {@code .incoming} while it is hashed; a copy whose hash differs
 * from the one read (the file was edited since) is filed with the facts read from the copy. Then, in one transaction
 * per batch: copies within the import are marked {@code copy_of}, photos already in the library under the same species
 * {@code duplicate}, the rest are placed exactly as Add photos places them ({@link PhotoStore.Filing}), {@code
 * also_elsewhere} when another species already holds the same bytes, and each item records its outcome. A photo an
 * earlier filing of this import placed in a batch that never committed (the app was killed; the rescan registered it)
 * is taken over instead of being reported as a duplicate (see {@link #strandedByThisImport}). If a batch
 * does not commit, every file it moved goes back to {@code .incoming} and is removed, and the batch is tried one photo
 * per transaction. After the commit each preview becomes its photo's thumbnail. A file that cannot be read fails alone.
 * The source folder is only read; filing stops between batches when asked, or when the folder disappears (its
 * photos are then left untouched: the drive may come back). Problems on the library's side are no photo's fault:
 * before each batch the library's drive must have room for it (plus a tenth and 100 MB), and a copy that cannot be
 * written into {@code .incoming} stops filing too ({@code library_full} or {@code library_unwritable}), leaving the
 * batch untouched. In the rare case that a file of a batch that did not commit cannot be moved back (logged), it stays
 * in its species folder without a record, and its item is left untouched for the rest of that filing.
 */
public final class ImportFiler {

    private static final Logger LOG = LoggerFactory.getLogger(ImportFiler.class);

    static final int BATCH = 50;
    static final String GONE = "The photo is no longer in the folder.";
    static final String LOCKED = "The photo is open in another program, or Butterfly Atlas isn't allowed to read it.";
    static final String NOT_FILED = "Butterfly Atlas couldn't file this photo.";

    /** Called inside each batch's transaction just before it commits (tests make a batch fail here). */
    @FunctionalInterface
    interface BeforeCommit {
        void check(List<Long> itemIds);
    }

    private final Supplier<Library> library;
    private final PhotoStore photos;
    private final Thumbnails thumbnails;
    private final Clock clock;
    private final BeforeCommit beforeCommit;
    private final Opener opener;
    private final Ingester ingester;
    private final ToLongFunction<Path> usableSpace;
    private final HasPhotos hasPhotos;

    /** Whether the import's folder still holds photos ({@link ImportWalker#hasListedFiles}); a seam for tests. */
    @FunctionalInterface
    interface HasPhotos {
        boolean test(Path root, Path data);
    }

    /** Opens a source file for reading; {@code followLinks} only for a Windows cloud placeholder. */
    @FunctionalInterface
    interface Opener {
        Opener NIO = (file, followLinks) -> followLinks ? Files.newInputStream(file)
                : Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS);

        InputStream open(Path file, boolean followLinks) throws IOException;
    }

    /**
     * Copies a stream into {@code .incoming} while hashing it ({@link PhotoIngest#ingest(InputStream, Path, String)}):
     * a failed read of the source is an {@link AtlasException.Kind#INTERRUPTED}, an IOException is the library's side.
     */
    @FunctionalInterface
    interface Ingester {
        PhotoIngest.Result ingest(InputStream in, Path target, String originalName) throws IOException;
    }

    /** What a source is, from its attributes read without following links. */
    enum SourceKind { FILE, PLACEHOLDER, NOT_A_FILE }

    public ImportFiler(Supplier<Library> library, PhotoStore photos, PhotoProcessor processor, Thumbnails thumbnails,
                       Clock clock) {
        this(library, photos, processor, thumbnails, clock, ids -> { });
    }

    ImportFiler(Supplier<Library> library, PhotoStore photos, PhotoProcessor processor, Thumbnails thumbnails, Clock clock,
                BeforeCommit beforeCommit) {
        this(library, photos, processor, thumbnails, clock, beforeCommit, Opener.NIO,
                new PhotoIngest(processor, photos.maxBytes())::ingest, ImportFiler::usableSpace);
    }

    ImportFiler(Supplier<Library> library, PhotoStore photos, PhotoProcessor processor, Thumbnails thumbnails, Clock clock,
                BeforeCommit beforeCommit, Opener opener, Ingester ingester, ToLongFunction<Path> usableSpace) {
        this(library, photos, processor, thumbnails, clock, beforeCommit, opener, ingester, usableSpace,
                ImportWalker::hasListedFiles);
    }

    ImportFiler(Supplier<Library> library, PhotoStore photos, PhotoProcessor processor, Thumbnails thumbnails, Clock clock,
                BeforeCommit beforeCommit, Opener opener, Ingester ingester, ToLongFunction<Path> usableSpace,
                HasPhotos hasPhotos) {
        this.library = library;
        this.photos = photos;
        this.thumbnails = thumbnails;
        this.clock = clock;
        this.beforeCommit = beforeCommit;
        this.opener = opener;
        this.ingester = ingester;
        this.usableSpace = usableSpace;
        this.hasPhotos = hasPhotos;
    }

    /** The usable space of the drive holding {@code folder}, or {@link Long#MAX_VALUE} when it cannot be told. */
    private static long usableSpace(Path folder) {
        try {
            return Files.getFileStore(folder).getUsableSpace();
        } catch (IOException | RuntimeException e) {
            LOG.info("Could not tell the free space of {}: {}", folder, e.toString());
            return Long.MAX_VALUE;
        }
    }

    /** An item to file, as it stood when its batch began. */
    private record Item(long id, String relPath, String sha256, String v, String taxonId, long size) {
    }

    /** One item's copy in {@code .incoming}, or why it could not be made. */
    private record Copy(Item item, Path temp, PhotoIngest.Result received, String failure) {
    }

    /**
     * Files every item that is read, assigned, not skipped and not filed (a failed one is tried again), until done or
     * {@code stop}. Returns how many items got an outcome. Throws {@code library_full} or {@code library_unwritable}
     * (CONFLICT) when the library cannot take the photos; batches already filed stay filed, the rest are untouched.
     */
    public int file(long runId, ImportReader.Progress progress, BooleanSupplier stop) {
        Library lib = library.get();
        Path root = new ImportStore(library, clock).run(runId).root();
        List<Long> ids = lib.database().read(c -> fileable(c, runId));
        if (ids.isEmpty() || stop.getAsBoolean()) {
            return 0; // no filing happened: it is not counted
        }
        // A new filing of this import: its outcomes carry its number, for the result's "last filing" figures. The half of
        // a pair the choice leaves out is marked only when the chosen half is filed (fixes J3, see leaveOutPartner).
        ids = lib.database().write(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE import_run SET filings = filings + 1,"
                    + " last_species_before = NULL WHERE id = ?")) {
                ps.setLong(1, runId);
                ps.executeUpdate();
            }
            return fileable(c, runId);
        });
        progress.report(0, ids.size());
        int done = 0;
        for (int from = 0; from < ids.size(); from += BATCH) {
            if (stop.getAsBoolean() || !Files.isDirectory(root)) {
                break;
            }
            List<Long> batch = ids.subList(from, Math.min(ids.size(), from + BATCH));
            Batch filed = fileBatch(lib, runId, root, batch, stop);
            done += filed.done();
            progress.report(from + filed.reached(), ids.size());
            if (filed.reached() < batch.size()) {
                break; // stopped part-way: the rest are left as they are
            }
        }
        return done;
    }

    /** What a batch did: how many items got an outcome, and how far into the batch it went (all of it unless stopped). */
    private record Batch(int done, int reached) {
    }

    /** True when the import has something to file. */
    public boolean hasWork(long runId) {
        return !library.get().database().read(c -> fileable(c, runId)).isEmpty();
    }

    /** Room kept free on the library's drive beyond a batch: a tenth of it, and 100 MB. */
    static long needed(long bytes) {
        return bytes + bytes / 10 + 100L * 1024 * 1024;
    }

    /**
     * Copies the batch's photos, then files the copies in one transaction. A stop is heard before each copy: the copies
     * already made are filed (copying happens outside the transaction, so nothing is half done) and the rest are left.
     */
    private Batch fileBatch(Library lib, long runId, Path root, List<Long> batch, BooleanSupplier stop) {
        List<Item> items = lib.database().read(c -> items(c, runId, batch));
        long bytes = items.stream().mapToLong(Item::size).sum();
        if (usableSpace.applyAsLong(lib.folder()) < needed(bytes)) {
            LOG.warn("Filing stopped: the library's drive has less than {} bytes free", needed(bytes));
            throw ImportErrors.libraryFull(); // before anything is copied: no item is touched
        }
        List<Copy> copies = new ArrayList<>();
        // Asked at most once per batch, and only when a source is missing: it may walk a large folder (or a slow drive).
        FolderCheck folderHasPhotos = new FolderCheck(() -> hasPhotos.test(root, lib.folder()));
        int reached = batch.size();
        try {
            for (Item item : items) {
                if (stop.getAsBoolean()) {
                    reached = batch.indexOf(item.id()); // a pause or a cancel: file what is copied so far
                    break;
                }
                Copy copy = copy(lib.folder(), root, item, folderHasPhotos);
                if (copy == null) {
                    break; // the folder is gone (a drive unplugged): the rest are left as they are
                }
                copies.add(copy);
            }
            Map<Long, Path> previews = new LinkedHashMap<>();
            int done;
            PhotoStore.Filing together = photos.filing();
            try {
                done = write(lib, runId, copies, previews, together);
            } catch (RuntimeException e) {
                LOG.warn("A batch of {} photos could not be filed together; filing them one at a time", copies.size(), e);
                done = 0;
                for (Copy copy : copies) {
                    if (notPutBack(together, copy)) {
                        continue;
                    }
                    done += fileAlone(lib, runId, copy, previews);
                }
            }
            thumbnails.adopt(previews);
            photos.batchFiled(); // a pending taxonomy update's summary follows the photos
            return new Batch(done, reached);
        } finally {
            for (Copy copy : copies) {
                if (copy.temp() != null) {
                    ImportReader.deleteQuietly(copy.temp()); // placed copies are no longer here
                }
            }
        }
    }

    /**
     * One copy filed in a transaction of its own. One the database stayed busy for (another window's long write) is tried
     * {@link DatabaseBusy#RETRIES} more times, then recorded as not filed in the busy words (audit A16); any other failure
     * is recorded as not filed. Returns how many got an outcome.
     */
    private int fileAlone(Library lib, long runId, Copy copy, Map<Long, Path> previews) {
        for (int attempt = 0; ; attempt++) {
            PhotoStore.Filing alone = photos.filing();
            try {
                return write(lib, runId, List.of(copy), previews, alone);
            } catch (RuntimeException failure) {
                if (notPutBack(alone, copy)) {
                    return 0;
                }
                boolean busy = DatabaseBusy.is(failure);
                if (busy && attempt < DatabaseBusy.RETRIES) {
                    LOG.info("The database was busy filing {}; trying again", copy.item().relPath());
                    continue;
                }
                LOG.warn("Could not file {}", copy.item().relPath(), failure);
                return write(lib, runId, List.of(new Copy(copy.item(), null, null, busy ? DatabaseBusy.MESSAGE : NOT_FILED)),
                        previews, photos.filing());
            }
        }
    }

    /**
     * Whether this copy was placed by a transaction that did not commit and could not be moved back. Its file stays in
     * the species folder without a record (logged), and the item is left untouched for the rest of this filing.
     */
    private static boolean notPutBack(PhotoStore.Filing filing, Copy copy) {
        if (copy.temp() != null && filing.notPutBack().contains(copy.temp())) {
            LOG.warn("{} was copied into the library but could not be taken back out; leaving it for now",
                    copy.item().relPath());
            return true;
        }
        return false;
    }

    /**
     * Copies an item's source into {@code .incoming}, hashing it. A source that cannot be read is the photo's failure;
     * null when the import's folder itself is gone (nothing is recorded); a copy that cannot be written into the
     * library stops filing ({@code library_full} or {@code library_unwritable}), blaming no photo.
     */
    private Copy copy(Path data, Path root, Item item, FolderCheck folderHasPhotos) {
        Path temp = data.resolve(Library.INCOMING).resolve(UUID.randomUUID() + Library.PART);
        Path source;
        try {
            source = ImportStore.resolve(root, item.relPath());
        } catch (IllegalArgumentException e) {
            return new Copy(item, temp, null, ImportReader.UNREADABLE);
        }
        SourceKind kind;
        InputStream in;
        try {
            // Looked at without following links: a link or folder put in its place since the walk is not the photo. A
            // cloud placeholder on Windows ("other") is, as for the walk and the read.
            kind = kind(Files.readAttributes(source, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS), ImportNames.WINDOWS);
            if (kind == SourceKind.NOT_A_FILE) {
                return new Copy(item, temp, null, GONE);
            }
            in = opener.open(source, kind == SourceKind.PLACEHOLDER); // read only
        } catch (NoSuchFileException e) {
            return gone(folderHasPhotos, item, temp);
        } catch (FileSystemException e) {
            LOG.info("Could not read {} for filing: {}", source, e.toString());
            return new Copy(item, temp, null, LOCKED);
        } catch (IOException | RuntimeException e) {
            LOG.info("Could not read {} for filing: {}", source, e.toString());
            return new Copy(item, temp, null, ImportReader.UNREADABLE);
        }
        try {
            try {
                Files.createDirectories(temp.getParent());
            } catch (IOException | RuntimeException e) {
                throw libraryProblem(data, temp, item, e);
            }
            try {
                return new Copy(item, temp, ingester.ingest(in, temp, ImportStore.nameOf(item.relPath())), null);
            } catch (AtlasException e) {
                if (e.kind() != AtlasException.Kind.INTERRUPTED) {
                    return new Copy(item, temp, null, e.getMessage()); // too large, not a photo: the photo's own
                }
                // The source stopped part-way: a drive that went away, or a file that could not be read to the end.
                closeQuietly(in);
                LOG.info("Reading {} for filing stopped part-way", source);
                return Files.exists(source, LinkOption.NOFOLLOW_LINKS) ? new Copy(item, temp, null, ImportReader.UNREADABLE)
                        : gone(folderHasPhotos, item, temp);
            } catch (IOException e) {
                // Reads of the source fail as INTERRUPTED: an IOException is the copy's side (.incoming).
                throw libraryProblem(data, temp, item, e);
            } catch (RuntimeException e) {
                LOG.info("Could not read {} for filing: {}", source, e.toString());
                return new Copy(item, temp, null, ImportReader.UNREADABLE);
            }
        } finally {
            closeQuietly(in); // before the copy is moved; a failure to close a source that was read in full is harmless
        }
    }

    /**
     * A source that is not there: the photo's failure, or null (nothing recorded) when the whole folder is gone or holds
     * no photos any more (an emptied folder, a mount point left behind by an unplugged drive).
     */
    private static Copy gone(FolderCheck folderHasPhotos, Item item, Path temp) {
        if (!folderHasPhotos.get()) {
            ImportReader.deleteQuietly(temp);
            return null;
        }
        return new Copy(item, temp, null, GONE);
    }

    /** Whether the folder still holds photos, found out on the first question and remembered (one batch). */
    private static final class FolderCheck {
        private final BooleanSupplier check;
        private Boolean answer;

        FolderCheck(BooleanSupplier check) {
            this.check = check;
        }

        boolean get() {
            if (answer == null) {
                answer = check.getAsBoolean();
            }
            return answer;
        }
    }

    /** The library could not take a copy: removes it and tells whether the drive is full or cannot be written. */
    private AtlasException libraryProblem(Path data, Path temp, Item item, Exception e) {
        ImportReader.deleteQuietly(temp);
        boolean full = usableSpace.applyAsLong(data) < needed(item.size());
        LOG.warn("Filing stopped: could not copy {} into the library ({})", item.relPath(), full ? "drive full" : "not writable", e);
        return full ? ImportErrors.libraryFull() : ImportErrors.libraryUnwritable();
    }

    private static void closeQuietly(InputStream in) {
        try {
            in.close();
        } catch (IOException | RuntimeException e) {
            LOG.debug("Could not close a source", e);
        }
    }

    /**
     * What a source with these attributes (read without following links) is: a file, a Windows cloud placeholder (an
     * "other" file the walk and the read accept too, opened following links so that the cloud client fetches it), or
     * not a file at all (a link or folder put in its place: the photo is no longer there).
     */
    static SourceKind kind(BasicFileAttributes attrs, boolean windows) {
        if (!ImportNames.isFile(attrs, windows)) {
            return SourceKind.NOT_A_FILE;
        }
        return attrs.isRegularFile() ? SourceKind.FILE : SourceKind.PLACEHOLDER;
    }

    /** One transaction for these copies; adds the previews to adopt once it has committed. Returns how many got an outcome. */
    private int write(Library lib, long runId, List<Copy> copies, Map<Long, Path> previews, PhotoStore.Filing filing) {
        Map<Long, Path> adopt = new LinkedHashMap<>();
        List<Long> make = new ArrayList<>();
        int done = lib.database().write(c -> {
            long seq = ImportStore.stamp(c, runId, clock.instant());
            String choice = pairChoice(c, runId);
            boolean speciesNoted = false;
            int outcomes = 0;
            List<Long> ids = new ArrayList<>();
            for (Copy copy : copies) {
                Item item = copy.item();
                ids.add(item.id());
                if (!stillFileable(c, item)) {
                    continue; // changed since the batch began
                }
                if (copy.failure() != null) {
                    outcome(c, item.id(), ImportStore.OUT_FAILED, null, copy.failure(), false, null, null, seq);
                    outcomes++;
                    continue;
                }
                PhotoIngest.Result received = copy.received();
                boolean changed = !received.sha256().equals(item.sha256());
                Long filedCopy = filedWithSameBytes(c, runId, item.id(), received.sha256());
                if (filedCopy != null) {
                    outcome(c, item.id(), ImportStore.COPY_OF, null, "Same photo as " + relPath(c, filedCopy) + ".", false,
                            filedCopy, changed ? received : null, seq);
                } else if (!isSpecies(c, item.taxonId())) {
                    outcome(c, item.id(), ImportStore.OUT_FAILED, null, "Photos can only be filed under a species.", false, null,
                            changed ? received : null, seq);
                } else {
                    List<TaxonRef> holders = filing.holders(c, received.sha256());
                    boolean inSpecies = holders.stream().anyMatch(t -> t.id().equals(item.taxonId()));
                    Long stranded = inSpecies ? strandedByThisImport(c, runId, received.sha256(), item.taxonId()) : null;
                    if (stranded != null) {
                        // Placed by an earlier filing of this import that never committed (the app was killed): take it.
                        if (!speciesNoted) {
                            noteSpeciesBefore(c, runId);
                            speciesNoted = true;
                        }
                        PhotoStore.Placed placed = filing.adopt(c, stranded, item.taxonId(), ImportStore.nameOf(item.relPath()),
                                received, tagKeys(c, item.id()));
                        userPlace(c, item.id(), placed.imageId());
                        Optional<TaxonRef> elsewhere = holders.stream().filter(t -> !t.id().equals(item.taxonId())).findFirst();
                        outcome(c, item.id(), elsewhere.isPresent() ? ImportStore.ALSO_ELSEWHERE : ImportStore.FILED,
                                placed.imageId(), elsewhere.map(t -> "Also filed under " + name(t) + ".").orElse(null),
                                firstForSpecies(c, runId, item.taxonId(), placed.imageId()), null,
                                changed ? received : null, seq);
                        make.add(placed.imageId()); // its file is the library's own: the thumbnail queue makes one if missing
                    } else if (inSpecies) {
                        outcome(c, item.id(), ImportStore.DUPLICATE, imageOf(c, received.sha256(), item.taxonId()),
                                "Already in your atlas.", false, null, changed ? received : null, seq);
                    } else {
                        if (!speciesNoted) {
                            noteSpeciesBefore(c, runId); // before this import's first photo goes in: for its milestone
                            speciesNoted = true;
                        }
                        PhotoStore.Placed placed = filing.place(c, item.taxonId(), copy.temp(), ImportStore.nameOf(item.relPath()),
                                received, tagKeys(c, item.id()));
                        userPlace(c, item.id(), placed.imageId());
                        String kind = holders.isEmpty() ? ImportStore.FILED : ImportStore.ALSO_ELSEWHERE;
                        String note = holders.isEmpty() ? null : "Also filed under " + name(holders.get(0)) + ".";
                        outcome(c, item.id(), kind, placed.imageId(), note,
                                firstForSpecies(c, runId, item.taxonId(), placed.imageId()), null, changed ? received : null, seq);
                        if (!changed && item.v() != null) {
                            adopt.put(placed.imageId(), ImportStore.previewFile(lib.folder(), runId, item.id(), item.v()));
                        } else {
                            make.add(placed.imageId()); // no preview of this content: the thumbnail queue makes one
                        }
                    }
                }
                leaveOutPartner(c, item.id(), choice, seq);
                outcomes++;
            }
            beforeCommit.check(ids);
            return outcomes;
        }, filing::undo);
        previews.putAll(adopt);
        make.forEach(thumbnails::request);
        return done;
    }

    /**
     * The place the user chose for the item, as it stands now, goes on the filed photo as the user's (none when they
     * removed it); the place consumer, which has just run, never writes over it again. Without a choice the photo
     * keeps what the consumer found.
     */
    private static void userPlace(Connection c, long itemId, long imageId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("UPDATE image SET place_id = (SELECT user_place_id FROM import_item"
                + " WHERE id = ?), place_source = 'user' WHERE id = ? AND EXISTS (SELECT 1 FROM import_item WHERE id = ?"
                + " AND user_place_set = 1)")) {
            ps.setLong(1, itemId);
            ps.setLong(2, imageId);
            ps.setLong(3, itemId);
            ps.executeUpdate();
        }
    }

    private static void noteSpeciesBefore(Connection c, long runId) throws SQLException {
        // Before this import's (and this filing's) first photo goes in: for the milestones. Photos an earlier filing of
        // this import left behind (taken over below) are not counted: they go in with this filing.
        String before = "(SELECT COUNT(DISTINCT i.taxon_id) FROM image i WHERE i.deleted_at IS NULL AND NOT " + LEFT_BEHIND
                + ")";
        try (PreparedStatement ps = c.prepareStatement("UPDATE import_run AS r SET"
                + " species_before = coalesce(species_before, " + before + "),"
                + " last_species_before = coalesce(last_species_before, " + before + ") WHERE id = ?")) {
            ps.setLong(1, runId);
            ps.executeUpdate();
        }
    }

    /**
     * SQL on {@code image i} and {@code import_run r}, asked during a filing (whose number is already counted): a photo
     * the rescan registered after the import was created, when an earlier filing of it had begun, and that no import
     * item claims.
     */
    private static final String STRANDED = "i.source = 'rescan' AND r.filings > 1"
            + " AND julianday(i.added_at) >= julianday(r.created_at)" // as instants: "08:00:00.5Z" is after "08:00:00Z"
            + " AND NOT EXISTS (SELECT 1 FROM import_item x WHERE x.image_id = i.id)";

    /**
     * SQL on {@code image i} and {@code import_run r}: {@link #STRANDED} with the bytes and species of an item of the
     * import still to be filed, so the photo goes in with this filing (it is taken over when that item's turn comes).
     */
    private static final String LEFT_BEHIND = "(" + STRANDED + " AND EXISTS (SELECT 1 FROM import_item y"
            + " WHERE y.run_id = r.id AND (y.outcome IS NULL OR y.outcome = 'failed') AND y.sha256 = i.sha256"
            + " AND y.taxon_id = i.taxon_id))";

    /**
     * Whether the photo is its species' first: no other live photo of the species, leaving out those an earlier filing
     * of this import left behind (they go in with this filing, and only one of its photos is the first).
     */
    private static boolean firstForSpecies(Connection c, long runId, String taxonId, long imageId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM image i, import_run r WHERE r.id = ?"
                + " AND i.taxon_id = ? AND i.deleted_at IS NULL AND i.id <> ? AND NOT " + LEFT_BEHIND + " LIMIT 1")) {
            ps.setLong(1, runId);
            ps.setString(2, taxonId);
            ps.setLong(3, imageId);
            try (ResultSet rs = ps.executeQuery()) {
                return !rs.next();
            }
        }
    }

    /**
     * A live photo of the species with these bytes that the rescan registered after this import was created, when an
     * earlier filing of it had begun, and that no item claims: a photo an earlier filing of this import moved into place in a batch
     * that never committed (the app was killed). Null if none. A photo added any other way (Add photos, an import's
     * committed filing) is never taken over: it stays a duplicate.
     */
    private static Long strandedByThisImport(Connection c, long runId, String sha256, String taxonId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT i.id FROM image i, import_run r WHERE r.id = ?"
                + " AND i.sha256 = ? AND i.taxon_id = ? AND i.deleted_at IS NULL AND " + STRANDED
                + " ORDER BY i.id LIMIT 1")) {
            ps.setLong(1, runId);
            ps.setString(2, sha256);
            ps.setString(3, taxonId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : null;
            }
        }
    }

    /** Records an item's outcome; with {@code changed}, the facts of the content actually filed replace the ones read. */
    private static void outcome(Connection c, long itemId, String outcome, Long imageId, String error, boolean first,
                                Long copyOf, PhotoIngest.Result changed, long seq) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("UPDATE import_item SET outcome = ?, image_id = ?, error = ?,"
                + " first_for_species = ?, copy_of_item_id = ?, seq = ?,"
                + " filing_no = (SELECT filings FROM import_run WHERE id = import_item.run_id) WHERE id = ?")) {
            ps.setString(1, outcome);
            ps.setObject(2, imageId);
            ps.setString(3, error);
            ps.setInt(4, first ? 1 : 0);
            ps.setObject(5, copyOf);
            ps.setLong(6, seq);
            ps.setLong(7, itemId);
            ps.executeUpdate();
        }
        if (changed != null) {
            try (PreparedStatement ps = c.prepareStatement("UPDATE import_item SET sha256 = ?, format = ?, captured_at = ?,"
                    + " meta_json = ?, v = ?, file_size = ? WHERE id = ?")) {
                ps.setString(1, changed.sha256());
                ps.setString(2, changed.format().name());
                ps.setString(3, changed.facts().capturedAt() == null ? null : changed.facts().capturedAt().toString());
                ps.setString(4, changed.facts().metaJson());
                ps.setString(5, changed.sha256().substring(0, 8));
                ps.setLong(6, changed.size());
                ps.setLong(7, itemId);
                ps.executeUpdate();
            }
        }
    }

    private static String pairChoice(Connection c, long runId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT pair_choice FROM import_run WHERE id = ?")) {
            ps.setLong(1, runId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    /**
     * With the choice {@code jpeg} or {@code raw}: once the chosen half of a pair has a filed-type outcome (in this same
     * transaction), its other half is left out ({@code pair_left}, with the reason as its error, any earlier failure of
     * it cleared: it is no longer to be filed). The choice so takes effect per shot (fixes J3): a pause, a full drive or
     * a failed copy before then leaves both halves as they were.
     */
    private static void leaveOutPartner(Connection c, long itemId, String choice, long seq) throws SQLException {
        if (!ImportPairs.JPEG.equals(choice) && !ImportPairs.RAW.equals(choice)) {
            return;
        }
        String leftType;
        try (PreparedStatement ps = c.prepareStatement("SELECT file_type FROM import_item WHERE id ="
                + " (SELECT pair_item_id FROM import_item WHERE id = ?)")) {
            ps.setLong(1, itemId);
            try (ResultSet rs = ps.executeQuery()) {
                leftType = rs.next() ? rs.getString(1) : null;
            }
        }
        if (leftType == null) {
            return;
        }
        String chosen = ImportPairs.JPEG.equals(choice) ? ImportPairs.PARTNER_TYPES : "('raw')";
        try (PreparedStatement ps = c.prepareStatement("UPDATE import_item SET pair_left = 1, outcome = NULL, image_id = NULL,"
                + " error = ?, seq = ?, filing_no = (SELECT filings FROM import_run WHERE id = import_item.run_id)"
                + " WHERE id = (SELECT pair_item_id FROM import_item WHERE id = ? AND file_type IN " + chosen
                + " AND outcome IN " + ImportPairs.FILED_TYPE + ") AND pair_left = 0"
                + " AND (outcome IS NULL OR outcome = 'failed')")) {
            ps.setString(1, ImportPairs.leftOut(leftType, ImportPairs.kinds(c, runIdOf(c, itemId))));
            ps.setLong(2, seq);
            ps.setLong(3, itemId);
            ps.executeUpdate();
        }
    }

    private static long runIdOf(Connection c, long itemId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT run_id FROM import_item WHERE id = ?")) {
            ps.setLong(1, itemId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : -1;
            }
        }
    }

    private static List<Long> fileable(Connection c, long runId) throws SQLException {
        List<Long> ids = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT id FROM import_item WHERE run_id = ? AND " + FILEABLE
                + " ORDER BY id")) {
            ps.setLong(1, runId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ids.add(rs.getLong(1));
                }
            }
        }
        return ids;
    }

    /**
     * SQL on {@code import_item}: read, assigned, not skipped, and not filed (a failed filing is tried again); a half of
     * a RAW + JPEG or RAW + HEIC pair only when the choice files it ({@code both}, or its own type: {@code jpeg} is
     * the JPEG or HEIC half), so none before the choice is made, and not the half already left out.
     */
    static final String FILEABLE = "read_state = 'read' AND taxon_id IS NOT NULL AND skipped = 0"
            + " AND (outcome IS NULL OR outcome = 'failed') AND pair_left = 0 AND (pair_item_id IS NULL"
            + " OR (SELECT pair_choice FROM import_run WHERE id = import_item.run_id)"
            + " IN ('both', CASE WHEN file_type = 'heic' THEN 'jpeg' ELSE file_type END))";

    private static List<Item> items(Connection c, long runId, List<Long> ids) throws SQLException {
        List<Item> items = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT id, rel_path, sha256, v, taxon_id, file_size FROM import_item"
                + " WHERE run_id = ? AND id IN (" + SqlLists.marks(ids.size()) + ") AND " + FILEABLE + " ORDER BY id")) {
            ps.setLong(1, runId);
            SqlLists.bind(ps, 2, ids);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    items.add(new Item(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                            rs.getLong(6)));
                }
            }
        }
        return items;
    }

    /** Whether the item is still to be filed under the same species as when its batch began. */
    private static boolean stillFileable(Connection c, Item item) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM import_item WHERE id = ? AND taxon_id = ? AND " + FILEABLE)) {
            ps.setLong(1, item.id());
            ps.setString(2, item.taxonId());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /** Another item of this import with these bytes that is already in the library (one it filed first), if any. */
    private static Long filedWithSameBytes(Connection c, long runId, long itemId, String sha256) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT id FROM import_item WHERE run_id = ? AND sha256 = ? AND id <> ?"
                + " AND outcome IN ('filed', 'also_elsewhere', 'duplicate')"
                + " ORDER BY CASE WHEN outcome = 'duplicate' THEN 1 ELSE 0 END, id LIMIT 1")) {
            ps.setLong(1, runId);
            ps.setString(2, sha256);
            ps.setLong(3, itemId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : null;
            }
        }
    }

    private static boolean isSpecies(Connection c, String taxonId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM taxon WHERE id = ? AND rank = 'species'")) {
            ps.setString(1, taxonId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static Long imageOf(Connection c, String sha256, String taxonId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT id FROM image WHERE sha256 = ? AND taxon_id = ?"
                + " AND deleted_at IS NULL ORDER BY id LIMIT 1")) {
            ps.setString(1, sha256);
            ps.setString(2, taxonId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : null;
            }
        }
    }

    private static String relPath(Connection c, long itemId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT rel_path FROM import_item WHERE id = ?")) {
            ps.setLong(1, itemId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : "another photo";
            }
        }
    }

    private static List<String> tagKeys(Connection c, long itemId) throws SQLException {
        List<String> keys = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT t.key FROM import_item_tag it JOIN tag t ON t.id = it.tag_id"
                + " WHERE it.item_id = ?")) {
            ps.setLong(1, itemId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    keys.add(rs.getString(1));
                }
            }
        }
        return keys;
    }

    private static String name(TaxonRef taxon) {
        return taxon.common() != null ? taxon.common() + " (" + taxon.name() + ")" : taxon.name();
    }
}
