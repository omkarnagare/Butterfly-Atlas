package com.butterflyatlas.app.taxonomy;

import com.butterflyatlas.app.taxonomy.bundle.Bundle;
import com.butterflyatlas.app.taxonomy.bundle.BundleReader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The taxonomy bundles shipped inside the app (docs/PLAN.md §5.2). {@code bundles.sha256} lists
 * them; every listed file must exist and match its SHA-256, and their {@code basedOn} links must
 * form one unbroken chain, oldest first.
 */
public final class TaxonomyBundles {

    public static final String DIRECTORY = "/taxonomy/";
    public static final String CHECKSUMS = "bundles.sha256";

    private static final Pattern LINE = Pattern.compile("^([0-9a-f]{64}) [ *]([^/\\\\]+)$");

    /** Opens a named file next to the bundles, or returns null if there is none. */
    @FunctionalInterface
    public interface ResourceSource {
        InputStream open(String name) throws IOException;
    }

    /** One verified bundle with the file it came from. */
    public record Shipped(String fileName, String sha256, Bundle bundle) {
    }

    private record Entry(String sha256, String fileName) {
    }

    private final List<Shipped> bundles;

    private TaxonomyBundles(List<Shipped> bundles) {
        this.bundles = List.copyOf(bundles);
    }

    public static TaxonomyBundles fromClasspath() {
        return load(name -> TaxonomyBundles.class.getResourceAsStream(DIRECTORY + name));
    }

    /**
     * The bundles in {@code folder} instead of the classpath (developer only, {@code -Datlas.taxonomyDir}; design D10):
     * its {@code bundles.sha256} and the bundles it lists, with the same checks. Names in the list cannot leave the folder
     * (they hold no {@code /} or {@code \}).
     */
    public static TaxonomyBundles fromDirectory(Path folder) {
        return load(name -> {
            Path file = folder.resolve(name);
            return Files.isRegularFile(file) ? Files.newInputStream(file) : null;
        });
    }

    public static TaxonomyBundles load(ResourceSource source) {
        List<Shipped> shipped = new ArrayList<>();
        for (Entry entry : readChecksums(source)) {
            shipped.add(verify(source, entry.fileName(), entry.sha256()));
        }
        if (shipped.isEmpty()) {
            throw new BundleException("No taxonomy is bundled with this copy of Butterfly Atlas. Reinstall Butterfly Atlas.");
        }
        // Versions are zero-padded ISO dates (yyyy-MM-dd), so text order is date order.
        shipped.sort(Comparator.comparing(s -> s.bundle().version()));
        checkChain(shipped);
        return new TaxonomyBundles(shipped);
    }

    public Shipped newest() {
        return bundles.getLast();
    }

    /** All bundles, oldest first. */
    public List<Shipped> all() {
        return bundles;
    }

    public Optional<Shipped> byVersion(String version) {
        return bundles.stream().filter(s -> s.bundle().version().equals(version)).findFirst();
    }

    public static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is part of every Java runtime", e);
        }
    }

    private static List<Entry> readChecksums(ResourceSource source) {
        byte[] bytes = readAll(source, CHECKSUMS);
        if (bytes == null) {
            throw new BundleException("The taxonomy list " + CHECKSUMS + " is missing. Reinstall Butterfly Atlas.");
        }
        List<Entry> entries = new ArrayList<>();
        String text = new String(bytes, StandardCharsets.UTF_8);
        if (text.startsWith("\uFEFF")) {
            text = text.substring(1);
        }
        String[] lines = text.split("\r?\n");
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].isBlank()) {
                continue;
            }
            Matcher m = LINE.matcher(lines[i].strip());
            if (!m.matches()) {
                throw new BundleException("The taxonomy list " + CHECKSUMS + " is damaged at line " + (i + 1)
                        + ". Reinstall Butterfly Atlas.");
            }
            entries.add(new Entry(m.group(1), m.group(2)));
        }
        return entries;
    }

    private static Shipped verify(ResourceSource source, String fileName, String expectedSha) {
        byte[] bytes = readAll(source, fileName);
        if (bytes == null) {
            throw new BundleException("The taxonomy file " + fileName + " is missing. Reinstall Butterfly Atlas.");
        }
        String actual = sha256(bytes);
        if (!actual.equals(expectedSha)) {
            throw new BundleException("The taxonomy file " + fileName + " is damaged. Reinstall Butterfly Atlas.");
        }
        try {
            return new Shipped(fileName, actual, BundleReader.read(bytes));
        } catch (IOException e) {
            throw new BundleException("The taxonomy file " + fileName + " could not be read. Reinstall Butterfly Atlas.", e);
        }
    }

    private static byte[] readAll(ResourceSource source, String name) {
        try (InputStream in = source.open(name)) {
            return in == null ? null : in.readAllBytes();
        } catch (IOException e) {
            throw new BundleException("The taxonomy file " + name + " could not be read. Reinstall Butterfly Atlas.", e);
        }
    }

    private static void checkChain(List<Shipped> oldestFirst) {
        Set<String> versions = new HashSet<>();
        for (int i = 0; i < oldestFirst.size(); i++) {
            Bundle bundle = oldestFirst.get(i).bundle();
            if (!versions.add(bundle.version())) {
                throw new BundleException("The taxonomy bundles shipped with this copy are inconsistent (two share version "
                        + bundle.version() + "). Reinstall Butterfly Atlas.");
            }
            String expected = i == 0 ? null : oldestFirst.get(i - 1).bundle().version();
            if (!Objects.equals(bundle.basedOn(), expected)) {
                throw new BundleException("The taxonomy bundles shipped with this copy are inconsistent (bundle "
                        + bundle.version() + " is based on " + bundle.basedOn() + ", expected " + expected
                        + "). Reinstall Butterfly Atlas.");
            }
        }
    }
}
