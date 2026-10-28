package com.butterflyatlas.app.updates;

import com.butterflyatlas.app.db.ChangeLog;
import com.butterflyatlas.app.imports.ImportSpeciesChoices;
import com.butterflyatlas.app.taxonomy.bundle.Bundle;
import com.butterflyatlas.app.taxonomy.bundle.BundleTaxon;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The switch-over of a bundle step (5b contract §3 stage 3), inside the caller's one transaction, once every move is
 * done: the taxa as the new tree has them (new ones added, parents first), merged photos (live and in Recently deleted)
 * on the target, Recently-deleted photos' new restore names, the bundle's synonyms and the search index, user search
 * words moved to a merge's target, the photos still to sort of each split, import items remapped, one
 * {@code change_log} row per change and per photo moved, and a {@code taxonomy_state} row.
 */
final class SwitchOver {

    static final String STEP = "taxonomy.step";
    static final String DONE = "taxonomy.done";

    private SwitchOver() {
    }

    /**
     * Switches the library over to {@code bundle}; returns the step (for its merges and splits) and the folders it
     * left.
     */
    static Switched apply(Connection c, long updateId, Bundle bundle, String sha256, Instant now) throws SQLException {
        String from = PendingUpdate.libraryVersion(c);
        Model model = Model.load(c);
        Step step = Step.of(model.tree(), bundle);
        // The merged photos already carry the target's numbers (their rows followed their files): the target's next number
        // goes past them before Recently deleted's photos are numbered.
        Map<String, Integer> highest = new HashMap<>();
        for (Model.Live p : model.live()) {
            String owner = step.merged.getOrDefault(p.taxonId(), p.taxonId());
            if (p.number() != null) {
                highest.merge(owner, p.number(), Math::max);
            }
        }
        for (String target : new LinkedHashSet<>(step.merged.values())) {
            Tree.Node t = step.after.get(target);
            t.next = Math.max(t.next, highest.getOrDefault(target, 0) + 1);
        }
        List<Step.Renamed> renamed = step.deleted(model.deleted());

        insertNew(c, step, bundle.group().key());
        updateTaxa(c, step);
        for (Map.Entry<String, String> merge : step.merged.entrySet()) {
            splitMerged(c, updateId, step, merge.getKey(), merge.getValue(), now);
            try (PreparedStatement ps = c.prepareStatement("UPDATE image SET taxon_id = ? WHERE taxon_id = ?")) {
                ps.setString(1, merge.getValue());
                ps.setString(2, merge.getKey());
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM split_photo WHERE taxon_id = ?")) {
                ps.setString(1, merge.getKey());
                ps.executeUpdate();
            }
        }
        try (PreparedStatement ps = c.prepareStatement("UPDATE image SET taxon_id = ?, file_name = ?, number = ? WHERE id = ?")) {
            for (Step.Renamed r : renamed) {
                ps.setString(1, r.taxonId());
                ps.setString(2, r.fileName());
                Sql.setInteger(ps, 3, r.number());
                ps.setLong(4, r.imageId());
                ps.addBatch();
            }
            ps.executeBatch();
        }
        for (String source : step.splits.keySet()) {
            try (PreparedStatement ps = c.prepareStatement("INSERT OR REPLACE INTO split_photo (image_id, taxon_id, version)"
                    + " SELECT id, taxon_id, ? FROM image WHERE taxon_id = ? AND deleted_at IS NULL")) {
                ps.setString(1, bundle.version());
                ps.setString(2, source);
                ps.executeUpdate();
            }
        }
        search(c, bundle);
        for (Map.Entry<String, String> merge : step.merged.entrySet()) {
            moveWords(c, merge.getKey(), merge.getValue());
        }
        Map<String, String> split = new LinkedHashMap<>();
        step.splits.keySet().forEach(source -> split.put(source, step.before.get(source).name)); // kept id or not
        ImportSpeciesChoices.remapped(c, step.merged, split, now);
        log(c, updateId, step, from, now);
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO taxonomy_state (bundle_version, bundle_sha256, applied_at) VALUES (?, ?, ?)")) {
            ps.setString(1, bundle.version());
            ps.setString(2, sha256);
            ps.setString(3, now.toString());
            ps.executeUpdate();
        }
        return new Switched(step, from, oldFolders(c, updateId, bundle.version()));
    }

    /** A step switched over: what it did, and the folders its photos left (removed once the write has committed). */
    record Switched(Step step, String from, List<String> oldFolders) {
    }

    private static void insertNew(Connection c, Step step, String groupKey) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO taxon (id, parent_id, rank, name, common_name, group_key,"
                + " source, inat_id, ancestor_path, folder_name, active, sort_key) VALUES (?, ?, ?, ?, ?, ?, 'official', ?, ?, ?, 1, ?)")) {
            for (String id : step.inserted) {
                Tree.Node n = step.after.get(id);
                ps.setString(1, n.id);
                ps.setString(2, n.parentId);
                ps.setString(3, n.rank);
                ps.setString(4, n.name);
                ps.setString(5, n.common);
                ps.setString(6, groupKey);
                Sql.setInteger(ps, 7, n.inatId);
                ps.setString(8, step.after.ancestorPath(id));
                ps.setString(9, n.folderName());
                ps.setString(10, n.sortKey());
                ps.executeUpdate(); // one by one: a parent before its children (foreign key)
            }
        }
    }

    /** Every official and custom taxon as the new tree has it (the group and Custom additions stay as they are). */
    private static void updateTaxa(Connection c, Step step) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("UPDATE taxon SET parent_id = ?, rank = ?, name = ?, common_name = ?,"
                + " sort_key = ?, folder_name = ?, ancestor_path = ?, active = ?, inat_id = ?, family_id = ?, cover_image_id = ?,"
                + " next_photo_number = max(next_photo_number, ?) WHERE id = ?")) {
            for (Tree.Node n : step.after.all()) {
                if ("system".equals(n.source)) {
                    continue;
                }
                ps.setString(1, n.parentId);
                ps.setString(2, n.rank);
                ps.setString(3, n.name);
                ps.setString(4, n.common);
                ps.setString(5, n.sortKey());
                ps.setString(6, n.folderName());
                ps.setString(7, step.after.ancestorPath(n.id));
                ps.setInt(8, n.active ? 1 : 0);
                Sql.setInteger(ps, 9, n.inatId);
                ps.setString(10, n.familyId);
                Sql.setInteger(ps, 11, n.cover);
                ps.setInt(12, n.next);
                ps.setString(13, n.id);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    /**
     * Every official taxon in the bundle takes its bundled synonyms and its search rows ({@code name}, {@code common},
     * {@code synonym}) from the bundle; the user's search words ({@code word}) stay.
     */
    private static void search(Connection c, Bundle bundle) throws SQLException {
        try (PreparedStatement dropSynonyms = c.prepareStatement("DELETE FROM taxon_synonym WHERE taxon_id = ? AND source = 'bundle'");
             PreparedStatement dropIndex = c.prepareStatement("DELETE FROM taxon_fts WHERE taxon_id = ?"
                     + " AND kind IN ('name', 'common', 'synonym')");
             PreparedStatement synonym = c.prepareStatement("INSERT OR IGNORE INTO taxon_synonym (taxon_id, name, source)"
                     + " VALUES (?, ?, 'bundle')");
             PreparedStatement index = c.prepareStatement("INSERT INTO taxon_fts (taxon_id, kind, text) VALUES (?, ?, ?)")) {
            for (BundleTaxon t : bundle.taxa()) {
                dropSynonyms.setString(1, t.id());
                dropSynonyms.executeUpdate();
                dropIndex.setString(1, t.id());
                dropIndex.executeUpdate();
                index(index, t.id(), "name", t.name());
                if (t.common() != null) {
                    index(index, t.id(), "common", t.common());
                }
                for (String name : t.synonyms()) {
                    synonym.setString(1, t.id());
                    synonym.setString(2, name);
                    if (synonym.executeUpdate() > 0) {
                        index(index, t.id(), "synonym", name);
                    }
                }
            }
        }
    }

    private static void index(PreparedStatement ps, String id, String kind, String text) throws SQLException {
        ps.setString(1, id);
        ps.setString(2, kind);
        ps.setString(3, text);
        ps.executeUpdate();
    }

    /** The user's search words of a merged taxon go to its target (a word the target already has is not doubled). */
    static void moveWords(Connection c, String source, String target) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("UPDATE OR IGNORE taxon_synonym SET taxon_id = ? WHERE taxon_id = ?"
                + " AND source = 'user'")) {
            ps.setString(1, target);
            ps.setString(2, source);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = c.prepareStatement("DELETE FROM taxon_synonym WHERE taxon_id = ? AND source = 'user'")) {
            ps.setString(1, source);
            ps.executeUpdate();
        }
        reindexWords(c, source);
        reindexWords(c, target);
    }

    /** The {@code word} search rows of a taxon, from its user synonyms. */
    static void reindexWords(Connection c, String id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("DELETE FROM taxon_fts WHERE taxon_id = ? AND kind = 'word'")) {
            ps.setString(1, id);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO taxon_fts (taxon_id, kind, text) SELECT taxon_id, 'word',"
                + " name FROM taxon_synonym WHERE taxon_id = ? AND source = 'user'")) {
            ps.setString(1, id);
            ps.executeUpdate();
        }
    }

    /** One row per change, one per photo moved in the step, and the step itself. */
    private static void log(Connection c, long updateId, Step step, String from, Instant now) throws SQLException {
        String version = step.bundle.version();
        for (String id : step.inserted) {
            ChangeLog.record(c, now, "taxon.add", ChangeLog.payload("id", id, "from", null, "to", step.after.get(id).name,
                    "version", version));
        }
        for (Step.TaxonChange change : step.changes) {
            switch (change.kind()) {
                case Step.RENAME -> {
                    String id = change.from().getFirst();
                    ChangeLog.record(c, now, "taxon.rename", ChangeLog.payload("id", id, "from", step.before.get(id).name,
                            "to", step.after.get(id).name, "version", version));
                }
                case Step.MOVE -> {
                    String id = change.from().getFirst();
                    ChangeLog.record(c, now, "taxon.move", ChangeLog.payload("id", id, "from", step.before.get(id).parentId,
                            "to", step.after.get(id).parentId, "version", version));
                }
                case Step.MERGE -> ChangeLog.record(c, now, "taxon.merge", ChangeLog.payload("ids", change.from(),
                        "from", change.from().stream().map(id -> step.before.get(id).name).toList(),
                        "to", change.to().getFirst(), "version", version));
                case Step.SPLIT -> ChangeLog.record(c, now, "taxon.split", ChangeLog.payload("id", change.from().getFirst(),
                        "from", step.before.get(change.from().getFirst()).name, "to", change.to(), "version", version,
                        "photos", splitPhotos(c, change.from().getFirst(), version)));
                case Step.REACTIVATE -> ChangeLog.record(c, now, "taxon.reactivate", ChangeLog.payload("id",
                        change.to().getFirst(), "from", null, "to", step.after.get(change.to().getFirst()).name,
                        "version", version));
                case Step.REMOVE -> ChangeLog.record(c, now, "taxon.remove", ChangeLog.payload("id", change.from().getFirst(),
                        "from", step.before.get(change.from().getFirst()).name, "to", null, "version", version));
                default -> {
                    // an add is logged with the taxa inserted above
                }
            }
        }
        List<Map<String, ?>> moved = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT m.image_id, i.taxon_id, m.old_rel, m.new_rel FROM taxonomy_move m"
                + " LEFT JOIN image i ON i.id = m.image_id WHERE m.update_id = ? AND m.step_version = ? AND m.done = 1"
                + " ORDER BY m.seq")) {
            ps.setLong(1, updateId);
            ps.setString(2, version);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    moved.add(ChangeLog.payload("id", rs.getLong(1), "taxonId", rs.getString(2), "fromPath", rs.getString(3),
                            "toPath", rs.getString(4), "update", updateId));
                }
            }
        }
        ChangeLog.recordAll(c, now, ChangeLog.MOVE, moved);
    }

    /**
     * A split species merged away by a later step: its photos still to sort go to the target with the merge (their
     * {@code split_photo} rows go), and a {@code split.merged} entry puts it in the result ("Photos still to sort on X
     * moved to Y with the merge.").
     */
    private static void splitMerged(Connection c, long updateId, Step step, String source, String target, Instant now)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM split_photo s JOIN image i ON i.id = s.image_id"
                + " WHERE s.taxon_id = ? AND i.taxon_id = s.taxon_id AND i.deleted_at IS NULL")) {
            ps.setString(1, source);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                if (rs.getInt(1) > 0) {
                    Tree.Node from = step.before.get(source);
                    Tree.Node to = step.after.get(target);
                    ChangeLog.record(c, now, Results.SPLIT_MERGED, ChangeLog.payload("split", new UpdateViews.Ref(from.id,
                            from.name, from.common), "into", new UpdateViews.Ref(to.id, to.name, to.common), "photos",
                            rs.getInt(1), "update", updateId));
                }
            }
        }
    }

    /** The photos a split left to sort. */
    private static int splitPhotos(Connection c, String id, String version) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM split_photo WHERE taxon_id = ? AND version = ?")) {
            ps.setString(1, id);
            ps.setString(2, version);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    /** The folders the step's photos were in, and theirs up to {@code images/} (not included), deepest first. */
    static List<String> oldFolders(Connection c, long updateId, String step) throws SQLException {
        Set<String> folders = new LinkedHashSet<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT old_rel FROM taxonomy_move WHERE update_id = ?"
                + " AND step_version = ? ORDER BY seq")) {
            ps.setLong(1, updateId);
            ps.setString(2, step);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    folders.addAll(Folders.parents(rs.getString(1)));
                }
            }
        }
        return List.copyOf(folders);
    }
}
