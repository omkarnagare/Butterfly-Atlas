package com.butterflyatlas.app.filters;

import com.butterflyatlas.app.db.SqlLists;
import com.butterflyatlas.app.filters.FilterQuery.PlaceCount;
import com.butterflyatlas.app.filters.FilterQuery.PlaceRow;
import com.butterflyatlas.app.filters.FilterQuery.UnknownCount;
import com.butterflyatlas.app.places.PlaceRules;
import com.butterflyatlas.app.places.PlaceSearch;
import com.butterflyatlas.app.places.PlaceStore;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The filter's counts per place ({@link FilterQuery#places}, design D7), rolled up in SQL on {@code place.ancestor_path},
 * and the species list's places at a depth ({@link #atDepth}), on the caller's snapshot.
 */
final class FilterPlaces {

    /** The place levels from the top, as the species list's place columns. */
    private static final List<String> LEVELS = List.of("country", "state", "district");
    private static final Comparator<String> EXACT = Comparator.nullsFirst(Comparator.naturalOrder());

    /**
     * Matching photos of one species at one place of the species list (4b contract §4): the names of its country, state
     * and district down to the depth (null where the photo's place has no such level there, all null for Unknown
     * place), the photos and the ids of the tags they carry.
     */
    record AtDepth(String country, String state, String district, int photos, Set<Long> tags) {
    }

    private FilterPlaces() {
    }

