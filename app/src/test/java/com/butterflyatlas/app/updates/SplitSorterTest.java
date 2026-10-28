package com.butterflyatlas.app.updates;

import com.butterflyatlas.app.AtlasException;
import com.butterflyatlas.app.photos.Photo;
import com.butterflyatlas.app.photos.PhotoQueries;
import com.butterflyatlas.app.photos.SpeciesFiles;
import com.butterflyatlas.app.search.SearchService;
import com.butterflyatlas.app.taxa.TaxonViews.Count;
import com.butterflyatlas.app.taxonomy.bundle.Bundle;
import com.butterflyatlas.app.testing.UpdateBundles;
import com.butterflyatlas.app.updates.UpdateViews.Ref;
import com.butterflyatlas.app.updates.UpdateViews.Split;
import com.butterflyatlas.app.updates.UpdateViews.SplitBrief;
import com.butterflyatlas.app.updates.UpdateViews.SplitTarget;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.AccessDeniedException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Sorting a split species' photos (5b contract §5): the photos still to sort, Sort, and toSort on the taxon views. */
class SplitSorterTest {

    private static final String DANAUS = "images/Nymphalidae/Danainae/Danaini/Danaus/";

    @TempDir
    Path dir;

    private UpdateFixture f;
    private final AtomicInteger changes = new AtomicInteger();
    private SplitSorter sorter;
    private List<Photo> chrysippus;

    @BeforeEach
    void setUp() throws Exception {
        f = new UpdateFixture(dir);
        f.populated();
        sorter = new SplitSorter(() -> f.library, f.photos, new PhotoQueries(() -> f.library, f.clock), changes::incrementAndGet);
        chrysippus = new PhotoQueries(() -> f.library, f.clock).photos("A5");
    }

    private Updates applied(Bundle v2) throws InterruptedException {
        Updates updates = new Updates(() -> f.library, UpdateBundles.bundles(UpdateFixture.v1(), v2), f.clock);
        updates.startup();
        updates.apply();
        assertThat(updates.awaitIdle(Duration.ofSeconds(30))).isTrue();
        return updates;
    }

    private static void invalid(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String message) {
        assertThatThrownBy(call).isInstanceOfSatisfying(AtlasException.class, e -> {
            assertThat(e.kind()).isEqualTo(AtlasException.Kind.INVALID);
            assertThat(e.getMessage()).isEqualTo(message);
        });
    }

    @Test
    void aSplitSpeciesListsItsPhotosToSortAndTheSpeciesItWentInto() throws Exception {
        Updates updates = applied(UpdateFixture.v2());

        SplitBrief brief = new SplitBrief("A5", "Danaus chrysippus", "Plain Tiger", "Nymphalidae", 3, "2026-02-01",
                List.of(new Ref("A5", "Danaus chrysippus", "Plain Tiger"), new Ref("A16", "Danaus petilia", null)));
        assertThat(sorter.splits()).containsExactly(brief);
        assertThat(updates.update().result().splits()).containsExactly(brief);
        Split split = sorter.split("A5");
        assertThat(split.taxon()).isEqualTo(brief);
        assertThat(split.targets()).containsExactly(
                new SplitTarget("A5", "Danaus chrysippus", "Plain Tiger", "Nymphalidae", new Count(1, 1)),
                new SplitTarget("A16", "Danaus petilia", null, "Nymphalidae", new Count(0, 1)));
        assertThat(split.left()).isEqualTo(3);
        assertThat(split.total()).isEqualTo(3);
        assertThat(split.photos()).extracting(Photo::id).containsExactlyElementsOf(chrysippus.stream().map(Photo::id).toList());
        assertThat(f.taxa.taxon("A5").toSort()).isEqualTo(3);
        assertThat(f.taxa.children("A4")).filteredOn(c -> c.id().equals("A5")).extracting(c -> c.toSort()).containsExactly(3);
        assertThat(new SearchService(() -> f.db).search("chrysippus", 5)).extracting(SearchService.Hit::toSort).containsExactly(3);
        assertThat(f.taxa.taxon("A6").toSort()).isZero();
        assertThatThrownBy(() -> sorter.split("A6")).isInstanceOfSatisfying(AtlasException.class,
                e -> assertThat(e.getMessage()).isEqualTo("There is no split species A6 in your atlas."));
    }

