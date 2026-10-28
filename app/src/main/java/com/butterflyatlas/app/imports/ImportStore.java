package com.butterflyatlas.app.imports;

import com.butterflyatlas.app.db.Database;
import com.butterflyatlas.app.library.Library;
import com.butterflyatlas.app.photos.HeicStatus;
import com.butterflyatlas.app.photos.Photo;
import com.butterflyatlas.app.photos.PhotoFacts;
import com.butterflyatlas.app.photos.Thumbnails;
import com.butterflyatlas.app.places.PlaceRef;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;
import java.util.function.Supplier;

/**
 * The saved state of every import (design §4, B2; contract §2): {@code import_run} and its {@code import_item} rows.
 * Every change to an item stamps it with the run's next change number ({@link #stamp}), so the grid asks only for
 * what changed since the version it has. Reads here; the walk, the read, the edits and filing change the rows.
 */
public final class ImportStore {

    public static final String OPEN = "open";
    public static final String FINISHED = "finished";

    /** Item read states. */
    public static final String PENDING = "pending";
    public static final String READ = "read";
    public static final String UNSUPPORTED = "unsupported";
    public static final String FAILED = "failed";
    public static final String MISSING = "missing";

    /** Item outcomes; all but {@link #OUT_FAILED} mean the item is done with. */
    public static final String FILED = "filed";
    public static final String DUPLICATE = "duplicate";
    public static final String ALSO_ELSEWHERE = "also_elsewhere";
    public static final String COPY_OF = "copy_of";
    public static final String OUT_FAILED = "failed";

    /** Preview folders sit under {@code .thumbs/import/<run id>/}. */
    public static final String PREVIEWS = "import";

    /**
     * {@code stopped}: the phase the app's closing cut short, until the user continues, finishes or discards the import;
     * {@code paused}: the user paused filing; {@code notice}: why filing last stopped when the reason was the library's
     * (its drive full or not writable), until filing starts again; {@code updateNote}: what a taxonomy update changed in
     * its items (a split species' assignments cleared); {@code filings}: how many filings have begun.
     */
    public record Run(long id, String rootPath, String state, String createdAt, String updatedAt, String lastWalkAt,
                      boolean paused, Stopped stopped, String notice, String updateNote, int filings) {

        public Path root() {
            return Path.of(rootPath);
        }
    }

    /** A phase cut short by the app closing: {@code reading} (a walk counts as reading) or {@code filing}. */
    public record Stopped(String phase, int done, int total, String at) {
    }

    /**
     * An import as {@code /api/status} shows it: its folder, whether it is open, and its stopped phase (null unless
     * stopped).
     */
    public record Brief(long id, String rootPath, boolean open, Stopped stopped) {

        public Path root() {
            return Path.of(rootPath);
        }
    }

    /** Each item is in exactly one of reading … unassigned (checked in that order); photos = total − problems − missing. */
    public record Counts(int total, int photos, int reading, int unassigned, int suggested, int assigned, int skipped,
                         int problems, int filed, int missing) {
    }

    /**
     * One file of an import, as the grid shows it (contract §2 "Item"); {@code place} is the place the user chose for it
     * ({@code source} "user"; null when they removed it), else where its GPS lies ({@code source} "gps"; null without
     * GPS, before it is read or outside every boundary). 7a contract §3: {@code fileType} once read, {@code rawPreview}
     * for a RAW item (its preview as displayed and the turn {@code /display} needs), {@code pairId} the other item of
     * its RAW + JPEG pair; {@code outcome} {@code pair_left} (with its {@code error}) for the half a choice left out.
     * Phase 7b contract §4 and audit 6: {@code heic} as Photo's for a HEIC item (null otherwise), {@code previewFailed}
     * when its preview could not be made (any format; never because no HEIC decoder is available now).
     */
    public record Item(long id, String path, String folder, String name, long size, String modifiedAt, String capturedAt,
                       Integer width, Integer height, String state, String problem, String suggestedTaxonId, String libraryTaxonId, Long copyOf,
                       String taxonId, List<String> tags, boolean skipped, String outcome, Long imageId, String error,
                       String v, PlaceRef place, String fileType, Photo.RawPreview rawPreview, Long pairId,
                       Photo.Heic heic, boolean previewFailed) {
    }

