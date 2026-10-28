package com.butterflyatlas.importer.taxonomy;

import com.butterflyatlas.importer.inat.InatTaxon;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TaxonomyBuilderTest {

    private static InatTaxon taxon(long id, String name, String rank, Long... ancestors) {
        return new InatTaxon(id, name, rank, null, List.of(ancestors), true, List.of());
    }

    private static final Map<Long, InatTaxon> ANCESTORS = List.of(
            taxon(10, "Nymphalidae", "family"),
            taxon(20, "Danainae", "subfamily"),
            taxon(30, "Danaini", "tribe"),
            taxon(35, "Danaina", "subtribe"),
            taxon(40, "Danaus", "genus"),
            taxon(11, "Pieridae", "family"),
            taxon(21, "Coliadinae", "subfamily"),
            taxon(41, "Eurema", "genus")
    ).stream().collect(Collectors.toMap(InatTaxon::id, Function.identity()));

    private static final InatTaxon PLAIN_TIGER =
            taxon(1, "Danaus chrysippus", "species", 48460L, 47224L, 10L, 20L, 30L, 35L, 40L, 1L);
    private static final InatTaxon GRASS_YELLOW =
            taxon(2, "Eurema hecabe", "species", 48460L, 47224L, 11L, 21L, 41L, 2L);

    private static Map<Long, TreeNode> byId(List<TreeNode> nodes) {
        return nodes.stream().collect(Collectors.toMap(TreeNode::inatId, Function.identity()));
    }

    @Test
    void keepsFamilyToSpeciesAndSkipsOtherRanks() {
        List<TreeNode> nodes = TaxonomyBuilder.build(List.of(PLAIN_TIGER), ANCESTORS, Map.of());

        assertThat(nodes).extracting(TreeNode::name)
                .containsExactly("Nymphalidae", "Danainae", "Danaini", "Danaus", "Danaus chrysippus");
        Map<Long, TreeNode> byId = byId(nodes);
        assertThat(byId.get(10L).parentInatId()).isNull();
        assertThat(byId.get(40L).parentInatId()).isEqualTo(30L);
        assertThat(byId.get(1L).parentInatId()).isEqualTo(40L);
    }

    @Test
    void hangsAGenusWithoutATribeUnderItsSubfamily() {
        Map<Long, TreeNode> byId = byId(TaxonomyBuilder.build(List.of(GRASS_YELLOW), ANCESTORS, Map.of()));

        assertThat(byId.get(41L).parentInatId()).isEqualTo(21L);
    }

    @Test
    void sharesAncestorsBetweenSpecies() {
        InatTaxon stripedTiger = taxon(3, "Danaus genutia", "species", 47224L, 10L, 20L, 30L, 35L, 40L, 3L);

        List<TreeNode> nodes = TaxonomyBuilder.build(List.of(PLAIN_TIGER, stripedTiger, GRASS_YELLOW), ANCESTORS, Map.of());

        assertThat(nodes).hasSize(10);
        assertThat(nodes).extracting(TreeNode::inatId).doesNotHaveDuplicates();
        List<Long> seen = new java.util.ArrayList<>();
        for (TreeNode node : nodes) {
            if (node.parentInatId() != null) {
                assertThat(seen).contains(node.parentInatId());
            }
            seen.add(node.inatId());
        }
    }

    @Test
    void attachesSynonymsAndCommonNames() {
        InatTaxon withCommonName = new InatTaxon(1, "Danaus chrysippus", "species", "Plain Tiger",
                PLAIN_TIGER.ancestorIds(), true, List.of());

        TreeNode species = byId(TaxonomyBuilder.build(List.of(withCommonName), ANCESTORS,
                Map.of(1L, List.of("Limnas klugii")))).get(1L);

        assertThat(species.commonName()).isEqualTo("Plain Tiger");
        assertThat(species.synonyms()).containsExactly("Limnas klugii");
    }

    @Test
    void ignoresTaxaThatAreNotSpecies() {
        InatTaxon genusOnly = taxon(40, "Danaus", "genus", 47224L, 10L, 20L, 30L, 40L);

        assertThat(TaxonomyBuilder.build(List.of(genusOnly), ANCESTORS, Map.of())).isEmpty();
    }

    @Test
    void refusesASpeciesWithoutAFamily() {
        InatTaxon orphan = taxon(5, "Orphanus nullus", "species", 47224L, 40L, 5L);

        assertThatThrownBy(() -> TaxonomyBuilder.build(List.of(orphan), ANCESTORS, Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Orphanus nullus");
    }

    @Test
    void acceptsTheSameSpeciesTwiceWithIdenticalData() {
        assertThat(TaxonomyBuilder.build(List.of(PLAIN_TIGER, PLAIN_TIGER), ANCESTORS, Map.of())).hasSize(5);
    }

    @Test
    void rejectsTheSameTaxonWithConflictingAncestry() {
        InatTaxon moved = taxon(1, "Danaus chrysippus", "species", 47224L, 11L, 21L, 41L, 1L);

        assertThatThrownBy(() -> TaxonomyBuilder.build(List.of(PLAIN_TIGER, moved), ANCESTORS, Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Conflicting ancestry for Danaus chrysippus");
    }
}
