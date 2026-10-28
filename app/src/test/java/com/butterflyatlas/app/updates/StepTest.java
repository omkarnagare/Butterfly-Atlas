package com.butterflyatlas.app.updates;

import com.butterflyatlas.app.photos.Photo;
import com.butterflyatlas.app.taxonomy.bundle.Bundle;
import com.butterflyatlas.app.testing.UpdateBundles;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The new tree of one bundle step (5b contract §3 "The new tree"): every change kind at every rank. */
class StepTest {

    @TempDir
    Path dir;

    private UpdateFixture f;

    @BeforeEach
    void setUp() {
        f = new UpdateFixture(dir);
    }

    private Step step(Bundle bundle) {
        return Step.of(f.db.read(Tree::load), bundle);
    }

    private static UpdateBundles.Derived v2() {
        return UpdateBundles.derive(UpdateFixture.v1(), "2026-02-01");
    }

    private static List<String> changes(Step step) {
        return step.changes.stream().map(c -> c.kind() + " " + c.rank() + " " + c.from() + "→" + c.to()).toList();
    }

    /** "id parent active name folder path" of a taxon in the new tree. */
    private static String node(Step step, String id) {
        Tree.Node n = step.after.get(id);
        return n.id + " " + n.parentId + " " + (n.active ? "active" : "inactive") + " " + n.name + " "
                + step.after.folder(id) + " " + step.after.ancestorPath(id);
    }

    @Test
    void aRenameAtEveryRankRenamesTheFoldersBelowIt() {
        Step step = step(v2().rename("A1", "Nymphalidae2").rename("A2", "Danainae2").rename("A3", "Danaini2")
                .rename("A4", "Danaus2").rename("A5", "Danaus chrysippa").common("A5", "Plain Tigress").build());

        assertThat(changes(step)).containsExactly("rename family [A1]→[A1]", "rename subfamily [A2]→[A2]",
                "rename tribe [A3]→[A3]", "rename genus [A4]→[A4]", "rename species [A5]→[A5]");
        assertThat(node(step, "A5")).isEqualTo("A5 A4 active Danaus chrysippa images/Nymphalidae2/Danainae2/Danaini2/Danaus2/"
                + "Danaus_chrysippa_[A5]/ /G:butterflies/A1/A2/A3/A4/A5/");
        assertThat(step.after.get("A5").common).isEqualTo("Plain Tigress");
        assertThat(step.after.folder("A8")).isEqualTo("images/Nymphalidae2/Danainae2/Danaini2/Tirumala/Tirumala_limniace_[A8]/");
        assertThat(step.before.get("A5").name).as("the library's tree is not changed").isEqualTo("Danaus chrysippus");
    }

    @Test
    void aMoveAtEveryRankTakesItsSubtreeAlong() {
        Step step = step(v2().move("A10", "A1").move("A3", "A10").move("A11", "A3").move("A6", "A7").move("A9", null).build());

        assertThat(changes(step)).containsExactly("move subfamily [A10]→[A10]", "move tribe [A3]→[A3]",
                "move genus [A11]→[A11]", "move species [A6]→[A6]");
        assertThat(node(step, "A6")).isEqualTo("A6 A7 active Danaus genutia images/Nymphalidae/Papilioninae/Danaini/Tirumala/"
                + "Danaus_genutia_[A6]/ /G:butterflies/A1/A10/A3/A7/A6/");
        assertThat(node(step, "A12")).isEqualTo("A12 A11 active Papilio polytes images/Nymphalidae/Papilioninae/Danaini/"
                + "Papilio/Papilio_polytes_[A12]/ /G:butterflies/A1/A10/A3/A11/A12/");
        assertThat(node(step, "A9")).as("Papilionidae keeps no subfamily").startsWith("A9 G:butterflies active Papilionidae");
    }

    @Test
    void aSpeciesMergeIntoANewTargetAddsItFirstAndTheSourceGoesInactiveWithItsCoverPassedOn() {
        f.sql("UPDATE taxon SET cover_image_id = 7 WHERE id = 'A14'");
        Step step = step(v2().add("A15", "A11", "species", "Papilio agestor", "Tawny Mime").merge("A15", "A14", "A13").build());

        assertThat(changes(step)).containsExactly("merge species [A14, A13]→[A15]");
        assertThat(step.inserted).containsExactly("A15");
        assertThat(step.merged).containsEntry("A13", "A15").containsEntry("A14", "A15");
        assertThat(node(step, "A15")).isEqualTo("A15 A11 active Papilio agestor images/Papilionidae/Papilioninae/Papilio/"
                + "Papilio_agestor_[A15]/ /G:butterflies/A9/A10/A11/A15/");
        assertThat(node(step, "A14")).startsWith("A14 A11 inactive Papilio clytia");
        assertThat(step.after.get("A15").cover).isEqualTo(7L);
        assertThat(step.after.get("A14").cover).isNull();
    }

