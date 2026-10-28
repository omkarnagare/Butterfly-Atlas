package com.butterflyatlas.placeimporter;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * Builds the place bundle. Run from the repository root:
 * <pre>./mvnw -pl place-importer compile exec:java -Dexec.args="--lgd &lt;districts.csv&gt;"</pre>
 * Options: {@code --lgd <file>} (LGD district list; optional), {@code --version <yyyy-MM-dd>} (default today),
 * {@code --out <dir>} (default {@code app/src/main/resources/places}), {@code --registry <file>} (default
 * {@code place-importer/place-ids.json}), {@code --cache <dir>} (default {@code place-importer/.cache}),
 * {@code --sources <dir>} (read the four source files from here instead of downloading them), {@code --based-on <json>}
 * (the place list this one follows; default the newest {@code places-*.json} in {@code --out} older than this version,
 * none for the first list), {@code --changes <csv>} (the merges, splits and retirements; default the reviewed
 * {@code place-changes.csv} inside the tool). A later list is compared with the one it is based on (5c contract §3):
 * its {@code basedOn} and {@code changes} are written, and a place gone without a line, or a line the lists disagree with,
 * stops the build with the place named.
 */
public final class PlaceImporterMain {

    private static final Set<String> OPTIONS = new TreeSet<>(Set.of("--lgd", "--version", "--out", "--registry", "--cache",
            "--sources", "--based-on", "--changes"));
    private static final java.util.regex.Pattern LIST = java.util.regex.Pattern.compile("places-(\\d{4}-\\d{2}-\\d{2})\\.json");

    record Arguments(String version, Path out, Path registry, Path cache, Path sources, Path lgd, boolean defaultPaths,
                     Path basedOn, Path changes) {
    }

    private PlaceImporterMain() {
    }

    public static void main(String[] args) {
        int exit = run(args, LocalDate.now(), Path.of("").toAbsolutePath(), System.out, System.err);
        if (exit != 0) {
            System.exit(exit);
        }
    }

