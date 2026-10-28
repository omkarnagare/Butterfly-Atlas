package com.butterflyatlas.app.filters;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * The species list of an export (4b contract §4): one row per species in taxonomic order, or per species per place at
 * the chosen depth, with the photo count and tags of the row's matching photos.
 */
class FilterListTest {

    @TempDir
    Path dir;

    private FilterFixture f;

    @BeforeEach
    void open() {
        f = new FilterFixture(dir);
    }

    /** "id country/state/district photos [tags] missing source" per row. */
    private List<String> rows(String filter, int depth) {
        return f.query.exportSet(Filter.parse(filter), depth).rows().stream()
                .map(r -> r.id() + " " + (depth == 0 ? "" : nz(r.country()) + "/" + nz(r.state()) + "/" + nz(r.district()) + " ")
                        + r.photos() + " " + r.tags() + (r.missing().isEmpty() ? "" : " " + r.missing()) + " " + r.source())
                .toList();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    @Test
    void oneRowPerSpeciesInTaxonomicOrderWithItsNamesAndTagsInTagOrder() {
        f.photo("A5", "P4", "male", "larva");
        f.photo("A5", null, "egg");
        f.photo("A12", "P8");

        FilterQuery.ExportSet set = f.query.exportSet(Filter.parse(""), 0);

        assertThat(set.species()).isEqualTo(6);
        assertThat(rows("", 0)).containsExactly("A5 2 [egg, larva, male] official", "A6 0 [] official",
                "A8 0 [] official", "A14 0 [] official", "A13 0 [] official", "A12 1 [] official");
        FilterQuery.ListRow first = set.rows().get(0);
        assertThat(first).isEqualTo(new FilterQuery.ListRow("A5", "Nymphalidae", "Danainae", "Danaini", "Danaus",
                "Danaus chrysippus", "Plain Tiger", null, null, null, 2, List.of("egg", "larva", "male"), List.of(), "official"));
        assertThat(set.rows().get(3).tribe()).as("Papilio has no tribe").isNull();
    }

    @Test
    void photoCountsAndTagsCountOnlyMatchingPhotos() {
        f.photo("A5", "P4", "male", "larva");
        f.photo("A5", "P3", "female");
        f.photo("A6", "P3", "female", "upper_wing");

        assertThat(rows("status=photographed&tags=female", 0)).containsExactly("A5 1 [female] official",
                "A6 1 [upper wing, female] official");
        assertThat(rows("places=P2&status=photographed", 0)).containsExactly("A5 1 [larva, male] official");
        assertThat(rows("status=not&taxa=A4&tags=male", 0)).containsExactly("A6 0 [] official");
    }

    @Test
    void missingLifeStagesListsWhatEachSpeciesLacks() {
        f.photo("A5", "P4", "egg", "male");
        f.photo("A12", "P4", "larva");

        assertThat(rows("stages=missing", 0)).containsExactly("A5 1 [egg, male] [larva, pupa] official",
                "A12 1 [larva] [egg, pupa] official");
    }

    @Test
    void placeRowsAtDistrictDepthFillOnlyTheLevelsAPhotoHasAndUnknownComesLast() {
        f.photo("A5", "P5", "male");
        f.photo("A5", "P4", "larva");
        f.photo("A5", "P4");
        f.photo("A5", "P2", "egg");
        f.photo("A5", null, "pupa");
        f.photo("A5", "P404");
        f.photo("A5", "P8");
        f.photo("A12", "P3");

        assertThat(rows("taxa=A4,A12", 3)).containsExactly(
                "A5 Farland// 1 [] official",
                "A5 Testland/Northstate/ 1 [egg] official",
                "A5 Testland/Northstate/Alpha 2 [larva] official",
                "A5 Testland/Northstate/Beta 1 [male] official",
                "A5 // 2 [pupa] official",
                "A6 // 0 [] official",
                "A12 Testland/Leafstate/ 1 [] official");
    }

    @Test
    void placeRowsAtCountryAndStateDepthRollUp() {
        f.photo("A5", "P5", "male");
        f.photo("A5", "P4", "larva");
        f.photo("A5", "P3");
        f.photo("A5", "P1");
        f.photo("A5", "P9", "egg");

        assertThat(rows("taxa=A5", 1)).containsExactly("A5 Testland// 4 [larva, male] official",
                "A5 Westfar// 1 [egg] official");
        assertThat(rows("taxa=A5", 2)).containsExactly("A5 Testland// 1 [] official",
                "A5 Testland/Leafstate/ 1 [] official", "A5 Testland/Northstate/ 2 [larva, male] official",
                "A5 Westfar// 1 [egg] official");
    }

    @Test
    void aUserDistrictAndAManualSpeciesHaveTheirRows() {
        f.userDistrict("PU1", "Tamhini");
        f.manual("U1", "Aaa manual");
        f.photo("A5", "PU1");
        f.photo("U1", "P4");

        assertThat(rows("", 3)).startsWith("A5 Testland/Northstate/Tamhini 1 [] official").endsWith(
                "U1 Testland/Northstate/Alpha 1 [] manual");
        assertThat(f.query.exportSet(Filter.parse("taxa=U1"), 0).rows().get(0).family()).isNull();
    }

    @Test
    void aCustomSpeciesComesLastWithItsFamilyAndGenusAndItsPhotosAreMarkedCustom() {
        f.custom("U1", "Danaus novus", "A1");
        f.custom("U2", "Neptis sankara", "A1");
        long novus = f.photo("U1", "P4");
        f.photo("U2", "P4");
        f.photo("A12", "P4");

        assertThat(rows("", 0)).endsWith("A12 1 [] official", "U1 1 [] manual", "U2 1 [] manual");
        List<FilterQuery.ListRow> list = f.query.exportSet(Filter.parse("taxa=U:butterflies"), 0).rows();
        assertThat(list).extracting(FilterQuery.ListRow::family, FilterQuery.ListRow::subfamily, FilterQuery.ListRow::tribe,
                FilterQuery.ListRow::genus).containsExactly(tuple("Nymphalidae", "Danainae", "Danaini", "Danaus"),
                tuple("Nymphalidae", null, null, "Neptis"));
        FilterQuery.ExportSet set = f.query.exportSet(Filter.parse(""), 0, false, true);
        assertThat(set.photoList()).extracting(FilterQuery.ExportPhoto::taxonId).containsExactly("A12", "U1", "U2");
        assertThat(set.photoList().get(1)).isEqualTo(new FilterQuery.ExportPhoto(novus, "U1", set.photoList().get(1).fileName(),
                1, "Nymphalidae", "Danainae", "Danaini", "Danaus", "Danaus novus", true, false, false));
        assertThat(set.photoList().get(0).custom()).isFalse();
    }

    @Test
    void thePhotosOfAnExportComeInThePhotosViewsOrderWithTheirSpeciesNames() {
        long mormon = f.photo("A12", "P4");
        long second = f.photo("A5", "P4");
        f.number(second, 2);
        long first = f.photo("A5", "P4");
        f.number(first, 1);
        long unnumbered = f.photo("A5", null, "egg");
        f.size(unnumbered, 5000);
        f.delete(f.photo("A5", "P4"));
        long genutia = f.photo("A6", "P4", "egg", "larva", "pupa");

        FilterQuery.ExportSet set = f.query.exportSet(Filter.parse(""), 0, false, true);

        assertThat(set.photoList()).extracting(FilterQuery.ExportPhoto::id)
                .containsExactly(unnumbered, first, second, genutia, mormon);
        assertThat(List.of(set.species(), set.photographedSpecies(), set.photos())).containsExactly(6, 3, 5);
        assertThat(set.bytes()).isEqualTo(5004);
        assertThat(set.rows()).isEmpty();
        assertThat(set.query()).isEmpty();
        FilterQuery.ExportPhoto photo = set.photoList().get(0);
        assertThat(photo).isEqualTo(new FilterQuery.ExportPhoto(unnumbered, "A5", photo.fileName(), 5000,
                "Nymphalidae", "Danainae", "Danaini", "Danaus", "Danaus chrysippus", false, false, false));
        assertThat(photo.fileName()).startsWith("photo_");
        assertThat(set.photoList().get(4)).extracting(FilterQuery.ExportPhoto::tribe, FilterQuery.ExportPhoto::genus)
                .containsExactly(null, "Papilio");
        FilterQuery.ExportSet missing = f.query.exportSet(Filter.parse("stages=missing&taxa=A404"), 1, true, true);
        assertThat(missing.photoList()).extracting(FilterQuery.ExportPhoto::taxonId).as("A6 has every stage")
                .containsExactly("A5", "A5", "A5", "A12");
        assertThat(missing.rows()).extracting(FilterQuery.ListRow::id).as("the list from the same snapshot")
                .containsExactly("A5", "A5", "A12");
        assertThat(missing.query()).isEqualTo("stages=missing");
        assertThat(f.query.exportSet(Filter.parse("status=not"), 0, true, true).photoList()).isEmpty();
    }

    @Test
    void anUntaggedPhotoWithNoPlaceAloneHasItsRow() {
        f.photo("A12", null);

        assertThat(rows("taxa=A12", 0)).containsExactly("A12 1 [] official");
        assertThat(rows("taxa=A12", 3)).containsExactly("A12 // 1 [] official");
    }

    @Test
    void placesWhoseNamesDifferOnlyByCaseKeepOneOrder() {
        f.userDistrict("PU1", "tamhini");
        f.userDistrict("PU2", "Tamhini");
        f.photo("A5", "PU1", "egg");
        f.photo("A5", "PU2", "larva");

        for (int i = 0; i < 5; i++) {
            assertThat(rows("taxa=A5", 3)).containsExactly("A5 Testland/Northstate/Tamhini 1 [larva] official",
                    "A5 Testland/Northstate/tamhini 1 [egg] official");
        }
    }

    @Test
    void theListAndThePhotosOfAnExportComeFromOneStateOfTheLibrary() {
        long a = f.photo("A5", "P4");
        long b = f.photo("A5", "P4");
        boolean[] once = {false};
        // Another connection deletes a photo between the species list and the photo list.
        FilterQuery racing = new FilterQuery(f.library::database, conn -> {
            if (!once[0]) {
                once[0] = true;
                f.delete(b);
            }
        });

        FilterQuery.ExportSet set = racing.exportSet(Filter.parse("taxa=A5"), 0, true, true);

        assertThat(once[0]).as("the change was made between the reads").isTrue();
        assertThat(set.rows()).extracting(FilterQuery.ListRow::photos).containsExactly(2);
        assertThat(set.photoList()).extracting(FilterQuery.ExportPhoto::id).containsExactly(a, b);
        assertThat(f.query.exportSet(Filter.parse("taxa=A5"), 0, true, true).photoList()).hasSize(1);
    }
}
