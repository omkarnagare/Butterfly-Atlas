package com.butterflyatlas.app.filters;

import com.butterflyatlas.app.db.ChangeLog;
import com.butterflyatlas.app.db.SqlLists;
import com.butterflyatlas.app.filters.Taxa.Taxon;
import com.butterflyatlas.app.places.PlaceRules;
import com.butterflyatlas.app.places.PlaceSearch;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A filter checked against the library (4a contract §1): unknown or retired taxa dropped, the chosen taxa, tags and
 * places as rows, and the species universe in taxonomic order. A chosen place merged by a place update is replaced by
 * the place it became part of; a former (split or retired) place stays chosen and filters; a place the library does not
 * have is left out; the merged and the missing ones are said in {@code notes} (5c contract §6 "Filter").
 *
 * @param used     the filter as used (its {@link Filter#query()} is the normalised query string)
 * @param taxa     the chosen taxa that count, in the order given
 * @param tags     the chosen tags that exist, in the order given
 * @param places   the chosen places the library has (a merged one as its survivor), in the order given, each once
 *                 (without {@code unknown})
 * @param echo     {@code places} as the UI shows them, with {@code unknown} where it was given
 * @param unknown  whether "Unknown place" is chosen
 * @param notes    the chosen places that were merged (replaced by their survivor) or that the library does not have
 * @param universe the species of the chosen taxa and source that are visible, in taxonomic order
 * @param all      the whole taxon table
 */
record Resolved(Filter used, List<Taxon> taxa, List<Tag> tags, List<Place> places, List<Object> echo, boolean unknown,
                List<FilterQuery.PlaceNote> notes, List<Taxon> universe, Taxa all) {

    /** A tag of the library. */
    record Tag(long id, String key, String label) {
    }

    /** A place of the library and its {@code ancestor_path} ({@code /P1/P20/P414/}). */
    record Place(String id, String path) {
    }

    /** What the UI gets for "Unknown place" in a filter. */
    record UnknownPlace(String id) {
    }

    static Resolved resolve(Connection c, Filter filter) throws SQLException {
        Taxa all = Taxa.load(c);
        List<Taxon> taxa = new ArrayList<>();
        for (String id : filter.taxa()) {
            Taxon t = all.find(id);
            if (t != null && all.usable(t)) {
                taxa.add(t);
            }
        }
        List<Tag> tags = tags(c, filter.tags());
        List<String> placeIds = filter.places().stream().filter(id -> !Filter.UNKNOWN.equals(id)).toList();
        Map<String, Row> rows = rows(c, placeIds);
        Map<String, String> survivor = new HashMap<>();
        Map<String, String[]> mergedUser = mergedUserPlaces(c, placeIds.stream().filter(id -> !rows.containsKey(id)).toList());
        for (String id : placeIds) {
            String at = mergedUser.containsKey(id) ? mergedUser.get(id)[0] : id;
            if (!rows.containsKey(at)) {
                rows.putAll(rows(c, List.of(at)));
            }
            // A merge chain (a place merged, then its survivor merged too) ends at the place still standing.
            for (int hops = 0; rows.containsKey(at) && rows.get(at).replacedBy() != null && hops < 10; hops++) {
                at = rows.get(at).replacedBy();
                if (!rows.containsKey(at)) {
                    rows.putAll(rows(c, List.of(at)));
                }
            }
            if (rows.containsKey(at)) {
                survivor.put(id, at);
            }
        }
        Map<String, PlaceSearch.Place> shown = PlaceRules.places(c, survivor.values());
        List<Place> places = new ArrayList<>();
        List<Object> echo = new ArrayList<>();
        List<String> usedPlaces = new ArrayList<>();
        List<FilterQuery.PlaceNote> notes = new ArrayList<>();
        for (String id : filter.places()) {
            if (Filter.UNKNOWN.equals(id)) {
                if (!usedPlaces.contains(id)) {
                    echo.add(new UnknownPlace(id));
                    usedPlaces.add(id);
                }
                continue;
            }
            String at = survivor.get(id);
            if (at == null) {
                notes.add(new FilterQuery.PlaceNote(id, "gone", null, null));
                continue;
            }
            if (!at.equals(id)) {
                String name = mergedUser.containsKey(id) ? mergedUser.get(id)[1] : rows.get(id).name();
                notes.add(new FilterQuery.PlaceNote(id, "merged", name, shown.get(at)));
            }
            if (!usedPlaces.contains(at)) {
                places.add(new Place(at, rows.get(at).path()));
                echo.add(shown.get(at));
                usedPlaces.add(at);
            }
        }
        List<Taxon> universe = all.visibleSpecies().stream()
                .filter(s -> taxa.isEmpty() || taxa.stream().anyMatch(t -> s.path().startsWith(t.path())))
                .filter(s -> Filter.ALL.equals(filter.source()) || filter.source().equals(s.source()))
                .toList();
        Filter used = filter.withIds(taxa.stream().map(Taxon::id).toList(), tags.stream().map(Tag::key).toList(), usedPlaces);
        return new Resolved(used, List.copyOf(taxa), tags, List.copyOf(places), echo, usedPlaces.contains(Filter.UNKNOWN),
                List.copyOf(notes), universe, all);
    }

    /** Whether photos are narrowed by place at all (some place or "Unknown place" chosen). */
    boolean byPlace() {
        return unknown || !places.isEmpty();
    }

    private static List<Tag> tags(Connection c, List<String> keys) throws SQLException {
        Map<String, Tag> found = new HashMap<>();
        for (List<String> chunk : SqlLists.chunks(keys)) {
            try (PreparedStatement ps = c.prepareStatement("SELECT id, key, label FROM tag WHERE key IN ("
                    + SqlLists.marks(chunk.size()) + ")")) {
                SqlLists.bind(ps, 1, chunk);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        found.put(rs.getString(2), new Tag(rs.getLong(1), rs.getString(2), rs.getString(3)));
                    }
                }
            }
        }
        return keys.stream().filter(found::containsKey).map(found::get).toList();
    }

    /**
     * User places a place update step merged into another place and deleted (5c design P4), from {@code change_log}:
     * id → {the place it went into, its name}.
     */
    private static Map<String, String[]> mergedUserPlaces(Connection c, List<String> ids) throws SQLException {
        Map<String, String[]> merged = new HashMap<>();
        for (String id : ids) {
            try (PreparedStatement ps = c.prepareStatement("SELECT json_extract(payload_json, '$.into'),"
                    + " json_extract(payload_json, '$.name') FROM change_log WHERE kind = ?"
                    + " AND json_extract(payload_json, '$.userId') = ? ORDER BY id DESC LIMIT 1")) {
                ps.setString(1, ChangeLog.USER_PLACE_MERGED);
                ps.setString(2, id);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        merged.put(id, new String[] {rs.getString(1), rs.getString(2)});
                    }
                }
            }
        }
        return merged;
    }

    /** A chosen place's row: its {@code ancestor_path}, bare name and survivor (merged places). */
    private record Row(String path, String name, String replacedBy) {
    }

    /** The rows of the places with these ids the library has, active or not. */
    private static Map<String, Row> rows(Connection c, List<String> ids) throws SQLException {
        Map<String, Row> found = new HashMap<>();
        for (List<String> chunk : SqlLists.chunks(ids)) {
            try (PreparedStatement ps = c.prepareStatement("SELECT id, ancestor_path, name, replaced_by FROM place WHERE id IN ("
                    + SqlLists.marks(chunk.size()) + ")")) {
                SqlLists.bind(ps, 1, chunk);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        found.put(rs.getString(1), new Row(rs.getString(2), rs.getString(3), rs.getString(4)));
                    }
                }
            }
        }
        return found;
    }
}
