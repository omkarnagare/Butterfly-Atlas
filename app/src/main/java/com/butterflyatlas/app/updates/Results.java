package com.butterflyatlas.app.updates;

import com.butterflyatlas.app.updates.UpdateViews.LeftBehind;
import com.butterflyatlas.app.updates.UpdateViews.Match;
import com.butterflyatlas.app.updates.UpdateViews.Result;
import com.butterflyatlas.app.updates.UpdateViews.SplitBrief;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What a finished update or merge did (5b contract §7 "The result is kept"): written into its {@code taxonomy.done} (or
 * {@code custom.merge}) entry of {@code change_log} with the versions, photos, kept folders, folders not removed and
 * photos left behind (with its splits and matches), and read back from there: the result of this run, and {@code last}
 * after a restart.
 */
final class Results {

    /** A photo given up after {@code missing} and Retry. */
    static final String LEFT = "photo.left";
    /** A split species merged away with photos still to sort. */
    static final String SPLIT_MERGED = "split.merged";

    /** Entries are read leniently: a field added later (or the {@code update} of an entry) is not a failure. */
    private static final ObjectMapper JSON = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
            false);

    private Results() {
    }

    /**
     * The photos of a bundle update as its summary counts them: moved, merged, split, and the live photos of a species
     * removed from the list or back on it.
     */
    static int photos(Connection c, UpdateStore.Row row) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM (SELECT image_id FROM taxonomy_move"
                + " WHERE update_id = ? UNION SELECT image_id FROM split_photo WHERE version > ? AND version <= ?"
                + " UNION SELECT i.id FROM image i JOIN change_log l ON l.kind IN ('taxon.remove', 'taxon.reactivate')"
                + " AND json_extract(l.payload_json, '$.id') = i.taxon_id AND json_extract(l.payload_json, '$.version') > ?"
                + " AND json_extract(l.payload_json, '$.version') <= ? WHERE i.deleted_at IS NULL)")) {
            ps.setLong(1, row.id());
            ps.setString(2, row.from());
            ps.setString(3, row.to());
            ps.setString(4, row.from());
            ps.setString(5, row.to());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    /**
     * The photo files the update actually moved: its moves done whose path changed, less the photos given up after
     * {@code missing} (their moves count as done, their files were not found).
     */
    static int moved(Connection c, long updateId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM taxonomy_move WHERE update_id = ? AND done = 1"
                + " AND old_rel <> new_rel AND image_id NOT IN (SELECT json_extract(payload_json, '$.id') FROM change_log"
                + " WHERE kind = '" + LEFT + "' AND json_extract(payload_json, '$.update') = ?)")) {
            ps.setLong(1, updateId);
            ps.setLong(2, updateId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    /** The folders kept and not removed of the update's switched steps ({@code taxonomy.step} entries), in order. */
    static Folders.Removed stepFolders(Connection c, long updateId) throws SQLException {
        List<String> kept = new ArrayList<>();
        List<String> notRemoved = new ArrayList<>();
        for (JsonNode step : entries(c, "taxonomy.step", updateId)) {
            step.path("keptFolders").forEach(f -> kept.add(f.asText()));
            step.path("notRemoved").forEach(f -> notRemoved.add(f.asText()));
        }
        return new Folders.Removed(kept, notRemoved);
    }

    /** The photos the update gave up after {@code missing} and Retry. */
    static List<LeftBehind> leftBehind(Connection c, long updateId) throws SQLException {
        List<LeftBehind> left = new ArrayList<>();
        for (JsonNode entry : entries(c, LEFT, updateId)) {
            left.add(new LeftBehind(entry.path("id").asLong(), entry.path("fileName").asText(), entry.path("oldPath").asText()));
        }
        return left;
    }

    /** The split species with photos still to sort that a step merged away. */
    static List<UpdateViews.SplitMerged> splitsMerged(Connection c, long updateId) throws SQLException {
        List<UpdateViews.SplitMerged> merged = new ArrayList<>();
        for (JsonNode entry : entries(c, SPLIT_MERGED, updateId)) {
            merged.add(JSON.convertValue(entry, UpdateViews.SplitMerged.class));
        }
        return merged;
    }

    /** The result's fields for a {@code change_log} payload (after the caller's own fields). */
    static Map<String, Object> payload(Map<String, Object> payload, UpdateStore.Row row, int photos, int moved,
                                       Folders.Removed folders, List<LeftBehind> leftBehind, List<SplitBrief> splits,
                                       List<UpdateViews.SplitMerged> splitsMerged, List<Match> matches) {
        Map<String, Object> all = new LinkedHashMap<>(payload);
        all.put("from", row.from());
        all.put("to", row.to());
        all.put("update", row.id());
        all.put("photos", photos);
        all.put("moved", moved);
        all.put("keptFolders", folders.kept());
        all.put("notRemoved", folders.notRemoved());
        all.put("leftBehind", leftBehind);
        all.put("splits", splits);
        all.put("splitsMerged", splitsMerged);
        all.put("matches", matches);
        all.put("notes", notes(splitsMerged));
        return all;
    }

    /** The plain one-line notes of what an update did (5b contract §7). */
    static List<String> notes(List<UpdateViews.SplitMerged> splitsMerged) {
        List<String> notes = new ArrayList<>();
        for (UpdateViews.SplitMerged merged : splitsMerged) {
            notes.add("Photos still to sort on " + merged.split().name() + " moved to " + merged.into().name()
                    + " with the merge.");
        }
        return notes;
    }

    /** The result of the update {@code updateId}, once it is done. */
    static Optional<Result> of(Connection c, long updateId) throws SQLException {
        return read(c, "SELECT at, payload_json FROM change_log WHERE kind IN ('taxonomy.done', 'custom.merge')"
                + " AND json_extract(payload_json, '$.update') = " + updateId + " ORDER BY id DESC LIMIT 1");
    }

    /** The most recent finished update's or merge's result, if any. */
    static Optional<Result> last(Connection c) throws SQLException {
        return read(c, "SELECT at, payload_json FROM change_log WHERE kind IN ('taxonomy.done', 'custom.merge')"
                + " AND json_extract(payload_json, '$.photos') IS NOT NULL ORDER BY id DESC LIMIT 1");
    }

    private static Optional<Result> read(Connection c, String sql) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) {
                return Optional.empty();
            }
            JsonNode p = JSON.readTree(rs.getString(2));
            List<String> kept = JSON.convertValue(p.path("keptFolders"), new TypeReference<List<String>>() { });
            List<String> notRemoved = JSON.convertValue(p.path("notRemoved"), new TypeReference<List<String>>() { });
            List<LeftBehind> left = JSON.convertValue(p.path("leftBehind"), new TypeReference<List<LeftBehind>>() { });
            List<SplitBrief> splits = JSON.convertValue(p.path("splits"), new TypeReference<List<SplitBrief>>() { });
            List<UpdateViews.SplitMerged> splitsMerged = JSON.convertValue(p.path("splitsMerged"),
                    new TypeReference<List<UpdateViews.SplitMerged>>() { });
            List<Match> matches = JSON.convertValue(p.path("matches"), new TypeReference<List<Match>>() { });
            List<String> notes = JSON.convertValue(p.path("notes"), new TypeReference<List<String>>() { });
            return Optional.of(new Result(p.path("from").asText(), p.path("to").asText(), p.path("photos").asInt(),
                    p.path("moved").asInt(),
                    orEmpty(kept), orEmpty(notRemoved), orEmpty(left), orEmpty(splits), orEmpty(splitsMerged), orEmpty(matches),
                    orEmpty(notes), rs.getString(1)));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("A finished update's entry cannot be read", e);
        }
    }

    private static <T> List<T> orEmpty(List<T> list) {
        return list == null ? List.of() : list;
    }

    private static List<JsonNode> entries(Connection c, String kind, long updateId) throws SQLException {
        List<JsonNode> entries = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT payload_json FROM change_log WHERE kind = ?"
                + " AND json_extract(payload_json, '$.update') = ? ORDER BY id")) {
            ps.setString(1, kind);
            ps.setLong(2, updateId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    entries.add(JSON.readTree(rs.getString(1)));
                }
            }
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("An update's entry cannot be read", e);
        }
        return entries;
    }
}