    @Test
    void sortingMovesThePhotosIntoTheTargetAndSortingIntoTheSplitSpeciesItselfLeavesThemThere() throws Exception {
        applied(UpdateFixture.v2());
        List<Long> two = chrysippus.subList(0, 2).stream().map(Photo::id).toList();
        Photo third = chrysippus.get(2);

        Split after = sorter.sort("A5", two, "A16");

        assertThat(after.left()).isEqualTo(1);
        assertThat(after.total()).as("of the photos it had at the split").isEqualTo(3);
        assertThat(after.photos()).extracting(Photo::id).containsExactly(third.id());
        assertThat(f.rows("SELECT rel_path FROM image WHERE taxon_id = 'A16' ORDER BY number")).containsExactly(
                DANAUS + "Danaus_petilia_[A16]/Danaus_petilia_01.jpg", DANAUS + "Danaus_petilia_[A16]/Danaus_petilia_02.jpg");
        assertThat(f.file(DANAUS + "Danaus_petilia_[A16]/Danaus_petilia_02.jpg")).isRegularFile();
        assertThat(f.rows("SELECT COUNT(*) FROM change_log WHERE kind = 'photo.move' AND payload_json LIKE '%\"A16\"%'"))
                .containsExactly("2");
        assertThat(f.taxa.taxon("A5").toSort()).isEqualTo(1);
        assertThat(changes.get()).isEqualTo(1);

        Split done = sorter.sort("A5", List.of(third.id()), "A5");

        assertThat(done.left()).isZero();
        assertThat(done.photos()).isEmpty();
        assertThat(f.rows("SELECT rel_path FROM image WHERE id = " + third.id())).containsExactly(third.relPath());
        assertThat(f.file(third.relPath())).isRegularFile();
        assertThat(sorter.splits()).isEmpty();
        assertThat(f.taxa.taxon("A5").toSort()).isZero();
        assertThat(changes.get()).isEqualTo(2);
    }

    @Test
    void whenTheLastPhotoOfASplitSpeciesThatWentIsSortedItIsHidden() throws Exception {
        applied(UpdateBundles.derive(UpdateFixture.v1(), "2026-02-01").add("A16", "A4", "species", "Danaus petilia", null)
                .add("A17", "A4", "species", "Danaus alcippus", null).split("A5", "A16", "A17").build());
        assertThat(f.taxa.taxon("A5").active()).isFalse();

        sorter.sort("A5", chrysippus.stream().map(Photo::id).toList(), "A17");

        assertThatThrownBy(() -> f.taxa.taxon("A5")).as("inactive with no live photo").isInstanceOf(AtlasException.class);
        assertThat(f.rows("SELECT COUNT(*) FROM image WHERE taxon_id = 'A17'")).containsExactly("3");
        assertThat(sorter.split("A5").left()).isZero();
    }

    @Test
    void aSortIsRefusedForAnotherTargetPhotosNotToSortOrNone() throws Exception {
        applied(UpdateFixture.v2());
        long genutia = f.rows("SELECT min(id) FROM image WHERE taxon_id = 'A6'").stream().map(Long::parseLong).findFirst()
                .orElseThrow();
        List<Long> one = List.of(chrysippus.getFirst().id());

        invalid(() -> sorter.sort("A5", one, "A6"), "Choose one of the species Danaus chrysippus was split into.");
        invalid(() -> sorter.sort("A5", List.of(genutia), "A16"), "Choose photos of Danaus chrysippus that are still to sort.");
        invalid(() -> sorter.sort("A5", List.of(), "A16"), "Choose at least one photo.");
        assertThatThrownBy(() -> sorter.sort("A6", one, "A16")).isInstanceOfSatisfying(AtlasException.class,
                e -> assertThat(e.kind()).isEqualTo(AtlasException.Kind.NOT_FOUND));
        assertThat(sorter.split("A5").left()).isEqualTo(3);
    }

