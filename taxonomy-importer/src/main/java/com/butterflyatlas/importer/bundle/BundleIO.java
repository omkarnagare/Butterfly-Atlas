package com.butterflyatlas.importer.bundle;

import com.butterflyatlas.importer.taxonomy.TaxonomyBuilder;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Reads and writes bundle files ({@code taxonomy-<region>-<yyyy-MM-dd>.json}). */
public final class BundleIO {

    private static final ObjectMapper JSON = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private static final ObjectWriter WRITER = JSON.writer(prettyPrinter());

    private static final Pattern VERSION = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

    private static final Comparator<BundleTaxon> ORDER = Comparator
            .comparingInt((BundleTaxon t) -> TaxonomyBuilder.RANKS.indexOf(t.rank()))
            .thenComparing(BundleTaxon::name)
            .thenComparing(BundleTaxon::id);

    private BundleIO() {
    }

    private static DefaultPrettyPrinter prettyPrinter() {
        DefaultIndenter indenter = new DefaultIndenter("  ", "\n");
        DefaultPrettyPrinter printer = new DefaultPrettyPrinter();
        printer.indentObjectsWith(indenter);
        printer.indentArraysWith(indenter);
        return printer;
    }

    /** Lower-case, runs of non-alphanumerics become a single "-", no leading/trailing "-". */
    static String slug(String region) {
        return region.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
    }

    public static String fileName(String region, String version) {
        if (!VERSION.matcher(version).matches()) {
            throw new IllegalArgumentException("Bundle version must be yyyy-MM-dd: " + version);
        }
        return "taxonomy-" + slug(region) + "-" + version + ".json";
    }

    public static void write(Path file, Bundle bundle) throws IOException {
        for (BundleTaxon t : bundle.taxa()) {
            if (t.id() == null || t.name() == null) {
                throw new IllegalArgumentException("Bundle taxon has null id or name: " + t);
            }
            if (!TaxonomyBuilder.RANKS.contains(t.rank())) {
                throw new IllegalArgumentException("Bundle taxon " + t.id() + " has unknown rank: " + t.rank());
            }
        }
        checkIntegrity(bundle.taxa());
        List<BundleTaxon> sorted = bundle.taxa().stream()
                .map(t -> new BundleTaxon(t.id(), t.parent(), t.rank(), t.name(), t.common(), t.inatId(),
                        t.synonyms().stream().sorted().toList()))
                .sorted(ORDER)
                .toList();
        Bundle ordered = new Bundle(bundle.format(), bundle.version(), bundle.basedOn(), bundle.generatedAt(),
                bundle.ranks(), bundle.region(), bundle.group(), bundle.source(), sorted, bundle.changes());
        String content = WRITER.writeValueAsString(ordered) + "\n";

        Path absolute = file.toAbsolutePath();
        Path parent = absolute.getParent();
        Files.createDirectories(parent);
        Path temp = parent.resolve(absolute.getFileName() + ".tmp");
        try {
            Files.writeString(temp, content, StandardCharsets.UTF_8);
            Files.move(temp, absolute, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static void checkIntegrity(List<BundleTaxon> taxa) {
        Set<String> ids = new HashSet<>();
        Set<Long> inatIds = new HashSet<>();
        for (BundleTaxon t : taxa) {
            if (!ids.add(t.id())) {
                throw new IllegalArgumentException("Atlas id " + t.id() + " appears more than once");
            }
            if (!inatIds.add(t.inatId())) {
                throw new IllegalArgumentException("iNaturalist id " + t.inatId() + " appears more than once");
            }
        }
        for (BundleTaxon t : taxa) {
            boolean family = "family".equals(t.rank());
            if (family && t.parent() != null) {
                throw new IllegalArgumentException("Family " + t.id() + " must not have a parent");
            }
            if (!family && t.parent() == null) {
                throw new IllegalArgumentException("Taxon " + t.id() + " (" + t.rank() + ") must have a parent");
            }
            if (t.parent() != null && !ids.contains(t.parent())) {
                throw new IllegalArgumentException("Taxon " + t.id() + " has parent " + t.parent()
                        + ", which is not in the bundle");
            }
        }
    }

    public static Bundle read(Path file) throws IOException {
        Bundle bundle = JSON.readValue(file.toFile(), Bundle.class);
        if (bundle.format() > Bundle.FORMAT) {
            throw new IOException("Bundle " + file + " uses format " + bundle.format()
                    + "; this version understands up to " + Bundle.FORMAT);
        }
        return bundle;
    }

    /** The newest bundle for {@code region} whose version sorts before {@code version}. */
    public static Optional<Bundle> previous(Path directory, String region, String version) throws IOException {
        if (!Files.isDirectory(directory)) {
            return Optional.empty();
        }
        Pattern name = Pattern.compile("^taxonomy-" + Pattern.quote(slug(region)) + "-(\\d{4}-\\d{2}-\\d{2})\\.json$");
        Optional<Path> newest;
        try (Stream<Path> files = Files.list(directory)) {
            newest = files
                    .filter(file -> {
                        Matcher m = name.matcher(file.getFileName().toString());
                        return m.matches() && m.group(1).compareTo(version) < 0;
                    })
                    .max(Comparator.comparing(file -> file.getFileName().toString()));
        }
        if (newest.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(read(newest.get()));
        } catch (IOException e) {
            throw new IOException("Cannot read previous bundle " + newest.get(), e);
        }
    }
}
