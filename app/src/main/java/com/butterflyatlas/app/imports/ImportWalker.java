package com.butterflyatlas.app.imports;

import com.butterflyatlas.app.db.Database;
import com.butterflyatlas.app.library.Library;
import com.butterflyatlas.app.search.SearchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.Normalizer;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;
import java.util.function.Supplier;

/**
 * The walk of an import's folder (design §4.2 step 1, B3): every photo or video file under the root becomes an item by
 * its relative path, with its size and modified time, so the grid can show placeholders at once. Symbolic links are not
 * followed, hidden and system folders are skipped, and so is the library's own data folder. On a re-walk, new files
 * are added, vanished ones become {@code missing} and edited ones (size or modified time changed) go back to
 * {@code pending}, keeping their species, tags and skip; an item already filed is left alone. A folder that could not be
 * read hides nothing: its items are left as they were. The source folder is only read.
 */
public final class ImportWalker {

    private static final Logger LOG = LoggerFactory.getLogger(ImportWalker.class);

    /** One file found: its path under the root ({@code /} separators), size and modified time. */
    public record Found(String relPath, long size, String modifiedAt) {
    }

    /** What a walk of a folder saw; {@code unreadable} lists folders (relative paths) it could not read. */
    public record Listing(List<Found> files, Set<String> unreadable) {
    }

    /** What a walk changed. */
    public record Result(int added, int changed, int missing, int returned, int total) {
    }

    /** Resolves a path on disk (injectable for tests). */
    interface RealPath {
        Path of(Path path) throws IOException;
    }

    /** What the walk does with a folder: go in, leave it out, or leave it out because it could not be read. */
    enum Verdict {
        WALK, SKIP, UNREADABLE
    }

    /** File times within this of each other, with the same size, are the same edit (FAT and exFAT keep two seconds). */
    private static final long SAME_TIME_MILLIS = 2000;
    /** How many files are visited between looks at the stop signal. */
    private static final int STOP_EVERY = 200;

    private final Supplier<Library> library;
    private final SearchService search;
    private final Clock clock;
    private final boolean windows;
    private final RealPath realPath;
    /** Run between the suggestions and the walk's write (tests change the library there). */
    private volatile Runnable beforeWrite = () -> { };

    public ImportWalker(Supplier<Library> library, SearchService search, Clock clock) {
        this(library, search, clock, ImportNames.WINDOWS, Path::toRealPath);
    }

    ImportWalker(Supplier<Library> library, SearchService search, Clock clock, boolean windows, RealPath realPath) {
        this.library = library;
        this.search = search;
        this.clock = clock;
        this.windows = windows;
        this.realPath = realPath;
    }

    /** Why a walk returned. */
    public enum Ended {
        /** The folder was walked and what changed is saved. */
        WALKED,
        /** Asked to stop part-way: nothing changed. */
        STOPPED,
        /** The folder is not there: nothing changed. */
        FOLDER_MISSING,
        /**
         * Nothing in the folder where photos not yet filed were: an unplugged drive or an emptied folder, not a clean-out.
         * Treated like a missing folder; nothing changed.
         */
        FOLDER_EMPTY
    }

    /** How a walk ended, and (only when {@link Ended#WALKED}) what it changed. */
    public record Walk(Ended ended, Result result) {

        /** What the walk changed; throws when it did not walk. */
        public Result walked() {
            if (ended != Ended.WALKED) {
                throw new IllegalStateException("The walk ended " + ended);
            }
            return result;
        }
    }

    /**
     * Walks the import's folder and saves what changed, unless the folder is missing or empty where photos not yet
     * filed were, or the walk was stopped ({@code stop}): then nothing changes. {@code progress} hears the number of
     * files found so far.
     */
    public Walk walk(long runId, IntConsumer progress, BooleanSupplier stop) {
        ImportStore.Run run = new ImportStore(library, clock).run(runId);
        Path root = run.root();
        if (!Files.isDirectory(root)) {
            return new Walk(Ended.FOLDER_MISSING, null);
        }
        Optional<Listing> listing = list(root, progress, stop);
        if (listing.isEmpty()) {
            return new Walk(Ended.STOPPED, null);
        }
        // Only photos still to file count: a folder cleared after everything was filed is just empty.
        if (listing.get().files().isEmpty() && library.get().database().read(c -> existing(c, runId).values().stream()
                .anyMatch(i -> !i.missing() && !i.done()))) {
            return new Walk(Ended.FOLDER_EMPTY, null);
        }
        return new Walk(Ended.WALKED, apply(runId, root, listing.get(), false));
    }

