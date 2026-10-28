package com.butterflyatlas.app.updates;

import com.butterflyatlas.app.taxa.TaxonSnapshot;
import com.butterflyatlas.app.updates.UpdateViews.Ref;
import com.butterflyatlas.app.updates.UpdateViews.SplitBrief;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Split species and their photos still to sort (5b contract §5): a live photo is still to sort while it has a
 * {@code split_photo} row and is still on the species it names. The species a split went into, the version and the
 * photos it had then come from its {@code taxon.split} entry in {@code change_log} (the latest, if it was split again).
 */
final class Splits {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** A split as {@code change_log} has it. */
    record Logged(String version, List<String> targets, int photos) {
    }

    private Splits() {
    }

    /** The latest split of {@code id}, if it was split. */
    static Optional<Logged> logged(Connection c, String id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT payload_json FROM change_log WHERE kind = 'taxon.split'"
                + " AND json_extract(payload_json, '$.id') = ? ORDER BY id DESC LIMIT 1")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                JsonNode payload = JSON.readTree(rs.getString(1));
                List<String> targets = new ArrayList<>();
                payload.path("to").forEach(t -> targets.add(t.asText()));
                return Optional.of(new Logged(payload.path("version").asText(), targets, payload.path("photos").asInt()));
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("A taxon.split entry of " + id + " cannot be read", e);
            }
        }
    }

    /** The live photos of {@code id} still to sort, newest first. */
    static List<Long> toSort(Connection c, String id) throws SQLException {
        List<Long> ids = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT s.image_id FROM split_photo s JOIN image i ON i.id = s.image_id"
                + " WHERE s.taxon_id = ? AND i.taxon_id = s.taxon_id AND i.deleted_at IS NULL ORDER BY i.added_at DESC, i.id DESC")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ids.add(rs.getLong(1));
                }
            }
        }
        return ids;
    }

    /** A split species with what is left to sort; empty when {@code id} was never split. */
    static Optional<SplitBrief> brief(Connection c, TaxonSnapshot snapshot, String id) throws SQLException {
        Optional<TaxonSnapshot.Row> row = snapshot.find(id);
        Optional<Logged> logged = logged(c, id);
        if (row.isEmpty() || logged.isEmpty()) {
            return Optional.empty();
        }
        List<Ref> targets = new ArrayList<>();
        for (String target : logged.get().targets()) {
            snapshot.find(target).ifPresent(t -> targets.add(new Ref(t.id(), t.name(), t.common())));
        }
        TaxonSnapshot.Row r = row.get();
        return Optional.of(new SplitBrief(r.id(), r.name(), r.common(), snapshot.family(r), r.toSort(), logged.get().version(),
                targets));
    }

    /** The split species with live photos still to sort, by name. */
    static List<SplitBrief> briefs(Connection c) throws SQLException {
        TaxonSnapshot snapshot = TaxonSnapshot.load(c);
        List<String> ids = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT DISTINCT s.taxon_id FROM split_photo s JOIN image i"
                + " ON i.id = s.image_id WHERE i.taxon_id = s.taxon_id AND i.deleted_at IS NULL");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                ids.add(rs.getString(1));
            }
        }
        List<SplitBrief> list = new ArrayList<>();
        for (String id : ids) {
            brief(c, snapshot, id).ifPresent(list::add);
        }
        list.sort(Comparator.comparing((SplitBrief b) -> b.name().toLowerCase(Locale.ROOT)).thenComparing(SplitBrief::id));
        return list;
    }

    /** How many split species have live photos still to sort. */
    static int count(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(DISTINCT s.taxon_id) FROM split_photo s JOIN image i"
                + " ON i.id = s.image_id WHERE i.taxon_id = s.taxon_id AND i.deleted_at IS NULL");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
