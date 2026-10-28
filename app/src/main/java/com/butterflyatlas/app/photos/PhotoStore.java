package com.butterflyatlas.app.photos;

import com.butterflyatlas.app.AtlasException;
import com.butterflyatlas.app.db.Database;
import com.butterflyatlas.app.library.Library;
import com.butterflyatlas.app.photos.consumers.Dimensions;
import com.butterflyatlas.app.taxa.TaxonSnapshot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.LongConsumer;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Every change to photo files (design §4, §6): adding photos and filing imported ones here; moving, deleting, restoring
 * and purging ({@link PhotoChanges}), covers ({@link Covers}) and the Recently deleted sweep ({@link DeletedSweep}) are
 * handed on. The database and the folders change together; when a step fails, files are put back.
 */
public final class PhotoStore {

    private static final Logger LOG = LoggerFactory.getLogger(PhotoStore.class);

    /** The largest photo the app accepts: 300 MB. */
    public static final long MAX_BYTES = 300L * 1024 * 1024;

    /** Species photographed at which the celebration shows a badge. */
    public static final List<Integer> MILESTONES = List.of(10, 25, 50, 100, 250, 500, 1000);

    private final Supplier<Library> library;
    private final PhotoIngest ingest;
    private final FactConsumers facts;
    private final Clock clock;
    private final LongConsumer added;
    private final PhotoChanges changes;
    private final Covers covers;
    private final DeletedSweep sweep;
    private final DeletedCount deleted = new DeletedCount();
    private final List<Runnable> changed = new CopyOnWriteArrayList<>();
    /** The library's version: goes up with every write that changes photos (multi-window contract §10, A8). */
    private final java.util.concurrent.atomic.AtomicLong version = new java.util.concurrent.atomic.AtomicLong();
    /** The startup's purge skipped while a taxonomy update held the gate (its age), run when the update finishes. */
    private volatile Duration skippedPurge;

    /**
     * With {@code dimensions} as the only fact consumer (tests).
     *
     * @param added told the id of every photo added, after it is saved (Main queues its thumbnail)
     */
    public PhotoStore(Supplier<Library> library, PhotoProcessor processor, Clock clock, LongConsumer added) {
        this(library, processor, clock, added, MAX_BYTES);
    }

    public PhotoStore(Supplier<Library> library, PhotoProcessor processor, Clock clock, LongConsumer added, long maxBytes) {
        this(library, processor, clock, added, maxBytes, List.of(new Dimensions()));
    }

    /** @param consumers the fact consumers every added or filed photo gets in its own transaction */
    public PhotoStore(Supplier<Library> library, PhotoProcessor processor, Clock clock, LongConsumer added,
                      List<PhotoConsumer> consumers) {
        this(library, processor, clock, added, MAX_BYTES, consumers);
    }

    public PhotoStore(Supplier<Library> library, PhotoProcessor processor, Clock clock, LongConsumer added, long maxBytes,
                      List<PhotoConsumer> consumers) {
        this.library = library;
        this.ingest = new PhotoIngest(processor, maxBytes);
        this.facts = new FactConsumers(consumers, new ProcessingLedger(clock));
        this.clock = clock;
        this.added = added;
        this.changes = new PhotoChanges(library, clock, deleted);
        this.covers = new Covers(library, clock);
        this.sweep = new DeletedSweep(library);
    }

    /**
     * Removes uploads a crash left half-written in {@code .incoming}. Only files untouched for two
     * minutes go, so an upload that races first-run setup is safe. Called at startup; returns how many went.
     */
    public int cleanIncoming() {
        return PhotoFiles.cleanIncoming(library.get().folder(), INCOMING_GRACE);
    }

    /** An unfinished upload this old is a leftover, not an upload in progress. */
    static final java.time.Duration INCOMING_GRACE = java.time.Duration.ofMinutes(2);

    public long maxBytes() {
        return ingest.maxBytes();
    }

    /** "This file is larger than 300 MB, ..." for this store's limit. */
    public String tooLargeMessage() {
        return ingest.tooLargeMessage();
    }

    private record Outcome(boolean skipped, long id, boolean first, TaxonRef other, int photographed) {
    }

