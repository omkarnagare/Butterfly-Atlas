package com.butterflyatlas.app.places;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** The place bundle committed in {@code app/src/main/resources/places/}, read with the app's own reader. */
class CommittedPlacesTest {

    private static PlaceBundles bundles;
    private static Map<String, PlaceDef> byId;

    @BeforeAll
    static void load() {
        bundles = PlaceBundles.fromClasspath();
        byId = bundles.places().stream().collect(Collectors.toMap(PlaceDef::id, Function.identity()));
    }

    private static PlaceDef at(double lat, double lon) {
        return byId.get(bundles.index().lookup(lat, lon).orElseThrow());
    }

    private static List<String> path(PlaceDef place) {
        List<String> names = new ArrayList<>();
        for (PlaceDef p = place; p != null; p = p.parentId() == null ? null : byId.get(p.parentId())) {
            names.add(p.name());
        }
        return names;
    }

    @Test
    void theBundleIsAcceptedWithEveryPlace() {
        assertThat(bundles.places()).hasSize(5562);
        assertThat(bundles.all()).extracting(PlaceBundles.Shipped::version).containsExactly("2026-10-13", "2026-10-16");
        assertThat(bundles.all().get(0).places()).as("the first list, kept for libraries on it").hasSize(5563);
        assertThat(bundles.newest().changes()).containsExactly(new PlaceChange("merge", List.of("P4245"), List.of("P4187"),
                "Brežice (Pečice) merged into Brežice"));
    }

    @Test
    void knownPlacesAreFound() {
        assertThat(bundles.index().lookup(18.5204, 73.8567)).as("Pune").contains("P414");
        assertThat(bundles.index().lookup(15, 70)).as("Arabian Sea").isEmpty();
        assertThat(at(34.37, 73.47).name()).as("Muzaffarabad").isEqualTo("Jammu and Kashmir");
        PlaceDef leh = at(34.1526, 77.5771);
        assertThat(leh.name()).isEqualTo("Leh Ladakh");
        assertThat(leh.level()).isEqualTo("district");
        assertThat(path(at(27.7172, 85.3240))).as("Kathmandu").last().isEqualTo("Nepal");
        assertThat(path(at(46.005, 15.575))).as("Pečice, Slovenia: merged into Brežice").containsExactly("Brežice", "Slovenia");
        assertThat(byId.get("P4187").aliases()).containsExactly("Brežice (Pečice)", "Krsko");
        assertThat(at(45.96, 15.49).name()).as("Krško").isEqualTo("Krško");
    }

    @Test
    void siblingsDifferAsSearchComparesNamesAndFormerNamesAreKept() {
        Map<String, List<String>> siblings = bundles.places().stream().collect(Collectors.groupingBy(
                p -> p.parentId() + "/" + PlaceSearch.fold(p.name()), Collectors.mapping(PlaceDef::id, Collectors.toList())));
        assertThat(siblings.values()).allSatisfy(ids -> assertThat(ids).hasSize(1));
        assertThat(byId.get("P407").aliases()).as("Mumbai Suburban").contains("Bombay Suburban");
        assertThat(byId.get("P731").aliases()).as("Varanasi").contains("Benares", "Banaras");
    }

    @Test
    void aLibraryOnTheFirstListTakesThe5cStepAndItsPhotoInBrezicePecicMovesToBrezice(@org.junit.jupiter.api.io.TempDir
                                                                                       java.nio.file.Path dir)
            throws Exception {
        java.util.Map<String, byte[]> first = new java.util.LinkedHashMap<>();
        first.put("places-2026-10-13.json", resource("places-2026-10-13.json"));
        first.put("places-2026-10-13.bin", com.butterflyatlas.app.testing.PlaceBin.encode(List.of())); // its borders don't ship
        first.put(PlaceBundles.CHECKSUMS, com.butterflyatlas.app.testing.TestPlaces.checksums(first));
        com.butterflyatlas.app.library.Library old = com.butterflyatlas.app.library.Library.open(dir,
                com.butterflyatlas.app.testing.TestBundles.fixture(), PlaceBundles.load(first),
                com.butterflyatlas.app.testing.TestBundles.CLOCK);
        long photo = com.butterflyatlas.app.testing.TestBundles.addImage(old.database(), "A5");
        old.database().write(c -> c.createStatement().executeUpdate("UPDATE image SET place_id = 'P4245', place_source = 'gps'"
                + " WHERE id = " + photo));

        com.butterflyatlas.app.library.Library now = com.butterflyatlas.app.library.Library.open(dir,
                com.butterflyatlas.app.testing.TestBundles.fixture(), bundles, com.butterflyatlas.app.testing.TestBundles.CLOCK);

        assertThat(now.placeSteps()).containsExactly("2026-10-16");
        String state = now.database().read(c -> {
            try (var rs = c.createStatement().executeQuery("SELECT (SELECT place_id FROM image WHERE id = " + photo + "),"
                    + " (SELECT active || ' ' || replaced_by FROM place WHERE id = 'P4245'),"
                    + " (SELECT touches FROM place_update WHERE version = '2026-10-16')")) {
                rs.next();
                return rs.getString(1) + " | " + rs.getString(2) + " | " + rs.getInt(3);
            }
        });
        assertThat(state).isEqualTo("P4187 | 0 P4187 | 1");
    }

    private static byte[] resource(String name) throws java.io.IOException {
        try (java.io.InputStream in = CommittedPlacesTest.class.getResourceAsStream(PlaceBundles.DIRECTORY + name)) {
            return in.readAllBytes();
        }
    }
}
