package com.butterflyatlas.app.filters;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The whole taxon table with each species' live photo count, read in two statements (the table is small: ≈1,600 rows),
 * the visibility rule (docs/PLAN.md §11: a species is shown while active or while it has live photos) and the
 * taxonomic order of species (4a contract §2).
 */
final class Taxa {

    /** The ranks above species, top-down. */
    static final List<String> RANKS = List.of("family", "subfamily", "tribe", "genus");

    /**
     * A taxon row; {@code livePhotos} counts its own live photos (only species have any); {@code familyId} is set on
     * custom (manual) species only.
     */
    record Taxon(String id, String parentId, String rank, String name, String common, String source, boolean active,
                 String path, String sortKey, int livePhotos, String familyId) {

        boolean isSpecies() {
            return "species".equals(rank);
        }

        boolean visibleSpecies() {
            return isSpecies() && (active || livePhotos > 0);
        }
    }

    private final Map<String, Taxon> byId = new LinkedHashMap<>();
    private final List<Taxon> visibleSpecies = new ArrayList<>();

    private Taxa() {
    }

    static Taxa load(Connection c) throws SQLException {
        Map<String, Integer> live = new HashMap<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT taxon_id, COUNT(*) FROM image WHERE deleted_at IS NULL"
                + " GROUP BY taxon_id"); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                live.put(rs.getString(1), rs.getInt(2));
            }
        }
        Taxa taxa = new Taxa();
        try (PreparedStatement ps = c.prepareStatement("SELECT id, parent_id, rank, name, common_name, source, active,"
                + " ancestor_path, sort_key, family_id FROM taxon"); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                Taxon t = new Taxon(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                        rs.getString(6), rs.getInt(7) == 1, rs.getString(8), rs.getString(9),
                        live.getOrDefault(rs.getString(1), 0), rs.getString(10));
                taxa.byId.put(t.id(), t);
            }
        }
        for (Taxon t : taxa.byId.values()) {
            if (t.visibleSpecies()) {
                taxa.visibleSpecies.add(t);
            }
        }
        taxa.visibleSpecies.sort(taxa.order());
        return taxa;
    }

    Taxon find(String id) {
        return byId.get(id);
    }

    /**
     * Every visible species, in taxonomic order: active, or with live photos (the species rule of
     * {@link com.butterflyatlas.app.taxa.TaxonSnapshot#visible}).
     */
    List<Taxon> visibleSpecies() {
        return visibleSpecies;
    }

    /** A chosen taxon counts while it is, or has below it, a visible species (else it is dropped like an unknown id). */
    boolean usable(Taxon taxon) {
        return visibleSpecies.stream().anyMatch(s -> s.path().startsWith(taxon.path()));
    }

    /** Whether the library shows any manual species. */
    boolean anyManual() {
        return visibleSpecies.stream().anyMatch(s -> Filter.MANUAL.equals(s.source()));
    }

    /**
     * A species' family, subfamily, tribe and genus names as the species list and exports show them (null where there is
     * none): an official species' ancestors; for a custom species (5a contract "Changed shapes") the family it was given,
     * the binomial's first word as the genus, and the subfamily and tribe of the active official genus of that name in
     * that family, when there is one.
     */
    record Names(String family, String subfamily, String tribe, String genus) {
    }

    Names names(Taxon species) {
        if (species.familyId() == null) {
            return new Names(FilterQuery.name(ancestor(species, "family")), FilterQuery.name(ancestor(species, "subfamily")),
                    FilterQuery.name(ancestor(species, "tribe")), FilterQuery.name(ancestor(species, "genus")));
        }
        Taxon family = byId.get(species.familyId());
        String genusName = species.name().split(" ", 2)[0];
        Taxon genus = family == null ? null : byId.values().stream()
                .filter(t -> "genus".equals(t.rank()) && t.active() && Filter.OFFICIAL.equals(t.source())
                        && t.name().equals(genusName) && t.path().startsWith(family.path()))
                .findFirst().orElse(null);
        return new Names(FilterQuery.name(family), genus == null ? null : FilterQuery.name(ancestor(genus, "subfamily")),
                genus == null ? null : FilterQuery.name(ancestor(genus, "tribe")), genusName);
    }

    /** The ancestor of {@code taxon} at {@code rank}, or null when its line has none. */
    Taxon ancestor(Taxon taxon, String rank) {
        for (String id : taxon.path().split("/")) {
            Taxon a = id.isEmpty() ? null : byId.get(id);
            if (a != null && a.rank().equals(rank)) {
                return a;
            }
        }
        return null;
    }

    /**
     * Taxonomic order: by family, subfamily, tribe, genus and species, each by sort key then id, as Explore lists
     * children. A missing subfamily, tribe or genus sorts as if it were empty (first); species with no family in their
     * line (custom species, under "Custom additions", whatever family they were given) come after every official
     * species, by sort key then id, as "Custom additions" does in Explore.
     */
    Comparator<Taxon> order() {
        Map<String, Taxon[]> lines = new HashMap<>();
        Comparator<Taxon> byKey = Comparator.comparing(Taxon::sortKey).thenComparing(Taxon::id);
        return (a, b) -> {
            Taxon[] la = lines.computeIfAbsent(a.id(), id -> line(a));
            Taxon[] lb = lines.computeIfAbsent(b.id(), id -> line(b));
            if ((la[0] == null) != (lb[0] == null)) {
                return la[0] == null ? 1 : -1;
            }
            for (int i = 0; i < la.length; i++) {
                if (la[i] == lb[i]) {
                    continue;
                }
                if (la[i] == null || lb[i] == null) {
                    return la[i] == null ? -1 : 1;
                }
                int c = byKey.compare(la[i], lb[i]);
                if (c != 0) {
                    return c;
                }
            }
            return 0;
        };
    }

    private Taxon[] line(Taxon species) {
        Taxon[] line = new Taxon[RANKS.size() + 1];
        for (int i = 0; i < RANKS.size(); i++) {
            line[i] = ancestor(species, RANKS.get(i));
        }
        line[RANKS.size()] = species;
        return line;
    }
}
