package com.butterflyatlas.app.places;

import com.butterflyatlas.app.AtlasException;
import com.butterflyatlas.app.imports.ImportPlaceChoices;
import com.butterflyatlas.app.library.Library;
import com.butterflyatlas.app.photos.Photo;
import com.butterflyatlas.app.photos.PhotoPlaces;
import com.butterflyatlas.app.testing.TestBundles;
import com.butterflyatlas.app.testing.TestClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.LongStream;

import static com.butterflyatlas.app.places.UserPlacesTest.assertError;
import static com.butterflyatlas.app.places.UserPlacesTest.sql;
import static com.butterflyatlas.app.places.UserPlacesTest.strings;
import static org.assertj.core.api.Assertions.assertThat;

/** Setting and removing the place of photos (3c-2 contract §1 "Photos"). */
class PhotoPlacesTest {

    @TempDir
    Path dir;

    private Library library;
    private final TestClock clock = new TestClock();
    private PhotoPlaces places;
    private UserPlaces userPlaces;
    private long gps;
    private long none;
    private long user;

    @BeforeEach
    void setUp() {
        library = TestBundles.fixtureLibrary(dir);
        places = new PhotoPlaces(() -> library, clock);
        userPlaces = new UserPlaces(() -> library.database(), clock, new PlaceSearch(() -> library.database()),
                new ImportPlaceChoices());
        gps = TestBundles.addImage(library.database(), "A5");
        none = TestBundles.addImage(library.database(), "A5");
        user = TestBundles.addImage(library.database(), "A6");
        sql(library.database(), "UPDATE image SET place_id = 'P4', place_source = 'gps' WHERE id = " + gps);
        sql(library.database(), "UPDATE image SET place_id = 'P6', place_source = 'user' WHERE id = " + user);
    }

    private List<String> placesOfPhotos() {
        return strings(library.database(), "SELECT id || ' ' || coalesce(place_id, '-') || ' ' || coalesce(place_source, '-')"
                + " FROM image ORDER BY id");
    }

    @Test
    void settingAPlaceMarksEveryPhotoAsTheUsersInOneGoAndAnswersThePhotosInTheOrderGiven() {
        List<Photo> photos = places.place(List.of(user, gps, none, gps), "P5", false);

        assertThat(photos).extracting(Photo::id).containsExactly(user, gps, none);
        assertThat(photos).allSatisfy(p -> {
            assertThat(p.place().id()).isEqualTo("P5");
            assertThat(p.place().path()).containsExactly("Beta", "Northstate", "Testland");
            assertThat(p.place().source()).isEqualTo("user");
        });
        assertThat(placesOfPhotos()).containsExactly(gps + " P5 user", none + " P5 user", user + " P5 user");
        assertThat(strings(library.database(), "SELECT kind || ' ' || at || ' ' || payload_json FROM change_log ORDER BY id"))
                .containsExactly(
                        "photo.place 2026-10-11T09:00:00Z {\"id\":" + user + ",\"placeId\":\"P5\",\"fromPlaceId\":\"P6\",\"fromSource\":\"user\"}",
                        "photo.place 2026-10-11T09:00:00Z {\"id\":" + gps + ",\"placeId\":\"P5\",\"fromPlaceId\":\"P4\",\"fromSource\":\"gps\"}",
                        "photo.place 2026-10-11T09:00:00Z {\"id\":" + none + ",\"placeId\":\"P5\",\"fromPlaceId\":null,\"fromSource\":null}");
        assertThat(userPlaces.recent()).extracting(PlaceSearch.Place::id).containsExactly("P5");
    }

    @Test
    void removingThePlaceLeavesThePhotosUnknownPlaceAsTheUsersAndTouchesNoRecentPlace() {
        List<Photo> photos = places.place(List.of(gps, user), null, true);

        assertThat(photos).extracting(Photo::place).containsOnlyNulls();
        assertThat(placesOfPhotos()).containsExactly(gps + " - user", none + " - -", user + " - user");
        assertThat(strings(library.database(), "SELECT payload_json FROM change_log ORDER BY id")).containsExactly(
                "{\"id\":" + gps + ",\"placeId\":null,\"fromPlaceId\":\"P4\",\"fromSource\":\"gps\"}",
                "{\"id\":" + user + ",\"placeId\":null,\"fromPlaceId\":\"P6\",\"fromSource\":\"user\"}");
        assertThat(userPlaces.recent()).isEmpty();
    }

