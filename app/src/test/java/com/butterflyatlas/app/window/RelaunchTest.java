package com.butterflyatlas.app.window;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** How the app reopens itself after a library change (change-library-folder contract §1). */
class RelaunchTest {

    @TempDir
    Path folder;

    @Test
    void theInstalledAppIsStartedAgainByItsOwnProgramWithNoArguments() throws Exception {
        Path program = Files.createFile(folder.resolve("Butterfly Atlas"));

        Relaunch.Plan plan = Relaunch.plan(program.toString());

        assertThat(plan.restarts()).isTrue();
        assertThat(plan.command()).isEqualTo(List.of(program.toString()));
        assertThat(plan.why()).isNull();
    }

    @Test
    void withoutTheInstalledAppsProgramItQuitsAndAsksAndSaysWhy() {
        for (String missing : new String[] {null, "", "  ", folder.resolve("gone").toString(), "relative/program"}) {
            Relaunch.Plan plan = Relaunch.plan(missing);

            assertThat(plan.restarts()).as("%s", missing).isFalse();
            assertThat(plan.command()).as("%s", missing).isNull();
            assertThat(plan.why()).as("%s", missing).isEqualTo(Relaunch.NOT_INSTALLED);
        }
    }

    @Test
    void theReasonsAreForTheUserNotTheLog() {
        assertThat(Relaunch.NOT_INSTALLED).doesNotContain("jpackage", "property", "null");
        assertThat(Relaunch.NOT_STARTED).startsWith("Butterfly Atlas couldn't reopen itself.");
    }
}