    /**
     * Runs the command and returns the process exit code: 0 ok, 2 bad usage, 1 build failure. A failed check writes
     * nothing; the id registry is saved before the bundle (so ids handed out are never handed out again), and the
     * bundle is written whole or not at all.
     */
    static int run(String[] args, LocalDate today, Path cwd, PrintStream out, PrintStream err) {
        Arguments arguments;
        try {
            arguments = parse(args, today, cwd);
            checkEnvironment(arguments, cwd);
        } catch (IllegalArgumentException e) {
            err.println(e.getMessage());
            return 2;
        }
        long start = System.nanoTime();
        boolean registrySaved = false;
        boolean pinned = true;   // the override table was reviewed against the pinned sources only
        try {
            out.println("Building places " + arguments.version() + " ...");
            Path sourceDir = arguments.sources();
            if (sourceDir == null) {
                Sources.Fetcher fetcher = Sources.http();
                for (Sources.Source source : Sources.ALL) {
                    out.println("  source " + Sources.ensure(source, arguments.cache(), fetcher).getFileName());
                }
                sourceDir = arguments.cache();
            } else {
                for (Sources.Source source : Sources.ALL) {
                    Path file = sourceDir.resolve(source.file());
                    String sha = Files.isRegularFile(file) ? Sources.sha256(file) : null;
                    pinned &= source.sha256().equals(sha);
                    if (sha != null && !sha.equals(source.sha256())) {
                        err.println("Warning: " + source.file() + " in " + sourceDir + " is not the pinned file (SHA-256 "
                                + sha + ", pinned " + source.sha256() + ")");
                    }
                }
            }
            List<Lgd.District> lgd = arguments.lgd() == null ? null : Lgd.read(arguments.lgd());
            List<Lgd.Override> overrides;
            try (InputStream in = PlaceImporterMain.class.getResourceAsStream("/lgd-overrides.csv")) {
                overrides = in == null ? List.of() : Lgd.readOverrides(in);
            }
            List<SiblingNames.Override> nameOverrides;
            try (InputStream in = PlaceImporterMain.class.getResourceAsStream("/name-overrides.csv")) {
                nameOverrides = in == null ? List.of() : SiblingNames.readOverrides(in);
            }
            List<SiblingNames.Alias> extraAliases;
            try (InputStream in = PlaceImporterMain.class.getResourceAsStream("/extra-aliases.csv")) {
                extraAliases = in == null ? List.of() : SiblingNames.readAliases(in);
            }
            byte[] table;
            try (InputStream in = arguments.changes() == null
                    ? PlaceImporterMain.class.getResourceAsStream("/place-changes.csv")
                    : Files.newInputStream(arguments.changes())) {
                table = in == null ? "version,type,from,to,reason\n".getBytes(java.nio.charset.StandardCharsets.UTF_8)
                        : in.readAllBytes();
            }
            List<PlaceChanges.Line> lines = PlaceChanges.read(new java.io.ByteArrayInputStream(table), arguments.version());
            // Every merge up to this version shapes the build: a place merged once stays merged.
            Map<String, String> merged = PlaceChanges.mergedInto(PlaceChanges.readUpTo(new java.io.ByteArrayInputStream(table),
                    arguments.version()));
            Path basedOn = arguments.basedOn() != null ? arguments.basedOn() : newestBefore(arguments.out(), arguments.version());
            AtlasBuilder.Atlas atlas = AtlasBuilder.build(Sources.read(sourceDir), lgd, overrides, nameOverrides, extraAliases,
                    merged);
            PlaceIdRegistry registry = PlaceIdRegistry.load(arguments.registry());
            BundleWriter.Bundle bundle = BundleWriter.assemble(arguments.version(), atlas, registry);

            List<String> failures = new ArrayList<>(atlas.failures());
            PlaceDiff.Previous previous = basedOn == null ? null : PlaceDiff.read(basedOn);
            PlaceDiff.Result diff = null;
            if (previous != null) {
                diff = PlaceDiff.compare(previous, bundle.places(), lines, registry,
                        PlaceDiff.givenUp(arguments.out(), previous));
                failures.addAll(diff.failures());
                bundle = BundleWriter.withHistory(bundle, previous.version(), diff.changes(), diff.aliases());
            } else if (!lines.isEmpty()) {
                failures.add("place-changes.csv has lines for " + arguments.version() + ", but there is no earlier list to"
                        + " compare with");
            }
            if (pinned) {
                failures.addAll(SiblingNames.unknownOverrides(atlas.unusedNameOverrides()));
            }
            failures.addAll(BundleWriter.structure(bundle.places(), bundle.entries()));
            failures.addAll(Checks.testPoints(bundle.places(), bundle.entries(), Checks.CONTRACT_POINTS));
            Checks.size(bundle.bin().length, Checks.MAX_BIN_BYTES).ifPresent(failures::add);
            if (!failures.isEmpty()) {
                err.println("Build failed, nothing written:");
                failures.forEach(f -> err.println("  " + f));
                return 1;
            }

            String lgdName = arguments.lgd() == null ? null : arguments.lgd().getFileName().toString();
            String lgdSha = arguments.lgd() == null ? null : Sources.sha256(arguments.lgd());
            registry.save(arguments.registry());
            registrySaved = true;
            BundleWriter.write(arguments.out(), bundle, Sources.notice(arguments.version(), lgdName, lgdSha))
                    .forEach(w -> err.println("Warning: " + w));
            report(out, arguments, atlas, bundle, start);
            if (diff != null) {
                out.println("Changes since " + previous.version() + ": " + diff.counts());
                diff.changes().forEach(c -> out.println("  " + c.type() + ": " + c.detail()));
            } else {
                out.println("The first place list: nothing to compare with");
            }
            return 0;
        } catch (BundleWriter.PartialWriteException e) {
            err.println("Build failed, the bundle folder may be incomplete: " + e.getMessage());
            return 1;
        } catch (IOException | RuntimeException e) {
            err.println(registrySaved
                    ? "Build failed, the bundle was not written (the id registry " + arguments.registry()
                    + " was saved; its new ids stay reserved): " + e.getMessage()
                    : "Build failed, nothing written: " + e.getMessage());
            return 1;
        }
    }

