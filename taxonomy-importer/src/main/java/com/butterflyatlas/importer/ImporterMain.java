package com.butterflyatlas.importer;

import com.butterflyatlas.importer.bundle.BundleIO;
import com.butterflyatlas.importer.inat.CachingTransport;
import com.butterflyatlas.importer.inat.HttpTransport;
import com.butterflyatlas.importer.inat.InatClient;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * Builds a taxonomy bundle from iNaturalist. Run from the repository root:
 * <pre>./mvnw -pl taxonomy-importer compile exec:java [-Dexec.args="--quality research --version 2026-11-08"]</pre>
 */
public final class ImporterMain {

    static final String USER_AGENT =
            "ButterflyAtlas-taxonomy-importer/0.1 (+https://github.com/omkarnagare/Butterfly-Atlas)";
    private static final Set<String> OPTIONS = new TreeSet<>(Set.of("--place", "--region", "--taxon", "--group", "--group-name",
            "--quality", "--version", "--out", "--registry"));

    record Arguments(Importer.Options options, Path cacheDirectory, boolean defaultPaths) {
    }

    private ImporterMain() {
    }

    public static void main(String[] args) {
        int exit = run(args, LocalDate.now(), Path.of("").toAbsolutePath(), System.out, System.err);
        if (exit != 0) {
            System.exit(exit);
        }
    }

    /** Runs the command and returns the process exit code: 0 ok, 2 bad usage, 1 import failure. */
    static int run(String[] args, LocalDate today, Path cwd, PrintStream out, PrintStream err) {
        Arguments arguments;
        try {
            arguments = parse(args, today);
            checkEnvironment(arguments, cwd);
        } catch (IllegalArgumentException e) {
            err.println(e.getMessage());
            return 2;
        }
        try {
            Importer.Options options = arguments.options();
            InatClient inat = new InatClient(
                    new CachingTransport(HttpTransport.create(USER_AGENT), arguments.cacheDirectory()),
                    options.placeId());
            out.println("Importing " + options.groupName() + " of " + options.regionName()
                    + " (" + options.quality().description() + ") as version " + options.version() + " ...");
            Importer.Summary summary = new Importer(inat, Clock.systemUTC()).run(options);
            out.println("Wrote " + summary.file());
            summary.countsByRank().forEach((rank, count) -> out.println("  " + rank + ": " + count));
            out.println("  changes since previous bundle: " + summary.changeCount());
            summary.changes().forEach(change -> out.println("  " + change.type().json() + ": " + change.detail()));
            if (summary.previousCount() > 0 && summary.retiredCount() * 5 > summary.previousCount()) {
                out.println("WARNING: " + summary.retiredCount() + " of " + summary.previousCount()
                        + " taxa in the previous bundle are no longer present (more than 20%). "
                        + "Check the changes above before committing.");
            }
            return 0;
        } catch (IOException | RuntimeException e) {
            err.println("Import failed: " + e.getMessage());
            return 1;
        }
    }

    /** Guards against running from the wrong directory or without the id registry. */
    static void checkEnvironment(Arguments arguments, Path cwd) {
        if (arguments.defaultPaths() && !Files.exists(cwd.resolve("taxonomy-importer/pom.xml"))) {
            throw new IllegalArgumentException(
                    "Run this from the repository root (taxonomy-importer/pom.xml not found in " + cwd + ")");
        }
        Importer.Options options = arguments.options();
        if (!Files.exists(options.registryFile()) && hasBundleFor(options)) {
            throw new IllegalArgumentException("Found existing bundles but no Atlas id registry at "
                    + options.registryFile() + "; restore it from git before importing");
        }
    }

    private static boolean hasBundleFor(Importer.Options options) {
        Path dir = options.outputDirectory();
        if (!Files.isDirectory(dir)) {
            return false;
        }
        String file = BundleIO.fileName(options.regionName(), "2000-01-01");
        String prefix = file.substring(0, file.length() - "2000-01-01.json".length());
        try (Stream<Path> files = Files.list(dir)) {
            return files.anyMatch(f -> {
                String name = f.getFileName().toString();
                return name.startsWith(prefix) && name.endsWith(".json")
                        && name.length() == prefix.length() + "2000-01-01.json".length();
            });
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot list " + dir + ": " + e.getMessage());
        }
    }

    static Arguments parse(String[] args, LocalDate today) {
        Map<String, String> values = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            String key = args[i];
            if (!OPTIONS.contains(key)) {
                throw new IllegalArgumentException("Unknown option " + key + ". Known options:\n  "
                        + String.join("\n  ", OPTIONS));
            }
            if (i + 1 >= args.length) {
                throw new IllegalArgumentException(key + " needs a value");
            }
            values.put(key, args[++i]);
        }
        String version = values.getOrDefault("--version", today.toString());
        if (!version.matches("\\d{4}-\\d{2}-\\d{2}")) {
            throw new IllegalArgumentException("--version must be a date like yyyy-MM-dd (e.g. 2026-10-08), got: " + version);
        }
        Importer.Options options = new Importer.Options(
                positiveNumber(values, "--place", "6681"),
                values.getOrDefault("--region", "India"),
                positiveNumber(values, "--taxon", "47224"),
                values.getOrDefault("--group", "butterflies"),
                values.getOrDefault("--group-name", "Butterflies"),
                Quality.parse(values.getOrDefault("--quality", "verifiable")),
                version,
                Path.of(values.getOrDefault("--out", "app/src/main/resources/taxonomy")),
                Path.of(values.getOrDefault("--registry", "taxonomy-importer/atlas-ids.json")));
        Path registryFile = options.registryFile();
        Path registryDirectory = registryFile.getParent() == null ? Path.of("") : registryFile.getParent();
        Path cacheDirectory = registryDirectory.resolve(".cache").resolve(version);
        boolean defaultPaths = !values.containsKey("--out") && !values.containsKey("--registry");
        return new Arguments(options, cacheDirectory, defaultPaths);
    }

    private static long positiveNumber(Map<String, String> values, String key, String fallback) {
        String value = values.getOrDefault(key, fallback);
        try {
            long number = Long.parseLong(value);
            if (number > 0) {
                return number;
            }
        } catch (NumberFormatException ignored) {
            // reported below
        }
        throw new IllegalArgumentException(key + " must be a positive whole number, got: " + value);
    }
}
