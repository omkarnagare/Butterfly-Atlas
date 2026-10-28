package com.butterflyatlas.app.updates;

import com.butterflyatlas.app.photos.Photo;
import com.butterflyatlas.app.taxonomy.bundle.Bundle;
import com.butterflyatlas.app.testing.UpdateBundles;
import com.butterflyatlas.app.updates.UpdateViews.Update;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Names and folders (review J18): the update's own targets never clash (the later gets a free name), two genera that
 * swap names move everything, and a folder kept only by a file browser's clutter is removed with it.
 */
class UpdatesNamesTest {

    private static final String DANAINI = "images/Nymphalidae/Danainae/Danaini/";
    private static final String GENUTIA = DANAINI + "Danaus/Danaus_genutia_[A6]/";
    private static final String MELANIPPUS = DANAINI + "Danaus/Danaus_melanippus_[A6]/";

    @TempDir
    Path dir;

    private UpdateFixture f;

    @BeforeEach
    void setUp() {
        f = new UpdateFixture(dir);
    }

    private Update applied(Bundle v2) throws InterruptedException {
        Updates updates = new Updates(() -> f.library, UpdateBundles.bundles(UpdateFixture.v1(), v2), f.clock);
        updates.startup();
        updates.apply();
        assertThat(updates.awaitIdle(Duration.ofSeconds(30))).isTrue();
        return updates.update();
    }

    private static UpdateBundles.Derived v2() {
        return UpdateBundles.derive(UpdateFixture.v1(), "2026-02-01");
    }

    private void everyRowHasItsFile() {
        for (String rel : f.rows("SELECT rel_path FROM image WHERE deleted_at IS NULL")) {
            assertThat(f.file(rel)).as(rel).isRegularFile();
        }
    }

    @Test
    void aFileNamedByHandAsTheNewNameIsGivenAFreeNameNotAPause() throws Exception {
        List<Photo> photos = f.add("A6", 2);
        byte[] mine = Files.readAllBytes(f.file(photos.get(1).relPath()));
        // The user named the second photo as the first will be named after the rename.
        Files.move(f.file(photos.get(1).relPath()), f.file(GENUTIA + "Danaus_melanippus_01.jpg"));
        f.sql("UPDATE image SET rel_path = '" + GENUTIA + "Danaus_melanippus_01.jpg', file_name = 'Danaus_melanippus_01.jpg',"
                + " number = NULL WHERE id = " + photos.get(1).id());

        Update done = applied(v2().rename("A6", "Danaus melanippus").build());

        assertThat(done.state()).isEqualTo("done");
        assertThat(f.rows("SELECT id, file_name, number FROM image ORDER BY id")).containsExactly(
                photos.get(0).id() + "|Danaus_melanippus_01.jpg|1", photos.get(1).id() + "|Danaus_melanippus_03.jpg|3");
        assertThat(Files.readAllBytes(f.file(MELANIPPUS + "Danaus_melanippus_03.jpg"))).isEqualTo(mine);
        everyRowHasItsFile();
    }

    @Test
    void twoGeneraThatSwapNamesMoveEverythingWithoutAPause() throws Exception {
        Photo chrysippus = f.add("A5");
        Photo limniace = f.add("A8");

        Update done = applied(v2().rename("A4", "Tirumala").rename("A7", "Danaus").build());

        assertThat(done.state()).isEqualTo("done");
        assertThat(f.rows("SELECT id, rel_path FROM image ORDER BY id")).containsExactly(
                chrysippus.id() + "|" + DANAINI + "Tirumala/Danaus_chrysippus_[A5]/Danaus_chrysippus_01.jpg",
                limniace.id() + "|" + DANAINI + "Danaus/Tirumala_limniace_[A8]/Tirumala_limniace_01.jpg");
        assertThat(done.result().keptFolders()).isEmpty();
        everyRowHasItsFile();
    }

    @Test
    void aFolderLeftWithOnlyAFileBrowsersClutterIsRemovedAndNotListed() throws Exception {
        f.add("A6", 2);
        f.add("A14");
        Files.writeString(f.file(GENUTIA + ".DS_Store"), "finder");
        Files.writeString(f.file(GENUTIA + "._Danaus_genutia_01.jpg"), "appledouble");
        Files.writeString(f.file(GENUTIA + "Thumbs.db"), "explorer");
        String clytia = "images/Papilionidae/Papilioninae/Papilio/Papilio_clytia_[A14]/";
        Files.writeString(f.file(clytia + "notes.txt"), "seen at Tamhini");
        Files.writeString(f.file(clytia + "desktop.ini"), "explorer");

        Update done = applied(v2().rename("A6", "Danaus melanippus").add("A15", "A11", "species", "Papilio agestor", null)
                .merge("A15", "A14").build());

        assertThat(f.file(GENUTIA)).doesNotExist();
        assertThat(done.result().keptFolders()).containsExactly(clytia);
        assertThat(f.file(clytia + "notes.txt")).hasContent("seen at Tamhini");
        assertThat(f.file(clytia + "desktop.ini")).as("only removed with a folder that holds nothing else").exists();
    }
}