    /**
     * Adds one photo to a species from a stream of its bytes, read once through {@link PhotoIngest}. The
     * bytes decide the format, not {@code contentType} or the name; {@code originalName} is kept for the
     * record (and a RAW extension on a TIFF makes it a RAW photo, which keeps that extension). The image row, its snapshot ({@code image_meta}),
     * its tags and every fact consumer's work and ledger row ({@link FactConsumers}) are written in one transaction.
     */
    public AddResult add(String taxonId, InputStream body, String contentType, String originalName, List<String> tagKeys) {
        Library lib = library.get();
        lib.gate().check();
        lib.photoHold().check(); // before the body is read: the route drains it (audit A5)
        Database db = lib.database();
        List<String> keys = tagKeys == null ? List.of() : tagKeys.stream().distinct().toList();
        TaxonRef species = db.read(c -> {
            TaxonRef ref = PhotoRows.species(c, taxonId);
            PhotoRows.requireTags(c, keys);
            return ref;
        });
        Path temp = lib.folder().resolve(PhotoFiles.INCOMING).resolve(UUID.randomUUID() + Library.PART);
        Path[] placed = new Path[1];
        try {
            Files.createDirectories(temp.getParent());
            PhotoIngest.Result received = ingest.ingest(body, temp, originalName);
            String now = clock.instant().toString();
            Outcome outcome = lib.gate().whileOpen(() -> lib.photoHold().whileFree(() -> db.write(c -> {
                // Looked up under the write lock, so two uploads of the same bytes cannot both pass.
                List<TaxonRef> holders = taxaHolding(c, received.sha256());
                if (holders.stream().anyMatch(t -> t.id().equals(taxonId))) {
                    return new Outcome(true, 0, false, null, PhotoRows.count(c, PhotoRows.SPECIES_PHOTOGRAPHED, ps -> { }));
                }
                TaxonRef other = holders.isEmpty() ? null : holders.get(0);
                boolean first = PhotoRows.count(c, "SELECT COUNT(*) FROM image WHERE taxon_id = ? AND deleted_at IS NULL",
                        ps -> ps.setString(1, taxonId)) == 0;
                PhotoNames.Named named = PhotoNames.freeName(c, lib.folder(), taxonId, received.extension(), PhotoNames.nextNumber(c, taxonId));
                try {
                    PhotoFiles.move(temp, named.file());
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
                placed[0] = named.file();
                long id = insert(c, taxonId, named, originalName, received, modifiedAt(named.file(), now), "app");
                if (PhotoRows.addTags(c, id, keys) != keys.size()) {
                    throw AtlasException.invalid(PhotoRows.noSuchTag(keys, c));
                }
                ImageMeta.write(c, id, received.facts(), clock.instant());
                facts.applyAll(c, List.of(new PhotoConsumer.Subject(id, received.facts(), named.file())));
                PhotoNames.setNextNumber(c, taxonId, named.number() + 1);
                int photographed = PhotoRows.count(c, PhotoRows.SPECIES_PHOTOGRAPHED, ps -> { });
                return new Outcome(false, id, first, other, photographed);
            })));
            if (outcome.skipped()) {
                return new AddResult(AddResult.SKIPPED, null, species, false, null, outcome.photographed());
            }
            placed[0] = null; // committed: the file stays
            long id = outcome.id();
            boolean first = outcome.first();
            Integer milestone = first && MILESTONES.contains(outcome.photographed()) ? outcome.photographed() : null;
            try {
                added.accept(id);
            } catch (RuntimeException e) {
                LOG.warn("Photo {} is saved, but its thumbnail could not be queued", id, e);
            }
            Photo photo = db.read(c -> PhotoRows.live(c, id));
            return changed(new AddResult(outcome.other() == null ? AddResult.ADDED : AddResult.ADDED_ELSEWHERE_TOO, photo,
                    outcome.other(), first, milestone, outcome.photographed()));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not save a photo for " + taxonId, e);
        } finally {
            PhotoFiles.deleteQuietly(placed[0]);
            PhotoFiles.deleteQuietly(temp);
        }
    }

    /** The NN and extension after {@code <Genus>_<epithet>_} in a filed photo's name. */
    private static final Pattern FILED_NUMBER = Pattern.compile("(\\d{2,})\\.[^.]+");

    /** One photo an import placed in its species: its id. */
    public record Placed(long imageId) {
    }

    /**
     * Files photos that are already copied into {@code .incoming} (by {@link PhotoIngest#ingest(InputStream, Path, String)})
     * inside the caller's write transaction, numbered and named exactly as Add photos does; for imports (design §4.4).
     * A filing is used for one transaction: if it does not commit, {@link #undo} (the {@code undo} of
     * {@link Database#write(Database.Work, Runnable)}) moves every file it placed back where it came from; a file that
     * could not be put back is named by {@link #notPutBack}. The taxa are loaded once per filing (under the write lock).
     */
    public final class Filing {

        private final Library lib = library.get();
        private final FileMoves moves = new FileMoves();
        private TaxonSnapshot snapshot;

        private Filing() {
        }

        /** Species that already hold a live photo with these bytes (B9: same species → duplicate; another → also filed). */
        public List<TaxonRef> holders(Connection c, String sha256) throws SQLException {
            return taxaHolding(c, sha256);
        }

        /**
         * Moves {@code temp} into the species folder under its next free {@code <Genus>_<epithet>_NN} name and records
         * it: the image row ({@code source = 'import'}, {@code original_name}), its snapshot, its tags and every fact
         * consumer's work and ledger row. The species must exist (callers check it is a species) and the tags must be keys.
         */
        public Placed place(Connection c, String taxonId, Path temp, String originalName, PhotoIngest.Result received,
                            List<String> tagKeys) throws SQLException {
            if (snapshot == null) {
                snapshot = TaxonSnapshot.load(c); // once per filing: a batch of 50 holds the write lock
            }
            PhotoNames.Named named = PhotoNames.freeName(c, snapshot, lib.folder(), taxonId, received.extension(), PhotoNames.nextNumber(c, taxonId));
            moves.move(temp, named.file(), originalName);
            long id = insert(c, taxonId, named, originalName, received, modifiedAt(named.file(), clock.instant().toString()),
                    "import");
            List<String> keys = tagKeys.stream().distinct().toList();
            if (PhotoRows.addTags(c, id, keys) != keys.size()) {
                throw AtlasException.invalid(PhotoRows.noSuchTag(keys, c));
            }
            ImageMeta.write(c, id, received.facts(), clock.instant());
            facts.applyAll(c, List.of(new PhotoConsumer.Subject(id, received.facts(), named.file())));
            PhotoNames.setNextNumber(c, taxonId, named.number() + 1);
            return new Placed(id);
        }

        /**
         * Takes over a photo that is already in the species, for an import whose earlier filing placed it and then did
         * not commit (the app was killed between the move and the commit; the rescan registered the file). It then reads
         * as that filing would have left it: {@code source = 'import'}, its {@code original_name}, and its number when
         * the file has the {@code <Genus>_<epithet>_NN} name filing gave it (and no other live photo of the species has
         * that number; the species' next number goes past it). The import's tags are added, and the snapshot and every
         * fact consumer's work written if the photo has no snapshot. Nothing moves.
         */
        public Placed adopt(Connection c, long imageId, String taxonId, String originalName, PhotoIngest.Result received,
                            List<String> tagKeys) throws SQLException {
            Integer number = filedNumber(c, imageId, taxonId);
            try (PreparedStatement ps = c.prepareStatement("UPDATE image SET source = 'import', original_name = ?,"
                    + " number = coalesce(?, number) WHERE id = ?")) {
                ps.setString(1, originalName);
                ps.setObject(2, number);
                ps.setLong(3, imageId);
                ps.executeUpdate();
            }
            if (number != null) {
                PhotoNames.setNextNumber(c, taxonId, number + 1);
            }
            List<String> keys = tagKeys.stream().distinct().toList();
            PhotoRows.addTags(c, imageId, keys);
            if (ImageMeta.writeIfMissing(c, imageId, received.facts(), clock.instant())) {
                facts.applyAll(c, List.of(new PhotoConsumer.Subject(imageId, received.facts(), null)));
            }
            return new Placed(imageId);
        }

        /** The NN of a photo named {@code <Genus>_<epithet>_NN.ext} after its species, unless taken; null otherwise. */
        private Integer filedNumber(Connection c, long imageId, String taxonId) throws SQLException {
            if (snapshot == null) {
                snapshot = TaxonSnapshot.load(c);
            }
            Optional<TaxonSnapshot.Row> row = snapshot.find(taxonId);
            String fileName = null;
            try (PreparedStatement ps = c.prepareStatement("SELECT file_name FROM image WHERE id = ?")) {
                ps.setLong(1, imageId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        fileName = rs.getString(1);
                    }
                }
            }
            if (row.isEmpty() || fileName == null) {
                return null;
            }
            String prefix = FileNames.safe(row.get().name()) + "_";
            if (!fileName.startsWith(prefix)) {
                return null;
            }
            Matcher m = FILED_NUMBER.matcher(fileName.substring(prefix.length()));
            if (!m.matches() || m.group(1).length() > 9) {
                return null;
            }
            int number = Integer.parseInt(m.group(1));
            boolean taken = PhotoRows.count(c, "SELECT COUNT(*) FROM image WHERE taxon_id = ? AND number = ?"
                    + " AND deleted_at IS NULL AND id <> ?", ps -> {
                        ps.setString(1, taxonId);
                        ps.setInt(2, number);
                        ps.setLong(3, imageId);
                    }) > 0;
            return taken ? null : number;
        }

        /** Puts back every file this filing moved (newest first). */
        public void undo() {
            moves.undo();
        }

        /** The {@code temp} paths of files {@link #undo} could not move back (each left in its species folder). */
        public Set<Path> notPutBack() {
            return Set.copyOf(moves.notPutBack());
        }
    }

