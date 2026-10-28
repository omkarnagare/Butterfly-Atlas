package com.butterflyatlas.app.filters;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** The universe, matching photos, status, Missing life stages and the filter as used (4a contract §2, §3 Count). */
class FilterCountTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path dir;

    private FilterFixture f;

    @BeforeEach
    void open() {
        f = new FilterFixture(dir);
    }

    @Test
    void anEmptyLibraryCountsEveryVisibleSpeciesAndNoPhotos() throws Exception {
        assertThat(JSON.writeValueAsString(f.count(""))).isEqualTo("{\"photos\":0,\"species\":6,\"photographedSpecies\":0,\"bytes\":0,"
                + "\"manualSpecies\":false,\"filter\":{\"query\":\"\",\"taxa\":[],\"tags\":[],\"tagMode\":\"any\","
                + "\"places\":[],\"status\":\"all\",\"source\":\"all\",\"stages\":null,\"placeNotes\":[],\"types\":[]}}");
    }

    @Test
    void photographedSpeciesCountsTheSpeciesWithMatchingPhotosWhateverTheStatus() {
        f.photo("A5", "P4", "larva");
        f.photo("A6", "P5", "egg", "larva", "pupa");
        f.photo("A12", null, "male");

        assertThat(photographed("places=P2")).as("status all: the universe, and those shot in Northstate").isEqualTo("6 2");
        assertThat(photographed("tags=larva")).isEqualTo("6 2");
        assertThat(photographed("")).isEqualTo("6 3");
        assertThat(photographed("places=P2&status=photographed")).isEqualTo("2 2");
        assertThat(photographed("places=P2&status=not")).isEqualTo("4 0");
        assertThat(photographed("stages=missing")).as("A5 and A12 lack a stage").isEqualTo("2 2");
        assertThat(photographed("places=P9")).isEqualTo("6 0");
    }

    /** "species photographedSpecies" of a count. */
    private String photographed(String filter) {
        FilterQuery.FilterCount count = f.count(filter);
        return count.species() + " " + count.photographedSpecies();
    }

    @Test
    void taxaAtEveryRankAndSeveral() {
        assertThat(f.numbers("taxa=A9")).as("family").isEqualTo("0 3 0");
        assertThat(f.numbers("taxa=A2")).as("subfamily").isEqualTo("0 3 0");
        assertThat(f.numbers("taxa=A3")).as("tribe").isEqualTo("0 3 0");
        assertThat(f.numbers("taxa=A4")).as("genus").isEqualTo("0 2 0");
        assertThat(f.numbers("taxa=A5")).as("species").isEqualTo("0 1 0");
        assertThat(f.numbers("taxa=A4,A11")).as("several").isEqualTo("0 5 0");
        assertThat(f.numbers("taxa=A4,A5,A3")).as("overlapping").isEqualTo("0 3 0");
        assertThat(f.numbers("taxa=G:butterflies")).as("the group").isEqualTo("0 6 0");
    }

    @Test
    void matchingPhotosAreLivePhotosOfTheUniverseWithTheirBytes() {
        long a = f.photo("A5", "P4");
        long b = f.photo("A5", null);
        long c = f.photo("A12", "P5");
        long gone = f.photo("A6", "P4");
        f.size(a, 1_000);
        f.size(b, 2_500);
        f.size(c, 4_000_000_000L);
        f.size(gone, 7);
        f.delete(gone);

        assertThat(f.numbers("")).isEqualTo("3 6 4000003500");
        assertThat(f.numbers("taxa=A1")).isEqualTo("2 3 3500");
        assertThat(f.numbers("taxa=A6")).as("only a deleted photo").isEqualTo("0 1 0");
        assertThat(f.numbers("taxa=A6&status=photographed")).isEqualTo("0 0 0");
    }

    @Test
    void tagsAnyOrAllArePerPhoto() {
        f.photo("A5", null, "larva");
        f.photo("A5", null, "male");
        f.photo("A6", null, "larva", "male", "egg");
        f.photo("A12", null);

        assertThat(f.numbers("tags=larva,male&status=photographed")).isEqualTo("3 2 3");
        assertThat(f.numbers("tags=larva,male&tagMode=all&status=photographed"))
                .as("A5 has larva and male only on different photos").isEqualTo("1 1 1");
        assertThat(f.numbers("tags=larva,male&tagMode=all&status=not")).isEqualTo("0 5 0");
        assertThat(f.numbers("tags=egg")).isEqualTo("1 6 1");
    }

    @Test
    void placesMatchAtOrBelowAndUnknownIsItsOwnChoice() {
        f.userDistrict("PU1", "Tamhini");
        f.photo("A5", "P4");
        f.photo("A5", "P5");
        f.photo("A6", "P2");
        f.photo("A8", "PU1");
        f.photo("A12", "P3");
        f.photo("A13", "P8");
        f.photo("A14", null);

        assertThat(f.numbers("places=P1&status=photographed")).as("country").isEqualTo("5 4 5");
        assertThat(f.numbers("places=P2&status=photographed")).as("state, its districts and the user's").isEqualTo("4 3 4");
        assertThat(f.numbers("places=P4&status=photographed")).as("district").isEqualTo("1 1 1");
        assertThat(f.numbers("places=PU1&status=photographed")).as("user district").isEqualTo("1 1 1");
        assertThat(f.numbers("places=unknown&status=photographed")).isEqualTo("1 1 1");
        assertThat(f.numbers("places=P4,P3,unknown&status=photographed")).as("several").isEqualTo("3 3 3");
        assertThat(f.numbers("places=P8,P5&status=photographed")).isEqualTo("2 2 2");
        assertThat(f.numbers("places=P6&status=photographed")).isEqualTo("0 0 0");
    }

    @Test
    void aPhotoWhosePlaceIsMissingMatchesUnknownPlaceAndNoPlace() {
        f.photo("A5", "P4");
        f.photo("A6", "PX");
        f.photo("A14", null);

        assertThat(f.numbers("places=unknown&status=photographed")).isEqualTo("2 2 2");
        assertThat(f.numbers("places=P1&status=photographed")).isEqualTo("1 1 1");
        assertThat(f.numbers("places=P1,unknown&status=photographed")).isEqualTo("3 3 3");
        assertThat(f.numbers("status=photographed")).isEqualTo("3 3 3");
    }

    @Test
    void statusIsRelativeToTagsAndPlaces() {
        f.photo("A5", "P5", "larva");
        f.photo("A6", "P4", "egg");

        assertThat(f.numbers("places=P4")).isEqualTo("1 6 1");
        assertThat(f.numbers("places=P4&status=photographed")).isEqualTo("1 1 1");
        assertThat(f.numbers("places=P4&status=not")).as("A5 was shot only in Beta").isEqualTo("0 5 0");
        assertThat(f.numbers("tags=larva&status=not")).isEqualTo("0 5 0");
        assertThat(f.numbers("tags=larva&places=P4&status=photographed")).isEqualTo("0 0 0");
        assertThat(f.numbers("status=not")).isEqualTo("0 4 0");
    }

    @Test
    void fileTypesArePerPhotoAndNotYetPhotographedMeansNoPhotoOfThoseTypes() throws Exception {
        f.type(f.photo("A5", "P4", "egg"), "jpeg");
        f.type(f.photo("A5", "P4"), "raw");
        f.type(f.photo("A6", "P5", "egg"), "raw");
        f.type(f.photo("A12", null), null);

        assertThat(f.count("types=raw").photos()).isEqualTo(2);
        assertThat(f.count("types=raw").photographedSpecies()).isEqualTo(2);
        assertThat(f.count("types=jpeg,png").photos()).isEqualTo(1);
        assertThat(f.count("types=png").photographedSpecies()).isZero();
        assertThat(f.count("types=raw,jpeg").photos()).as("a photo of no known type matches none").isEqualTo(3);
        assertThat(f.count("").photos()).isEqualTo(4);
        assertThat(f.count("types=raw&status=not").species()).as("every species but A5 and A6").isEqualTo(4);
        assertThat(f.count("status=not").species()).isEqualTo(3);
        assertThat(f.count("types=raw&tags=egg").photos()).isEqualTo(1);
        assertThat(f.count("types=jpeg&places=P5").photos()).isZero();
        assertThat(JSON.writeValueAsString(f.count("types=raw,jpeg").filter())).endsWith(",\"types\":[\"raw\",\"jpeg\"]}");
    }

    @Test
    void heicIsAFileTypeOfItsOwn() throws Exception {
        f.type(f.photo("A5", "P4", "egg"), "jpeg");
        f.type(f.photo("A6", "P5"), "heic");
        f.type(f.photo("A6", "P5", "egg"), "heic");

        assertThat(f.count("types=heic").photos()).isEqualTo(2);
        assertThat(f.count("types=heic").photographedSpecies()).isEqualTo(1);
        assertThat(f.count("types=jpeg,png,tiff,webp,raw").photos()).as("HEIC photos left out").isEqualTo(1);
        assertThat(f.count("types=heic&tags=egg").photos()).isEqualTo(1);
        assertThat(JSON.writeValueAsString(f.count("types=heic,raw").filter())).endsWith(",\"types\":[\"heic\",\"raw\"]}");
    }

    @Test
    void missingLifeStagesUsesPlacesButNotTagsOrStatus() throws Exception {
        f.photo("A5", "P4", "egg");
        f.photo("A5", "P4", "larva");
        f.photo("A5", "P5", "pupa");
        f.photo("A6", "P4", "egg", "larva", "pupa");
        f.photo("A6", "P4", "male");
        f.photo("A12", null, "male");

        assertThat(f.numbers("stages=missing")).as("A5 complete overall; A12 lacks all three").isEqualTo("1 1 1");
        assertThat(f.numbers("stages=missing&places=P4")).as("in Alpha A5 lacks pupa").isEqualTo("2 1 2");
        assertThat(f.numbers("stages=missing&places=P5")).isEqualTo("1 1 1");
        assertThat(f.numbers("stages=missing&places=P9")).isEqualTo("0 0 0");
        assertThat(f.numbers("stages=missing&tags=male&tagMode=all&status=not&places=P4")).isEqualTo("2 1 2");
        assertThat(JSON.writeValueAsString(f.count("tags=male&status=not&stages=missing").filter())).isEqualTo(
                "{\"query\":\"tags=male&status=not&stages=missing\",\"taxa\":[],\"tags\":[{\"key\":\"male\",\"label\":\"male\"}],"
                        + "\"tagMode\":\"any\",\"places\":[],\"status\":\"not\",\"source\":\"all\",\"stages\":\"missing\",\"placeNotes\":[],\"types\":[]}");
    }

    @Test
    void removedSpeciesCountOnlyWhileTheyHaveLivePhotos() {
        f.retire("A6");
        f.retire("A8");
        f.retire("A13");
        f.photo("A6", "P4");
        long gone = f.photo("A8", "P4");
        f.delete(gone);

        assertThat(f.numbers("")).isEqualTo("1 4 1");
        assertThat(f.numbers("taxa=A3")).isEqualTo("1 2 1");
        assertThat(f.numbers("status=photographed")).isEqualTo("1 1 1");
        assertThat(f.numbers("status=not")).isEqualTo("0 3 0");
        assertThat(f.numbers("places=P5&status=not")).as("A6 is visible, just not in Beta").isEqualTo("0 4 0");
    }

    @Test
    void sourceOfficialOrManual() {
        assertThat(f.count("").manualSpecies()).isFalse();
        f.manual("U1", "Arhopala mine");
        f.manual("U2", "Arhopala gone");
        f.retire("U2");
        f.photo("U1", null);
        f.photo("A5", null);

        assertThat(f.count("").manualSpecies()).isTrue();
        assertThat(f.numbers("")).isEqualTo("2 7 2");
        assertThat(f.numbers("source=manual")).isEqualTo("1 1 1");
        assertThat(f.numbers("source=official")).isEqualTo("1 6 1");
        assertThat(f.numbers("source=manual&taxa=A1")).isEqualTo("0 0 0");
        assertThat(f.numbers("taxa=U:butterflies")).isEqualTo("1 1 1");
    }

    @Test
    void aFamilyFilterLeavesOutTheCustomSpeciesOfThatFamily() {
        f.custom("U1", "Danaus novus", "A1");
        f.photo("U1", null);
        f.photo("A5", null);

        assertThat(f.numbers("taxa=A1")).as("the family's own species only").isEqualTo("1 3 1");
        assertThat(f.numbers("taxa=A4")).isEqualTo("1 2 1");
        assertThat(f.numbers("source=manual")).isEqualTo("1 1 1");
        assertThat(f.numbers("")).isEqualTo("2 7 2");
    }

    @Test
    void nestedPlacesCountAPhotoOnce() {
        f.photo("A5", "P4");
        f.photo("A6", "P5");
        f.photo("A12", "P3");

        assertThat(f.numbers("places=P2,P4&status=photographed")).isEqualTo("2 2 2");
        assertThat(f.numbers("places=P4,P1,P2&status=photographed")).isEqualTo("3 3 3");
    }

    @Test
    void allOfASingleTagIsThatTag() {
        f.photo("A5", null, "larva");
        f.photo("A5", null, "larva", "male");
        f.photo("A6", null, "male");

        assertThat(f.numbers("tags=larva&tagMode=all&status=photographed")).isEqualTo("2 1 2")
                .isEqualTo(f.numbers("tags=larva&status=photographed"));
    }

    @Test
    void sourceOfficialWithAPlace() {
        f.manual("U1", "Arhopala mine");
        f.photo("U1", "P4");
        f.photo("A5", "P4");
        f.photo("A6", "P5");
        f.photo("A12", "P8");

        assertThat(f.numbers("source=official&places=P2&status=photographed")).isEqualTo("2 2 2");
        assertThat(f.numbers("source=official&places=P4&status=photographed")).isEqualTo("1 1 1");
        assertThat(f.numbers("source=manual&places=P4")).isEqualTo("1 1 1");
        assertThat(f.numbers("source=official&places=P4&status=not")).isEqualTo("0 5 0");
    }

    @Test
    void onlyHiddenManualSpeciesMeanNoManualSpecies() {
        f.manual("U2", "Arhopala gone");
        f.retire("U2");

        assertThat(f.count("").manualSpecies()).isFalse();
    }

    @Test
    void unknownTaxaAreDroppedAFormerPlaceStaysChosenAPlaceTheLibraryLacksIsListedAndTheFilterAsUsedIsEchoed()
            throws Exception {
        f.userDistrict("PU1", "Tamhini");
        f.sql("UPDATE place SET active = 0 WHERE id = 'P6'");
        f.retire("A14");
        f.photo("A5", "P4");

        FilterQuery.FilterCount count = f.count("taxa=A404,A12,A14,A5&tags=nope,egg,larva&places=P404,PU1,P6,unknown,P2"
                + "&tagMode=all");

        assertThat(JSON.writeValueAsString(count.filter())).isEqualTo("{\"query\":\"taxa=A12,A5&tags=egg,larva&tagMode=all"
                + "&places=PU1,P6,unknown,P2\",\"taxa\":[{\"id\":\"A12\",\"rank\":\"species\",\"name\":\"Papilio polytes\","
                + "\"common\":\"Common Mormon\"},{\"id\":\"A5\",\"rank\":\"species\",\"name\":\"Danaus chrysippus\","
                + "\"common\":\"Plain Tiger\"}],\"tags\":[{\"key\":\"egg\",\"label\":\"egg\"},{\"key\":\"larva\",\"label\":"
                + "\"larva\"}],\"tagMode\":\"all\",\"places\":[{\"id\":\"PU1\",\"name\":\"Tamhini\",\"path\":[\"Tamhini\","
                + "\"Northstate\",\"Testland\"],\"level\":\"district\",\"mine\":true,\"former\":false},{\"id\":\"P6\","
                + "\"name\":\"Lakeland (former district)\",\"path\":[\"Lakeland (former district)\",\"Northstate\","
                + "\"Testland\"],\"level\":\"district\",\"mine\":false,\"former\":true},{\"id\":\"unknown\"},{\"id\":\"P2\","
                + "\"name\":\"Northstate\",\"path\":[\"Northstate\",\"Testland\"],\"level\":\"state\",\"mine\":false,\"former\":false}],"
                + "\"status\":\"all\",\"source\":\"all\",\"stages\":null,\"placeNotes\":[{\"id\":\"P404\",\"kind\":\"gone\","
                + "\"name\":null,\"into\":null}],\"types\":[]}");
        assertThat(JSON.writeValueAsString(f.count("taxa=A1,A9").filter().taxa())).isEqualTo("[{\"id\":\"A1\","
                + "\"rank\":\"family\",\"name\":\"Nymphalidae\",\"common\":\"Brush-footed Butterflies\"},{\"id\":\"A9\","
                + "\"rank\":\"family\",\"name\":\"Papilionidae\",\"common\":\"Swallowtails\"}]");
    }

    @Test
    void whenEveryChosenIdIsGoneTheFilterFallsBackToAll() {
        f.photo("A5", "P4");
        f.photo("A12", null);

        FilterQuery.FilterCount count = f.count("taxa=A404&places=P404&tags=nope&status=photographed");

        assertThat(count.filter().query()).isEqualTo("status=photographed");
        assertThat(f.numbers("taxa=A404&places=P404&tags=nope&status=photographed")).isEqualTo("2 2 2");
    }
}
