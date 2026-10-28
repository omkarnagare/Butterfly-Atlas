package com.butterflyatlas.importer;

import com.butterflyatlas.importer.bundle.Bundle;
import com.butterflyatlas.importer.bundle.BundleIO;
import com.butterflyatlas.importer.bundle.BundleTaxon;
import com.butterflyatlas.importer.bundle.Change;
import com.butterflyatlas.importer.inat.FakeTransport;
import com.butterflyatlas.importer.inat.InatClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class ImporterTest {

    @TempDir
    Path dir;

    private static final Map<Long, String> TAXA = Map.of(
            47922L, "{\"id\":47922,\"name\":\"Nymphalidae\",\"rank\":\"family\",\"is_active\":true,\"ancestor_ids\":[47224,47922],\"preferred_common_name\":\"Brush-footed Butterflies\"}",
            47923L, "{\"id\":47923,\"name\":\"Lycaenidae\",\"rank\":\"family\",\"is_active\":true,\"ancestor_ids\":[47224,47923]}",
            48663L, "{\"id\":48663,\"name\":\"Danaus\",\"rank\":\"genus\",\"is_active\":true,\"ancestor_ids\":[47224,47922,48663]}",
            125511L, "{\"id\":125511,\"name\":\"Arhopala\",\"rank\":\"genus\",\"is_active\":true,\"ancestor_ids\":[47224,47923,125511]}");

    private static String species(String danausName) {
        return """
                {"total_results":2,"results":[
                  {"count":10,"taxon":{"id":67835,"name":"%s","rank":"species","is_active":true,
                    "ancestor_ids":[48460,1,47224,47922,48663,67835],"preferred_common_name":"Plain Tiger"}},
                  {"count":3,"taxon":{"id":146903,"name":"Arhopala centaurus","rank":"species","is_active":true,
                    "ancestor_ids":[48460,1,47224,47923,125511,146903],"preferred_common_name":"Centaur Oakblue"}}]}"""
                .formatted(danausName);
    }

    private static FakeTransport transport(String danausName) {
        return new FakeTransport()
                .respond("species_counts", species(danausName))
                .route("/v2/taxa/", (URI uri) -> "{\"results\":[" + Arrays.stream(
                                uri.getPath().substring("/v2/taxa/".length()).split(","))
                        .map(id -> TAXA.get(Long.parseLong(id)))
                        .collect(Collectors.joining(",")) + "]}")
                .respond("/v1/taxa/", """
                        {"results":[{"id":146903,"name":"Arhopala centaurus","names":[
                          {"name":"Arhopala pseudocentaurus","lexicon":"scientific-names","is_valid":false}]},
                         {"id":67835,"name":"Danaus chrysippus","names":[]}]}""");
    }

    private Importer.Options options(String version) {
        return new Importer.Options(6681, "India", 47224, "butterflies", "Butterflies", Quality.VERIFIABLE, version,
                dir.resolve("out"), dir.resolve("atlas-ids.json"));
    }

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-08T10:00:00Z"), ZoneOffset.UTC);

    @Test
    void writesABundleAndTheIdRegistry() throws Exception {
        Importer.Summary summary = new Importer(new InatClient(transport("Danaus chrysippus"), 6681), CLOCK)
                .run(options("2026-10-08"));

        Path file = dir.resolve("out/taxonomy-india-2026-10-08.json");
        assertThat(summary.file()).isEqualTo(file);
        assertThat(summary.countsByRank()).containsEntry("family", 2L).containsEntry("genus", 2L).containsEntry("species", 2L);
        assertThat(Files.exists(dir.resolve("atlas-ids.json"))).isTrue();

        Bundle bundle = BundleIO.read(file);
        assertThat(bundle.region().inatPlaceId()).isEqualTo(6681);
        assertThat(bundle.source().quality()).isEqualTo("verifiable");
        assertThat(bundle.generatedAt()).isEqualTo("2026-10-08T10:00:00Z");
        assertThat(bundle.changes()).isEmpty();
        assertThat(bundle.basedOn()).isNull();
        assertThat(bundle.ranks()).containsExactly("family", "subfamily", "tribe", "genus", "species");
        assertThat(bundle.source().note()).isEqualTo(
                "Taxonomy data from iNaturalist. Species list from verifiable iNaturalist observations in India.");
        BundleTaxon oakblue = bundle.taxa().stream().filter(t -> t.inatId() == 146903).findFirst().orElseThrow();
        assertThat(oakblue.common()).isEqualTo("Centaur Oakblue");
        assertThat(oakblue.synonyms()).containsExactly("Arhopala pseudocentaurus");
    }

    @Test
    void rewritesTheChecksumListAfterEachImport() throws Exception {
        new Importer(new InatClient(transport("Danaus chrysippus"), 6681), CLOCK).run(options("2026-10-08"));
        new Importer(new InatClient(transport("Danaus chrysippus"), 6681), CLOCK).run(options("2026-11-08"));

        assertThat(Files.readAllLines(dir.resolve("out/bundles.sha256")))
                .hasSize(2)
                .satisfiesExactly(
                        line -> assertThat(line).endsWith("  taxonomy-india-2026-10-08.json"),
                        line -> assertThat(line).endsWith("  taxonomy-india-2026-11-08.json"));
    }

    @Test
    void reportsChangesAgainstThePreviousBundle() throws Exception {
        new Importer(new InatClient(transport("Danaus chrysippus"), 6681), CLOCK).run(options("2026-10-08"));

        Importer.Summary summary = new Importer(new InatClient(transport("Danaus chrysippus chrysippus"), 6681), CLOCK)
                .run(options("2026-11-08"));

        Bundle bundle = BundleIO.read(dir.resolve("out/taxonomy-india-2026-11-08.json"));
        assertThat(bundle.changes()).extracting(Change::type).containsExactly(Change.Type.RENAME);
        assertThat(bundle.basedOn()).isEqualTo("2026-10-08");
        assertThat(bundle.ranks()).containsExactly("family", "subfamily", "tribe", "genus", "species");
        assertThat(summary.changeCount()).isEqualTo(1);
        assertThat(bundle.taxa()).filteredOn(t -> t.inatId() == 67835).extracting(BundleTaxon::id)
                .containsExactly(BundleIO.read(dir.resolve("out/taxonomy-india-2026-10-08.json")).taxa().stream()
                        .filter(t -> t.inatId() == 67835).findFirst().orElseThrow().id());
    }

    @Test
    void aFailedBundleWriteCanBeRerunWithTheSameIds() throws Exception {
        Files.writeString(dir.resolve("out"), "not a directory");
        Importer importer = new Importer(new InatClient(transport("Danaus chrysippus"), 6681), CLOCK);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> importer.run(options("2026-10-08")))
                .isInstanceOf(java.io.IOException.class);
        assertThat(Files.exists(dir.resolve("atlas-ids.json"))).isTrue();
        String idsAfterFailure = Files.readString(dir.resolve("atlas-ids.json"));

        Files.delete(dir.resolve("out"));
        Importer.Summary summary = importer.run(options("2026-10-08"));

        assertThat(Files.readString(dir.resolve("atlas-ids.json"))).isEqualTo(idsAfterFailure);
        assertThat(BundleIO.read(summary.file()).taxa()).hasSize(6);
    }

    @Test
    void refusesToWriteAnEmptyBundle() {
        FakeTransport empty = new FakeTransport().respond("species_counts", "{\"total_results\":0,\"results\":[]}");
        Importer importer = new Importer(new InatClient(empty, 6681), CLOCK);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> importer.run(options("2026-10-08")))
                .isInstanceOf(java.io.IOException.class)
                .hasMessage("iNaturalist returned no species for taxon 47224 in place 6681; refusing to write an empty bundle");
        assertThat(Files.exists(dir.resolve("out"))).isFalse();
    }

    @Test
    void wordsTheSourceNoteForResearchGrade() throws Exception {
        Importer.Options research = new Importer.Options(6681, "India", 47224, "butterflies", "Butterflies",
                Quality.RESEARCH, "2026-10-08", dir.resolve("out"), dir.resolve("atlas-ids.json"));

        Importer.Summary summary = new Importer(new InatClient(transport("Danaus chrysippus"), 6681), CLOCK).run(research);

        assertThat(BundleIO.read(summary.file()).source().note()).isEqualTo(
                "Taxonomy data from iNaturalist. Species list from research-grade iNaturalist observations in India.");
    }
}
