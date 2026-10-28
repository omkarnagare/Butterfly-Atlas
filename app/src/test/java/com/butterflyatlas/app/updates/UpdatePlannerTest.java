package com.butterflyatlas.app.updates;

import com.butterflyatlas.app.photos.Photo;
import com.butterflyatlas.app.taxonomy.bundle.Bundle;
import com.butterflyatlas.app.testing.UpdateBundles;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The plan of a step (5b contract §3 stage 1): one move per photo whose path changes, written, no file touched. */
class UpdatePlannerTest {

    private static final String GENUTIA = "images/Nymphalidae/Danainae/Danaini/Danaus/Danaus_genutia_[A6]/";
    private static final String MELANIPPUS = "images/Nymphalidae/Danainae/Danaini/Danaus/Danaus_melanippus_[A6]/";
    private static final String POLYTES = "images/Papilionidae/Papilioninae/Papilio/Papilio_polytes_[A12]/";

    @TempDir
    Path dir;

    private UpdateFixture f;

    @BeforeEach
    void setUp() {
        f = new UpdateFixture(dir);
        f.sql("INSERT INTO taxonomy_update (kind, from_version, to_version, step_version, stage, state, started_at)"
                + " VALUES ('bundle', '2026-01-01', '2026-02-01', '2026-02-01', 'plan', 'running', 't')");
    }

    private UpdatePlanner.Plan plan(Bundle bundle) {
        return f.db.write(c -> UpdatePlanner.plan(c, f.library.folder(), 1, bundle, 1));
    }

    private static UpdateBundles.Derived v2() {
        return UpdateBundles.derive(UpdateFixture.v1(), "2026-02-01");
    }

    private List<String> moves() {
        return f.rows("SELECT seq, image_id, old_rel, new_rel, new_file_name, new_number, done FROM taxonomy_move ORDER BY seq");
    }

    @Test
    void aRenamedSpeciesPhotosGetItsNewFolderAndNameAndAFileNamedByHandKeepsItsName() throws Exception {
        List<Photo> photos = f.add("A6", 3);
        Files.move(f.file(photos.get(2).relPath()), f.file(GENUTIA + "my_best.jpg"));
        f.sql("UPDATE image SET rel_path = '" + GENUTIA + "my_best.jpg', file_name = 'my_best.jpg', number = NULL WHERE id = "
                + photos.get(2).id());
        Photo deleted = f.add("A6");
        f.photos.delete(List.of(deleted.id()));
        f.add("A5");

        UpdatePlanner.Plan plan = plan(v2().rename("A6", "Danaus melanippus").build());

        assertThat(plan.conflict()).isNull();
        assertThat(moves()).containsExactly(
                "1|" + photos.get(0).id() + "|" + GENUTIA + "Danaus_genutia_01.jpg|" + MELANIPPUS + "Danaus_melanippus_01.jpg"
                        + "|Danaus_melanippus_01.jpg|null|0",
                "2|" + photos.get(1).id() + "|" + GENUTIA + "Danaus_genutia_02.jpg|" + MELANIPPUS + "Danaus_melanippus_02.jpg"
                        + "|Danaus_melanippus_02.jpg|null|0",
                "3|" + photos.get(2).id() + "|" + GENUTIA + "my_best.jpg|" + MELANIPPUS + "my_best.jpg|my_best.jpg|null|0");
        assertThat(f.file(photos.get(0).relPath())).as("no file touched").exists();
        assertThat(f.file(MELANIPPUS)).doesNotExist();
        assertThat(f.rows("SELECT name FROM taxon WHERE id = 'A6'")).as("the taxa change at the switch-over")
                .containsExactly("Danaus genutia");
    }

    @Test
    void aGenusMovedWhileItsSpeciesIsRenamedGivesOneMovePerPhoto() {
        Photo photo = f.add("A8");

        plan(v2().move("A7", "A2").rename("A8", "Tirumala limniacea").build());

        assertThat(moves()).containsExactly("1|" + photo.id() + "|images/Nymphalidae/Danainae/Danaini/Tirumala/"
                + "Tirumala_limniace_[A8]/Tirumala_limniace_01.jpg|images/Nymphalidae/Danainae/Tirumala/"
                + "Tirumala_limniacea_[A8]/Tirumala_limniacea_01.jpg|Tirumala_limniacea_01.jpg|null|0");
    }

