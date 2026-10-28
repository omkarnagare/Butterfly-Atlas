package com.butterflyatlas.app;

import com.butterflyatlas.app.testing.TestBundles;
import com.butterflyatlas.app.testing.TestServices;
import com.butterflyatlas.app.updates.Updates;
import com.butterflyatlas.app.web.AtlasServices;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** When the library's opening fails part-way, the startup's hold is released all the same (review J21). */
class MainStartupTest {

    @TempDir
    Path home;

    @Test
    void aFailingOpeningStillSettlesTheStartupFiles() {
        AtlasServices services = TestServices.configured(home);
        try {
            Updates updates = new Updates(() -> {
                throw new IllegalStateException("no library");
            }, TestBundles.fixture(), services.imports(), services.exports(), services.backups(), TestBundles.CLOCK);
            List<String> seen = new ArrayList<>();

            assertThatThrownBy(() -> Main.opened(updates, services.imports(), services.exports(), () -> seen.add("work"),
                    () -> seen.add("settled"))).hasMessage("no library");

            assertThat(seen).containsExactly("settled");
        } finally {
            services.imports().close();
            services.exports().close();
            services.thumbnails().close();
        }
    }

    @Test
    void anOpeningThatWorksStartsTheWorkAndLeavesSettlingToIt() {
        AtlasServices services = TestServices.configured(home);
        try {
            List<String> seen = new ArrayList<>();

            Main.opened(services.updates(), services.imports(), services.exports(), () -> seen.add("work"),
                    () -> seen.add("settled"));

            assertThat(seen).containsExactly("work");
        } finally {
            services.imports().close();
            services.exports().close();
            services.thumbnails().close();
        }
    }

    @Test
    void thePlaceNoteIsReadBeforeTheStartupWorkCanFinishItsPlacesStep() {
        com.butterflyatlas.app.library.Library library = com.butterflyatlas.app.testing.TestBundles.fixtureLibrary(
                home.resolve("Atlas"));
        TestBundles.addImage(library.database(), "A5"); // not checked yet: the re-check is not over by itself
        com.butterflyatlas.app.library.Library stepped = com.butterflyatlas.app.library.Library.open(library.folder(),
                TestBundles.fixture(), com.butterflyatlas.app.testing.UpdatePlaces.bundles(
                        com.butterflyatlas.app.testing.UpdatePlaces.fixture(),
                        com.butterflyatlas.app.testing.UpdatePlaces.fixtureNext()), TestBundles.CLOCK);
        com.butterflyatlas.app.placeupdates.PlaceNotes notes = new com.butterflyatlas.app.placeupdates.PlaceNotes(
                () -> java.util.Optional.of(stepped.database()), 2, 3, TestBundles.CLOCK);

        Main.placeNoteFirst(notes, notes::placesFinished); // a catch-up that is over at once

        assertThat(notes.status().pending()).isEqualTo(1);
        assertThat(notes.status().rechecking()).as("this run's places step is over").isFalse();
    }
}
