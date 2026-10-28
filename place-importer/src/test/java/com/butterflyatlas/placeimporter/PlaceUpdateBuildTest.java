package com.butterflyatlas.placeimporter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** A later place list built on the one before it (5c contract §3), from the fixtures (no network). */
class PlaceUpdateBuildTest {

    @TempDir
    Path dir;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();
    private static final ObjectMapper JSON = new ObjectMapper();

    private Path sources = AtlasBuilderTest.fixtures();

    private int run(String version, String... more) {
        out.reset();
        err.reset();
        List<String> list = new ArrayList<>(List.of("--sources", sources.toString(), "--out",
                dir.resolve("places").toString(), "--registry", dir.resolve("place-ids.json").toString(), "--version", version));
        list.addAll(List.of(more));
        return PlaceImporterMain.run(list.toArray(String[]::new), LocalDate.of(2026, 10, 13), dir,
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8));
    }

    private Path changes(String lines) throws Exception {
        Path file = dir.resolve("place-changes.csv");
        Files.writeString(file, "version,type,from,to,reason\n" + lines);
        return file;
    }

    private JsonNode list(String version) throws Exception {
        return JSON.readTree(dir.resolve("places/places-" + version + ".json").toFile());
    }

    private static Map<String, JsonNode> byId(JsonNode list) {
        Map<String, JsonNode> places = new HashMap<>();
        list.path("places").forEach(p -> places.put(p.path("id").asText(), p));
        return places;
    }

    private String id(String key) throws Exception {
        return JSON.readTree(dir.resolve("place-ids.json").toFile()).path(key).asText();
    }

    @Test
    void aMergeLineMergesTheAreaAndNamesIntoTheSurvivorAndTheListSaysSoAndNothingElse() throws Exception {
        assertThat(run("2026-10-13")).as(err.toString(StandardCharsets.UTF_8)).isZero();
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("The first place list: nothing to compare with");
        JsonNode v1 = list("2026-10-13");
        String gandaki = id("ne:NPL-1");
        String bagmati = id("ne:NPL-2");
        Path csv = changes("2026-10-20,merge,ne:NPL-1,ne:NPL-2,Gandaki is part of Bagmati\n");

        assertThat(run("2026-10-20", "--changes", csv.toString())).as(err.toString(StandardCharsets.UTF_8)).isZero();

        JsonNode v2 = list("2026-10-20");
        assertThat(v2.path("basedOn").asText()).isEqualTo("2026-10-13");
        assertThat(v2.path("changes").toString()).isEqualTo("[{\"type\":\"merge\",\"from\":[\"" + gandaki + "\"],\"to\":[\""
                + bagmati + "\"],\"detail\":\"Gandaki merged into Bagmati\"}]");
        Map<String, JsonNode> before = byId(v1);
        Map<String, JsonNode> after = byId(v2);
        assertThat(after).doesNotContainKey(gandaki);
        assertThat(after.get(bagmati).path("aliases").toString()).contains("\"Gandaki\"");
        before.remove(gandaki);
        before.remove(bagmati);
        after.remove(bagmati);
        assertThat(after).as("nothing else differs").isEqualTo(before);
        assertThat(out.toString(StandardCharsets.UTF_8)).contains(
                "Changes since 2026-10-13: 1 merged, 0 split, 0 retired, 0 renamed, 0 moved, 0 added",
                "  merge: Gandaki merged into Bagmati");
        try (Stream<Path> files = Files.list(dir.resolve("places"))) {
            assertThat(files.map(f -> f.getFileName().toString()).sorted()).as("only the newest list's boundaries")
                    .containsExactly("NOTICE.txt", "places-2026-10-13.json", "places-2026-10-20.bin", "places-2026-10-20.json",
                            "places.sha256");
        }
        assertThat(Files.readString(dir.resolve("places/places.sha256"))).contains("  places-2026-10-13.json\n",
                "  places-2026-10-20.bin\n", "  places-2026-10-20.json\n").doesNotContain("places-2026-10-13.bin");
        List<BinDecoder.Entry> entries = BinDecoder.decode(Files.readAllBytes(dir.resolve("places/places-2026-10-20.bin")));
        assertThat(BinDecoder.lookup(entries, 28.21, 83.99)).as("Pokhara, in Gandaki's area").isEqualTo(bagmati);
        assertThat(id("ne:NPL-1")).as("the merged place's id stays reserved").isEqualTo(gandaki);

        byte[] json = Files.readAllBytes(dir.resolve("places/places-2026-10-20.json"));
        byte[] bin = Files.readAllBytes(dir.resolve("places/places-2026-10-20.bin"));
        assertThat(run("2026-10-20", "--changes", csv.toString())).isZero();
        assertThat(Files.readAllBytes(dir.resolve("places/places-2026-10-20.json"))).as("deterministic").isEqualTo(json);
        assertThat(Files.readAllBytes(dir.resolve("places/places-2026-10-20.bin"))).isEqualTo(bin);
    }

    @Test
    void aMergedPlacesNameStaysAnotherNameOfItsSurvivorInEveryLaterList() throws Exception {
        assertThat(run("2026-10-13")).isZero();
        String bagmati = id("ne:NPL-2");
        Path csv = changes("2026-10-20,merge,ne:NPL-1,ne:NPL-2,Gandaki is part of Bagmati\n");
        assertThat(run("2026-10-20", "--changes", csv.toString())).isZero();

        assertThat(run("2026-11-01", "--changes", csv.toString())).as(err.toString(StandardCharsets.UTF_8)).isZero();
        assertThat(run("2026-12-01", "--changes", csv.toString())).isZero();

        for (String version : List.of("2026-11-01", "2026-12-01")) {
            JsonNode list = list(version);
            assertThat(list.path("changes")).as(version).isEmpty();
            assertThat(byId(list).get(bagmati).path("aliases").toString()).as(version).contains("\"Gandaki\"");
        }
        assertThat(list("2026-12-01").path("basedOn").asText()).isEqualTo("2026-11-01");
    }

    @Test
    void aPlaceGoneWithoutALineOrALineForAPlaceStillThereStopsTheBuildWithThePlaceNamed() throws Exception {
        assertThat(run("2026-10-13")).isZero();
        String gandaki = id("ne:NPL-1");
        sources = dir.resolve("sources");
        Files.createDirectories(sources);
        for (Path f : Files.list(AtlasBuilderTest.fixtures()).toList()) {
            Files.copy(f, sources.resolve(f.getFileName()));
        }
        JsonNode states = JSON.readTree(sources.resolve("ne_10m_admin_1_states_provinces.geojson").toFile());
        var features = (com.fasterxml.jackson.databind.node.ArrayNode) states.path("features");
        for (int i = features.size() - 1; i >= 0; i--) {
            if ("NPL-1".equals(features.get(i).path("properties").path("adm1_code").asText())) {
                features.remove(i);
            }
        }
        Files.writeString(sources.resolve("ne_10m_admin_1_states_provinces.geojson"), states.toString());

        assertThat(run("2026-10-20")).isEqualTo(1);
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("Build failed, nothing written:", gandaki
                + " Gandaki is not in the new list: add a merge, split or retire line to place-changes.csv");

        sources = AtlasBuilderTest.fixtures();
        assertThat(run("2026-10-20", "--changes", changes("2026-10-20,retire,ne:NPL-1,,Gone\n").toString())).isEqualTo(1);
        assertThat(err.toString(StandardCharsets.UTF_8)).contains(gandaki + " Gandaki is still in the new list, but"
                + " place-changes.csv retires it");
        assertThat(dir.resolve("places/places-2026-10-20.json")).doesNotExist();
    }

    @Test
    void aPlaceAnEarlierListGaveUpThatTheSourcesBringBackStopsTheBuildWithThePlaceNamed() throws Exception {
        assertThat(run("2026-10-13")).isZero();
        String gandaki = id("ne:NPL-1");
        sources = dir.resolve("sources");
        Files.createDirectories(sources);
        for (Path f : Files.list(AtlasBuilderTest.fixtures()).toList()) {
            Files.copy(f, sources.resolve(f.getFileName()));
        }
        Path states = sources.resolve("ne_10m_admin_1_states_provinces.geojson");
        String withGandaki = Files.readString(states);
        JsonNode parsed = JSON.readTree(withGandaki);
        var features = (com.fasterxml.jackson.databind.node.ArrayNode) parsed.path("features");
        for (int i = features.size() - 1; i >= 0; i--) {
            if ("NPL-1".equals(features.get(i).path("properties").path("adm1_code").asText())) {
                features.remove(i);
            }
        }
        Files.writeString(states, parsed.toString());
        Path csv = changes("2026-10-20,retire,ne:NPL-1,,Gone from the sources\n");
        assertThat(run("2026-10-20", "--changes", csv.toString())).as(err.toString(StandardCharsets.UTF_8)).isZero();
        Files.writeString(states, withGandaki);

        assertThat(run("2026-11-01", "--changes", csv.toString())).isEqualTo(1);

        assertThat(err.toString(StandardCharsets.UTF_8)).contains("Build failed, nothing written:", gandaki + " Gandaki was"
                + " given up by an earlier place list and is in the sources again: a place id is never used twice; check"
                + " the sources");
        assertThat(dir.resolve("places/places-2026-11-01.json")).doesNotExist();
    }

    @Test
    void aDistrictMatchedToAnLgdCodeLaterKeepsTheIdItHadUnderItsGeoBoundariesKey() throws Exception {
        assertThat(run("2026-10-13")).isZero();
        String pune = id("gb:ADM2-PUNE");
        Map<String, JsonNode> v1 = byId(list("2026-10-13"));

        assertThat(run("2026-10-20", "--lgd", AtlasBuilderTest.fixtures().resolve("lgd-districts.csv").toString()))
                .as(err.toString(StandardCharsets.UTF_8)).isZero();

        assertThat(id("lgd:490")).isEqualTo(pune);
        assertThat(id("gb:ADM2-PUNE")).as("the gb key stays").isEqualTo(pune);
        Map<String, JsonNode> v2 = byId(list("2026-10-20"));
        assertThat(v2.keySet()).as("same ids: not a change").isEqualTo(v1.keySet());
        assertThat(list("2026-10-20").path("changes").toString()).doesNotContain("\"add\"", "\"retire\"", "\"merge\"");
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("Changes since 2026-10-13: 0 merged, 0 split, 0 retired");
    }
}
