package com.butterflyatlas.app.placeupdates;

import com.butterflyatlas.app.filters.Filter;
import com.butterflyatlas.app.filters.FilterQuery;
import com.butterflyatlas.app.places.PlaceSearch;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The Export screen's filter after a place update step (5c contract §6 "Filter"). */
class FilterAfterUpdateTest {

    @TempDir
    Path dir;

    private PlaceUpdateFixture f;
    private FilterQuery query;

    @BeforeEach
    void setUp() {
        f = new PlaceUpdateFixture(dir).populated();
        f.step(PlaceUpdateFixture.next(), 2);
        query = new FilterQuery(() -> f.db);
    }

    private static List<String> rows(List<FilterQuery.PlaceRow> rows) {
        return rows.stream().map(r -> r instanceof FilterQuery.PlaceCount p ? p.id() + " " + p.name() + " " + p.photos()
                : ((FilterQuery.UnknownCount) r).id() + " " + ((FilterQuery.UnknownCount) r).photos()).toList();
    }

    @Test
    void aMergedPlaceInTheFilterIsReplacedByItsSurvivorAndSaidSo() {
        FilterQuery.FilterCount count = query.count(Filter.parse("places=P7"));

        assertThat(count.filter().query()).isEqualTo("places=P6");
        assertThat(count.filter().places()).extracting(p -> ((PlaceSearch.Place) p).id()).containsExactly("P6");
        assertThat(count.filter().placeNotes()).containsExactly(new FilterQuery.PlaceNote("P7", "merged", "Islet",
                new PlaceSearch.Place("P6", "Lakeland", List.of("Lakeland", "Northstate", "Testland"), "district", false)));
        assertThat(count.photos()).isEqualTo(2);
        assertThat(query.count(Filter.parse("places=P7,P6")).filter().places()).as("the survivor once").hasSize(1);
    }

    @Test
    void aUserPlaceMergedIntoAnOfficialOneIsReplacedByItAndSaidSo() {
        FilterQuery.FilterCount count = query.count(Filter.parse("places=" + f.myAlphaWest));

        assertThat(count.filter().query()).isEqualTo("places=P11");
        assertThat(count.filter().placeNotes()).extracting(n -> n.id() + " " + n.kind() + " " + n.name() + " " + n.into().id())
                .containsExactly(f.myAlphaWest + " merged Alpha West P11");
        assertThat(count.photos()).isEqualTo(1);
    }

    @Test
    void aFormerPlaceStillFiltersLabelledAndAPlaceNowhereIsListedAsGone() {
        FilterQuery.FilterCount count = query.count(Filter.parse("places=P4,P999"));

        assertThat(count.photos()).isEqualTo(3);
        assertThat(count.filter().places()).containsExactly(new PlaceSearch.Place("P4", "Alpha (former district)",
                List.of("Alpha (former district)", "Northstate", "Testland"), "district", false, true));
        assertThat(count.filter().placeNotes()).containsExactly(new FilterQuery.PlaceNote("P999", "gone", null, null));
    }

    @Test
    void thePlaceCountsListAFormerPlaceOnlyWhileItHasPhotosAndFollowMoves() {
        assertThat(rows(query.places(Filter.parse("")))).containsExactly("P1 Testland 8", "P9 Westfar (former country) 1",
                "unknown 1");
        assertThat(rows(query.places(Filter.parse("places=P1")))).containsExactly("P2 Northstate 7", "P10 Southstate 1");
        assertThat(rows(query.places(Filter.parse("places=P2")))).containsExactly("P4 Alpha (former district) 3",
                "P6 Lakeland 2", "P11 Alpha West 1", f.tamhini + " Tamhini 1");

        f.place("P11", f.alphaByHand);
        f.sql("UPDATE image SET place_id = 'P12' WHERE id IN (" + f.alphaWest + ", " + f.alphaEast + ")");

        assertThat(rows(query.places(Filter.parse("places=P2")))).as("no photos left: the former place is gone from the list")
                .containsExactly("P12 Alpha East 2", "P11 Alpha West 2", "P6 Lakeland 2", f.tamhini + " Tamhini 1");
    }

    @Test
    void theSpeciesListNamesAFormerPlaceLabelled() {
        List<FilterQuery.ListRow> rows = query.exportSet(Filter.parse("taxa=A12,A6"), 3).rows();

        assertThat(rows).extracting(r -> r.id() + " " + r.country() + " / " + r.state() + " / " + r.district() + " "
                + r.photos()).contains("A12 Westfar (former country) / null / null 1",
                "A6 Testland / Northstate / Alpha (former district) 1", "A6 Testland / Northstate / Lakeland 1");
    }
}
