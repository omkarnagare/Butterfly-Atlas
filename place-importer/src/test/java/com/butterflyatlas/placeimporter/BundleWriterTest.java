package com.butterflyatlas.placeimporter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import static com.butterflyatlas.placeimporter.PartitionTest.wkt;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BundleWriterTest {

    @TempDir
    Path dir;

    private static final AtlasBuilder.Atlas SMALL = new AtlasBuilder.Atlas(
            List.of(
                    new AtlasBuilder.Place("iso:IN", null, "country", "India", List.of("Bharat"), Map.of("iso", "IN")),
                    new AtlasBuilder.Place("iso:IN-MH", "iso:IN", "state", "Maharashtra", List.of(),
                            Map.of("lgd", "27", "iso", "IN-MH")),
                    new AtlasBuilder.Place("lgd:490", "iso:IN-MH", "district", "Pune", List.of("Poona"),
                            Map.of("gb", "G1", "lgd", "490"))),
            List.of(new Partition.Piece("lgd:490", wkt("POLYGON ((73.5 18, 74 18, 74 19, 73.5 19, 73.5 18))"))),
            List.of(), List.of(), null, List.of(), List.of(), List.of(), List.of());

    @Test
    void writesTheContractJsonWithIdsFromTheRegistry() {
        PlaceIdRegistry registry = new PlaceIdRegistry();

        BundleWriter.Bundle bundle = BundleWriter.assemble("2026-10-13", SMALL, registry);

        assertThat(bundle.jsonName()).isEqualTo("places-2026-10-13.json");
        assertThat(bundle.binName()).isEqualTo("places-2026-10-13.bin");
        assertThat(new String(bundle.json(), StandardCharsets.UTF_8)).isEqualTo("""
                {
                  "format" : 1,
                  "version" : "2026-10-13",
                  "basedOn" : null,
                  "levels" : [
                    "country",
                    "state",
                    "district"
                  ],
                  "geometry" : "places-2026-10-13.bin",
                  "places" : [
                    {
                      "id" : "P1",
                      "parent" : null,
                      "level" : "country",
                      "name" : "India",
                      "aliases" : [
                        "Bharat"
                      ],
                      "codes" : {
                        "iso" : "IN"
                      }
                    },
                    {
                      "id" : "P2",
                      "parent" : "P1",
                      "level" : "state",
                      "name" : "Maharashtra",
                      "aliases" : [ ],
                      "codes" : {
                        "iso" : "IN-MH",
                        "lgd" : "27"
                      }
                    },
                    {
                      "id" : "P3",
                      "parent" : "P2",
                      "level" : "district",
                      "name" : "Pune",
                      "aliases" : [
                        "Poona"
                      ],
                      "codes" : {
                        "lgd" : "490",
                        "gb" : "G1"
                      }
                    }
                  ],
                  "changes" : [ ]
                }
                """);
        assertThat(bundle.entries()).singleElement().satisfies(e -> {
            assertThat(e.id()).isEqualTo("P3");
            assertThat(e.rings()).singleElement().satisfies(r -> assertThat(r).hasSize(10).contains(7_350_000, 1_900_000));
        });
    }

    @Test
    void keepsTheIdsAlreadyInTheRegistry() {
        PlaceIdRegistry registry = new PlaceIdRegistry();
        registry.idFor("lgd:490");

        BundleWriter.Bundle bundle = BundleWriter.assemble("2026-10-13", SMALL, registry);

        assertThat(bundle.places()).extracting(BundlePlace::id).containsExactly("P2", "P3", "P1");
        assertThat(bundle.entries()).extracting(BinWriter.Entry::id).containsExactly("P1");
    }

    @Test
    void ringsAreClosedOnTheGridAndNoSegmentSpansMoreThan90Degrees() {
        int[] ring = BundleWriter.rings(wkt("POLYGON ((-180 -90, 180 -90, 180 -80, -180 -80, -180 -90))")).get(0);

        assertThat(ring[0]).isEqualTo(ring[ring.length - 2]);
        assertThat(ring[1]).isEqualTo(ring[ring.length - 1]);
        for (int i = 0; i + 3 < ring.length; i += 2) {
            assertThat(Math.abs(ring[i + 2] - ring[i])).isLessThanOrEqualTo(9_000_000);
        }
        assertThat(ring.length / 2).isEqualTo(5 + 3 + 3);
    }

    @Test
    void theSameAreaGivesTheSameRingsWhereverItsRingsStartAndInWhateverOrderItsPartsCome() {
        List<int[]> a = BundleWriter.rings(wkt("MULTIPOLYGON (((0 0, 1 0, 1 1, 0 1, 0 0)), ((5 5, 6 5, 6 6, 5 5)))"));
        List<int[]> b = BundleWriter.rings(wkt("MULTIPOLYGON (((6 6, 5 5, 6 5, 6 6)), ((1 1, 0 1, 0 0, 1 0, 1 1)))"));

        assertThat(a).hasSize(2);
        assertThat(b).hasSize(2);
        assertThat(b.get(0)).isEqualTo(a.get(0));
        assertThat(b.get(1)).isEqualTo(a.get(1));
    }

    @Test
    void theStructureCheckFindsWrongParentsOrderIdsAndLongSegments() {
        List<BundlePlace> places = List.of(
                new BundlePlace("P2", "P1", "state", "Maharashtra", List.of(), Map.of()),
                new BundlePlace("P1", null, "country", "India", List.of(), Map.of()),
                new BundlePlace("P3", "P1", "district", "Pune", List.of(), Map.of()),
                new BundlePlace("X4", null, "country", "Nepal", List.of(), Map.of()));
        List<BinWriter.Entry> entries = List.of(
                new BinWriter.Entry("P9", List.of(new int[]{0, 0, 1, 0, 1, 1, 0, 0})),
                new BinWriter.Entry("P3", List.of(new int[]{-18_000_000, 0, 18_000_000, 0, 0, 100, -18_000_000, 0})));

        assertThat(BundleWriter.structure(places, entries)).containsExactly(
                "Place P2 is listed before its parent P1",
                "District P3 is not under a state",
                "Place id X4 is not P<number>",
                "Boundary P9 is not a place",
                "Boundary P3 has a segment spanning more than 180° of longitude (it must be split at ±180)");
    }

    @Test
    void theStructureCheckFindsSiblingsThatShareANameIgnoringCaseAndAccentsIndiaIncluded() {
        List<BundlePlace> places = List.of(
                new BundlePlace("P1", null, "country", "India", List.of(), Map.of()),
                new BundlePlace("P2", "P1", "state", "Maharashtra", List.of(), Map.of()),
                new BundlePlace("P3", "P2", "district", "Pune", List.of(), Map.of()),
                new BundlePlace("P4", "P2", "district", "PUNE", List.of(), Map.of()),
                new BundlePlace("P5", "P1", "state", "Goa", List.of(), Map.of()),
                new BundlePlace("P6", "P5", "district", "Pune", List.of(), Map.of()),
                new BundlePlace("P7", null, "country", "india", List.of(), Map.of()),
                new BundlePlace("P8", null, "country", "Slovenia", List.of(), Map.of()),
                new BundlePlace("P9", "P8", "state", "Krsko", List.of(), Map.of()),
                new BundlePlace("P10", "P8", "state", "Krško", List.of(), Map.of()));

        assertThat(BundleWriter.structure(places, List.of())).containsExactly(
                "Countries P1, P7 share the name India (add rows to name-overrides.csv)",
                "Places P3, P4 in Maharashtra share the name Pune (add rows to name-overrides.csv)",
                "Places P9, P10 in Slovenia share the name Krsko (add rows to name-overrides.csv)");
    }

    @Test
    void writesTheFilesAndListsEveryPlaceListAndOnlyTheNewBoundariesInTheChecksumList() throws Exception {
        Files.writeString(dir.resolve("places-2026-01-01.json"), "old");
        Files.writeString(dir.resolve("places-2026-01-01.bin"), "old bin");
        Files.writeString(dir.resolve("README.md"), "not a bundle");
        BundleWriter.Bundle bundle = BundleWriter.assemble("2026-10-13", SMALL, new PlaceIdRegistry());

        BundleWriter.write(dir, bundle, "Sources: test\n");

        assertThat(Files.readAllBytes(dir.resolve("places-2026-10-13.json"))).isEqualTo(bundle.json());
        assertThat(Files.readAllBytes(dir.resolve("places-2026-10-13.bin"))).isEqualTo(bundle.bin());
        assertThat(Files.readString(dir.resolve("NOTICE.txt"))).isEqualTo("Sources: test\n");
        assertThat(Files.readString(dir.resolve("places.sha256"))).isEqualTo(
                sha("old".getBytes(StandardCharsets.UTF_8)) + "  places-2026-01-01.json\n"
                        + sha(bundle.bin()) + "  places-2026-10-13.bin\n"
                        + sha(bundle.json()) + "  places-2026-10-13.json\n");
        assertThat(dir.resolve("places-2026-10-13.json").toString()).contains("2026-10-13");
        assertThat(dir.resolve("places-2026-01-01.bin")).as("only the newest list's boundaries ship").doesNotExist();
        assertThat(dir.resolve("README.md")).exists();
    }

    @Test
    void theStructureCheckFindsBlankNamesBadAliasesAndBoundariesWithoutRings() {
        List<BundlePlace> places = List.of(
                new BundlePlace("P1", null, "country", " ", List.of(), Map.of()),
                new BundlePlace("P2", null, "country", "Nepal", List.of("Nepal", "  ", "Nepāl"), Map.of()));
        List<BinWriter.Entry> entries = List.of(new BinWriter.Entry("P2", List.of()));

        assertThat(BundleWriter.structure(places, entries)).containsExactly(
                "Place P1 has no name",
                "Place P2 has an alias equal to its name",
                "Place P2 has a blank alias",
                "Boundary P2 has no rings");
    }

    @Test
    void aFailedWriteLeavesTheFolderAsItWas() throws Exception {
        Files.writeString(dir.resolve("places-2026-01-01.json"), "old");
        Files.writeString(dir.resolve("places-2026-01-01.bin"), "old bin");
        Files.writeString(dir.resolve("NOTICE.txt"), "old notice");
        Files.writeString(dir.resolve("places.sha256"), "old sums");
        Map<String, String> before = contents(dir);
        BundleWriter.Bundle bundle = BundleWriter.assemble("2026-10-13", SMALL, new PlaceIdRegistry());

        assertThatThrownBy(() -> BundleWriter.write(dir, bundle, "new notice", (from, to) -> {
            if (to.getFileName().toString().equals("places.sha256") && from.getFileName().toString().endsWith(".tmp")) {
                throw new IOException("disk full");
            }
            Files.move(from, to, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        })).isInstanceOf(IOException.class).isNotInstanceOf(BundleWriter.PartialWriteException.class)
                .hasMessageContaining("disk full");

        assertThat(contents(dir)).isEqualTo(before);
    }

    @Test
    void writingComputesTheChecksumsFromTheBytesWrittenNotFromAReRead() throws Exception {
        BundleWriter.Bundle bundle = BundleWriter.assemble("2026-10-13", SMALL, new PlaceIdRegistry());
        List<Path> movedBeforeTheChecksums = new java.util.ArrayList<>();

        BundleWriter.write(dir, bundle, "notice", (from, to) -> {
            if (!to.getFileName().toString().equals("places.sha256") && !to.getFileName().toString().contains(".old")) {
                movedBeforeTheChecksums.add(to);
            }
            Files.move(from, to, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        });

        assertThat(movedBeforeTheChecksums).extracting(p -> p.getFileName().toString())
                .containsExactly("places-2026-10-13.json", "places-2026-10-13.bin", "NOTICE.txt");
        assertThat(Files.readString(dir.resolve("places.sha256"))).isEqualTo(sha(bundle.bin()) + "  places-2026-10-13.bin\n"
                + sha(bundle.json()) + "  places-2026-10-13.json\n");
        try (Stream<Path> files = Files.list(dir)) {
            assertThat(files.map(f -> f.getFileName().toString()))
                    .containsExactlyInAnyOrder("places-2026-10-13.json", "places-2026-10-13.bin", "NOTICE.txt", "places.sha256");
        }
    }

    @Test
    void aFailedWriteOverABundleOfTheSameVersionPutsItsOldFilesBackByteForByte() throws Exception {
        byte[] oldJson = {1, 2, 3, (byte) 0xFF};
        byte[] oldBin = {0x1F, (byte) 0x8B, 0, 9};
        Files.write(dir.resolve("places-2026-10-13.json"), oldJson);
        Files.write(dir.resolve("places-2026-10-13.bin"), oldBin);
        Files.writeString(dir.resolve("places.sha256"), "old sums");
        BundleWriter.Bundle bundle = BundleWriter.assemble("2026-10-13", SMALL, new PlaceIdRegistry());

        assertThatThrownBy(() -> BundleWriter.write(dir, bundle, "notice", failingOn("places.sha256", false, false)))
                .isInstanceOf(IOException.class);

        assertThat(Files.readAllBytes(dir.resolve("places-2026-10-13.json"))).isEqualTo(oldJson);
        assertThat(Files.readAllBytes(dir.resolve("places-2026-10-13.bin"))).isEqualTo(oldBin);
        try (Stream<Path> files = Files.list(dir)) {
            assertThat(files.map(f -> f.getFileName().toString()))
                    .containsExactlyInAnyOrder("places-2026-10-13.json", "places-2026-10-13.bin", "places.sha256");
        }
    }

    @Test
    void aFailedMoveOfAnOldFileAsideLeavesNoOldCopyBehind() throws Exception {
        Files.writeString(dir.resolve("NOTICE.txt"), "old notice");
        Map<String, String> before = contents(dir);
        BundleWriter.Bundle bundle = BundleWriter.assemble("2026-10-13", SMALL, new PlaceIdRegistry());

        assertThatThrownBy(() -> BundleWriter.write(dir, bundle, "notice", (from, to) -> {
            if (to.getFileName().toString().endsWith(".old")) {
                throw new IOException("no room");
            }
            Files.move(from, to, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        })).isInstanceOf(IOException.class).isNotInstanceOf(BundleWriter.PartialWriteException.class);

        assertThat(contents(dir)).isEqualTo(before);
    }

    @Test
    void aFailedWriteThatCannotBeUndoneSaysWhatIsLeft() throws Exception {
        Files.writeString(dir.resolve("NOTICE.txt"), "old notice");
        BundleWriter.Bundle bundle = BundleWriter.assemble("2026-10-13", SMALL, new PlaceIdRegistry());

        assertThatThrownBy(() -> BundleWriter.write(dir, bundle, "notice", failingOn("places.sha256", true, false)))
                .isInstanceOf(BundleWriter.PartialWriteException.class)
                .hasMessageContaining("disk full")
                .hasMessageContaining("could not be undone; check NOTICE.txt");
    }

    @Test
    void anOldCopyThatCannotBeDeletedAfterASuccessfulWriteIsOnlyAWarning() throws Exception {
        Files.writeString(dir.resolve("NOTICE.txt"), "old notice");
        BundleWriter.Bundle bundle = BundleWriter.assemble("2026-10-13", SMALL, new PlaceIdRegistry());

        List<String> warnings = BundleWriter.write(dir, bundle, "new notice", failingOn("nothing", false, true));

        assertThat(Files.readString(dir.resolve("NOTICE.txt"))).isEqualTo("new notice");
        assertThat(warnings).singleElement().asString().startsWith("Could not delete NOTICE.txt").contains(".old");
    }

    /**
     * Moves files, failing the move of the new {@code failTarget} into place; with {@code failRestore} also every move of an old copy
     * back, and with {@code failDeleteOld} the deletion of old copies.
     */
    private static BundleWriter.Mover failingOn(String failTarget, boolean failRestore, boolean failDeleteOld) {
        return new BundleWriter.Mover() {
            @Override
            public void move(Path from, Path to) throws IOException {
                if (to.getFileName().toString().equals(failTarget) && from.getFileName().toString().endsWith(".tmp")) {
                    throw new IOException("disk full");
                }
                if (failRestore && from.getFileName().toString().endsWith(".old")) {
                    throw new IOException("still full");
                }
                Files.move(from, to, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }

            @Override
            public void delete(Path file) throws IOException {
                if (failDeleteOld && file.getFileName().toString().endsWith(".old")) {
                    throw new IOException("busy");
                }
                Files.deleteIfExists(file);
            }
        };
    }

    private static Map<String, String> contents(Path dir) throws IOException {
        Map<String, String> files = new TreeMap<>();
        try (Stream<Path> list = Files.list(dir)) {
            for (Path f : list.toList()) {
                files.put(f.getFileName().toString(), Files.readString(f));
            }
        }
        return files;
    }

    @Test
    void refusesAVersionThatIsNotADate() {
        assertThatThrownBy(() -> BundleWriter.assemble("latest", SMALL, new PlaceIdRegistry()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("yyyy-MM-dd");
    }

    private static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
