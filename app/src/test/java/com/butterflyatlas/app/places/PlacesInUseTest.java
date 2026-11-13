package com.butterflyatlas.app.places;

import com.butterflyatlas.app.imports.ImportPlaceChoices;
import com.butterflyatlas.app.library.Library;
import com.butterflyatlas.app.testing.TestBundles;
import com.butterflyatlas.app.testing.TestClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static com.butterflyatlas.app.places.UserPlacesTest.sql;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Place filter's list before anything is typed (2026-11-13 design, addendum §3): the places the live photos use, the
 * most photos first (then by name), each with its count, at most {@value UserPlaces#IN_USE_LIMIT}.
 */
class PlacesInUseTest {

    @TempDir
    Path dir;

    private Library library;
    private UserPlaces places;

    @BeforeEach
    void setUp() {
        library = TestBundles.fixtureLibrary(dir);
        places = new UserPlaces(() -> library.database(), new TestClock(), new PlaceSearch(() -> library.database()),
                new ImportPlaceChoices());
    }

    private long photoAt(String placeId) {
        long id = TestBundles.addImage(library.database(), "A5");
        if (placeId != null) {
            sql(library.database(), "UPDATE image SET place_id = '" + placeId + "', place_source = 'user' WHERE id = " + id);
        }
        return id;
    }

    @Test
    void theMostUsedPlacesComeFirstWithTheirPhotoCountsAndDeletedPhotosDontCount() {
        photoAt("P5");
        photoAt("P4");
        photoAt("P4");
        photoAt("P6");
        photoAt(null);
        TestBundles.markDeleted(library.database(), photoAt("P6"));
        TestBundles.markDeleted(library.database(), photoAt("P7"));

        assertThat(places.inUse()).extracting(p -> p.name() + " " + p.photos() + " " + String.join("/", p.path()))
                .containsExactly("Alpha 2 Alpha/Northstate/Testland", "Beta 1 Beta/Northstate/Testland",
                        "Lakeland 1 Lakeland/Northstate/Testland");
        assertThat(places.inUse().getFirst().id()).isEqualTo("P4");
        assertThat(places.inUse().getFirst().level()).isEqualTo("district");
    }

    @Test
    void atMostTwentyAreListed() {
        for (int n = 0; n < 25; n++) {
            sql(library.database(), "INSERT INTO place (id, parent_id, level, name, source, active, ancestor_path, sort_key)"
                    + " VALUES ('U" + n + "', 'P2', 'district', 'Place " + (char) ('A' + n) + "', 'user', 1, '/P1/P2/U" + n + "/',"
                    + " 'place " + (char) ('a' + n) + "')");
            photoAt("U" + n);
        }
        assertThat(places.inUse()).hasSize(UserPlaces.IN_USE_LIMIT).first().extracting(UserPlaces.InUse::name)
                .isEqualTo("Place A");
    }

    @Test
    void noPhotosWithAPlaceMeansAnEmptyList() {
        photoAt(null);
        assertThat(places.inUse()).isEmpty();
    }
}
