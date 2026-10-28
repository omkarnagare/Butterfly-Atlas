package com.butterflyatlas.app.taxa;

import com.butterflyatlas.app.AtlasException;
import com.butterflyatlas.app.db.Database;
import com.butterflyatlas.app.photos.PhotoQueries;
import com.butterflyatlas.app.photos.PhotoQueries.Stages;
import com.butterflyatlas.app.taxa.TaxonViews.Count;
import com.butterflyatlas.app.taxa.TaxonViews.RankCount;
import com.butterflyatlas.app.taxa.TaxonViews.TaxonCard;
import com.butterflyatlas.app.taxa.TaxonViews.TaxonDetail;
import com.butterflyatlas.app.taxa.TaxonViews.TreeNode;
import com.butterflyatlas.app.testing.TestBundles;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TaxonServiceTest {

    @TempDir
    Path dir;

    private Database db;
    private TaxonService taxa;

    @BeforeEach
    void setUp() {
        db = TestBundles.fixtureDatabase(dir);
        taxa = new TaxonService(() -> db);
    }

    @Test
    void summaryCountsEverySpeciesInTheGroup() {
        TaxonViews.Summary summary = taxa.summary();

        assertThat(summary.group().id()).isEqualTo("G:butterflies");
        assertThat(summary.group().species()).isEqualTo(new Count(0, 6));
        assertThat(summary.group().ranks()).containsExactly(
                new RankCount("family", 0, 2), new RankCount("subfamily", 0, 2), new RankCount("tribe", 0, 1),
                new RankCount("genus", 0, 3), new RankCount("species", 0, 6));
        assertThat(summary.families()).extracting(TaxonCard::name).containsExactly("Nymphalidae", "Papilionidae");
    }

    @Test
    void aPhotoCountsAtEveryRankAbove() {
        TestBundles.addImage(db, "A5");

        assertThat(taxa.summary().group().species()).isEqualTo(new Count(1, 6));
        assertThat(taxa.summary().group().ranks()).containsExactly(
                new RankCount("family", 1, 2), new RankCount("subfamily", 1, 2), new RankCount("tribe", 1, 1),
                new RankCount("genus", 1, 3), new RankCount("species", 1, 6));
        assertThat(taxa.taxon("A1").species()).isEqualTo(new Count(1, 3));
        assertThat(taxa.children("A3")).extracting(TaxonCard::species).containsExactly(new Count(1, 2), new Count(0, 1));
        assertThat(taxa.taxon("A9").species()).isEqualTo(new Count(0, 3));
    }

    @Test
    void childrenAreSortedByNameAndCarryTheirFamily() {
        assertThat(taxa.children("A3")).extracting(TaxonCard::id).containsExactly("A4", "A7");
        assertThat(taxa.children("A3")).extracting(TaxonCard::family).containsOnly("Nymphalidae");
        assertThat(taxa.children("A11")).extracting(TaxonCard::id).containsExactly("A14", "A13", "A12");
        assertThat(taxa.children("G:butterflies")).extracting(TaxonCard::id).containsExactly("A1", "A9", "U:butterflies");
    }

    @Test
    void detailHasBreadcrumbsNamesAndFolder() {
        TaxonDetail plainTiger = taxa.taxon("A5");

        assertThat(plainTiger.path()).extracting(TaxonViews.Crumb::id).containsExactly("G:butterflies", "A1", "A2", "A3", "A4");
        assertThat(plainTiger.name()).isEqualTo("Danaus chrysippus");
        assertThat(plainTiger.common()).isEqualTo("Plain Tiger");
        assertThat(plainTiger.rank()).isEqualTo("species");
        assertThat(plainTiger.family()).isEqualTo("Nymphalidae");
        assertThat(plainTiger.active()).isTrue();
        assertThat(plainTiger.synonyms()).containsExactly("Limnas klugii");
        assertThat(plainTiger.searchWords()).isEmpty();
        assertThat(plainTiger.folder()).isEqualTo("images/Nymphalidae/Danainae/Danaini/Danaus/Danaus_chrysippus_[A5]/");
        assertThat(plainTiger.ranks()).isEmpty();
        assertThat(taxa.taxon("A4").folder()).isNull();
    }

    @Test
    void aFamilyWithoutTribesHasNoTribeCount() {
        assertThat(taxa.taxon("A9").ranks()).containsExactly(
                new RankCount("subfamily", 0, 1), new RankCount("genus", 0, 1), new RankCount("species", 0, 3));
    }

    @Test
    void aRetiredSpeciesWithoutPhotosIsHiddenAndNotCounted() {
        TestBundles.retire(db, "A13");

        assertThat(taxa.children("A11")).extracting(TaxonCard::id).containsExactly("A14", "A12");
        assertThat(taxa.taxon("A9").species()).isEqualTo(new Count(0, 2));
        assertThatThrownBy(() -> taxa.taxon("A13")).isInstanceOfSatisfying(AtlasException.class,
                e -> assertThat(e.kind()).isEqualTo(AtlasException.Kind.NOT_FOUND));
    }

    @Test
    void aRetiredSpeciesWithPhotosStaysAndCounts() {
        TestBundles.retire(db, "A13");
        TestBundles.addImage(db, "A13");

        assertThat(taxa.children("A11")).filteredOn(card -> card.id().equals("A13"))
                .singleElement().satisfies(card -> {
                    assertThat(card.active()).isFalse();
                    assertThat(card.species()).isEqualTo(new Count(1, 1));
                });
        assertThat(taxa.taxon("A9").species()).isEqualTo(new Count(1, 3));
    }

    @Test
    void aPhotoInRecentlyDeletedNoLongerCounts() {
        long photo = TestBundles.addImage(db, "A5");
        TestBundles.markDeleted(db, photo);

        assertThat(taxa.summary().group().species()).isEqualTo(new Count(0, 6));
        assertThat(taxa.taxon("A1").species()).isEqualTo(new Count(0, 3));
    }

    @Test
    void aRetiredSpeciesWhoseOnlyPhotoIsDeletedIsHidden() {
        TestBundles.retire(db, "A13");
        TestBundles.markDeleted(db, TestBundles.addImage(db, "A13"));

        assertThat(taxa.children("A11")).extracting(TaxonCard::id).containsExactly("A14", "A12");
        assertThatThrownBy(() -> taxa.taxon("A13")).isInstanceOf(AtlasException.class);
    }

    @Test
    void unknownIdsAreNotFound() {
        assertThatThrownBy(() -> taxa.taxon("A999")).isInstanceOfSatisfying(AtlasException.class, e -> {
            assertThat(e.kind()).isEqualTo(AtlasException.Kind.NOT_FOUND);
            assertThat(e.getMessage()).isEqualTo("There is no taxon A999 in your atlas.");
        });
        assertThatThrownBy(() -> taxa.children("A999")).isInstanceOf(AtlasException.class);
    }

    @Test
    void treeHoldsEveryVisibleTaxonWithCounts() {
        TestBundles.addImage(db, "A12");

        TreeNode root = taxa.tree();

        assertThat(root.id()).isEqualTo("G:butterflies");
        assertThat(root.species()).isEqualTo(new Count(1, 6));
        assertThat(root.children()).extracting(TreeNode::id).containsExactly("A1", "A9");
        TreeNode danaini = root.children().get(0).children().get(0).children().get(0);
        assertThat(danaini.children()).extracting(TreeNode::id).containsExactly("A4", "A7");
        TreeNode mormon = root.children().get(1).children().get(0).children().get(0).children().get(2);
        assertThat(mormon.id()).isEqualTo("A12");
        assertThat(mormon.species()).isEqualTo(new Count(1, 1));
        assertThat(mormon.children()).isEmpty();
    }

    @Test
    void customAdditionsJoinHomeAndTheTreeOnceTheyHoldASpecies() {
        assertThat(taxa.summary().families()).extracting(TaxonCard::id).containsExactly("A1", "A9");
        assertThat(taxa.tree().children()).extracting(TreeNode::id).containsExactly("A1", "A9");

        TestBundles.addManualSpecies(db, "U7", "Something newus", "A1");

        TaxonViews.Summary summary = taxa.summary();
        assertThat(summary.group().species()).isEqualTo(new Count(0, 7));
        assertThat(summary.families()).extracting(TaxonCard::id).containsExactly("A1", "A9", "U:butterflies");
        assertThat(taxa.tree().children()).extracting(TreeNode::id).containsExactly("A1", "A9", "U:butterflies");
        assertThat(taxa.tree().children().get(2).children()).singleElement().satisfies(node -> {
            assertThat(node.family()).isEqualTo("Nymphalidae");
            assertThat(node.source()).isEqualTo("manual");
        });
        assertThat(taxa.taxon("A1").species()).as("never a family's").isEqualTo(new Count(0, 3));
        assertThat(taxa.taxon("U7").folder()).isEqualTo("images/Custom_additions/Something_newus_[U7]/");
    }

    @Test
    void customAdditionsAreAlwaysAChildOfTheGroupLastAndCanBeOpenedEmpty() {
        assertThat(taxa.children("G:butterflies")).extracting(TaxonCard::id).containsExactly("A1", "A9", "U:butterflies");
        TaxonCard additions = taxa.children("G:butterflies").get(2);
        assertThat(additions.rank()).isEqualTo("additions");
        assertThat(additions.name()).isEqualTo("Custom additions");
        assertThat(additions.source()).isEqualTo("system");
        assertThat(additions.family()).isNull();
        assertThat(additions.species()).isEqualTo(new Count(0, 0));

        TaxonDetail empty = taxa.taxon("U:butterflies");
        assertThat(empty.species()).isEqualTo(new Count(0, 0));
        assertThat(empty.path()).extracting(TaxonViews.Crumb::id).containsExactly("G:butterflies");
        assertThat(taxa.children("U:butterflies")).isEmpty();

        TestBundles.addManualSpecies(db, "U1", "Neptis sankara", "A1");
        TestBundles.addImage(db, "U1");
        assertThat(taxa.taxon("U:butterflies").species()).isEqualTo(new Count(1, 1));
        assertThat(taxa.children("G:butterflies").get(2).species()).isEqualTo(new Count(1, 1));
        assertThat(taxa.summary().group().species()).as("the group counts it").isEqualTo(new Count(1, 7));
        assertThat(taxa.taxon("A1").species()).as("its family does not").isEqualTo(new Count(0, 3));
    }

    @Test
    void aCustomSpeciesShowsItsFamilyAndSource() {
        TestBundles.addManualSpecies(db, "U3", "Neptis sankara", "A1");

        TaxonCard card = taxa.children("U:butterflies").getFirst();
        assertThat(card.family()).isEqualTo("Nymphalidae");
        assertThat(card.source()).isEqualTo("manual");
        TaxonDetail detail = taxa.taxon("U3");
        assertThat(detail.family()).isEqualTo("Nymphalidae");
        assertThat(detail.familyId()).isEqualTo("A1");
        assertThat(detail.source()).isEqualTo("manual");
        assertThat(detail.path()).extracting(TaxonViews.Crumb::name).containsExactly("Butterflies", "Custom additions");
        TaxonDetail official = taxa.taxon("A5");
        assertThat(official.source()).isEqualTo("official");
        assertThat(official.familyId()).isNull();
        assertThat(taxa.taxon("G:butterflies").source()).isEqualTo("system");
        assertThat(taxa.children("A4")).extracting(TaxonCard::source).containsOnly("official");
    }

    @Test
    void aRetiredGenusStaysWhileItsSpeciesAreListed() {
        TestBundles.retire(db, "A4");

        assertThat(taxa.children("A3")).filteredOn(card -> card.id().equals("A4"))
                .singleElement().satisfies(card -> {
                    assertThat(card.active()).isFalse();
                    assertThat(card.species()).isEqualTo(new Count(0, 2));
                });
    }

    @Test
    void speciesDetailHasPhotoCountStagesAndCover() {
        long egg = TestBundles.addImage(db, "A5");
        long adult = TestBundles.addImage(db, "A5");
        TestBundles.addImage(db, "A5");
        TestBundles.tagImage(db, egg, "egg");
        TestBundles.tagImage(db, adult, "upper_wing");

        TaxonDetail plainTiger = taxa.taxon("A5");

        assertThat(plainTiger.photoCount()).isEqualTo(3);
        assertThat(plainTiger.stages()).isEqualTo(new Stages(true, false, false, true));
        assertThat(plainTiger.cover().id()).isEqualTo(adult);
        assertThat(plainTiger.cover().isCover()).isTrue();
        assertThat(taxa.taxon("A6").cover()).isNull();
        assertThat(taxa.taxon("A6").stages()).isEqualTo(Stages.NONE);
    }

    @Test
    void higherRanksCountThePhotosBelowThem() {
        TestBundles.addImage(db, "A5");
        TestBundles.addImage(db, "A6");
        TestBundles.markDeleted(db, TestBundles.addImage(db, "A6"));
        TestBundles.addImage(db, "A12");

        assertThat(taxa.taxon("A4").photoCount()).isEqualTo(2);
        assertThat(taxa.taxon("A4").cover()).isNull();
        assertThat(taxa.taxon("A1").photoCount()).isEqualTo(2);
        assertThat(taxa.summary().group().photoCount()).isEqualTo(3);
    }

    @Test
    void speciesCardsCarryTheirCover() {
        long photo = TestBundles.addImage(db, "A5");

        assertThat(taxa.children("A4")).extracting(TaxonCard::cover)
                .containsExactly(new PhotoQueries.Cover(photo, String.valueOf(Instant.parse("2026-10-09T08:00:00Z").toEpochMilli() + "-1")),
                        null);
        assertThat(taxa.children("A3")).extracting(TaxonCard::cover).containsOnlyNulls();
        assertThat(taxa.summary().families()).extracting(TaxonCard::cover).containsOnlyNulls();
    }

    @Test
    void homeShowsTheLatestAndRecentPhotos() {
        assertThat(taxa.summary().photoCount()).isZero();
        assertThat(taxa.summary().latest()).isNull();
        assertThat(taxa.summary().recent()).isEmpty();

        long last = 0;
        for (int i = 0; i < 14; i++) {
            last = TestBundles.addImage(db, i % 2 == 0 ? "A5" : "A12");
        }
        TestBundles.markDeleted(db, last);

        TaxonViews.Summary summary = taxa.summary();
        assertThat(summary.photoCount()).isEqualTo(13);
        assertThat(summary.recent()).hasSize(12);
        assertThat(summary.latest().photo().id()).isEqualTo(last - 1);
        assertThat(summary.latest().taxon().id()).isEqualTo("A5");
        assertThat(summary.latest().taxon().cover()).isNotNull();
        assertThat(summary.recent().get(1).taxon().id()).isEqualTo("A12");
    }

    @Test
    void nextOnListPicksTheGeneraClosestToComplete() {
        assertThat(taxa.summary().nextOnList()).isEmpty();

        TestBundles.addImage(db, "A5");   // Danaus 1 of 2
        TestBundles.addImage(db, "A12");  // Papilio 1 of 3

        assertThat(taxa.summary().nextOnList()).extracting(TaxonCard::id).containsExactly("A6", "A14", "A13");

        TestBundles.addImage(db, "A6");   // Danaus complete: nothing left to suggest there
        assertThat(taxa.summary().nextOnList()).extracting(TaxonCard::id).containsExactly("A14", "A13");
    }

    @Test
    void fullLifeCyclesNeedEggLarvaPupaAndAnAdult() {
        long egg = TestBundles.addImage(db, "A5");
        long larva = TestBundles.addImage(db, "A5");
        long pupa = TestBundles.addImage(db, "A5");
        TestBundles.tagImage(db, egg, "egg");
        TestBundles.tagImage(db, larva, "larva");
        TestBundles.tagImage(db, pupa, "pupa");
        assertThat(taxa.summary().fullLifeCycles()).isEmpty();

        long adult = TestBundles.addImage(db, "A5");
        TestBundles.tagImage(db, adult, "wsf");

        assertThat(taxa.summary().fullLifeCycles()).extracting(TaxonCard::id).containsExactly("A5");
        assertThat(taxa.taxon("A5").stages().full()).isTrue();
        TestBundles.markDeleted(db, pupa);
        assertThat(taxa.summary().fullLifeCycles()).isEmpty();
    }
}
