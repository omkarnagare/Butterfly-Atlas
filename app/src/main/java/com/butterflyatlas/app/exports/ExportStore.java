package com.butterflyatlas.app.exports;

import com.butterflyatlas.app.AtlasException;
import com.butterflyatlas.app.ErrorCodes;
import com.butterflyatlas.app.JobStates;
import com.butterflyatlas.app.db.Database;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Exports as the library records them ({@code export_run}, {@code export_item}; 4b contract §2, §3): the run with its
 * choices and counts, its photo list fixed at the start, progress saved in batches, and the history. Each change of
 * state happens only from the states it comes from, and the database holds at most one export running or paused.
 * <p>
 * The current export and the history are also kept in memory, so the reads polled every second ({@link #current},
 * {@link #history}) never open a connection while a copy writes: every change to {@code export_run} goes through this
 * store, which reads the runs it changed in the same write and keeps them once the write commits (writes are made one
 * at a time here, so what is kept follows the order of the commits). They are read from the database once, when first
 * asked; a write that fails may still have committed (a failure closing the connection), so they are then read again.
 */
public final class ExportStore {

    public static final String RUNNING = JobStates.RUNNING;
    public static final String PAUSED = JobStates.PAUSED;
    public static final String DONE = JobStates.DONE;
    public static final String CANCELLED = JobStates.CANCELLED;

    public static final String PENDING = JobStates.PENDING;
    public static final String ITEM_DONE = JobStates.DONE;
    public static final String FAILED = "failed";
    public static final String SKIPPED = "skipped";

    static final String CLOSING_NOTICE = "This export stopped when Butterfly Atlas closed.";
    /** At most this many items are inserted per statement batch at the start. */
    private static final int BATCH = 500;

    private static final String COLUMNS = "id, dest_root, folder_name, what, layout, strip_location, place_depth,"
            + " filter_query, csv_name, species, state, paused_by, notice, cancel_choice, not_removed, total, copied, skipped,"
            + " failed, renamed, total_bytes, bytes_done, started_at, finished_at, raw_with_location, raw_count, heic_count,"
            + " accepted_kinds";

    /** One export. {@code folderName} is the export folder, or the CSV file for CSV only, inside {@code destRoot}. */
    public record Run(long id, String destRoot, String folderName, String what, String layout, boolean stripLocation,
                      String placeDepth, String filter, String csvName, int species, String state, String pausedBy,
                      String notice, String cancel, int notRemoved, int total, int copied, int skipped, int failed,
                      int renamed, long totalBytes, long bytesDone, String startedAt, String finishedAt,
                      boolean rawWithLocation, int rawCount, int heicCount, List<String> acceptedKinds) {

        /** The export folder (or the CSV file for CSV only). */
        public Path folder() {
            return Path.of(destRoot).resolve(folderName);
        }

        /** Photos dealt with: copied, skipped or failed. */
        public int done() {
            return copied + skipped + failed;
        }

        public boolean finished() {
            return DONE.equals(state) || CANCELLED.equals(state);
        }
    }

    /**
     * What a new export records about itself. Its {@code raw_count} starts at 0 and counts the RAW photos as they are
     * copied (fixes J6), and {@code heic_count} the HEIC photos (Phase 7b contract §6); {@code rawWithLocation} is the
     * acceptance of both.
     */
    public record NewRun(String destRoot, String folderName, String what, String layout, boolean stripLocation,
                         String placeDepth, String filter, String csvName, int species, String state, Long csvBytes,
                         Long csvMtime, boolean rawWithLocation, List<String> acceptedKinds) {

        public NewRun(String destRoot, String folderName, String what, String layout, boolean stripLocation,
                      String placeDepth, String filter, String csvName, int species, String state, Long csvBytes,
                      Long csvMtime, boolean rawWithLocation) {
            this(destRoot, folderName, what, layout, stripLocation, placeDepth, filter, csvName, species, state, csvBytes,
                    csvMtime, rawWithLocation, rawWithLocation ? List.of("raw", "heic") : List.of());
        }

        /** A run without the RAW acceptance. */
        public NewRun(String destRoot, String folderName, String what, String layout, boolean stripLocation,
                      String placeDepth, String filter, String csvName, int species, String state, Long csvBytes,
                      Long csvMtime) {
            this(destRoot, folderName, what, layout, stripLocation, placeDepth, filter, csvName, species, state, csvBytes,
                    csvMtime, false);
        }
    }

    /**
     * A photo of a new export: the photo, its species and file name at the start, its target, its size, and whether it
     * is RAW or HEIC (copied exactly, never through the stripper).
     */
    public record NewItem(long imageId, String taxonId, String fileName, String targetRel, boolean renamed, long bytes,
                          boolean raw, boolean heic) {

        public NewItem(long imageId, String taxonId, String fileName, String targetRel, boolean renamed, long bytes) {
            this(imageId, taxonId, fileName, targetRel, renamed, bytes, false, false);
        }

        public NewItem(long imageId, String taxonId, String fileName, String targetRel, boolean renamed, long bytes,
                       boolean raw) {
            this(imageId, taxonId, fileName, targetRel, renamed, bytes, raw, false);
        }
    }

    /** A photo still to copy; {@code raw} and {@code heic} as marked at the start. */
    public record Item(int seq, long imageId, String fileName, String targetRel, long bytes, boolean raw, boolean heic) {

        public Item(int seq, long imageId, String fileName, String targetRel, long bytes) {
            this(seq, imageId, fileName, targetRel, bytes, false, false);
        }

        public Item(int seq, long imageId, String fileName, String targetRel, long bytes, boolean raw) {
            this(seq, imageId, fileName, targetRel, bytes, raw, false);
        }

        /** Copied exactly (RAW or HEIC), never through the stripper. */
        public boolean exact() {
            return raw || heic;
        }
    }

    /**
     * How one photo went: its new state, why (skipped, failed), and for one done the bytes written and the file's
     * modification time (ms; null when not known).
     */
    public record Outcome(int seq, String state, String reason, long bytes, Long mtime) {

        public Outcome(int seq, String state, String reason, long bytes) {
            this(seq, state, reason, bytes, null);
        }
    }

    /** A file the export wrote: its target, its size and its modification time (ms; null when not known). */
    public record Written(String targetRel, long bytes, Long mtime) {
    }

    /** A skipped, failed or renamed photo, as the export's details show it. */
    public record Problem(String kind, long imageId, String file, String species, String target, String reason) {
    }

    /** The export running or paused (null when none) and the done and cancelled ones, newest first, read together. */
    public record History(Run current, List<Run> past) {
    }

    /** One export with up to a limit of its problems and how many it has in all, read together. */
    public record Details(Run run, List<Problem> problems, int count) {
    }

    /** The current export and the past ones as the database holds them, and the database they come from. */
    private record Kept(Database database, Run current, List<Run> past) {
    }

    private final Supplier<Database> database;
    private final Clock clock;
    /** Held by each write of {@code export_run} and by reading the runs into memory, so they are kept in order. */
    private final Object writes = new Object();
    /** The runs in memory; null until first read, and after a write that may or may not have committed. */
    private volatile Kept kept;

    public ExportStore(Supplier<Database> database, Clock clock) {
        this.database = database;
        this.clock = clock;
    }

    /**
     * Records a new export and its photos in one write (the items in batches of {@value #BATCH}); a CSV-only export is
     * created done. Returns its id.
     */
    public long record(NewRun run, List<NewItem> items) {
        return record(run, items, () -> { }, () -> { });
    }

    /**
     * As {@link #record(NewRun, List)}, running {@code before} inside the write first, and {@code undo} when the write
     * does not commit ({@link Database#write(Database.Work, Runnable)}).
     */
    long record(NewRun run, List<NewItem> items, Runnable before, Runnable undo) {
        return change(c -> {
            before.run();
            return create(c, run, items);
        }, null, undo);
    }

    private long create(Connection c, NewRun run, List<NewItem> items) throws SQLException {
        String now = clock.instant().toString();
        long id;
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO export_run (dest_root, folder_name, what, layout,"
                + " strip_location, place_depth, filter_query, csv_name, species, state, total, total_bytes,"
                + " started_at, finished_at, csv_bytes, csv_mtime, raw_with_location, accepted_kinds)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                PreparedStatement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, run.destRoot());
            ps.setString(2, run.folderName());
            ps.setString(3, run.what());
            ps.setString(4, run.layout());
            ps.setInt(5, run.stripLocation() ? 1 : 0);
            ps.setString(6, run.placeDepth());
            ps.setString(7, run.filter());
            ps.setString(8, run.csvName());
            ps.setInt(9, run.species());
            ps.setString(10, run.state());
            ps.setInt(11, items.size());
            ps.setLong(12, items.stream().mapToLong(NewItem::bytes).sum());
            ps.setString(13, now);
            ps.setString(14, DONE.equals(run.state()) ? now : null);
            ps.setObject(15, run.csvBytes());
            ps.setObject(16, run.csvMtime());
            ps.setInt(17, run.rawWithLocation() ? 1 : 0);
            ps.setString(18, String.join(",", run.acceptedKinds()));
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                id = keys.getLong(1);
            }
        }
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO export_item (run_id, seq, image_id, taxon_id, file_name,"
                + " target_rel, renamed, bytes, raw, heic) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            int seq = 0;
            for (NewItem item : items) {
                ps.setLong(1, id);
                ps.setInt(2, ++seq);
                ps.setLong(3, item.imageId());
                ps.setString(4, item.taxonId());
                ps.setString(5, item.fileName());
                ps.setString(6, item.targetRel());
                ps.setInt(7, item.renamed() ? 1 : 0);
                ps.setLong(8, item.bytes());
                ps.setInt(9, item.raw() ? 1 : 0);
                ps.setInt(10, item.heic() ? 1 : 0);
                ps.addBatch();
                if (seq % BATCH == 0) {
                    ps.executeBatch();
                }
            }
            ps.executeBatch();
        }
        return id;
    }

    /** 404 {@code not_found}: no export has that id. */
    public static AtlasException notFound() {
        return new AtlasException(AtlasException.Kind.NOT_FOUND, ErrorCodes.NOT_FOUND, "That export can't be found.");
    }

    public Optional<Run> find(long id) {
        return database.get().read(c -> runs(c, "WHERE id = ?", id).stream().findFirst());
    }

    /** The export, or 404 {@code not_found}. */
    public Run run(long id) {
        return find(id).orElseThrow(ExportStore::notFound);
    }

    /** The export running or paused, if any (from memory). */
    public Optional<Run> current() {
        return Optional.ofNullable(kept().current());
    }

    /** Done and cancelled exports, newest first (from memory). */
    List<Run> past() {
        return kept().past();
    }

    /**
     * The current export and the history from one state of the library (an export finishing meanwhile is in one), from
     * memory.
     */
    public History history() {
        Kept k = kept();
        return new History(k.current(), k.past());
    }

    /** The export running or paused (null when none) if the runs are in memory, else empty: never reads (a status poll). */
    public Optional<Optional<Run>> currentKept() {
        Kept k = kept;
        return k != null && k.database() == database.get() ? Optional.of(Optional.ofNullable(k.current())) : Optional.empty();
    }

    /** The runs in memory, read from the database when not yet read (or read from another library). */
    private Kept kept() {
        Database db = database.get();
        Kept k = kept;
        if (k != null && k.database() == db) {
            return k;
        }
        synchronized (writes) {
            k = kept;
            if (k == null || k.database() != db) {
                k = db.snapshot(c -> load(db, c));
                kept = k;
            }
            return k;
        }
    }

    private static Kept load(Database db, Connection c) throws SQLException {
        return new Kept(db, current(c).orElse(null), List.copyOf(past(c)));
    }

    /**
     * The runs kept after a write that changed only export {@code id}: that one read again (in the write), the others
     * as they were.
     */
    private static Kept with(Kept before, long id, Connection c) throws SQLException {
        Run run = runs(c, "WHERE id = ?", id).stream().findFirst().orElse(null);
        Run current = before.current() != null && before.current().id() != id ? before.current() : null;
        List<Run> past = new ArrayList<>(before.past().size() + 1);
        for (Run r : before.past()) {
            if (r.id() != id) {
                past.add(r);
            }
        }
        if (run != null && run.finished()) {
            int at = 0;
            while (at < past.size() && past.get(at).id() > id) {
                at++;
            }
            past.add(at, run); // newest first
        } else if (run != null) {
            current = run;
        }
        return new Kept(before.database(), current, List.copyOf(past));
    }

    /**
     * A write that changes {@code export_run}: the work, then the runs read again in the same write ({@code id}: only
     * that export changed; null: any), kept once it commits. When the write fails it may still have committed (a
     * failure closing the connection), so the runs are read from the database again when next asked.
     */
    private <T> T change(Database.Work<T> work, Long id, Runnable undo) {
        Database db = database.get();
        synchronized (writes) {
            Kept before = kept != null && kept.database() == db ? kept : null;
            List<Kept> after = new ArrayList<>(1);
            T result;
            try {
                result = db.write(c -> {
                    T r = work.apply(c);
                    after.add(id == null || before == null ? load(db, c) : with(before, id, c));
                    return r;
                }, undo);
            } catch (RuntimeException | Error e) {
                kept = null;
                throw e;
            }
            kept = after.get(0);
            return result;
        }
    }

    /** The export, its first {@code limit} problems and their count, from one state of the library; 404 when unknown. */
    public Details details(long id, int limit) {
        return database.get().snapshot(c -> {
            Run run = runs(c, "WHERE id = ?", id).stream().findFirst().orElseThrow(ExportStore::notFound);
            return new Details(run, problems(c, id, limit), problemCount(c, id));
        });
    }

    private static Optional<Run> current(Connection c) throws SQLException {
        // the database holds at most one (the export_run_one index): the order and limit only make that explicit
        return runs(c, "WHERE state IN ('running', 'paused') ORDER BY id DESC LIMIT 1").stream().findFirst();
    }

    private static List<Run> past(Connection c) throws SQLException {
        return runs(c, "WHERE state IN ('done', 'cancelled') ORDER BY id DESC");
    }

    /** Up to {@code limit} photos still to copy after {@code afterSeq}, in order. */
    public List<Item> pending(long runId, int afterSeq, int limit) {
        return database.get().read(c -> {
            List<Item> items = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT seq, image_id, file_name, target_rel, bytes, raw, heic"
                    + " FROM export_item WHERE run_id = ? AND state = 'pending' AND seq > ? ORDER BY seq LIMIT ?")) {
                ps.setLong(1, runId);
                ps.setInt(2, afterSeq);
                ps.setInt(3, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        items.add(new Item(rs.getInt(1), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getLong(5),
                                rs.getInt(6) == 1, rs.getInt(7) == 1));
                    }
                }
            }
            return items;
        });
    }

    /** The bytes (sizes at the start) of the photos still to copy. */
    public long pendingBytes(long runId) {
        return database.get().read(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT COALESCE(SUM(bytes), 0) FROM export_item WHERE run_id = ?"
                    + " AND state = 'pending'")) {
                ps.setLong(1, runId);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getLong(1);
                }
            }
        });
    }

    /** Saves how these photos went, and the run's counts with them, in one write. */
    public void save(long runId, List<Outcome> outcomes) {
        if (outcomes.isEmpty()) {
            return;
        }
        change(c -> {
            int copied = 0;
            int skipped = 0;
            int failed = 0;
            int renamed = 0;
            int raw = 0;
            int heic = 0;
            long bytes = 0;
            try (PreparedStatement ps = c.prepareStatement("UPDATE export_item SET state = ?, reason = ?, written_bytes = ?,"
                    + " written_mtime = ? WHERE run_id = ? AND seq = ? AND state = 'pending'"
                    + " RETURNING bytes, renamed, raw, heic")) {
                for (Outcome o : outcomes) {
                    boolean done = ITEM_DONE.equals(o.state());
                    ps.setString(1, o.state());
                    ps.setString(2, o.reason());
                    ps.setObject(3, done ? o.bytes() : null);
                    ps.setObject(4, done ? o.mtime() : null);
                    ps.setLong(5, runId);
                    ps.setInt(6, o.seq());
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            continue; // already recorded: counted once
                        }
                        bytes += rs.getLong(1); // the original's size, as total_bytes counts it: done = total, all bytes
                        if (done && rs.getInt(2) == 1) {
                            renamed++; // a shortened name counts once its photo is copied under it
                        }
                        if (done && rs.getInt(3) == 1) {
                            raw++; // a RAW photo counts once it is copied, with its location (fixes J6)
                        }
                        if (done && rs.getInt(4) == 1) {
                            heic++; // a HEIC photo likewise (Phase 7b contract §6)
                        }
                    }
                    switch (o.state()) {
                        case ITEM_DONE -> copied++;
                        case SKIPPED -> skipped++;
                        case FAILED -> failed++;
                        default -> throw new IllegalArgumentException("Unknown outcome " + o.state());
                    }
                }
            }
            try (PreparedStatement ps = c.prepareStatement("UPDATE export_run SET copied = copied + ?, skipped = skipped + ?,"
                    + " failed = failed + ?, renamed = renamed + ?, raw_count = raw_count + ?, heic_count = heic_count + ?,"
                    + " bytes_done = bytes_done + ? WHERE id = ?")) {
                ps.setInt(1, copied);
                ps.setInt(2, skipped);
                ps.setInt(3, failed);
                ps.setInt(4, renamed);
                ps.setInt(5, raw);
                ps.setInt(6, heic);
                ps.setLong(7, bytes);
                ps.setLong(8, runId);
                ps.executeUpdate();
            }
            return null;
        }, runId, () -> { });
    }

    /** A photo marked RAW at the start whose file no longer is RAW: copied as any photo, not counted as RAW (fixes J8). */
    public void notRaw(long runId, int seq) {
        marks(runId, seq, false, false);
    }

    /**
     * Sets a photo's exact marks as its file is now (fixes J8, Phase 7b audit 23): a RAW now HEIC (or the other way) is
     * counted as what it is; one neither is copied as any photo.
     */
    public void marks(long runId, int seq, boolean raw, boolean heic) {
        update(runId, "UPDATE export_item SET raw = ?, heic = ? WHERE run_id = ? AND seq = ?", raw ? 1L : 0L, heic ? 1L : 0L,
                runId, (long) seq);
    }

    /** Running → paused, for this reason (with its notice); a paused export stays as it is. True when it changed. */
    public boolean pause(long runId, String pausedBy, String notice) {
        return update(runId, "UPDATE export_run SET state = 'paused', paused_by = ?, notice = ? WHERE id = ? AND state"
                + " = 'running'", pausedBy, notice, runId) > 0;
    }

    /** Paused → running. True when it changed. */
    public boolean resume(long runId) {
        return update(runId, "UPDATE export_run SET state = 'running', paused_by = NULL, notice = NULL WHERE id = ?"
                + " AND state = 'paused'", runId) > 0;
    }

    /** Running → done (every photo dealt with). True when it changed. */
    public boolean finish(long runId) {
        return update(runId, "UPDATE export_run SET state = 'done', paused_by = NULL, notice = NULL, finished_at = ?"
                + " WHERE id = ? AND state = 'running'", clock.instant().toString(), runId) > 0;
    }

    /**
     * Running or paused → cancelled, keeping or removing the files ({@code notRemoved}: files Remove could not delete).
     * True when it changed (false: it had finished already).
     */
    public boolean cancel(long runId, String choice, int notRemoved) {
        return update(runId, "UPDATE export_run SET state = 'cancelled', paused_by = NULL, notice = NULL,"
                + " cancel_choice = ?, not_removed = ?, finished_at = ? WHERE id = ? AND state IN ('running', 'paused')",
                choice, (long) notRemoved, clock.instant().toString(), runId) > 0;
    }

    /** At startup: an export still marked running was cut short by the app closing, and is paused. Returns how many. */
    public int markClosing() {
        return change(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE export_run SET state = 'paused', paused_by = 'closing',"
                    + " notice = ? WHERE state = 'running'")) {
                ps.setString(1, CLOSING_NOTICE);
                return ps.executeUpdate();
            }
        }, null, () -> { });
    }

    /** The files recorded as written (done), in order, with what was recorded of them. */
    public List<Written> writtenFiles(long runId) {
        return database.get().read(c -> {
            List<Written> files = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT target_rel, written_bytes, written_mtime FROM export_item"
                    + " WHERE run_id = ? AND state = 'done' ORDER BY seq")) {
                ps.setLong(1, runId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        long mtime = rs.getLong(3);
                        Long known = rs.wasNull() ? null : mtime;
                        files.add(new Written(rs.getString(1), rs.getLong(2), known));
                    }
                }
            }
            return files;
        });
    }

    /** The species list as written at the start (its name, size and time), or null when there is none. */
    public Written csvWritten(long runId) {
        return database.get().read(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT csv_name, csv_bytes, csv_mtime FROM export_run WHERE id = ?"
                    + " AND csv_name IS NOT NULL")) {
                ps.setLong(1, runId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return null;
                    }
                    long bytes = rs.getLong(2); // 0 when not known: then neither is the time, so Remove keeps it
                    long mtime = rs.getLong(3);
                    Long known = rs.wasNull() ? null : mtime;
                    return new Written(rs.getString(1), bytes, known);
                }
            }
        });
    }

    /** The targets of the photos still to copy, in order. */
    public List<String> pendingTargets(long runId) {
        return targets(runId, " AND state = 'pending'");
    }

    /** Every target of the export, in order (their {@code .part} files are the export's own). */
    public List<String> targets(long runId) {
        return targets(runId, "");
    }

    /**
     * The export's problems: skipped and failed photos in item order, then renamed ones copied under their shortened
     * name, at most {@code limit}; the species is the photo's at the start.
     */
    List<Problem> problems(long runId, int limit) {
        return database.get().read(c -> problems(c, runId, limit));
    }

    private static List<Problem> problems(Connection c, long runId, int limit) throws SQLException {
        List<Problem> problems = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT kind, image_id, file_name, name, target_rel, reason FROM ("
                + " SELECT 0 AS part, e.seq, e.state AS kind, e.image_id, e.file_name, t.name, e.target_rel, e.reason"
                + " FROM export_item e LEFT JOIN taxon t ON t.id = e.taxon_id WHERE e.run_id = ? AND e.state IN"
                + " ('skipped', 'failed') UNION ALL SELECT 1 AS part, e.seq, 'renamed', e.image_id, e.file_name, t.name,"
                + " e.target_rel, NULL FROM export_item e LEFT JOIN taxon t ON t.id = e.taxon_id WHERE e.run_id = ? AND"
                + " e.renamed = 1 AND e.state = 'done') ORDER BY part, seq LIMIT ?")) {
            ps.setLong(1, runId);
            ps.setLong(2, runId);
            ps.setInt(3, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String kind = rs.getString(1);
                    String target = rs.getString(5);
                    String reason = "renamed".equals(kind)
                            ? "Saved as " + ExportNames.fileName(target) + ": the full name was too long."
                            : rs.getString(6);
                    problems.add(new Problem(kind, rs.getLong(2), rs.getString(3), rs.getString(4), target, reason));
                }
            }
        }
        return problems;
    }

    /** How many problems the export has in all. */
    int problemCount(long runId) {
        return database.get().read(c -> problemCount(c, runId));
    }

    private static int problemCount(Connection c, long runId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT (SELECT COUNT(*) FROM export_item WHERE run_id = ? AND state"
                + " IN ('skipped', 'failed')) + (SELECT COUNT(*) FROM export_item WHERE run_id = ? AND renamed = 1"
                + " AND state = 'done')")) {
            ps.setLong(1, runId);
            ps.setLong(2, runId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    /** Removes a done or cancelled export's record (never its files); false when it is running or paused. */
    public boolean delete(long runId) {
        return change(c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM export_run WHERE id = ? AND state IN ('done',"
                    + " 'cancelled')")) {
                ps.setLong(1, runId);
                return ps.executeUpdate() > 0;
            }
        }, runId, () -> { });
    }

    /** Removes the records of every done and cancelled export; returns how many. */
    public int deleteFinished() {
        return change(c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM export_run WHERE state IN ('done', 'cancelled')")) {
                return ps.executeUpdate();
            }
        }, null, () -> { });
    }

    private List<String> targets(long runId, String and) {
        return database.get().read(c -> {
            List<String> targets = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT target_rel FROM export_item WHERE run_id = ?" + and
                    + " ORDER BY seq")) {
                ps.setLong(1, runId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        targets.add(rs.getString(1));
                    }
                }
            }
            return targets;
        });
    }

    /** A change of export {@code runId} alone. */
    private int update(long runId, String sql, Object... params) {
        return change(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                for (int i = 0; i < params.length; i++) {
                    ps.setObject(i + 1, params[i]);
                }
                return ps.executeUpdate();
            }
        }, runId, () -> { });
    }

    private static List<Run> runs(Connection c, String where, Object... params) throws SQLException {
        List<Run> runs = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT " + COLUMNS + " FROM export_run " + where)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    runs.add(new Run(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                            rs.getInt(6) == 1, rs.getString(7), rs.getString(8), rs.getString(9), rs.getInt(10),
                            rs.getString(11), rs.getString(12), rs.getString(13), rs.getString(14), rs.getInt(15),
                            rs.getInt(16), rs.getInt(17), rs.getInt(18), rs.getInt(19), rs.getInt(20), rs.getLong(21),
                            rs.getLong(22), rs.getString(23), rs.getString(24), rs.getInt(25) == 1, rs.getInt(26),
                            rs.getInt(27), rs.getString(28) == null || rs.getString(28).isEmpty() ? List.of()
                                    : List.of(rs.getString(28).split(","))));
                }
            }
        }
        return runs;
    }
}
