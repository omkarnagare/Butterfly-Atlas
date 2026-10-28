package com.butterflyatlas.app.taxa;

import com.butterflyatlas.app.photos.Photo;
import com.butterflyatlas.app.photos.PhotoQueries.Cover;
import com.butterflyatlas.app.photos.PhotoQueries.Stages;

import java.util.List;

/**
 * What the core hands to the UI. {@code family} is the family's scientific name, for its colour (a custom species: the
 * family it was given); {@code source} is official, manual (a custom species) or system (the group, Custom additions).
 */
public final class TaxonViews {

    private TaxonViews() {
    }

    /** "got of total species photographed". */
    public record Count(int got, int total) {
    }

    /** How many taxa of one rank sit below a node, and how many of them are photographed. */
    public record RankCount(String rank, int got, int total) {
    }

    public record Crumb(String id, String rank, String name, String common) {
    }

    /**
     * {@code cover} is set for species with photos, null otherwise; {@code toSort}: a split species' live photos still to
     * sort, else 0.
     */
    public record TaxonCard(String id, String rank, String name, String common, String family, boolean active,
                            String source, Count species, Cover cover, int toSort) {
    }

    /**
     * One taxon in full. {@code folder}, {@code stages} and {@code cover} are for species
     * ({@code folder} and {@code cover} null otherwise); {@code photoCount} counts live photos
     * of the taxon and everything below it; {@code familyId} is the family of a custom species (null otherwise);
     * {@code toSort}: a split species' live photos still to sort, else 0.
     */
    public record TaxonDetail(String id, String rank, String name, String common, String family, String familyId,
                              boolean active, String source, List<Crumb> path, Count species, List<RankCount> ranks, List<String> synonyms,
                              List<String> searchWords, String folder, int photoCount, Stages stages, Photo cover, int toSort) {
    }

    /** A photo with its species, for Home's "Latest addition" and "Recently added". */
    public record RecentPhoto(Photo photo, TaxonCard taxon) {
    }

    /**
     * Home. {@code families} are the group's visible children: the families, then "Custom additions"
     * once it has a species shown. {@code latest} is the newest photo (null before the first),
     * {@code recent} the newest 12, {@code nextOnList} up to 8 species still to photograph in the
     * genera most nearly complete, {@code fullLifeCycles} the species with egg, larva, pupa and adult photos.
     */
    public record Summary(TaxonDetail group, List<TaxonCard> families, int photoCount, RecentPhoto latest,
                          List<RecentPhoto> recent, List<TaxonCard> nextOnList, List<TaxonCard> fullLifeCycles) {
    }

    /** The tree has Custom additions only while it has a species shown. */
    public record TreeNode(String id, String rank, String name, String common, String family, String source,
                           Count species, List<TreeNode> children) {
    }
}