    /** A new {@link Filing} for one transaction. */
    public Filing filing() {
        return new Filing();
    }

    /** See {@link DeletedSweep#sweep}. */
    public int sweepDeleted() {
        return sweep.sweep();
    }

    /**
     * Runs {@code listener} after each photo added, moved, deleted, restored and purged, and after each batch of an
     * import's filing (a pending taxonomy update's summary and its counts follow them).
     */
    public void onChange(Runnable listener) {
        changed.add(listener);
    }

    /** A batch of an import's filing committed (or failed): the listeners are told, as for a photo added. */
    public void batchFiled() {
        changed(null);
    }

    /**
     * {@code /api/status}'s {@code libraryVersion}, from memory: it goes up with every upload, move, delete, restore,
     * purge and filed batch here, and with {@link #changedElsewhere}. Starts at 0 when the app starts.
     */
    public long libraryVersion() {
        return version.get();
    }

    /**
     * Photos were changed by another part of the app (a custom species renamed or deleted, a split sorted, a taxonomy
     * update's moves): the library's version goes up. The change listeners are not told (their writers tell their own).
     */
    public void changedElsewhere() {
        version.incrementAndGet();
    }

    private <T> T changed(T result) {
        version.incrementAndGet();
        for (Runnable listener : changed) {
            try {
                listener.run();
            } catch (RuntimeException e) {
                LOG.warn("A listener of photo changes failed", e);
            }
        }
        return result;
    }