    /**
     * The folder moved: walks {@code newRoot} and, if any of the import's files are there by the same relative path,
     * points the import at it and saves the walk; else 400 {@code folder_mismatch} and nothing changes.
     */
    public Result relocate(long runId, Path newRoot) {
        return relocate(runId, newRoot, () -> false).orElseThrow();
    }

    /** {@link #relocate(long, Path)} that stops when asked ({@code stop}): empty then, and nothing changes. */
    public Optional<Result> relocate(long runId, Path newRoot, BooleanSupplier stop) {
        return relocate(runId, newRoot, stop, Supplier::get);
    }

    /** Runs the last step of a relocate (the check that no other import uses the folder, and the save). */
    @FunctionalInterface
    interface Guard {
        Result run(Supplier<Result> apply);
    }

    /**
     * {@link #relocate(long, Path, BooleanSupplier)} whose last step runs through {@code guard} (the facade runs it under
     * its start lock): the folder is checked again there, as a start of it may have begun during the walk.
     */
    Optional<Result> relocate(long runId, Path newRoot, BooleanSupplier stop, Guard guard) {
        if (!Files.isDirectory(newRoot)) {
            throw ImportErrors.folderNotFound();
        }
        checkNotInUse(runId, newRoot);
        Optional<Listing> walked = list(newRoot, n -> { }, stop);
        if (walked.isEmpty()) {
            return Optional.empty();
        }
        Listing listing = walked.get();
        Map<String, Existing> known = library.get().database().read(c -> existing(c, runId));
        Map<String, Long> sizes = new HashMap<>();
        for (Found found : listing.files()) {
            sizes.put(key(found.relPath()), found.size());
        }
        long matching = known.values().stream().filter(i -> Long.valueOf(i.size()).equals(sizes.get(key(i.relPath())))).count();
        long present = known.values().stream().filter(i -> !i.missing()).count(); // ones already gone count for nothing
        if (matching == 0 || matching * 2 < present) {
            throw ImportErrors.folderMismatch(); // a few camera-style names in common are not the same folder
        }
        return Optional.of(guard.run(() -> {
            checkNotInUse(runId, newRoot);
            return apply(runId, newRoot, listing, true);
        }));
    }

    /** 409 {@code folder_in_use} when another open import uses the folder. */
    private void checkNotInUse(long runId, Path folder) {
        if (new ImportStore(library, clock).openRunOf(folder).filter(other -> other.id() != runId).isPresent()) {
            throw ImportErrors.folderInUse();
        }
    }

