package com.butterflyatlas.app.taxonomy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TaxonomyBundlesTest {

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static byte[] bundle(String version, String basedOn, int format) {
        String based = basedOn == null ? "null" : "\"" + basedOn + "\"";
        return ("""
                {"format":%d,"version":"%s","basedOn":%s,"generatedAt":"2026-10-08T00:00:00Z",
                 "ranks":["family","subfamily","tribe","genus","species"],
                 "region":{"name":"India","inatPlaceId":6681},
                 "group":{"key":"butterflies","name":"Butterflies","inatTaxonId":47224},
                 "source":{"name":"iNaturalist","url":"https://www.inaturalist.org","quality":"verifiable","note":"n"},
                 "taxa":[],"changes":[]}""").formatted(format, version, based).getBytes(StandardCharsets.UTF_8);
    }

    /** A fake classpath holding the given files plus a checksum list computed from {@code listed}. */
    private static TaxonomyBundles.ResourceSource source(Map<String, byte[]> listed, Map<String, byte[]> actual)
            throws Exception {
        StringBuilder sums = new StringBuilder();
        for (Map.Entry<String, byte[]> e : listed.entrySet()) {
            sums.append(sha256(e.getValue())).append("  ").append(e.getKey()).append('\n');
        }
        Map<String, byte[]> files = new HashMap<>(actual);
        files.put(TaxonomyBundles.CHECKSUMS, sums.toString().getBytes(StandardCharsets.UTF_8));
        return name -> files.containsKey(name) ? new ByteArrayInputStream(files.get(name)) : null;
    }

    @Test
    void loadsAndVerifiesTheShippedBundles() {
        TaxonomyBundles bundles = TaxonomyBundles.fromClasspath();

        TaxonomyBundles.Shipped newest = bundles.newest();
        assertThat(newest.fileName()).isEqualTo("taxonomy-india-2026-10-08.json");
        assertThat(newest.sha256()).isEqualTo("1f8a0e5997ff5572d91c62d37ce5dc8c2c6567d397e0e834839b62f534e2c546");
        assertThat(newest.bundle().basedOn()).isNull();
        assertThat(newest.bundle().taxa()).hasSize(1604);
    }

    @Test
    void checksumListMatchesTheBundleFolderExactly() throws Exception {
        Path folder = Path.of("src/main/resources/taxonomy");
        Set<String> onDisk;
        try (Stream<Path> files = Files.list(folder)) {
            onDisk = files.map(f -> f.getFileName().toString()).filter(n -> n.endsWith(".json")).collect(Collectors.toSet());
        }
        List<String> lines = Files.readAllLines(folder.resolve(TaxonomyBundles.CHECKSUMS)).stream()
                .filter(line -> !line.isBlank()).toList();

        assertThat(lines).extracting(line -> line.substring(66)).containsExactlyInAnyOrderElementsOf(onDisk);
        for (String line : lines) {
            String name = line.substring(66);
            assertThat(line.substring(0, 64))
                    .as("checksum of %s — regenerate with: cd %s && shasum -a 256 taxonomy-*.json > %s", name, folder,
                            TaxonomyBundles.CHECKSUMS)
                    .isEqualTo(sha256(Files.readAllBytes(folder.resolve(name))));
        }
    }

    @Test
    void refusesABundleWhoseBytesChanged() throws Exception {
        byte[] original = bundle("2026-10-08", null, 1);
        byte[] edited = bundle("2026-10-09", null, 1);

        assertThatThrownBy(() -> TaxonomyBundles.load(source(Map.of("a.json", original), Map.of("a.json", edited))))
                .isInstanceOf(BundleException.class)
                .hasMessage("The taxonomy file a.json is damaged. Reinstall Butterfly Atlas.");
    }

    @Test
    void refusesAMissingBundle() throws Exception {
        assertThatThrownBy(() -> TaxonomyBundles.load(source(Map.of("a.json", bundle("2026-10-08", null, 1)), Map.of())))
                .isInstanceOf(BundleException.class)
                .hasMessage("The taxonomy file a.json is missing. Reinstall Butterfly Atlas.");
    }

    @Test
    void refusesAMalformedChecksumList() {
        byte[] sums = "not a checksum line\n".getBytes(StandardCharsets.UTF_8);
        TaxonomyBundles.ResourceSource source = name -> name.equals(TaxonomyBundles.CHECKSUMS)
                ? new ByteArrayInputStream(sums) : null;

        assertThatThrownBy(() -> TaxonomyBundles.load(source))
                .isInstanceOf(BundleException.class)
                .hasMessageContaining("line 1");
    }

    @Test
    void ordersBundlesByVersionAlongTheirChain() throws Exception {
        Map<String, byte[]> files = Map.of(
                "b.json", bundle("2026-11-01", "2026-10-08", 1),
                "a.json", bundle("2026-10-08", null, 1));

        TaxonomyBundles bundles = TaxonomyBundles.load(source(files, files));

        assertThat(bundles.all()).extracting(s -> s.bundle().version()).containsExactly("2026-10-08", "2026-11-01");
        assertThat(bundles.newest().fileName()).isEqualTo("b.json");
        assertThat(bundles.byVersion("2026-10-08")).hasValueSatisfying(s -> assertThat(s.fileName()).isEqualTo("a.json"));
    }

    @Test
    void refusesAGapInTheChain() throws Exception {
        Map<String, byte[]> files = Map.of(
                "a.json", bundle("2026-10-08", null, 1),
                "c.json", bundle("2026-12-01", "2026-11-01", 1));

        assertThatThrownBy(() -> TaxonomyBundles.load(source(files, files)))
                .isInstanceOf(BundleException.class)
                .hasMessageContaining("bundle 2026-12-01 is based on 2026-11-01");
    }

    @Test
    void refusesANewerFormat() throws Exception {
        Map<String, byte[]> files = Map.of("a.json", bundle("2026-10-08", null, 2));

        assertThatThrownBy(() -> TaxonomyBundles.load(source(files, files)))
                .isInstanceOf(BundleException.class)
                .hasMessage("The taxonomy file a.json could not be read. Reinstall Butterfly Atlas.");
    }

    private static TaxonomyBundles.ResourceSource rawSource(byte[] checksums, Map<String, byte[]> files) {
        Map<String, byte[]> all = new HashMap<>(files);
        all.put(TaxonomyBundles.CHECKSUMS, checksums);
        return name -> all.containsKey(name) ? new ByteArrayInputStream(all.get(name)) : null;
    }

    @Test
    void readsAListWithWindowsLineEndingsBlankLinesAndTheBinaryMarker() throws Exception {
        byte[] a = bundle("2026-10-08", null, 1);
        byte[] b = bundle("2026-11-01", "2026-10-08", 1);
        String list = "\uFEFF" + sha256(a) + "  a.json\r\n\r\n" + sha256(b) + " *b.json\r\n";

        TaxonomyBundles bundles = TaxonomyBundles.load(rawSource(list.getBytes(StandardCharsets.UTF_8),
                Map.of("a.json", a, "b.json", b)));

        assertThat(bundles.all()).hasSize(2);
        assertThat(bundles.newest().fileName()).isEqualTo("b.json");
    }

    @Test
    void aFolderGivesItsListedBundlesWithTheSameChecks(@TempDir Path folder) throws Exception {
        byte[] a = bundle("2026-10-08", null, 1);
        byte[] b = bundle("2026-12-01", "2026-10-08", 1);
        Files.write(folder.resolve("taxonomy-india-2026-10-08.json"), a);
        Files.write(folder.resolve("taxonomy-india-2026-12-01.json"), b);
        Files.writeString(folder.resolve("notes.txt"), "not listed, not read");
        Files.writeString(folder.resolve(TaxonomyBundles.CHECKSUMS), sha256(b) + "  taxonomy-india-2026-12-01.json\n"
                + sha256(a) + "  taxonomy-india-2026-10-08.json\n");

        TaxonomyBundles bundles = TaxonomyBundles.fromDirectory(folder);

        assertThat(bundles.all()).extracting(TaxonomyBundles.Shipped::fileName)
                .containsExactly("taxonomy-india-2026-10-08.json", "taxonomy-india-2026-12-01.json");
        assertThat(bundles.newest().bundle().basedOn()).isEqualTo("2026-10-08");

        Files.write(folder.resolve("taxonomy-india-2026-12-01.json"), bundle("2026-12-02", "2026-10-08", 1));
        assertThatThrownBy(() -> TaxonomyBundles.fromDirectory(folder)).isInstanceOf(BundleException.class)
                .hasMessage("The taxonomy file taxonomy-india-2026-12-01.json is damaged. Reinstall Butterfly Atlas.");
        Files.delete(folder.resolve("taxonomy-india-2026-12-01.json"));
        assertThatThrownBy(() -> TaxonomyBundles.fromDirectory(folder)).isInstanceOf(BundleException.class)
                .hasMessage("The taxonomy file taxonomy-india-2026-12-01.json is missing. Reinstall Butterfly Atlas.");
        assertThatThrownBy(() -> TaxonomyBundles.fromDirectory(folder.resolve("nowhere"))).isInstanceOf(BundleException.class)
                .hasMessage("The taxonomy list bundles.sha256 is missing. Reinstall Butterfly Atlas.");
    }

    @Test
    void refusesAnEmptyList() {
        assertThatThrownBy(() -> TaxonomyBundles.load(rawSource("\n".getBytes(StandardCharsets.UTF_8), Map.of())))
                .isInstanceOf(BundleException.class)
                .hasMessage("No taxonomy is bundled with this copy of Butterfly Atlas. Reinstall Butterfly Atlas.");
    }
}