    private static void report(PrintStream out, Arguments arguments, AtlasBuilder.Atlas atlas, BundleWriter.Bundle bundle,
                               long start) {
        out.println("Wrote " + bundle.jsonName() + " and " + bundle.binName() + " (" + String.format(Locale.ROOT, "%,d",
                bundle.bin().length) + " bytes) to " + arguments.out());
        Map<String, Integer> counts = new TreeMap<>(Map.of("country", 0, "state", 0, "district", 0));
        atlas.places().forEach(p -> counts.merge(p.level(), 1, Integer::sum));
        out.println("Places:");
        for (String level : List.of("country", "state", "district")) {
            out.println("  " + level + ": " + counts.get(level));
        }
        Map<String, AtlasBuilder.Place> byKey = new HashMap<>();
        atlas.places().forEach(p -> byKey.put(p.key(), p));
        out.println("Boundaries: " + bundle.entries().size());
        long small = atlas.gaps().stream().filter(g -> !g.assignedTo().equals(g.parent())).count();
        out.println("  gaps between the sources given to a neighbour: " + small);
        atlas.gaps().stream().filter(g -> g.assignedTo().equals(g.parent())).forEach(g -> out.println(String.format(
                Locale.ROOT, "  area without a %s, kept as an area of %s: %,.0f km² around %.2f, %.2f",
                byKey.get(g.parent()).level().equals("country") ? "state" : "district", byKey.get(g.parent()).name(),
                g.areaKm2(), g.at().y, g.at().x)));
        List<String> islands = atlas.dropped().stream().map(d -> String.format(Locale.ROOT, "%s (%d, %,.2f km²)",
                byKey.containsKey(d.key()) ? byKey.get(d.key()).name() : d.key(), d.parts(), d.areaKm2())).toList();
        out.println("  islands lost to simplification: " + atlas.dropped().stream().mapToInt(Partition.Dropped::parts).sum()
                + (islands.isEmpty() ? "" : " in " + String.join("; ", islands)));
        out.println("Places renamed to tell siblings apart (" + atlas.renames().size() + ")"
                + (atlas.renames().isEmpty() ? "" : ":"));
        for (SiblingNames.Rename r : atlas.renames()) {
            out.println("  " + r.from() + " → " + r.to() + " (" + byKey.get(r.parentKey()).name() + ", " + r.key() + ")");
        }
        out.println("Name overrides for places not in the data (" + atlas.unusedNameOverrides().size() + ")"
                + (atlas.unusedNameOverrides().isEmpty() ? "" : ": " + String.join(", ",
                atlas.unusedNameOverrides().stream().map(SiblingNames.Override::key).toList())));
        out.println("Other spellings for places not in the data (" + atlas.unusedAliases().size() + ")"
                + (atlas.unusedAliases().isEmpty() ? "" : ": " + String.join(", ",
                atlas.unusedAliases().stream().map(a -> a.key() + " " + a.alias()).toList())));
        if (atlas.lgd() == null) {
            out.println("LGD comparison skipped: no --lgd list given");
        } else {
            Lgd.Match match = atlas.lgd();
            out.println("LGD: " + match.districts().size() + " boundary districts matched");
            List<String> missing = match.lgdWithoutBoundary().stream()
                    .map(d -> Names.tidy(d.name()) + " (" + Names.tidy(d.stateName()) + ", " + d.code() + ")").toList();
            out.println("LGD districts without a boundary (" + missing.size() + ")"
                    + (missing.isEmpty() ? "" : ": " + String.join("; ", missing)));
            List<String> unmatched = match.boundariesWithoutLgd().stream()
                    .map(b -> b.name() + " (" + byKey.get("iso:" + b.states().get(0)).name() + ")").toList();
            out.println("Boundary districts without an LGD match (" + unmatched.size() + ")"
                    + (unmatched.isEmpty() ? "" : ": " + String.join("; ", unmatched)));
            List<String> unused = match.overridesWithoutLgd().stream()
                    .map(o -> o.stateIso() + " " + o.boundaryName() + " → " + o.lgdCode()).toList();
            out.println("LGD overrides whose code is not in the list (" + unused.size() + ")"
                    + (unused.isEmpty() ? "" : ": " + String.join("; ", unused)));
            if (!match.statesWithoutLgd().isEmpty()) {
                out.println("States without an LGD match: " + String.join(", ", match.statesWithoutLgd()));
            }
        }
        out.println(String.format(Locale.ROOT, "Took %.1f s", (System.nanoTime() - start) / 1e9));
    }