    /**
     * Whether {@code root} is a folder holding at least one file a walk would find: the same folders are left out
     * ({@link #verdict}: hidden and system folders, links, the library's folder {@code data}, Windows junctions) and the
     * same files listed. Stops at the first. A folder that is there but holds none is as good as missing (an emptied
     * folder, a drive's mount point left behind). When it cannot be told (something could not be read), true: nothing is
     * treated as gone on a guess.
     */
    static boolean hasListedFiles(Path root, Path data) {
        if (!Files.isDirectory(root)) {
            return false;
        }
        boolean windows = ImportNames.WINDOWS;
        RealPath real = Path::toRealPath;
        boolean[] found = {false};
        boolean[] unsure = {false};
        try {
            Path start = root.toRealPath();
            Path realData = realOrSelf(real, data);
            Files.walkFileTree(start, EnumSet.noneOf(FileVisitOption.class), Integer.MAX_VALUE, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (dir.equals(start)) {
                        return FileVisitResult.CONTINUE;
                    }
                    Verdict verdict = verdict(dir, attrs, realData, windows, real);
                    if (verdict == Verdict.UNREADABLE) {
                        unsure[0] = true;
                    }
                    return verdict == Verdict.WALK ? FileVisitResult.CONTINUE : FileVisitResult.SKIP_SUBTREE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (ImportNames.isFile(attrs, windows) && ImportNames.isListed(file.getFileName().toString())) {
                        found[0] = true;
                        return FileVisitResult.TERMINATE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException e) {
                    unsure[0] = true;
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException e) {
                    if (e != null) {
                        unsure[0] = true;
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException | RuntimeException e) {
            LOG.info("Could not tell whether {} still holds photos: {}", root, e.toString());
            return true;
        }
        if (!found[0] && unsure[0]) {
            LOG.info("Could not read all of {}; taking it that it still holds photos", root);
        }
        return found[0] || unsure[0];
    }

    /** Lists the photo and video files under {@code root}; empty if stopped part-way. Touches no database. */
    Optional<Listing> list(Path root, IntConsumer progress, BooleanSupplier stop) {
        Path start;
        try {
            start = realPath.of(root); // the chosen folder itself may be a link; nothing below it is followed
        } catch (IOException e) {
            return Optional.of(new Listing(List.of(), Set.of("")));
        }
        Path data = realOrSelf(library.get().folder());
        List<Found> files = new ArrayList<>();
        Set<String> unreadable = new HashSet<>();
        boolean[] stopped = {false};
        int[] visited = {0};
        try {
            Files.walkFileTree(start, EnumSet.noneOf(FileVisitOption.class), Integer.MAX_VALUE, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (stop.getAsBoolean()) {
                        stopped[0] = true;
                        return FileVisitResult.TERMINATE;
                    }
                    if (!dir.equals(start)) {
                        Verdict verdict = verdict(dir, attrs, data);
                        if (verdict == Verdict.UNREADABLE) {
                            unreadable.add(relative(start, dir));
                        }
                        if (verdict != Verdict.WALK) {
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (++visited[0] % STOP_EVERY == 0 && stop.getAsBoolean()) {
                        stopped[0] = true;
                        return FileVisitResult.TERMINATE;
                    }
                    String name = file.getFileName().toString();
                    // Links are visited as themselves and skipped; cloud placeholders (reparse points) are files.
                    if (ImportNames.isFile(attrs, windows) && ImportNames.isListed(name)) {
                        files.add(new Found(relative(start, file), attrs.size(), time(attrs.lastModifiedTime().toInstant())));
                        progress.accept(files.size());
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException e) {
                    LOG.info("Import could not read {}: {}", file, e.toString());
                    unreadable.add(relative(start, file));
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException e) {
                    if (e != null) {
                        LOG.info("Import could not read all of {}: {}", dir, e.toString());
                        unreadable.add(relative(start, dir));
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            LOG.warn("Import could not walk {}", root, e);
            unreadable.add("");
        }
        return stopped[0] ? Optional.empty() : Optional.of(new Listing(files, unreadable));
    }

    /** Tests: run {@code work} between the suggestions and the walk's write. */
    void beforeWrite(Runnable work) {
        beforeWrite = work;
    }

    /** Those of {@code ids} that have no taxon row. */
    private static Set<String> goneTaxa(Connection c, Set<String> ids) throws SQLException {
        Set<String> gone = new HashSet<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM taxon WHERE id = ?")) {
            for (String id : ids) {
                ps.setString(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        gone.add(id);
                    }
                }
            }
        }
        return gone;
    }

    /** Saves a walk of {@code root} in one transaction (and points the import at {@code root} when relocating). */
    Result apply(long runId, Path root, Listing walked, boolean relocating) {
        // New items get ids in folder order (natural, by path), so the grid and filing follow the folder.
        List<Found> sorted = new ArrayList<>(walked.files());
        sorted.sort(Comparator.comparing(Found::relPath, ImportNames.NATURAL));
        Listing listing = new Listing(sorted, walked.unreadable());
        String rootName = root.getFileName() == null ? null : root.getFileName().toString();
        Map<String, Existing> before = library.get().database().read(c -> existing(c, runId));
        // Suggestions search outside the write transaction, so the lock is held only for the writes.
        Suggestions suggestions = new Suggestions(search);
        Map<String, String> suggested = new HashMap<>();
        Map<String, Existing> beforeByKey = byKey(before.values());
        for (Found found : listing.files()) {
            Existing old = before.containsKey(found.relPath()) ? before.get(found.relPath()) : beforeByKey.get(key(found.relPath()));
            if (old == null || (!old.done() && (old.missing() || old.changedFrom(found)))) {
                suggestions.forFile(rootName, found.relPath()).ifPresent(id -> suggested.put(found.relPath(), id));
            }
        }
        Instant now = clock.instant();
        beforeWrite.run();
        Database.Work<Result> work = c -> {
            // A suggested species deleted since it was suggested (a custom species) is no suggestion any more.
            suggested.values().removeAll(goneTaxa(c, new HashSet<>(suggested.values())));
            Map<String, Existing> current = existing(c, runId);
            Map<String, Existing> currentByKey = byKey(current.values());
            // Each file finds its item: the same path first, then the same name in another Unicode form.
            Map<Found, Existing> matched = new HashMap<>();
            Set<Long> claimed = new HashSet<>();
            for (Found found : listing.files()) {
                Existing old = current.get(found.relPath());
                if (old != null) {
                    matched.put(found, old);
                    claimed.add(old.id());
                }
            }
            for (Found found : listing.files()) {
                Existing old = currentByKey.get(key(found.relPath()));
                if (!matched.containsKey(found) && old != null && claimed.add(old.id())) {
                    matched.put(found, old);
                }
            }
            List<Found> inserts = new ArrayList<>();
            List<Found> resets = new ArrayList<>();
            List<Found> renames = new ArrayList<>();
            List<Existing> gone = new ArrayList<>();
            int added = 0;
            int changed = 0;
            int returned = 0;
            Set<String> oldShas = new HashSet<>();
            for (Found found : listing.files()) {
                Existing old = matched.get(found);
                if (old == null) {
                    inserts.add(found);
                    added++;
                    continue;
                }
                if (!old.relPath().equals(found.relPath())) {
                    renames.add(found);
                }
                if (!old.done() && (old.missing() || old.changedFrom(found))) {
                    resets.add(found);
                    if (old.missing()) {
                        returned++;
                    } else {
                        changed++;
                    }
                    oldShas.add(old.sha256());
                }
            }
            for (Existing old : current.values()) {
                if (!claimed.contains(old.id()) && !old.done() && !old.missing() && !under(old.relPath(), listing.unreadable())) {
                    gone.add(old);
                    oldShas.add(old.sha256());
                }
            }
            boolean anything = relocating || !inserts.isEmpty() || !resets.isEmpty() || !renames.isEmpty() || !gone.isEmpty();
            if (anything) {
                long seq = ImportStore.stamp(c, runId, now);
                try (PreparedStatement insert = c.prepareStatement("INSERT INTO import_item (run_id, rel_path, file_size,"
                        + " modified_at, read_state, suggested_taxon_id, seq) VALUES (?, ?, ?, ?, 'pending', ?, ?)");
                     PreparedStatement reset = c.prepareStatement("UPDATE import_item SET file_size = ?, modified_at = ?,"
                        + " read_state = 'pending', problem = NULL, sha256 = NULL, format = NULL, captured_at = NULL,"
                        + " meta_json = NULL, v = NULL, library_taxon_id = NULL, copy_of_item_id = NULL, gps_place_id = NULL,"
                        + " suggested_taxon_id = ?, seq = ? WHERE id = ?");
                     PreparedStatement rename = c.prepareStatement("UPDATE import_item SET rel_path = ?, seq = ? WHERE id = ?");
                     PreparedStatement vanished = c.prepareStatement("UPDATE import_item SET read_state = 'missing',"
                        + " copy_of_item_id = NULL, seq = ? WHERE id = ?")) {
                    for (Found found : inserts) {
                        insert.setLong(1, runId);
                        insert.setString(2, found.relPath());
                        insert.setLong(3, found.size());
                        insert.setString(4, found.modifiedAt());
                        insert.setString(5, suggested.get(found.relPath()));
                        insert.setLong(6, seq);
                        insert.addBatch();
                    }
                    for (Found found : resets) {
                        reset.setLong(1, found.size());
                        reset.setString(2, found.modifiedAt());
                        reset.setString(3, suggested.get(found.relPath()));
                        reset.setLong(4, seq);
                        reset.setLong(5, matched.get(found).id());
                        reset.addBatch();
                    }
                    for (Found found : renames) {
                        rename.setString(1, found.relPath()); // as on disk now
                        rename.setLong(2, seq);
                        rename.setLong(3, matched.get(found).id());
                        rename.addBatch();
                    }
                    for (Existing old : gone) {
                        vanished.setLong(1, seq);
                        vanished.setLong(2, old.id());
                        vanished.addBatch();
                    }
                    insert.executeBatch();
                    reset.executeBatch();
                    rename.executeBatch();
                    vanished.executeBatch();
                }
                // an original edited or gone: its copies get another
                ImportStore.recomputeCopies(c, runId, oldShas, seq);
                ImportPairs.recompute(c, runId, seq);
            }
            try (PreparedStatement ps = c.prepareStatement("UPDATE import_run SET last_walk_at = ?"
                    + (relocating ? ", root_path = ?" : "") + " WHERE id = ?")) {
                ps.setString(1, now.toString());
                if (relocating) {
                    ps.setString(2, root.toString());
                    ps.setLong(3, runId);
                } else {
                    ps.setLong(2, runId);
                }
                ps.executeUpdate();
            }
            return new Result(added, changed, gone.size(), returned, listing.files().size());
        };
        Database db = library.get().database();
        // A relocate changes the import's folder: through the store, so the imports kept in memory follow it.
        return relocating ? ImportStore.change(db, work) : db.write(work);
    }

    private static Map<String, Existing> byKey(Collection<Existing> items) {
        Map<String, Existing> byKey = new HashMap<>();
        for (Existing item : items) {
            byKey.putIfAbsent(key(item.relPath()), item);
        }
        return byKey;
    }

    /** An item as the walk compares it. */
    private record Existing(long id, String relPath, long size, String modifiedAt, boolean missing, boolean done,
                            String sha256) {

        boolean changedFrom(Found found) {
            if (size != found.size()) {
                return true;
            }
            try {
                return Math.abs(Instant.parse(modifiedAt).toEpochMilli() - Instant.parse(found.modifiedAt()).toEpochMilli())
                        > SAME_TIME_MILLIS;
            } catch (RuntimeException e) {
                return !modifiedAt.equals(found.modifiedAt());
            }
        }
    }

    private static Map<String, Existing> existing(Connection c, long runId) throws SQLException {
        Map<String, Existing> items = new HashMap<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT i.id, i.rel_path, i.file_size, i.modified_at, i.read_state,"
                + " " + ImportStore.DONE + ", i.sha256 FROM import_item i WHERE i.run_id = ?")) {
            ps.setLong(1, runId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    items.put(rs.getString(2), new Existing(rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getString(4),
                            ImportStore.MISSING.equals(rs.getString(5)), rs.getBoolean(6), rs.getString(7)));
                }
            }
        }
        return items;
    }

    /** Whether {@code relPath} lies in one of {@code folders} ({@code ""} is the root: everything). */
    private static boolean under(String relPath, Set<String> folders) {
        for (String folder : folders) {
            if (folder.isEmpty() || relPath.equals(folder) || relPath.startsWith(folder + "/")) {
                return true;
            }
        }
        return false;
    }

    /**
     * What to do with a folder below the start: leave out system folder names, Windows hidden or system folders,
     * symbolic links, and the library's data folder; on Windows also NTFS junctions, which Java reports as plain
     * folders, found by their real path not being the path they were reached by. A folder whose real path cannot be
     * resolved is {@code UNREADABLE}: left out, but its items are not marked missing. Elsewhere no real path is asked
     * for (network mounts), the no-follow walk having already kept symbolic links out.
     */
    Verdict verdict(Path dir, BasicFileAttributes attrs, Path data) {
        return verdict(dir, attrs, data, windows, realPath);
    }

    private static Verdict verdict(Path dir, BasicFileAttributes attrs, Path data, boolean windows, RealPath realPath) {
        if (ImportNames.isSkippedFolder(dir.getFileName().toString()) || FolderBrowser.isHiddenOrSystem(attrs)
                || attrs.isSymbolicLink() || dir.equals(data)) {
            return Verdict.SKIP;
        }
        if (windows) {
            try {
                return realPath.of(dir).equals(dir) ? Verdict.WALK : Verdict.SKIP;
            } catch (IOException e) {
                LOG.info("Import could not resolve {}: {}", dir, e.toString());
                return Verdict.UNREADABLE;
            }
        }
        return Verdict.WALK;
    }

    private Path realOrSelf(Path path) {
        return realOrSelf(realPath, path);
    }

    private static Path realOrSelf(RealPath realPath, Path path) {
        try {
            return realPath.of(path);
        } catch (IOException e) {
            return path;
        }
    }

    /** The key two spellings of one name share: its Unicode NFC form (macOS keeps names decomposed). */
    static String key(String relPath) {
        return Normalizer.normalize(relPath, Normalizer.Form.NFC);
    }

    private static String relative(Path start, Path file) {
        Path rel = start.relativize(file);
        List<String> parts = new ArrayList<>();
        rel.forEach(part -> parts.add(part.toString()));
        return String.join("/", parts);
    }

    /** File times to the millisecond, as stored and compared. */
    static String time(Instant instant) {
        return instant.truncatedTo(ChronoUnit.MILLIS).toString();
    }
}