    /**
     * See {@link PhotoChanges#move}; refused while a taxonomy update runs, waits or is paused, and while a backup holds the
     * photos ({@link #held}).
     */
    public List<Photo> move(List<Long> rawIds, String taxonId) {
        return changed(held(() -> changes.move(rawIds, taxonId)));
    }

    /**
     * Runs a change of photos through the taxonomy gate, then the backup's hold (409 {@code backup_copying} while a backup
     * of Everything runs or is paused before it is finished; multi-window contract §4).
     */
    private <T> T held(java.util.function.Supplier<T> work) {
        Library lib = library.get();
        return lib.gate().whileOpen(() -> lib.photoHold().whileFree(work));
    }

    /** See {@link Covers#setCover}. */
    public Long setCover(String taxonId, Long photoId) {
        return covers.setCover(taxonId, photoId);
    }

    /** How many photos are in Recently deleted, from memory once read ({@link DeletedCount}). */
    public int deletedCount() {
        return deleted.get(library.get().database());
    }

    /** How many photos are in Recently deleted if that is in memory, else null: never reads (a status poll). */
    public Integer deletedCountKept() {
        return deleted.kept(library.get().database());
    }

    /** See {@link PhotoChanges#delete}; refused while a taxonomy update runs, waits or is paused. */
    public int delete(List<Long> rawIds) {
        return changed(held(() -> changes.delete(rawIds)));
    }

