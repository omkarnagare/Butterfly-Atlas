package com.butterflyatlas.app.filters;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/** The species of a result (4a contract §3 Species): every one, in taxonomic order, with its matching photos. */
class FilterSpeciesTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path dir;

    private FilterFixture f;

    @BeforeEach
    void open() {
        f = new FilterFixture(dir);
    }

    private List<String> ids(String filter) {
        return f.query.species(Filter.parse(filter)).stream().map(FilterQuery.SpeciesRow::id).toList();
    }

    /** "id photos missing…" per row. */
    private List<String> rows(String filter) {
        return f.query.species(Filter.parse(filter)).stream()
                .map(s -> s.id() + " " + s.photos() + (s.missing().isEmpty() ? "" : " " + String.join(",", s.missing())))
                .toList();
    }

    @Test
    void speciesComeInTaxonomicOrder() {
        assertThat(ids("")).containsExactly("A5", "A6", "A8", "A14", "A13", "A12");
        assertThat(ids("taxa=A11,A4")).containsExactly("A5", "A6", "A14", "A13", "A12");
    }

    @Test
    void aSpeciesWithNoTribeSortsBeforeItsSubfamilysTribesAndManualSpeciesComeLast() {
        f.taxon("A20", "A2", "genus", "Zeta", null);
        f.taxon("A21", "A20", "species", "Zeta alpha", "Alpha Zeta");
        f.taxon("A22", "A1", "subfamily", "Aaainae", null);
        f.taxon("A23", "A22", "genus", "Zz", null);
        f.taxon("A24", "A23", "species", "Zz top", null);
        f.manual("U1", "Aaa manual");

        assertThat(ids("")).containsExactly("A24", "A21", "A5", "A6", "A8", "A14", "A13", "A12", "U1");
    }

    @Test
    void customSpeciesComeAfterEveryOfficialOneByNameWhateverTheirFamily() {
        f.custom("U2", "Aaa first", "A9");
        f.custom("U1", "Zzz last", "A1");
        f.custom("U3", "Aaa first", "A1"); // the same sort key (only by the rows: the app never allows it): by id

        assertThat(ids("")).containsExactly("A5", "A6", "A8", "A14", "A13", "A12", "U2", "U3", "U1");
    }

    @Test
    void aCustomSpeciesRowHasItsFamilyTheBinomialsGenusAndThatGenussSubfamilyAndTribe() throws Exception {
        f.custom("U1", "Danaus novus", "A1");
        f.custom("U2", "Papilio novus", "A1"); // Papilio is a genus of another family: not this one's
        f.retire("A11");
        f.custom("U3", "Papilio alter", "A9"); // Papilio retired: no subfamily

        assertThat(f.query.species(Filter.parse("source=manual"))).extracting(FilterQuery.SpeciesRow::id,
                FilterQuery.SpeciesRow::family, FilterQuery.SpeciesRow::subfamily, FilterQuery.SpeciesRow::tribe,
                FilterQuery.SpeciesRow::genus, FilterQuery.SpeciesRow::source).containsExactly(
                tuple("U1", "Nymphalidae", "Danainae", "Danaini", "Danaus", "manual"),
                tuple("U3", "Papilionidae", null, null, "Papilio", "manual"),
                tuple("U2", "Nymphalidae", null, null, "Papilio", "manual"));
    }

    @Test
    void aRowNamesItsHigherTaxaAndCountsItsMatchingPhotos() throws Exception {
        f.photo("A5", "P4", "larva");
        f.photo("A5", "P5");
        f.photo("A12", null);
        f.manual("U1", "Arhopala mine");
        f.retire("A13");
        f.photo("A13", "P4");

        assertThat(JSON.writeValueAsString(f.query.species(Filter.parse("taxa=A4")))).isEqualTo("[{\"id\":\"A5\","
                + "\"name\":\"Danaus chrysippus\",\"common\":\"Plain Tiger\",\"family\":\"Nymphalidae\",\"subfamily\":\"Danainae\","
                + "\"tribe\":\"Danaini\",\"genus\":\"Danaus\",\"photos\":2,\"missing\":[],\"source\":\"official\",\"active\":true},"
                + "{\"id\":\"A6\",\"name\":\"Danaus genutia\",\"common\":\"Striped Tiger\",\"family\":\"Nymphalidae\","
                + "\"subfamily\":\"Danainae\",\"tribe\":\"Danaini\",\"genus\":\"Danaus\",\"photos\":0,\"missing\":[],"
                + "\"source\":\"official\",\"active\":true}]");
        assertThat(JSON.writeValueAsString(f.query.species(Filter.parse("taxa=A13,U1")))).isEqualTo("[{\"id\":\"A13\","
                + "\"name\":\"Papilio demoleus\",\"common\":null,\"family\":\"Papilionidae\",\"subfamily\":\"Papilioninae\","
                + "\"tribe\":null,\"genus\":\"Papilio\",\"photos\":1,\"missing\":[],\"source\":\"official\",\"active\":false},"
                + "{\"id\":\"U1\",\"name\":\"Arhopala mine\",\"common\":null,\"family\":null,\"subfamily\":null,\"tribe\":null,"
                + "\"genus\":null,\"photos\":0,\"missing\":[],\"source\":\"manual\",\"active\":true}]");
    }

    @Test
    void statusTagsAndPlacesChooseTheRowsAndTheirCounts() {
        f.photo("A5", "P4", "larva");
        f.photo("A5", "P5", "larva", "male");
        f.photo("A12", null, "male");

        assertThat(rows("")).containsExactly("A5 2", "A6 0", "A8 0", "A14 0", "A13 0", "A12 1");
        assertThat(rows("status=photographed")).containsExactly("A5 2", "A12 1");
        assertThat(rows("status=not&tags=male")).containsExactly("A6 0", "A8 0", "A14 0", "A13 0");
        assertThat(rows("tags=larva,male&tagMode=all&status=photographed")).containsExactly("A5 1");
        assertThat(rows("places=P2,unknown&tags=male&status=photographed")).containsExactly("A5 1", "A12 1");
    }

    @Test
    void missingLifeStagesListsTheAbsentStagesInOrder() {
        f.photo("A5", "P4", "pupa");
        f.photo("A5", "P4", "male");
        f.photo("A6", "P4", "larva", "egg");
        f.photo("A8", "P4", "egg", "larva", "pupa");
        f.photo("A12", "P5");

        assertThat(rows("stages=missing")).containsExactly("A5 2 egg,larva", "A6 1 pupa", "A12 1 egg,larva,pupa");
        assertThat(rows("stages=missing&places=P4")).containsExactly("A5 2 egg,larva", "A6 1 pupa");
    }

    @Test
    void completeLifeStagesListsSpeciesWithEggLarvaAndPupaWhateverTheirOtherTags() {
        f.photo("A5", "P4", "egg");
        f.photo("A5", "P4", "larva", "male");
        f.photo("A5", "P5", "pupa");
        f.photo("A6", "P4", "egg", "larva", "pupa");
        f.photo("A8", "P4", "egg", "larva");
        f.photo("A12", "P5");

        assertThat(rows("stages=complete")).containsExactly("A5 3", "A6 1");
        assertThat(rows("stages=complete&places=P4")).as("A5's pupa is elsewhere").containsExactly("A6 1");
        assertThat(rows("stages=complete&taxa=A12")).isEmpty();
    }

    @Test
    void deletedPhotosNeverCount() {
        long gone = f.photo("A5", "P4");
        f.delete(gone);

        assertThat(rows("status=photographed")).isEmpty();
        assertThat(rows("taxa=A5")).containsExactly("A5 0");
    }
}