    /**
     * An import's RAW + JPEG and RAW + HEIC pairs (7a contract §3 "ImportSummary.pairs"): how many, how many are still to
     * file (assigned, not skipped, not filed), the choice ({@code both | jpeg | raw}, null until made; {@code jpeg} is
     * the JPEG or HEIC half), whether filing has touched a pair (the choice can no longer change), and the kinds of
     * partner present ({@code ["jpeg"]}, {@code ["heic"]} or {@code ["jpeg", "heic"]}; Phase 7b contract §4).
     */
    public record Pairs(int count, int toFile, String choice, boolean locked, List<String> kinds) {
    }

    /** The outcome the API answers for the half of a pair the choice left out ({@code pair_left = 1}). */
    public static final String PAIR_LEFT = "pair_left";

    public record TaxonName(String name, String common) {
    }

    /**
     * {@code version}: the import's change number; {@code taxa}: every taxon the items refer to; {@code revision}: the
     * import's revision (multi-window contract §5: its choices as this answer has them, sent back with File);
     * {@code previousRevision}: on an edit's answer, the revision just before it (§11 A20: a window takes the new one only
     * when it held that one), left out otherwise.
     */
    public record ItemsPage(long version, List<Item> items, Map<String, TaxonName> taxa, long revision,
                            @JsonInclude(JsonInclude.Include.NON_NULL) Long previousRevision) {

        public ItemsPage(long version, List<Item> items, Map<String, TaxonName> taxa) {
            this(version, items, taxa, 0, null);
        }

        /** This page with the import's revision (a read). */
        public ItemsPage withRevision(long revision) {
            return new ItemsPage(version, items, taxa, revision, null);
        }

        /** This page with the import's revision after an edit, and the one before it. */
        public ItemsPage withRevision(long revision, long previous) {
            return new ItemsPage(version, items, taxa, revision, previous);
        }
    }

    /** An unfinished import of a folder, for the folder browser's "Continue your import". */
    public record OpenImport(long id, int filed, int total) {
    }

    /**
     * What an import has done so far (contract §2 "ImportResult"). {@code problems} are the photos that could not be
     * read (by reason); photos that could not be filed are counted in {@code failed}, not there.
     */
    public record Result(int filed, int newSpecies, int speciesCount, Integer milestone, List<SpeciesFiled> bySpecies,
                         int duplicates, int alsoElsewhere, int copies, int skipped, int failed, List<Problem> problems,
                         int left, LastFiling lastFiling, int pairLeft, String pairChoice) {
    }

    /** The latest filing's own figures (for its celebration); null before the first filing. */
    public record LastFiling(int filed, int newSpecies, Integer milestone, int speciesCount) {
    }

    /** Photos filed under one species by this import; {@code isNew}: the species had no live photo before. */
    public record SpeciesFiled(String taxonId, String name, String common, int count, boolean isNew) {
    }

    public record Problem(String reason, int count) {
    }

    /** SQL: an item that is done with (filed, or not filed for a reason that holds). */
    static final String DONE = "i.outcome IN ('filed', 'duplicate', 'also_elsewhere', 'copy_of')";

    private final Supplier<Library> library;
    private final Clock clock;
    private final ImportItems items;
    private final ImportCounts counts;
    /**
     * The imports of each open library in brief, in memory once read (design D9), one per library database whichever
     * store reads or writes them; changed only by the writes below that say so (each under {@link #WRITES}).
     */
    private static final Map<Database, List<Brief>> KEPT = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Object WRITES = new Object();

    public ImportStore(Supplier<Library> library, Clock clock) {
        this(library, clock, () -> { });
    }

    ImportStore(Supplier<Library> library, Clock clock, Runnable betweenReads) {
        this.library = library;
        this.clock = clock;
        this.items = new ImportItems(library, betweenReads);
        this.counts = new ImportCounts(library);
    }

