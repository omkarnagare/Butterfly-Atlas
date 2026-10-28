package com.butterflyatlas.app.places;

import com.butterflyatlas.app.db.SqlLists;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The library's places ({@code place}, {@code place_alias}, {@code place_state}): loading the bundled ones, and
 * reading a place as its path of names up to its country. Every method works inside the caller's connection.
 */
public final class PlaceStore {

    /**
     * A place as photos show it: {@code path} runs from the place itself up to its country (1–3 names), an inactive
     * place's name labelled ({@link #label}: "Chittoor (former district)"); {@code ids} are the ids of those places in the
     * same order; {@code former} whether the place itself is inactive (split, retired or merged); {@code replacedBy} the
     * place a merged one is part of now, at the end of a chain of merges (null otherwise); {@code level} the place's own
     * level (country, state or district).
     */
    public record PlaceView(String id, List<String> path, List<String> ids, boolean former, String replacedBy,
                            String level) {

        public PlaceView {
            path = List.copyOf(path);
            ids = List.copyOf(ids);
        }
    }

    private final Clock clock;

    /**
     * A place's name as every view shows it: an inactive one with "(former district)" or "(former country)", and a
     * first-level one with "(former state)" in India or "(former region)" elsewhere; {@code country} is the name of
     * the country the place is in.
     */
    public static String label(String name, String level, boolean active, String country) {
        if (active) {
            return name;
        }
        return name + " (former " + ("state".equals(level) && !isIndia(country) ? "region" : level) + ")";
    }

    /** Whether this is India's name (a country named India, as search ranks India's places first). */
    public static boolean isIndia(String country) {
        return country != null && PlaceSearch.fold(country).equals("india");
    }

    public PlaceStore(Clock clock) {
        this.clock = clock;
    }

    /**
     * Brings the bundle's places into the library: places whose id is missing are inserted (with their other names,
     * {@code ancestor_path}, {@code sort_key} = the lower-cased name, {@code active} 1, {@code source 'bundled'});
     * bundled places already there take the bundle's name, sort key and other names (a rename keeps the id, design C5);
     * and the bundle is recorded in {@code place_state}. User places, parents and photos are never touched (merges and
     * splits are Phase 5). Does nothing when this very bundle (version and SHA-256) is already recorded. Returns how
     * many places went in.
     */
    public int syncBundle(Connection tx, PlaceBundles bundles) throws SQLException {
        return syncBundle(tx, bundles, Map.of());
    }

