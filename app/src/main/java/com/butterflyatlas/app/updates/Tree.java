package com.butterflyatlas.app.updates;

import com.butterflyatlas.app.photos.FileNames;
import com.butterflyatlas.app.taxonomy.BundleLoader;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The library's taxa in memory, as an update step sees them (5b contract §3): every row of {@code taxon} with what a
 * step can change. Paths and folders are worked out from the parents, so a step changes a parent or a name and every
 * descendant's {@code ancestor_path} and folder follow.
 */
final class Tree {

    /** One taxon. Mutable: a step works on its own copy. */
    static final class Node {
        final String id;
        String parentId;
        String rank;
        String name;
        String common;
        final String source;
        String familyId;
        Long inatId;
        boolean active;
        Long cover;
        int next;

        Node(String id, String parentId, String rank, String name, String common, String source, String familyId,
             Long inatId, boolean active, Long cover, int next) {
            this.id = id;
            this.parentId = parentId;
            this.rank = rank;
            this.name = name;
            this.common = common;
            this.source = source;
            this.familyId = familyId;
            this.inatId = inatId;
            this.active = active;
            this.cover = cover;
            this.next = next;
        }

        Node copy() {
            return new Node(id, parentId, rank, name, common, source, familyId, inatId, active, cover, next);
        }

        boolean species() {
            return BundleLoader.SPECIES_RANK.equals(rank);
        }

        boolean official() {
            return "official".equals(source);
        }

        /** {@code Danaus_chrysippus_[A5]} for a species (official or custom), the safe name otherwise. */
        String folderName() {
            if (BundleLoader.GROUP_RANK.equals(rank) || BundleLoader.ADDITIONS_RANK.equals(rank)) {
                return FileNames.safe(name);
            }
            return FileNames.safe(species() ? name + " [" + id + "]" : name);
        }

        String sortKey() {
            return name.toLowerCase(Locale.ROOT);
        }
    }

    private final Map<String, Node> nodes = new LinkedHashMap<>();

    private Tree() {
    }

    static Tree load(Connection c) throws SQLException {
        Tree tree = new Tree();
        try (PreparedStatement ps = c.prepareStatement("SELECT id, parent_id, rank, name, common_name, source, family_id,"
                + " inat_id, active, cover_image_id, next_photo_number FROM taxon ORDER BY id");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                long inat = rs.getLong(8);
                Long inatId = rs.wasNull() ? null : inat;
                long cover = rs.getLong(10);
                Long coverId = rs.wasNull() ? null : cover;
                tree.put(new Node(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                        rs.getString(6), rs.getString(7), inatId, rs.getInt(9) == 1, coverId, rs.getInt(11)));
            }
        }
        return tree;
    }

    Tree copy() {
        Tree copy = new Tree();
        nodes.values().forEach(n -> copy.put(n.copy()));
        return copy;
    }

    void put(Node node) {
        nodes.put(node.id, node);
    }

    Node get(String id) {
        return nodes.get(id);
    }

    boolean has(String id) {
        return nodes.containsKey(id);
    }

    List<Node> all() {
        return List.copyOf(nodes.values());
    }

    /** The children of each taxon, read once (for walks over many taxa). */
    Map<String, List<Node>> children() {
        Map<String, List<Node>> children = new HashMap<>();
        for (Node n : nodes.values()) {
            if (n.parentId != null) {
                children.computeIfAbsent(n.parentId, k -> new ArrayList<>()).add(n);
            }
        }
        return children;
    }

    /** The ids from the group root down to {@code id} itself. */
    List<String> lineage(String id) {
        List<String> ids = new ArrayList<>();
        Node n = nodes.get(id);
        while (n != null) {
            ids.addFirst(n.id);
            if (ids.size() > nodes.size()) {
                throw new IllegalStateException("The taxa of the library form a loop at " + id);
            }
            n = n.parentId == null ? null : nodes.get(n.parentId);
        }
        return ids;
    }

    /** {@code /G:butterflies/A1/…/A5/}. */
    String ancestorPath(String id) {
        return "/" + String.join("/", lineage(id)) + "/";
    }

    /** Whether {@code id} is {@code ancestor} or below it. */
    boolean under(String id, String ancestor) {
        return lineage(id).contains(ancestor);
    }

    /**
     * Where a species' photos go, relative to the data folder ({@code images/…/Danaus_chrysippus_[A5]/}); null
     * otherwise.
     */
    String folder(String id) {
        Node n = nodes.get(id);
        if (n == null || !n.species()) {
            return null;
        }
        StringBuilder folder = new StringBuilder("images/");
        List<String> lineage = lineage(id);
        // The first is the group root: it has no folder of its own (as TaxonSnapshot.folder).
        for (String a : lineage.subList(1, lineage.size())) {
            folder.append(nodes.get(a).folderName()).append('/');
        }
        return folder.toString();
    }

    /**
     * The family's scientific name for a taxon at or below family rank (a custom species: the family given), else null.
     */
    String family(String id) {
        Node n = nodes.get(id);
        if (n == null) {
            return null;
        }
        if (n.familyId != null) {
            Node family = nodes.get(n.familyId);
            return family == null ? null : family.name;
        }
        for (String a : lineage(id)) {
            Node ancestor = nodes.get(a);
            if ("family".equals(ancestor.rank)) {
                return ancestor.name;
            }
        }
        return null;
    }
}
