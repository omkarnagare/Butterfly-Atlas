package com.butterflyatlas.importer.taxonomy;

import com.butterflyatlas.importer.bundle.Bundle;
import com.butterflyatlas.importer.bundle.BundleTaxon;
import com.butterflyatlas.importer.bundle.Change;
import com.butterflyatlas.importer.inat.InatTaxon;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Gives the new tree its Atlas ids and lists what changed since the previous bundle.
 *
 * <p>The app must treat a bundle's change list as one remap keyed by Atlas id, not apply entries one by
 * one in list order. MERGE and SPLIT targets may be ids that are not present in the previous bundle.
 */
public final class ChangeDetector {

    public record Result(List<BundleTaxon> taxa, List<Change> changes) {
    }

    private ChangeDetector() {
    }

    /**
     * @param previous the last bundle, or {@code null} for the first import
     * @param next     the freshly built tree (parents before children)
     * @param retired  current iNaturalist data for taxa in {@code previous} that are missing from {@code next}
     */
    public static Result reconcile(Bundle previous, List<TreeNode> next, Map<Long, InatTaxon> retired,
                                   AtlasIdRegistry registry) {
        List<BundleTaxon> previousTaxa = previous == null ? List.of() : previous.taxa();
        Map<Long, BundleTaxon> previousByInat = previousTaxa.stream()
                .collect(Collectors.toMap(BundleTaxon::inatId, Function.identity()));
        Map<String, BundleTaxon> previousById = previousTaxa.stream()
                .collect(Collectors.toMap(BundleTaxon::id, Function.identity()));
        Map<Long, TreeNode> nextByInat = next.stream()
                .collect(Collectors.toMap(TreeNode::inatId, Function.identity(), (a, b) -> a, LinkedHashMap::new));

        for (BundleTaxon before : previousTaxa) {
            if (nextByInat.containsKey(before.inatId())
                    && !registry.find(before.inatId()).map(before.id()::equals).orElse(false)) {
                throw new IllegalStateException("Atlas id registry does not match the previous bundle for "
                        + before.name() + " (iNat " + before.inatId() + "): registry has "
                        + registry.find(before.inatId()).orElse("nothing") + ", bundle has " + before.id());
            }
        }

        List<Change> changes = new ArrayList<>();
        Set<String> targets = new HashSet<>();
        if (previous != null) {
            reconcileRetired(previousTaxa, nextByInat, previousByInat, retired, registry, changes, targets);
        }

        List<BundleTaxon> taxa = new ArrayList<>();
        for (TreeNode node : next) {
            String id = registry.idFor(node.inatId());
            String parent = node.parentInatId() == null ? null : registry.idFor(node.parentInatId());
            taxa.add(new BundleTaxon(id, parent, node.rank(), node.name(), node.commonName(), node.inatId(),
                    node.synonyms()));
        }
        Map<String, Long> owner = new HashMap<>();
        for (BundleTaxon taxon : taxa) {
            Long other = owner.putIfAbsent(taxon.id(), taxon.inatId());
            if (other != null) {
                throw new IllegalStateException("Atlas id " + taxon.id() + " would be shared by iNaturalist taxa "
                        + other + " and " + taxon.inatId());
            }
        }
        if (previous == null) {
            return new Result(taxa, List.of());
        }

        Map<String, String> nameById = new HashMap<>();
        previousTaxa.forEach(t -> nameById.put(t.id(), t.name()));
        taxa.forEach(t -> nameById.put(t.id(), t.name()));
        for (BundleTaxon taxon : taxa) {
            BundleTaxon before = previousById.get(taxon.id());
            if (before == null) {
                if (!targets.contains(taxon.id())) {
                    changes.add(new Change(Change.Type.ADD, List.of(), List.of(taxon.id()), taxon.name() + " added"));
                }
                continue;
            }
            if (!before.name().equals(taxon.name())) {
                changes.add(new Change(Change.Type.RENAME, List.of(taxon.id()), List.of(taxon.id()),
                        before.name() + " → " + taxon.name()));
            }
            if (!Objects.equals(before.parent(), taxon.parent())) {
                changes.add(new Change(Change.Type.MOVE, List.of(taxon.id()), List.of(taxon.id()),
                        taxon.name() + ": " + nameOf(nameById, before.parent()) + " → " + nameOf(nameById, taxon.parent())));
            }
        }
        changes.sort(Comparator.comparing((Change c) -> c.type().ordinal())
                .thenComparing(c -> String.join(",", c.from()))
                .thenComparing(c -> String.join(",", c.to())));
        return new Result(taxa, changes);
    }

    private static void reconcileRetired(List<BundleTaxon> previousTaxa, Map<Long, TreeNode> nextByInat,
                                         Map<Long, BundleTaxon> previousByInat, Map<Long, InatTaxon> retired,
                                         AtlasIdRegistry registry, List<Change> changes, Set<String> targets) {
        List<BundleTaxon> gone = previousTaxa.stream()
                .filter(t -> !nextByInat.containsKey(t.inatId()))
                .sorted(Comparator.comparingLong(BundleTaxon::inatId))
                .toList();
        Map<BundleTaxon, List<Long>> replacements = new LinkedHashMap<>();
        Map<Long, Integer> claims = new HashMap<>();
        for (BundleTaxon taxon : gone) {
            InatTaxon info = retired.get(taxon.inatId());
            List<Long> outputs = info == null ? List.of() : info.currentSynonymousIds().stream()
                    .filter(nextByInat::containsKey).sorted().toList();
            replacements.put(taxon, outputs);
            outputs.forEach(id -> claims.merge(id, 1, Integer::sum));
        }

        Map<Long, List<BundleTaxon>> merges = new LinkedHashMap<>();
        for (Map.Entry<BundleTaxon, List<Long>> entry : replacements.entrySet()) {
            BundleTaxon taxon = entry.getKey();
            List<Long> outputs = entry.getValue();
            if (outputs.isEmpty()) {
                changes.add(new Change(Change.Type.REMOVE, List.of(taxon.id()), List.of(), taxon.name() + " removed"));
            } else if (outputs.size() == 1) {
                long output = outputs.get(0);
                boolean swap = !previousByInat.containsKey(output) && claims.get(output) == 1
                        && registry.find(output).map(taxon.id()::equals).orElse(true);
                if (swap) {
                    registry.alias(output, taxon.id());
                } else {
                    merges.computeIfAbsent(output, id -> new ArrayList<>()).add(taxon);
                }
            } else {
                List<String> to = outputs.stream().map(registry::idFor).toList();
                targets.addAll(to);
                changes.add(new Change(Change.Type.SPLIT, List.of(taxon.id()), to, taxon.name() + " split into "
                        + outputs.stream().map(id -> nextByInat.get(id).name()).collect(Collectors.joining(", "))));
            }
        }
        merges.forEach((output, sources) -> {
            String to = registry.idFor(output);
            if (sources.stream().anyMatch(source -> source.id().equals(to))) {
                throw new IllegalStateException("Change detection produced a self-merge for " + to);
            }
            targets.add(to);
            changes.add(new Change(Change.Type.MERGE, sources.stream().map(BundleTaxon::id).toList(), List.of(to),
                    sources.stream().map(BundleTaxon::name).collect(Collectors.joining(", "))
                            + " merged into " + nextByInat.get(output).name()));
        });
    }

    private static String nameOf(Map<String, String> nameById, String id) {
        return id == null ? "(top level)" : nameById.getOrDefault(id, id);
    }
}
