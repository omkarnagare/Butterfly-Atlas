package com.butterflyatlas.app.updates;

import com.butterflyatlas.app.taxonomy.bundle.Bundle;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The plan of one bundle step (5b contract §3 stage 1), inside the caller's transaction, no file touched: the new tree
 * worked out ({@link Step}), and a {@code taxonomy_move} row for every live photo whose path changes. A target already
 * on disk (a file not of the library's in the way) is a conflict: nothing is written, and the update pauses with
 * {@code target_exists} before anything moves (two of the update's own targets never clash: the later is given a free
 * name).
 */
final class UpdatePlanner {

    /** A step planned: its moves (written), or the conflict that stops it (nothing written). */
    record Plan(List<Step.Moved> moves, Conflict conflict) {
    }

    /** A target that is taken: the file name and the folder it is in. */
    record Conflict(String fileName, String folder) {

        String message() {
            return "A file named " + fileName + " is already in " + folder + ". Move it out of the way, then choose Retry.";
        }
    }

    private UpdatePlanner() {
    }

    /**
     * Plans {@code bundle} over the library as it is: its moves are written as {@code taxonomy_move} rows of the update
     * from {@code firstSeq} on, and existing merge targets' next photo numbers move past the numbers given (a target
     * new in the bundle gets its own at the switch-over, from its photos' numbers).
     */
    static Plan plan(Connection c, Path data, long updateId, Bundle bundle, int firstSeq) throws SQLException {
        Model model = Model.load(c);
        Step step = Step.of(model.tree(), bundle);
        List<Step.Moved> moves = step.moves(model.live(), rel -> Files.exists(resolve(data, rel)));
        Conflict conflict = conflict(data, moves);
        if (conflict != null) {
            return new Plan(List.of(), conflict);
        }
        Map<String, Integer> next = new HashMap<>();
        for (Tree.Node n : step.after.all()) {
            if (!step.inserted.contains(n.id) && step.before.has(n.id) && n.next > step.before.get(n.id).next) {
                next.put(n.id, n.next); // a merge target, or a species whose photo was given a free name
            }
        }
        write(c, updateId, bundle.version(), firstSeq, moves, next);
        return new Plan(moves, null);
    }

    /**
     * Writes a planned step: its moves as {@code taxonomy_move} rows from {@code firstSeq} on, and the species' next
     * photo numbers moved past the numbers given ({@code next}: taxon → its next number).
     */
    static void write(Connection c, long updateId, String step, int firstSeq, List<Step.Moved> moves,
                      Map<String, Integer> next) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO taxonomy_move (update_id, step_version, seq, image_id,"
                + " old_rel, new_rel, new_file_name, new_number) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
            int seq = firstSeq;
            for (Step.Moved m : moves) {
                ps.setLong(1, updateId);
                ps.setString(2, step);
                ps.setInt(3, seq++);
                ps.setLong(4, m.imageId());
                ps.setString(5, m.oldRel());
                ps.setString(6, m.newRel());
                ps.setString(7, m.newFileName());
                Sql.setInteger(ps, 8, m.newNumber());
                ps.addBatch();
            }
            ps.executeBatch();
        }
        try (PreparedStatement ps = c.prepareStatement("UPDATE taxon SET next_photo_number = max(next_photo_number, ?)"
                + " WHERE id = ?")) {
            for (Map.Entry<String, Integer> entry : next.entrySet()) {
                ps.setInt(1, entry.getValue());
                ps.setString(2, entry.getKey());
                ps.executeUpdate();
            }
        }
    }

    /** The first planned target that is taken: by an earlier one (without case), or on disk by another file. */
    static Conflict conflict(Path data, List<Step.Moved> moves) {
        Map<String, Step.Moved> targets = new HashMap<>();
        for (Step.Moved m : moves) {
            if (targets.putIfAbsent(Step.fold(m.newRel()), m) != null) {
                return conflictAt(m.newRel());
            }
            Path target = resolve(data, m.newRel());
            if (Files.exists(target) && !sameFile(resolve(data, m.oldRel()), target)) {
                return conflictAt(m.newRel());
            }
        }
        return null;
    }

    private static Conflict conflictAt(String relPath) {
        String[] parts = relPath.split("/");
        return new Conflict(parts[parts.length - 1], parts.length > 1 ? parts[parts.length - 2] : "");
    }

    /** Whether two paths are one file (a change of case only, on a file system that ignores case). */
    static boolean sameFile(Path a, Path b) {
        try {
            return Files.exists(a) && Files.isSameFile(a, b);
        } catch (IOException e) {
            return false;
        }
    }

    /** A {@code /}-separated path relative to the data folder. */
    static Path resolve(Path data, String relPath) {
        Path path = data;
        for (String part : relPath.split("/")) {
            if (!part.isEmpty()) {
                path = path.resolve(part);
            }
        }
        return path;
    }
}
