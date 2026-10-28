package com.butterflyatlas.app;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * No package cycle among the core packages ({@link CorePackagesTest#corePackages()}), so each can be read and changed
 * on its own (3c-3 design §2 item 10). A package's dependencies are the core packages its main sources import
 * (a subpackage counts as its package), by import or fully qualified name in code (not comments). Two older cycles, outside the 3c-3 split, are named in {@link #KNOWN}: a new
 * dependency closing any other cycle fails, and so does a known one that is gone (then remove it from the list).
 */
class PackageCyclesTest {

    private static final Path ROOT = Path.of("src/main/java/com/butterflyatlas/app");
    /** A reference to a package of the app, by import or fully qualified name. */
    private static final Pattern REFERENCE = Pattern.compile("\\bcom\\.butterflyatlas\\.app\\.([a-z]\\w*)\\.");
    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
    private static final Pattern LINE_COMMENT = Pattern.compile("(?<!:)//[^\\n]*");
    private static final Pattern PACKAGE_LINE = Pattern.compile("^package\\s[^;]*;", Pattern.MULTILINE);
    /**
     * Dependencies that close cycles older than 3c-3, left for later: photos → taxa (filing names photos from
     * {@code taxa.TaxonSnapshot}, while taxa shows photos) and taxonomy → photos ({@code photos.FileNames} for folder
     * names, while photos reach taxonomy through library and taxa).
     */
    private static final Set<String> KNOWN = Set.of("photos -> taxa", "taxonomy -> photos");

    /** Each core package's dependencies on the other core packages, read from the import lines of its main sources. */
    static Map<String, Set<String>> dependencies() throws IOException {
        assertThat(ROOT).as("run from the app module (the sources are at " + ROOT + ")").isDirectory();
        List<String> core = CorePackagesTest.corePackages();
        Map<String, Set<String>> graph = new TreeMap<>();
        for (String pkg : core) {
            Set<String> uses = new TreeSet<>();
            try (Stream<Path> files = Files.walk(ROOT.resolve(pkg))) {
                for (Path source : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                    for (String used : referencedPackages(Files.readString(source))) {
                        if (!used.equals(pkg) && core.contains(used)) {
                            uses.add(used);
                        }
                    }
                }
            }
            graph.put(pkg, uses);
        }
        return graph;
    }

    /**
     * The app packages a source text refers to in code, by import or fully qualified name; the {@code package} line and
     * comments (javadoc too) do not count.
     */
    static Set<String> referencedPackages(String source) {
        String code = BLOCK_COMMENT.matcher(source).replaceAll(" ");
        code = LINE_COMMENT.matcher(code).replaceAll("");
        code = PACKAGE_LINE.matcher(code).replaceAll("");
        Set<String> found = new TreeSet<>();
        Matcher m = REFERENCE.matcher(code);
        while (m.find()) {
            found.add(m.group(1));
        }
        return found;
    }

    /** Each package of each cycle with the dependencies it has on the other members, for a failure message. */
    static String describe(List<Set<String>> cycles, Map<String, Set<String>> graph) {
        StringBuilder out = new StringBuilder();
        for (Set<String> cycle : cycles) {
            out.append("\ncycle ").append(cycle).append(':');
            for (String pkg : cycle) {
                Set<String> edges = new TreeSet<>(graph.getOrDefault(pkg, Set.of()));
                edges.retainAll(cycle);
                out.append("\n  ").append(pkg).append(" -> ").append(edges);
            }
        }
        return out.toString();
    }

    /** The cycles of a graph: each group of two or more nodes that reach one another (sorted), found by Tarjan's method. */
    static List<Set<String>> cycles(Map<String, Set<String>> graph) {
        Map<String, Integer> index = new HashMap<>();
        Map<String, Integer> low = new HashMap<>();
        Deque<String> stack = new ArrayDeque<>();
        Set<String> onStack = new TreeSet<>();
        List<Set<String>> cycles = new ArrayList<>();
        for (String node : graph.keySet()) {
            if (!index.containsKey(node)) {
                connect(node, graph, index, low, stack, onStack, cycles);
            }
        }
        return cycles;
    }

    private static void connect(String node, Map<String, Set<String>> graph, Map<String, Integer> index,
                                Map<String, Integer> low, Deque<String> stack, Set<String> onStack, List<Set<String>> cycles) {
        index.put(node, index.size());
        low.put(node, index.get(node));
        stack.push(node);
        onStack.add(node);
        for (String next : graph.getOrDefault(node, Set.of())) {
            if (!index.containsKey(next)) {
                connect(next, graph, index, low, stack, onStack, cycles);
                low.put(node, Math.min(low.get(node), low.get(next)));
            } else if (onStack.contains(next)) {
                low.put(node, Math.min(low.get(node), index.get(next)));
            }
        }
        if (low.get(node).equals(index.get(node))) {
            Set<String> group = new TreeSet<>();
            String member;
            do {
                member = stack.pop();
                onStack.remove(member);
                group.add(member);
            } while (!member.equals(node));
            if (group.size() > 1) {
                cycles.add(group);
            }
        }
    }

    /** The graph without the {@link #KNOWN} dependencies. */
    private static Map<String, Set<String>> withoutKnown(Map<String, Set<String>> graph) {
        Map<String, Set<String>> rest = new TreeMap<>();
        graph.forEach((pkg, uses) -> {
            Set<String> kept = new TreeSet<>(uses);
            kept.removeIf(use -> KNOWN.contains(pkg + " -> " + use));
            rest.put(pkg, kept);
        });
        return rest;
    }

    @Test
    void theCycleFinderFindsEveryCycleAndNothingElse() {
        Map<String, Set<String>> graph = Map.of("a", Set.of("b"), "b", Set.of("c"), "c", Set.of("a"), "d", Set.of("a", "e"),
                "e", Set.of("d"), "f", Set.of("a"));

        assertThat(cycles(graph)).containsExactlyInAnyOrder(Set.of("a", "b", "c"), Set.of("d", "e"));
        assertThat(cycles(Map.of("a", Set.of("b"), "b", Set.of("c"), "c", Set.of()))).isEmpty();
    }

    @Test
    void importsAndFullyQualifiedNamesCountButCommentsAndThePackageLineDoNot() {
        String source = """
                package com.butterflyatlas.app.photos;

                import com.butterflyatlas.app.library.Library;
                import static com.butterflyatlas.app.db.Database.Work;

                /** See {@link com.butterflyatlas.app.search.Searcher} and com.butterflyatlas.app.config.Config. */
                class Sample {
                    // com.butterflyatlas.app.imports.Imports is only mentioned here
                    /* com.butterflyatlas.app.places.Places neither */
                    private final com.butterflyatlas.app.taxa.TaxonSnapshot snapshot = null;
                    String url = "http://example.org"; com.butterflyatlas.app.taxonomy.Taxonomy t;
                }
                """;

        assertThat(referencedPackages(source)).containsExactlyInAnyOrder("library", "db", "taxa", "taxonomy");
    }

    @Test
    void aCycleFailureNamesEachPackageAndItsEdgesToTheOtherMembers() {
        Map<String, Set<String>> graph = Map.of("a", Set.of("b", "x"), "b", Set.of("a"), "x", Set.of());

        assertThat(describe(cycles(graph), graph)).contains("cycle [a, b]:").contains("a -> [b]").contains("b -> [a]")
                .doesNotContain("x");
    }

    @Test
    void everyCorePackageIsRead() throws Exception {
        assertThat(dependencies().keySet()).containsAll(List.of("config", "db", "taxonomy", "library", "taxa", "search",
                "photos", "imports", "places", "filters", "exports", "custom", "updates"));
    }

    @Test
    void coreHasNoPackageCycleBeyondTheKnownOnes() throws Exception {
        Map<String, Set<String>> graph = dependencies();

        Map<String, Set<String>> rest = withoutKnown(graph);
        List<Set<String>> found = cycles(rest);

        assertThat(found).as("package cycles:" + describe(found, rest)).isEmpty();
    }

    @Test
    void theKnownCyclesAreStillThere() throws Exception {
        Map<String, Set<String>> graph = dependencies();

        for (String known : KNOWN) {
            String[] edge = known.split(" -> ");
            assertThat(graph.get(edge[0])).as(known + " is gone: remove it from KNOWN").contains(edge[1]);
            Map<String, Set<String>> alone = withoutKnown(graph);
            alone.get(edge[0]).add(edge[1]);
            assertThat(cycles(alone)).as(known + " closes a cycle (else remove it from KNOWN)")
                    .anySatisfy(cycle -> assertThat(cycle).contains(edge[0], edge[1]));
        }
    }

    @Test
    void noCorePackageDependsOnUpdates() throws Exception {
        dependencies().forEach((pkg, uses) -> assertThat(uses).as(pkg).doesNotContain("updates"));
    }

    @Test
    void placesUseNeitherPhotosNorImportsNorTheLibrary() throws Exception {
        assertThat(dependencies().get("places")).doesNotContain("photos", "imports", "library");
    }
}
