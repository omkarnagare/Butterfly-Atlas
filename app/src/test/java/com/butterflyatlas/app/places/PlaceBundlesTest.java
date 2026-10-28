package com.butterflyatlas.app.places;

import com.butterflyatlas.app.taxonomy.TaxonomyBundles;
import com.butterflyatlas.app.testing.PlaceBin;
import com.butterflyatlas.app.testing.TestPlaces;
import com.butterflyatlas.app.testing.UpdatePlaces;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlaceBundlesTest {

    @TempDir
    Path dir;

    @Test
    void aVerifiedFolderGivesTheTreeItsVersionAndTheBoundaries() {
        PlaceBundles bundles = PlaceBundles.load(TestPlaces.write(dir, TestPlaces.files()));

        assertThat(bundles.version()).isEqualTo("2026-10-13");
        assertThat(bundles.sha256()).isEqualTo(TaxonomyBundles.sha256(TestPlaces.json().getBytes(StandardCharsets.UTF_8)));
        assertThat(bundles.places()).extracting(PlaceDef::id).containsExactly("P1", "P2", "P3", "P4", "P5", "P6", "P7", "P8", "P9");
        assertThat(bundles.places().get(0)).isEqualTo(new PlaceDef("P1", null, "country", "Testland", List.of("Testia"),
                Map.of("iso", "TL")));
        assertThat(bundles.places().get(3)).isEqualTo(new PlaceDef("P4", "P2", "district", "Alpha", List.of("Alfa"),
                Map.of("lgd", "521")));
        assertThat(bundles.index().lookup(18.5, 73.5)).contains("P4");
        assertThat(bundles.index()).as("one index, cached").isSameAs(bundles.index());
    }

    @Test
    void aChainOfListsGivesEveryListOldestFirstAndTheNewestIsUsed() {
        UpdatePlaces.Built v1 = UpdatePlaces.fixture();
        UpdatePlaces.Built v2 = UpdatePlaces.fixtureNext();

        PlaceBundles bundles = UpdatePlaces.bundles(v1, v2);

        assertThat(bundles.all()).extracting(PlaceBundles.Shipped::version).containsExactly("2026-10-13", "2026-11-01");
        assertThat(bundles.all()).extracting(PlaceBundles.Shipped::basedOn).containsExactly(null, "2026-10-13");
        assertThat(bundles.all().get(0).sha256()).isEqualTo(TestPlaces.bundles().sha256());
        assertThat(bundles.newest().fileName()).isEqualTo("places-2026-11-01.json");
        assertThat(bundles.version()).isEqualTo("2026-11-01");
        assertThat(bundles.sha256()).isEqualTo(TaxonomyBundles.sha256(v2.json()));
        assertThat(bundles.places()).extracting(PlaceDef::id).containsExactly("P1", "P8", "P2", "P3", "P10", "P5", "P6",
                "P11", "P12");
        assertThat(bundles.position("2026-10-13")).isEqualTo(1);
        assertThat(bundles.position("2026-11-01")).isEqualTo(2);
        assertThat(bundles.position("2026-12-01")).isEqualTo(-1);
        assertThat(bundles.all().get(0).places()).hasSize(9);
        assertThat(bundles.newest().changes()).extracting(c -> c.type() + " " + c.from() + " " + c.to() + " " + c.detail())
                .containsExactly(
                        "merge [P7] [P6] Islet merged into Lakeland",
                        "split [P4] [P11, P12] Alpha split into Alpha West, Alpha East",
                        "retire [P9] [] Westfar retired",
                        "rename [P5] [P5] Beta → Betania",
                        "move [P5] [P5] Betania: Northstate → Southstate",
                        "add [] [P10] Southstate added",
                        "add [] [P11] Alpha West added",
                        "add [] [P12] Alpha East added");
        assertThat(bundles.index().lookup(18.5, 73.25)).as("the west half of the cut").contains("P11");
        assertThat(bundles.index().lookup(18.5, 73.75)).as("the east half").contains("P12");
        assertThat(bundles.index().lookup(18.5, 76.5)).as("the merged islet").contains("P6");
        assertThat(bundles.index().lookup(18.5, 76.35)).as("the lake is still no place").isEmpty();
        assertThat(bundles.index().lookup(-14.5, -179.5)).as("the retired country's area went with it").isEmpty();
    }

    @Test
    void aFolderGivenForDevelopmentIsReadWithTheSameChecks() {
        Path folder = UpdatePlaces.write(dir.resolve("places"), UpdatePlaces.fixture(), UpdatePlaces.fixtureNext());

        assertThat(PlaceBundles.fromDirectory(folder).version()).isEqualTo("2026-11-01");

        TestPlaces.write(folder, Map.of(TestPlaces.BIN, TestPlaces.bin()));
        assertThatThrownBy(() -> PlaceBundles.fromDirectory(folder)).isInstanceOf(PlaceBundleException.class)
                .hasMessage("The place file places-2026-10-13.bin is not on the list places.sha256. Reinstall Butterfly Atlas.");
    }

    @Test
    void theListsMustBeOneUnbrokenChainWithOnlyTheNewestBoundaries() {
        UpdatePlaces.Built v1 = UpdatePlaces.fixture();
        UpdatePlaces.Built v2 = UpdatePlaces.fixtureNext();
        assertRefused(UpdatePlaces.files(v1, v2.basedOn(null)), "The place file places-2026-11-01.json is not usable: it is"
                + " based on no list, but places-2026-10-13.json comes before it. Reinstall Butterfly Atlas.");
        assertRefused(UpdatePlaces.files(v1, v2.basedOn("2026-10-01")), "The place file places-2026-11-01.json is not"
                + " usable: it is based on 2026-10-01, but the list before it is 2026-10-13. Reinstall Butterfly Atlas.");
        assertRefused(UpdatePlaces.files(v2), "The place file places-2026-11-01.json is not usable: it is based on"
                + " 2026-10-13, which is not shipped. Reinstall Butterfly Atlas.");

        Map<String, byte[]> oldBin = new java.util.LinkedHashMap<>(UpdatePlaces.files(v1, v2));
        oldBin.remove(v2.binName());
        oldBin.put(TestPlaces.BIN, TestPlaces.bin());
        oldBin.put(PlaceBundles.CHECKSUMS, TestPlaces.checksums(oldBin));
        assertRefused(oldBin, "The place file places-2026-11-01.json is not usable: its boundaries places-2026-11-01.bin"
                + " are not on the list places.sha256. Reinstall Butterfly Atlas.");

        Map<String, byte[]> twoBins = new java.util.LinkedHashMap<>(UpdatePlaces.files(v1, v2));
        twoBins.put(TestPlaces.BIN, TestPlaces.bin());
        twoBins.put(PlaceBundles.CHECKSUMS, TestPlaces.checksums(twoBins));
        assertRefused(twoBins, "The place files places-2026-10-13.bin and places-2026-11-01.bin are both on the list"
                + " places.sha256; only the newest list's boundaries ship. Reinstall Butterfly Atlas.");
    }

    @Test
    void aListsChangesMustAgreeWithTheListBeforeIt() {
        UpdatePlaces.Built v1 = UpdatePlaces.fixture();
        UpdatePlaces.Built v2 = UpdatePlaces.fixtureNext();
        List<PlaceChange> changes = v2.changes();

        assertChangesRefused(v1, v2.withChanges(without(changes, "retire")), "place P9 (Westfar) is gone without a merge,"
                + " split or retire");
        assertChangesRefused(v1, v2.withChanges(replace(changes, "retire",
                new PlaceChange("retire", List.of("P8"), List.of(), "Farland retired"))), "its retire of P8 names P8, which is"
                + " still in the list or was never in the one before it");
        assertChangesRefused(v1, v2.withChanges(replace(changes, "merge",
                new PlaceChange("merge", List.of("P7"), List.of("P9"), "Islet merged into Westfar"))), "its merge of P7 does"
                + " not name one place of the list to merge into");
        assertChangesRefused(v1, v2.withChanges(replace(changes, "split",
                new PlaceChange("split", List.of("P4"), List.of("P11"), "Alpha split into Alpha West"))), "its split of P4"
                + " does not name one place split into two or more places of the list");
        assertChangesRefused(v1, v2.withChanges(replace(changes, "merge",
                new PlaceChange("merge", List.of("P7"), List.of("P10"), "Islet merged into Southstate"))), "its merge of P7"
                + " goes from a district to a state");
        assertChangesRefused(v1, v2.withChanges(replace(changes, "split",
                new PlaceChange("split", List.of("P4"), List.of("P11", "P10"), "Alpha split"))), "its split of P4"
                + " goes from a district to a state");
        List<PlaceChange> twice = new java.util.ArrayList<>(changes);
        twice.add(new PlaceChange("retire", List.of("P7"), List.of(), "Islet retired"));
        assertChangesRefused(v1, v2.withChanges(twice), "place P7 is in two merges, splits or retires");
        assertChangesRefused(v1, v2.withChanges(without(changes, "rename")), "its rename changes do not match the lists (P5)");
        List<PlaceChange> extraAdd = new java.util.ArrayList<>(changes);
        extraAdd.add(new PlaceChange("add", List.of(), List.of("P6"), "Lakeland added"));
        assertChangesRefused(v1, v2.withChanges(extraAdd), "its add changes do not match the lists (P6)");
        assertChangesRefused(v1, v2.withChanges(without(changes, "move")), "its move changes do not match the lists (P5)");
        assertChangesRefused(v1, v2.withChanges(List.of(new PlaceChange("swap", List.of(), List.of(), ""))),
                "it has a change of unknown type swap");

        List<PlaceDef> places = new java.util.ArrayList<>(v2.places());
        places.replaceAll(p -> p.id().equals("P3") ? new PlaceDef("P3", null, "country", p.name(), p.aliases(), p.codes()) : p);
        assertChangesRefused(v1, v2.withPlaces(places), "place P3 changes its level from state to country");

        UpdatePlaces.Built v3 = UpdatePlaces.derive(v2, "2026-12-01").add("P9", null, "country", "Westfar again").build();
        assertRefused(UpdatePlaces.files(v1, v2, v3), "The place file places-2026-12-01.json is not usable: it lists P9"
                + " again, which an earlier list gave up. Reinstall Butterfly Atlas.");
    }

    private static List<PlaceChange> without(List<PlaceChange> changes, String type) {
        return changes.stream().filter(c -> !c.type().equals(type)).toList();
    }

    private static List<PlaceChange> replace(List<PlaceChange> changes, String type, PlaceChange instead) {
        return changes.stream().map(c -> c.type().equals(type) ? instead : c).toList();
    }

    private static void assertChangesRefused(UpdatePlaces.Built v1, UpdatePlaces.Built v2, String why) {
        assertRefused(UpdatePlaces.files(v1, v2), "The place file places-2026-11-01.json is not usable: " + why + "."
                + " Reinstall Butterfly Atlas.");
    }

    @Test
    void aDamagedListOrBoundaryFileIsRefusedAtOnce() {
        Map<String, byte[]> damagedJson = new java.util.LinkedHashMap<>(TestPlaces.files());
        damagedJson.put(TestPlaces.JSON, TestPlaces.json().replace("Alpha", "Alpho").getBytes(StandardCharsets.UTF_8));
        assertRefused(damagedJson, "The place file places-2026-10-13.json is damaged. Reinstall Butterfly Atlas.");

        Map<String, byte[]> damagedBin = new java.util.LinkedHashMap<>(TestPlaces.files());
        byte[] bin = damagedBin.get(TestPlaces.BIN).clone();
        bin[bin.length / 2] ^= 1;
        damagedBin.put(TestPlaces.BIN, bin);
        assertRefused(damagedBin, "The place file places-2026-10-13.bin is damaged. Reinstall Butterfly Atlas.");
    }

    @Test
    void aFileNotOnTheListOrMissingFromItIsRefused() {
        Map<String, byte[]> unlisted = new java.util.LinkedHashMap<>(TestPlaces.files());
        unlisted.put("places-2026-11-01.json", TestPlaces.json("2026-11-01", TestPlaces.BIN).getBytes(StandardCharsets.UTF_8));
        assertRefused(unlisted, "The place file places-2026-11-01.json is not on the list places.sha256."
                + " Reinstall Butterfly Atlas.");

        Map<String, byte[]> missing = new java.util.LinkedHashMap<>(TestPlaces.files());
        missing.remove(TestPlaces.BIN);
        assertRefused(missing, "The place file places-2026-10-13.bin is missing. Reinstall Butterfly Atlas.");

        Map<String, byte[]> noList = new java.util.LinkedHashMap<>(TestPlaces.files());
        noList.remove(PlaceBundles.CHECKSUMS);
        assertRefused(noList, "The place list places.sha256 is missing. Reinstall Butterfly Atlas.");

        Map<String, byte[]> badList = new java.util.LinkedHashMap<>(TestPlaces.files());
        badList.put(PlaceBundles.CHECKSUMS, "not a checksum\n".getBytes(StandardCharsets.UTF_8));
        assertRefused(badList, "The place list places.sha256 is damaged at line 1. Reinstall Butterfly Atlas.");

        Map<String, byte[]> nothing = new java.util.LinkedHashMap<>();
        nothing.put(PlaceBundles.CHECKSUMS, new byte[0]);
        assertRefused(nothing, "No place data is bundled with this copy of Butterfly Atlas. Reinstall Butterfly Atlas.");
    }

    @Test
    void aListThatBreaksTheFormatIsRefused() {
        assertBadJson(TestPlaces.json().replace("\"format\": 1", "\"format\": 2"), "format 2");
        assertBadJson(TestPlaces.json().replace("\"parent\": \"P2\", \"level\": \"district\", \"name\": \"Beta\"",
                "\"parent\": \"P7\", \"level\": \"district\", \"name\": \"Beta\""), "P5");
        assertBadJson(TestPlaces.json().replace("\"id\": \"P5\"", "\"id\": \"P4\""), "P4 twice");
        assertBadJson(TestPlaces.json().replace("\"level\": \"state\", \"name\": \"Leafstate\"",
                "\"level\": \"town\", \"name\": \"Leafstate\""), "P3");
        assertBadJson(TestPlaces.json().replace("\"name\": \"Beta\"", "\"name\": \" \""), "P5");
        assertBadJson(TestPlaces.json().replace("\"id\": \"P1\", \"parent\": null, \"level\": \"country\"",
                "\"id\": \"P1\", \"parent\": null, \"level\": \"state\""), "P1");
        assertBadJson(TestPlaces.json().replace("\"aliases\": [\"Alfa\"]", "\"aliases\": null"), "P4");
        assertBadJson(TestPlaces.json().replace("\"id\": \"P9\"", "\"id\": \"X9\""), "X9");
        assertBadJson(TestPlaces.json("2026-10-14", TestPlaces.BIN), "2026-10-14");
        assertBadJson(TestPlaces.json(TestPlaces.VERSION, "places-2026-01-01.bin"), "places-2026-01-01.bin");
        assertBadJson("{ not json", "could not be read");
    }

    @Test
    void boundariesOfAPlaceTheListDoesNotHaveAreRefusedOnFirstUse() {
        Map<String, byte[]> files = new java.util.LinkedHashMap<>(TestPlaces.files());
        java.util.List<PlaceBin.Area> areas = new java.util.ArrayList<>(TestPlaces.areas());
        areas.add(PlaceBin.box("P99", 90, 10, 91, 11));
        files.put(TestPlaces.BIN, PlaceBin.encode(areas));
        files.put(PlaceBundles.CHECKSUMS, TestPlaces.checksums(files));
        PlaceBundles bundles = PlaceBundles.load(files);

        assertThatThrownBy(() -> bundles.index().lookup(18.5, 73.5)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("P99");
    }

    @Test
    void theBundleIsFoundInsideAJarAsInTheInstalledApp() throws Exception {
        Path jar = dir.resolve("app.jar");
        try (FileSystem zip = FileSystems.newFileSystem(URI.create("jar:" + jar.toUri()), Map.of("create", "true"))) {
            Path places = Files.createDirectories(zip.getPath("places"));
            for (Map.Entry<String, byte[]> file : TestPlaces.files().entrySet()) {
                Files.write(places.resolve(file.getKey()), file.getValue());
            }
            Files.writeString(places.resolve("NOTICE.txt"), "sources and licences");
        }

        PlaceBundles bundles = PlaceBundles.fromResource(URI.create("jar:" + jar.toUri() + "!/places/places.sha256").toURL());

        assertThat(bundles.version()).isEqualTo("2026-10-13");
        assertThat(bundles.index().lookup(18.5, 74.5)).contains("P5");
        PlaceBundles again = PlaceBundles.fromResource(URI.create("jar:" + jar.toUri() + "!/places/places.sha256").toURL());
        assertThat(again.places()).hasSize(9);
    }

    @Test
    void theBundleIsFoundInAFolderAsWhenRunFromTheBuild() throws Exception {
        Path places = TestPlaces.write(dir.resolve("classes/places"), TestPlaces.files());

        PlaceBundles bundles = PlaceBundles.fromResource(places.resolve(PlaceBundles.CHECKSUMS).toUri().toURL());

        assertThat(bundles.places()).hasSize(9);
    }

    @Test
    void withoutABundleOnTheClassPathTheMessageSaysSo() {
        assertThatThrownBy(() -> PlaceBundles.fromResource(null)).isInstanceOf(PlaceBundleException.class)
                .hasMessage("The place list places.sha256 is missing. Reinstall Butterfly Atlas.");
    }

    private static void assertBadJson(String json, String message) {
        Map<String, byte[]> files = new java.util.LinkedHashMap<>(TestPlaces.files());
        files.put(TestPlaces.JSON, json.getBytes(StandardCharsets.UTF_8));
        files.put(PlaceBundles.CHECKSUMS, TestPlaces.checksums(files));
        assertThatThrownBy(() -> PlaceBundles.load(files)).isInstanceOf(PlaceBundleException.class)
                .hasMessageContaining(message).hasMessageEndingWith("Reinstall Butterfly Atlas.");
    }

    private static void assertRefused(Map<String, byte[]> files, String message) {
        assertThatThrownBy(() -> PlaceBundles.load(files)).isInstanceOf(PlaceBundleException.class).hasMessage(message);
    }
}
