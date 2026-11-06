package com.butterflyatlas.app;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Where JNA unpacks its native library (contract §3): the installed app ships it in {@code native/} and names it with
 * {@code jna.boot.library.path}, so nothing is unpacked; any other run unpacks it into {@code <config>/native}, never the
 * shared temp folder.
 */
class MainJnaTest {

    @TempDir
    Path config;

    @Test
    void aRunWithoutTheShippedLibraryUnpacksItInTheConfigFolder() {
        Map<String, String> properties = new HashMap<>();

        assertThat(Main.jnaTmpdir(properties::get, config)).contains(config.resolve("native"));
    }

    @Test
    void theInstalledAppWhichNamesItsOwnLibraryUnpacksNothing() {
        Map<String, String> properties = Map.of("jna.boot.library.path", "/Applications/Butterfly Atlas.app/Contents/app/native");

        assertThat(Main.jnaTmpdir(properties::get, config)).isEmpty();
    }

    @Test
    void aBlankLibraryPathCountsAsNone() {
        Map<String, String> properties = Map.of("jna.boot.library.path", " ");

        assertThat(Main.jnaTmpdir(properties::get, config)).contains(config.resolve("native"));
    }

    @Test
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "POSIX permissions")
    void theFolderIsMadeOwnerOnlyAndThePropertySet() throws Exception {
        Map<String, String> properties = new HashMap<>();

        Main.useJnaTmpdir(properties::get, properties::put, config);

        Path folder = config.resolve("native");
        assertThat(properties).containsEntry("jna.tmpdir", folder.toString());
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(folder))).isEqualTo(OwnerOnly.FOLDER);
    }

    @Test
    void nothingIsSetForTheInstalledApp() {
        Map<String, String> properties = new HashMap<>(Map.of("jna.boot.library.path", "C:\\Butterfly_Atlas\\app\\native"));

        Main.useJnaTmpdir(properties::get, properties::put, config);

        assertThat(properties).doesNotContainKey("jna.tmpdir");
        assertThat(config.resolve("native")).doesNotExist();
        assertThat(Optional.ofNullable(properties.get("jna.boot.library.path"))).isPresent();
    }
}
