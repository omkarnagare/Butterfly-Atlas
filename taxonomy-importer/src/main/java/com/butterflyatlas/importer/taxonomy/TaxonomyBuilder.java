package com.butterflyatlas.importer.taxonomy;

import com.butterflyatlas.importer.inat.InatTaxon;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Builds the Family → Subfamily → Tribe → Genus → Species tree the app shows. */
public final class TaxonomyBuilder {

    /** Ranks the app shows, top to bottom. Everything else in iNaturalist's ancestry is skipped. */
    public static final List<String> RANKS = List.of("family", "subfamily", "tribe", "genus", "species");

    private TaxonomyBuilder() {
    }

    /**
     * @param species    species to include (taxa of other ranks are ignored)
     * @param ancestors  ancestor taxa by iNaturalist id; ids missing here are skipped
     * @param synonyms   former scientific names by species id
     * @return kept nodes, parents before children
     */
    public static List<TreeNode> build(List<InatTaxon> species, Map<Long, InatTaxon> ancestors,
                                       Map<Long, List<String>> synonyms) {
        Map<Long, TreeNode> nodes = new LinkedHashMap<>();
        for (InatTaxon taxon : species) {
            if (!"species".equals(taxon.rank())) {
                continue;
            }
            Long parent = null;
            for (Long ancestorId : taxon.ancestorIds()) {
                if (ancestorId == taxon.id()) {
                    break;
                }
                InatTaxon ancestor = ancestors.get(ancestorId);
                if (ancestor == null || !RANKS.contains(ancestor.rank())) {
                    continue;
                }
                if (parent == null && !"family".equals(ancestor.rank())) {
                    throw new IllegalStateException(taxon.name() + " has no family in its iNaturalist ancestry");
                }
                add(nodes, ancestor, parent, List.of());
                parent = ancestor.id();
            }
            if (parent == null) {
                throw new IllegalStateException(taxon.name() + " has no family in its iNaturalist ancestry");
            }
            add(nodes, taxon, parent, synonyms.getOrDefault(taxon.id(), List.of()));
        }
        return new ArrayList<>(nodes.values());
    }

    private static void add(Map<Long, TreeNode> nodes, InatTaxon taxon, Long parent, List<String> synonyms) {
        TreeNode existing = nodes.get(taxon.id());
        if (existing == null) {
            nodes.put(taxon.id(), new TreeNode(taxon.id(), parent, taxon.rank(), taxon.name(),
                    taxon.commonName(), List.copyOf(synonyms)));
            return;
        }
        if (!Objects.equals(existing.parentInatId(), parent) || !existing.rank().equals(taxon.rank())
                || !existing.name().equals(taxon.name())) {
            throw new IllegalStateException("Conflicting ancestry for " + taxon.name() + ": parent "
                    + existing.parentInatId() + " vs " + parent);
        }
    }
}
