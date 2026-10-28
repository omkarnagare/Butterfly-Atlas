package com.butterflyatlas.app.imports;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Import items that name a species being deleted (a custom species, 5a contract §3) forget it; a taxonomy update points
 * them to a merged species' target and takes a split species from those not filed yet (5b contract §3).
 */
public final class ImportSpeciesChoices {

    private static final String NAMES = "(taxon_id = ? OR suggested_taxon_id = ? OR library_taxon_id = ?)";

    private ImportSpeciesChoices() {
    }

    /** The open imports with an item assigned to {@code taxonId}. */
    public static List<Long> choosing(Connection c, String taxonId) throws SQLException {
        List<Long> runs = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT DISTINCT i.run_id FROM import_item i JOIN import_run r"
                + " ON r.id = i.run_id WHERE r.state = 'open' AND i.taxon_id = ? ORDER BY i.run_id")) {
            ps.setString(1, taxonId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    runs.add(rs.getLong(1));
                }
            }
        }
        return runs;
    }

    /**
     * Items with {@code taxonId} as their species, suggestion or library match lose it (inside the caller's
     * transaction); each open import they belong to takes a change number, so its grid hears of it (a finished import's
     * items forget it unstamped). Returns those open imports (their revisions go up).
     */
    public static List<Long> deleted(Connection c, String taxonId, Instant now) throws SQLException {
        List<Long> runs = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT DISTINCT i.run_id FROM import_item i JOIN import_run r"
                + " ON r.id = i.run_id WHERE r.state = 'open' AND (i.taxon_id = ? OR i.suggested_taxon_id = ?"
                + " OR i.library_taxon_id = ?) ORDER BY i.run_id")) {
            bind(ps, 1, taxonId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    runs.add(rs.getLong(1));
                }
            }
        }
        for (long runId : runs) {
            long seq = ImportStore.stamp(c, runId, now);
            try (PreparedStatement ps = c.prepareStatement("UPDATE import_item SET seq = ? WHERE run_id = ? AND " + NAMES)) {
                ps.setLong(1, seq);
                ps.setLong(2, runId);
                bind(ps, 3, taxonId);
                ps.executeUpdate();
            }
        }
        for (String column : new String[] {"taxon_id", "suggested_taxon_id", "library_taxon_id"}) {
            try (PreparedStatement ps = c.prepareStatement("UPDATE import_item SET " + column + " = NULL WHERE " + column + " = ?")) {
                ps.setString(1, taxonId);
                ps.executeUpdate();
            }
        }
        return runs;
    }

    /**
     * A taxonomy update's switch-over (inside its transaction): items whose species, suggestion or library match is a
     * merged species ({@code merged}: source → target) point to the target; items not filed yet whose species or
     * suggestion is a split species ({@code split}: id → name, one that keeps its id too: its photos are to be sorted)
     * lose it (their library match stays: the photo is still on it). Each open import they belong to is stamped, and its
     * {@code update_note} says so (added to a note already there; never in {@code notice}, which is filing's).
     */
    public static void remapped(Connection c, Map<String, String> merged, Map<String, String> split, Instant now)
            throws SQLException {
        for (Map.Entry<String, String> merge : merged.entrySet()) {
            String source = merge.getKey();
            for (long runId : openRuns(c, NAMES, source, 3)) {
                long seq = ImportStore.stamp(c, runId, now);
                try (PreparedStatement ps = c.prepareStatement("UPDATE import_item SET seq = ? WHERE run_id = ? AND " + NAMES)) {
                    ps.setLong(1, seq);
                    ps.setLong(2, runId);
                    bind(ps, 3, source);
                    ps.executeUpdate();
                }
            }
            for (String column : new String[] {"taxon_id", "suggested_taxon_id", "library_taxon_id"}) {
                try (PreparedStatement ps = c.prepareStatement("UPDATE import_item SET " + column + " = ? WHERE " + column + " = ?")) {
                    ps.setString(1, merge.getValue());
                    ps.setString(2, source);
                    ps.executeUpdate();
                }
            }
        }
        String splitNames = "(taxon_id = ? OR suggested_taxon_id = ?) AND outcome IS NULL";
        // run → species name → {assigned, suggested and not assigned}: an item assigned elsewhere keeps its species
        Map<Long, Map<String, int[]>> cleared = new TreeMap<>();
        for (Map.Entry<String, String> species : split.entrySet()) {
            String id = species.getKey();
            for (long runId : openRuns(c, splitNames, id, 2)) {
                try (PreparedStatement ps = c.prepareStatement("SELECT sum(taxon_id IS ?), sum(taxon_id IS NULL"
                        + " AND suggested_taxon_id IS ?) FROM import_item WHERE run_id = ? AND outcome IS NULL")) {
                    ps.setString(1, id);
                    ps.setString(2, id);
                    ps.setLong(3, runId);
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        cleared.computeIfAbsent(runId, k -> new TreeMap<>()).put(species.getValue(),
                                new int[] {rs.getInt(1), rs.getInt(2)});
                    }
                }
                long seq = ImportStore.stamp(c, runId, now);
                try (PreparedStatement ps = c.prepareStatement("UPDATE import_item SET seq = ? WHERE run_id = ? AND " + splitNames)) {
                    ps.setLong(1, seq);
                    ps.setLong(2, runId);
                    ps.setString(3, id);
                    ps.setString(4, id);
                    ps.executeUpdate();
                }
            }
            for (String column : new String[] {"taxon_id", "suggested_taxon_id"}) {
                try (PreparedStatement ps = c.prepareStatement("UPDATE import_item SET " + column + " = NULL WHERE " + column
                        + " = ? AND outcome IS NULL")) {
                    ps.setString(1, id);
                    ps.executeUpdate();
                }
            }
        }
        for (Map.Entry<Long, Map<String, int[]>> run : cleared.entrySet()) {
            String note = splitNote(run.getValue());
            if (note == null) {
                continue;
            }
            try (PreparedStatement ps = c.prepareStatement("UPDATE import_run SET update_note = CASE WHEN update_note IS NULL"
                    + " THEN ? ELSE update_note || ' ' || ? END WHERE id = ?")) {
                ps.setString(1, note);
                ps.setString(2, note);
                ps.setLong(3, run.getKey());
                ps.executeUpdate();
            }
        }
    }

    /**
     * The import's note of a split (5b contract §7): "3 photos were assigned to Danaus chrysippus, which was split.
     * Choose their species again." (or "suggested as", or both); with several split species, one sentence for them all;
     * null when no item lost its species or suggestion.
     */
    static String splitNote(Map<String, int[]> bySpecies) {
        int assigned = bySpecies.values().stream().mapToInt(n -> n[0]).sum();
        int suggested = bySpecies.values().stream().mapToInt(n -> n[1]).sum();
        if (assigned + suggested == 0) {
            return null;
        }
        if (bySpecies.values().stream().filter(n -> n[0] + n[1] > 0).count() > 1) {
            return photos(assigned + suggested) + " assigned to or suggested as species that were split. Choose their"
                    + " species again.";
        }
        String name = bySpecies.entrySet().stream().filter(e -> e.getValue()[0] + e.getValue()[1] > 0).findFirst().orElseThrow()
                .getKey();
        if (suggested == 0) {
            return photos(assigned) + " assigned to " + name + ", which was split. Choose their species again.";
        }
        if (assigned == 0) {
            return photos(suggested) + " suggested as " + name + ", which was split. Choose their species.";
        }
        return photos(assigned) + " assigned to " + name + " and " + suggested + " suggested as it, which was split. Choose"
                + " their species again.";
    }

    /** "1 photo was", "3 photos were". */
    private static String photos(int count) {
        return count == 1 ? "1 photo was" : count + " photos were";
    }

    /** The open imports with an item matching {@code where} (its {@code count} parameters all {@code taxonId}). */
    private static List<Long> openRuns(Connection c, String where, String taxonId, int count) throws SQLException {
        List<Long> runs = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT DISTINCT run_id FROM import_item WHERE " + where
                + " AND run_id IN (SELECT id FROM import_run WHERE state = 'open') ORDER BY run_id")) {
            for (int i = 1; i <= count; i++) {
                ps.setString(i, taxonId);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    runs.add(rs.getLong(1));
                }
            }
        }
        return runs;
    }

    private static void bind(PreparedStatement ps, int at, String taxonId) throws SQLException {
        for (int i = 0; i < 3; i++) {
            ps.setString(at + i, taxonId);
        }
    }
}
