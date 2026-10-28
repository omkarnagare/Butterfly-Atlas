package com.butterflyatlas.app.backup;

import com.butterflyatlas.app.AtlasException;
import com.butterflyatlas.app.ErrorCodes;
import com.butterflyatlas.app.JobStates;
import com.butterflyatlas.app.db.Database;
import com.butterflyatlas.app.exports.ExportStore;

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
 * Backups as the library records them ({@code backup_run}, {@code backup_file}, {@code backup_problem}; contract §1, §4):
 * the run with its choices and counts, the files it wrote, the photos it could not copy, progress saved in batches (with
 * the id of the last photo settled, so a resume goes on after it), and the history. Each change of state happens only from
 * the states it comes from, and the database holds at most one backup running or paused.
 * <p>
 * Like {@link ExportStore}, the current backup and the history are kept in memory, so the reads polled every second never
 * open a connection while a copy writes: every change to {@code backup_run} goes through this store, which reads the runs
 * again in the same write and keeps them once it commits; a write that fails may still have committed, so they are then
 * read again.
 */
public final class BackupStore {

    static final String RUNNING = JobStates.RUNNING;
    static final String PAUSED = JobStates.PAUSED;
    static final String DONE = JobStates.DONE;
    static final String CANCELLED = JobStates.CANCELLED;

    static final String ITEM_DONE = JobStates.DONE;
    static final String SKIPPED = "skipped";
    static final String FAILED = "failed";

    static final String CLOSED_NOTICE = "This backup stopped when Butterfly Atlas closed.";

    private static final String COLUMNS = "id, state, what, dest_root, folder, total, done, copied, skipped, failed,"
            + " bytes_total, bytes_done, paused_by, notice, cancel, not_removed, last_image_id, started_at, finished_at";

    /** One backup. {@code folder} is the folder it created inside {@code destRoot}. */
    record Run(long id, String state, String what, String destRoot, String folder, int total, int done, int copied,
               int skipped, int failed, long bytesTotal, long bytesDone, String pausedBy, String notice, String cancel,
               int notRemoved, long lastImageId, String startedAt, String finishedAt) {

        Path folderPath() {
            return Path.of(destRoot).resolve(folder);
        }

        boolean finished() {
            return DONE.equals(state) || CANCELLED.equals(state);
        }
    }

    /** What a new backup records about itself. */
    record NewRun(String what, String destRoot, String folder, int total, long bytesTotal) {
    }

    /** A live photo still to copy. */
    record Photo(long id, String rel, long bytes) {
    }

    /**
     * How one photo went: {@code state} done, skipped or failed; the reason for the last two; for a done one the size and
     * time (ms) of the file written. {@code bytes} is the original's size, which counts towards the progress either way.
     */
    record Outcome(long imageId, String rel, String state, String reason, long bytes, long size, long mtime) {
    }

    /** A skipped or failed photo, as the details show it. */
    public record Problem(String kind, Long imageId, String file, String species, String rel, String target, String reason) {
    }

    record History(Run current, List<Run> past) {
    }

    record Details(Run run, List<Problem> problems, int count) {
    }

    private record Kept(Database database, Run current, List<Run> past) {
    }

    private final Supplier<Database> database;
    private final Clock clock;
    private final Object writes = new Object();
    private volatile Kept kept;

    BackupStore(Supplier<Database> database, Clock clock) {
        this.database = database;
        this.clock = clock;
    }

    public static AtlasException notFound() {
        return new AtlasException(AtlasException.Kind.NOT_FOUND, ErrorCodes.NOT_FOUND, "That backup can't be found.");
    }