    @Test
    void aPhotoWhosePlaceStaysAsItWasIsNotRecordedAndAChoiceThatChangesNothingIsNotRecent() {
        places.place(List.of(user), "P6", false);
        assertThat(strings(library.database(), "SELECT COUNT(*) FROM change_log")).containsExactly("0");
        assertThat(userPlaces.recent()).as("nothing changed").isEmpty();

        places.place(List.of(user, gps), "P6", false);
        assertThat(placesOfPhotos()).contains(gps + " P6 user", user + " P6 user");
        assertThat(strings(library.database(), "SELECT payload_json FROM change_log ORDER BY id")).containsExactly(
                "{\"id\":" + gps + ",\"placeId\":\"P6\",\"fromPlaceId\":\"P4\",\"fromSource\":\"gps\"}");
        assertThat(userPlaces.recent()).extracting(PlaceSearch.Place::id).containsExactly("P6");

        places.place(List.of(none), null, true);
        places.place(List.of(none, gps), null, true);
        assertThat(strings(library.database(), "SELECT payload_json FROM change_log ORDER BY id")).as("no place, now as the user's")
                .containsExactly(
                        "{\"id\":" + gps + ",\"placeId\":\"P6\",\"fromPlaceId\":\"P4\",\"fromSource\":\"gps\"}",
                        "{\"id\":" + none + ",\"placeId\":null,\"fromPlaceId\":null,\"fromSource\":null}",
                        "{\"id\":" + gps + ",\"placeId\":null,\"fromPlaceId\":\"P6\",\"fromSource\":\"user\"}");
    }

    @Test
    void theLatestPlaceIsTheNewestRecentOne() {
        places.place(List.of(gps), "P5", false);
        clock.advance(Duration.ofMinutes(1));
        places.place(List.of(gps), "P4", false);
        clock.advance(Duration.ofMinutes(1));
        places.place(List.of(none), "P5", false);

        assertThat(userPlaces.recent()).extracting(PlaceSearch.Place::id).containsExactly("P5", "P4");
    }

    @Test
    void nothingChangesWhenAPhotoIsUnknownOrInRecentlyDeleted() {
        long deleted = TestBundles.addImage(library.database(), "A5");
        TestBundles.markDeleted(library.database(), deleted);
        List<String> before = placesOfPhotos();

        assertError(() -> places.place(List.of(gps, 999L), "P5", false), AtlasException.Kind.NOT_FOUND, null,
                "There is no photo 999 in your atlas.");
        assertError(() -> places.place(List.of(gps, deleted), null, true), AtlasException.Kind.NOT_FOUND, null,
                "There is no photo " + deleted + " in your atlas.");

        assertThat(placesOfPhotos()).isEqualTo(before);
        assertThat(strings(library.database(), "SELECT COUNT(*) FROM change_log")).containsExactly("0");
        assertThat(userPlaces.recent()).isEmpty();
    }

    @Test
    void anUnknownOrRetiredPlaceIsNotFound() {
        sql(library.database(), "UPDATE place SET active = 0 WHERE id = 'P7'");

        assertError(() -> places.place(List.of(gps), "P404", false), AtlasException.Kind.NOT_FOUND, "not_found",
                "There is no such place.");
        assertError(() -> places.place(List.of(gps), "P7", false), AtlasException.Kind.NOT_FOUND, "not_found",
                "There is no such place.");
        assertThat(placesOfPhotos()).contains(gps + " P4 gps");
    }

    @Test
    void theRequestNeedsPhotosAtMostTenThousandAndEitherAPlaceOrRemove() {
        assertError(() -> places.place(List.of(), "P5", false), AtlasException.Kind.INVALID, "invalid", "Choose at least one photo.");
        assertError(() -> places.place(null, "P5", false), AtlasException.Kind.INVALID, "invalid", "Choose at least one photo.");
        List<Long> many = new ArrayList<>(LongStream.rangeClosed(1, 10_001).boxed().toList());
        assertError(() -> places.place(many, "P5", false), AtlasException.Kind.INVALID, "invalid",
                "Choose at most 10,000 photos at a time.");
        assertError(() -> places.place(List.of(gps), "P5", true), AtlasException.Kind.INVALID, "invalid",
                "Choose a place, or remove the place.");
        assertError(() -> places.place(List.of(gps), null, false), AtlasException.Kind.INVALID, "invalid",
                "Choose a place, or remove the place.");
    }

    @Test
    void tenThousandPhotosArePlacedInOneGo() {
        sql(library.database(), "WITH RECURSIVE n(x) AS (SELECT 1 UNION ALL SELECT x + 1 FROM n WHERE x < 9997)"
                + " INSERT INTO image (taxon_id, rel_path, file_name, file_size, modified_at, sha256, added_at, source)"
                + " SELECT 'A5', 'images/bulk/p_' || x || '.jpg', 'p_' || x || '.jpg', 1, '2026-10-09T08:00:00Z', 'bulk' || x,"
                + " '2026-10-09T08:00:00Z', 'app' FROM n");
        List<Long> ids = strings(library.database(), "SELECT id FROM image ORDER BY id").stream().map(Long::valueOf).toList();

        assertThat(places.place(ids, "P5", false)).hasSize(10_000);
        assertThat(strings(library.database(), "SELECT COUNT(*) FROM image WHERE place_id = 'P5' AND place_source = 'user'"))
                .containsExactly("10000");
        assertThat(strings(library.database(), "SELECT COUNT(*) FROM change_log WHERE kind = 'photo.place'"))
                .containsExactly("10000");
    }
}
