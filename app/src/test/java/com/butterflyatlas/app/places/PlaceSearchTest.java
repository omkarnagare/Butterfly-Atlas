package com.butterflyatlas.app.places;

import com.butterflyatlas.app.AtlasException;
import com.butterflyatlas.app.library.Library;
import com.butterflyatlas.app.testing.TestBundles;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static com.butterflyatlas.app.places.UserPlacesTest.bundled;
import static com.butterflyatlas.app.places.UserPlacesTest.ids;
import static com.butterflyatlas.app.places.UserPlacesTest.india;
import static com.butterflyatlas.app.places.UserPlacesTest.sql;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Finding places by name (3c-2 contract §1 "Places"). */
class PlaceSearchTest {

    @TempDir
    Path dir;

    private Library library;
    private PlaceSearch search;

    @BeforeEach
    void setUp() {
        library = TestBundles.fixtureLibrary(dir);
        search = new PlaceSearch(() -> library.database());
    }

    @Test
    void searchRanksExactThenPrefixThenWordPrefixThenAliasThenContainsWithIndiaFirstThenLevelThenName() {
        india(library.database());

        assertThat(ids(search.search("pun", null))).containsExactly(
                "P22",                // name prefix: India, district
                "P24",                // name prefix: India, state
                "P31", "P26",         // name prefix: Pakistan, states by name (Púnchal reads as punchal)
                "P27",                // a word inside the name
                "P28",                // another spelling
                "P30");               // contains
        assertThat(ids(search.search("pune", null))).startsWith("P22");
        assertThat(ids(search.search("  BOMBAY ", null))).containsExactly("P23");
        assertThat(ids(search.search("poo", null))).containsExactly("P22");
        assertThat(ids(search.search("punch", null))).containsExactly("P31");
        assertThat(ids(search.search("PÚN", null))).startsWith("P22");
    }

    @Test
    void anExactNameComesBeforeLongerNamesAndAPlaceComesOnceAtItsBestMatch() {
        india(library.database());
        bundled(library.database(), "P40", "P21", "district", "Punea", "Pune");

        assertThat(ids(search.search("pune", null))).containsExactly("P22", "P40");
    }

    @Test
    void searchReturnsPlacesWithTheirPathLevelAndWhetherTheUserAddedThem() {
        india(library.database());

        assertThat(search.search("mumbai", null)).containsExactly(
                new PlaceSearch.Place("P23", "Mumbai", List.of("Mumbai", "Maharashtra", "India"), "district", false));
    }

    @Test
    void shortQueriesRetiredPlacesAndCountriesWithStatesAreNotFoundButCountriesWithoutStatesAre() {
        india(library.database());
        sql(library.database(), "UPDATE place SET active = 0 WHERE id = 'P30'");

        assertThat(search.search("p", null)).isEmpty();
        assertThat(search.search(" ", null)).isEmpty();
        assertThat(search.search(null, null)).isEmpty();
        assertThat(ids(search.search("kapunda", null))).isEmpty();
        assertThat(ids(search.search("india", null))).isEmpty();
        assertThat(ids(search.search("testland", null))).isEmpty();
        assertThat(ids(search.search("farland", null))).containsExactly("P8");
        assertThat(search.search("farland", null).get(0).path()).containsExactly("Farland");
    }

    @Test
    void searchCanBeLimitedToOneLevel() {
        india(library.database());

        assertThat(ids(search.search("pun", "state"))).containsExactly("P24", "P31", "P26");
        assertThatThrownBy(() -> search.search("pun", "town")).isInstanceOfSatisfying(AtlasException.class, e -> {
            assertThat(e.kind()).isEqualTo(AtlasException.Kind.INVALID);
            assertThat(e.code()).isEqualTo("invalid");
        });
    }

    @Test
    void searchGivesAtMostTwentyPlaces() {
        for (int n = 1; n <= 25; n++) {
            bundled(library.database(), "P" + (100 + n), "P2", "district", "Zed " + n);
        }

        assertThat(search.search("zed", null)).hasSize(20);
    }

    @Test
    void anotherLibraryGetsItsOwnSearch() {
        india(library.database());
        assertThat(ids(search.search("pune", null))).containsExactly("P22");

        library = TestBundles.fixtureLibrary(dir.resolve("other"));

        assertThat(search.search("pune", null)).isEmpty();
    }
}
