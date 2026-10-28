package com.butterflyatlas.app.testing;

import com.butterflyatlas.app.taxonomy.TaxonomyBundles;
import com.butterflyatlas.app.taxonomy.bundle.Bundle;
import com.butterflyatlas.app.taxonomy.bundle.BundleReader;
import com.butterflyatlas.app.taxonomy.bundle.BundleTaxon;
import com.butterflyatlas.app.taxonomy.bundle.Change;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Taxonomy update chains for tests and real-app runs (design D10): a bundle derived from another with chosen changes
 * (rename, move, merge, split, add and remove at any rank, a removed taxon re-added), its change list made the way the
 * taxonomy importer makes it (one remap keyed by Atlas id: renames and moves from the trees, adds and removes from the ids,
 * merges and splits as chosen; a merge or split target may be new), and the chain as {@link TaxonomyBundles} in memory or
 * written to a folder with its {@code bundles.sha256} for {@code -Datlas.taxonomyDir}.
 *
 * <p>{@link #main} derives a v2 from the real bundle for a real-app run:
 * {@code ./mvnw -B -ntp -pl app test-compile exec:exec -Dexec.executable=java -Dexec.classpathScope=test
 * "-Dexec.args=-cp %classpath com.butterflyatlas.app.testing.UpdateBundles <folder> [custom name]"}.
 */
public final class UpdateBundles {

    /** The real bundle's file name inside the app. */
    public static final String REAL = "taxonomy-india-2026-10-08.json";
    /** The version of the v2 {@link #main} derives. */
    public static final String REAL_V2 = "2026-12-01";
    /** The added species {@link #main} gives by default: a name a custom species can have today (not in the list). */
    public static final String DEFAULT_CUSTOM_NAME = "Lethe margaretae";

    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private UpdateBundles() {
    }

    /** The 14-taxon fixture bundle (version 2026-01-01). */
    public static Bundle fixture() {
        return read(TestBundles.fixtureBytes());
    }

    /** The real bundle shipped with the app. */
    public static Bundle real() {
        return read(realBytes());
    }

    static byte[] realBytes() {
        try (InputStream in = UpdateBundles.class.getResourceAsStream(TaxonomyBundles.DIRECTORY + REAL)) {
            return Objects.requireNonNull(in, "missing resource " + TaxonomyBundles.DIRECTORY + REAL).readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Bundle read(byte[] bytes) {
        try {
            return BundleReader.read(bytes);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A bundle of {@code version} based on {@code base}, to be given its changes. */
    public static Derived derive(Bundle base, String version) {
        return new Derived(base, version);
    }

    /** The bundle as the app reads it (JSON). */
    public static byte[] json(Bundle bundle) {
        try {
            return JSON.writeValueAsBytes(bundle);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** {@code taxonomy-india-<version>.json}. */
    public static String fileName(Bundle bundle) {
        return "taxonomy-india-" + bundle.version() + ".json";
    }

    /** The chain as the app would ship it, in memory, with a correct checksum list. */
    public static TaxonomyBundles bundles(Bundle... chain) {
        Map<String, byte[]> files = new HashMap<>();
        StringBuilder sums = new StringBuilder();
        for (Bundle bundle : chain) {
            byte[] bytes = json(bundle);
            files.put(fileName(bundle), bytes);
            sums.append(TaxonomyBundles.sha256(bytes)).append("  ").append(fileName(bundle)).append('\n');
        }
        files.put(TaxonomyBundles.CHECKSUMS, sums.toString().getBytes(StandardCharsets.UTF_8));
        return TaxonomyBundles.load(name -> files.containsKey(name) ? new ByteArrayInputStream(files.get(name)) : null);
    }

    /** Writes the chain into {@code folder} (made if needed): each bundle and {@code bundles.sha256}. */
    public static void write(Path folder, Bundle... chain) throws IOException {
        Map<String, byte[]> files = new LinkedHashMap<>();
        for (Bundle bundle : chain) {
            files.put(fileName(bundle), json(bundle));
        }
        writeFiles(folder, files);
    }

    private static void writeFiles(Path folder, Map<String, byte[]> files) throws IOException {
        Files.createDirectories(folder);
        StringBuilder sums = new StringBuilder();
        for (Map.Entry<String, byte[]> file : files.entrySet()) {
            Files.write(folder.resolve(file.getKey()), file.getValue());
            sums.append(TaxonomyBundles.sha256(file.getValue())).append("  ").append(file.getKey()).append('\n');
        }
        Files.writeString(folder.resolve(TaxonomyBundles.CHECKSUMS), sums.toString());
    }

    /** A bundle being derived from its base; each change returns it, {@link #build} gives the bundle. */
    public static final class Derived {

        private final Bundle base;
        private final String version;
        /** The new tree by id, in the base's order with new taxa last. */
        private final Map<String, BundleTaxon> taxa = new LinkedHashMap<>();
        private final List<Change> merges = new ArrayList<>();
        private final List<Change> splits = new ArrayList<>();
        private long nextInat;

        private Derived(Bundle base, String version) {
            this.base = base;
            this.version = version;
            base.taxa().forEach(t -> taxa.put(t.id(), t));
            nextInat = base.taxa().stream().mapToLong(BundleTaxon::inatId).max().orElse(0) + 1;
        }

        private BundleTaxon get(String id) {
            BundleTaxon t = taxa.get(id);
            if (t == null) {
                throw new IllegalArgumentException("No taxon " + id + " in the bundle being derived");
            }
            return t;
        }

        private Derived put(BundleTaxon t) {
            taxa.put(t.id(), t);
            return this;
        }

        /** A new scientific name. */
        public Derived rename(String id, String name) {
            BundleTaxon t = get(id);
            return put(new BundleTaxon(t.id(), t.parent(), t.rank(), name, t.common(), t.inatId(), t.synonyms()));
        }

        /** A new common name (null: none). */
        public Derived common(String id, String common) {
            BundleTaxon t = get(id);
            return put(new BundleTaxon(t.id(), t.parent(), t.rank(), t.name(), common, t.inatId(), t.synonyms()));
        }

        /** New bundled synonyms. */
        public Derived synonyms(String id, String... names) {
            BundleTaxon t = get(id);
            return put(new BundleTaxon(t.id(), t.parent(), t.rank(), t.name(), t.common(), t.inatId(), List.of(names)));
        }

        /** A new parent (null for a family); its subtree goes with it. */
        public Derived move(String id, String parent) {
            BundleTaxon t = get(id);
            return put(new BundleTaxon(t.id(), parent, t.rank(), t.name(), t.common(), t.inatId(), t.synonyms()));
        }

        /** A new taxon (its parent must be in the bundle). */
        public Derived add(String id, String parent, String rank, String name, String common) {
            if (taxa.containsKey(id)) {
                throw new IllegalArgumentException(id + " is already in the bundle");
            }
            return put(new BundleTaxon(id, parent, rank, name, common, nextInat++, List.of()));
        }

        /** A taxon of an earlier bundle again, as it was there (a removed species re-added keeps its id). */
        public Derived readd(BundleTaxon taxon) {
            if (taxa.containsKey(taxon.id())) {
                throw new IllegalArgumentException(taxon.id() + " is already in the bundle");
            }
            return put(taxon);
        }

        /** The taxon and everything below it are gone from the list. */
        public Derived remove(String id) {
            get(id);
            for (String gone : subtree(id)) {
                taxa.remove(gone);
            }
            return this;
        }

        /**
         * The sources are merged into the target, which must already be in the bundle (add it first when it is new): the
         * sources go, and what was under them in the bundle is now under the target.
         */
        public Derived merge(String target, String... sources) {
            get(target);
            List<String> names = new ArrayList<>();
            for (String source : sources) {
                names.add(get(source).name());
                for (BundleTaxon child : List.copyOf(taxa.values())) {
                    if (source.equals(child.parent())) {
                        move(child.id(), target);
                    }
                }
                taxa.remove(source);
            }
            merges.add(new Change(Change.Type.MERGE, List.of(sources), List.of(target),
                    String.join(", ", names) + " merged into " + get(target).name()));
            return this;
        }

        /**
         * The source is split into the targets, which must already be in the bundle (add the new ones first); the source
         * goes unless it is one of the targets (it then keeps its id).
         */
        public Derived split(String source, String... targets) {
            String name = get(source).name();
            List<String> to = List.of(targets);
            to.forEach(this::get);
            if (!to.contains(source)) {
                taxa.remove(source);
            }
            splits.add(new Change(Change.Type.SPLIT, List.of(source), to, name + " split into "
                    + to.stream().map(id -> get(id).name()).collect(Collectors.joining(", "))));
            return this;
        }

        private List<String> subtree(String id) {
            List<String> ids = new ArrayList<>(List.of(id));
            for (int i = 0; i < ids.size(); i++) {
                String parent = ids.get(i);
                taxa.values().stream().filter(t -> parent.equals(t.parent())).forEach(t -> ids.add(t.id()));
            }
            return ids;
        }

        /** The bundle: its taxa, and its change list relative to the base (as the importer orders it). */
        public Bundle build() {
            Map<String, BundleTaxon> before = new HashMap<>();
            base.taxa().forEach(t -> before.put(t.id(), t));
            Set<String> mergedOrSplit = new HashSet<>();
            Set<String> targets = new HashSet<>();
            for (Change change : merges) {
                mergedOrSplit.addAll(change.from());
                targets.addAll(change.to());
            }
            for (Change change : splits) {
                mergedOrSplit.addAll(change.from());
                targets.addAll(change.to());
            }
            List<Change> changes = new ArrayList<>(merges);
            changes.addAll(splits);
            for (BundleTaxon t : taxa.values()) {
                BundleTaxon old = before.get(t.id());
                if (old == null) {
                    if (!targets.contains(t.id())) {
                        changes.add(new Change(Change.Type.ADD, List.of(), List.of(t.id()), t.name() + " added"));
                    }
                    continue;
                }
                if (!old.name().equals(t.name())) {
                    changes.add(new Change(Change.Type.RENAME, List.of(t.id()), List.of(t.id()), old.name() + " → " + t.name()));
                }
                if (!Objects.equals(old.parent(), t.parent())) {
                    changes.add(new Change(Change.Type.MOVE, List.of(t.id()), List.of(t.id()), t.name() + ": "
                            + nameOf(old.parent()) + " → " + nameOf(t.parent())));
                }
            }
            for (BundleTaxon old : base.taxa()) {
                if (!taxa.containsKey(old.id()) && !mergedOrSplit.contains(old.id())) {
                    changes.add(new Change(Change.Type.REMOVE, List.of(old.id()), List.of(), old.name() + " removed"));
                }
            }
            changes.sort(Comparator.comparing((Change c) -> c.type().ordinal())
                    .thenComparing(c -> String.join(",", c.from()))
                    .thenComparing(c -> String.join(",", c.to())));
            for (BundleTaxon t : taxa.values()) {
                if (t.parent() != null && !taxa.containsKey(t.parent())) {
                    throw new IllegalStateException(t.id() + "'s parent " + t.parent() + " is not in the bundle");
                }
            }
            return new Bundle(base.format(), version, base.version(), version + "T00:00:00Z", base.ranks(), base.region(),
                    base.group(), base.source(), List.copyOf(taxa.values()), changes);
        }

        private String nameOf(String id) {
            if (id == null) {
                return "(top level)";
            }
            BundleTaxon t = taxa.containsKey(id) ? taxa.get(id) : base.taxa().stream().filter(b -> b.id().equals(id))
                    .findFirst().orElse(null);
            return t == null ? id : t.name();
        }
    }

    /**
     * The v2 of the real bundle for a real-app run: Euploea core renamed Euploea coreta; the genus Pachliopta (with its
     * species) moved from Troidini to Papilionini; Tirumala septentrionis merged into Tirumala limniace; Danaus
     * chrysippus split into itself and a new Danaus petilia; and a new species named {@code customName} in the genus of
     * that name (its first word) when the list has one, else in Lethe.
     */
    public static Bundle realV2(String customName) {
        Bundle real = real();
        int last = real.taxa().stream().mapToInt(t -> Integer.parseInt(t.id().substring(1))).max().orElseThrow();
        String genusName = customName.split(" ")[0];
        String genus = real.taxa().stream().filter(t -> "genus".equals(t.rank()) && t.name().equals(genusName))
                .map(BundleTaxon::id).findFirst().orElse("A258");
        return derive(real, REAL_V2)
                .rename("A21", "Euploea coreta")
                .move("A102", "A8")
                .merge("A43", "A124")
                .add("A" + (last + 1), "A4", "species", "Danaus petilia", "Lesser Wanderer")
                .split("A5", "A5", "A" + (last + 1))
                .add("A" + (last + 2), genus, "species", customName, null)
                .build();
    }

    /**
     * Writes the real bundle (as shipped) and its v2 ({@link #realV2}) with their {@code bundles.sha256} into a folder,
     * for {@code -Datlas.taxonomyDir=<folder>}. Arguments: the folder, then optionally the added species' name (by default
     * {@value #DEFAULT_CUSTOM_NAME}: add a custom species of that name first to see it matched).
     */
    public static void main(String[] args) throws IOException {
        if (args.length < 1 || args.length > 2) {
            System.err.println("Usage: UpdateBundles <folder> [\"Genus epithet\" of the added species]");
            System.exit(2);
            return;
        }
        Path folder = Path.of(args[0]).toAbsolutePath();
        String customName = args.length > 1 ? args[1] : DEFAULT_CUSTOM_NAME;
        Bundle v2 = realV2(customName);
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put(REAL, realBytes());
        files.put(fileName(v2), json(v2));
        writeFiles(folder, files);
        System.out.println("Wrote " + REAL + " and " + fileName(v2) + " (" + v2.changes().size() + " changes) with "
                + TaxonomyBundles.CHECKSUMS + " to " + folder);
        v2.changes().forEach(c -> System.out.println("  " + c.type().json() + ": " + c.detail()));
        System.out.println("Run the app with -Datlas.taxonomyDir=" + folder);
    }
}