    @Test
    void aPhotoMovedAwayWithMoveIsNoLongerToSort() throws Exception {
        applied(UpdateFixture.v2());

        f.photos.move(List.of(chrysippus.getFirst().id()), "A6");

        assertThat(sorter.split("A5").left()).isEqualTo(2);
        assertThat(f.rows("SELECT COUNT(*) FROM split_photo")).containsExactly("2");
    }

    @Test
    void aSplitSpeciesMergedAwayLaterTakesItsPhotosToSortAlongAndTheResultSaysSo() throws Exception {
        Bundle v3 = UpdateBundles.derive(UpdateFixture.v2(), "2026-03-01").merge("A6", "A5").build();
        Updates updates = new Updates(() -> f.library, UpdateBundles.bundles(UpdateFixture.v1(), UpdateFixture.v2(), v3), f.clock);
        updates.startup();
        updates.apply();
        assertThat(updates.awaitIdle(Duration.ofSeconds(30))).isTrue();

        assertThat(updates.update().result().splitsMerged()).containsExactly(new UpdateViews.SplitMerged(
                new Ref("A5", "Danaus chrysippus", "Plain Tiger"), new Ref("A6", "Danaus melanippus", "Striped Tiger"), 3));
        assertThat(updates.update().result().notes()).containsExactly("Photos still to sort on Danaus chrysippus moved to"
                + " Danaus melanippus with the merge.");
        assertThat(updates.update().last().notes()).isEqualTo(updates.update().result().notes());
        assertThat(f.rows("SELECT COUNT(*) FROM split_photo")).containsExactly("0");
        assertThat(sorter.splits()).isEmpty();
        assertThat(f.rows("SELECT COUNT(*) FROM image WHERE taxon_id = 'A6' AND deleted_at IS NULL")).containsExactly("5");
    }

    @Test
    void aTargetNoLongerOnTheListIsAMissingTargetAndCannotBeSortedInto() throws Exception {
        applied(UpdateFixture.v2());
        Bundle v3 = UpdateBundles.derive(UpdateFixture.v2(), "2026-03-01").remove("A16").build();
        Updates next = new Updates(() -> f.library, UpdateBundles.bundles(UpdateFixture.v1(), UpdateFixture.v2(), v3), f.clock);
        next.startup();
        next.apply();
        assertThat(next.awaitIdle(Duration.ofSeconds(30))).isTrue();

        Split split = sorter.split("A5");

        assertThat(split.targets()).extracting(SplitTarget::id).containsExactly("A5");
        assertThat(split.missingTargets()).containsExactly(new Ref("A16", "Danaus petilia", null));
        invalid(() -> sorter.sort("A5", List.of(chrysippus.getFirst().id()), "A16"),
                "Choose one of the species Danaus chrysippus was split into.");
    }

    @Test
    void aSortWaitsForATaxonomyUpdate() throws Exception {
        applied(UpdateFixture.v2());
        AtomicInteger calls = new AtomicInteger();
        Bundle v3 = UpdateBundles.derive(UpdateFixture.v2(), "2026-03-01").rename("A6", "Danaus genutius").build();
        Updates paused = new Updates(() -> f.library, UpdateBundles.bundles(UpdateFixture.v1(), UpdateFixture.v2(), v3), f.clock,
                (from, to) -> {
                    if (calls.incrementAndGet() == 1) {
                        throw new AccessDeniedException(from.toString());
                    }
                    SpeciesFiles.moveFile(from, to);
                }, path -> Long.MAX_VALUE);
        paused.startup();
        paused.apply();
        assertThat(paused.awaitIdle(Duration.ofSeconds(30))).isTrue();

        assertThatThrownBy(() -> sorter.sort("A5", List.of(chrysippus.getFirst().id()), "A16"))
                .isInstanceOfSatisfying(AtlasException.class, e -> assertThat(e.code()).isEqualTo("taxonomy_updating"));
        assertThat(sorter.split("A5").left()).isEqualTo(3);
    }
}
