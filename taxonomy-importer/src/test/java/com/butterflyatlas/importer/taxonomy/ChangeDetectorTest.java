package com.butterflyatlas.importer.taxonomy;

import com.butterflyatlas.importer.bundle.Bundle;
import com.butterflyatlas.importer.bundle.BundleTaxon;
import com.butterflyatlas.importer.bundle.Change;
import com.butterflyatlas.importer.inat.InatTaxon;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChangeDetectorTest {

    private final AtlasIdRegistry registry = new AtlasIdRegistry();

    private static TreeNode family(long id, String name) {
        return new TreeNode(id, null, "family", name, null, List.of());
    }

    private static TreeNode genus(long id, long parent, String name) {
        return new TreeNode(id, parent, "genus", name, null, List.of());
    }

    private static TreeNode species(long id, long parent, String name) {
        return new TreeNode(id, parent, "species", name, null, List.of());
    }

    private static InatTaxon retired(long id, Long... replacements) {
        return new InatTaxon(id, "retired " + id, "species", null, List.of(id), false, List.of(replacements));
    }

    /** Family 10 → genera 20, 21 → species 1, 2 under genus 20. */
    private static List<TreeNode> baseline() {
        return List.of(family(10, "Nymphalidae"), genus(20, 10, "Danaus"), genus(21, 10, "Tirumala"),
                species(1, 20, "Danaus chrysippus"), species(2, 20, "Danaus genutia"));
    }

    private Bundle previousBundle() {
        ChangeDetector.Result first = ChangeDetector.reconcile(null, baseline(), Map.of(), registry);
        return new Bundle(Bundle.FORMAT, "2026-09-08", null, "t", null, null, null, null, first.taxa(), first.changes());
    }

    private static List<TreeNode> replace(List<TreeNode> nodes, TreeNode... replacements) {
        List<TreeNode> result = new ArrayList<>(nodes);
        for (TreeNode replacement : replacements) {
            result.removeIf(node -> node.inatId() == replacement.inatId());
            result.add(replacement);
        }
        return result;
    }

    private static List<TreeNode> without(List<TreeNode> nodes, long... inatIds) {
        List<TreeNode> result = new ArrayList<>(nodes);
        for (long id : inatIds) {
            result.removeIf(node -> node.inatId() == id);
        }
        return result;
    }

    @Test
    void firstImportAssignsIdsAndReportsNoChanges() {
        ChangeDetector.Result result = ChangeDetector.reconcile(null, baseline(), Map.of(), registry);

        assertThat(result.changes()).isEmpty();
        assertThat(result.taxa()).extracting(BundleTaxon::id).containsExactly("A1", "A2", "A3", "A4", "A5");
        BundleTaxon chrysippus = result.taxa().get(3);
        assertThat(chrysippus.parent()).isEqualTo("A2");
        assertThat(chrysippus.inatId()).isEqualTo(1);
    }

    @Test
    void reportsNothingWhenNothingChanged() {
        Bundle previous = previousBundle();

        assertThat(ChangeDetector.reconcile(previous, baseline(), Map.of(), registry).changes()).isEmpty();
    }

    @Test
    void reportsAddedSpecies() {
        Bundle previous = previousBundle();
        List<TreeNode> next = replace(baseline(), species(3, 21, "Tirumala limniace"));

        List<Change> changes = ChangeDetector.reconcile(previous, next, Map.of(), registry).changes();

        assertThat(changes).containsExactly(
                new Change(Change.Type.ADD, List.of(), List.of("A6"), "Tirumala limniace added"));
    }

    @Test
    void reportsRenamesInPlace() {
        Bundle previous = previousBundle();
        List<TreeNode> next = replace(baseline(), species(2, 20, "Danaus genutius"));

        assertThat(ChangeDetector.reconcile(previous, next, Map.of(), registry).changes()).containsExactly(
                new Change(Change.Type.RENAME, List.of("A5"), List.of("A5"), "Danaus genutia → Danaus genutius"));
    }

    @Test
    void reportsMoves() {
        Bundle previous = previousBundle();
        List<TreeNode> next = replace(baseline(), species(2, 21, "Danaus genutia"));

        assertThat(ChangeDetector.reconcile(previous, next, Map.of(), registry).changes()).containsExactly(
                new Change(Change.Type.MOVE, List.of("A5"), List.of("A5"), "Danaus genutia: Danaus → Tirumala"));
    }

    @Test
    void aSwappedINaturalistIdKeepsItsAtlasId() {
        Bundle previous = previousBundle();
        List<TreeNode> next = replace(without(baseline(), 2), species(7, 20, "Danaus genutius"));

        ChangeDetector.Result result = ChangeDetector.reconcile(previous, next, Map.of(2L, retired(2, 7L)), registry);

        assertThat(result.changes()).containsExactly(
                new Change(Change.Type.RENAME, List.of("A5"), List.of("A5"), "Danaus genutia → Danaus genutius"));
        assertThat(registry.idFor(7)).isEqualTo("A5");
    }

    @Test
    void reportsMergesIntoAnExistingSpecies() {
        Bundle previous = previousBundle();
        List<TreeNode> next = without(baseline(), 2);

        assertThat(ChangeDetector.reconcile(previous, next, Map.of(2L, retired(2, 1L)), registry).changes())
                .containsExactly(new Change(Change.Type.MERGE, List.of("A5"), List.of("A4"),
                        "Danaus genutia merged into Danaus chrysippus"));
    }

    @Test
    void groupsSeveralRetiredTaxaMergingIntoOneNewTaxon() {
        Bundle previous = previousBundle();
        List<TreeNode> next = replace(without(baseline(), 1, 2), species(8, 20, "Danaus plexippus"));

        List<Change> changes = ChangeDetector.reconcile(previous, next,
                Map.of(1L, retired(1, 8L), 2L, retired(2, 8L)), registry).changes();

        assertThat(changes).containsExactly(new Change(Change.Type.MERGE, List.of("A4", "A5"), List.of("A6"),
                "Danaus chrysippus, Danaus genutia merged into Danaus plexippus"));
    }

    @Test
    void reportsSplitsWithoutAlsoReportingTheNewSpeciesAsAdded() {
        Bundle previous = previousBundle();
        List<TreeNode> next = replace(without(baseline(), 2),
                species(8, 20, "Danaus genutia"), species(9, 20, "Danaus alcippoides"));

        assertThat(ChangeDetector.reconcile(previous, next, Map.of(2L, retired(2, 8L, 9L)), registry).changes())
                .containsExactly(new Change(Change.Type.SPLIT, List.of("A5"), List.of("A6", "A7"),
                        "Danaus genutia split into Danaus genutia, Danaus alcippoides"));
    }

    @Test
    void reportsRemovalsWithNoReplacement() {
        Bundle previous = previousBundle();
        List<TreeNode> next = without(baseline(), 2);

        assertThat(ChangeDetector.reconcile(previous, next, Map.of(), registry).changes()).containsExactly(
                new Change(Change.Type.REMOVE, List.of("A5"), List.of(), "Danaus genutia removed"));
    }

    @Test
    void reRunningTheSameSwapIsIdempotent() {
        Bundle previous = previousBundle();
        List<TreeNode> next = replace(without(baseline(), 2), species(7, 20, "Danaus genutius"));
        Map<Long, InatTaxon> retired = Map.of(2L, retired(2, 7L));

        ChangeDetector.Result first = ChangeDetector.reconcile(previous, next, retired, registry);
        ChangeDetector.Result second = ChangeDetector.reconcile(previous, next, retired, registry);

        assertThat(second).isEqualTo(first);
        assertThat(second.changes()).containsExactly(
                new Change(Change.Type.RENAME, List.of("A5"), List.of("A5"), "Danaus genutia → Danaus genutius"));
    }

    @Test
    void aGenusSwapKeepsItsIdAndChildrenStayPut() {
        Bundle previous = previousBundle();
        List<TreeNode> next = List.of(family(10, "Nymphalidae"), genus(30, 10, "Danaus"), genus(21, 10, "Tirumala"),
                species(1, 30, "Danaus chrysippus"), species(2, 30, "Danaus genutia"));
        Map<Long, InatTaxon> retired = Map.of(20L, retired(20, 30L));

        ChangeDetector.Result first = ChangeDetector.reconcile(previous, next, retired, registry);
        ChangeDetector.Result second = ChangeDetector.reconcile(previous, next, retired, registry);

        assertThat(first.changes()).isEmpty();
        assertThat(second).isEqualTo(first);
        assertThat(second.taxa()).filteredOn(t -> t.inatId() == 1 || t.inatId() == 2)
                .extracting(BundleTaxon::parent).containsOnly("A2");
    }

    @Test
    void everyScenarioIsIdempotentWhenRunTwice() {
        record Scenario(List<TreeNode> next, Map<Long, InatTaxon> retired) {
        }
        List<Scenario> scenarios = List.of(
                new Scenario(replace(baseline(), species(3, 21, "Tirumala limniace")), Map.of()),
                new Scenario(replace(baseline(), species(2, 20, "Danaus genutius")), Map.of()),
                new Scenario(replace(baseline(), species(2, 21, "Danaus genutia")), Map.of()),
                new Scenario(without(baseline(), 2), Map.of(2L, retired(2, 1L))),
                new Scenario(replace(without(baseline(), 1, 2), species(8, 20, "Danaus plexippus")),
                        Map.of(1L, retired(1, 8L), 2L, retired(2, 8L))),
                new Scenario(replace(without(baseline(), 2), species(8, 20, "Danaus genutia"),
                        species(9, 20, "Danaus alcippoides")), Map.of(2L, retired(2, 8L, 9L))),
                new Scenario(without(baseline(), 2), Map.of()));
        for (Scenario scenario : scenarios) {
            AtlasIdRegistry fresh = new AtlasIdRegistry();
            ChangeDetector.Result base = ChangeDetector.reconcile(null, baseline(), Map.of(), fresh);
            Bundle previous = new Bundle(Bundle.FORMAT, "2026-09-08", null, "t", null, null, null, null, base.taxa(), base.changes());

            ChangeDetector.Result first = ChangeDetector.reconcile(previous, scenario.next(), scenario.retired(), fresh);
            ChangeDetector.Result second = ChangeDetector.reconcile(previous, scenario.next(), scenario.retired(), fresh);

            assertThat(second).isEqualTo(first);
        }
    }

    @Test
    void rejectsARegistryThatDoesNotMatchThePreviousBundle() {
        Bundle previous = previousBundle();

        assertThatThrownBy(() -> ChangeDetector.reconcile(previous, baseline(), Map.of(), new AtlasIdRegistry()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not match the previous bundle");
    }

    @Test
    void rejectsTwoCoexistingINaturalistTaxaSharingOneAtlasId() {
        Bundle previous = previousBundle();
        registry.alias(7, "A5"); // left over from an earlier swap of 2 → 7 that was later reversed
        List<TreeNode> next = replace(baseline(), species(7, 20, "Danaus genutius"));

        assertThatThrownBy(() -> ChangeDetector.reconcile(previous, next, Map.of(), registry))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Atlas id A5 would be shared by iNaturalist taxa 2 and 7");
    }
}