    /**
     * As {@link #syncBundle(Connection, PlaceBundles)}; {@code kept}: other names earlier place update steps gave places
     * (by id), which a place keeps whatever the bundle says (5c contract §10).
     */
    public int syncBundle(Connection tx, PlaceBundles bundles, Map<String, Set<String>> kept) throws SQLException {
        try (PreparedStatement ps = tx.prepareStatement("SELECT 1 FROM place_state WHERE bundle_version = ? AND bundle_sha256 = ?")) {
            ps.setString(1, bundles.version());
            ps.setString(2, bundles.sha256());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return 0;
                }
            }
        }
        Map<String, String> names = new HashMap<>();
        // The other names each bundled place has now: a place whose set equals the bundle's keeps its rows untouched.
        Map<String, Set<String>> aliases = new HashMap<>();
        try (PreparedStatement ps = tx.prepareStatement("SELECT id, name FROM place WHERE source = 'bundled'");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                names.put(rs.getString(1), rs.getString(2));
            }
        }
        try (PreparedStatement ps = tx.prepareStatement("SELECT a.place_id, a.name FROM place_alias a"
                + " JOIN place p ON p.id = a.place_id WHERE p.source = 'bundled'");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                aliases.computeIfAbsent(rs.getString(1), k -> new HashSet<>()).add(rs.getString(2));
            }
        }
        Map<String, String> paths = new HashMap<>();
        int inserted = 0;
        try (PreparedStatement place = tx.prepareStatement("INSERT OR IGNORE INTO place (id, parent_id, level, name, source,"
                + " ancestor_path, sort_key, active) VALUES (?, ?, ?, ?, 'bundled', ?, ?, 1)");
             PreparedStatement alias = tx.prepareStatement("INSERT OR IGNORE INTO place_alias (place_id, name) VALUES (?, ?)");
             PreparedStatement rename = tx.prepareStatement("UPDATE place SET name = ?, sort_key = ? WHERE id = ? AND source = 'bundled'");
             PreparedStatement dropAliases = tx.prepareStatement("DELETE FROM place_alias WHERE place_id = ?")) {
            for (PlaceDef def : bundles.places()) {
                // Parents come first in the bundle, so their paths are known.
                String path = (def.parentId() == null ? "/" : paths.get(def.parentId())) + def.id() + "/";
                paths.put(def.id(), path);
                place.setString(1, def.id());
                place.setString(2, def.parentId());
                place.setString(3, def.level());
                place.setString(4, def.name());
                place.setString(5, path);
                place.setString(6, PlaceSearch.fold(def.name()));
                if (place.executeUpdate() == 0) {
                    // Already in the library: a bundled place takes this bundle's names; a user place is never touched.
                    if (names.containsKey(def.id()) && !names.get(def.id()).equals(def.name())) {
                        rename.setString(1, def.name());
                        rename.setString(2, PlaceSearch.fold(def.name()));
                        rename.setString(3, def.id());
                        rename.executeUpdate();
                    }
                    if (!names.containsKey(def.id()) || aliases.getOrDefault(def.id(), Set.of()).equals(wanted(def, kept))) {
                        continue;
                    }
                    dropAliases.setString(1, def.id());
                    dropAliases.executeUpdate();
                } else {
                    inserted++;
                }
                for (String name : wanted(def, kept)) {
                    alias.setString(1, def.id());
                    alias.setString(2, name);
                    alias.executeUpdate();
                }
            }
        }
        try (PreparedStatement ps = tx.prepareStatement("INSERT INTO place_state (bundle_version, bundle_sha256, applied_at)"
                + " VALUES (?, ?, ?)")) {
            ps.setString(1, bundles.version());
            ps.setString(2, bundles.sha256());
            ps.setString(3, clock.instant().toString());
            ps.executeUpdate();
        }
        return inserted;
    }

    /** A bundled place's other names: the bundle's and those kept from earlier steps, never its own name. */
    private static Set<String> wanted(PlaceDef def, Map<String, Set<String>> kept) {
        Set<String> wanted = new java.util.LinkedHashSet<>(def.aliases());
        wanted.addAll(kept.getOrDefault(def.id(), Set.of()));
        wanted.remove(def.name());
        return wanted;
    }

    /** The place with this id as its path of names; null if the library has no such place. */
    public PlaceView view(Connection c, String placeId) throws SQLException {
        return views(c, List.of(placeId)).get(placeId);
    }

    /**
     * The places with these ids (ids the library does not have are left out), read with their parent and grandparent in
     * one statement per {@value SqlLists#CHUNK} ids, so a page of photos costs one query whatever its size.
     */
    public static Map<String, PlaceView> views(Connection c, Collection<String> placeIds) throws SQLException {
        List<String> ids = new ArrayList<>(new LinkedHashSet<>(placeIds));
        Map<String, PlaceView> views = new HashMap<>();
        for (List<String> chunk : SqlLists.chunks(ids)) {
            try (PreparedStatement ps = c.prepareStatement("SELECT p.id, p.replaced_by, p.name, p.level, p.active, p2.id,"
                    + " p2.name, p2.level, p2.active, p3.id, p3.name, p3.level, p3.active FROM place p"
                    + " LEFT JOIN place p2 ON p2.id = p.parent_id LEFT JOIN place p3 ON p3.id = p2.parent_id"
                    + " WHERE p.id IN (" + SqlLists.marks(chunk.size()) + ")")) {
                SqlLists.bind(ps, 1, chunk);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        List<String> path = new ArrayList<>(3);
                        List<String> pathIds = new ArrayList<>(3);
                        // id, name, level, active of the place (from column 1, its name at 3), p2 (from 6), p3 (from 10)
                        String country = rs.getString(3);
                        for (int at : new int[] {7, 11}) {
                            country = rs.getString(at) == null ? country : rs.getString(at);
                        }
                        for (int at : new int[] {3, 7, 11}) {
                            if (rs.getString(at) == null) {
                                break;
                            }
                            pathIds.add(rs.getString(at == 3 ? 1 : at - 1));
                            path.add(label(rs.getString(at), rs.getString(at + 1), rs.getInt(at + 2) == 1, country));
                        }
                        views.put(rs.getString(1), new PlaceView(rs.getString(1), path, pathIds, rs.getInt(5) == 0,
                                rs.getString(2), rs.getString(4)));
                    }
                }
            }
        }
        for (Map.Entry<String, PlaceView> entry : views.entrySet()) {
            PlaceView view = entry.getValue();
            if (view.replacedBy() != null) {
                entry.setValue(new PlaceView(view.id(), view.path(), view.ids(), view.former(), survivor(c, view.replacedBy()),
                        view.level()));
            }
        }
        return views;
    }

    /** The place {@code id} stands for now: itself, or the end of the chain of merges it was taken into. */
    public static String survivor(Connection c, String id) throws SQLException {
        String at = id;
        try (PreparedStatement ps = c.prepareStatement("SELECT replaced_by FROM place WHERE id = ?")) {
            for (int hops = 0; hops < 10; hops++) { // bounded: a broken chain must not loop
                ps.setString(1, at);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next() || rs.getString(1) == null) {
                        return at;
                    }
                    at = rs.getString(1);
                }
            }
        }
        return at;
    }
}
