package com.butterflyatlas.app.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class AppDirsTest {

    @TempDir
    Path home;

    @Test
    void usesApplicationSupportOnMac() {
        assertThat(AppDirs.configDir("Mac OS X", Map.of(), home))
                .isEqualTo(home.resolve("Library").resolve("Application Support").resolve("Butterfly Atlas"));
    }

    @Test
    void usesAppDataOnWindows() {
        Path appData = home.resolve("Roaming");
        assertThat(AppDirs.configDir("Windows 11", Map.of("APPDATA", appData.toString()), home))
                .isEqualTo(appData.resolve("Butterfly Atlas"));
    }

    @Test
    void fallsBackToTheRoamingFolderWhenAppDataIsUnset() {
        assertThat(AppDirs.configDir("Windows 10", Map.of(), home))
                .isEqualTo(home.resolve("AppData").resolve("Roaming").resolve("Butterfly Atlas"));
    }

    @Test
    void usesXdgConfigHomeOnLinux() {
        Path xdg = home.resolve("xdg");
        assertThat(AppDirs.configDir("Linux", Map.of("XDG_CONFIG_HOME", xdg.toString()), home))
                .isEqualTo(xdg.resolve("butterfly-atlas"));
    }

    @Test
    void usesDotConfigOnLinuxByDefault() {
        assertThat(AppDirs.configDir("Linux", Map.of(), home)).isEqualTo(home.resolve(".config").resolve("butterfly-atlas"));
    }

    @Test
    void ignoresARelativeXdgConfigHome() {
        assertThat(AppDirs.configDir("Linux", Map.of("XDG_CONFIG_HOME", "relative/dir"), home))
                .isEqualTo(home.resolve(".config").resolve("butterfly-atlas"));
    }

    @Test
    void suggestsDocumentsButterflyAtlasForDataWithoutASpace() {
        assertThat(AppDirs.defaultDataFolder(home)).isEqualTo(home.resolve("Documents").resolve("Butterfly_Atlas"));
    }

    @Test
    void theConfigFolderIsCreatedOwnerOnlyAndAnOlderOneIsTightenedWithoutTouchingItsContents() throws Exception {
        assumeTrue(home.getFileSystem().supportedFileAttributeViews().contains("posix"));
        Path fresh = home.resolve("a").resolve("Butterfly Atlas");
        Path older = Files.createDirectories(home.resolve("older"));
        Files.setPosixFilePermissions(older, PosixFilePermissions.fromString("rwxr-xr-x"));
        Path inside = Files.writeString(older.resolve("config.json"), "{}");
        Files.setPosixFilePermissions(inside, PosixFilePermissions.fromString("rw-r--r--"));

        AppDirs.createConfigDir(fresh);
        AppDirs.createConfigDir(older);

        assertThat(Files.getPosixFilePermissions(fresh)).isEqualTo(PosixFilePermissions.fromString("rwx------"));
        assertThat(Files.getPosixFilePermissions(older)).isEqualTo(PosixFilePermissions.fromString("rwx------"));
        assertThat(Files.getPosixFilePermissions(inside)).isEqualTo(PosixFilePermissions.fromString("rw-r--r--"));
    }
}
