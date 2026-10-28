package com.butterflyatlas.app.updates;

import com.butterflyatlas.app.db.ChangeLog;
import com.butterflyatlas.app.imports.ImportSpeciesChoices;
import com.butterflyatlas.app.photos.FileNames;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * A custom species merged into the official species it now matches (5b contract §5), as a {@code match} update of the
 * same engine: the plan moves its live photos into the official species' folder with that species' next free numbers
 * (no file touched); after the moves the switch-over puts its photos (live and in Recently deleted) on the official
 * species with their tags and places, moves its search words (its own name and common name become search words of the
 * official species when they differ from its names), keeps its cover if the official species has none, re-points import
 * items, deletes the custom species (its U id is never given again), logs {@code custom.merge} and marks the match
 * merged.
 */
final class MatchMerge {

    static final String LOGGED = "custom.merge";

    private MatchMerge() {
    }

    /** The plan of the merge, inside the caller's transaction: its moves written from {@code firstSeq} on. */
    static UpdatePlanner.Plan plan(Connection c, Path data, UpdateStore.Row update, int firstSeq) throws SQLException {
        Model model = Model.load(c);
        Tree tree = model.tree();
        Tree.Node official = tree.get(update.officialId());
        if (official == null || !tree.has(update.customId())) {
            return new UpdatePlanner.Plan(List.of(), null); // gone meanwhile: nothing to merge
        }
        String folder = tree.folder(official.id);
        Set<String> taken = new HashSet<>();
        model.live().forEach(p -> taken.add(Step.fold(p.relPath())));
        List<Model.Live> photos = model.live().stream().filter(p -> p.taxonId().equals(update.customId()))
                .sorted(Comparator.comparing((Model.Live p) -> p.number() == null ? Integer.MAX_VALUE : p.number())
                        .thenComparingLong(Model.Live::id)).toList();
        List<Step.Moved> moves = new ArrayList<>();
        int n = Math.max(1, official.next);
        for (Model.Live p : photos) {
            String extension = Step.extension(p.fileName());
            while (true) {
                String name = FileNames.photoName(official.name, n++, extension);
                String rel = folder + name;
                if (!taken.contains(Step.fold(rel)) && !Files.exists(UpdatePlanner.resolve(data, rel))) {
                    moves.add(new Step.Moved(p.id(), official.id, p.relPath(), rel, name, n - 1));
                    taken.add(Step.fold(rel));
                    break;
                }
            }
        }
        moves.sort(Comparator.comparingLong(Step.Moved::imageId));
        UpdatePlanner.Conflict conflict = UpdatePlanner.conflict(data, moves);
        if (conflict != null) {
            return new UpdatePlanner.Plan(List.of(), conflict);
        }
        UpdatePlanner.write(c, update.id(), update.step(), firstSeq, moves, Map.of(official.id, n));
        return new UpdatePlanner.Plan(moves, null);
    }

    /** The switch-over of the merge, inside the caller's transaction ({@code custom.merge} is logged by the caller). */
    static Merged switchOver(Connection c, UpdateStore.Row update, Instant now) throws SQLException {
        String customId = update.customId();
        String officialId = update.officialId();
        Model model = Model.load(c);
        Tree tree = model.tree();
        Tree.Node custom = tree.get(customId);
        Tree.Node official = tree.get(officialId);
        if (custom == null || official == null) {
            return new Merged(customId, 0, List.of()); // gone meanwhile (the update's gate keeps that from happening)
        }
        int highest = model.live().stream().filter(p -> p.taxonId().equals(customId) || p.taxonId().equals(officialId))
                .map(Model.Live::number).filter(Objects::nonNull).mapToInt(Integer::intValue).max().orElse(0);
        int next = Math.max(official.next, highest + 1);
        try (PreparedStatement ps = c.prepareStatement("UPDATE image SET taxon_id = ?, file_name = ?, number = ? WHERE id = ?")) {
            for (Model.Deleted d : model.deleted()) {
                if (d.taxonId().equals(customId)) {
                    ps.setString(1, officialId);
                    ps.setString(2, FileNames.photoName(official.name, next, Step.extension(d.fileName())));
                    ps.setInt(3, next++);
                    ps.setLong(4, d.id());
                    ps.addBatch();
                }
            }
            ps.executeBatch();
        }
        try (PreparedStatement ps = c.prepareStatement("UPDATE image SET taxon_id = ? WHERE taxon_id = ?")) {
            ps.setString(1, officialId);
            ps.setString(2, customId);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = c.prepareStatement("UPDATE taxon SET next_photo_number = max(next_photo_number, ?),"
                + " cover_image_id = coalesce(cover_image_id, ?) WHERE id = ?")) {
            ps.setInt(1, next);
            Sql.setInteger(ps, 2, custom.cover);
            ps.setString(3, officialId);
            ps.executeUpdate();
        }
        SwitchOver.moveWords(c, customId, officialId);
        for (String name : new String[] {custom.name, custom.common}) {
            if (name != null && !name.equalsIgnoreCase(official.name) && (official.common == null
                    || !name.equalsIgnoreCase(official.common))) {
                try (PreparedStatement ps = c.prepareStatement("INSERT OR IGNORE INTO taxon_synonym (taxon_id, name, source,"
                        + " added_at) VALUES (?, ?, 'user', ?)")) {
                    ps.setString(1, officialId);
                    ps.setString(2, name);
                    ps.setString(3, now.toString());
                    ps.executeUpdate();
                }
            }
        }
        SwitchOver.reindexWords(c, officialId);
        ImportSpeciesChoices.remapped(c, Map.of(customId, officialId), Map.of(), now);
        List<Map<String, ?>> moved = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT image_id, old_rel, new_rel FROM taxonomy_move WHERE update_id = ?"
                + " AND done = 1 ORDER BY seq")) {
            ps.setLong(1, update.id());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    moved.add(ChangeLog.payload("id", rs.getLong(1), "taxonId", officialId, "fromPath", rs.getString(2),
                            "toPath", rs.getString(3), "update", update.id()));
                }
            }
        }
        ChangeLog.recordAll(c, now, ChangeLog.MOVE, moved);
        for (String sql : new String[] {"DELETE FROM taxon_synonym WHERE taxon_id = ?", "DELETE FROM taxon_fts WHERE taxon_id = ?",
                "DELETE FROM taxon WHERE id = ?"}) {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, customId);
                ps.executeUpdate();
            }
        }
        CustomMatches.decide(c, customId, officialId, CustomMatches.MERGED, now);
        return new Merged(custom.name, moved.size(), SwitchOver.oldFolders(c, update.id(), update.step()));
    }

    /**
     * A merge switched over: the custom species' name, its photos moved, the folders they left (logged by the caller).
     */
    record Merged(String name, int photos, List<String> oldFolders) {
    }
}
