package com.butterflyatlas.importer;

import com.butterflyatlas.importer.bundle.Bundle;
import com.butterflyatlas.importer.bundle.BundleChecksums;
import com.butterflyatlas.importer.bundle.BundleIO;
import com.butterflyatlas.importer.bundle.BundleTaxon;
import com.butterflyatlas.importer.bundle.Change;
import com.butterflyatlas.importer.inat.InatClient;
import com.butterflyatlas.importer.inat.InatTaxon;
import com.butterflyatlas.importer.taxonomy.AtlasIdRegistry;
import com.butterflyatlas.importer.taxonomy.ChangeDetector;
import com.butterflyatlas.importer.taxonomy.TaxonomyBuilder;
import com.butterflyatlas.importer.taxonomy.TreeNode;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Runs one import: fetch from iNaturalist, build the tree, assign ids, diff, write the bundle. */
public final class Importer {

    public record Options(long placeId, String regionName, long groupTaxonId, String groupKey, String groupName,
                          Quality quality, String version, Path outputDirectory, Path registryFile) {
    }

    public record Summary(Path file, Map<String, Long> countsByRank, List<Change> changes,
                          int retiredCount, int previousCount) {
        public int changeCount() {
            return changes.size();
        }
    }

    private final InatClient inat;
    private final Clock clock;

    public Importer(InatClient inat, Clock clock) {
        this.inat = inat;
        this.clock = clock;
    }

    public Summary run(Options options) throws IOException {
        AtlasIdRegistry registry = AtlasIdRegistry.load(options.registryFile());
        Bundle previous = BundleIO.previous(options.outputDirectory(), options.regionName(), options.version())
                .orElse(null);

        List<InatTaxon> species = inat.speciesInPlace(options.placeId(), options.groupTaxonId(), options.quality())
                .stream().filter(t -> "species".equals(t.rank()) && t.active()).toList();
        if (species.isEmpty()) {
            throw new IOException("iNaturalist returned no species for taxon " + options.groupTaxonId()
                    + " in place " + options.placeId() + "; refusing to write an empty bundle");
        }
        Map<Long, InatTaxon> ancestors = index(inat.taxa(ancestorIds(species, options.groupTaxonId())));
        Map<Long, List<String>> synonyms = inat.synonyms(species.stream().map(InatTaxon::id).toList());
        List<TreeNode> tree = TaxonomyBuilder.build(species, ancestors, synonyms);

        Map<Long, InatTaxon> retired = Map.of();
        int retiredCount = 0;
        if (previous != null) {
            Set<Long> present = tree.stream().map(TreeNode::inatId).collect(Collectors.toSet());
            List<Long> gone = previous.taxa().stream().map(BundleTaxon::inatId)
                    .filter(id -> !present.contains(id)).toList();
            retiredCount = gone.size();
            retired = index(inat.taxa(gone));
        }

        ChangeDetector.Result result = ChangeDetector.reconcile(previous, tree, retired, registry);
        Bundle bundle = new Bundle(Bundle.FORMAT, options.version(), previous == null ? null : previous.version(),
                clock.instant().toString(), TaxonomyBuilder.RANKS,
                new Bundle.Region(options.regionName(), options.placeId()),
                new Bundle.Group(options.groupKey(), options.groupName(), options.groupTaxonId()),
                new Bundle.Source("iNaturalist", "https://www.inaturalist.org", options.quality().label(),
                        "Taxonomy data from iNaturalist. Species list from " + options.quality().noteWording()
                                + " iNaturalist observations in " + options.regionName() + "."),
                result.taxa(), result.changes());

        Path file = options.outputDirectory().resolve(BundleIO.fileName(options.regionName(), options.version()));
        // Ids are append-only, so saving first is harmless if the bundle write then fails.
        registry.save(options.registryFile());
        BundleIO.write(file, bundle);
        BundleChecksums.write(options.outputDirectory());

        Map<String, Long> counts = new TreeMap<>(result.taxa().stream()
                .collect(Collectors.groupingBy(BundleTaxon::rank, Collectors.counting())));
        return new Summary(file, counts, result.changes(), retiredCount, previous == null ? 0 : previous.taxa().size());
    }

    /** Ids strictly between the group taxon and each species, e.g. family … genus. */
    private static Set<Long> ancestorIds(List<InatTaxon> species, long groupTaxonId) {
        Set<Long> ids = new LinkedHashSet<>();
        for (InatTaxon taxon : species) {
            List<Long> chain = taxon.ancestorIds();
            int group = chain.indexOf(groupTaxonId);
            if (group < 0) {
                throw new IllegalStateException(taxon.name() + " is not inside taxon " + groupTaxonId);
            }
            chain.subList(group + 1, chain.size()).stream().filter(id -> id != taxon.id()).forEach(ids::add);
        }
        return ids;
    }

    private static Map<Long, InatTaxon> index(List<InatTaxon> taxa) {
        return taxa.stream().collect(Collectors.toMap(InatTaxon::id, Function.identity(), (a, b) -> a,
                LinkedHashMap::new));
    }
}