    /** Starts a new import of {@code root} (an absolute, normalised path). */
    public Run create(Path root) {
        String now = clock.instant().toString();
        long id = change(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO import_run (root_path, state, created_at, updated_at)"
                    + " VALUES (?, 'open', ?, ?)", PreparedStatement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, root.toString());
                ps.setString(2, now);
                ps.setString(3, now);
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    keys.next();
                    return keys.getLong(1);
                }
            }
        });
        return run(id);
    }

    /**
     * Every import in brief, open ones first, then by when they were created, newest first: from memory once read (a
     * status poll runs no SQL).
     */
    public List<Brief> briefs() {
        Database db = library.get().database();
        List<Brief> kept = KEPT.get(db);
        if (kept != null) {
            return kept;
        }
        synchronized (WRITES) {
            return KEPT.computeIfAbsent(db, d -> d.read(ImportStore::briefs));
        }
    }

    /** Every import in brief if in memory, else empty: never reads (a status poll). */
    public Optional<List<Brief>> briefsKept() {
        return Optional.ofNullable(KEPT.get(library.get().database()));
    }

    /**
     * A write that can change which imports there are, open or stopped: the work, then the imports read again in the same
     * write, kept once it commits. When the write fails it may still have committed (a failure closing the
     * connection), so they are read from the database again when next asked.
     */
    private <T> T change(Database.Work<T> work) {
        return change(library.get().database(), work);
    }

    /** {@link #change(Database.Work)} on {@code db}, for a write elsewhere that changes an import's folder (a relocate). */
    static <T> T change(Database db, Database.Work<T> work) {
        synchronized (WRITES) {
            List<List<Brief>> after = new ArrayList<>(1);
            try {
                T result = db.write(c -> {
                    T r = work.apply(c);
                    after.add(briefs(c));
                    return r;
                });
                KEPT.put(db, after.get(0));
                return result;
            } catch (RuntimeException | Error e) {
                KEPT.remove(db);
                throw e;
            }
        }
    }

    private static List<Brief> briefs(Connection c) throws SQLException {
        return runs(c, "", null).stream().map(r -> new Brief(r.id(), r.rootPath(), OPEN.equals(r.state()), r.stopped()))
                .toList();
    }

    /** The import, or 404 {@code import_not_found}. */
    public Run run(long id) {
        return find(id).orElseThrow(ImportErrors::importNotFound);
    }

    public Optional<Run> find(long id) {
        return library.get().database().read(c -> runs(c, " WHERE id = ?", id).stream().findFirst());
    }

    static Run run(Connection c, long id) throws SQLException {
        return runs(c, " WHERE id = ?", id).stream().findFirst().orElseThrow(ImportErrors::importNotFound);
    }

    /** Every import, open ones first, newest first. */
    public List<Run> runs() {
        return library.get().database().read(c -> runs(c, "", null));
    }

    /** The open import of this folder, if any: the same root as the computer compares paths. */
    public Optional<Run> openRunOf(Path folder) {
        return runs().stream().filter(run -> OPEN.equals(run.state()) && samePath(run.rootPath(), folder)).findFirst();
    }

    /** For the folder browser: the open import of exactly this folder, with how far it is. */
    public Optional<OpenImport> openImport(Path folder) {
        return openRunOf(folder).map(run -> {
            Counts counts = counts(run.id());
            return new OpenImport(run.id(), counts.filed(), counts.total());
        });
    }

    public Counts counts(long runId) {
        return counts.counts(runId);
    }

    /** The counts of every import, in one query (for the list of imports). */
    public Map<Long, Counts> countsByRun() {
        return counts.countsByRun();
    }

    /** All items ({@code since} null), or those changed after version {@code since}. */
    public ItemsPage items(long runId, Long since) {
        return items.items(runId, since);
    }

    /** The items stamped with exactly {@code seq} (what one change touched), with the version as it stands. */
    ItemsPage stamped(Connection c, long runId, Long seq) throws SQLException {
        return ImportItems.stamped(c, runId, seq);
    }

    /** See {@link ImportCounts#result}. */
    public Result result(long runId) {
        return counts.result(runId);
    }

    /** The import's RAW + JPEG pairs (7a contract §3), or null when it has none. */
    Pairs pairs(long runId) {
        return library.get().database().read(c -> ImportPairs.of(c, runId));
    }

    /** An item's file as the preview and the large view need it; {@code format} and {@code orientation} once read. */
    /** An item's files: where it is, how it was read, and the library photo filing made of it ({@code imageId}, or null). */
    public record ItemFile(long id, String relPath, String state, String v, String format, int orientation, Long imageId,
                           long size, String modifiedAt, PhotoFacts.Preview rawPreview, int previewFailed) {
    }

    /**
     * An import's HEIC items (Phase 7b contract §4, audit 10): how many were read, how many have no preview because the
     * computer's decoder is missing ({@code unshowable}; 0 while it is available or checked), and how many previews are
     * being made now ({@code making}).
     */
    public record Heic(int count, int unshowable, int making) {
    }

    /** {@link Heic} of this import, or null when it has no HEIC items read. */
    Heic heic(long runId, int making) {
        Path data = library.get().folder();
        return library.get().database().read(c -> {
            int count = 0;
            int unshowable = 0;
            boolean missing = HeicStatus.current().isMissing();
            try (PreparedStatement ps = c.prepareStatement("SELECT id, v, (outcome IS NULL OR outcome = 'failed')"
                    + " AND pair_left = 0 FROM import_item WHERE run_id = ? AND read_state = 'read' AND file_type = 'heic'")) {
                ps.setLong(1, runId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        count++;
                        // only those still to file (fixes J13): a filed one is the library's to show
                        if (missing && rs.getBoolean(3) && (rs.getString(2) == null
                                || !Files.exists(previewFile(data, runId, rs.getLong(1), rs.getString(2))))) {
                            unshowable++;
                        }
                    }
                }
            }
            return count == 0 ? null : new Heic(count, unshowable, making);
        });
    }

    /** One item of this import, or 404 when the import has no such item. */
    public ItemFile itemFile(long runId, long itemId) {
        return items.itemFile(runId, itemId);
    }

    /**
     * Records that a phase of this import is running ({@code walking}, {@code reading} or {@code filing}) and how far it
     * is; {@code phase} null when it ended, failed or the user paused it. A run still marked at the next start was cut
     * short by the app closing ({@link #markStopped}). An import discarded meanwhile is left alone (nothing to record):
     * the phases call it from their own thread and meet the discard on their own.
     */
    public void setActive(long runId, String phase, int done, int total) {
        library.get().database().write(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE import_run SET active_phase = ?, active_done = ?,"
                    + " active_total = ? WHERE id = ?")) {
                ps.setString(1, phase);
                ps.setObject(2, phase == null ? null : done);
                ps.setObject(3, phase == null ? null : total);
                ps.setLong(4, runId);
                ps.executeUpdate();
            }
            return null;
        });
    }

    /**
     * At startup, before anything runs: every run still marked active was cut short by the app closing, and is
     * reported as stopped (with its last progress) until the user continues, finishes or discards it. Returns how many.
     */
    public int markStopped() {
        String now = clock.instant().toString();
        // A stopped filing already recorded stays unless filing itself was cut short again: a continue's walk or read
        // cut short must not relabel it as "reading 0 of 0" (the filing is what the user still has to carry on).
        String keep = "WHEN stopped_phase = 'filing' AND active_phase <> 'filing' THEN ";
        return change(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE import_run SET"
                    + " stopped_phase = CASE " + keep + "stopped_phase WHEN active_phase = 'filing' THEN 'filing'"
                    + " ELSE 'reading' END,"
                    // a walk's progress counts files found, not "done of total": a cut-short walk is reading, 0 of 0
                    + " stopped_done = CASE " + keep + "stopped_done WHEN active_phase = 'walking' THEN 0"
                    + " ELSE coalesce(active_done, 0) END,"
                    + " stopped_total = CASE " + keep + "stopped_total WHEN active_phase = 'walking' THEN 0"
                    + " ELSE coalesce(active_total, 0) END,"
                    + " stopped_at = CASE " + keep + "stopped_at ELSE ? END, active_phase = NULL, active_done = NULL,"
                    + " active_total = NULL WHERE active_phase IS NOT NULL")) {
                ps.setString(1, now);
                return ps.executeUpdate();
            }
        });
    }

    /**
     * Saves how far the running phase is, only while the import is still recorded as in that phase: a save that comes
     * after the phase ended (or another began) changes nothing, so it can never leave a phase marked running (nor does
     * one for an import discarded meanwhile).
     */
    public void saveProgress(long runId, String phase, int done, int total) {
        library.get().database().write(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE import_run SET active_done = ?, active_total = ?"
                    + " WHERE id = ? AND active_phase = ?")) {
                ps.setInt(1, done);
                ps.setInt(2, total);
                ps.setLong(3, runId);
                ps.setString(4, phase);
                ps.executeUpdate();
            }
            return null;
        });
    }

    /**
     * Why filing stopped when the reason was the library's (a plain message), or null when filing starts again. An import
     * discarded meanwhile is left alone (filing notes it from its own thread).
     */
    public void setNotice(long runId, String notice) {
        library.get().database().write(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE import_run SET notice = ? WHERE id = ?")) {
                ps.setString(1, notice);
                ps.setLong(2, runId);
                ps.executeUpdate();
            }
            return null;
        });
    }

    /** The user went on with the import (continued, filed, finished): it is no longer reported as stopped. */
    public void clearStopped(long runId) {
        change(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE import_run SET stopped_phase = NULL, stopped_done = NULL,"
                    + " stopped_total = NULL, stopped_at = NULL WHERE id = ?")) {
                ps.setLong(1, runId);
                exists(ps.executeUpdate());
            }
            return null;
        });
    }

    /** The user paused filing (true), or filing starts again (false). */
    public void setPaused(long runId, boolean paused) {
        library.get().database().write(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE import_run SET paused = ? WHERE id = ?")) {
                ps.setInt(1, paused ? 1 : 0);
                ps.setLong(2, runId);
                exists(ps.executeUpdate());
            }
            return null;
        });
    }

    /** Saves how the import's RAW + JPEG pairs are filed ({@code both}, {@code jpeg} or {@code raw}). */
    void setPairChoice(long runId, String choice) {
        library.get().database().write(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE import_run SET pair_choice = ?, updated_at = ? WHERE id = ?")) {
                ps.setString(1, choice);
                ps.setString(2, clock.instant().toString());
                ps.setLong(3, runId);
                exists(ps.executeUpdate());
            }
            return null;
        });
    }

    /** Marks the import finished; it stays listed, with its result, until it is deleted. */
    public void finish(long runId) {
        change(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE import_run SET state = 'finished', paused = 0, notice = NULL,"
                    + " stopped_phase = NULL, stopped_done = NULL, stopped_total = NULL, stopped_at = NULL, updated_at = ?"
                    + " WHERE id = ?")) {
                ps.setString(1, clock.instant().toString());
                ps.setLong(2, runId);
                exists(ps.executeUpdate());
            }
            return null;
        });
    }

    /** Removes the import's rows (items and their tags go with it); never its photos or the source folder. */
    public void delete(long runId) {
        change(c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM import_run WHERE id = ?")) {
                ps.setLong(1, runId);
                exists(ps.executeUpdate());
            }
            return null;
        });
    }

    /** 404 {@code import_not_found} when a change to one import touched no row: there is no such import. */
    private static void exists(int updated) {
        if (updated == 0) {
            throw ImportErrors.importNotFound();
        }
    }

    /** The import's preview folder: {@code <data>/.thumbs/import/<run id>}. */
    public static Path previewFolder(Path data, long runId) {
        return data.resolve(Thumbnails.FOLDER).resolve(PREVIEWS).resolve(Long.toString(runId));
    }

    /** An item's 480 px preview for one read of its file ({@code v}). */
    public static Path previewFile(Path data, long runId, long itemId, String v) {
        return previewFolder(data, runId).resolve(itemId + "-" + v + ".jpg");
    }

    /**
     * Takes the run's next change number and dates the run; every item changed in the calling transaction is stamped
     * with it. Call it only once something is about to change.
     */
    static long stamp(Connection c, long runId, Instant now) throws SQLException {
        // One statement: the number is taken and moved on together, whatever transaction the caller is in.
        try (PreparedStatement ps = c.prepareStatement("UPDATE import_run SET next_seq = next_seq + 1, updated_at = ?"
                + " WHERE id = ? RETURNING next_seq - 1")) {
            ps.setString(1, now.toString());
            ps.setLong(2, runId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw ImportErrors.importNotFound();
                }
                return rs.getLong(1);
            }
        }
    }

    /**
     * Makes {@code copy_of_item_id} right for every read item of the run with these bytes: the one with the lowest id is
     * the original (none), the others point to it. Items whose pointer changes are stamped with {@code seq}; a filed
     * item (one with an outcome) is never changed.
     */
    static void recomputeCopies(Connection c, long runId, Collection<String> sha256s, long seq) throws SQLException {
        String original = "(SELECT MIN(o.id) FROM import_item o WHERE o.run_id = ? AND o.sha256 = ? AND o.read_state = 'read')";
        String wanted = "CASE WHEN id = " + original + " THEN NULL ELSE " + original + " END";
        try (PreparedStatement ps = c.prepareStatement("UPDATE import_item SET copy_of_item_id = " + wanted + ", seq = ?"
                + " WHERE run_id = ? AND sha256 = ? AND read_state = 'read' AND NOT coalesce("
                + DONE.replace("i.", "") + ", 0)" // still to file (a failed one too): its copy is pointed again
                + " AND copy_of_item_id IS NOT " + wanted)) {
            for (String sha256 : new java.util.LinkedHashSet<>(sha256s)) {
                if (sha256 == null) {
                    continue;
                }
                int at = 1;
                for (int n = 0; n < 2; n++) {
                    ps.setLong(at++, runId);
                    ps.setString(at++, sha256);
                }
                ps.setLong(at++, seq);
                ps.setLong(at++, runId);
                ps.setString(at++, sha256);
                for (int n = 0; n < 2; n++) {
                    ps.setLong(at++, runId);
                    ps.setString(at++, sha256);
                }
                ps.executeUpdate();
            }
        }
    }

    /**
     * Whether a stored root and a folder are the same place: equal paths, or the same folder on disk (case, links). A
     * root that is no longer there (moved, a drive unplugged) is the same only by its path: the disk cannot say more.
     * {@code Files.exists} is false too for a share that cannot be reached, which then also compares by path only.
     */
    static boolean samePath(String rootPath, Path folder) {
        Path root;
        try {
            root = Path.of(rootPath);
        } catch (InvalidPathException e) {
            return false;
        }
        if (root.normalize().equals(folder.normalize())) {
            return true;
        }
        try {
            return Files.exists(root) && Files.exists(folder) && Files.isSameFile(root, folder);
        } catch (IOException e) {
            return false;
        }
    }

    /** An item's file: {@code relPath} ({@code /}-separated, as the walk saved it) under {@code root}. */
    static Path resolve(Path root, String relPath) {
        Path path = root;
        for (String part : relPath.split("/")) {
            if (part.isEmpty() || part.equals(".") || part.equals("..")) {
                throw new IllegalArgumentException("Not a path the walk saves: " + relPath);
            }
            path = path.resolve(part);
        }
        return path;
    }

    /** {@code a/b} for {@code a/b/c.jpg}, {@code ""} at the root. */
    static String folderOf(String relPath) {
        int slash = relPath.lastIndexOf('/');
        return slash < 0 ? "" : relPath.substring(0, slash);
    }

    static String nameOf(String relPath) {
        return relPath.substring(relPath.lastIndexOf('/') + 1);
    }

    static long version(Connection c, long runId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT next_seq - 1 FROM import_run WHERE id = ?")) {
            ps.setLong(1, runId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw ImportErrors.importNotFound();
                }
                return rs.getLong(1);
            }
        }
    }

    private static List<Run> runs(Connection c, String where, Long id) throws SQLException {
        List<Run> runs = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT id, root_path, state, created_at, updated_at, last_walk_at,"
                + " paused, stopped_phase, stopped_done, stopped_total, stopped_at, notice, update_note, filings FROM import_run" + where + " ORDER BY state <> 'open', created_at DESC, id DESC")) {
            if (id != null) {
                ps.setLong(1, id);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Stopped stopped = rs.getString(8) == null ? null
                            : new Stopped(rs.getString(8), rs.getInt(9), rs.getInt(10), rs.getString(11));
                    runs.add(new Run(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                            rs.getString(6), rs.getInt(7) == 1, stopped, rs.getString(12), rs.getString(13), rs.getInt(14)));
                }
            }
        }
        return runs;
    }
}
