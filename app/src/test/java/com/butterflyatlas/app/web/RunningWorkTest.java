package com.butterflyatlas.app.web;

import com.butterflyatlas.app.photos.ConsumerRunner;
import com.butterflyatlas.app.testing.TestServices;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/** The work that refuses a change of library folder, named in plain words (change-library-folder spec §4). */
class RunningWorkTest {

    @TempDir
    Path home;

    private boolean export;
    private boolean backup;
    private boolean filing;
    private boolean taxonomy;
    private String processing;
    private boolean startup;

    private Optional<String> now() {
        Supplier<Optional<String>> running = RunningWork.of(() -> export, () -> backup, () -> filing, () -> taxonomy,
                () -> processing, () -> startup);
        return running.get();
    }

    @Test
    void eachKindOfRunningWorkIsNamed() {
        assertThat(now()).isEmpty();
        startup = true;
        assertThat(now()).contains(RunningWork.STARTUP);
        processing = ConsumerRunner.DETAILS;
        assertThat(now()).contains(RunningWork.DETAILS);
        processing = ConsumerRunner.PLACES;
        assertThat(now()).contains(RunningWork.PLACES);
        taxonomy = true;
        assertThat(now()).contains(RunningWork.TAXONOMY);
        filing = true;
        assertThat(now()).contains(RunningWork.FILING);
        backup = true;
        assertThat(now()).contains(RunningWork.BACKUP);
        export = true;
        assertThat(now()).contains("An export is running. Pause it or wait for it to finish, then try again.");
    }

    @Test
    void anOpenLibraryWithNothingRunningRefusesNothing() {
        AtlasServices services = TestServices.configured(home);
        try {
            assertThat(RunningWork.of(services, () -> false).get()).isEmpty();
            assertThat(RunningWork.of(services, () -> true).get()).contains(RunningWork.STARTUP);
        } finally {
            services.exports().close();
            services.thumbnails().close();
        }
    }
}
