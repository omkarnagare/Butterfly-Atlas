package com.butterflyatlas.app.placeupdates;

import com.butterflyatlas.app.db.Database;
import com.butterflyatlas.app.db.TestDatabases;
import com.butterflyatlas.app.library.Library;
import com.butterflyatlas.app.places.PlaceBundles;
import com.butterflyatlas.app.testing.TestBundles;
import com.butterflyatlas.app.testing.UpdatePlaces;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A library brought up to the newest place list when it opens, step by step, through crashes (5c contract §4). */
class PlaceChainTest {

    @TempDir
    Path dir;

    private static final UpdatePlaces.Built V1 = UpdatePlaces.fixture();
    private static final UpdatePlaces.Built V2 = UpdatePlaces.fixtureNext();
    private static final UpdatePlaces.Built V3 = UpdatePlaces.derive(V2, "2026-12-01").rename("P12", "Alpha Rise")
            .merge("P5", "P6").build();

    private static PlaceBundles chain() {
        return UpdatePlaces.bundles(V1, V2, V3);
    }

    private static final String STATE = "SELECT p.id, p.parent_id, p.name, p.active, p.replaced_by, p.ancestor_path,"
            + " (SELECT group_concat(a.name, ',') FROM (SELECT name FROM place_alias WHERE place_id = p.id ORDER BY name) a)"
            + " FROM place p ORDER BY p.id";

    @Test
    void aNewLibraryLoadsTheNewestListWithoutSteps() {
        PlaceUpdateFixture f = new PlaceUpdateFixture(dir, chain());

        assertThat(f.library.placeSteps()).isEmpty();
        assertThat(f.rows("SELECT bundle_version FROM place_state")).containsExactly("2026-12-01");
        assertThat(f.rows("SELECT COUNT(*) FROM place_update")).containsExactly("0");
        assertThat(f.rows("SELECT id FROM place WHERE id IN ('P4', 'P6', 'P7', 'P12')")).as("only the newest list's places")
                .containsExactly("P12");
    }

    @Test
    void aLibraryThatSkippedAListTakesEveryStepInOrderAtOneOpening() {
        PlaceUpdateFixture f = new PlaceUpdateFixture(dir).populated();

        Library library = Library.open(f.library.folder(), TestBundles.fixture(), chain(), f.clock);

        assertThat(library.placeSteps()).containsExactly("2026-11-01", "2026-12-01");
        assertThat(f.rows("SELECT bundle_version FROM place_state ORDER BY rowid")).containsExactly("2026-10-13",
                "2026-11-01", "2026-12-01");
        assertThat(f.rows("SELECT version, from_version, touches FROM place_update ORDER BY version"))
                .containsExactly("2026-11-01|2026-10-13|1", "2026-12-01|2026-11-01|1");
        assertThat(f.places()).contains(f.islet + "|P5|gps", f.isletDeleted + "|P5|gps", f.alphaWest + "|P4|gps");
        assertThat(library.placeIndex().lookup(18.5, 73.75)).contains("P12");

        Library again = Library.open(f.library.folder(), TestBundles.fixture(), chain(), f.clock);

        assertThat(again.placeSteps()).as("nothing left to do").isEmpty();
        assertThat(f.rows("SELECT COUNT(*) FROM place_state")).containsExactly("3");
    }

    @Test
    void aCrashInsideAStepUndoesItAndTheNextOpeningAppliesItAgainAsIfNothingHappened() {
        PlaceUpdateFixture uninterrupted = new PlaceUpdateFixture(dir.resolve("a")).populated();
        Library.open(uninterrupted.library.folder(), TestBundles.fixture(), chain(), uninterrupted.clock);
        PlaceUpdateFixture f = new PlaceUpdateFixture(dir.resolve("b")).populated();
        List<String> before = f.rows(STATE);
        List<String> photosBefore = f.places();

        Database crashing = Database.open(f.library.database().file());
        TestDatabases.beforeCommit(crashing, () -> {
            throw new IllegalStateException("killed");
        });
        assertThatThrownBy(() -> PlaceChain.bringUpToDate(crashing, chain(), f.clock))
                .isInstanceOfSatisfying(PlaceUpdateException.class, e -> {
                    assertThat(e.from()).isEqualTo("2026-10-13");
                    assertThat(e.to()).isEqualTo("2026-11-01");
                    assertThat(e.getCause()).hasMessage("killed");
                });
        assertThat(f.rows(STATE)).isEqualTo(before);
        assertThat(f.places()).isEqualTo(photosBefore);
        assertThat(f.rows("SELECT COUNT(*) FROM change_log WHERE kind LIKE 'bundled.%' OR kind = 'place.step'"))
                .containsExactly("0");

        Library.open(f.library.folder(), TestBundles.fixture(), chain(), f.clock);

        assertThat(f.rows(STATE)).isEqualTo(uninterrupted.rows(STATE));
        assertThat(f.places()).isEqualTo(uninterrupted.places());
    }

