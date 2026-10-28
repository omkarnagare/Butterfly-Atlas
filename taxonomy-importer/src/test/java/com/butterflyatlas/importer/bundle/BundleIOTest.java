package com.butterflyatlas.importer.bundle;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BundleIOTest {

    @TempDir
    Path dir;

    static Bundle sample(String version, List<BundleTaxon> taxa, List<Change> changes) {
        return new Bundle(Bundle.FORMAT, version, null, "2026-10-08T00:00:00Z", List.of("family"),
                new Bundle.Region("India", 6681), new Bundle.Group("butterflies", "Butterflies", 47224),
                new Bundle.Source("iNaturalist", "https://www.inaturalist.org", "research", "Taxonomy data from iNaturalist."),
                taxa, changes);
    }

    private static final List<BundleTaxon> TAXA = List.of(
            new BundleTaxon("A3", "A1", "species", "Danaus chrysippus", "Plain Tiger", 67835, List.of("Limnas klugii")),
            new BundleTaxon("A1", null, "family", "Nymphalidae", "Brush-footed Butterflies", 47922, List.of()),
            new BundleTaxon("A2", null, "family", "Lycaenidae", null, 47923, List.of()));

    @Test
    void roundTripsABundle() throws Exception {
        Bundle bundle = sample("2026-10-08", TAXA,
                List.of(new Change(Change.Type.RENAME, List.of("A3"), List.of("A3"), "Old → New")));
        Path file = dir.resolve("taxonomy-india-2026-10-08.json");

        BundleIO.write(file, bundle);
        Bundle read = BundleIO.read(file);

        assertThat(read.version()).isEqualTo("2026-10-08");
        assertThat(read.changes()).containsExactlyElementsOf(bundle.changes());
        assertThat(read.taxa()).containsExactlyInAnyOrderElementsOf(TAXA);
    }

    @Test
    void sortsTaxaByRankThenNameAndUsesLowercaseChangeTypes() throws Exception {
        Path file = dir.resolve("taxonomy-india-2026-10-08.json");

        BundleIO.write(file, sample("2026-10-08", TAXA,
                List.of(new Change(Change.Type.MERGE, List.of("A2"), List.of("A1"), "x"))));

        assertThat(BundleIO.read(file).taxa()).extracting(BundleTaxon::name)
                .containsExactly("Lycaenidae", "Nymphalidae", "Danaus chrysippus");
        String json = Files.readString(file);
        assertThat(json).contains("\"type\" : \"merge\"");
        assertThat(json).doesNotContain("\"common\" : null");
    }

    @Test
    void namesBundleFilesByRegionAndVersion() {
        assertThat(BundleIO.fileName("India", "2026-10-08")).isEqualTo("taxonomy-india-2026-10-08.json");
    }

    @Test
    void findsTheNewestBundleOlderThanTheOneBeingBuilt() throws Exception {
        BundleIO.write(dir.resolve("taxonomy-india-2026-09-08.json"), sample("2026-09-08", TAXA, List.of()));
        BundleIO.write(dir.resolve("taxonomy-india-2026-10-08.json"), sample("2026-10-08", TAXA, List.of()));
        Files.writeString(dir.resolve("notes.txt"), "ignore me");

        assertThat(BundleIO.previous(dir, "India", "2026-11-08")).get().extracting(Bundle::version).isEqualTo("2026-10-08");
        assertThat(BundleIO.previous(dir, "India", "2026-10-08")).get().extracting(Bundle::version).isEqualTo("2026-09-08");
        assertThat(BundleIO.previous(dir, "India", "2026-09-08")).isEmpty();
        assertThat(BundleIO.previous(dir.resolve("missing"), "India", "2026-09-08")).isEmpty();
    }

    @Test
    void rejectsTaxaWithUnknownRankOrMissingFields() {
        Path file = dir.resolve("taxonomy-india-2026-10-08.json");
        BundleTaxon badRank = new BundleTaxon("A9", null, "subtribe", "X y", null, 1, List.of());
        assertThatThrownBy(() -> BundleIO.write(file, sample("2026-10-08", List.of(badRank), List.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Bundle taxon A9 has unknown rank: subtribe");
        BundleTaxon noName = new BundleTaxon("A8", null, "family", null, null, 1, List.of());
        assertThatThrownBy(() -> BundleIO.write(file, sample("2026-10-08", List.of(noName), List.of())))
                .isInstanceOf(IllegalArgumentException.class);
        BundleTaxon noId = new BundleTaxon(null, null, "family", "N", null, 1, List.of());
        assertThatThrownBy(() -> BundleIO.write(file, sample("2026-10-08", List.of(noId), List.of())))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(file).doesNotExist();
    }

    @Test
    void leavesNoTempFileBehind() throws Exception {
        BundleIO.write(dir.resolve("taxonomy-india-2026-10-08.json"), sample("2026-10-08", TAXA, List.of()));
        try (var files = Files.list(dir)) {
            assertThat(files.map(f -> f.getFileName().toString()).toList())
                    .containsExactly("taxonomy-india-2026-10-08.json");
        }
    }

    @Test
    void ignoresUnknownPropertiesAndDefaultsMissingLists() throws Exception {
        Path file = dir.resolve("taxonomy-india-2026-10-08.json");
        Files.writeString(file, """
                {"format":1,"version":"2026-10-08","future":true,
                 "taxa":[{"id":"A1","rank":"family","name":"Nymphalidae","inatId":1,"extra":"x"}]}
                """);

        Bundle read = BundleIO.read(file);

        assertThat(read.taxa().get(0).synonyms()).isEmpty();
        assertThat(read.changes()).isEmpty();
    }

    @Test
    void rejectsNewerFormats() throws Exception {
        Path file = dir.resolve("taxonomy-india-2026-10-08.json");
        Files.writeString(file, "{\"format\":2,\"version\":\"2026-10-08\"}");

        assertThatThrownBy(() -> BundleIO.read(file)).isInstanceOf(java.io.IOException.class)
                .hasMessage("Bundle " + file + " uses format 2; this version understands up to 1");
    }

    @Test
    void previousIgnoresLookalikeFiles() throws Exception {
        BundleIO.write(dir.resolve("taxonomy-india-2026-09-08.json"), sample("2026-09-08", TAXA, List.of()));
        Files.writeString(dir.resolve("taxonomy-india-2026-10-08.bak.json"), "garbage");
        Files.writeString(dir.resolve("taxonomy-india-north-2026-01-01.json"), "garbage");

        assertThat(BundleIO.previous(dir, "India", "2026-11-08")).get().extracting(Bundle::version).isEqualTo("2026-09-08");
    }

    @Test
    void previousWrapsReadFailures() throws Exception {
        Path bad = dir.resolve("taxonomy-india-2026-09-08.json");
        Files.writeString(bad, "not json");

        assertThatThrownBy(() -> BundleIO.previous(dir, "India", "2026-10-08"))
                .isInstanceOf(java.io.IOException.class)
                .hasMessage("Cannot read previous bundle " + bad)
                .hasCauseInstanceOf(java.io.IOException.class);
    }

    @Test
    void slugsRegionsAndValidatesVersions() {
        assertThat(BundleIO.slug("North  India/Goa")).isEqualTo("north-india-goa");
        assertThatThrownBy(() -> BundleIO.fileName("India", "2026-1-8")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void writesUnixLineEndingsAndSortedSynonyms() throws Exception {
        Path file = dir.resolve("taxonomy-india-2026-10-08.json");
        BundleTaxon t = new BundleTaxon("A1", null, "family", "Nymphalidae", null, 1, List.of("Zeta", "Alpha"));

        BundleIO.write(file, sample("2026-10-08", List.of(t), List.of()));

        String json = Files.readString(file);
        assertThat(json).doesNotContain("\r").endsWith("}\n");
        assertThat(BundleIO.read(file).taxa().get(0).synonyms()).containsExactly("Alpha", "Zeta");
    }

    private void assertRejected(String message, BundleTaxon... taxa) {
        Path file = dir.resolve("taxonomy-india-2026-10-08.json");
        assertThatThrownBy(() -> BundleIO.write(file, sample("2026-10-08", List.of(taxa), List.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(message);
        assertThat(file).doesNotExist();
    }

    @Test
    void rejectsDuplicateAtlasIds() {
        assertRejected("Atlas id A1 appears more than once",
                new BundleTaxon("A1", null, "family", "N", null, 1, List.of()),
                new BundleTaxon("A1", null, "family", "L", null, 2, List.of()));
    }

    @Test
    void rejectsDuplicateINaturalistIds() {
        assertRejected("iNaturalist id 1 appears more than once",
                new BundleTaxon("A1", null, "family", "N", null, 1, List.of()),
                new BundleTaxon("A2", null, "family", "L", null, 1, List.of()));
    }

    @Test
    void rejectsParentsThatAreNotInTheBundle() {
        assertRejected("Taxon A2 has parent A9, which is not in the bundle",
                new BundleTaxon("A1", null, "family", "N", null, 1, List.of()),
                new BundleTaxon("A2", "A9", "genus", "Danaus", null, 2, List.of()));
    }

    @Test
    void rejectsAFamilyWithAParent() {
        assertRejected("Family A2 must not have a parent",
                new BundleTaxon("A1", null, "family", "N", null, 1, List.of()),
                new BundleTaxon("A2", "A1", "family", "L", null, 2, List.of()));
    }

    @Test
    void rejectsANonFamilyWithoutAParent() {
        assertRejected("Taxon A2 (genus) must have a parent",
                new BundleTaxon("A1", null, "family", "N", null, 1, List.of()),
                new BundleTaxon("A2", null, "genus", "Danaus", null, 2, List.of()));
    }

    @Test
    void writesBasedOnAndRanksEvenWhenNull() throws Exception {
        Path file = dir.resolve("taxonomy-india-2026-10-08.json");

        BundleIO.write(file, sample("2026-10-08", TAXA, List.of()));

        String json = Files.readString(file);
        assertThat(json).contains("\"basedOn\" : null").containsPattern("\"ranks\" : \\[\n\\s+\"family\"\n\\s+\\]");
        assertThat(json.indexOf("\"version\"")).isLessThan(json.indexOf("\"basedOn\""));
        assertThat(json.indexOf("\"basedOn\"")).isLessThan(json.indexOf("\"generatedAt\""));
        assertThat(json.indexOf("\"generatedAt\"")).isLessThan(json.indexOf("\"ranks\""));
        assertThat(json.indexOf("\"ranks\"")).isLessThan(json.indexOf("\"region\""));
    }
}
