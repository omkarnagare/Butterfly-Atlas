package com.butterflyatlas.app.filters;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Counts per place (4a contract §3 Places, design D7). */
class FilterPlacesTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path dir;

    private FilterFixture f;

    @BeforeEach
    void open() {
        f = new FilterFixture(dir);
        f.userDistrict("PU1", "Tamhini");
        f.photo("A5", "P4", "larva");
        f.photo("A5", "P4");
        f.photo("A6", "P4", "egg");
        f.photo("A5", "P5");
        f.photo("A8", "PU1", "larva");
        f.photo("A12", "P2");
        f.photo("A13", "P3");
        f.photo("A13", "P8", "larva");
        f.photo("A14", null);
        f.photo("A12", null, "larva");
        long gone = f.photo("A6", "P9");
        f.delete(gone);
    }

    /** "id species photos" per row. */
    private List<String> rows(String filter) {
        return f.query.places(Filter.parse(filter)).stream().map(row -> switch (row) {
            case FilterQuery.PlaceCount p -> p.id() + " " + p.species() + " " + p.photos();
            case FilterQuery.UnknownCount u -> u.id() + " " + u.species() + " " + u.photos();
        }).toList();
    }

    @Test
    void noPlaceChosenGivesTheCountriesThenUnknown() throws Exception {
        assertThat(rows("")).containsExactly("P1 5 7", "P8 1 1", "unknown 2 2");
        assertThat(JSON.writeValueAsString(f.query.places(Filter.parse("taxa=A13,A14")))).isEqualTo("[{\"id\":\"P8\","
                + "\"name\":\"Farland\",\"path\":[\"Farland\"],\"level\":\"country\",\"mine\":false,\"species\":1,"
                + "\"photos\":1},{\"id\":\"P1\",\"name\":\"Testland\",\"path\":[\"Testland\"],\"level\":\"country\","
                + "\"mine\":false,\"species\":1,\"photos\":1},{\"id\":\"unknown\",\"species\":1,\"photos\":1}]");
    }

    @Test
    void onePlaceGivesItsChildrenWithPhotosAndADistrictItself() throws Exception {
        assertThat(rows("places=P1")).containsExactly("P2 4 6", "P3 1 1");
        assertThat(rows("places=P2")).as("photos placed at the state itself make its own row")
                .containsExactly("P4 2 3", "P5 1 1", "P2 1 1", "PU1 1 1");
        assertThat(rows("places=P4")).containsExactly("P4 2 3");
        assertThat(rows("places=P8")).as("a country without states").containsExactly("P8 1 1");
        assertThat(rows("places=P6")).isEmpty();
        assertThat(rows("places=unknown")).containsExactly("unknown 2 2");
        assertThat(JSON.writeValueAsString(f.query.places(Filter.parse("places=PU1")))).isEqualTo("[{\"id\":\"PU1\","
                + "\"name\":\"Tamhini\",\"path\":[\"Tamhini\",\"Northstate\",\"Testland\"],\"level\":\"district\",\"mine\":true,"
                + "\"species\":1,\"photos\":1}]");
    }

    @Test
    void severalPlacesGiveThemselvesAtOrBelowWithUnknownLast() {
        assertThat(rows("places=P4,P2,unknown,P6")).containsExactly("P2 4 6", "P4 2 3", "unknown 2 2");
        assertThat(rows("places=P3,P8")).as("Farland before Leafstate").containsExactly("P8 1 1", "P3 1 1");
    }

    @Test
    void aPhotoWhosePlaceIsMissingIsUnknownPlaceEverywhere() {
        f.photo("A8", "PX");

        assertThat(rows("")).containsExactly("P1 5 7", "P8 1 1", "unknown 3 3");
        assertThat(rows("places=unknown")).containsExactly("unknown 3 3");
        assertThat(rows("places=P8,unknown")).containsExactly("P8 1 1", "unknown 3 3");
        assertThat(rows("places=P1")).containsExactly("P2 4 6", "P3 1 1");
    }

    @Test
    void tagsTaxaStatusAndMissingStagesNarrowTheCounts() {
        assertThat(rows("tags=larva")).containsExactly("P1 2 2", "P8 1 1", "unknown 1 1");
        assertThat(rows("tags=larva&places=P2")).containsExactly("P4 1 1", "PU1 1 1");
        assertThat(rows("taxa=A4&status=photographed")).containsExactly("P1 2 4");
        assertThat(rows("status=not")).isEmpty();
        assertThat(rows("status=not&places=P4")).isEmpty();
        assertThat(rows("stages=missing&places=P4")).as("A5 and A6 both lack a stage in Alpha").containsExactly("P4 2 3");
        f.photo("A6", "P4", "larva", "pupa");
        assertThat(rows("stages=missing&places=P4")).containsExactly("P4 1 2");
        assertThat(rows("stages=complete&places=P4")).as("A6 now has every stage in Alpha").containsExactly("P4 1 2");
    }

    @Test
    void rowsSortByPhotosThenName() {
        f.photo("A6", "P3");

        assertThat(rows("places=P1&taxa=A6,A13")).containsExactly("P3 2 2", "P2 1 1");
        assertThat(rows("places=P1&taxa=A6,A13,A12")).as("Leafstate before Northstate").containsExactly("P3 2 2", "P2 2 2");
    }
}
