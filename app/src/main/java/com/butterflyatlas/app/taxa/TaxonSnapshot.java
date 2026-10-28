package com.butterflyatlas.app.taxa;

import com.butterflyatlas.app.taxa.TaxonViews.Count;
import com.butterflyatlas.app.taxa.TaxonViews.RankCount;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Every taxon with its species counts, read in one query. At ≈1,600 taxa it is cheap to load
 * per request, and it keeps every count rule (docs/PLAN.md §11, design D1) in one place.
 */
public final class TaxonSnapshot {

    /** The rank of the group's Custom additions ({@code U:<group>}). */
    public static final String ADDITIONS = "additions";

    /** Ranks below the group, top-down. */
    public static final List<String> RANKS = List.of("family", "subfamily", "tribe", "genus", "species");

    /**
     * {@code photos} counts the taxon's own live photos (only species have any); {@code source} is official, manual or
     * system; {@code familyId} is set on custom (manual) species only; {@code toSort}: of a split species, the live photos
     * still to sort into the species it was split into (5b contract §5).
     */
    public record Row(String id, String parentId, String rank, String name, String common, boolean active,
                      String path, String folder, int photos, String source, String familyId, int toSort) {

        public boolean photographed() {
            return photos > 0;
        }

        boolean isSpecies() {
            return "species".equals(rank);
        }

        List<String> pathIds() {
            return Arrays.stream(path.split("/")).filter(s -> !s.isEmpty()).toList();
        }
    }

    // This count is the only "has a photo" test: live photos, not those in Recently deleted. The
    // partial index image_live_species (V11) answers it.
    private static final String QUERY = "SELECT t.id, t.parent_id, t.rank, t.name, t.common_name, t.active,"
            + " t.ancestor_path, t.folder_name,"
            + " (SELECT COUNT(*) FROM image i WHERE i.taxon_id = t.id AND i.deleted_at IS NULL), t.source, t.family_id"
            + " FROM taxon t ORDER BY (t.rank = 'additions'), t.sort_key, t.id";
    // Split species' photos still to sort, in one pass over split_photo (usually empty).
    private static final String TO_SORT = "SELECT s.taxon_id, COUNT(*) FROM split_photo s JOIN image i ON i.id = s.image_id"
            + " WHERE i.taxon_id = s.taxon_id AND i.deleted_at IS NULL GROUP BY s.taxon_id";

    private final Map<String, Row> rows = new LinkedHashMap<>();
    private final Map<String, List<Row>> children = new HashMap<>();
    private final Map<String, int[]> counts = new HashMap<>();
    private final Map<String, Integer> photoCounts = new HashMap<>();

    private TaxonSnapshot() {
    }