    /** See {@link PhotoChanges#restore}; refused while a taxonomy update runs, waits or is paused. */
    public List<Photo> restore(List<Long> rawIds) {
        return changed(held(() -> changes.restore(rawIds)));
    }

    /** See {@link PhotoChanges#purge}; refused while a taxonomy update runs, waits or is paused. */
    public int purge(List<Long> idsOrEmptyForAll) {
        return changed(held(() -> changes.purge(idsOrEmptyForAll)));
    }

    /**
     * See {@link PhotoChanges#purgeOlderThan}; while a taxonomy update runs, waits or is paused it is skipped and
     * remembered: {@link #purgeSkipped} runs it once the update has finished.
     */
    public int purgeOlderThan(Duration age) {
        Library lib = library.get();
        if (lib.gate().isClosed()) {
            skippedPurge = age;
            LOG.info("Emptying old entries from Recently deleted waits for the taxonomy update to finish");
            return 0;
        }
        return changed(lib.gate().whileOpen(() -> changes.purgeOlderThan(age)));
    }

    /**
     * The purge {@link #purgeOlderThan} skipped for a taxonomy update, now (it finished); returns how many went. It is
     * remembered until it has run: one that fails (logged) or finds the gate closed again is tried the next time.
     */
    public int purgeSkipped() {
        Duration age = skippedPurge;
        if (age == null) {
            return 0;
        }
        try {
            int purged = changed(library.get().gate().whileOpen(() -> changes.purgeOlderThan(age)));
            skippedPurge = null;
            return purged;
        } catch (RuntimeException e) {
            LOG.warn("Emptying old entries from Recently deleted failed; it is tried again next time", e);
            return 0;
        }
    }

    /** Species that already hold a live photo with these bytes. */
    private static List<TaxonRef> taxaHolding(Connection c, String sha256) throws SQLException {
        List<TaxonRef> taxa = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT DISTINCT t.id, t.name, t.common_name FROM image i"
                + " JOIN taxon t ON t.id = i.taxon_id WHERE i.sha256 = ? AND i.deleted_at IS NULL ORDER BY t.id")) {
            ps.setString(1, sha256);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    taxa.add(new TaxonRef(rs.getString(1), rs.getString(2), rs.getString(3)));
                }
            }
        }
        return taxa;
    }

    private static String modifiedAt(Path file, String fallback) {
        try {
            return Files.getLastModifiedTime(file).toInstant().toString();
        } catch (IOException e) {
            return fallback;
        }
    }

    private long insert(Connection c, String taxonId, PhotoNames.Named named, String originalName, PhotoIngest.Result received,
                        String modifiedAt, String source) throws SQLException {
        PhotoFacts info = received.facts();
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO image (taxon_id, rel_path, file_name, number,"
                + " original_name, file_size, modified_at, sha256, width, height, orientation, added_at, source, file_type)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", PreparedStatement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, taxonId);
            ps.setString(2, named.relPath());
            ps.setString(3, named.fileName());
            ps.setInt(4, named.number());
            ps.setString(5, originalName == null || originalName.isBlank() ? null : originalName.strip());
            ps.setLong(6, received.size());
            ps.setString(7, modifiedAt);
            ps.setString(8, received.sha256());
            ps.setInt(9, info.width());
            ps.setInt(10, info.height());
            ps.setInt(11, info.orientation());
            ps.setString(12, clock.instant().toString());
            ps.setString(13, source);
            ps.setString(14, received.format().fileType());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }
}
