package com.butterflyatlas.app.imports;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/** The import's note of a taxonomy update's split (5b contract §7). */
class ImportSpeciesChoicesTest {

    @Test
    void theSplitNoteSaysAssignedOrSuggestedAsOrNamesSeveralSpeciesAtOnce() {
        assertThat(ImportSpeciesChoices.splitNote(Map.of("Danaus chrysippus", new int[] {3, 0}))).isEqualTo("3 photos were"
                + " assigned to Danaus chrysippus, which was split. Choose their species again.");
        assertThat(ImportSpeciesChoices.splitNote(Map.of("Danaus chrysippus", new int[] {0, 2}))).isEqualTo("2 photos were"
                + " suggested as Danaus chrysippus, which was split. Choose their species.");
        assertThat(ImportSpeciesChoices.splitNote(new TreeMap<>(Map.of("Danaus chrysippus", new int[] {1, 0},
                "Papilio polytes", new int[] {0, 1})))).isEqualTo("2 photos were assigned to or suggested as species that were"
                + " split. Choose their species again.");
        assertThat(ImportSpeciesChoices.splitNote(Map.of("Danaus chrysippus", new int[] {0, 0}))).isNull();
    }
}