    @Test
    void aCrashAfterAStepKeepsItAndTheNextOpeningGoesOnFromTheNext() {
        PlaceUpdateFixture uninterrupted = new PlaceUpdateFixture(dir.resolve("a")).populated();
        Library.open(uninterrupted.library.folder(), TestBundles.fixture(), chain(), uninterrupted.clock);
        PlaceUpdateFixture f = new PlaceUpdateFixture(dir.resolve("b")).populated();

        Database crashing = Database.open(f.library.database().file());
        AtomicInteger commits = new AtomicInteger();
        TestDatabases.beforeCommit(crashing, () -> {
            if (commits.incrementAndGet() == 2) {
                throw new IllegalStateException("killed");
            }
        });
        assertThatThrownBy(() -> PlaceChain.bringUpToDate(crashing, chain(), f.clock))
                .isInstanceOfSatisfying(PlaceUpdateException.class, e -> assertThat(e.to()).isEqualTo("2026-12-01"));
        assertThat(f.rows("SELECT version FROM place_update")).containsExactly("2026-11-01");

        Library library = Library.open(f.library.folder(), TestBundles.fixture(), chain(), f.clock);

        assertThat(library.placeSteps()).containsExactly("2026-12-01");
        assertThat(f.rows(STATE)).isEqualTo(uninterrupted.rows(STATE));
        assertThat(f.places()).isEqualTo(uninterrupted.places());
        assertThat(f.rows("SELECT COUNT(*) FROM change_log WHERE kind = 'place.step'")).containsExactly("2");
    }

    @Test
    void theNewestListRebuiltWithAnotherChecksumKeepsTheNamesItsStepsCarried() {
        PlaceUpdateFixture f = new PlaceUpdateFixture(dir).populated();
        Library.open(f.library.folder(), TestBundles.fixture(), UpdatePlaces.bundles(V1, V2), f.clock);
        UpdatePlaces.Built rebuilt = V2.withPlaces(V2.places().stream().map(p -> p.id().equals("P6") || p.id().equals("P5")
                ? new com.butterflyatlas.app.places.PlaceDef(p.id(), p.parentId(), p.level(), p.name(), List.of(), p.codes())
                : p).toList());

        Library.open(f.library.folder(), TestBundles.fixture(), UpdatePlaces.bundles(V1, rebuilt), f.clock);

        assertThat(f.rows("SELECT place_id, name FROM place_alias WHERE place_id IN ('P5', 'P6') ORDER BY 1, 2"))
                .as("the merged place's and the old name stay").containsExactly("P5|Beta", "P6|Islet");
        assertThat(f.rows("SELECT COUNT(*) FROM place_state")).containsExactly("3");
    }

    @Test
    void aLibraryOnPlaceDataThisAppDoesNotShipIsRefusedNotGuessedAt() {
        PlaceUpdateFixture f = new PlaceUpdateFixture(dir).populated();
        f.sql("INSERT INTO place_state (bundle_version, bundle_sha256, applied_at) VALUES ('2027-02-01', 'x',"
                + " '2027-02-02T00:00:00Z')");
        List<String> before = f.rows(STATE);

        assertThatThrownBy(() -> Library.open(f.library.folder(), TestBundles.fixture(), chain(), f.clock))
                .isInstanceOf(PlaceDataUnavailableException.class)
                .hasMessage("This library uses place data from a newer version of Butterfly Atlas (2027-02-01). Install that"
                        + " version or a newer one to open it.");
        assertThat(f.rows(STATE)).isEqualTo(before);
    }
}