    /** Records a new backup, running. Returns its id. */
    long create(NewRun run) {
        return change(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO backup_run (state, what, dest_root, folder, total,"
                    + " bytes_total, started_at) VALUES ('running', ?, ?, ?, ?, ?, ?)", PreparedStatement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, run.what());
                ps.setString(2, run.destRoot());
                ps.setString(3, run.folder());
                ps.setInt(4, run.total());
                ps.setLong(5, run.bytesTotal());
                ps.setString(6, clock.instant().toString());
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    keys.next();
                    return keys.getLong(1);
                }
            }
        }, false);
    }

    Optional<Run> find(long id) {
        return database.get().read(c -> runs(c, "WHERE id = ?", id).stream().findFirst());
    }

    Run run(long id) {
        return find(id).orElseThrow(BackupStore::notFound);
    }

    /** The backup running or paused, if any (from memory). */
    Optional<Run> current() {
        return Optional.ofNullable(kept().current());
    }

    History history() {
        Kept k = kept();
        return new History(k.current(), k.past());
    }

    /** The backup running or paused (null when none) if the runs are in memory, else empty: never reads (a status poll). */
    Optional<Optional<Run>> currentKept() {
        Kept k = kept;
        return k != null && k.database() == database.get() ? Optional.of(Optional.ofNullable(k.current())) : Optional.empty();
    }

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
        return new Kept(db, runs(c, "WHERE state IN ('running', 'paused') ORDER BY id DESC LIMIT 1").stream().findFirst()
                .orElse(null), List.copyOf(runs(c, "WHERE state IN ('done', 'cancelled') ORDER BY id DESC")));
    }

    /**
     * A write that changes {@code backup_run}: the work, then the runs read again in the same write, kept once it
     * commits. {@code history}: the past ones may have changed too (else only the current one is read again).
     */
    private <T> T change(Database.Work<T> work, boolean history) {
        Database db = database.get();
        synchronized (writes) {
            Kept before = kept != null && kept.database() == db ? kept : null;
            List<Kept> after = new ArrayList<>(1);
            T result;
            try {
                result = db.write(c -> {
                    T r = work.apply(c);
                    if (history || before == null) {
                        after.add(load(db, c));
                    } else {
                        after.add(new Kept(db, runs(c, "WHERE state IN ('running', 'paused') ORDER BY id DESC LIMIT 1")
                                .stream().findFirst().orElse(null), before.past()));
                    }
                    return r;
                });
            } catch (RuntimeException | Error e) {
                kept = null;
                throw e;
            }
            kept = after.get(0);
            return result;
        }
    }

    /** Up to {@code limit} live photos after {@code afterId}, in id order. */
    List<Photo> photos(long afterId, int limit) {
        return database.get().read(c -> {
            List<Photo> photos = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT id, rel_path, file_size FROM image WHERE deleted_at IS NULL"
                    + " AND id > ? ORDER BY id LIMIT ?")) {
                ps.setLong(1, afterId);
                ps.setInt(2, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        photos.add(new Photo(rs.getLong(1), rs.getString(2), rs.getLong(3)));
                    }
                }
            }
            return photos;
        });
    }

    /** Saves how these photos went and the cursor (the last photo settled), and the run's counts, in one write. */
    void save(long runId, List<Outcome> outcomes, long lastImageId) {
        if (outcomes.isEmpty()) {
            return;
        }
        change(c -> {
            int copied = 0;
            int skipped = 0;
            int failed = 0;
            long bytes = 0;
            for (Outcome o : outcomes) {
                bytes += o.bytes();
                switch (o.state()) {
                    case ITEM_DONE -> {
                        copied++;
                        try (PreparedStatement ps = c.prepareStatement("INSERT OR REPLACE INTO backup_file (run_id, rel, size,"
                                + " mtime_ms) VALUES (?, ?, ?, ?)")) {
                            ps.setLong(1, runId);
                            ps.setString(2, o.rel());
                            ps.setLong(3, o.size());
                            ps.setLong(4, o.mtime());
                            ps.executeUpdate();
                        }
                    }
                    case SKIPPED, FAILED -> {
                        if (SKIPPED.equals(o.state())) {
                            skipped++;
                        } else {
                            failed++;
                        }
                        try (PreparedStatement ps = c.prepareStatement("INSERT OR REPLACE INTO backup_problem (run_id, rel,"
                                + " reason, kind) VALUES (?, ?, ?, ?)")) {
                            ps.setLong(1, runId);
                            ps.setString(2, o.rel());
                            ps.setString(3, o.reason());
                            ps.setString(4, o.state());
                            ps.executeUpdate();
                        }
                    }
                    default -> throw new IllegalArgumentException("Unknown outcome " + o.state());
                }
            }
            try (PreparedStatement ps = c.prepareStatement("UPDATE backup_run SET copied = copied + ?, skipped = skipped + ?,"
                    + " failed = failed + ?, done = done + ?, bytes_done = bytes_done + ?, last_image_id = MAX(last_image_id, ?),"
                    + " total = MAX(total, done + ?) WHERE id = ?")) {
                ps.setInt(1, copied);
                ps.setInt(2, skipped);
                ps.setInt(3, failed);
                ps.setInt(4, copied + skipped + failed);
                ps.setLong(5, bytes);
                ps.setLong(6, lastImageId);
                ps.setInt(7, copied + skipped + failed);
                ps.setLong(8, runId);
                ps.executeUpdate();
            }
            return null;
        }, false);
    }

    /** A file the backup wrote that is not a photo (the database copy), so Remove deletes it only while it is unchanged. */
    void addFile(long runId, String rel, long size, long mtime) {
        change(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT OR REPLACE INTO backup_file (run_id, rel, size, mtime_ms)"
                    + " VALUES (?, ?, ?, ?)")) {
                ps.setLong(1, runId);
                ps.setString(2, rel);
                ps.setLong(3, size);
                ps.setLong(4, mtime);
                ps.executeUpdate();
            }
            return null;
        }, false);
    }

    /**
     * Takes files out of the record because they are no longer as written (gone, or changed): their rows go, and the counts
     * and bytes they were counted in come back off, so each photo is counted once however often it is dealt with again.
     */
    void unrecord(long runId, List<ExportStore.Written> files) {
        if (files.isEmpty()) {
            return;
        }
        change(c -> {
            long bytes = 0;
            for (ExportStore.Written file : files) {
                bytes += file.bytes();
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM backup_file WHERE run_id = ? AND rel = ?")) {
                    ps.setLong(1, runId);
                    ps.setString(2, file.targetRel());
                    ps.executeUpdate();
                }
            }
            try (PreparedStatement ps = c.prepareStatement("UPDATE backup_run SET copied = MAX(0, copied - ?),"
                    + " done = MAX(0, done - ?), bytes_done = MAX(0, bytes_done - ?) WHERE id = ?")) {
                ps.setInt(1, files.size());
                ps.setInt(2, files.size());
                ps.setLong(3, bytes);
                ps.setLong(4, runId);
                ps.executeUpdate();
            }
            return null;
        }, false);
    }

    /** Forgets a file that is not a photo (the database copy) that is not as written, so it is made again. */
    void dropFile(long runId, String rel) {
        change(c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM backup_file WHERE run_id = ? AND rel = ?")) {
                ps.setLong(1, runId);
                ps.setString(2, rel);
                ps.executeUpdate();
            }
            return null;
        }, false);
    }

    /** The live photo whose file is at this path in the library, if there is one. */
    Optional<Photo> photoByRel(String rel) {
        return database.get().read(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT id, rel_path, file_size FROM image WHERE rel_path = ?"
                    + " AND deleted_at IS NULL")) {
                ps.setString(1, rel);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(new Photo(rs.getLong(1), rs.getString(2), rs.getLong(3))) : Optional.empty();
                }
            }
        });
    }

    boolean hasFile(long runId, String rel) {
        return database.get().read(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM backup_file WHERE run_id = ? AND rel = ?")) {
                ps.setLong(1, runId);
                ps.setString(2, rel);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next();
                }
            }
        });
    }

    /** Running → paused, for this reason (with its notice); a paused backup stays as it is. True when it changed. */
    boolean pause(long runId, String pausedBy, String notice) {
        return update("UPDATE backup_run SET state = 'paused', paused_by = ?, notice = ? WHERE id = ? AND state = 'running'",
                false, pausedBy, notice, runId) > 0;
    }

    /** Paused → running. True when it changed. */
    boolean resume(long runId) {
        return update("UPDATE backup_run SET state = 'running', paused_by = NULL, notice = NULL WHERE id = ?"
                + " AND state = 'paused'", false, runId) > 0;
    }

    /**
     * Running → done: every photo dealt with and the copies verified. In the same write the last backup is recorded
     * ({@code backup.last_at}, {@code last_what}, {@code last_unread} = skipped + failed): only a backup that is done sets
     * them. The total becomes what was dealt with (photos added or deleted meanwhile). True when it changed.
     */
    boolean finish(long runId) {
        return change(c -> {
            String now = clock.instant().toString();
            try (PreparedStatement ps = c.prepareStatement("UPDATE backup_run SET state = 'done', paused_by = NULL, notice = NULL,"
                    + " total = done, finished_at = ? WHERE id = ? AND state = 'running' RETURNING what, skipped + failed")) {
                ps.setString(1, now);
                ps.setLong(2, runId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return false;
                    }
                    BackupSettings.recordLast(c, now, rs.getString(1), rs.getInt(2));
                    return true;
                }
            }
        }, true);
    }

    /** Running or paused → cancelled, keeping or removing the files ({@code notRemoved}: files Remove could not delete). */
    boolean cancel(long runId, String choice, int notRemoved) {
        return update("UPDATE backup_run SET state = 'cancelled', paused_by = NULL, notice = NULL, cancel = ?, not_removed = ?,"
                + " finished_at = ? WHERE id = ? AND state IN ('running', 'paused')", true, choice, (long) notRemoved,
                clock.instant().toString(), runId) > 0;
    }

    /** At startup: a backup still marked running was cut short by the app closing, and is paused (closed). */
    int markClosed() {
        return change(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE backup_run SET state = 'paused', paused_by = 'closed',"
                    + " notice = ? WHERE state = 'running'")) {
                ps.setString(1, CLOSED_NOTICE);
                return ps.executeUpdate();
            }
        }, false);
    }

    /** The files recorded as written, in order, with what was recorded of them. */
    List<ExportStore.Written> writtenFiles(long runId) {
        return database.get().read(c -> {
            List<ExportStore.Written> files = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT rel, size, mtime_ms FROM backup_file WHERE run_id = ?"
                    + " ORDER BY rel")) {
                ps.setLong(1, runId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        files.add(new ExportStore.Written(rs.getString(1), rs.getLong(2), rs.getLong(3)));
                    }
                }
            }
            return files;
        });
    }

    Details details(long runId, int limit) {
        return database.get().snapshot(c -> {
            Run run = runs(c, "WHERE id = ?", runId).stream().findFirst().orElseThrow(BackupStore::notFound);
            List<Problem> problems = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT p.kind, i.id, p.rel, t.name, p.reason FROM backup_problem p"
                    + " LEFT JOIN image i ON i.rel_path = p.rel AND i.deleted_at IS NULL LEFT JOIN taxon t ON t.id = i.taxon_id"
                    + " WHERE p.run_id = ? ORDER BY p.rowid LIMIT ?")) {
                ps.setLong(1, runId);
                ps.setInt(2, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String rel = rs.getString(3);
                        long image = rs.getLong(2);
                        problems.add(new Problem(rs.getString(1), rs.wasNull() ? null : image,
                                rel.substring(rel.lastIndexOf('/') + 1), rs.getString(4), rel, rel, rs.getString(5)));
                    }
                }
            }
            int count;
            try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM backup_problem WHERE run_id = ?")) {
                ps.setLong(1, runId);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    count = rs.getInt(1);
                }
            }
            return new Details(run, problems, count);
        });
    }

    /** Removes a done or cancelled backup's record (never its files); false when it is running or paused (or gone). */
    boolean delete(long runId) {
        return change(c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM backup_run WHERE id = ? AND state IN ('done',"
                    + " 'cancelled')")) {
                ps.setLong(1, runId);
                return ps.executeUpdate() > 0;
            }
        }, true);
    }

    /** Removes the records of every done and cancelled backup; returns how many. */
    int deleteFinished() {
        return change(c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM backup_run WHERE state IN ('done', 'cancelled')")) {
                return ps.executeUpdate();
            }
        }, true);
    }

    private int update(String sql, boolean history, Object... params) {
        return change(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                for (int i = 0; i < params.length; i++) {
                    ps.setObject(i + 1, params[i]);
                }
                return ps.executeUpdate();
            }
        }, history);
    }

    private static List<Run> runs(Connection c, String where, Object... params) throws SQLException {
        List<Run> runs = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT " + COLUMNS + " FROM backup_run " + where)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    runs.add(new Run(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                            rs.getInt(6), rs.getInt(7), rs.getInt(8), rs.getInt(9), rs.getInt(10), rs.getLong(11),
                            rs.getLong(12), rs.getString(13), rs.getString(14), rs.getString(15), rs.getInt(16),
                            rs.getLong(17), rs.getString(18), rs.getString(19)));
                }
            }
        }
        return runs;
    }
}