    @Test
    void aHigherRankMergeTakesTheLibraryOnlyChildrenAndCustomSpeciesFollowAMergedFamily() {
        // A species the library still has but the list dropped earlier (inactive), under Tirumala.
        f.sql("INSERT INTO taxon (id, parent_id, rank, name, group_key, source, ancestor_path, folder_name, active, sort_key)"
                + " VALUES ('A99', 'A7', 'species', 'Tirumala gautama', 'butterflies', 'official', '/G:butterflies/A1/A2/A3/A7/A99/',"
                + " 'Tirumala_gautama_[A99]', 0, 'tirumala gautama')");
        f.custom.add("A9", "Papilio sankara", null);

        Step step = step(v2().merge("A4", "A7").merge("A1", "A9").build());

        assertThat(changes(step)).containsExactly("move subfamily [A10]→[A10]", "move species [A8]→[A8]",
                "move species [A99]→[A99]", "merge family [A9]→[A1]", "merge genus [A7]→[A4]");
        assertThat(node(step, "A99")).as("library-only: follows the merged genus").isEqualTo("A99 A4 inactive Tirumala gautama"
                + " images/Nymphalidae/Danainae/Danaini/Danaus/Tirumala_gautama_[A99]/ /G:butterflies/A1/A2/A3/A4/A99/");
        assertThat(node(step, "A8")).as("in the bundle: where the bundle puts it").startsWith("A8 A4 active");
        assertThat(node(step, "A7")).startsWith("A7 A3 inactive Tirumala");
        assertThat(step.after.get("U1").familyId).isEqualTo("A1");
        assertThat(step.after.folder("U1")).isEqualTo("images/Custom_additions/Papilio_sankara_[U1]/");
    }

    @Test
    void aMergeIntoATaxonOfAnotherRankIsTakenAsARemovalAndItsPhotosStay() {
        Photo photo = f.add("A14");

        Step step = step(v2().merge("A4", "A14").build());

        assertThat(changes(step)).containsExactly("remove species [A14]→[]");
        assertThat(step.merged).isEmpty();
        assertThat(node(step, "A14")).startsWith("A14 A11 inactive Papilio clytia");
        assertThat(step.moves(f.db.read(Model::live), path -> false)).isEmpty();
        assertThat(photo.taxonId()).isEqualTo("A14");
    }

    @Test
    void aSplitAddsItsTargetsAndTheSourceStaysWhenItKeepsItsId() {
        Step keeps = step(v2().add("A16", "A4", "species", "Danaus petilia", null).split("A5", "A5", "A16").build());
        Step goes = step(v2().add("A16", "A4", "species", "Danaus petilia", null)
                .add("A17", "A4", "species", "Danaus alcippus", null).split("A5", "A16", "A17").build());

        assertThat(changes(keeps)).containsExactly("split species [A5]→[A5, A16]");
        assertThat(keeps.after.get("A5").active).isTrue();
        assertThat(keeps.splits).containsEntry("A5", List.of("A5", "A16"));
        assertThat(changes(goes)).containsExactly("split species [A5]→[A16, A17]");
        assertThat(goes.after.get("A5").active).isFalse();
        assertThat(goes.inserted).containsExactly("A16", "A17");
    }

    @Test
    void addsAtAnyRankAndRemovalsOfASubtree() {
        Step step = step(v2().add("A20", "A10", "tribe", "Troidini", null).add("A21", "A20", "genus", "Pachliopta", null)
                .add("A22", "A21", "species", "Pachliopta hector", "Crimson Rose").remove("A7").build());

        assertThat(changes(step)).containsExactly("add tribe []→[A20]", "add genus []→[A21]", "add species []→[A22]",
                "remove genus [A7]→[]", "remove species [A8]→[]");
        assertThat(node(step, "A22")).isEqualTo("A22 A21 active Pachliopta hector images/Papilionidae/Papilioninae/Troidini/"
                + "Pachliopta/Pachliopta_hector_[A22]/ /G:butterflies/A9/A10/A20/A21/A22/");
        assertThat(node(step, "A8")).as("removed: stays where it is, inactive").startsWith("A8 A7 inactive Tirumala limniace");
    }

    @Test
    void aRemovedSpeciesReAddedIsReactivatedWithItsFolderUnchanged() {
        Bundle v2 = v2().remove("A13").build();
        Step removed = step(v2);
        f.sql("UPDATE taxon SET active = 0 WHERE id = 'A13'");

        Step back = step(UpdateBundles.derive(v2, "2026-03-01").readd(UpdateFixture.v1Taxon("A13")).build());

        assertThat(changes(removed)).containsExactly("remove species [A13]→[]");
        assertThat(changes(back)).containsExactly("reactivate species []→[A13]");
        assertThat(back.inserted).isEmpty();
        assertThat(node(back, "A13")).isEqualTo("A13 A11 active Papilio demoleus images/Papilionidae/Papilioninae/Papilio/"
                + "Papilio_demoleus_[A13]/ /G:butterflies/A9/A10/A11/A13/");
    }

    @Test
    void customSpeciesAndTheGroupAreLeftAsTheyAre() {
        f.custom.add("A1", "Neptis sankara", "Broad-banded Sailer");

        Step step = step(v2().rename("A1", "Nymphalidae2").build());

        assertThat(node(step, "U1")).isEqualTo("U1 U:butterflies active Neptis sankara images/Custom_additions/"
                + "Neptis_sankara_[U1]/ /G:butterflies/U:butterflies/U1/");
        assertThat(step.after.family("U1")).isEqualTo("Nymphalidae2");
        assertThat(step.after.get("G:butterflies").name).isEqualTo("Butterflies");
    }
}
