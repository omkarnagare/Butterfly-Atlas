package com.butterflyatlas.app.updates;

import com.butterflyatlas.app.ErrorCodes;
import com.butterflyatlas.app.JobStates;
import com.butterflyatlas.app.db.ChangeLog;
import com.butterflyatlas.app.db.Database;
import com.butterflyatlas.app.library.LibraryDrive;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;
import java.util.function.ToLongFunction;

/**
 * The moves of a step (5b contract §3 stage 2, §4): in the order of {@code seq}, batches of {@link #BATCH}; each file
 * is moved never over an existing file, and each batch's write marks its moves done and sets those photos'
 * {@code rel_path}, {@code file_name} and {@code number}, so a photo's row follows its file. A move not done is judged by what
 * is on disk: the old file there and the new not → moved; the old gone and the new there → done already (the app
 * stopped between the move and its record); both there → paused {@code both_present}; neither → paused {@code missing}
 * (a Retry that finds it still gone marks it done and leaves the record to the next start's rescan). A stop (the app
 * closing) takes effect after the current file, the batch's progress saved.
 */
final class UpdateMoves {

    private static final Logger LOG = LoggerFactory.getLogger(UpdateMoves.class);

    /** Moves per write. */
    static final int BATCH = 200;
    /** Free space below which a failed move means the drive is full. */
    static final long FULL_BELOW = LibraryDrive.FULL_BELOW;

    static final String LOCKED = "locked";
    static final String NO_ACCESS = "no_access";
    static final String TARGET_EXISTS = "target_exists";
    static final String BOTH_PRESENT = "both_present";
    static final String MISSING = "missing";
    static final String FAILED = "failed";
    /** What a failure with no words of its own is called to the user (the stack trace is in the log). */
    static final String UNEXPECTED = "an unexpected problem; the details are in the log file";

    /** How a file is moved (tests make it fail). */
    @FunctionalInterface
    interface Mover {
        void move(Path from, Path to) throws IOException;
    }

    /** How the moves ended: all {@code done}, {@code stopped} (the app closing), or {@code paused} with a notice. */
    record Outcome(String kind, String code, String message) {

        static final Outcome DONE = new Outcome(JobStates.DONE, null, null);
        static final Outcome STOPPED = new Outcome("stopped", null, null);

        static Outcome paused(String code, String message) {
            return new Outcome(JobStates.PAUSED, code, message);
        }

        boolean done() {
            return this == DONE;
        }
    }

    private record Move(int seq, long imageId, String oldRel, String newRel, String newFileName, Integer newNumber) {
    }

    private final Database db;
    private final Path data;
    private final Mover mover;
    private final ToLongFunction<Path> usableSpace;
    private final boolean windows;
    private final Clock clock;

    /** @param windows whether a file the computer will not let go is open in another program (Windows) */
    UpdateMoves(Database db, Path data, Mover mover, ToLongFunction<Path> usableSpace, boolean windows, Clock clock) {
        this.clock = clock;
        this.db = db;
        this.data = data;
        this.mover = mover;
        this.usableSpace = usableSpace;
        this.windows = windows;
    }

    /**
     * Moves what is left of {@code step}'s moves. {@code carryOnMissing}: the update was paused {@code missing}, and the
     * Retry carries on without the photo still gone (only the first move left is judged so). {@code moved} hears how many
     * moves each batch recorded.
     */
    Outcome run(long updateId, String step, boolean carryOnMissing, BooleanSupplier stop, IntConsumer moved) {
        boolean skipMissing = carryOnMissing;
        while (true) {
            List<Move> batch = pending(updateId, step);
            if (batch.isEmpty()) {
                return Outcome.DONE;
            }
            List<Move> finished = new ArrayList<>();
            List<Move> left = new ArrayList<>();
            Outcome stopped = null;
            for (Move m : batch) {
                if (stop.getAsBoolean()) {
                    stopped = Outcome.STOPPED;
                    break;
                }
                if (!reachable()) {
                    stopped = Outcome.paused(ErrorCodes.DRIVE_GONE, driveGone());
                    break;
                }
                Path from = UpdatePlanner.resolve(data, m.oldRel());
                Path to = UpdatePlanner.resolve(data, m.newRel());
                boolean old = Files.exists(from, LinkOption.NOFOLLOW_LINKS);
                boolean now = Files.exists(to, LinkOption.NOFOLLOW_LINKS);
                if (old && now && UpdatePlanner.sameFile(from, to)) {
                    finished.add(m); // the same file under another case: already where it belongs
                } else if (old && !now) {
                    try {
                        mover.move(from, to);
                        finished.add(m);
                    } catch (IOException | RuntimeException e) {
                        stopped = problem(e, m);
                        break;
                    }
                } else if (!old && now) {
                    finished.add(m); // moved before the app stopped, not yet recorded
                } else if (old) {
                    stopped = bothPresent(m);
                    break;
                } else if (skipMissing) {
                    LOG.warn("Taxonomy update: {} is gone from {} and {}; carrying on without it", name(m), m.oldRel(), m.newRel());
                    finished.add(m);
                    left.add(m);
                } else {
                    stopped = missing(m);
                    break;
                }
                skipMissing = false;
            }
            try {
                record(updateId, step, finished, left);
            } catch (RuntimeException e) {
                if (reachable()) {
                    throw e;
                }
                // The drive went away with the library's database: the files moved are found at their new place when
                // the update carries on (moved before the app stopped, not recorded).
                LOG.warn("Taxonomy update: the moves done could not be recorded; the library's drive is gone", e);
                return Outcome.paused(ErrorCodes.DRIVE_GONE, driveGone());
            }
            moved.accept(finished.size());
            if (stopped != null) {
                return stopped;
            }
        }
    }

