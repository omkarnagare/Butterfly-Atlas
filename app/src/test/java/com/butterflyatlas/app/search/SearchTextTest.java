package com.butterflyatlas.app.search;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SearchTextTest {

    @Test
    void normalizeDropsAccentsCaseAndPunctuation() {
        assertThat(SearchText.normalize("  Café-au-lait   Oak-Blue! ")).isEqualTo("cafe au lait oak blue");
    }

    @Test
    void matchExpressionTurnsEachWordIntoAPrefix() {
        assertThat(SearchText.matchExpression("Common Peaco")).contains("\"common\"* \"peaco\"*");
    }

    @Test
    void queriesShorterThanTwoLettersMatchNothing() {
        assertThat(SearchText.matchExpression("a")).isEmpty();
        assertThat(SearchText.matchExpression("  ")).isEmpty();
        assertThat(SearchText.matchExpression("-")).isEmpty();
        assertThat(SearchText.matchExpression(null)).isEmpty();
    }

    @Test
    void fullTextOperatorsAreTreatedAsWords() {
        assertThat(SearchText.matchExpression("danaus OR \"x*")).contains("\"danaus\"* \"or\"* \"x\"*");
    }

    @Test
    void keepsVowelSignsInIndicWords() {
        assertThat(SearchText.matchExpression("मोर")).contains("\"मोर\"*");
    }
}
