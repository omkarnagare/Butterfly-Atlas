package com.butterflyatlas.app.photos;

/**
 * The answer to adding one photo (design §4). {@code status} is {@code added}, {@code skipped}
 * (already in this species: {@code photo} is null and {@code otherTaxon} is this species) or
 * {@code addedElsewhereToo} (added, and the same file is also under {@code otherTaxon}).
 * {@code firstForSpecies}, {@code milestone} (10, 25, … species photographed) and {@code speciesCount}
 * (species photographed after this add) drive the celebration.
 */
public record AddResult(String status, Photo photo, TaxonRef otherTaxon, boolean firstForSpecies, Integer milestone,
                        int speciesCount) {

    public static final String ADDED = "added";
    public static final String SKIPPED = "skipped";
    public static final String ADDED_ELSEWHERE_TOO = "addedElsewhereToo";
}
