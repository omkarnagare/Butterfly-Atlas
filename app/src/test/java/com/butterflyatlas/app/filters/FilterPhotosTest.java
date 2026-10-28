package com.butterflyatlas.app.filters;

import com.butterflyatlas.app.AtlasException;
import com.butterflyatlas.app.photos.PhotoQueries;
import com.butterflyatlas.app.testing.TestBundles;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Matching photos in pages (4a contract §3 Photos): order, cursors, limits and the gallery's photo shape. */
class FilterPhotosTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path dir;

    private FilterFixture f;

    @BeforeEach
    void open() {
        f = new FilterFixture(dir);
    }

    private FilterQuery.PhotoPage page(String filter, String after, int limit) {
        return f.query.photos(Filter.parse(filter), after, limit);
    }

    private List<Long> ids(FilterQuery.PhotoPage page) {
        return page.photos().stream().map(FilterQuery.FilterPhoto::id).toList();
    }

    /** Every matching photo, read {@code limit} at a time. */
    private List<Long> all(String filter, int limit) {
        List<Long> ids = new ArrayList<>();
        String after = null;
        int pages = 0;
        do {
            FilterQuery.PhotoPage page = page(filter, after, limit);
            assertThat(page.photos()).hasSizeLessThanOrEqualTo(limit);
            ids.addAll(ids(page));
            after = page.next();
            assertThat(++pages).as("pages").isLessThan(1_000);
        } while (after != null);
        return ids;
    }

    private static void outOfDate(ThrowingCallable call) {
        refused(call, "That page is out of date. Reload the results.");
    }

    private static void refused(ThrowingCallable call, String message) {
        assertThatThrownBy(call).isInstanceOfSatisfying(AtlasException.class, e -> {
            assertThat(e.kind()).isEqualTo(AtlasException.Kind.INVALID);
            assertThat(e.code()).isEqualTo("invalid");
            assertThat(e.getMessage()).isEqualTo(message);
        });
    }

    @Test
    void photosComeBySpeciesInTaxonomicOrderThenNumberThenId() {
        long p1 = f.photo("A12", null);
        long d1 = f.photo("A5", null);
        long d2 = f.photo("A5", null);
        long d3 = f.photo("A5", null);
        long g1 = f.photo("A6", null);
        long c1 = f.photo("A14", null);
        f.number(d1, 3);
        f.number(d2, 1);
        f.number(d3, null);
        f.number(p1, 1);

        assertThat(ids(page("", null, 500))).containsExactly(d3, d2, d1, g1, c1, p1);
        assertThat(page("", null, 500).next()).isNull();
    }

    @Test
    void anUnnumberedPhotoComesBeforeNumberZeroAndItsCursorSaysSo() {
        long zero = f.photo("A5", null);
        long none = f.photo("A5", null);
        f.number(zero, 0);

        assertThat(ids(page("", null, 500))).containsExactly(none, zero);
        String next = page("", null, 1).next();
        assertThat(new String(java.util.Base64.getUrlDecoder().decode(next))).isEqualTo("A5||" + none);
        assertThat(ids(page("", next, 1))).containsExactly(zero);
    }

    @Test
    void fileTypesNarrowThePhotos() {
        long raw = f.photo("A5", null);
        f.type(raw, "raw");
        f.type(f.photo("A5", null), "jpeg");

        assertThat(ids(page("types=raw", null, 200))).containsExactly(raw);
        assertThat(ids(page("types=png", null, 200))).isEmpty();
    }

    @Test
    void aPhotoIsTheGallerysPhotoWithItsSpecies() throws Exception {
        long id = f.photo("A5", "P4", "larva", "egg");

        ObjectNode expected = JSON.valueToTree(new PhotoQueries(() -> f.library, TestBundles.CLOCK).photo(id));
        expected.set("species", JSON.readTree("{\"id\":\"A5\",\"name\":\"Danaus chrysippus\",\"common\":\"Plain Tiger\"}"));

        assertThat(JSON.writeValueAsString(page("", null, 200))).isEqualTo("{\"photos\":[" + JSON.writeValueAsString(expected)
                + "],\"next\":null}");
        assertThat(JSON.writeValueAsString(expected)).contains("\"place\":{\"id\":\"P4\",\"path\":[\"Alpha\",\"Northstate\","
                + "\"Testland\"],\"ids\":[\"P4\",\"P2\",\"P1\"],\"source\":\"gps\",\"former\":false,\"level\":\"district\"}", "\"tags\":[\"egg\",\"larva\"]",
                "\"isCover\":true");
    }

    @Test
    void pagesFollowEachOtherWithNoGapsOrDuplicates() {
        List<Long> made = new ArrayList<>();
        for (String species : List.of("A12", "A5", "A13", "A6", "A8", "A14")) {
            for (int n = 0; n < 7; n++) {
                made.add(f.photo(species, n % 2 == 0 ? "P4" : null, n % 3 == 0 ? "larva" : "male"));
            }
        }
        List<Long> everything = ids(page("", null, 500));
        assertThat(everything).hasSize(42).containsExactlyInAnyOrderElementsOf(made);

        for (int limit : new int[] {1, 2, 3, 5, 7, 8, 41, 42, 43}) {
            assertThat(all("", limit)).as("limit " + limit).containsExactlyElementsOf(everything);
        }
        List<Long> filtered = ids(page("places=P4&tags=larva", null, 500));
        assertThat(filtered).hasSize(12);
        assertThat(all("places=P4&tags=larva", 5)).containsExactlyElementsOf(filtered);
        assertThat(page("", null, 42).next()).isNull();
        assertThat(page("", null, 41).next()).isNotNull();
    }

    @Test
    void aCursorStillWorksWhenItsPhotoIsDeleted() {
        List<Long> made = new ArrayList<>();
        for (int n = 0; n < 6; n++) {
            made.add(f.photo(n < 3 ? "A5" : "A12", null));
        }
        FilterQuery.PhotoPage first = page("", null, 2);
        assertThat(ids(first)).containsExactly(made.get(0), made.get(1));

        f.delete(made.get(1));
        f.delete(made.get(2));

        assertThat(ids(page("", first.next(), 2))).containsExactly(made.get(3), made.get(4));
    }

    @Test
    void aPageIsReadFromOneStateOfTheLibrary() {
        long a = f.photo("A5", null);
        long b = f.photo("A5", null);
        long c = f.photo("A12", null);
        boolean[] once = {false};
        // Another connection deletes one photo of the page and moves another out of the filter while the page is read.
        FilterQuery racing = new FilterQuery(f.library::database, conn -> {
            if (!once[0]) {
                once[0] = true;
                f.delete(b);
                f.sql("UPDATE image SET taxon_id = 'A13' WHERE id = " + c);
            }
        });

        assertThat(ids(racing.photos(Filter.parse("taxa=A5,A12"), null, 200))).containsExactly(a, b, c);
        assertThat(ids(page("taxa=A5,A12", null, 200))).containsExactly(a);
    }

    @Test
    void aPhotoNoLongerLiveOrInTheFilterIsLeftOutOfItsPage() {
        long a = f.photo("A5", null);
        long b = f.photo("A5", null);
        long c = f.photo("A12", null);
        long d = f.photo("A12", null);
        // The same connection changes the photos between the page's order keys and its photos.
        FilterQuery changing = new FilterQuery(f.library::database, conn -> {
            try (var st = conn.createStatement()) {
                st.executeUpdate("UPDATE image SET deleted_at = '2026-10-09T08:00:00Z', deleted_rel_path = '.deleted/x.jpg'"
                        + " WHERE id = " + b);
                st.executeUpdate("UPDATE image SET taxon_id = 'A13' WHERE id = " + c);
            }
        });

        FilterQuery.PhotoPage page = changing.photos(Filter.parse("taxa=A5,A12"), null, 3);

        assertThat(ids(page)).containsExactly(a);
        assertThat(ids(page("taxa=A5,A12", page.next(), 3))).as("the next page starts after the page's last key")
                .containsExactly(d);
    }

    @Test
    void aCursorWhosePhotoMovedToAnotherSpeciesGoesOnFromItsOldPosition() {
        long a = f.photo("A5", null);
        long b = f.photo("A5", null);
        long c = f.photo("A5", null);
        long d = f.photo("A12", null);
        FilterQuery.PhotoPage first = page("", null, 2);
        assertThat(ids(first)).containsExactly(a, b);

        f.sql("UPDATE image SET taxon_id = 'A12' WHERE id = " + b);

        assertThat(ids(page("", first.next(), 2))).as("b now comes first in A12").containsExactly(c, b);
        assertThat(all("", 2)).containsExactly(a, c, b, d);
    }

    @Test
    void aCursorWhosePhotoWasRenumberedGoesOnFromItsOldPosition() {
        long a = f.photo("A5", null);
        long b = f.photo("A5", null);
        long c = f.photo("A5", null);
        long d = f.photo("A12", null);
        FilterQuery.PhotoPage first = page("", null, 2);
        assertThat(ids(first)).containsExactly(a, b);

        f.number(b, 1);

        assertThat(ids(page("", first.next(), 2))).as("numbered photos come after the unnumbered").containsExactly(c, b);
        f.number(a, 2);
        f.number(c, 3);
        assertThat(ids(page("", first.next(), 2))).as("every number is after the old, unnumbered position")
                .containsExactly(b, a);
        assertThat(all("", 3)).containsExactly(b, a, c, d);
    }

    @Test
    void aCursorWhoseSpeciesWasEmptiedGoesOnWithTheNextSpecies() {
        List<Long> a5 = List.of(f.photo("A5", null), f.photo("A5", null), f.photo("A5", null));
        long d = f.photo("A6", null);
        long e = f.photo("A12", null);
        FilterQuery.PhotoPage first = page("", null, 2);
        assertThat(ids(first)).containsExactlyElementsOf(a5.subList(0, 2));

        a5.forEach(f::delete);

        assertThat(ids(page("", first.next(), 2))).as("A5 is still in the universe, with no photos").containsExactly(d, e);
        assertThat(page("", first.next(), 2).next()).isNull();
    }

    @Test
    void notPhotographedHasNoPhotosAndMissingStagesHasThoseSpeciesPhotos() {
        f.photo("A5", "P4", "egg", "larva", "pupa");
        long a6 = f.photo("A6", "P4", "egg");
        long a6b = f.photo("A6", "P5", "male");
        f.photo("A12", null);

        assertThat(page("status=not", null, 200).photos()).isEmpty();
        assertThat(page("status=not", null, 200).next()).isNull();
        assertThat(ids(page("stages=missing&places=P2", null, 200))).containsExactly(a6, a6b);
        assertThat(ids(page("stages=missing&places=P2&tags=male", null, 200))).as("tags are not used").containsExactly(a6, a6b);
    }

    @Test
    void theLimitIsBetween1And500AndDefaultsTo200() {
        assertThat(FilterQuery.limit(null)).isEqualTo(200);
        assertThat(FilterQuery.limit("")).isEqualTo(200);
        assertThat(FilterQuery.limit("1")).isEqualTo(1);
        assertThat(FilterQuery.limit("500")).isEqualTo(500);
        for (String bad : new String[] {"0", "501", "-3", "ten", "2.5", "99999999999"}) {
            refused(() -> FilterQuery.limit(bad), "The limit must be between 1 and 500.");
        }
        refused(() -> page("", null, 0), "The limit must be between 1 and 500.");
    }

    @Test
    void aBadOrStaleCursorIsRefused() {
        for (int n = 0; n < 4; n++) {
            f.photo("A5", null);
            f.photo("A12", null);
        }
        String next = page("taxa=A5", null, 2).next();
        assertThat(next).isNotNull();

        outOfDate(() -> page("", "garbage!", 2));
        outOfDate(() -> page("", next + "!", 2));
        outOfDate(() -> page("taxa=A12", next, 2));
        outOfDate(() -> page("", java.util.Base64.getUrlEncoder().encodeToString("A5|x|1".getBytes()), 2));
        assertThat(page("", next, 2).photos()).hasSize(2);
        f.retire("A5");
        f.sql("DELETE FROM image WHERE taxon_id = 'A5'");
        outOfDate(() -> page("", next, 2));
    }
}