    /** Why a move failed, in plain words. */
    private Outcome problem(Exception e, Move m) {
        LOG.warn("Taxonomy update could not move {} to {}", m.oldRel(), m.newRel(), e);
        if (!reachable()) {
            return Outcome.paused(ErrorCodes.DRIVE_GONE, driveGone());
        }
        if (e instanceof FileAlreadyExistsException) {
            return bothPresent(m);
        }
        if (e instanceof NoSuchFileException) {
            return missing(m);
        }
        if (e instanceof IOException && usableSpace.applyAsLong(data) < FULL_BELOW) {
            return Outcome.paused(ErrorCodes.DRIVE_FULL, driveFull());
        }
        if (e instanceof FileSystemException failure) {
            if (windows) { // access denied, a sharing violation: open in another program
                return Outcome.paused(LOCKED, name(m) + " is open in another program. Close it, then choose Retry.");
            }
            String reason = failure.getReason() == null ? "" : failure.getReason().toLowerCase(Locale.ROOT);
            if (failure instanceof AccessDeniedException || reason.contains("read-only") || reason.contains("not permitted")
                    || reason.contains("permission")) {
                return Outcome.paused(NO_ACCESS, "Butterfly Atlas can't change " + name(m) + " (no permission, or the drive is"
                        + " read-only). Fix that, then choose Retry.");
            }
        }
        return Outcome.paused(FAILED, failed(e));
    }

    static String failed(Exception e) {
        String detail = e.getMessage();
        if (detail == null || detail.isBlank()) {
            LOG.warn("A taxonomy update stopped on an error with no message", e);
            detail = UNEXPECTED; // never a class name: it means nothing to the person reading it
        }
        return "The taxonomy update stopped: " + detail + ". Choose Retry to try again.";
    }

    private static Outcome bothPresent(Move m) {
        return Outcome.paused(BOTH_PRESENT, name(m) + " is in both its old and its new folder. Keep one, then choose Retry.");
    }

    private static Outcome missing(Move m) {
        return Outcome.paused(MISSING, name(m) + " can't be found in its old or new folder. Put it back, or choose Retry to"
                + " carry on without it.");
    }

    static String driveFull() {
        return "The drive with your library is full. Free some space, then choose Retry.";
    }

    static String driveGone() {
        return "The drive with your library can't be found. Reconnect it, then choose Retry.";
    }

    /** The library's photos can be reached ({@code images/} is there): a drive unplugged takes it away. */
    boolean reachable() {
        return reachable(data);
    }

    static boolean reachable(Path data) {
        return Files.isDirectory(data.resolve("images"));
    }

    private static String name(Move m) {
        return m.oldRel().substring(m.oldRel().lastIndexOf('/') + 1);
    }

    private List<Move> pending(long updateId, String step) {
        return db.read(c -> {
            List<Move> moves = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT seq, image_id, old_rel, new_rel, new_file_name, new_number"
                    + " FROM taxonomy_move WHERE update_id = ? AND step_version = ? AND done = 0 ORDER BY seq LIMIT ?")) {
                ps.setLong(1, updateId);
                ps.setString(2, step);
                ps.setInt(3, BATCH);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        moves.add(new Move(rs.getInt(1), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getString(5),
                                Model.number(rs, 6)));
                    }
                }
            }
            return moves;
        });
    }

    /**
     * One write: the moves done, their photos' rows where their files are now, the update's count, and a
     * {@code photo.left} entry for each photo given up (it goes into the result).
     */
    private void record(long updateId, String step, List<Move> finished, List<Move> left) {
        if (finished.isEmpty()) {
            return;
        }
        db.write(c -> {
            try (PreparedStatement done = c.prepareStatement("UPDATE taxonomy_move SET done = 1 WHERE update_id = ?"
                    + " AND step_version = ? AND seq = ?");
                 PreparedStatement image = c.prepareStatement("UPDATE image SET rel_path = ?, file_name = ?,"
                         + " number = coalesce(?, number) WHERE id = ? AND deleted_at IS NULL")) {
                for (Move m : finished) {
                    done.setLong(1, updateId);
                    done.setString(2, step);
                    done.setInt(3, m.seq());
                    done.addBatch();
                    image.setString(1, m.newRel());
                    image.setString(2, m.newFileName());
                    Sql.setInteger(image, 3, m.newNumber());
                    image.setLong(4, m.imageId());
                    image.addBatch();
                }
                done.executeBatch();
                image.executeBatch();
            }
            UpdateStore.addDone(c, updateId, finished.size());
            List<Map<String, ?>> leftBehind = new ArrayList<>();
            for (Move m : left) {
                leftBehind.add(ChangeLog.payload("id", m.imageId(), "fileName", name(m), "oldPath", m.oldRel(), "update", updateId));
            }
            ChangeLog.recordAll(c, clock.instant(), Results.LEFT, leftBehind);
            return null;
        });
    }
}
