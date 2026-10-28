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

class PlaceImporterMainTest {

    @TempDir
    Path dir;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 13);

    private int run(String... args) {
        return PlaceImporterMain.run(args, TODAY, dir, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
    }

    private String[] args(Path sources, String... more) {
        List<String> list = new ArrayList<>(List.of("--sources", sources.toString(), "--out", dir.resolve("places").toString(),
                "--registry", dir.resolve("place-ids.json").toString()));
        list.addAll(List.of(more));
        return list.toArray(String[]::new);
    }

    @Test
    void buildsTheBundleFromTheSourcesAndTheContractTestPointsLandWhereTheyShould() throws Exception {
        Path fixtures = AtlasBuilderTest.fixtures();

        int exit = run(args(fixtures, "--lgd", fixtures.resolve("lgd-districts.csv").toString()));

        assertThat(exit).as(err.toString(StandardCharsets.UTF_8)).isZero();
        Path places = dir.resolve("places");
        try (Stream<Path> files = Files.list(places)) {
            assertThat(files.map(f -> f.getFileName().toString()).sorted())
                    .containsExactly("NOTICE.txt", "places-2026-10-13.bin", "places-2026-10-13.json", "places.sha256");
        }
        JsonNode json = new ObjectMapper().readTree(places.resolve("places-2026-10-13.json").toFile());
        assertThat(json.path("version").asText()).isEqualTo("2026-10-13");
        assertThat(json.path("geometry").asText()).isEqualTo("places-2026-10-13.bin");
        Map<String, JsonNode> byId = new HashMap<>();
        json.path("places").forEach(p -> byId.put(p.path("id").asText(), p));
        assertThat(byId.get("P1").path("name").asText()).isEqualTo("India");

        List<BinDecoder.Entry> entries = BinDecoder.decode(Files.readAllBytes(places.resolve("places-2026-10-13.bin")));
        assertThat(path(byId, BinDecoder.lookup(entries, 34.1526, 77.5771))).containsExactly("Leh Ladakh", "Ladakh", "India");
        assertThat(path(byId, BinDecoder.lookup(entries, 27.5860, 91.8594)))
                .containsExactly("Tawang", "Arunachal Pradesh", "India");
        assertThat(path(byId, BinDecoder.lookup(entries, 18.5204, 73.8567))).containsExactly("Pune", "Maharashtra", "India");
        assertThat(path(byId, BinDecoder.lookup(entries, 34.3700, 73.4711))).containsExactly("Jammu and Kashmir", "India");
        assertThat(path(byId, BinDecoder.lookup(entries, 27.7172, 85.3240))).containsExactly("Bagmati", "Nepal");
        assertThat(path(byId, BinDecoder.lookup(entries, 15.30, 73.895))).containsExactly("South Goa", "Goa", "India");
        assertThat(BinDecoder.lookup(entries, 15.0, 65.0)).isNull();

        String sums = Files.readString(places.resolve("places.sha256"));
        assertThat(sums).isEqualTo(
                BundleWriter.sha256(Files.readAllBytes(places.resolve("places-2026-10-13.bin"))) + "  places-2026-10-13.bin\n"
                        + BundleWriter.sha256(Files.readAllBytes(places.resolve("places-2026-10-13.json")))
                        + "  places-2026-10-13.json\n");
        assertThat(Files.readString(places.resolve("NOTICE.txt"))).contains("geoBoundaries", "ODbL", "Natural Earth",
                "Public domain", "Local Government Directory", "lgd-districts.csv");
        assertThat(Files.readString(dir.resolve("place-ids.json"))).contains("\"iso:IN\" : \"P1\"", "\"lgd:9\"");
        String report = out.toString(StandardCharsets.UTF_8);
        assertThat(report).contains("Wrote places-2026-10-13.json", "  country: 5", "  state: 9", "  district: 8",
                "LGD districts without a boundary (1): Bichom (Arunachal Pradesh, 787)",
                "Boundary districts without an LGD match (0)", "  islands lost to simplification: 0",
                "LGD overrides whose code is not in the list (", "IN-MH Ahmadnagar → 466",
                "Places renamed to tell siblings apart (0)", "Name overrides for places not in the data (",
                "Other spellings for places not in the data (");
    }

    @Test
    void aSecondRunKeepsEveryIdAndWritesTheSameFiles() throws Exception {
        Path fixtures = AtlasBuilderTest.fixtures();
        assertThat(run(args(fixtures))).isZero();
        byte[] json = Files.readAllBytes(dir.resolve("places/places-2026-10-13.json"));
        String registry = Files.readString(dir.resolve("place-ids.json"));

        assertThat(run(args(fixtures))).isZero();

        assertThat(Files.readAllBytes(dir.resolve("places/places-2026-10-13.json"))).isEqualTo(json);
        assertThat(Files.readString(dir.resolve("place-ids.json"))).isEqualTo(registry);
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("LGD comparison skipped: no --lgd list given");
    }

    @Test
    void aFailedCheckWritesNothing() throws Exception {
        Path sources = dir.resolve("sources");
        Files.createDirectories(sources);
        for (Path f : Files.list(AtlasBuilderTest.fixtures()).toList()) {
            Files.copy(f, sources.resolve(f.getFileName()));
        }
        Path states = sources.resolve("geoBoundaries-IND-ADM1.geojson");
        Files.writeString(states, Files.readString(states).replace("\"Ladākh\"", "\"Ladakh Region\""));

        int exit = run(args(sources));

        assertThat(exit).isEqualTo(1);
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("Build failed, nothing written:",
                "Test point Leh (34.1526, 77.5771) should be in Ladakh but is in Leh (Ladakh), Ladakh Region, India");
        assertThat(dir.resolve("places")).doesNotExist();
        assertThat(dir.resolve("place-ids.json")).doesNotExist();
    }

    @Test
    void theRegistryIsSavedBeforeTheBundleAndAFailedWriteDoesNotClaimNothingWasWritten() throws Exception {
        Path fixtures = AtlasBuilderTest.fixtures();
        Files.writeString(dir.resolve("places"), "a file where the folder should be");

        int exit = run(args(fixtures));

        assertThat(exit).isEqualTo(1);
        assertThat(dir.resolve("place-ids.json")).exists();
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("Build failed, the bundle was not written (the id registry")
                .doesNotContain("nothing written");
    }

    @Test
    void sourcesThatAreNotThePinnedFilesAreReported() {
        Path fixtures = AtlasBuilderTest.fixtures();

        assertThat(run(args(fixtures))).isZero();

        assertThat(err.toString(StandardCharsets.UTF_8)).contains(
                "Warning: ne_10m_admin_0_countries_ind.geojson in " + fixtures + " is not the pinned file (SHA-256 ",
                "geoBoundaries-IND-ADM2.geojson");
    }

    @Test
    void badUsageExitsWithTwo() {
        assertThat(run("--colour", "blue")).isEqualTo(2);
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("Unknown option --colour");
        assertThat(run("--version", "13-10-2026")).isEqualTo(2);
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("--version must be a date like yyyy-MM-dd");
        err.reset();
        assertThat(run("--version", "2026-02-30")).isEqualTo(2);
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("--version must be a date like yyyy-MM-dd");
        assertThat(run("--lgd", "a.csv", "--lgd", "b.csv")).isEqualTo(2);
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("--lgd is given twice");
        assertThat(run()).isEqualTo(2);
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("Run this from the repository root");
    }

    private static List<String> path(Map<String, JsonNode> byId, String id) {
        List<String> names = new ArrayList<>();
        for (String at = id; at != null; ) {
            JsonNode place = byId.get(at);
            names.add(place.path("name").asText());
            at = place.path("parent").isNull() ? null : place.path("parent").asText();
        }
        return names;
    }
}