    static Arguments parse(String[] args, LocalDate today, Path cwd) {
        Map<String, String> values = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            String key = args[i];
            if (!OPTIONS.contains(key)) {
                throw new IllegalArgumentException("Unknown option " + key + ". Known options:\n  " + String.join("\n  ", OPTIONS));
            }
            if (i + 1 >= args.length) {
                throw new IllegalArgumentException(key + " needs a value");
            }
            if (values.put(key, args[++i]) != null) {
                throw new IllegalArgumentException(key + " is given twice");
            }
        }
        String version = values.getOrDefault("--version", today.toString());
        if (!version.matches("\\d{4}-\\d{2}-\\d{2}") || !isDate(version)) {
            throw new IllegalArgumentException("--version must be a date like yyyy-MM-dd (e.g. 2026-10-13), got: " + version);
        }
        boolean defaultPaths = !values.containsKey("--out") && !values.containsKey("--registry")
                && !values.containsKey("--cache") && !values.containsKey("--sources");
        return new Arguments(version,
                cwd.resolve(values.getOrDefault("--out", "app/src/main/resources/places")),
                cwd.resolve(values.getOrDefault("--registry", "place-importer/place-ids.json")),
                cwd.resolve(values.getOrDefault("--cache", "place-importer/.cache")),
                values.containsKey("--sources") ? cwd.resolve(values.get("--sources")) : null,
                values.containsKey("--lgd") ? cwd.resolve(values.get("--lgd")) : null,
                defaultPaths,
                values.containsKey("--based-on") ? cwd.resolve(values.get("--based-on")) : null,
                values.containsKey("--changes") ? cwd.resolve(values.get("--changes")) : null);
    }

    private static boolean isDate(String text) {
        try {
            LocalDate.parse(text);
            return true;
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    /** The newest place list in {@code out} older than {@code version}; null when there is none (the first list). */
    static Path newestBefore(Path out, String version) throws IOException {
        if (!Files.isDirectory(out)) {
            return null;
        }
        Path newest = null;
        try (Stream<Path> files = Files.list(out)) {
            for (Path file : files.sorted().toList()) {
                java.util.regex.Matcher m = LIST.matcher(file.getFileName().toString());
                if (m.matches() && m.group(1).compareTo(version) < 0) {
                    newest = file;   // sorted by name, so by date: the last match is the newest
                }
            }
        }
        return newest;
    }

    /** Guards against running from the wrong directory or without the id registry. */
    static void checkEnvironment(Arguments arguments, Path cwd) {
        if (arguments.defaultPaths() && !Files.exists(cwd.resolve("place-importer/pom.xml"))) {
            throw new IllegalArgumentException("Run this from the repository root (place-importer/pom.xml not found in " + cwd + ")");
        }
        if (arguments.lgd() != null && !Files.isRegularFile(arguments.lgd())) {
            throw new IllegalArgumentException("--lgd: no file " + arguments.lgd());
        }
        if (arguments.basedOn() != null && !Files.isRegularFile(arguments.basedOn())) {
            throw new IllegalArgumentException("--based-on: no file " + arguments.basedOn());
        }
        if (arguments.changes() != null && !Files.isRegularFile(arguments.changes())) {
            throw new IllegalArgumentException("--changes: no file " + arguments.changes());
        }
        if (!Files.exists(arguments.registry()) && Files.isDirectory(arguments.out())) {
            try (Stream<Path> files = Files.list(arguments.out())) {
                if (files.anyMatch(f -> f.getFileName().toString().matches("places-\\d{4}-\\d{2}-\\d{2}\\.json"))) {
                    throw new IllegalArgumentException("Found existing place bundles but no id registry at "
                            + arguments.registry() + "; restore it from git before building");
                }
            } catch (IOException e) {
                throw new IllegalArgumentException("Cannot list " + arguments.out() + ": " + e.getMessage());
            }
        }
    }
}