    public static TaxonSnapshot load(Connection c) throws SQLException {
        TaxonSnapshot snapshot = new TaxonSnapshot();
        Map<String, Integer> toSort = new HashMap<>();
        try (PreparedStatement ps = c.prepareStatement(TO_SORT); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                toSort.put(rs.getString(1), rs.getInt(2));
            }
        }
        try (PreparedStatement ps = c.prepareStatement(QUERY); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                Row row = new Row(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                        rs.getInt(6) == 1, rs.getString(7), rs.getString(8), rs.getInt(9), rs.getString(10),
                        rs.getString(11), toSort.getOrDefault(rs.getString(1), 0));
                snapshot.rows.put(row.id(), row);
                if (row.parentId() != null) {
                    snapshot.children.computeIfAbsent(row.parentId(), k -> new ArrayList<>()).add(row);
                }
            }
        }
        snapshot.rollUp();
        return snapshot;
    }

    private void rollUp() {
        for (Row row : rows.values()) {
            if (row.photos() > 0) {
                for (String id : row.pathIds()) {
                    photoCounts.merge(id, row.photos(), Integer::sum);
                }
            }
            if (row.isSpecies() && (row.active() || row.photographed())) {
                for (String id : row.pathIds()) {
                    int[] count = counts.computeIfAbsent(id, k -> new int[2]);
                    count[1]++;
                    if (row.photographed()) {
                        count[0]++;
                    }
                }
            }
        }
    }

    public Optional<Row> find(String id) {
        return Optional.ofNullable(rows.get(id));
    }

    /**
     * The group root and Custom additions are always shown (5a contract §2: the Custom additions card is there even
     * with no species); a species while active or photographed; any other taxon while a species below it is shown.
     */
    public boolean visible(Row row) {
        if (row.parentId() == null || ADDITIONS.equals(row.rank())) {
            return true;
        }
        if (row.isSpecies()) {
            return row.active() || row.photographed();
        }
        return count(row.id()).total() > 0;
    }

    public Count count(String id) {
        int[] count = counts.get(id);
        return count == null ? new Count(0, 0) : new Count(count[0], count[1]);
    }

    /** Live photos of the taxon and everything below it. */
    public int photoCount(String id) {
        return photoCounts.getOrDefault(id, 0);
    }

    /** Every visible taxon of one rank, in name order. */
    public List<Row> visibleOfRank(String rank) {
        return rows.values().stream().filter(r -> r.rank().equals(rank)).filter(this::visible)
                .sorted(Comparator.comparing(r -> r.name().toLowerCase(Locale.ROOT))).toList();
    }

    public List<Row> visibleChildren(String id) {
        return children.getOrDefault(id, List.of()).stream().filter(this::visible).toList();
    }

    /** Ancestors from the group root down to the parent. */
    public List<Row> ancestors(Row row) {
        List<String> ids = row.pathIds();
        return ids.subList(0, ids.size() - 1).stream().map(rows::get).toList();
    }

    /**
     * The family's scientific name for a taxon at or below family rank (a custom species: the family it was given),
     * else null.
     */
    public String family(Row row) {
        if ("family".equals(row.rank())) {
            return row.name();
        }
        if (row.familyId() != null) {
            Row family = rows.get(row.familyId());
            return family == null ? null : family.name();
        }
        return ancestors(row).stream().filter(a -> "family".equals(a.rank())).map(Row::name).findFirst().orElse(null);
    }

    /** For each rank below {@code row}: visible taxa of that rank under it, and how many are photographed. */
    public List<RankCount> ranksBelow(Row row) {
        Map<String, int[]> byRank = new LinkedHashMap<>();
        for (String rank : RANKS) {
            byRank.put(rank, new int[2]);
        }
        for (Row other : rows.values()) {
            if (other != row && other.path().startsWith(row.path()) && byRank.containsKey(other.rank()) && visible(other)) {
                int[] tally = byRank.get(other.rank());
                tally[1]++;
                if (count(other.id()).got() > 0) {
                    tally[0]++;
                }
            }
        }
        return byRank.entrySet().stream()
                .filter(e -> e.getValue()[1] > 0)
                .map(e -> new RankCount(e.getKey(), e.getValue()[0], e.getValue()[1]))
                .toList();
    }

    /** Where a species' photos go, relative to the data folder (docs/PLAN.md §7.1); null for other ranks. */
    public String folder(Row row) {
        if (!row.isSpecies()) {
            return null;
        }
        StringBuilder folder = new StringBuilder("images/");
        List<Row> ancestors = ancestors(row);
        // The first ancestor is always the group root (ancestor_path starts at the group); it has no folder of its own.
        for (Row a : ancestors.subList(1, ancestors.size())) {
            folder.append(a.folder()).append('/');
        }
        return folder.append(row.folder()).append('/').toString();
    }

    /** 0 for the group root, then one per rank down to species. */
    public static int depth(String rank) {
        if ("group".equals(rank)) {
            return 0;
        }
        if (ADDITIONS.equals(rank)) {
            return 1;
        }
        int index = RANKS.indexOf(rank);
        return index < 0 ? RANKS.size() + 1 : index + 1;
    }
}
