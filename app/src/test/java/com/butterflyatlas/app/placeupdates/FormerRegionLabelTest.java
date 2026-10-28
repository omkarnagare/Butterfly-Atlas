package com.butterflyatlas.app.placeupdates;

import com.butterflyatlas.app.filters.Filter;
import com.butterflyatlas.app.filters.FilterQuery;
import com.butterflyatlas.app.photos.PhotoQueries;
import com.butterflyatlas.app.places.PlaceBundles;
import com.butterflyatlas.app.places.PlaceRules;
import com.butterflyatlas.app.places.PlaceSearch;
import com.butterflyatlas.app.places.PlaceStore;
import com.butterflyatlas.app.testing.UpdatePlaces;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A former first-level place is a "(former state)" in India and a "(former region)" everywhere else, in every view:
 * photos, the place lists and search, and the species list (polish after 5c).
 */
class FormerRegionLabelTest {

    @TempDir
    Path dir;

    /** Leafstate (P3) retired, with a photo in the user district Coast under it; {@code country} P1's name after the step. */
    private static List<String> shown(PlaceUpdateFixture f, String country) {
        long inCoast = f.add("A5", null);
        f.place(f.coast, inCoast);
        UpdatePlaces.Derived next = UpdatePlaces.derive(UpdatePlaces.fixture(), "2026-11-01").retire("P3");
        PlaceBundles chain = UpdatePlaces.bundles(UpdatePlaces.fixture(),
                country == null ? next.build() : next.rename("P1", country).build());
        f.step(chain, 2);

        FilterQuery query = new FilterQuery(() -> f.db);
        return List.of(
                f.db.read(c -> PlaceStore.views(c, List.of(f.coast))).get(f.coast).path().get(1),
                f.db.read(c -> PlaceRules.places(c, List.of("P3"))).get("P3").name(),
                new PlaceSearch(() -> f.db, chain).formerMatches("leafstate", null).getFirst().place().name(),
                new PhotoQueries(() -> f.library, f.clock).photos("A5").stream().filter(p -> p.id() == inCoast)
                        .findFirst().orElseThrow().place().path().get(1),
                query.exportSet(Filter.parse("taxa=A5&places=P3"), 3).rows().getFirst().state(),
                query.places(Filter.parse("places=P1")).stream().filter(r -> r instanceof FilterQuery.PlaceCount p
                        && p.id().equals("P3")).map(r -> ((FilterQuery.PlaceCount) r).name()).findFirst().orElseThrow());
    }

    @Test
    void outsideIndiaAFormerStateIsAFormerRegionEverywhere() {
        assertThat(shown(new PlaceUpdateFixture(dir).populated(), null)).containsOnly("Leafstate (former region)");
    }

    @Test
    void inIndiaAFormerStateStaysAFormerState() {
        assertThat(shown(new PlaceUpdateFixture(dir).populated(), "India")).containsOnly("Leafstate (former state)");
    }
}
