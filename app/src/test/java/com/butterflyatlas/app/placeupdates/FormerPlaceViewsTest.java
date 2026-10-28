package com.butterflyatlas.app.placeupdates;

import com.butterflyatlas.app.imports.ImportStore;
import com.butterflyatlas.app.photos.PhotoQueries;
import com.butterflyatlas.app.places.PlaceBundles;
import com.butterflyatlas.app.places.PlaceRef;
import com.butterflyatlas.app.places.PlaceRules;
import com.butterflyatlas.app.places.PlaceSearch;
import com.butterflyatlas.app.places.PlaceStore;
import com.butterflyatlas.app.testing.UpdatePlaces;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Former and merged places as every view shows them after a step (5c contract §6 "Views"). */
class FormerPlaceViewsTest {

    @TempDir
    Path dir;

    @Test
    void anInactivePlaceIsLabelledFormerInItsPathAndAMergedOneSaysWhatItBecamePartOf() {
        PlaceUpdateFixture f = new PlaceUpdateFixture(dir).populated();
        UpdatePlaces.Built v3 = UpdatePlaces.derive(UpdatePlaces.fixtureNext(), "2026-12-01").retire("P3").build();
        f.step(UpdatePlaces.bundles(UpdatePlaces.fixture(), UpdatePlaces.fixtureNext(), v3), 2);
        f.step(UpdatePlaces.bundles(UpdatePlaces.fixture(), UpdatePlaces.fixtureNext(), v3), 3);

        Map<String, PlaceStore.PlaceView> views = f.db.read(c -> PlaceStore.views(c, List.of("P4", "P7", "P9", "P11",
                f.coast)));

        assertThat(views.get("P4")).isEqualTo(new PlaceStore.PlaceView("P4", List.of("Alpha (former district)",
                "Northstate", "Testland"), List.of("P4", "P2", "P1"), true, null, "district"));
        assertThat(views.get("P7")).isEqualTo(new PlaceStore.PlaceView("P7", List.of("Islet (former district)",
                "Northstate", "Testland"), List.of("P7", "P2", "P1"), true, "P6", "district"));
        assertThat(views.get("P9").path()).containsExactly("Westfar (former country)");
        assertThat(views.get("P9").level()).as("a photo's place says its own level").isEqualTo("country");
        assertThat(views.get("P11").former()).isFalse();
        assertThat(views.get(f.coast)).as("a user district under a retired state stays, under its former region (Testland is not India)")
                .isEqualTo(new PlaceStore.PlaceView(f.coast, List.of("Coast", "Leafstate (former region)", "Testland"),
                        List.of(f.coast, "P3", "P1"), false, null, "district"));
        Map<String, PlaceSearch.Place> places = f.db.read(c -> PlaceRules.places(c, List.of("P4", "P11")));
        assertThat(places.get("P4")).isEqualTo(new PlaceSearch.Place("P4", "Alpha (former district)",
                List.of("Alpha (former district)", "Northstate", "Testland"), "district", false, true));
        assertThat(places.get("P11").former()).isFalse();
    }

    @Test
    void aPlaceMergedTwiceNamesItsFinalSurvivor() {
        PlaceUpdateFixture f = new PlaceUpdateFixture(dir).populated();
        UpdatePlaces.Built v3 = UpdatePlaces.derive(UpdatePlaces.fixtureNext(), "2026-12-01").merge("P5", "P6").build();
        PlaceBundles chain = UpdatePlaces.bundles(UpdatePlaces.fixture(), UpdatePlaces.fixtureNext(), v3);
        f.step(chain, 2);
        f.step(chain, 3);

        Map<String, PlaceStore.PlaceView> views = f.db.read(c -> PlaceStore.views(c, List.of("P6", "P7")));

        assertThat(views.get("P7").replacedBy()).as("Islet → Lakeland → Betania").isEqualTo("P5");
        assertThat(views.get("P6").replacedBy()).isEqualTo("P5");
    }

    @Test
    void photosAndImportItemsInAFormerPlaceSayItAndSearchStillFindsOnlyActivePlaces() {
        PlaceUpdateFixture f = new PlaceUpdateFixture(dir).populated();
        f.step(PlaceUpdateFixture.next(), 2);

        PlaceRef byHand = new PhotoQueries(() -> f.library, f.clock).photos("A6").stream()
                .filter(p -> p.id() == f.alphaByHand).findFirst().orElseThrow().place();
        assertThat(byHand).isEqualTo(new PlaceRef("P4", List.of("Alpha (former district)", "Northstate", "Testland"),
                List.of("P4", "P2", "P1"), "user", true, "district"));
        List<ImportStore.Item> items = new ImportStore(() -> f.library, f.clock).items(f.run, null).items();
        assertThat(items).extracting(i -> i.place().id() + " " + i.place().former() + " " + i.place().path().getFirst())
                .containsExactly("P6 false Lakeland", "P4 true Alpha (former district)", "P11 false Alpha West",
                        "P5 false Betania");
        PlaceSearch search = new PlaceSearch(() -> f.db, PlaceUpdateFixture.next());
        assertThat(search.search("alpha", null)).extracting(PlaceSearch.Place::id).containsExactly("P12", "P11");
        assertThat(search.search("islet", null)).as("a merged place's name finds its survivor")
                .extracting(PlaceSearch.Place::id).containsExactly("P6");
        assertThat(search.formerMatches("islet", null)).extracting(m -> m.place().name() + " → "
                + m.now().stream().map(PlaceSearch.Place::id).toList()).containsExactly("Islet (former district) → [P6]");
        assertThat(search.formerMatches("alpha", null)).as("a split: its places still active")
                .extracting(m -> m.place().id() + " → " + m.now().stream().map(PlaceSearch.Place::id).toList())
                .containsExactly("P4 → [P11, P12]");
        assertThat(search.formerMatches("westfar", null)).extracting(m -> m.place().id() + " " + m.now())
                .as("retired: nothing now").containsExactly("P9 []");
        assertThat(search.formerMatches("alpha", "state")).isEmpty();
    }
}