    @Test
    void aMergeGivesTheTargetsNextFreeNumbersSkippingNamesTakenOnDiskAndMovesItsNextNumberOn() throws Exception {
        f.add("A12", 2);
        Files.writeString(f.file(POLYTES + "Papilio_polytes_03.jpg"), "a file copied in by hand");
        List<Photo> clytia = f.add("A14", 2);
        f.add("A13");

        plan(v2().merge("A12", "A14", "A13").build());

        assertThat(moves()).extracting(r -> r.substring(r.indexOf('|') + 1)).containsExactly(
                clytia.get(0).id() + "|" + clytia.get(0).relPath() + "|" + POLYTES + "Papilio_polytes_05.jpg"
                        + "|Papilio_polytes_05.jpg|5|0",
                clytia.get(1).id() + "|" + clytia.get(1).relPath() + "|" + POLYTES + "Papilio_polytes_06.jpg"
                        + "|Papilio_polytes_06.jpg|6|0",
                (clytia.get(1).id() + 1) + "|images/Papilionidae/Papilioninae/Papilio/Papilio_demoleus_[A13]/Papilio_demoleus_01.jpg"
                        + "|" + POLYTES + "Papilio_polytes_04.jpg|Papilio_polytes_04.jpg|4|0");
        assertThat(f.rows("SELECT next_photo_number FROM taxon WHERE id = 'A12'")).containsExactly("7");
    }

    @Test
    void aMergeIntoANewTargetNumbersFromOneAndLeavesItsNumberToTheSwitchOver() {
        List<Photo> clytia = f.add("A14", 2);

        plan(v2().add("A15", "A11", "species", "Papilio agestor", null).merge("A15", "A14").build());

        String agestor = "images/Papilionidae/Papilioninae/Papilio/Papilio_agestor_[A15]/";
        assertThat(moves()).containsExactly(
                "1|" + clytia.get(0).id() + "|" + clytia.get(0).relPath() + "|" + agestor + "Papilio_agestor_01.jpg"
                        + "|Papilio_agestor_01.jpg|1|0",
                "2|" + clytia.get(1).id() + "|" + clytia.get(1).relPath() + "|" + agestor + "Papilio_agestor_02.jpg"
                        + "|Papilio_agestor_02.jpg|2|0");
    }

    @Test
    void splitAndRemovedSpeciesPhotosStayWhereTheyAre() {
        f.add("A5", 2);
        f.add("A13");

        UpdatePlanner.Plan plan = plan(v2().add("A16", "A4", "species", "Danaus petilia", null).split("A5", "A16")
                .remove("A13").build());

        assertThat(plan.moves()).isEmpty();
        assertThat(moves()).isEmpty();
    }

    @Test
    void aTargetAlreadyOnDiskIsAConflictAndNothingIsWritten() throws Exception {
        f.add("A6", 2);
        Files.createDirectories(f.file(MELANIPPUS));
        Files.writeString(f.file(MELANIPPUS + "Danaus_melanippus_02.jpg"), "someone else's");

        UpdatePlanner.Plan plan = plan(v2().rename("A6", "Danaus melanippus").build());

        assertThat(plan.conflict().message()).isEqualTo("A file named Danaus_melanippus_02.jpg is already in"
                + " Danaus_melanippus_[A6]. Move it out of the way, then choose Retry.");
        assertThat(moves()).isEmpty();
        assertThat(f.file(MELANIPPUS + "Danaus_melanippus_02.jpg")).hasContent("someone else's");
    }

    @Test
    void twoTargetsEqualWithoutCaseGiveTheLaterAFreeName() throws Exception {
        List<Photo> photos = f.add("A6", 2);
        // A file named by hand as the first photo will be named after the rename, but for case.
        Files.move(f.file(photos.get(1).relPath()), f.file(GENUTIA + "DANAUS_MELANIPPUS_01.jpg"));
        f.sql("UPDATE image SET rel_path = '" + GENUTIA + "DANAUS_MELANIPPUS_01.jpg', file_name = 'DANAUS_MELANIPPUS_01.jpg',"
                + " number = NULL WHERE id = " + photos.get(1).id());

        UpdatePlanner.Plan plan = plan(v2().rename("A6", "Danaus melanippus").build());

        assertThat(plan.conflict()).isNull();
        assertThat(moves()).extracting(r -> r.substring(r.indexOf('|') + 1)).containsExactly(
                photos.get(0).id() + "|" + GENUTIA + "Danaus_genutia_01.jpg|" + MELANIPPUS + "Danaus_melanippus_01.jpg"
                        + "|Danaus_melanippus_01.jpg|null|0",
                photos.get(1).id() + "|" + GENUTIA + "DANAUS_MELANIPPUS_01.jpg|" + MELANIPPUS + "Danaus_melanippus_03.jpg"
                        + "|Danaus_melanippus_03.jpg|3|0");
        assertThat(f.rows("SELECT next_photo_number FROM taxon WHERE id = 'A6'")).containsExactly("4");
    }
}
