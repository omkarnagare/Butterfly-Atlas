package com.butterflyatlas.app.testing;

import com.butterflyatlas.app.places.PlaceBundles;
import com.butterflyatlas.app.places.PlaceDef;
import com.butterflyatlas.app.places.PlaceIndex;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The test-scope generator of place update chains (5c design P9). */
class UpdatePlacesTest {

    @TempDir
    Path dir;

    @Test
    void aBoundaryFileReadsBackAsItWasWritten() {
        List<PlaceBin.Area> areas = PlaceBin.decode(TestPlaces.bin());

        assertThat(areas).extracting(PlaceBin.Area::id).containsExactly("P4", "P5", "P6", "P7", "P3", "P8", "P9");
        assertThat(areas.get(2).rings()).hasSize(2);
        assertThat(areas.get(0).rings().get(0)[1]).containsExactly(74.0, 18.0);
        assertThat(PlaceBin.encode(areas)).isEqualTo(TestPlaces.bin());
    }

    @Test
    void aCutGivesEachSideItsPartAndAMergedPlaceKeepsItsNamesOnTheSurvivor() {
        UpdatePlaces.Built next = UpdatePlaces.fixtureNext();

        assertThat(next.place("P6").aliases()).as("Islet's name goes to Lakeland").containsExactly("Islet");
        assertThat(next.place("P5")).isEqualTo(new PlaceDef("P5", "P10", "district", "Betania", List.of("Beta"),
                java.util.Map.of()));
        PlaceIndex index = UpdatePlaces.bundles(UpdatePlaces.fixture(), next).index();
        assertThat(index.lookup(18.9, 73.01)).contains("P11");
        assertThat(index.lookup(18.1, 73.49)).contains("P11");
        assertThat(index.lookup(18.1, 73.51)).contains("P12");
        assertThat(index.lookup(18.9, 73.99)).contains("P12");
        assertThat(index.lookup(18.5, 74.5)).as("Betania keeps its area").contains("P5");
    }

    @Test
    void theRealNextListIsAcceptedAndMovesGpsPhotosWhereItSays() {
        List<UpdatePlaces.Built> real = UpdatePlaces.real();
        UpdatePlaces.Built next = UpdatePlaces.realNext();
        List<UpdatePlaces.Built> chain = new java.util.ArrayList<>(real);
        chain.add(next);

        PlaceBundles bundles = UpdatePlaces.bundles(chain.toArray(new UpdatePlaces.Built[0]));
        PlaceBundles shipped = PlaceBundles.fromClasspath();

        assertThat(bundles.version()).isEqualTo(UpdatePlaces.REAL_NEXT);
        assertThat(next.changes()).extracting(c -> c.type()).containsExactly("merge", "merge", "split", "retire", "retire",
                "rename", "move", "add", "add", "add", "add");
        assertThat(name(bundles, bundles.index().lookup(20.41, 72.85).orElseThrow())).as("Daman, merged into a new district")
                .isEqualTo("Dadra Nagar Haveli and Daman");
        String chittoor = shipped.index().lookup(13.3, 79.6).orElseThrow();
        assertThat(shipped.places().stream().filter(p -> p.id().equals(chittoor)).findFirst().orElseThrow().name())
                .isEqualTo("Chittoor");
        assertThat(name(bundles, bundles.index().lookup(13.3, 79.6).orElseThrow())).as("east of the cut").isEqualTo("Tirupati");
        assertThat(name(bundles, bundles.index().lookup(13.3, 79.0).orElseThrow())).as("west of it").isEqualTo("Annamayya");
        assertThat(name(bundles, bundles.index().lookup(19.12, 72.85).orElseThrow())).as("Mumbai Suburban's area")
                .isEqualTo("Mumbai");
        assertThat(bundles.index().lookup(10.57, 72.64)).as("Kavaratti, Lakshadweep").isEmpty();
        assertThat(bundles.index().lookup(18.5204, 73.8567)).as("Pune").isEqualTo(shipped.index().lookup(18.5204, 73.8567));
        assertThat(name(bundles, bundles.index().lookup(18.5204, 73.8567).orElseThrow())).isEqualTo("Pune City");
    }

    @Test
    void theRealNextListAppliesToALibraryOnTheShippedOneWithRecentPlacesInTheMergedDistricts() {
        PlaceBundles shipped = PlaceBundles.fromClasspath();
        com.butterflyatlas.app.library.Library library = com.butterflyatlas.app.library.Library.open(dir.resolve("Atlas"),
                TestBundles.fixture(), shipped, TestBundles.CLOCK);
        String dadra = shipped.index().lookup(20.27, 73.02).orElseThrow();
        String daman = shipped.index().lookup(20.41, 72.85).orElseThrow();
        library.database().write(c -> c.createStatement().executeUpdate("INSERT INTO place_recent (place_id, used_at) VALUES"
                + " ('" + dadra + "', '2026-10-17T00:00:00Z'), ('" + daman + "', '2026-10-17T01:00:00Z')"));
        List<UpdatePlaces.Built> chain = new java.util.ArrayList<>(UpdatePlaces.real());
        chain.add(UpdatePlaces.realNext());

        com.butterflyatlas.app.library.Library next = com.butterflyatlas.app.library.Library.open(dir.resolve("Atlas"),
                TestBundles.fixture(), UpdatePlaces.bundles(chain.toArray(new UpdatePlaces.Built[0])), TestBundles.CLOCK);

        assertThat(next.placeSteps()).containsExactly(UpdatePlaces.REAL_NEXT);
        String joined = next.database().read(c -> {
            try (var rs = c.createStatement().executeQuery("SELECT group_concat(place_id) FROM place_recent")) {
                rs.next();
                return rs.getString(1);
            }
        });
        assertThat(joined).isEqualTo(next.placeIndex().lookup(20.41, 72.85).orElseThrow());
    }

    @Test
    void theMainWritesAFolderTheAppAccepts() throws Exception {
        UpdatePlaces.main(new String[] {dir.resolve("next").toString()});

        assertThat(PlaceBundles.fromDirectory(dir.resolve("next")).version()).isEqualTo(UpdatePlaces.REAL_NEXT);
        try (var files = Files.list(dir.resolve("next"))) {
            assertThat(files.filter(f -> f.getFileName().toString().endsWith(".bin")).toList()).hasSize(1);
        }
    }

    private static String name(PlaceBundles bundles, String id) {
        return bundles.places().stream().filter(p -> p.id().equals(id)).findFirst().orElseThrow().name();
    }
}