    /**
     * Matching photos by species and place at {@code depth} (0: one entry per species, places ignored; 1 country, 2
     * state, 3 district): a photo placed above the depth fills only the levels it has. Per species sorted by country,
     * state and district name (an empty level first), Unknown place last. Two grouped statements (photos, tags) by
     * species and place; the place tree is read whole (a few thousand rows) to name each place's levels.
     */
    static Map<String, List<AtDepth>> atDepth(Connection c, PhotoMatch match, int depth) throws SQLException {
        Map<String, Map<String, Integer>> photos = new HashMap<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT i.taxon_id, i.place_id, COUNT(*) FROM " + match.from()
                + " WHERE " + match.where() + " GROUP BY i.taxon_id, i.place_id")) {
            match.bind(ps, 1);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    photos.computeIfAbsent(rs.getString(1), k -> new HashMap<>()).put(rs.getString(2), rs.getInt(3));
                }
            }
        }
        Map<String, Map<String, Set<Long>>> tags = new HashMap<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT DISTINCT i.taxon_id, i.place_id, xt.tag_id FROM " + match.from()
                + " JOIN image_tag xt ON xt.image_id = i.id WHERE " + match.where())) {
            match.bind(ps, 1);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    tags.computeIfAbsent(rs.getString(1), k -> new HashMap<>())
                            .computeIfAbsent(rs.getString(2), k -> new HashSet<>()).add(rs.getLong(3));
                }
            }
        }
        Map<String, String[]> tree = depth == 0 ? Map.of() : tree(c);
        Map<String, List<AtDepth>> bySpecies = new HashMap<>();
        for (Map.Entry<String, Map<String, Integer>> species : photos.entrySet()) {
            Map<List<String>, int[]> counts = new HashMap<>();
            Map<List<String>, Set<Long>> tagsAt = new HashMap<>();
            Map<String, Set<Long>> tagsOf = tags.getOrDefault(species.getKey(), Collections.emptyMap()); // null keys allowed
            for (Map.Entry<String, Integer> place : species.getValue().entrySet()) {
                List<String> key = levels(tree, place.getKey(), depth);
                counts.computeIfAbsent(key, k -> new int[1])[0] += place.getValue();
                tagsAt.computeIfAbsent(key, k -> new HashSet<>()).addAll(tagsOf.getOrDefault(place.getKey(), Set.of()));
            }
            List<AtDepth> rows = new ArrayList<>();
            for (Map.Entry<List<String>, int[]> row : counts.entrySet()) {
                List<String> names = row.getKey();
                rows.add(new AtDepth(names.get(0), names.get(1), names.get(2), row.getValue()[0], tagsAt.get(names)));
            }
            rows.sort(Comparator.comparing((AtDepth a) -> a.country() == null && a.state() == null && a.district() == null
                            && depth > 0)
                    .thenComparing(a -> sortable(a.country())).thenComparing(a -> sortable(a.state()))
                    .thenComparing(a -> sortable(a.district()))
                    // names equal without case: by their exact spelling, so the rows never swap between runs
                    .thenComparing(AtDepth::country, EXACT).thenComparing(AtDepth::state, EXACT)
                    .thenComparing(AtDepth::district, EXACT));
            bySpecies.put(species.getKey(), rows);
        }
        return bySpecies;
    }

    /** Every place: id → its ancestor path's names by level (country, state, district), a former place's labelled. */
    private static Map<String, String[]> tree(Connection c) throws SQLException {
        Map<String, String[]> rows = new HashMap<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT id, level, name, ancestor_path, active FROM place");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                rows.put(rs.getString(1), new String[] {rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)});
            }
        }
        Map<String, String[]> tree = new HashMap<>();
        for (Map.Entry<String, String[]> place : rows.entrySet()) {
            String[] names = new String[LEVELS.size()];
            String[] ids = place.getValue()[2].split("/");
            String[] root = ids.length > 1 ? rows.get(ids[1]) : null;
            for (String id : ids) {
                String[] row = id.isEmpty() ? null : rows.get(id);
                int level = row == null ? -1 : LEVELS.indexOf(row[0]);
                if (level >= 0) {
                    // A former place's name labelled, as every view shows it ("Chittoor (former district)").
                    names[level] = PlaceStore.label(row[1], row[0], row[3].equals("1"), root == null ? null : root[1]);
                }
            }
            tree.put(place.getKey(), names);
        }
        return tree;
    }

    /** The names of a place's levels down to {@code depth} (all null for Unknown place: no place, or one not in the tree). */
    private static List<String> levels(Map<String, String[]> tree, String placeId, int depth) {
        String[] names = placeId == null ? null : tree.get(placeId);
        List<String> key = new ArrayList<>(Arrays.asList(new String[LEVELS.size()]));
        for (int i = 0; names != null && i < Math.min(depth, LEVELS.size()); i++) {
            key.set(i, names[i]);
        }
        return key;
    }

    /** A level's name for sorting: an empty level first, then by name without case. */
    private static String sortable(String name) {
        return name == null ? "" : name.toLowerCase(Locale.ROOT);
    }

    /** The rows of {@link FilterQuery#places} for a filter resolved on {@code c}. */
    static List<PlaceRow> places(Connection c, Resolved r) throws SQLException {
        boolean missingStages = r.used().missingStages();
        if (!missingStages && Filter.NOT.equals(r.used().status())) {
            return List.of();
        }
        // Missing life stages counts only the photos of species missing a stage; otherwise matching photos are
        // exactly those of the species in the result.
        List<List<String>> chunks = new ArrayList<>();
        if (missingStages) {
            List<String> species = FilterQuery.result(c, r).hits().stream().filter(h -> h.photos() > 0)
                    .map(h -> h.taxon().id()).toList();
            if (species.isEmpty()) {
                return List.of();
            }
            chunks.addAll(SqlLists.chunks(species));
        } else {
            chunks.add(List.of());
        }
        PhotoMatch match = PhotoMatch.of(r, !missingStages);
        Map<String, int[]> counts = new LinkedHashMap<>();
        boolean several = r.used().places().size() > 1;
        for (List<String> chunk : chunks) {
            String only = chunk.isEmpty() ? "" : " AND i.taxon_id IN (" + SqlLists.marks(chunk.size()) + ")";
            if (several) {
                for (Resolved.Place place : r.places()) {
                    count(c, counts, place.id(), "SELECT COUNT(*), COUNT(DISTINCT i.taxon_id) FROM " + match.from()
                            + " JOIN place p ON p.id = i.place_id WHERE " + match.where() + only
                            + " AND p.ancestor_path >= ? AND p.ancestor_path < ?", match, chunk,
                            List.of(place.path(), PhotoMatch.upper(place.path())));
                }
                if (r.unknown()) {
                    count(c, counts, Filter.UNKNOWN, "SELECT COUNT(*), COUNT(DISTINCT i.taxon_id) FROM " + match.from()
                            + " WHERE " + match.where() + only + " AND " + PhotoMatch.UNKNOWN_PLACE, match, chunk, List.of());
                }
                continue;
            }
            // The row of each photo: its country (no place chosen), or the chosen place's child on its path (the
            // place itself when the photo is placed there); a photo with no place, or one the library lacks, has
            // none (Unknown place, as PhotoMatch.UNKNOWN_PLACE).
            String target;
            List<Object> targetParams = new ArrayList<>();
            if (r.places().isEmpty()) {
                target = "substr(p.ancestor_path, 2, instr(substr(p.ancestor_path, 2), '/') - 1)";
            } else {
                String path = r.places().get(0).path();
                target = "CASE WHEN p.ancestor_path = ? THEN ? ELSE substr(p.ancestor_path, ?,"
                        + " instr(substr(p.ancestor_path, ?), '/') - 1) END";
                targetParams.addAll(List.of(path, r.places().get(0).id(), (long) path.length() + 1,
                        (long) path.length() + 1));
            }
            String sql = "SELECT CASE WHEN p.id IS NULL THEN NULL ELSE " + target + " END AS row_id, COUNT(*),"
                    + " COUNT(DISTINCT i.taxon_id) FROM " + match.from() + " LEFT JOIN place p ON p.id = i.place_id"
                    + " WHERE " + match.where() + only + " GROUP BY row_id";
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                int at = SqlLists.bind(ps, 1, targetParams);
                SqlLists.bind(ps, match.bind(ps, at), chunk);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String id = rs.getString(1) == null ? Filter.UNKNOWN : rs.getString(1);
                        int[] count = counts.computeIfAbsent(id, k -> new int[2]);
                        count[0] += rs.getInt(2);
                        count[1] += rs.getInt(3);
                    }
                }
            }
        }
        counts.values().removeIf(count -> count[0] == 0);
        Map<String, PlaceSearch.Place> shown = PlaceRules.places(c, counts.keySet().stream()
                .filter(id -> !Filter.UNKNOWN.equals(id)).toList());
        List<PlaceCount> known = new ArrayList<>();
        for (PlaceSearch.Place p : shown.values()) {
            int[] count = counts.get(p.id());
            known.add(new PlaceCount(p.id(), p.name(), p.path(), p.level(), p.mine(), count[1], count[0]));
        }
        known.sort(Comparator.comparingInt(PlaceCount::photos).reversed()
                .thenComparing(p -> p.name().toLowerCase(Locale.ROOT)).thenComparing(PlaceCount::id));
        List<PlaceRow> rows = new ArrayList<>(known);
        if (counts.containsKey(Filter.UNKNOWN)) {
            int[] count = counts.get(Filter.UNKNOWN);
            rows.add(new UnknownCount(Filter.UNKNOWN, count[1], count[0]));
        }
        return rows;
    }

    /** Adds one {@code COUNT(*), COUNT(DISTINCT taxon)} answer to the counts of a row. */
    private static void count(Connection c, Map<String, int[]> counts, String rowId, String sql, PhotoMatch match,
                              List<String> chunk, List<Object> after) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            SqlLists.bind(ps, SqlLists.bind(ps, match.bind(ps, 1), chunk), after);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                int[] count = counts.computeIfAbsent(rowId, k -> new int[2]);
                count[0] += rs.getInt(1);
                count[1] += rs.getInt(2);
            }
        }
    }
}
