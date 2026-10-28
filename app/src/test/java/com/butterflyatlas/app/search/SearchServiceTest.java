package com.butterflyatlas.app.search;

import com.butterflyatlas.app.db.Database;
import com.butterflyatlas.app.taxa.SearchWordService;
import com.butterflyatlas.app.taxa.TaxonViews.Count;
import com.butterflyatlas.app.testing.TestBundles;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SearchServiceTest {

    @TempDir
    Path dir;

    private Database db;
    private SearchService search;

    @BeforeEach
    void setUp() {
        db = TestBundles.fixtureDatabase(dir);
        search = new SearchService(() -> db);
    }

    private List<String> ids(String query) {
        return search.search(query, SearchService.DEFAULT_LIMIT).stream().map(SearchService.Hit::id).toList();
    }

    @Test
    void findsByCommonNamePrefix() {
        SearchService.Hit first = search.search("plain ti", 20).getFirst();

        assertThat(first.id()).isEqualTo("A5");
        assertThat(first.matchKind()).isEqualTo("common");
    }

    @Test
    void findsByScientificNameWithTheExactMatchFirst() {
        List<String> ids = ids("danaus");

        assertThat(ids).hasSize(4);
        assertThat(ids.getFirst()).isEqualTo("A4");
        assertThat(ids.subList(1, 3)).containsExactlyInAnyOrder("A5", "A6");
        assertThat(ids.getLast()).isEqualTo("A8");
    }

    @Test
    void aHitSaysItsSourceAndACustomSpeciesItsFamily() {
        TestBundles.addManualSpecies(db, "U3", "Neptis sankara", "A1");

        SearchService.Hit custom = search.search("u3", 20).getFirst();
        assertThat(custom.id()).isEqualTo("U3");
        assertThat(custom.source()).isEqualTo("manual");
        assertThat(custom.family()).isEqualTo("Nymphalidae");
        assertThat(custom.path()).isEqualTo("Custom additions");
        assertThat(search.search("plain ti", 20).getFirst().source()).isEqualTo("official");
    }

    @Test
    void findsBySynonymAndSaysWhichNameMatched() {
        SearchService.Hit hit = search.search("princeps", 20).getFirst();

        assertThat(hit.id()).isEqualTo("A13");
        assertThat(hit.matchKind()).isEqualTo("synonym");
        assertThat(hit.matched()).isEqualTo("Princeps demoleus");
    }

    @Test
    void findsYourOwnSearchWords() {
        new SearchWordService(() -> db, TestBundles.CLOCK).add("A5", "Kaner");

        SearchService.Hit hit = search.search("kane", 20).getFirst();

        assertThat(hit.id()).isEqualTo("A5");
        assertThat(hit.matchKind()).isEqualTo("word");
    }

    @Test
    void ignoresAccentsAndCase() {
        assertThat(ids("PLÁIN tiger")).first().isEqualTo("A5");
    }

    @Test
    void anAtlasIdGoesStraightToItsTaxon() {
        SearchService.Hit hit = search.search(" a12 ", 20).getFirst();

        assertThat(hit.id()).isEqualTo("A12");
        assertThat(hit.matchKind()).isEqualTo("id");
    }

    @Test
    void oneLetterFindsNothing() {
        assertThat(ids("p")).isEmpty();
    }

    @Test
    void hidesRetiredSpeciesUntilTheyHavePhotos() {
        TestBundles.retire(db, "A13");
        assertThat(ids("princeps")).isEmpty();

        TestBundles.addImage(db, "A13");
        assertThat(search.search("princeps", 20)).singleElement().satisfies(hit -> {
            assertThat(hit.id()).isEqualTo("A13");
            assertThat(hit.active()).isFalse();
        });
    }

    @Test
    void returnsEachTaxonOnce() {
        assertThat(ids("tiger")).doesNotHaveDuplicates().containsExactlyInAnyOrder("A4", "A5", "A6", "A8");
    }

    @Test
    void respectsTheLimit() {
        assertThat(search.search("common", 1)).hasSize(1);
        assertThat(search.search("common", 500)).hasSize(2);
    }

    @Test
    void hitsCarryTheirPathFamilyAndCount() {
        SearchService.Hit hit = search.search("mormon", 20).getFirst();

        assertThat(hit.id()).isEqualTo("A12");
        assertThat(hit.path()).isEqualTo("Papilionidae › Papilioninae › Papilio");
        assertThat(hit.family()).isEqualTo("Papilionidae");
        assertThat(hit.species()).isEqualTo(new Count(0, 1));
        assertThat(hit.common()).isEqualTo("Common Mormon");
    }

    @Test
    void findsASearchWordInDevanagari() {
        new SearchWordService(() -> db, TestBundles.CLOCK).add("A5", "मोर");

        SearchService.Hit hit = search.search("मोर", 20).getFirst();

        assertThat(hit.id()).isEqualTo("A5");
        assertThat(hit.matchKind()).isEqualTo("word");
    }

    @Test
    void anExactUserWordBeatsANamePrefix() {
        new SearchWordService(() -> db, TestBundles.CLOCK).add("A12", "Papilio");

        assertThat(search.search("papilio", 20).getFirst().id()).isEqualTo("A11");
        SearchService.Hit mormon = search.search("common mormon", 20).getFirst();
        assertThat(mormon.id()).isEqualTo("A12");
        assertThat(mormon.matchKind()).isEqualTo("common");
    }
}
