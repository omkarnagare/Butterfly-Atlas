package com.butterflyatlas.app.imports;

import com.butterflyatlas.app.db.Database;
import com.butterflyatlas.app.search.SearchService;
import com.butterflyatlas.app.taxa.SearchWordService;
import com.butterflyatlas.app.testing.TestBundles;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class SuggestionsTest {

    @TempDir
    Path dir;

    private Database db;
    private Suggestions suggestions;

    @BeforeEach
    void setUp() {
        db = TestBundles.fixtureDatabase(dir);
        suggestions = new Suggestions(new SearchService(() -> db));
    }

    @Test
    void namesAreCleanedOfSeparatorsCameraPrefixesAndNumbers() {
        assertThat(Suggestions.clean("Danaus_chrysippus_012")).isEqualTo("Danaus chrysippus");
        assertThat(Suggestions.clean("Plain-Tiger (3)")).isEqualTo("Plain Tiger");
        assertThat(Suggestions.clean("IMG_0412")).isEmpty();
        assertThat(Suggestions.clean("DSC0412")).isEmpty();
        assertThat(Suggestions.clean("DSCN_0001 Blue tiger")).isEqualTo("Blue tiger");
        assertThat(Suggestions.clean("01 Common Mormon 2024")).isEqualTo("Common Mormon");
        assertThat(Suggestions.clean("Sinhagad, Aug 2024")).isEqualTo("Sinhagad Aug");
        assertThat(Suggestions.clean(null)).isEmpty();
    }

    @Test
    void anExactScientificOrCommonNameSuggestsItsSpecies() {
        assertThat(suggestions.forName("Danaus chrysippus")).contains("A5");
        assertThat(suggestions.forName("danaus_chrysippus_07")).contains("A5");
        assertThat(suggestions.forName("PLAIN TIGER")).contains("A5");
        assertThat(suggestions.forName("Blue-Tiger")).contains("A8");
    }

    @Test
    void partialSynonymGenusAndUnknownNamesSuggestNothing() {
        assertThat(suggestions.forName("Plain")).as("a prefix is not exact").isEmpty();
        assertThat(suggestions.forName("Danaus")).as("a genus is not a species").isEmpty();
        assertThat(suggestions.forName("Limnas klugii")).as("synonyms do not suggest").isEmpty();
        assertThat(suggestions.forName("Sinhagad, Aug 2024")).isEmpty();
        assertThat(suggestions.forName("IMG_0412")).isEmpty();
    }

    @Test
    void aNameTwoSpeciesShareIsAmbiguous() {
        new SearchWordService(() -> db, TestBundles.CLOCK).add("A5", "Tiger mix");
        new SearchWordService(() -> db, TestBundles.CLOCK).add("A6", "Tiger mix");
        new SearchWordService(() -> db, TestBundles.CLOCK).add("A12", "Mormon female");

        assertThat(suggestions.forName("tiger mix")).isEmpty();
        assertThat(suggestions.forName("Mormon female 3")).as("a user search word").contains("A12");
    }

    @Test
    void aTrailingButterflyCountsOnNeitherSide() {
        new SearchWordService(() -> db, TestBundles.CLOCK).add("A12", "Lemon Pansy Butterfly");

        assertThat(suggestions.forName("Lemon pansy")).as("the shipped names end in Butterfly; folders rarely do").contains("A12");
        assertThat(suggestions.forName("Common Mormon Butterfly")).contains("A12");
        assertThat(suggestions.forName("Butterfly")).as("the word alone names nothing").isEmpty();
        assertThat(Suggestions.comparable("Plain Tiger Butterfly")).isEqualTo("plain tiger");
        assertThat(Suggestions.comparable("Plain Tiger Butterflies")).isEqualTo("plain tiger");
        assertThat(suggestions.forName("Common Mormon butterflies")).contains("A12");
        assertThat(suggestions.forName("Butterflies")).isEmpty();
    }

    /** Gives a species another common name in the search index, as the shipped bundle's longer names are. */
    private void commonName(String taxonId, String name) {
        db.write(c -> {
            try (var ps = c.prepareStatement("UPDATE taxon_fts SET text = ? WHERE taxon_id = ? AND kind = 'common'")) {
                ps.setString(1, name);
                ps.setString(2, taxonId);
                ps.executeUpdate();
            }
            return null;
        });
    }

    @Test
    void aCommonNameWithoutItsLastWordCountsWhenTwoWordsRemain() {
        commonName("A12", "Common Mormon Swallowtail");

        assertThat(suggestions.forName("Common Mormon")).contains("A12");
        assertThat(suggestions.forName("Common Mormon Swallowtail 4")).contains("A12");
        assertThat(Suggestions.withoutLastWord("common mormon swallowtail")).isEqualTo("common mormon");
        assertThat(Suggestions.withoutLastWord("blue tiger")).as("one word would be left").isNull();
    }

    @Test
    void aShortenedNameTwoSpeciesShareSuggestsNothing() {
        commonName("A12", "Common Mormon Swallowtail");
        commonName("A14", "Common Mormon Mimic");

        assertThat(suggestions.forName("Common Mormon")).isEmpty();
    }

    @Test
    void anExactNameWinsOverAShortenedOne() {
        commonName("A14", "Common Mormon Mimic");

        assertThat(suggestions.forName("Common Mormon")).as("A12 is exactly Common Mormon").contains("A12");
    }

    @Test
    void aSingleWordLeftNeverMatches() {
        assertThat(suggestions.forName("Blue")).as("Blue Tiger without Tiger").isEmpty();
        assertThat(suggestions.forName("Common")).isEmpty();
    }

    @Test
    void retiredSpeciesAreNotSuggested() {
        TestBundles.retire(db, "A14");

        assertThat(suggestions.forName("Common Mime")).isEmpty();
    }

    @Test
    void aFileTakesItsOwnNameFirstThenItsFoldersNearestFirstThenTheImportsFolder() {
        assertThat(suggestions.forFile("Trip", "Plain tiger/Blue tiger 2.jpg")).contains("A8");
        assertThat(suggestions.forFile("Trip", "Plain tiger/day 1/IMG_0001.JPG")).contains("A5");
        assertThat(suggestions.forFile("Common Mormon", "IMG_0001.JPG")).contains("A12");
        assertThat(suggestions.forFile("Trip", "Day 1/IMG_0001.JPG")).isEmpty();
        assertThat(suggestions.forFile(null, "IMG_0001.JPG")).isEmpty();
    }

    @Test
    void eachDistinctNameIsSearchedOnce() {
        AtomicInteger searches = new AtomicInteger();
        Suggestions counted = new Suggestions(new SearchService(() -> {
            searches.incrementAndGet();
            return db;
        }));

        for (int i = 0; i < 50; i++) {
            assertThat(counted.forFile("Trip", "Plain tiger/IMG_" + i + ".JPG")).isEqualTo(Optional.of("A5"));
        }

        assertThat(searches.get()).as("Plain tiger once; IMG_n is never searched").isEqualTo(1);
    }

    @Test
    void phoneAndCameraNamesWithDigitsInsideAreNumbers() {
        assertThat(Suggestions.clean("IMG-20240815-WA0003")).isEmpty();
        assertThat(Suggestions.clean("DSC_0412_DSC0413")).isEmpty();
        assertThat(Suggestions.clean("Plain Tiger - IMG_0412")).isEqualTo("Plain Tiger");
        assertThat(suggestions.forName("Screenshot_20240815-143022_Gallery")).isEmpty();
    }

    @Test
    void tenThousandWhatsAppStyleNamesNeverReachTheSearch() {
        AtomicInteger searches = new AtomicInteger();
        Suggestions counted = new Suggestions(new SearchService(() -> {
            searches.incrementAndGet();
            return db;
        }));

        for (int i = 0; i < 10_000; i++) {
            assertThat(counted.forFile(null, String.format("IMG-20240815-WA%04d.jpg", i))).isEmpty();
        }

        assertThat(searches.get()).isZero();
    }

    @Test
    void aRetiredSpeciesWithPhotosIsNotSuggested() {
        TestBundles.addImage(db, "A14");
        TestBundles.retire(db, "A14");

        assertThat(suggestions.forName("Common Mime")).isEmpty();
    }

    @Test
    void namesDifferingOnlyInCaseOrSeparatorsAreSearchedOnce() {
        AtomicInteger searches = new AtomicInteger();
        Suggestions counted = new Suggestions(new SearchService(() -> {
            searches.incrementAndGet();
            return db;
        }));

        assertThat(counted.forName("Plain tiger")).contains("A5");
        assertThat(counted.forName("PLAIN TIGER")).contains("A5");
        assertThat(counted.forName("plain_tiger_3")).contains("A5");

        assertThat(searches.get()).isEqualTo(1);
    }

    @Test
    void unfinishedNamesAndAccentedNamesBehave() {
        assertThat(suggestions.forName("Danaus sp.")).isEmpty();
        assertThat(suggestions.forName("Dänaus chrysippus")).contains("A5");
    }

    @Test
    void namesWithDigitsLeftInTheMiddleAreNotSearched() {
        AtomicInteger searches = new AtomicInteger();
        Suggestions counted = new Suggestions(new SearchService(() -> {
            searches.incrementAndGet();
            return db;
        }));

        for (int i = 0; i < 10_000; i++) {
            assertThat(counted.forFile(null, String.format("Screenshot_20240815-%06d_Gallery.png", i))).isEmpty();
        }
        assertThat(searches.get()).isZero();

        assertThat(counted.forName("Common Mormon")).contains("A12");
        assertThat(searches.get()).isEqualTo(1);
    }
}
